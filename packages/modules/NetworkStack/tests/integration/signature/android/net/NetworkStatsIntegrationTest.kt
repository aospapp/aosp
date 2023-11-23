/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package android.net

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.app.usage.NetworkStats
import android.app.usage.NetworkStats.Bucket.TAG_NONE
import android.app.usage.NetworkStatsManager
import android.net.ConnectivityManager.TYPE_TEST
import android.net.NetworkTemplate.MATCH_TEST
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.PacketBridge
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.TestDnsServer
import com.android.testutils.TestHttpServer
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_OK
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.Charset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class NetworkStatsIntegrationTest {
    private val INTERNAL_V6ADDR =
        LinkAddress(InetAddresses.parseNumericAddress("2001:db8::1234"), 64)
    private val EXTERNAL_V6ADDR =
        LinkAddress(InetAddresses.parseNumericAddress("2001:db8::5678"), 64)

    // Remote address, both the client and server will have a hallucination that
    // they are talking to this address.
    private val REMOTE_V6ADDR =
        LinkAddress(InetAddresses.parseNumericAddress("dead:beef::808:808"), 64)
    private val REMOTE_V4ADDR =
        LinkAddress(InetAddresses.parseNumericAddress("8.8.8.8"), 32)
    private val DEFAULT_BUFFER_SIZE = 1000
    private val CONNECTION_TIMEOUT_MILLIS = 15000
    private val TEST_DOWNLOAD_SIZE = 10000L
    private val TEST_UPLOAD_SIZE = 20000L
    private val HTTP_SERVER_NAME = "test.com"
    private val DNS_SERVER_PORT = 53
    private val TEST_TAG = 0xF00D

    // Set up the packet bridge with two IPv6 address only test networks.
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.getContext()
    private val packetBridge = runAsShell(MANAGE_TEST_NETWORKS) {
        PacketBridge(context, INTERNAL_V6ADDR, EXTERNAL_V6ADDR, REMOTE_V6ADDR.address)
    }.apply {
        start()
    }
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    // Set up DNS server for testing server and DNS64.
    private val fakeDns = TestDnsServer(
        packetBridge.externalNetwork, InetSocketAddress(EXTERNAL_V6ADDR.address, DNS_SERVER_PORT)
    ).apply {
        start()
        setAnswer(
            "ipv4only.arpa",
            listOf(IpPrefix(REMOTE_V6ADDR.address, REMOTE_V6ADDR.prefixLength).address)
        )
        setAnswer(HTTP_SERVER_NAME, listOf(REMOTE_V4ADDR.address))
    }

    // Start up test http server.
    private val httpServer = TestHttpServer(EXTERNAL_V6ADDR.address.hostAddress).apply {
        start()
    }

    @Before
    fun setUp() {
        assumeTrue(shouldRunTests())
    }

    // For networkstack tests, it is not guaranteed that the tethering module will be
    // updated at the same time. If the tethering module is not new enough, it may not contain
    // the necessary abilities to run these tests. For example, The tests depends on test
    // network stats being counted, which can only be achieved when they are marked as TYPE_TEST.
    // If the tethering module does not support TYPE_TEST stats, then these tests will need
    // to be skipped.
    fun shouldRunTests() = cm.getNetworkInfo(packetBridge.internalNetwork).type == TYPE_TEST

    @After
    fun tearDown() {
        packetBridge.stop()
        fakeDns.stop()
        httpServer.stop()
    }

    private fun waitFor464XlatReady(network: Network): String {
        val iface = cm.getLinkProperties(network).interfaceName

        // Make a network request to listen to the specific test network.
        val nr = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
            .setNetworkSpecifier(TestNetworkSpecifier(iface))
            .build()
        val testCb = TestableNetworkCallback()
        cm.registerNetworkCallback(nr, testCb)
        // Wait for the stacked address to be available.
        testCb.eventuallyExpect<LinkPropertiesChanged> {
            it.lp.stackedLinks?.getOrNull(0)?.linkAddresses?.getOrNull(0) != null
        }
        return iface
    }

    /**
     * Verify data usage download stats with test 464xlat networks.
     *
     * This test starts two test networks and binds them together, the internal one is for the
     * client to make http traffic on the test network, and the external one is for the mocked
     * http and dns server to bind to and provide responses.
     *
     * After Clat setup, the client will use clat v4 address to send packets to the mocked
     * server v4 address, which will be translated into a v6 packet by the clat daemon with
     * NAT64 prefix learned from the mocked DNS64 response. And send to the interface.
     *
     * While the packets are being forwarded to the external interface, the servers will see
     * the packets originated from the mocked v6 address, and destined to a local v6 address.
     */
    @Test
    fun test464XlatTcpStats() {
        // Wait for 464Xlat to be ready.
        val internalInterfaceName = waitFor464XlatReady(packetBridge.internalNetwork)

        val (_, rxBytesBeforeTest) = getTotalTxRxBytes(internalInterfaceName)
        val (_, rxTaggedBytesBeforeTest) = getTaggedTxRxBytes(internalInterfaceName, TEST_TAG)

        // Generate the download traffic.
        genHttpTraffic(packetBridge.internalNetwork, uploadSize = 0L, TEST_DOWNLOAD_SIZE)

        // In practice, for one way 10k download payload, the download usage is about
        // 11222~12880 bytes. And the upload usage is about 1279~1626 bytes, which is majorly
        // contributed by TCP ACK packets.
        val (txBytesAfterDownload, rxBytesAfterDownload) =
            getTotalTxRxBytes(internalInterfaceName)
        val (txTaggedBytesAfterDownload, rxTaggedBytesAfterDownload) = getTaggedTxRxBytes(
            internalInterfaceName,
            TEST_TAG
        )
        assertInRange(
            "Download size", internalInterfaceName,
            rxBytesAfterDownload - rxBytesBeforeTest,
            TEST_DOWNLOAD_SIZE, (TEST_DOWNLOAD_SIZE * 1.3).toLong()
        )
        // Increment of tagged data should be zero since no tagged traffic was generated.
        assertEquals(
            rxTaggedBytesBeforeTest,
            rxTaggedBytesAfterDownload,
            "Tagged download size of uid ${Process.myUid()} on $internalInterfaceName"
        )

        // Generate upload traffic with tag to verify tagged data accounting as well.
        genHttpTrafficWithTag(
            packetBridge.internalNetwork,
            TEST_UPLOAD_SIZE,
            downloadSize = 0L,
            TEST_TAG
        )

        // Verify upload data usage accounting.
        val (txBytesAfterUpload, _) = getTotalTxRxBytes(internalInterfaceName)
        val (txTaggedBytesAfterUpload, _) = getTaggedTxRxBytes(internalInterfaceName, TEST_TAG)
        assertInRange(
            "Upload size", internalInterfaceName,
            txBytesAfterUpload - txBytesAfterDownload,
            TEST_UPLOAD_SIZE, (TEST_UPLOAD_SIZE * 1.3).toLong()
        )
        assertInRange(
            "Tagged upload size of uid ${Process.myUid()}",
            internalInterfaceName,
            txTaggedBytesAfterUpload - txTaggedBytesAfterDownload,
            TEST_UPLOAD_SIZE,
            (TEST_UPLOAD_SIZE * 1.3).toLong()
        )
    }

    private fun genHttpTraffic(network: Network, uploadSize: Long, downloadSize: Long) =
        genHttpTrafficWithTag(network, uploadSize, downloadSize, NetworkStats.Bucket.TAG_NONE)

    private fun genHttpTrafficWithTag(
        network: Network,
        uploadSize: Long,
        downloadSize: Long,
        tag: Int
    ) {
        val path = "/test_upload_download"
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)

        httpServer.addResponse(
            TestHttpServer.Request(path, NanoHTTPD.Method.POST), NanoHTTPD.Response.Status.OK,
            content = getRandomString(downloadSize)
        )
        var httpConnection: HttpURLConnection? = null
        try {
            TrafficStats.setThreadStatsTag(tag)
            val spec = "http://$HTTP_SERVER_NAME:${httpServer.listeningPort}$path"
            val url = URL(spec)
            httpConnection = network.openConnection(url) as HttpURLConnection
            httpConnection.connectTimeout = CONNECTION_TIMEOUT_MILLIS
            httpConnection.requestMethod = "POST"
            httpConnection.doOutput = true
            // Tell the server that the response should not be compressed. Otherwise, the data usage
            // accounted will be less than expected.
            httpConnection.setRequestProperty("Accept-Encoding", "identity")
            // Tell the server that to close connection after this request, this is needed to
            // prevent from reusing the same socket that has different tagging requirement.
            httpConnection.setRequestProperty("Connection", "close")

            // Send http body.
            val outputStream = BufferedOutputStream(httpConnection.outputStream)
            outputStream.write(getRandomString(uploadSize).toByteArray(Charset.forName("UTF-8")))
            outputStream.close()
            assertEquals(HTTP_OK, httpConnection.responseCode)

            // Receive response from the server.
            val inputStream = BufferedInputStream(httpConnection.getInputStream())
            var total = 0L
            while (true) {
                val count = inputStream.read(buf)
                if (count == -1) break // End-of-Stream
                total += count
            }
            assertEquals(downloadSize, total)
        } finally {
            httpConnection?.inputStream?.close()
            TrafficStats.clearThreadStatsTag()
        }
    }

    private fun getTotalTxRxBytes(iface: String): Pair<Long, Long> {
        return getNetworkStatsThat(iface, TAG_NONE) { nsm, template ->
            nsm.querySummary(template, Long.MIN_VALUE, Long.MAX_VALUE)
        }
    }

    private fun getTaggedTxRxBytes(iface: String, tag: Int): Pair<Long, Long> {
        return getNetworkStatsThat(iface, tag) { nsm, template ->
            nsm.queryTaggedSummary(template, Long.MIN_VALUE, Long.MAX_VALUE)
        }
    }

    private fun getNetworkStatsThat(
        iface: String,
        tag: Int,
        queryApi: (nsm: NetworkStatsManager, template: NetworkTemplate) -> NetworkStats
    ): Pair<Long, Long> {
        val nsm = context.getSystemService(NetworkStatsManager::class.java)
        nsm.forceUpdate()
        val testTemplate = NetworkTemplate.Builder(MATCH_TEST)
            .setWifiNetworkKeys(setOf(iface)).build()
        val stats = queryApi.invoke(nsm, testTemplate)
        val recycled = NetworkStats.Bucket()
        var rx = 0L
        var tx = 0L
        while (stats.hasNextBucket()) {
            stats.getNextBucket(recycled)
            if (recycled.uid != Process.myUid() || recycled.tag != tag) continue
            rx += recycled.rxBytes
            tx += recycled.txBytes
        }
        return tx to rx
    }

    /** Verify the given value is in range [lower, upper]  */
    private fun assertInRange(tag: String, iface: String, value: Long, lower: Long, upper: Long) =
        assertTrue(
            value in lower..upper,
            "$tag on $iface: $value is not within range [$lower, $upper]"
        )

    fun getRandomString(length: Long): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }
}
