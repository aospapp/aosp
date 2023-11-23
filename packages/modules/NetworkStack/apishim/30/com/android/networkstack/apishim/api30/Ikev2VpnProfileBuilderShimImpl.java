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

package com.android.networkstack.apishim.api30;

import android.net.Ikev2VpnProfile;
import android.net.ProxyInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.networkstack.apishim.common.Ikev2VpnProfileBuilderShim;
import com.android.networkstack.apishim.common.Ikev2VpnProfileShim;
import com.android.networkstack.apishim.common.UnsupportedApiLevelException;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Implementation of Ikev2VpnProfileBuilderShim for API 30.
 */
@RequiresApi(Build.VERSION_CODES.R)
public class Ikev2VpnProfileBuilderShimImpl
        implements Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> {
    protected final Ikev2VpnProfile.Builder mBuilder;

    protected Ikev2VpnProfileBuilderShimImpl(@NonNull Ikev2VpnProfile.Builder builder) {
        mBuilder = builder;
    }

    protected Ikev2VpnProfileBuilderShimImpl(@NonNull String serverAddr,
            @NonNull String identity) {
        mBuilder = new Ikev2VpnProfile.Builder(serverAddr, identity);
    }

    /**
     * Returns a new instance of this shim impl.
     */
    public static Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> newInstance(
            @NonNull String serverAddr, @NonNull String identity) {
        return new Ikev2VpnProfileBuilderShimImpl(serverAddr, identity);
    }

    @Override
    public Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> setAuthPsk(@NonNull byte[] psk) {
        mBuilder.setAuthPsk(psk);
        return this;
    }

    @Override
    public Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> setAuthUsernamePassword(
            @NonNull String user, @NonNull String pass, @Nullable X509Certificate serverRootCa)
            throws UnsupportedApiLevelException {
        mBuilder.setAuthUsernamePassword(user, pass, serverRootCa);
        return this;
    }

    @Override
    public Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> setAuthDigitalSignature(
            @NonNull X509Certificate userCert, @NonNull PrivateKey key,
            @Nullable X509Certificate serverRootCa) {
        mBuilder.setAuthDigitalSignature(userCert, key, serverRootCa);
        return this;
    }

    @Override
    public Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> setBypassable(boolean isBypassable) {
        mBuilder.setBypassable(true);
        return this;
    }

    @Override
    public Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> setProxy(@Nullable ProxyInfo proxy) {
        mBuilder.setProxy(proxy);
        return this;
    }

    @Override
    public Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> setMaxMtu(int mtu) {
        mBuilder.setMaxMtu(mtu);
        return this;
    }

    @Override
    public Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> setMetered(boolean isMetered) {
        mBuilder.setMetered(isMetered);
        return this;
    }

    @Override
    public Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> setAllowedAlgorithms(
            @NonNull List<String> algorithmNames) {
        mBuilder.setAllowedAlgorithms(algorithmNames);
        return this;
    }

    @Override
    public Ikev2VpnProfile.Builder getBuilder() {
        return mBuilder;
    }

    @Override
    public Ikev2VpnProfileShim build() {
        return Ikev2VpnProfileShimImpl.newInstance(mBuilder.build());
    }
}
