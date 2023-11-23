/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.bluetooth.avrcpcontroller;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * A provider of downloaded cover art images.
 *
 * Cover art images are downloaded from remote devices and are promised to be "good" for the life of
 * a connection.
 *
 * Android applications are provided a Uri with their MediaMetadata and MediaItem objects that
 * points back to this provider. Uris are in the following format:
 *
 *   content://com.android.bluetooth.avrcpcontroller.AvrcpCoverArtProvider/<device>/<image-handle>
 *
 * It's expected by the Media framework that artwork at URIs will be available using the
 * ContentResolver#openInputStream and BitmapFactory#decodeStream functions. Our provider must
 * enable that usage pattern.
 */
public class AvrcpCoverArtProvider extends ContentProvider {
    private static final String TAG = "AvrcpCoverArtProvider";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private BluetoothAdapter mAdapter;
    private AvrcpCoverArtStorage mStorage;

    public AvrcpCoverArtProvider() {
    }

    static final String AUTHORITY = "com.android.bluetooth.avrcpcontroller.AvrcpCoverArtProvider";
    static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * Get the Uri for a cover art image based on the device and image handle
     *
     * @param device The Bluetooth device from which an image originated
     * @param imageHandle The provided handle of the cover artwork
     * @return The Uri this provider will store the downloaded image at
     */
    public static Uri getImageUri(BluetoothDevice device, String imageHandle) {
        if (device == null || imageHandle == null || "".equals(imageHandle)) return null;
        Uri uri = CONTENT_URI.buildUpon().appendQueryParameter("device", device.getAddress())
                .appendQueryParameter("handle", imageHandle)
                .build();
        debug("getImageUri -> " + uri.toString());
        return uri;
    }

    private ParcelFileDescriptor getImageDescriptor(BluetoothDevice device, String imageHandle)
            throws FileNotFoundException {
        debug("getImageDescriptor(" + device + ", " + imageHandle + ")");
        File file = mStorage.getImageFile(device, imageHandle);
        if (file == null) throw new FileNotFoundException();
        ParcelFileDescriptor pdf = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
        return pdf;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        debug("openFile(" + uri + ", '" + mode + "')");
        String address = null;
        String imageHandle = null;
        BluetoothDevice device = null;
        try {
            address = uri.getQueryParameter("device");
            imageHandle = uri.getQueryParameter("handle");
        } catch (NullPointerException e) {
            throw new FileNotFoundException();
        }

        try {
            device = mAdapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException();
        }

        return getImageDescriptor(device, imageHandle);
    }

    @Override
    public boolean onCreate() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mStorage = new AvrcpCoverArtStorage(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    private static void debug(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }
}
