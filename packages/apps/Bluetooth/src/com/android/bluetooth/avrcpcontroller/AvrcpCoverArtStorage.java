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

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * An abstraction of the file system storage of the downloaded cover art images.
 */
public class AvrcpCoverArtStorage {
    private static final String TAG = "AvrcpCoverArtStorage";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;

    /**
     * Create and initialize this Cover Art storage interface
     */
    public AvrcpCoverArtStorage(Context context) {
        mContext = context;
    }

    /**
     * Determine if an image already exists in storage
     *
     * @param device - The device the images was downloaded from
     * @param imageHandle - The handle that identifies the image
     */
    public boolean doesImageExist(BluetoothDevice device, String imageHandle) {
        if (device == null || imageHandle == null || "".equals(imageHandle)) return false;
        String path = getImagePath(device, imageHandle);
        if (path == null) return false;
        File file = new File(path);
        return file.exists();
    }

    /**
     * Retrieve an image file from storage
     *
     * @param device - The device the images was downloaded from
     * @param imageHandle - The handle that identifies the image
     * @return A file descriptor for the image
     */
    public File getImageFile(BluetoothDevice device, String imageHandle) {
        if (device == null || imageHandle == null || "".equals(imageHandle)) return null;
        String path = getImagePath(device, imageHandle);
        if (path == null) return null;
        File file = new File(path);
        return file.exists() ? file : null;
    }

    /**
     * Add an image to storage
     *
     * @param device - The device the images was downloaded from
     * @param imageHandle - The handle that identifies the image
     * @param image - The image
     */
    public Uri addImage(BluetoothDevice device, String imageHandle, Bitmap image) {
        debug("Storing image '" + imageHandle + "' from device " + device);
        if (device == null || imageHandle == null || "".equals(imageHandle) || image == null) {
            debug("Cannot store image. Improper aruguments");
            return null;
        }

        String path = getImagePath(device, imageHandle);
        if (path == null) {
            error("Cannot store image. Cannot provide a valid path to storage");
            return null;
        }

        try {
            String deviceDirectoryPath = getDevicePath(device);
            if (deviceDirectoryPath == null) {
                error("Cannot store image. Cannot get a valid path to per-device storage");
                return null;
            }
            File deviceDirectory = new File(deviceDirectoryPath);
            if (!deviceDirectory.exists()) {
                deviceDirectory.mkdirs();
            }

            FileOutputStream outputStream = new FileOutputStream(path);
            image.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            outputStream.flush();
            outputStream.close();
        } catch (IOException e) {
            error("Failed to store '" + imageHandle + "' to '" + path + "'");
            return null;
        }
        Uri uri = AvrcpCoverArtProvider.getImageUri(device, imageHandle);
        mContext.getContentResolver().notifyChange(uri, null);
        debug("Image stored at '" + path + "'");
        return uri;
    }

    /**
     * Remove a specific image
     *
     * @param device The device you wish to have images removed for
     * @param imageHandle The handle that identifies the image to delete
     */
    public void removeImage(BluetoothDevice device, String imageHandle) {
        debug("Removing image '" + imageHandle + "' from device " + device);
        if (device == null || imageHandle == null || "".equals(imageHandle)) return;
        String path = getImagePath(device, imageHandle);
        if (path == null) {
            error("Cannot remove image. Cannot get a valid path to storage");
            return;
        }
        File file = new File(path);
        if (!file.exists()) return;
        file.delete();
        debug("Image deleted at '" + path + "'");
    }

    /**
     * Remove all stored images associated with a device
     *
     * @param device The device you wish to have images removed for
     */
    public void removeImagesForDevice(BluetoothDevice device) {
        if (device == null) return;
        debug("Remove cover art for device " + device.getAddress());
        String deviceDirectoryPath = getDevicePath(device);
        if (deviceDirectoryPath == null) {
            error("Cannot remove images for device. Cannot get a valid path to storage");
            return;
        }
        File deviceDirectory = new File(deviceDirectoryPath);
        deleteStorageDirectory(deviceDirectory);
    }

    /**
     * Clear the entirety of storage
     */
    public void clear() {
        String storageDirectoryPath = getStorageDirectory();
        if (storageDirectoryPath == null) {
            error("Cannot remove images, cannot get a valid path to storage. Is it mounted?");
            return;
        }
        File storageDirectory = new File(storageDirectoryPath);
        deleteStorageDirectory(storageDirectory);
    }

    private String getStorageDirectory() {
        String dir = null;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            dir = mContext.getExternalFilesDir(null).getAbsolutePath() + "/coverart";
        } else {
            error("Cannot get storage directory, state=" + Environment.getExternalStorageState());
        }
        return dir;
    }

    private String getDevicePath(BluetoothDevice device) {
        String storageDir = getStorageDirectory();
        if (storageDir == null) return null;
        return storageDir + "/" + device.getAddress().replace(":", "");
    }

    private String getImagePath(BluetoothDevice device, String imageHandle) {
        String deviceDir = getDevicePath(device);
        if (deviceDir == null) return null;
        return deviceDir + "/" + imageHandle + ".png";
    }

    private void deleteStorageDirectory(File directory) {
        if (directory == null) {
            error("Cannot delete directory, file is null");
            return;
        }
        if (!directory.exists()) return;
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            debug("Deleting " + files[i].getAbsolutePath());
            if (files[i].isDirectory()) {
                deleteStorageDirectory(files[i]);
            } else {
                files[i].delete();
            }
        }
        directory.delete();
    }

    @Override
    public String toString() {
        String s = "CoverArtStorage:\n";
        String storageDirectory = getStorageDirectory();
        s += "    Storage Directory: " + storageDirectory + "\n";
        if (storageDirectory == null) {
            return s;
        }

        File storage = new File(storageDirectory);
        File[] devices = storage.listFiles();
        if (devices != null) {
            for (File deviceDirectory : devices) {
                s += "    " + deviceDirectory.getName() + ":\n";
                File[] images = deviceDirectory.listFiles();
                if (images == null) continue;
                for (File image : images) {
                    s += "      " + image.getName() + "\n";
                }
            }
        }
        return s;
    }

    private void debug(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }

    private void error(String msg) {
        Log.e(TAG, msg);
    }
}
