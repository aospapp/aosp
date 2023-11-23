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

package com.android.networkstack.apishim.api34;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.ArrayMap;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;
import com.android.networkstack.apishim.common.NsdShim;
import com.android.networkstack.apishim.common.UnsupportedApiLevelException;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Implementation of {@link NsdShim}.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class NsdShimImpl extends com.android.networkstack.apishim.api33.NsdShimImpl {
    private final Map<ServiceInfoCallbackShim, ServiceInfoCallbackWrapper> mCbWrappers =
            Collections.synchronizedMap(new ArrayMap<>());

    /**
     * Get a new instance of {@link NsdShim}.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    public static NsdShim newInstance() {
        if (SdkLevel.isAtLeastU()) {
            return new NsdShimImpl();
        } else {
            return new com.android.networkstack.apishim.api33.NsdShimImpl();
        }
    }

    @Override
    public void stopServiceResolution(@NonNull NsdManager nsdManager,
            @NonNull NsdManager.ResolveListener resolveListener)
            throws UnsupportedApiLevelException {
        nsdManager.stopServiceResolution(resolveListener);
    }

    private static class ServiceInfoCallbackWrapper implements NsdManager.ServiceInfoCallback {
        @NonNull
        final ServiceInfoCallbackShim mListener;

        ServiceInfoCallbackWrapper(@NonNull ServiceInfoCallbackShim listener) {
            mListener = listener;
        }

        @Override
        public void onServiceInfoCallbackRegistrationFailed(int errorCode) {
            mListener.onServiceInfoCallbackRegistrationFailed(errorCode);
        }

        @Override
        public void onServiceUpdated(@NonNull NsdServiceInfo serviceInfo) {
            mListener.onServiceUpdated(serviceInfo);
        }

        @Override
        public void onServiceLost() {
            mListener.onServiceLost();
        }

        @Override
        public void onServiceInfoCallbackUnregistered() {
            mListener.onServiceInfoCallbackUnregistered();
        }
    };

    @Override
    public void registerServiceInfoCallback(@NonNull NsdManager nsdManager,
            @NonNull NsdServiceInfo serviceInfo, @NonNull Executor executor,
            @NonNull ServiceInfoCallbackShim listener) throws UnsupportedApiLevelException {
        Objects.requireNonNull(listener);
        final ServiceInfoCallbackWrapper wrapper = new ServiceInfoCallbackWrapper(listener);
        if (null != mCbWrappers.put(listener, wrapper)) {
            throw new IllegalArgumentException("Listener shims must not be reused");
        }
        nsdManager.registerServiceInfoCallback(serviceInfo, executor, wrapper);
    }

    @Override
    public void unregisterServiceInfoCallback(@NonNull NsdManager nsdManager,
            @NonNull ServiceInfoCallbackShim listener) throws UnsupportedApiLevelException {
        final ServiceInfoCallbackWrapper wrapper = mCbWrappers.remove(listener);
        if (wrapper == null) {
            throw new IllegalArgumentException("Listener was not registered");
        }
        nsdManager.unregisterServiceInfoCallback(wrapper);
    }

    @NonNull
    @Override
    public List<InetAddress> getHostAddresses(@NonNull NsdServiceInfo serviceInfo) {
        return serviceInfo.getHostAddresses();
    }

    @Override
    public void setHostAddresses(@NonNull NsdServiceInfo serviceInfo,
            @NonNull List<InetAddress> addresses) {
        serviceInfo.setHostAddresses(addresses);
    }
}
