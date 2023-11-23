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

package com.android.tests.sdkprovider.restrictions.broadcasts;

import android.app.sdksandbox.LoadSdkException;
import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SandboxedSdkProvider;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;

public class BroadcastsTestSandboxedSdkProvider extends SandboxedSdkProvider {

    static class BroadcastTestSdkImpl extends IBroadcastSdkApi.Stub {
        private final Context mContext;

        public BroadcastTestSdkImpl(Context sdkContext) {
            mContext = sdkContext;
        }

        @Override
        public void registerBroadcastReceiver() {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SEND);
            filter.addAction(Intent.ACTION_VIEW);
            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {}
                    },
                    filter,
                    Context.RECEIVER_EXPORTED);
        }

        @Override
        public void registerBroadcastReceiverWithoutAction() {
            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {}
                    },
                    new IntentFilter(),
                    Context.RECEIVER_EXPORTED);
        }
    }

    @Override
    public SandboxedSdk onLoadSdk(Bundle params) throws LoadSdkException {
        return new SandboxedSdk(new BroadcastTestSdkImpl(getContext()));
    }

    @Override
    public View getView(Context windowContext, Bundle params, int width, int height) {
        return new View(windowContext);
    }
}
