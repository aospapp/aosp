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

import androidx.annotation.NonNull;

import com.android.networkstack.apishim.common.Ikev2VpnProfileShim;
/**
 * Implementation of Ikev2VpnProfileShim for API 30.
 */
// TODO : when API29 is no longer supported, remove the type argument
public class Ikev2VpnProfileShimImpl implements Ikev2VpnProfileShim<Ikev2VpnProfile> {
    protected final Ikev2VpnProfile mProfile;

    protected Ikev2VpnProfileShimImpl(Ikev2VpnProfile profile) {
        mProfile = profile;
    }

    /**
     * Returns a new instance of this shim impl.
     */
    public static Ikev2VpnProfileShim<Ikev2VpnProfile> newInstance(
            @NonNull Ikev2VpnProfile profile) {
        return new Ikev2VpnProfileShimImpl(profile);
    }

    public Ikev2VpnProfile getProfile() {
        return mProfile;
    }
}
