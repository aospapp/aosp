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

import com.android.adservices.service.common.AllowLists;

/** Used for web context and general API access APIs. Checks if the package has access to them. */
public class AppPackageAccessResolver implements IAccessResolver {
    private static final String ERROR_MESSAGE = "Package %s is not allowed to call the API.";
    private final String mAllowList;
    private final String mPackageName;

    public AppPackageAccessResolver(@NonNull String allowList, @NonNull String packageName) {
        mAllowList = allowList;
        mPackageName = packageName;
    }

    @Override
    public boolean isAllowed(@NonNull Context context) {
        return AllowLists.isPackageAllowListed(mAllowList, mPackageName);
    }

    @NonNull
    @Override
    @AdServicesStatusUtils.StatusCode
    public int getErrorStatusCode() {
        return AdServicesStatusUtils.STATUS_CALLER_NOT_ALLOWED;
    }

    @NonNull
    @Override
    public String getErrorMessage() {
        return String.format(ERROR_MESSAGE, mPackageName);
    }
}
