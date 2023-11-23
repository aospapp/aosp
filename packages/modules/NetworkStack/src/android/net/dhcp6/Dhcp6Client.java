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

package android.net.dhcp6;

import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_NONBLOCK;

import static com.android.net.module.util.NetworkStackConstants.ALL_DHCP_RELAY_AGENTS_AND_SERVERS;
import static com.android.net.module.util.NetworkStackConstants.DHCP6_CLIENT_PORT;
import static com.android.net.module.util.NetworkStackConstants.DHCP6_SERVER_PORT;
import static com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_ANY;

import android.content.Context;
import android.net.ip.IpClient;
import android.net.util.SocketUtils;
import android.os.Handler;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.util.StateMachine;
import com.android.net.module.util.InterfaceParams;
import com.android.net.module.util.PacketReader;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;

/**
 * A DHCPv6 client.
 *
 * So far only support IA_PD (prefix delegation), not for IA_NA/IA_TA yet.
 *
 * @hide
 */
public class Dhcp6Client extends StateMachine {
    private static final String TAG = Dhcp6Client.class.getSimpleName();
    private static final boolean DBG = true;

    // Internal messages.
    // Dhcp6Client shares the same handler with IpClient, define the base command range for
    // both public and private messages used in Dhcp6Client, to avoid commands overlap.
    private static final int PRIVATE_BASE         = IpClient.DHCP6CLIENT_CMD_BASE + 100;
    private static final int CMD_RECEIVED_PACKET  = PRIVATE_BASE + 1;

    @NonNull private final Context mContext;

    // State variables.
    @NonNull private final StateMachine mController;
    @NonNull private final String mIfaceName;

    private Dhcp6Client(@NonNull final Context context, @NonNull final StateMachine controller,
            @NonNull final InterfaceParams iface) {
        super(TAG, controller.getHandler());

        mContext = context;
        mController = controller;
        mIfaceName = iface.name;

        // TODO: add state machine initialization.
    }

    private class Dhcp6PacketHandler extends PacketReader {
        private FileDescriptor mUdpSock;

        Dhcp6PacketHandler(Handler handler) {
            super(handler);
        }

        @Override
        protected void handlePacket(byte[] recvbuf, int length) {
            try {
                final Dhcp6Packet packet = Dhcp6Packet.decodePacket(recvbuf, length);
                if (DBG) Log.d(TAG, "Received packet: " + packet);
                sendMessage(CMD_RECEIVED_PACKET, packet);
            } catch (Dhcp6Packet.ParseException e) {
                Log.e(TAG, "Can't parse DHCPv6 packet: " + e.getMessage());
            }
        }

        @Override
        protected FileDescriptor createFd() {
            try {
                mUdpSock = Os.socket(AF_INET6, SOCK_DGRAM | SOCK_NONBLOCK, IPPROTO_UDP);
                SocketUtils.bindSocketToInterface(mUdpSock, mIfaceName);
                Os.bind(mUdpSock, IPV6_ADDR_ANY, DHCP6_CLIENT_PORT);
            } catch (SocketException | ErrnoException e) {
                Log.e(TAG, "Error creating udp socket", e);
                closeFd(mUdpSock);
                mUdpSock = null;
                return null;
            }
            return mUdpSock;
        }

        @Override
        protected int readPacket(FileDescriptor fd, byte[] packetBuffer) throws Exception {
            try {
                return Os.read(fd, packetBuffer, 0, packetBuffer.length);
            } catch (IOException | ErrnoException e) {
                Log.e(TAG, "Fail to read packet");
                throw e;
            }
        }

        public int transmitPacket(final ByteBuffer buf) throws ErrnoException, SocketException {
            int ret = Os.sendto(mUdpSock, buf.array(), 0 /* byteOffset */,
                    buf.limit() /* byteCount */, 0 /* flags */, ALL_DHCP_RELAY_AGENTS_AND_SERVERS,
                    DHCP6_SERVER_PORT);
            return ret;
        }
    }
}
