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

package com.android.networkstack.apishim.common;

import android.net.Network;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdManager.DiscoveryListener;
import android.net.nsd.NsdManager.RegistrationListener;
import android.net.nsd.NsdManager.ResolveListener;
import android.net.nsd.NsdServiceInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.Executor;

/** Shim for NSD APIs, including {@link android.net.nsd.NsdManager} and
 * {@link android.net.nsd.NsdServiceInfo}. */
public interface NsdShim {
    /**
     * @see NsdServiceInfo#getNetwork()
     */
    @Nullable
    Network getNetwork(@NonNull NsdServiceInfo serviceInfo);

    /**
     * @see NsdServiceInfo#setNetwork(Network)
     */
    void setNetwork(@NonNull NsdServiceInfo serviceInfo, @Nullable Network network);

    /**
     * @see NsdManager#registerService(NsdServiceInfo, int, Executor, RegistrationListener)
     */
    void registerService(@NonNull NsdManager nsdManager, @NonNull NsdServiceInfo serviceInfo,
            int protocolType, @NonNull Executor executor, @NonNull RegistrationListener listener)
            throws UnsupportedApiLevelException;

    /**
     * @see NsdManager#discoverServices(String, int, Network, Executor, DiscoveryListener)
     */
    void discoverServices(@NonNull NsdManager nsdManager, @NonNull String serviceType,
            int protocolType, @Nullable Network network,
            @NonNull Executor executor, @NonNull DiscoveryListener listener)
            throws UnsupportedApiLevelException;

    /**
     * @see NsdManager#resolveService(NsdServiceInfo, Executor, ResolveListener)
     */
    void resolveService(@NonNull NsdManager nsdManager, @NonNull NsdServiceInfo serviceInfo,
            @NonNull Executor executor, @NonNull ResolveListener resolveListener)
            throws UnsupportedApiLevelException;

    /**
     * @see NsdManager#discoverServices(String, int, NetworkRequest, Executor, DiscoveryListener)
     */
    void discoverServices(@NonNull NsdManager nsdManager, @NonNull String serviceType,
            int protocolType, @Nullable NetworkRequest request,
            @NonNull Executor executor, @NonNull DiscoveryListener listener)
            throws UnsupportedApiLevelException;

    /**
     * @see NsdManager#stopServiceResolution(ResolveListener)
     */
    void stopServiceResolution(@NonNull NsdManager nsdManager,
            @NonNull ResolveListener resolveListener) throws UnsupportedApiLevelException;

    /**
     * @see NsdManager#ServiceInfoCallback
     */
    interface ServiceInfoCallbackShim {
        default void onServiceInfoCallbackRegistrationFailed(int errorCode) {}
        default void onServiceUpdated(@NonNull NsdServiceInfo serviceInfo) {}
        default void onServiceLost() {}
        default void onServiceInfoCallbackUnregistered() {}
    }

    /**
     * @see NsdManager#registerServiceInfoCallback
     */
    default void registerServiceInfoCallback(@NonNull NsdManager nsdManager,
            @NonNull NsdServiceInfo serviceInfo, @NonNull Executor executor,
            @NonNull ServiceInfoCallbackShim listener)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Service callback is only supported on U+");
    }

    /**
     * @see NsdManager#unregisterServiceInfoCallback
     */
    default void unregisterServiceInfoCallback(@NonNull NsdManager nsdManager,
            @NonNull ServiceInfoCallbackShim listener)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Service callback is only supported on U+");
    }

    /**
     * @see NsdServiceInfo#getHostAddresses()
     */
    @NonNull
    default List<InetAddress> getHostAddresses(@NonNull NsdServiceInfo serviceInfo)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("getHostAddresses is only supported on U+");
    }

    /**
     * @see NsdServiceInfo#setHostAddresses(List<InetAddress>)
     */
    default void setHostAddresses(@NonNull NsdServiceInfo serviceInfo,
            @NonNull List<InetAddress> addresses) throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("setHostAddresses is only supported on U+");
    }
}
