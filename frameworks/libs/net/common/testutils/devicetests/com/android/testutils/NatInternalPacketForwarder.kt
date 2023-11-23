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
 * A class that forwards packets from the internal {@link TestNetworkInterface} to the external
 * {@link TestNetworkInterface} with NAT. See {@link NatPacketForwarderBase} for detail.
 */
class NatInternalPacketForwarder(
    srcFd: FileDescriptor,
    mtu: Int,
    dstFd: FileDescriptor,
    extAddr: InetAddress,
    natMap: PacketBridge.NatMap
) : NatPacketForwarderBase(srcFd, mtu, dstFd, extAddr, natMap) {

    /**
     * Rewrite addresses, ports and fix up checksums for packets received on the internal
     * interface.
     *
     * Outgoing packet from the internal interface which is being forwarded to the
     * external interface with translated address, e.g. 192.168.1.1:5678 -> 8.8.8.8:80
     * will be translated into 8.8.8.8:1234 -> 1.2.3.4:80.
     *
     * The external port, e.g. 1234 in the above example, is the port number assigned by
     * the forwarder when creating the mapping to identify the source address and port when
     * the response is coming from the external interface. See {@link PacketBridge.NatMap}
     * for detail.
     */
    override fun preparePacketForForwarding(buf: ByteArray, len: Int, version: Int, proto: Int) {
        val (addrPos, addrLen) = getAddressPositionAndLength(version)

        // TODO: support one external address per ip version.
        val extAddrBuf = mExtAddr.address
        if (addrLen != extAddrBuf.size) throw IllegalStateException("Packet IP version mismatch")

        val srcAddr = getInetAddressAt(buf, addrPos, addrLen)

        // Copy the original destination to into the source address.
        for (i in 0 until addrLen) {
            buf[addrPos + i] = buf[addrPos + addrLen + i]
        }

        // Copy the external address into the destination address.
        for (i in 0 until addrLen) {
            buf[addrPos + addrLen + i] = extAddrBuf[i]
        }

        // Add an entry to NAT mapping table.
        val transportOffset =
            if (version == 4) PacketReflector.IPV4_HEADER_LENGTH
            else PacketReflector.IPV6_HEADER_LENGTH
        val srcPort = getPortAt(buf, transportOffset)
        val extPort = synchronized(mNatMap) { mNatMap.toExternalPort(srcAddr, srcPort, proto) }
        // Copy the external port to into the source port.
        setPortAt(extPort, buf, transportOffset)

        // Fix IP and Transport layer checksum.
        fixPacketChecksum(buf, len, version, proto.toByte())
    }
}
