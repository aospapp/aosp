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

package com.android.tests.sdkprovider.restrictions.contentproviders;

import android.app.sdksandbox.LoadSdkException;
import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SandboxedSdkProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.UserDictionary;
import android.view.View;

public class ContentProvidersTestSandboxedSdkProvider extends SandboxedSdkProvider {

    static class ContentProvidersTestSdkImpl extends IContentProvidersSdkApi.Stub {
        private final Context mContext;

        ContentProvidersTestSdkImpl(Context sdkContext) {
            mContext = sdkContext;
        }

        @Override
        public void getContentProvider() {
            final ContentResolver contentResolver = mContext.getContentResolver();
            contentResolver.query(
                    UserDictionary.Words.CONTENT_URI,
                    /*projection=*/ null,
                    /*queryArgs=*/ null,
                    /*cancellationSignal=*/ null);
        }

        @Override
        public void registerContentObserver() {
            final ContentResolver contentResolver = mContext.getContentResolver();
            final ContentObserver observer =
                    new ContentObserver(new Handler(Looper.getMainLooper())) {};
            contentResolver.registerContentObserver(
                    UserDictionary.Words.CONTENT_URI, /*notifyForDescendants=*/ true, observer);
        }
    }

    @Override
    public SandboxedSdk onLoadSdk(Bundle params) throws LoadSdkException {
        return new SandboxedSdk(new ContentProvidersTestSdkImpl(getContext()));
    }

    @Override
    public View getView(Context windowContext, Bundle params, int width, int height) {
        return new View(windowContext);
    }
}
