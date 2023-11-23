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

import java.io.FileDescriptor
import java.net.InetAddress

/**
 * A class that forwards packets from the external {@link TestNetworkInterface} to the internal
 * {@link TestNetworkInterface} with NAT. See {@link NatPacketForwarderBase} for detail.
 */
class NatExternalPacketForwarder(
    srcFd: FileDescriptor,
    mtu: Int,
    dstFd: FileDescriptor,
    extAddr: InetAddress,
    natMap: PacketBridge.NatMap
) : NatPacketForwarderBase(srcFd, mtu, dstFd, extAddr, natMap) {

    /**
     * Rewrite addresses, ports and fix up checksums for packets received on the external
     * interface.
     *
     * Incoming response from external interface which is being forwarded to the internal
     * interface with translated address, e.g. 1.2.3.4:80 -> 8.8.8.8:1234
     * will be translated into 8.8.8.8:80 -> 192.168.1.1:5678.
     *
     * For packets that are not an incoming response, do not forward them to the
     * internal interface.
     */
    override fun preparePacketForForwarding(buf: ByteArray, len: Int, version: Int, proto: Int) {
        val (addrPos, addrLen) = getAddressPositionAndLength(version)

        // TODO: support one external address per ip version.
        val extAddrBuf = mExtAddr.address
        if (addrLen != extAddrBuf.size) throw IllegalStateException("Packet IP version mismatch")

        // Get internal address by port.
        val transportOffset =
            if (version == 4) PacketReflector.IPV4_HEADER_LENGTH
            else PacketReflector.IPV6_HEADER_LENGTH
        val dstPort = getPortAt(buf, transportOffset + DESTINATION_PORT_OFFSET)
        val intAddrInfo = synchronized(mNatMap) { mNatMap.fromExternalPort(dstPort) }
        // No mapping, skip. This usually happens if the connection is initiated directly on
        // the external interface, e.g. DNS64 resolution, network validation, etc.
        if (intAddrInfo == null) return

        val intAddrBuf = intAddrInfo.address.address
        val intPort = intAddrInfo.port

        // Copy the original destination to into the source address.
        for (i in 0 until addrLen) {
            buf[addrPos + i] = buf[addrPos + addrLen + i]
        }

        // Copy the internal address into the destination address.
        for (i in 0 until addrLen) {
            buf[addrPos + addrLen + i] = intAddrBuf[i]
        }

        // Copy the internal port into the destination port.
        setPortAt(intPort, buf, transportOffset + DESTINATION_PORT_OFFSET)

        // Fix IP and Transport layer checksum.
        fixPacketChecksum(buf, len, version, proto.toByte())
    }
}
