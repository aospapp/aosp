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

package com.android.networkstack.apishim.api33;

import android.net.Ikev2VpnProfile;
import android.net.ipsec.ike.IkeTunnelConnectionParams;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;
import com.android.networkstack.apishim.common.Ikev2VpnProfileBuilderShim;
import com.android.networkstack.apishim.common.UnsupportedApiLevelException;

/**
 * A shim for Ikev2VpnProfile.Builder
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class Ikev2VpnProfileBuilderShimImpl
        extends com.android.networkstack.apishim.api30.Ikev2VpnProfileBuilderShimImpl {
    protected Ikev2VpnProfileBuilderShimImpl(@NonNull IkeTunnelConnectionParams params) {
        super(new Ikev2VpnProfile.Builder(params));
    }

    protected Ikev2VpnProfileBuilderShimImpl(@NonNull String serverAddr,
            @NonNull String identity) {
        super(serverAddr, identity);
    }

    /**
     * Returns a new instance of this shim impl.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    public static Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> newInstance(
            @NonNull String serverAddr, @NonNull String identity) {
        if (SdkLevel.isAtLeastT()) {
            return new Ikev2VpnProfileBuilderShimImpl(serverAddr, identity);
        }
        return com.android.networkstack.apishim.api30.Ikev2VpnProfileBuilderShimImpl
                .newInstance(serverAddr, identity);
    }

    /**
     * Returns a new instance of this shim impl.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public static Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> newInstance(
            @NonNull IkeTunnelConnectionParams params) throws UnsupportedApiLevelException {
        if (SdkLevel.isAtLeastT()) {
            return new Ikev2VpnProfileBuilderShimImpl(params);
        } else {
            throw new UnsupportedApiLevelException("Only supported from API 33");
        }
    }

    /**
     * @see Ikev2VpnProfile.Builder#setRequiresInternetValidation(boolean)
     */
    @Override
    public Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> setRequiresInternetValidation(
            boolean requiresInternetValidation) {
        mBuilder.setRequiresInternetValidation(requiresInternetValidation);
        return this;
    }

    /**
     * @see Ikev2VpnProfile.Builder#setLocalRoutesExcluded(boolean)
     */
    @Override
    public Ikev2VpnProfileBuilderShim<Ikev2VpnProfile.Builder> setLocalRoutesExcluded(
            boolean excludeLocalRoutes) {
        mBuilder.setLocalRoutesExcluded(excludeLocalRoutes);
        return this;
    }
}
