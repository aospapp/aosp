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

package android.scopedstorage.cts.device;

import static android.scopedstorage.cts.device.FileCreationUtils.createContentFromResource;
import static android.scopedstorage.cts.lib.TestUtils.createFileAs;
import static android.scopedstorage.cts.lib.TestUtils.deleteFileAsNoThrow;
import static android.scopedstorage.cts.lib.TestUtils.getDcimDir;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.util.Log;

import com.android.cts.install.lib.TestApp;

import org.jetbrains.annotations.NotNull;
import org.junit.rules.ExternalResource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class OtherAppFilesRule extends ExternalResource {

    private static final String TAG = "OtherAppFilesRule";
    private static final TestApp APP_D_LEGACY_HAS_RW = new TestApp("TestAppDLegacy",
            "android.scopedstorage.cts.testapp.D", 1, false, "CtsScopedStorageTestAppDLegacy.apk");
    private static final String NONCE = String.valueOf(System.nanoTime());
    private static final String IMAGE_1 = TAG + "_image1_" + NONCE + ".jpg";
    private static final String IMAGE_2 = TAG + "_image2_" + NONCE + ".jpg";
    private static final String VIDEO_1 = TAG + "_video1_" + NONCE + ".mp4";
    private static final String VIDEO_2 = TAG + "_video2_" + NONCE + ".mp4";

    private final ContentResolver mContentResolver;
    private final File mImageFile1 = new File(getDcimDir(), IMAGE_1);
    private final File mImageFile2 = new File(getDcimDir(), IMAGE_2);
    private final File mVideoFile1 = new File(getDcimDir(), VIDEO_1);
    private final File mVideoFile2 = new File(getDcimDir(), VIDEO_2);

    private Uri mImageUri1;
    private Uri mImageUri2;
    private Uri mVideoUri1;
    private Uri mVideoUri2;

    public OtherAppFilesRule(ContentResolver contentResolver) {
        this.mContentResolver = contentResolver;
    }

    @Override
    protected void before() throws Exception {
        File buffer = new File(getDcimDir(), "OtherAppFilesRule_buffer_" + NONCE + ".jpg");
        try {
            createContentFromResource(R.raw.img_with_metadata, buffer);
            mImageUri1 = createFileAsOther(buffer, mImageFile1);
            mImageUri2 = createFileAsOther(buffer, mImageFile2);
            mVideoUri1 = createEmptyFileAsOther(mVideoFile1);
            mVideoUri2 = createEmptyFileAsOther(mVideoFile2);

        } finally {
            buffer.delete();
        }
    }

    @Override
    protected void after() {
        deleteFileAsNoThrow(APP_D_LEGACY_HAS_RW, mImageFile1.getAbsolutePath());
        deleteFileAsNoThrow(APP_D_LEGACY_HAS_RW, mImageFile2.getAbsolutePath());
        deleteFileAsNoThrow(APP_D_LEGACY_HAS_RW, mVideoFile1.getAbsolutePath());
        deleteFileAsNoThrow(APP_D_LEGACY_HAS_RW, mVideoFile2.getAbsolutePath());
    }

    public File getImageFile1() {
        return mImageFile1;
    }

    public File getImageFile2() {
        return mImageFile2;
    }


    public File getVideoFile1() {
        return mVideoFile1;
    }

    public File getVideoFile2() {
        return mVideoFile2;
    }

    public Uri getImageUri1() {
        return mImageUri1;
    }

    public Uri getImageUri2() {
        return mImageUri2;
    }

    public Uri getVideoUri1() {
        return mVideoUri1;
    }

    public Uri getVideoUri2() {
        return mVideoUri2;
    }

    private Uri createFileAsOther(File buffer, File newFile)
            throws Exception {
        // Use a legacy app to create this file, since it could be outside shared storage.
        Log.d(TAG, "Creating file " + newFile);
        assertThat(createFileAs(APP_D_LEGACY_HAS_RW, newFile.getAbsolutePath(),
                createFileDescriptorBinder(buffer))).isTrue();
        final Uri mediaUri = MediaStore.scanFile(mContentResolver, newFile);
        assertThat(mediaUri).isNotNull();
        return mediaUri;
    }

    public Uri createEmptyFileAsOther(File file)
            throws Exception {
        // Use a legacy app to create this file, since it could be outside shared storage.
        Log.d(TAG, "Creating file " + file);
        assertThat(createFileAs(APP_D_LEGACY_HAS_RW, file.getAbsolutePath())).isTrue();
        final Uri mediaUri = MediaStore.scanFile(mContentResolver, file);
        assertThat(mediaUri).isNotNull();
        return mediaUri;
    }

    @NotNull
    private static IBinder createFileDescriptorBinder(File buffer) throws IOException {
        return new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                try (FileInputStream inputStream = new FileInputStream(buffer)) {
                    reply.writeFileDescriptor(inputStream.getFD());
                    return true;
                } catch (IOException e) {
                    Log.e(TAG, "Error reading resource", e);
                    return false;
                }
            }
        };
    }

}
