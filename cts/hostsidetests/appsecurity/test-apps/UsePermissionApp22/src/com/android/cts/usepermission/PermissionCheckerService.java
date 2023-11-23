/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.cts.usepermission;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * A service that can check if a permission is currently granted
 */
public class PermissionCheckerService extends IntentService {
    private final String REVIEW_PERMISSION_PKG = "com.android.cts.reviewpermissionhelper";

    public PermissionCheckerService() {
        super(PermissionCheckerService.class.getSimpleName());
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // Load bundle with context of client package so ResultReceiver class can be resolved
        Context context;
        try {
            context = createPackageContext(REVIEW_PERMISSION_PKG,
                    CONTEXT_INCLUDE_CODE | CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Cannot find client package " + REVIEW_PERMISSION_PKG);
        }
        ClassLoader cl = context.getClassLoader();
        Bundle bundle = intent.getExtras();
        bundle.setClassLoader(cl);

        ResultReceiver result = bundle.getParcelable(getPackageName() + ".RESULT");
        String permission = bundle.getString(getPackageName() + ".PERMISSION");

        result.send(checkSelfPermission(permission), null);
    }
}
