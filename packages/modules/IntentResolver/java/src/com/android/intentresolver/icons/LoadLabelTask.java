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

package com.android.intentresolver.icons;

import android.content.Context;
import android.content.PermissionChecker;
import android.content.pm.ActivityInfo;
import android.os.AsyncTask;
import android.os.Trace;

import com.android.intentresolver.R;
import com.android.intentresolver.TargetPresentationGetter;
import com.android.intentresolver.chooser.DisplayResolveInfo;

import java.util.function.Consumer;

class LoadLabelTask extends AsyncTask<Void, Void, CharSequence[]> {
    private final Context mContext;
    private final DisplayResolveInfo mDisplayResolveInfo;
    private final boolean mIsAudioCaptureDevice;
    protected final TargetPresentationGetter.Factory mPresentationFactory;
    private final Consumer<CharSequence[]> mCallback;

    LoadLabelTask(Context context, DisplayResolveInfo dri,
            boolean isAudioCaptureDevice, TargetPresentationGetter.Factory presentationFactory,
            Consumer<CharSequence[]> callback) {
        mContext = context;
        mDisplayResolveInfo = dri;
        mIsAudioCaptureDevice = isAudioCaptureDevice;
        mPresentationFactory = presentationFactory;
        mCallback = callback;
    }

    @Override
    protected CharSequence[] doInBackground(Void... voids) {
        try {
            Trace.beginSection("app-label");
            return loadLabel();
        } finally {
            Trace.endSection();
        }
    }

    private CharSequence[] loadLabel() {
        TargetPresentationGetter pg = mPresentationFactory.makePresentationGetter(
                mDisplayResolveInfo.getResolveInfo());

        if (mIsAudioCaptureDevice) {
            // This is an audio capture device, so check record permissions
            ActivityInfo activityInfo = mDisplayResolveInfo.getResolveInfo().activityInfo;
            String packageName = activityInfo.packageName;

            int uid = activityInfo.applicationInfo.uid;
            boolean hasRecordPermission =
                    PermissionChecker.checkPermissionForPreflight(
                            mContext,
                            android.Manifest.permission.RECORD_AUDIO, -1, uid,
                            packageName)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;

            if (!hasRecordPermission) {
                // Doesn't have record permission, so warn the user
                return new CharSequence[]{
                        pg.getLabel(),
                        mContext.getString(R.string.usb_device_resolve_prompt_warn)
                };
            }
        }

        return new CharSequence[]{
                pg.getLabel(),
                pg.getSubLabel()
        };
    }

    @Override
    protected void onPostExecute(CharSequence[] result) {
        mCallback.accept(result);
    }
}
