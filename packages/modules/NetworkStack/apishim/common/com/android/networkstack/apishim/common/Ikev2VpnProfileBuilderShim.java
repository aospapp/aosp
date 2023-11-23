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

package com.android.networkstack.apishim.common;

import android.net.ProxyInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * A shim for Ikev2VpnProfile.Builder.
 *
 * T should extend Ikev2VpnProfile.Builder, but this can't be written here as that class is not
 * available in API29.
 * @param <T> type of builder, typically Ikev2VpnProfile.Builder
 */
// TODO : when API29 is no longer supported, remove the type argument
public interface Ikev2VpnProfileBuilderShim<T> {
    /**
     * @see Ikev2VpnProfile.Builder#setRequiresInternetValidation(boolean)
     */
    default Ikev2VpnProfileBuilderShim<T> setRequiresInternetValidation(
            boolean requiresInternetValidation) throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 33");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setAuthPsk(byte[])
     */
    default Ikev2VpnProfileBuilderShim<T> setAuthPsk(@NonNull byte[] psk)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 30");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setAuthUsernamePassword(String, String, X509Certificate)
     */
    default Ikev2VpnProfileBuilderShim<T> setAuthUsernamePassword(@NonNull String user,
            @NonNull String pass, @Nullable X509Certificate serverRootCa)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 30");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setAuthDigitalSignature(X509Certificate, PrivateKey,
     *      X509Certificate)
     */
    default Ikev2VpnProfileBuilderShim<T> setAuthDigitalSignature(@NonNull X509Certificate userCert,
            @NonNull PrivateKey key, @Nullable X509Certificate serverRootCa)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 30");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setBypassable(boolean)
     */
    default Ikev2VpnProfileBuilderShim<T> setBypassable(boolean isBypassable)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 30");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setProxy(ProxyInfo)
     */
    default Ikev2VpnProfileBuilderShim<T> setProxy(@Nullable ProxyInfo proxy)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 30");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setMaxMtu(int)
     */
    default Ikev2VpnProfileBuilderShim<T> setMaxMtu(int mtu) throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 30");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setMetered(boolean)
     */
    default Ikev2VpnProfileBuilderShim<T> setMetered(boolean isMetered)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 30");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setAllowedAlgorithms(List<String>)
     */
    default Ikev2VpnProfileBuilderShim<T> setAllowedAlgorithms(@NonNull List<String> algorithmNames)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 30");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setLocalRoutesExcluded(boolean)
     */
    default Ikev2VpnProfileBuilderShim<T> setLocalRoutesExcluded(boolean excludeLocalRoutes)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 33");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setAutomaticIpVersionSelectionEnabled(boolean)
     */
    default Ikev2VpnProfileBuilderShim<T> setAutomaticIpVersionSelectionEnabled(boolean isEnabled)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 34");
    }

    /**
     * @see Ikev2VpnProfile.Builder#setAutomaticNattKeepaliveTimerEnabled(boolean)
     */
    default Ikev2VpnProfileBuilderShim<T> setAutomaticNattKeepaliveTimerEnabled(boolean isEnabled)
            throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 34");
    }

    /**
     * Get <T> type of builder, typically Ikev2VpnProfile.Builder
     */
    default T getBuilder() throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 30");
    }

    /**
     * Build an Ikev2VpnProfileShim
     */
    default Ikev2VpnProfileShim build() throws UnsupportedApiLevelException {
        throw new UnsupportedApiLevelException("Only supported from API 30");
    }
}
