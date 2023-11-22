/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.tv.settings.device.apps;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

/**
 * Handles uninstalling of an application.
 */
class UninstallManager {

    private final Activity mActivity;
    private final AppInfo mAppInfo;

    UninstallManager(Activity activity, AppInfo appInfo) {
        mActivity = activity;
        mAppInfo = appInfo;
    }

    boolean canUninstall() {
        return !mAppInfo.isUpdatedSystemApp() && !mAppInfo.isSystemApp();
    }

    void uninstall(int requestId) {
        if (canUninstall()) {
            uninstallPackage(!mAppInfo.isInstalled(), requestId);
        }
    }

    private void uninstallPackage(boolean allUsers, int requestId) {
        Uri packageURI = Uri.parse("package:" + mAppInfo.getPackageName());
        Intent uninstallIntent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageURI);
        uninstallIntent.putExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, allUsers);
        uninstallIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        uninstallIntent.putExtra(Intent.EXTRA_KEY_CONFIRM, true);
        mActivity.startActivityForResult(uninstallIntent, requestId);
    }
}
