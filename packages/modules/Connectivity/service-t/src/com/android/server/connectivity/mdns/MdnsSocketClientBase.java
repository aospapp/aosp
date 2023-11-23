/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.os.Looper;

import java.io.IOException;
import java.net.DatagramPacket;

/**
 * Base class for multicast socket client.
 *
 * @hide
 */
public interface MdnsSocketClientBase {
    /*** Start mDns discovery on given network. */
    default void startDiscovery() throws IOException { }

    /*** Stop mDns discovery. */
    default void stopDiscovery() { }

    /*** Set callback for receiving mDns response */
    void setCallback(@Nullable Callback callback);

    /**
     * Send a mDNS request packet via given network that asks for multicast response.
     *
     * <p>The socket client may use a null network to identify some or all interfaces, in which case
     * passing null sends the packet to these.
     */
    void sendMulticastPacket(@NonNull DatagramPacket packet, @Nullable Network network);

    /**
     * Send a mDNS request packet via given network that asks for unicast response.
     *
     * <p>The socket client may use a null network to identify some or all interfaces, in which case
     * passing null sends the packet to these.
     */
    void sendUnicastPacket(@NonNull DatagramPacket packet, @Nullable Network network);

    /*** Notify that the given network is requested for mdns discovery / resolution */
    void notifyNetworkRequested(@NonNull MdnsServiceBrowserListener listener,
            @Nullable Network network, @NonNull SocketCreationCallback socketCreationCallback);

    /*** Notify that the network is unrequested */
    default void notifyNetworkUnrequested(@NonNull MdnsServiceBrowserListener listener) { }

    /*** Gets looper that used by the socket client */
    default Looper getLooper() {
        return null;
    }

    /*** Callback for mdns response  */
    interface Callback {
        /*** Receive a mdns response */
        void onResponseReceived(@NonNull MdnsPacket packet, int interfaceIndex,
                @Nullable Network network);

        /*** Parse a mdns response failed */
        void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode,
                @Nullable Network network);
    }

    /*** Callback for requested socket creation  */
    interface SocketCreationCallback {
        /*** Notify requested socket is created */
        void onSocketCreated(@Nullable Network network);

        /*** Notify requested socket is destroyed */
        void onAllSocketsDestroyed(@Nullable Network network);
    }
}
