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
 * limitations under the License.
 */

package com.android.testutils

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.TestNetworkSpecifier
import android.os.Binder
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import java.net.InetAddress
import libcore.io.IoUtils

private const val MIN_PORT_NUMBER = 1025
private const val MAX_PORT_NUMBER = 65535

/**
 * A class that set up two {@link TestNetworkInterface} with NAT, and forward packets between them.
 *
 * See {@link NatPacketForwarder} for more detailed information.
 */
class PacketBridge(
    context: Context,
    internalAddr: LinkAddress,
    externalAddr: LinkAddress,
    dnsAddr: InetAddress
) {
    private val natMap = NatMap()
    private val binder = Binder()

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val tnm = context.getSystemService(TestNetworkManager::class.java)

    // Create test networks.
    private val internalIface = tnm.createTunInterface(listOf(internalAddr))
    private val externalIface = tnm.createTunInterface(listOf(externalAddr))

    // Register test networks to ConnectivityService.
    private val internalNetworkCallback: TestableNetworkCallback
    private val externalNetworkCallback: TestableNetworkCallback
    val internalNetwork: Network
    val externalNetwork: Network
    init {
        val (inCb, inNet) = createTestNetwork(internalIface, internalAddr, dnsAddr)
        val (exCb, exNet) = createTestNetwork(externalIface, externalAddr, dnsAddr)
        internalNetworkCallback = inCb
        externalNetworkCallback = exCb
        internalNetwork = inNet
        externalNetwork = exNet
    }

    // Setup the packet bridge.
    private val internalFd = internalIface.fileDescriptor.fileDescriptor
    private val externalFd = externalIface.fileDescriptor.fileDescriptor

    private val pr1 = NatInternalPacketForwarder(
        internalFd,
        1500,
        externalFd,
        externalAddr.address,
        natMap
    )
    private val pr2 = NatExternalPacketForwarder(
        externalFd,
        1500,
        internalFd,
        externalAddr.address,
        natMap
    )

    fun start() {
        IoUtils.setBlocking(internalFd, true /* blocking */)
        IoUtils.setBlocking(externalFd, true /* blocking */)
        pr1.start()
        pr2.start()
    }

    fun stop() {
        pr1.interrupt()
        pr2.interrupt()
        cm.unregisterNetworkCallback(internalNetworkCallback)
        cm.unregisterNetworkCallback(externalNetworkCallback)
    }

    /**
     * Creates a test network with given test TUN interface and addresses.
     */
    private fun createTestNetwork(
        testIface: TestNetworkInterface,
        addr: LinkAddress,
        dnsAddr: InetAddress
    ): Pair<TestableNetworkCallback, Network> {
        // Make a network request to hold the test network
        val nr = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
            .setNetworkSpecifier(TestNetworkSpecifier(testIface.interfaceName))
            .build()
        val testCb = TestableNetworkCallback()
        cm.requestNetwork(nr, testCb)

        val lp = LinkProperties().apply {
            addLinkAddress(addr)
            interfaceName = testIface.interfaceName
            addDnsServer(dnsAddr)
        }
        tnm.setupTestNetwork(lp, true /* isMetered */, binder)

        // Wait for available before return.
        val network = testCb.expect<Available>().network
        return testCb to network
    }

    /**
     * A helper class to maintain the mappings between internal addresses/ports and external
     * ports.
     *
     * This class assigns an unused external port number if the mapping between
     * srcaddress:srcport:protocol and the external port does not exist yet.
     *
     * Note that this class is not thread-safe. The instance of the class needs to be
     * synchronized in the callers when being used in multiple threads.
     */
    class NatMap {
        data class AddressInfo(val address: InetAddress, val port: Int, val protocol: Int)

        private val mToExternalPort = HashMap<AddressInfo, Int>()
        private val mFromExternalPort = HashMap<Int, AddressInfo>()

        // Skip well-known port 0~1024.
        private var nextExternalPort = MIN_PORT_NUMBER

        fun toExternalPort(addr: InetAddress, port: Int, protocol: Int): Int {
            val info = AddressInfo(addr, port, protocol)
            val extPort: Int
            if (!mToExternalPort.containsKey(info)) {
                extPort = nextExternalPort++
                if (nextExternalPort > MAX_PORT_NUMBER) {
                    throw IllegalStateException("Available ports are exhausted")
                }
                mToExternalPort[info] = extPort
                mFromExternalPort[extPort] = info
            } else {
                extPort = mToExternalPort[info]!!
            }
            return extPort
        }

        fun fromExternalPort(port: Int): AddressInfo? {
            return mFromExternalPort[port]
        }
    }
}
