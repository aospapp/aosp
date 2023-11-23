/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnHandlerThread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkAddress;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/**
 * The {@link MdnsMultinetworkSocketClient} manages the multinetwork socket for mDns
 *
 *  * <p>This class is not thread safe.
 */
public class MdnsMultinetworkSocketClient implements MdnsSocketClientBase {
    private static final String TAG = MdnsMultinetworkSocketClient.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;

    @NonNull private final Handler mHandler;
    @NonNull private final MdnsSocketProvider mSocketProvider;

    private final ArrayMap<MdnsServiceBrowserListener, InterfaceSocketCallback> mRequestedNetworks =
            new ArrayMap<>();
    private final ArrayMap<MdnsInterfaceSocket, ReadPacketHandler> mSocketPacketHandlers =
            new ArrayMap<>();
    private MdnsSocketClientBase.Callback mCallback = null;
    private int mReceivedPacketNumber = 0;

    public MdnsMultinetworkSocketClient(@NonNull Looper looper,
            @NonNull MdnsSocketProvider provider) {
        mHandler = new Handler(looper);
        mSocketProvider = provider;
    }

    private class InterfaceSocketCallback implements MdnsSocketProvider.SocketCallback {
        @NonNull
        private final SocketCreationCallback mSocketCreationCallback;
        @NonNull
        private final ArrayMap<MdnsInterfaceSocket, Network> mActiveNetworkSockets =
                new ArrayMap<>();

        InterfaceSocketCallback(SocketCreationCallback socketCreationCallback) {
            mSocketCreationCallback = socketCreationCallback;
        }

        @Override
        public void onSocketCreated(@Nullable Network network,
                @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {
            // The socket may be already created by other request before, try to get the stored
            // ReadPacketHandler.
            ReadPacketHandler handler = mSocketPacketHandlers.get(socket);
            if (handler == null) {
                // First request to create this socket. Initial a ReadPacketHandler for this socket.
                handler = new ReadPacketHandler(network, socket.getInterface().getIndex());
                mSocketPacketHandlers.put(socket, handler);
            }
            socket.addPacketHandler(handler);
            mActiveNetworkSockets.put(socket, network);
            mSocketCreationCallback.onSocketCreated(network);
        }

        @Override
        public void onInterfaceDestroyed(@Nullable Network network,
                @NonNull MdnsInterfaceSocket socket) {
            notifySocketDestroyed(socket);
            maybeCleanupPacketHandler(socket);
        }

        private void notifySocketDestroyed(@NonNull MdnsInterfaceSocket socket) {
            final Network network = mActiveNetworkSockets.remove(socket);
            if (!isAnySocketActive(network)) {
                mSocketCreationCallback.onAllSocketsDestroyed(network);
            }
        }

        void onNetworkUnrequested() {
            for (int i = mActiveNetworkSockets.size() - 1; i >= 0; i--) {
                // Iterate from the end so the socket can be removed
                final MdnsInterfaceSocket socket = mActiveNetworkSockets.keyAt(i);
                notifySocketDestroyed(socket);
                maybeCleanupPacketHandler(socket);
            }
        }
    }

    private boolean isSocketActive(@NonNull MdnsInterfaceSocket socket) {
        for (int i = 0; i < mRequestedNetworks.size(); i++) {
            final InterfaceSocketCallback isc = mRequestedNetworks.valueAt(i);
            if (isc.mActiveNetworkSockets.containsKey(socket)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnySocketActive(@Nullable Network network) {
        for (int i = 0; i < mRequestedNetworks.size(); i++) {
            final InterfaceSocketCallback isc = mRequestedNetworks.valueAt(i);
            if (isc.mActiveNetworkSockets.containsValue(network)) {
                return true;
            }
        }
        return false;
    }

    private ArrayMap<MdnsInterfaceSocket, Network> getActiveSockets() {
        final ArrayMap<MdnsInterfaceSocket, Network> sockets = new ArrayMap<>();
        for (int i = 0; i < mRequestedNetworks.size(); i++) {
            final InterfaceSocketCallback isc = mRequestedNetworks.valueAt(i);
            sockets.putAll(isc.mActiveNetworkSockets);
        }
        return sockets;
    }

    private void maybeCleanupPacketHandler(@NonNull MdnsInterfaceSocket socket) {
        if (isSocketActive(socket)) return;
        mSocketPacketHandlers.remove(socket);
    }

    private class ReadPacketHandler implements MulticastPacketReader.PacketHandler {
        private final Network mNetwork;
        private final int mInterfaceIndex;

        ReadPacketHandler(@NonNull Network network, int interfaceIndex) {
            mNetwork = network;
            mInterfaceIndex = interfaceIndex;
        }

        @Override
        public void handlePacket(byte[] recvbuf, int length, InetSocketAddress src) {
            processResponsePacket(recvbuf, length, mInterfaceIndex, mNetwork);
        }
    }

    /*** Set callback for receiving mDns response */
    @Override
    public void setCallback(@Nullable MdnsSocketClientBase.Callback callback) {
        ensureRunningOnHandlerThread(mHandler);
        mCallback = callback;
    }

    /***
     * Notify that the given network is requested for mdns discovery / resolution
     *
     * @param listener the listener for discovery.
     * @param network the target network for discovery. Null means discovery on all possible
     *                interfaces.
     * @param socketCreationCallback the callback to notify socket creation.
     */
    @Override
    public void notifyNetworkRequested(@NonNull MdnsServiceBrowserListener listener,
            @Nullable Network network, @NonNull SocketCreationCallback socketCreationCallback) {
        ensureRunningOnHandlerThread(mHandler);
        InterfaceSocketCallback callback = mRequestedNetworks.get(listener);
        if (callback != null) {
            throw new IllegalArgumentException("Can not register duplicated listener");
        }

        if (DBG) Log.d(TAG, "notifyNetworkRequested: network=" + network);
        callback = new InterfaceSocketCallback(socketCreationCallback);
        mRequestedNetworks.put(listener, callback);
        mSocketProvider.requestSocket(network, callback);
    }

    /*** Notify that the network is unrequested */
    @Override
    public void notifyNetworkUnrequested(@NonNull MdnsServiceBrowserListener listener) {
        ensureRunningOnHandlerThread(mHandler);
        final InterfaceSocketCallback callback = mRequestedNetworks.get(listener);
        if (callback == null) {
            Log.e(TAG, "Can not be unrequested with unknown listener=" + listener);
            return;
        }
        callback.onNetworkUnrequested();
        // onNetworkUnrequested does cleanups based on mRequestedNetworks, only remove afterwards
        mRequestedNetworks.remove(listener);
        mSocketProvider.unrequestSocket(callback);
    }

    @Override
    public Looper getLooper() {
        return mHandler.getLooper();
    }

    private void sendMdnsPacket(@NonNull DatagramPacket packet, @Nullable Network targetNetwork) {
        final boolean isIpv6 = ((InetSocketAddress) packet.getSocketAddress()).getAddress()
                instanceof Inet6Address;
        final boolean isIpv4 = ((InetSocketAddress) packet.getSocketAddress()).getAddress()
                instanceof Inet4Address;
        final ArrayMap<MdnsInterfaceSocket, Network> activeSockets = getActiveSockets();
        for (int i = 0; i < activeSockets.size(); i++) {
            final MdnsInterfaceSocket socket = activeSockets.keyAt(i);
            final Network network = activeSockets.valueAt(i);
            // Check ip capability and network before sending packet
            if (((isIpv6 && socket.hasJoinedIpv6()) || (isIpv4 && socket.hasJoinedIpv4()))
                    // Contrary to MdnsUtils.isNetworkMatched, only send packets targeting
                    // the null network to interfaces that have the null network (tethering
                    // downstream interfaces).
                    && Objects.equals(network, targetNetwork)) {
                try {
                    socket.send(packet);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to send a mDNS packet.", e);
                }
            }
        }
    }

    private void processResponsePacket(byte[] recvbuf, int length, int interfaceIndex,
            @NonNull Network network) {
        int packetNumber = ++mReceivedPacketNumber;

        final MdnsPacket response;
        try {
            response = MdnsResponseDecoder.parseResponse(recvbuf, length);
        } catch (MdnsPacket.ParseException e) {
            if (e.code != MdnsResponseErrorCode.ERROR_NOT_RESPONSE_MESSAGE) {
                Log.e(TAG, e.getMessage(), e);
                if (mCallback != null) {
                    mCallback.onFailedToParseMdnsResponse(packetNumber, e.code, network);
                }
            }
            return;
        }

        if (mCallback != null) {
            mCallback.onResponseReceived(response, interfaceIndex, network);
        }
    }

    /**
     * Sends a mDNS request packet via given network that asks for multicast response. Null network
     * means sending packet via all networks.
     */
    @Override
    public void sendMulticastPacket(@NonNull DatagramPacket packet, @Nullable Network network) {
        mHandler.post(() -> sendMdnsPacket(packet, network));
    }

    /**
     * Sends a mDNS request packet via given network that asks for unicast response. Null network
     * means sending packet via all networks.
     */
    @Override
    public void sendUnicastPacket(@NonNull DatagramPacket packet, @Nullable Network network) {
        // TODO: Separate unicast packet.
        mHandler.post(() -> sendMdnsPacket(packet, network));
    }
}
