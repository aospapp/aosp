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
package com.android.tests.sdkprovider.crashtest;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SandboxedSdkProvider;
import android.content.Context;
import android.os.Bundle;
import android.view.View;

import java.util.concurrent.Executors;

public class CrashTestSandboxedSdkProvider extends SandboxedSdkProvider {
    static class CrashTestSdkImpl extends ICrashTestSdkApi.Stub {
        private final Context mContext;

        CrashTestSdkImpl(Context context) {
            mContext = context;
        }

        @Override
        public void triggerCrash() {
            Executors.newSingleThreadExecutor()
                    .execute(
                            () -> {
                                throw new RuntimeException("This is a test exception");
                            });
        }

        @Override
        public ApplicationExitInfo getLastApplicationExitInfo() {
            final ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am == null) {
                return null;
            }
            return am.getHistoricalProcessExitReasons(mContext.getPackageName(), 0, 1).get(0);
        }
    }

    @Override
    public SandboxedSdk onLoadSdk(Bundle params) {
        return new SandboxedSdk(new CrashTestSdkImpl(getContext()));
    }

    @Override
    public View getView(Context windowContext, Bundle params, int width, int height) {
        return new View(windowContext);
    }
}
