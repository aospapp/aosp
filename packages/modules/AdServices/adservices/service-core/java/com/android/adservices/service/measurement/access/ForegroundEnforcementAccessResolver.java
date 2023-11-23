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

package com.android.adservices.service.measurement.access;

import android.adservices.common.AdServicesStatusUtils;
import android.annotation.NonNull;
import android.content.Context;

import com.android.adservices.LogUtil;
import com.android.adservices.service.common.AppImportanceFilter;
import com.android.adservices.service.common.compat.ProcessCompatUtils;

import java.util.function.Supplier;

/** Resolves if API was called from foreground. */
public class ForegroundEnforcementAccessResolver implements IAccessResolver {
    private static final String ERROR_MESSAGE = "Measurement API was not called from foreground.";
    private final int mAppNameId;
    private final int mCallingUid;
    private final AppImportanceFilter mAppImportanceFilter;
    private final boolean mEnforceForegroundStatus;

    public ForegroundEnforcementAccessResolver(
            int appNameId,
            int callingUid,
            @NonNull AppImportanceFilter appImportanceFilter,
            @NonNull Supplier<Boolean> enforceForegroundStatus) {
        mEnforceForegroundStatus = enforceForegroundStatus.get();
        mAppNameId = appNameId;
        mAppImportanceFilter = appImportanceFilter;
        mCallingUid = callingUid;
    }

    @Override
    public boolean isAllowed(@NonNull Context context) {
        if (!mEnforceForegroundStatus) {
            LogUtil.d("Enforcement foreground flag has been disabled");
            return true;
        }

        if (ProcessCompatUtils.isSdkSandboxUid(mCallingUid)) {
            LogUtil.d("Foreground check skipped, app running on Sandbox");
            return true;
        }

        // @throws AppImportanceFilter.WrongCallingApplicationStateException if not in foreground
        try {
            mAppImportanceFilter.assertCallerIsInForeground(mCallingUid, mAppNameId, null);
        } catch (AppImportanceFilter.WrongCallingApplicationStateException e) {
            LogUtil.e("App not running in foreground");
            return false;
        }

        return true;
    }

    @NonNull
    @Override
    @AdServicesStatusUtils.StatusCode
    public int getErrorStatusCode() {
        return AdServicesStatusUtils.STATUS_BACKGROUND_CALLER;
    }

    @NonNull
    @Override
    public String getErrorMessage() {
        return ERROR_MESSAGE;
    }
}
