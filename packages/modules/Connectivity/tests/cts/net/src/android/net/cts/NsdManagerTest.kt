/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * limitations under the License.
 */
package android.net.cts

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.app.compat.CompatChanges
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.InetAddresses.parseNumericAddress
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkRequest
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.TestNetworkSpecifier
import android.net.connectivity.ConnectivityCompatChanges
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStarted
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStopped
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.ServiceFound
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.ServiceLost
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.StartDiscoveryFailed
import android.net.cts.NsdManagerTest.NsdDiscoveryRecord.DiscoveryEvent.StopDiscoveryFailed
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.RegistrationFailed
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.ServiceRegistered
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.ServiceUnregistered
import android.net.cts.NsdManagerTest.NsdRegistrationRecord.RegistrationEvent.UnregistrationFailed
import android.net.cts.NsdManagerTest.NsdResolveRecord.ResolveEvent.ResolutionStopped
import android.net.cts.NsdManagerTest.NsdResolveRecord.ResolveEvent.ResolveFailed
import android.net.cts.NsdManagerTest.NsdResolveRecord.ResolveEvent.ServiceResolved
import android.net.cts.NsdManagerTest.NsdResolveRecord.ResolveEvent.StopResolutionFailed
import android.net.cts.NsdManagerTest.NsdServiceInfoCallbackRecord.ServiceInfoCallbackEvent.RegisterCallbackFailed
import android.net.cts.NsdManagerTest.NsdServiceInfoCallbackRecord.ServiceInfoCallbackEvent.ServiceUpdated
import android.net.cts.NsdManagerTest.NsdServiceInfoCallbackRecord.ServiceInfoCallbackEvent.ServiceUpdatedLost
import android.net.cts.NsdManagerTest.NsdServiceInfoCallbackRecord.ServiceInfoCallbackEvent.UnregisterCallbackSucceeded
import android.net.cts.util.CtsNetUtils
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdManager.RegistrationListener
import android.net.nsd.NsdManager.ResolveListener
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process.myTid
import android.platform.test.annotations.AppModeFull
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.AF_INET6
import android.system.OsConstants.EADDRNOTAVAIL
import android.system.OsConstants.ENETUNREACH
import android.system.OsConstants.IPPROTO_UDP
import android.system.OsConstants.SOCK_DGRAM
import android.util.Log
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.PollingCheck
import com.android.compatibility.common.util.PropertyUtil
import com.android.modules.utils.build.SdkLevel.isAtLeastU
import com.android.net.module.util.ArrayTrackRecord
import com.android.net.module.util.TrackRecord
import com.android.networkstack.apishim.NsdShimImpl
import com.android.networkstack.apishim.common.NsdShim
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.TestableNetworkAgent
import com.android.testutils.TestableNetworkAgent.CallbackEntry.OnNetworkCreated
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.filters.CtsNetTestCasesMaxTargetSdk30
import com.android.testutils.filters.CtsNetTestCasesMaxTargetSdk33
import com.android.testutils.runAsShell
import com.android.testutils.tryTest
import com.android.testutils.waitForIdle
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.concurrent.Executor
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "NsdManagerTest"
private const val TIMEOUT_MS = 2000L
private const val NO_CALLBACK_TIMEOUT_MS = 200L
// Registration may take a long time if there are devices with the same hostname on the network,
// as the device needs to try another name and probe again. This is especially true since when using
// mdnsresponder the usual hostname is "Android", and on conflict "Android-2", "Android-3", ... are
// tried sequentially
private const val REGISTRATION_TIMEOUT_MS = 10_000L
private const val DBG = false
private const val TEST_PORT = 12345

private val nsdShim = NsdShimImpl.newInstance()

@AppModeFull(reason = "Socket cannot bind in instant app mode")
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@ConnectivityModuleTest
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
class NsdManagerTest {
    // Rule used to filter CtsNetTestCasesMaxTargetSdkXX
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val nsdManager by lazy { context.getSystemService(NsdManager::class.java) }

    private val cm by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val serviceName = "NsdTest%09d".format(Random().nextInt(1_000_000_000))
    private val serviceType = "_nmt%09d._tcp".format(Random().nextInt(1_000_000_000))
    private val handlerThread = HandlerThread(NsdManagerTest::class.java.simpleName)
    private val ctsNetUtils by lazy{ CtsNetUtils(context) }

    private lateinit var testNetwork1: TestTapNetwork
    private lateinit var testNetwork2: TestTapNetwork

    private class TestTapNetwork(
        val iface: TestNetworkInterface,
        val requestCb: NetworkCallback,
        val agent: TestableNetworkAgent,
        val network: Network
    ) {
        fun close(cm: ConnectivityManager) {
            cm.unregisterNetworkCallback(requestCb)
            agent.unregister()
            iface.fileDescriptor.close()
            agent.waitForIdle(TIMEOUT_MS)
        }
    }

    private interface NsdEvent
    private open class NsdRecord<T : NsdEvent> private constructor(
        private val history: ArrayTrackRecord<T>,
        private val expectedThreadId: Int? = null
    ) : TrackRecord<T> by history {
        constructor(expectedThreadId: Int? = null) : this(ArrayTrackRecord(), expectedThreadId)

        val nextEvents = history.newReadHead()

        override fun add(e: T): Boolean {
            if (expectedThreadId != null) {
                assertEquals(expectedThreadId, myTid(), "Callback is running on the wrong thread")
            }
            return history.add(e)
        }

        inline fun <reified V : NsdEvent> expectCallbackEventually(
            timeoutMs: Long = TIMEOUT_MS,
            crossinline predicate: (V) -> Boolean = { true }
        ): V = nextEvents.poll(timeoutMs) { e -> e is V && predicate(e) } as V?
                ?: fail("Callback for ${V::class.java.simpleName} not seen after $timeoutMs ms")

        inline fun <reified V : NsdEvent> expectCallback(timeoutMs: Long = TIMEOUT_MS): V {
            val nextEvent = nextEvents.poll(timeoutMs)
            assertNotNull(nextEvent, "No callback received after $timeoutMs ms, expected " +
                    "${V::class.java.simpleName}")
            assertTrue(nextEvent is V, "Expected ${V::class.java.simpleName} but got " +
                    nextEvent.javaClass.simpleName)
            return nextEvent
        }

        inline fun assertNoCallback(timeoutMs: Long = NO_CALLBACK_TIMEOUT_MS) {
            val cb = nextEvents.poll(timeoutMs)
            assertNull(cb, "Expected no callback but got $cb")
        }
    }

    private class NsdRegistrationRecord(expectedThreadId: Int? = null) : RegistrationListener,
            NsdRecord<NsdRegistrationRecord.RegistrationEvent>(expectedThreadId) {
        sealed class RegistrationEvent : NsdEvent {
            abstract val serviceInfo: NsdServiceInfo

            data class RegistrationFailed(
                override val serviceInfo: NsdServiceInfo,
                val errorCode: Int
            ) : RegistrationEvent()

            data class UnregistrationFailed(
                override val serviceInfo: NsdServiceInfo,
                val errorCode: Int
            ) : RegistrationEvent()

            data class ServiceRegistered(override val serviceInfo: NsdServiceInfo) :
                    RegistrationEvent()
            data class ServiceUnregistered(override val serviceInfo: NsdServiceInfo) :
                    RegistrationEvent()
        }

        override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) {
            add(RegistrationFailed(si, err))
        }

        override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) {
            add(UnregistrationFailed(si, err))
        }

        override fun onServiceRegistered(si: NsdServiceInfo) {
            add(ServiceRegistered(si))
        }

        override fun onServiceUnregistered(si: NsdServiceInfo) {
            add(ServiceUnregistered(si))
        }
    }

    private class NsdDiscoveryRecord(expectedThreadId: Int? = null) :
            DiscoveryListener, NsdRecord<NsdDiscoveryRecord.DiscoveryEvent>(expectedThreadId) {
        sealed class DiscoveryEvent : NsdEvent {
            data class StartDiscoveryFailed(val serviceType: String, val errorCode: Int) :
                    DiscoveryEvent()

            data class StopDiscoveryFailed(val serviceType: String, val errorCode: Int) :
                    DiscoveryEvent()

            data class DiscoveryStarted(val serviceType: String) : DiscoveryEvent()
            data class DiscoveryStopped(val serviceType: String) : DiscoveryEvent()
            data class ServiceFound(val serviceInfo: NsdServiceInfo) : DiscoveryEvent()
            data class ServiceLost(val serviceInfo: NsdServiceInfo) : DiscoveryEvent()
        }

        override fun onStartDiscoveryFailed(serviceType: String, err: Int) {
            add(StartDiscoveryFailed(serviceType, err))
        }

        override fun onStopDiscoveryFailed(serviceType: String, err: Int) {
            add(StopDiscoveryFailed(serviceType, err))
        }

        override fun onDiscoveryStarted(serviceType: String) {
            add(DiscoveryStarted(serviceType))
        }

        override fun onDiscoveryStopped(serviceType: String) {
            add(DiscoveryStopped(serviceType))
        }

        override fun onServiceFound(si: NsdServiceInfo) {
            add(ServiceFound(si))
        }

        override fun onServiceLost(si: NsdServiceInfo) {
            add(ServiceLost(si))
        }

        fun waitForServiceDiscovered(
            serviceName: String,
            serviceType: String,
            expectedNetwork: Network? = null
        ): NsdServiceInfo {
            val serviceFound = expectCallbackEventually<ServiceFound> {
                it.serviceInfo.serviceName == serviceName &&
                        (expectedNetwork == null ||
                                expectedNetwork == nsdShim.getNetwork(it.serviceInfo))
            }.serviceInfo
            // Discovered service types have a dot at the end
            assertEquals("$serviceType.", serviceFound.serviceType)
            return serviceFound
        }
    }

    private class NsdResolveRecord : ResolveListener,
            NsdRecord<NsdResolveRecord.ResolveEvent>() {
        sealed class ResolveEvent : NsdEvent {
            data class ResolveFailed(val serviceInfo: NsdServiceInfo, val errorCode: Int) :
                    ResolveEvent()

            data class ServiceResolved(val serviceInfo: NsdServiceInfo) : ResolveEvent()
            data class ResolutionStopped(val serviceInfo: NsdServiceInfo) : ResolveEvent()
            data class StopResolutionFailed(val serviceInfo: NsdServiceInfo, val errorCode: Int) :
                    ResolveEvent()
        }

        override fun onResolveFailed(si: NsdServiceInfo, err: Int) {
            add(ResolveFailed(si, err))
        }

        override fun onServiceResolved(si: NsdServiceInfo) {
            add(ServiceResolved(si))
        }

        override fun onResolutionStopped(si: NsdServiceInfo) {
            add(ResolutionStopped(si))
        }

        override fun onStopResolutionFailed(si: NsdServiceInfo, err: Int) {
            super.onStopResolutionFailed(si, err)
            add(StopResolutionFailed(si, err))
        }
    }

    private class NsdServiceInfoCallbackRecord : NsdShim.ServiceInfoCallbackShim,
            NsdRecord<NsdServiceInfoCallbackRecord.ServiceInfoCallbackEvent>() {
        sealed class ServiceInfoCallbackEvent : NsdEvent {
            data class RegisterCallbackFailed(val errorCode: Int) : ServiceInfoCallbackEvent()
            data class ServiceUpdated(val serviceInfo: NsdServiceInfo) : ServiceInfoCallbackEvent()
            object ServiceUpdatedLost : ServiceInfoCallbackEvent()
            object UnregisterCallbackSucceeded : ServiceInfoCallbackEvent()
        }

        override fun onServiceInfoCallbackRegistrationFailed(err: Int) {
            add(RegisterCallbackFailed(err))
        }

        override fun onServiceUpdated(si: NsdServiceInfo) {
            add(ServiceUpdated(si))
        }

        override fun onServiceLost() {
            add(ServiceUpdatedLost)
        }

        override fun onServiceInfoCallbackUnregistered() {
            add(UnregisterCallbackSucceeded)
        }
    }

    @Before
    fun setUp() {
        handlerThread.start()

        if (TestUtils.shouldTestTApis()) {
            runAsShell(MANAGE_TEST_NETWORKS) {
                testNetwork1 = createTestNetwork()
                testNetwork2 = createTestNetwork()
            }
        }
    }

    private fun createTestNetwork(): TestTapNetwork {
        val tnm = context.getSystemService(TestNetworkManager::class.java)
        val iface = tnm.createTapInterface()
        val cb = TestableNetworkCallback()
        val testNetworkSpecifier = TestNetworkSpecifier(iface.interfaceName)
        cm.requestNetwork(NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_TRUSTED)
                .addTransportType(TRANSPORT_TEST)
                .setNetworkSpecifier(testNetworkSpecifier)
                .build(), cb)
        val agent = registerTestNetworkAgent(iface.interfaceName)
        val network = agent.network ?: fail("Registered agent should have a network")

        cb.eventuallyExpect<LinkPropertiesChanged>(TIMEOUT_MS) {
            it.lp.linkAddresses.isNotEmpty()
        }

        // The network has no INTERNET capability, so will be marked validated immediately
        // It does not matter if validated capabilities come before/after the link addresses change
        cb.eventuallyExpect<CapabilitiesChanged>(TIMEOUT_MS, from = 0) {
            it.caps.hasCapability(NET_CAPABILITY_VALIDATED)
        }
        return TestTapNetwork(iface, cb, agent, network)
    }

    private fun registerTestNetworkAgent(ifaceName: String): TestableNetworkAgent {
        val lp = LinkProperties().apply {
            interfaceName = ifaceName
        }

        val agent = TestableNetworkAgent(context, handlerThread.looper,
                NetworkCapabilities().apply {
                    removeCapability(NET_CAPABILITY_TRUSTED)
                    addTransportType(TRANSPORT_TEST)
                    setNetworkSpecifier(TestNetworkSpecifier(ifaceName))
                }, lp, NetworkAgentConfig.Builder().build())
        val network = agent.register()
        agent.markConnected()
        agent.expectCallback<OnNetworkCreated>()

        // Wait until the link-local address can be used. Address flags are not available without
        // elevated permissions, so check that bindSocket works.
        PollingCheck.check("No usable v6 address on interface after $TIMEOUT_MS ms", TIMEOUT_MS) {
            // To avoid race condition between socket connection succeeding and interface returning
            // a non-empty address list. Verify that interface returns a non-empty list, before
            // trying the socket connection.
            if (NetworkInterface.getByName(ifaceName).interfaceAddresses.isEmpty()) {
                return@check false
            }

            val sock = Os.socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP)
            tryTest {
                network.bindSocket(sock)
                Os.connect(sock, parseNumericAddress("ff02::fb%$ifaceName"), 12345)
                true
            }.catch<ErrnoException> {
                if (it.errno != ENETUNREACH && it.errno != EADDRNOTAVAIL) {
                    throw it
                }
                false
            } cleanup {
                Os.close(sock)
            }
        }

        lp.setLinkAddresses(NetworkInterface.getByName(ifaceName).interfaceAddresses.map {
            LinkAddress(it.address, it.networkPrefixLength.toInt())
        })
        agent.sendLinkProperties(lp)
        return agent
    }

    private fun makeTestServiceInfo(network: Network? = null) = NsdServiceInfo().also {
        it.serviceType = serviceType
        it.serviceName = serviceName
        it.network = network
        it.port = TEST_PORT
    }

    @After
    fun tearDown() {
        if (TestUtils.shouldTestTApis()) {
            runAsShell(MANAGE_TEST_NETWORKS) {
                // Avoid throwing here if initializing failed in setUp
                if (this::testNetwork1.isInitialized) testNetwork1.close(cm)
                if (this::testNetwork2.isInitialized) testNetwork2.close(cm)
            }
        }
        handlerThread.waitForIdle(TIMEOUT_MS)
        handlerThread.quitSafely()
        handlerThread.join()
    }

    @Test
    fun testNsdManager() {
        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = serviceName
        // Test binary data with various bytes
        val testByteArray = byteArrayOf(-128, 127, 2, 1, 0, 1, 2)
        // Test string data with 256 characters (25 blocks of 10 characters + 6)
        val string256 = "1_________2_________3_________4_________5_________6_________" +
                "7_________8_________9_________10________11________12________13________" +
                "14________15________16________17________18________19________20________" +
                "21________22________23________24________25________123456"

        // Illegal attributes
        listOf(
                Triple(null, null, "null key"),
                Triple("", null, "empty key"),
                Triple(string256, null, "key with 256 characters"),
                Triple("key", string256.substring(3),
                        "key+value combination with more than 255 characters"),
                Triple("key", string256.substring(4), "key+value combination with 255 characters"),
                Triple("\u0019", null, "key with invalid character"),
                Triple("=", null, "key with invalid character"),
                Triple("\u007f", null, "key with invalid character")
        ).forEach {
            assertFailsWith<IllegalArgumentException>(
                    "Setting invalid ${it.third} unexpectedly succeeded") {
                si.setAttribute(it.first, it.second)
            }
        }

        // Allowed attributes
        si.setAttribute("booleanAttr", null as String?)
        si.setAttribute("keyValueAttr", "value")
        si.setAttribute("keyEqualsAttr", "=")
        si.setAttribute(" whiteSpaceKeyValueAttr ", " value ")
        si.setAttribute("binaryDataAttr", testByteArray)
        si.setAttribute("nullBinaryDataAttr", null as ByteArray?)
        si.setAttribute("emptyBinaryDataAttr", byteArrayOf())
        si.setAttribute("longkey", string256.substring(9))
        val socket = ServerSocket(0)
        val localPort = socket.localPort
        si.port = localPort
        if (DBG) Log.d(TAG, "Port = $localPort")

        val registrationRecord = NsdRegistrationRecord()
        // Test registering without an Executor
        nsdManager.registerService(si, NsdManager.PROTOCOL_DNS_SD, registrationRecord)
        val registeredInfo = registrationRecord.expectCallback<ServiceRegistered>(
                REGISTRATION_TIMEOUT_MS).serviceInfo

        // Only service name is included in ServiceRegistered callbacks
        assertNull(registeredInfo.serviceType)
        assertEquals(si.serviceName, registeredInfo.serviceName)

        val discoveryRecord = NsdDiscoveryRecord()
        // Test discovering without an Executor
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)

        // Expect discovery started
        discoveryRecord.expectCallback<DiscoveryStarted>()

        // Expect a service record to be discovered
        val foundInfo = discoveryRecord.waitForServiceDiscovered(
                registeredInfo.serviceName, serviceType)

        // Test resolving without an Executor
        val resolveRecord = NsdResolveRecord()
        nsdManager.resolveService(foundInfo, resolveRecord)
        val resolvedService = resolveRecord.expectCallback<ServiceResolved>().serviceInfo
        assertEquals(".$serviceType", resolvedService.serviceType)
        assertEquals(registeredInfo.serviceName, resolvedService.serviceName)

        // Check Txt attributes
        assertEquals(8, resolvedService.attributes.size)
        assertTrue(resolvedService.attributes.containsKey("booleanAttr"))
        assertNull(resolvedService.attributes["booleanAttr"])
        assertEquals("value", resolvedService.attributes["keyValueAttr"].utf8ToString())
        assertEquals("=", resolvedService.attributes["keyEqualsAttr"].utf8ToString())
        assertEquals(" value ",
                resolvedService.attributes[" whiteSpaceKeyValueAttr "].utf8ToString())
        assertEquals(string256.substring(9), resolvedService.attributes["longkey"].utf8ToString())
        assertArrayEquals(testByteArray, resolvedService.attributes["binaryDataAttr"])
        assertTrue(resolvedService.attributes.containsKey("nullBinaryDataAttr"))
        assertNull(resolvedService.attributes["nullBinaryDataAttr"])
        assertTrue(resolvedService.attributes.containsKey("emptyBinaryDataAttr"))
        // TODO: change the check to target SDK U when this is what the code implements
        if (isAtLeastU()) {
            assertArrayEquals(byteArrayOf(), resolvedService.attributes["emptyBinaryDataAttr"])
        } else {
            assertNull(resolvedService.attributes["emptyBinaryDataAttr"])
        }
        assertEquals(localPort, resolvedService.port)

        // Unregister the service
        nsdManager.unregisterService(registrationRecord)
        registrationRecord.expectCallback<ServiceUnregistered>()

        // Expect a callback for service lost
        val lostCb = discoveryRecord.expectCallbackEventually<ServiceLost> {
            it.serviceInfo.serviceName == serviceName
        }
        // Lost service types have a dot at the end
        assertEquals("$serviceType.", lostCb.serviceInfo.serviceType)

        // Register service again to see if NsdManager can discover it
        val si2 = NsdServiceInfo()
        si2.serviceType = serviceType
        si2.serviceName = serviceName
        si2.port = localPort
        val registrationRecord2 = NsdRegistrationRecord()
        nsdManager.registerService(si2, NsdManager.PROTOCOL_DNS_SD, registrationRecord2)
        val registeredInfo2 = registrationRecord2.expectCallback<ServiceRegistered>(
                REGISTRATION_TIMEOUT_MS).serviceInfo

        // Expect a service record to be discovered (and filter the ones
        // that are unrelated to this test)
        val foundInfo2 = discoveryRecord.waitForServiceDiscovered(
                registeredInfo2.serviceName, serviceType)

        // Resolve the service
        val resolveRecord2 = NsdResolveRecord()
        nsdManager.resolveService(foundInfo2, resolveRecord2)
        val resolvedService2 = resolveRecord2.expectCallback<ServiceResolved>().serviceInfo

        // Check that the resolved service doesn't have any TXT records
        assertEquals(0, resolvedService2.attributes.size)

        nsdManager.stopServiceDiscovery(discoveryRecord)

        discoveryRecord.expectCallbackEventually<DiscoveryStopped>()

        nsdManager.unregisterService(registrationRecord2)
        registrationRecord2.expectCallback<ServiceUnregistered>()
    }

    @Test
    fun testNsdManager_DiscoverOnNetwork() {
        // This test requires shims supporting T+ APIs (discovering on specific network)
        assumeTrue(TestUtils.shouldTestTApis())

        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = this.serviceName
        si.port = 12345 // Test won't try to connect so port does not matter

        val registrationRecord = NsdRegistrationRecord()
        val registeredInfo = registerService(registrationRecord, si)

        tryTest {
            val discoveryRecord = NsdDiscoveryRecord()
            nsdShim.discoverServices(nsdManager, serviceType, NsdManager.PROTOCOL_DNS_SD,
                    testNetwork1.network, Executor { it.run() }, discoveryRecord)

            val foundInfo = discoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)
            assertEquals(testNetwork1.network, nsdShim.getNetwork(foundInfo))

            // Rewind to ensure the service is not found on the other interface
            discoveryRecord.nextEvents.rewind(0)
            assertNull(discoveryRecord.nextEvents.poll(timeoutMs = 100L) {
                it is ServiceFound &&
                        it.serviceInfo.serviceName == registeredInfo.serviceName &&
                        nsdShim.getNetwork(it.serviceInfo) != testNetwork1.network
            }, "The service should not be found on this network")
        } cleanup {
            nsdManager.unregisterService(registrationRecord)
        }
    }

    @Test
    fun testNsdManager_DiscoverWithNetworkRequest() {
        // This test requires shims supporting T+ APIs (discovering on network request)
        assumeTrue(TestUtils.shouldTestTApis())

        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = this.serviceName
        si.port = 12345 // Test won't try to connect so port does not matter

        val handler = Handler(handlerThread.looper)
        val executor = Executor { handler.post(it) }

        val registrationRecord = NsdRegistrationRecord(expectedThreadId = handlerThread.threadId)
        val registeredInfo1 = registerService(registrationRecord, si, executor)
        val discoveryRecord = NsdDiscoveryRecord(expectedThreadId = handlerThread.threadId)

        tryTest {
            val specifier = TestNetworkSpecifier(testNetwork1.iface.interfaceName)
            nsdShim.discoverServices(nsdManager, serviceType, NsdManager.PROTOCOL_DNS_SD,
                    NetworkRequest.Builder()
                            .removeCapability(NET_CAPABILITY_TRUSTED)
                            .addTransportType(TRANSPORT_TEST)
                            .setNetworkSpecifier(specifier)
                            .build(),
                    executor, discoveryRecord)

            val discoveryStarted = discoveryRecord.expectCallback<DiscoveryStarted>()
            assertEquals(serviceType, discoveryStarted.serviceType)

            val serviceDiscovered = discoveryRecord.expectCallback<ServiceFound>()
            assertEquals(registeredInfo1.serviceName, serviceDiscovered.serviceInfo.serviceName)
            // Discovered service types have a dot at the end
            assertEquals("$serviceType.", serviceDiscovered.serviceInfo.serviceType)
            assertEquals(testNetwork1.network, nsdShim.getNetwork(serviceDiscovered.serviceInfo))

            // Unregister, then register the service back: it should be lost and found again
            nsdManager.unregisterService(registrationRecord)
            val serviceLost1 = discoveryRecord.expectCallback<ServiceLost>()
            assertEquals(registeredInfo1.serviceName, serviceLost1.serviceInfo.serviceName)
            assertEquals(testNetwork1.network, nsdShim.getNetwork(serviceLost1.serviceInfo))

            registrationRecord.expectCallback<ServiceUnregistered>()
            val registeredInfo2 = registerService(registrationRecord, si, executor)
            val serviceDiscovered2 = discoveryRecord.expectCallback<ServiceFound>()
            assertEquals(registeredInfo2.serviceName, serviceDiscovered2.serviceInfo.serviceName)
            assertEquals("$serviceType.", serviceDiscovered2.serviceInfo.serviceType)
            assertEquals(testNetwork1.network, nsdShim.getNetwork(serviceDiscovered2.serviceInfo))

            // Teardown, then bring back up a network on the test interface: the service should
            // go away, then come back
            testNetwork1.agent.unregister()
            val serviceLost = discoveryRecord.expectCallback<ServiceLost>()
            assertEquals(registeredInfo2.serviceName, serviceLost.serviceInfo.serviceName)
            assertEquals(testNetwork1.network, nsdShim.getNetwork(serviceLost.serviceInfo))

            val newAgent = runAsShell(MANAGE_TEST_NETWORKS) {
                registerTestNetworkAgent(testNetwork1.iface.interfaceName)
            }
            val newNetwork = newAgent.network ?: fail("Registered agent should have a network")
            val serviceDiscovered3 = discoveryRecord.expectCallback<ServiceFound>()
            assertEquals(registeredInfo2.serviceName, serviceDiscovered3.serviceInfo.serviceName)
            assertEquals("$serviceType.", serviceDiscovered3.serviceInfo.serviceType)
            assertEquals(newNetwork, nsdShim.getNetwork(serviceDiscovered3.serviceInfo))
        } cleanupStep {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStopped>()
        } cleanup {
            nsdManager.unregisterService(registrationRecord)
        }
    }

    @Test
    fun testNsdManager_DiscoverWithNetworkRequest_NoMatchingNetwork() {
        // This test requires shims supporting T+ APIs (discovering on network request)
        assumeTrue(TestUtils.shouldTestTApis())

        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = this.serviceName
        si.port = 12345 // Test won't try to connect so port does not matter

        val handler = Handler(handlerThread.looper)
        val executor = Executor { handler.post(it) }

        val discoveryRecord = NsdDiscoveryRecord(expectedThreadId = handlerThread.threadId)
        val specifier = TestNetworkSpecifier(testNetwork1.iface.interfaceName)

        tryTest {
            nsdShim.discoverServices(nsdManager, serviceType, NsdManager.PROTOCOL_DNS_SD,
                    NetworkRequest.Builder()
                            .removeCapability(NET_CAPABILITY_TRUSTED)
                            .addTransportType(TRANSPORT_TEST)
                            // Specified network does not have this capability
                            .addCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                            .setNetworkSpecifier(specifier)
                            .build(),
                    executor, discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStarted>()
        } cleanup {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStopped>()
        }
    }

    private fun checkAddressScopeId(iface: TestNetworkInterface, address: List<InetAddress>) {
        val targetSdkVersion = context.packageManager
            .getTargetSdkVersion(context.applicationInfo.packageName)
        if (targetSdkVersion <= Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val ifaceIdx = NetworkInterface.getByName(iface.interfaceName).index
        address.forEach {
            if (it is Inet6Address && it.isLinkLocalAddress) {
                assertEquals(ifaceIdx, it.scopeId)
            }
        }
    }

    @Test
    fun testNsdManager_ResolveOnNetwork() {
        // This test requires shims supporting T+ APIs (NsdServiceInfo.network)
        assumeTrue(TestUtils.shouldTestTApis())

        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = this.serviceName
        si.port = 12345 // Test won't try to connect so port does not matter

        val registrationRecord = NsdRegistrationRecord()
        val registeredInfo = registerService(registrationRecord, si)
        tryTest {
            val resolveRecord = NsdResolveRecord()

            val discoveryRecord = NsdDiscoveryRecord()
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)

            val foundInfo1 = discoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)
            assertEquals(testNetwork1.network, nsdShim.getNetwork(foundInfo1))
            // Rewind as the service could be found on each interface in any order
            discoveryRecord.nextEvents.rewind(0)
            val foundInfo2 = discoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork2.network)
            assertEquals(testNetwork2.network, nsdShim.getNetwork(foundInfo2))

            nsdShim.resolveService(nsdManager, foundInfo1, Executor { it.run() }, resolveRecord)
            val cb = resolveRecord.expectCallback<ServiceResolved>()
            cb.serviceInfo.let {
                // Resolved service type has leading dot
                assertEquals(".$serviceType", it.serviceType)
                assertEquals(registeredInfo.serviceName, it.serviceName)
                assertEquals(si.port, it.port)
                assertEquals(testNetwork1.network, nsdShim.getNetwork(it))
                checkAddressScopeId(testNetwork1.iface, it.hostAddresses)
            }
            // TODO: check that MDNS packets are sent only on testNetwork1.
        } cleanupStep {
            nsdManager.unregisterService(registrationRecord)
        } cleanup {
            registrationRecord.expectCallback<ServiceUnregistered>()
        }
    }

    @Test
    fun testNsdManager_RegisterOnNetwork() {
        // This test requires shims supporting T+ APIs (NsdServiceInfo.network)
        assumeTrue(TestUtils.shouldTestTApis())

        val si = NsdServiceInfo()
        si.serviceType = serviceType
        si.serviceName = this.serviceName
        si.network = testNetwork1.network
        si.port = 12345 // Test won't try to connect so port does not matter

        // Register service on testNetwork1
        val registrationRecord = NsdRegistrationRecord()
        registerService(registrationRecord, si)
        val discoveryRecord = NsdDiscoveryRecord()
        val discoveryRecord2 = NsdDiscoveryRecord()
        val discoveryRecord3 = NsdDiscoveryRecord()

        tryTest {
            // Discover service on testNetwork1.
            nsdShim.discoverServices(nsdManager, serviceType, NsdManager.PROTOCOL_DNS_SD,
                testNetwork1.network, Executor { it.run() }, discoveryRecord)
            // Expect that service is found on testNetwork1
            val foundInfo = discoveryRecord.waitForServiceDiscovered(
                serviceName, serviceType, testNetwork1.network)
            assertEquals(testNetwork1.network, nsdShim.getNetwork(foundInfo))

            // Discover service on testNetwork2.
            nsdShim.discoverServices(nsdManager, serviceType, NsdManager.PROTOCOL_DNS_SD,
                testNetwork2.network, Executor { it.run() }, discoveryRecord2)
            // Expect that discovery is started then no other callbacks.
            discoveryRecord2.expectCallback<DiscoveryStarted>()
            discoveryRecord2.assertNoCallback()

            // Discover service on all networks (not specify any network).
            nsdShim.discoverServices(nsdManager, serviceType, NsdManager.PROTOCOL_DNS_SD,
                null as Network? /* network */, Executor { it.run() }, discoveryRecord3)
            // Expect that service is found on testNetwork1
            val foundInfo3 = discoveryRecord3.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)
            assertEquals(testNetwork1.network, nsdShim.getNetwork(foundInfo3))
        } cleanupStep {
            nsdManager.stopServiceDiscovery(discoveryRecord2)
            discoveryRecord2.expectCallback<DiscoveryStopped>()
        } cleanup {
            nsdManager.unregisterService(registrationRecord)
        }
    }

    @Test
    fun testNsdManager_RegisterServiceNameWithNonStandardCharacters() {
        val serviceNames = "^Nsd.Test|Non-#AsCiI\\Characters&\\ufffe テスト 測試"
        val si = NsdServiceInfo().apply {
            serviceType = this@NsdManagerTest.serviceType
            serviceName = serviceNames
            port = 12345 // Test won't try to connect so port does not matter
        }

        // Register the service name which contains non-standard characters.
        val registrationRecord = NsdRegistrationRecord()
        nsdManager.registerService(si, NsdManager.PROTOCOL_DNS_SD, registrationRecord)
        registrationRecord.expectCallback<ServiceRegistered>(REGISTRATION_TIMEOUT_MS)

        tryTest {
            // Discover that service name.
            val discoveryRecord = NsdDiscoveryRecord()
            nsdManager.discoverServices(
                serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryRecord
            )
            val foundInfo = discoveryRecord.waitForServiceDiscovered(serviceNames, serviceType)

            // Expect that resolving the service name works properly even service name contains
            // non-standard characters.
            val resolveRecord = NsdResolveRecord()
            nsdManager.resolveService(foundInfo, resolveRecord)
            val resolvedCb = resolveRecord.expectCallback<ServiceResolved>()
            assertEquals(foundInfo.serviceName, resolvedCb.serviceInfo.serviceName)
        } cleanupStep {
            nsdManager.unregisterService(registrationRecord)
        } cleanup {
            registrationRecord.expectCallback<ServiceUnregistered>()
        }
    }

    private fun checkConnectSocketToMdnsd(shouldFail: Boolean) {
        val discoveryRecord = NsdDiscoveryRecord()
        val localSocket = LocalSocket()
        tryTest {
            // Discover any service from NsdManager to enforce NsdService to start the mdnsd.
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStarted>()

            // Checks the /dev/socket/mdnsd is created.
            val socket = File("/dev/socket/mdnsd")
            val doesSocketExist = PollingCheck.waitFor(
                TIMEOUT_MS,
                {
                    socket.exists()
                },
                { doesSocketExist ->
                    doesSocketExist
                },
            )

            // If the socket is not created, then no need to check the access.
            if (doesSocketExist) {
                // Create a LocalSocket and try to connect to mdnsd.
                assertFalse("LocalSocket is connected.", localSocket.isConnected)
                val address = LocalSocketAddress("mdnsd", LocalSocketAddress.Namespace.RESERVED)
                if (shouldFail) {
                    assertFailsWith<IOException>("Expect fail but socket connected") {
                        localSocket.connect(address)
                    }
                } else {
                    localSocket.connect(address)
                    assertTrue("LocalSocket is not connected.", localSocket.isConnected)
                }
            }
        } cleanup {
            localSocket.close()
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStopped>()
        }
    }

    /**
     * Starting from Android U, the access to the /dev/socket/mdnsd is blocked by the
     * sepolicy(b/265364111).
     */
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    fun testCannotConnectSocketToMdnsd() {
        val targetSdkVersion = context.packageManager
                .getTargetSdkVersion(context.applicationInfo.packageName)
        assumeTrue(targetSdkVersion > Build.VERSION_CODES.TIRAMISU)
        val firstApiLevel = min(PropertyUtil.getFirstApiLevel(), PropertyUtil.getVendorApiLevel())
        // The sepolicy is implemented in the vendor image, so the access may not be blocked if
        // the vendor image is not update to date.
        assumeTrue(firstApiLevel > Build.VERSION_CODES.TIRAMISU)
        checkConnectSocketToMdnsd(shouldFail = true)
    }

    @Test @CtsNetTestCasesMaxTargetSdk33("mdnsd socket is accessible up to target SDK 33")
    fun testCanConnectSocketToMdnsd() {
        checkConnectSocketToMdnsd(shouldFail = false)
    }

    @Test @CtsNetTestCasesMaxTargetSdk30("Socket is started with the service up to target SDK 30")
    fun testManagerCreatesLegacySocket() {
        nsdManager // Ensure the lazy-init member is initialized, so NsdManager is created
        val socket = File("/dev/socket/mdnsd")
        val timeout = System.currentTimeMillis() + TIMEOUT_MS
        while (!socket.exists() && System.currentTimeMillis() < timeout) {
            Thread.sleep(10)
        }
        assertTrue("$socket was not found after $TIMEOUT_MS ms", socket.exists())
    }

    // The compat change is part of a connectivity module update that applies to T+
    @ConnectivityModuleTest @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
    @Test @CtsNetTestCasesMaxTargetSdk30("Socket is started with the service up to target SDK 30")
    fun testManagerCreatesLegacySocket_CompatChange() {
        // The socket may have been already created by some other app, or some other test, in which
        // case this test cannot verify creation. At least verify that the compat change is
        // disabled in a process with max SDK 30; unit tests already verify that start is requested
        // when the compat change is disabled.
        // Note that before T the compat constant had a different int value.
        assertFalse(CompatChanges.isChangeEnabled(
                ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER))
    }

    @Test
    fun testStopServiceResolution() {
        // This test requires shims supporting U+ APIs (NsdManager.stopServiceResolution)
        assumeTrue(TestUtils.shouldTestUApis())

        val si = NsdServiceInfo()
        si.serviceType = this@NsdManagerTest.serviceType
        si.serviceName = this@NsdManagerTest.serviceName
        si.port = 12345 // Test won't try to connect so port does not matter

        val resolveRecord = NsdResolveRecord()
        // Try to resolve an unknown service then stop it immediately.
        // Expected ResolutionStopped callback.
        nsdShim.resolveService(nsdManager, si, { it.run() }, resolveRecord)
        nsdShim.stopServiceResolution(nsdManager, resolveRecord)
        val stoppedCb = resolveRecord.expectCallback<ResolutionStopped>()
        assertEquals(si.serviceName, stoppedCb.serviceInfo.serviceName)
        assertEquals(si.serviceType, stoppedCb.serviceInfo.serviceType)
    }

    @Test
    fun testRegisterServiceInfoCallback() {
        // This test requires shims supporting U+ APIs (NsdManager.registerServiceInfoCallback)
        assumeTrue(TestUtils.shouldTestUApis())

        val lp = cm.getLinkProperties(testNetwork1.network)
        assertNotNull(lp)
        val addresses = lp.addresses
        assertFalse(addresses.isEmpty())

        val si = NsdServiceInfo().apply {
            serviceType = this@NsdManagerTest.serviceType
            serviceName = this@NsdManagerTest.serviceName
            network = testNetwork1.network
            port = 12345 // Test won't try to connect so port does not matter
        }

        // Register service on the network
        val registrationRecord = NsdRegistrationRecord()
        registerService(registrationRecord, si)

        val discoveryRecord = NsdDiscoveryRecord()
        val cbRecord = NsdServiceInfoCallbackRecord()
        tryTest {
            // Discover service on the network.
            nsdShim.discoverServices(nsdManager, serviceType, NsdManager.PROTOCOL_DNS_SD,
                    testNetwork1.network, Executor { it.run() }, discoveryRecord)
            val foundInfo = discoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)

            // Register service callback and check the addresses are the same as network addresses
            nsdShim.registerServiceInfoCallback(nsdManager, foundInfo, { it.run() }, cbRecord)
            val serviceInfoCb = cbRecord.expectCallback<ServiceUpdated>()
            assertEquals(foundInfo.serviceName, serviceInfoCb.serviceInfo.serviceName)
            val hostAddresses = serviceInfoCb.serviceInfo.hostAddresses
            assertEquals(addresses.size, hostAddresses.size)
            for (hostAddress in hostAddresses) {
                assertTrue(addresses.contains(hostAddress))
            }
            checkAddressScopeId(testNetwork1.iface, serviceInfoCb.serviceInfo.hostAddresses)
        } cleanupStep {
            nsdManager.unregisterService(registrationRecord)
            registrationRecord.expectCallback<ServiceUnregistered>()
            discoveryRecord.expectCallback<ServiceLost>()
            cbRecord.expectCallback<ServiceUpdatedLost>()
        } cleanupStep {
            // Cancel subscription and check stop callback received.
            nsdShim.unregisterServiceInfoCallback(nsdManager, cbRecord)
            cbRecord.expectCallback<UnregisterCallbackSucceeded>()
        } cleanup {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<DiscoveryStopped>()
        }
    }

    @Test
    fun testStopServiceResolutionFailedCallback() {
        // This test requires shims supporting U+ APIs (NsdManager.stopServiceResolution)
        assumeTrue(TestUtils.shouldTestUApis())

        // It's not possible to make ResolutionListener#onStopResolutionFailed callback sending
        // because it is only sent in very edge-case scenarios when the legacy implementation is
        // used, and the legacy implementation is never used in the current AOSP builds. Considering
        // that this callback isn't expected to be sent at all at the moment, and this is just an
        // interface with no implementation. To verify this callback, just call
        // onStopResolutionFailed on the record directly then verify it is received.
        val resolveRecord = NsdResolveRecord()
        resolveRecord.onStopResolutionFailed(
                NsdServiceInfo(), NsdManager.FAILURE_OPERATION_NOT_RUNNING)
        val failedCb = resolveRecord.expectCallback<StopResolutionFailed>()
        assertEquals(NsdManager.FAILURE_OPERATION_NOT_RUNNING, failedCb.errorCode)
    }

    @Test
    fun testSubtypeAdvertisingAndDiscovery() {
        val si = makeTestServiceInfo(network = testNetwork1.network)
        // Test "_type._tcp.local,_subtype" syntax with the registration
        si.serviceType = si.serviceType + ",_subtype"

        val registrationRecord = NsdRegistrationRecord()

        val baseTypeDiscoveryRecord = NsdDiscoveryRecord()
        val subtypeDiscoveryRecord = NsdDiscoveryRecord()
        val otherSubtypeDiscoveryRecord = NsdDiscoveryRecord()
        tryTest {
            registerService(registrationRecord, si)

            // Test "_subtype._type._tcp.local" syntax with discovery. Note this is not
            // "_subtype._sub._type._tcp.local".
            nsdManager.discoverServices(serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    testNetwork1.network, Executor { it.run() }, baseTypeDiscoveryRecord)
            nsdManager.discoverServices("_othersubtype.$serviceType",
                    NsdManager.PROTOCOL_DNS_SD,
                    testNetwork1.network, Executor { it.run() }, otherSubtypeDiscoveryRecord)
            nsdManager.discoverServices("_subtype.$serviceType",
                    NsdManager.PROTOCOL_DNS_SD,
                    testNetwork1.network, Executor { it.run() }, subtypeDiscoveryRecord)

            subtypeDiscoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)
            baseTypeDiscoveryRecord.waitForServiceDiscovered(
                    serviceName, serviceType, testNetwork1.network)
            otherSubtypeDiscoveryRecord.expectCallback<DiscoveryStarted>()
            // The subtype callback was registered later but called, no need for an extra delay
            otherSubtypeDiscoveryRecord.assertNoCallback(timeoutMs = 0)
        } cleanupStep {
            nsdManager.stopServiceDiscovery(baseTypeDiscoveryRecord)
            nsdManager.stopServiceDiscovery(subtypeDiscoveryRecord)
            nsdManager.stopServiceDiscovery(otherSubtypeDiscoveryRecord)

            baseTypeDiscoveryRecord.expectCallback<DiscoveryStopped>()
            subtypeDiscoveryRecord.expectCallback<DiscoveryStopped>()
            otherSubtypeDiscoveryRecord.expectCallback<DiscoveryStopped>()
        } cleanup {
            nsdManager.unregisterService(registrationRecord)
        }
    }

    /**
     * Register a service and return its registration record.
     */
    private fun registerService(
        record: NsdRegistrationRecord,
        si: NsdServiceInfo,
        executor: Executor = Executor { it.run() }
    ): NsdServiceInfo {
        nsdShim.registerService(nsdManager, si, NsdManager.PROTOCOL_DNS_SD, executor, record)
        // We may not always get the name that we tried to register;
        // This events tells us the name that was registered.
        val cb = record.expectCallback<ServiceRegistered>(REGISTRATION_TIMEOUT_MS)
        return cb.serviceInfo
    }

    private fun resolveService(discoveredInfo: NsdServiceInfo): NsdServiceInfo {
        val record = NsdResolveRecord()
        nsdShim.resolveService(nsdManager, discoveredInfo, Executor { it.run() }, record)
        val resolvedCb = record.expectCallback<ServiceResolved>()
        assertEquals(discoveredInfo.serviceName, resolvedCb.serviceInfo.serviceName)

        return resolvedCb.serviceInfo
    }
}

private fun ByteArray?.utf8ToString(): String {
    if (this == null) return ""
    return String(this, StandardCharsets.UTF_8)
}
