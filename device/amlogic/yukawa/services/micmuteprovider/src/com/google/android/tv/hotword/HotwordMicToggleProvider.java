/*
** Copyright 2021, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.google.android.tv.hotwordmic;

import android.app.AppGlobals;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

public class HotwordMicToggleProvider extends ContentProvider {
    private static final String TAG = "HotwordMicToggleProvider";
    private static final boolean DEBUG = false;
    private static final String HOTWORDMIC_AUTH = "atv.hotwordmic";
    private static final String TOGGLE_STATE = "togglestate";
    public static final Uri HOTWORDMIC_URI = new Uri.Builder()
                                                     .scheme("content")
                                                     .authority(HOTWORDMIC_AUTH)
                                                     .appendPath(TOGGLE_STATE)
                                                     .build();

    private AudioManager mAudioManager;

    private boolean isAllowedPackage() {
        String pkg = getCallingPackage();
        boolean isUidPrivileged = false;
        try {
            isUidPrivileged =
                    AppGlobals.getPackageManager().isUidPrivileged(Binder.getCallingUid());
        } catch (RemoteException e) {
            Log.e(TAG, "isAllowedPackage uid err: " + e);
        }
        return !TextUtils.isEmpty(pkg) && isUidPrivileged;
    }

    public boolean isMicMuted() { return mAudioManager.isMicrophoneMute(); }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        if (HOTWORDMIC_URI.equals(uri) && isAllowedPackage()) {
            MatrixCursor cursor = new MatrixCursor(new String[] {"result"});
            boolean micMuted = isMicMuted();
            if (DEBUG) Log.v(TAG, "micMuted: " + micMuted);
            // URI values expected: MUTED (0), UNMUTED (1)
            cursor.addRow(new Integer[] {micMuted ? 0 : 1});
            return cursor;
        }
        Log.e(TAG, "Package not allowed");
        return null;
    }

    @Override
    public boolean onCreate() {
        mAudioManager = (AudioManager) getContext().getSystemService(AudioManager.class);
        return true;
    }

    // Unsupported
    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        return null;
    }

    // Unsupported
    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    // Unsupported
    @Override
    public int update(
            @NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    // Unsupported
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }
}
