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
package android.app.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class DownloadManagerLegacyTest extends DownloadManagerTestBase {
    @Test
    public void testAddCompletedDownload() throws Exception {
        final String[] filePaths = new String[] {
                createFile(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "file1.txt").getPath(),
                "/sdcard/Download/file2.txt",
        };

        for (String path : filePaths) {
            final String fileContents = "Test content:" + path + "_" + System.nanoTime();

            final File file = new File(path);
            writeToFile(file, fileContents);

            final long id = mDownloadManager.addCompletedDownload(file.getName(), "Test desc", true,
                    "text/plain", path, fileContents.getBytes().length, true);
            final String actualContents = readFromFile(mDownloadManager.openDownloadedFile(id));
            assertEquals(fileContents, actualContents);

            final Uri downloadUri = mDownloadManager.getUriForDownloadedFile(id);
            mContext.grantUriPermission("com.android.shell", downloadUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            final String rawFilePath = getRawFilePath(downloadUri);
            final String rawFileContents = readFromRawFile(rawFilePath);
            assertEquals(fileContents, rawFileContents);
            assertRemoveDownload(id, 0);
        }
    }

    @Test
    public void testAddCompletedDownload_invalidPublicDir() throws Exception {
        final File file = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "colors.txt");
        final String fileContents = "RED;GREEN;BLUE";
        writeToFile(file, fileContents);
        try {
            mDownloadManager.addCompletedDownload(file.getName(), "Test desc", true,
                    "text/plain", file.getPath(), fileContents.getBytes().length, true);
            fail(file + " is not valid for addCompletedDownload()");
        } catch (Exception e) {
            // expected
        }
    }

    /**
     * Add a file using DownloadManager.addCompleteDownload
     * and verify that the file has been added to MediaStore as well.
     */
    @Test
    public void testAddCompletedDownload_mediaStoreEntry() throws Exception {
        final String[] downloadPaths = {
                new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "file1.txt").getPath(),
                "/sdcard/Download/file2.txt",
        };
        for (String downloadLocation : downloadPaths) {
            final String fileContents =
                    "Test content:" + downloadLocation + "_" + System.nanoTime();
            final File file = new File(downloadLocation);
            writeToFile(file, fileContents);

            final long downloadId = mDownloadManager.addCompletedDownload(
                    file.getName(), "Test desc",
                    true, "text/plain", downloadLocation, fileContents.getBytes().length, true);
            assertTrue(downloadId >= 0);
            final Uri downloadUri = mDownloadManager.getUriForDownloadedFile(downloadId);
            mContext.grantUriPermission("com.android.shell", downloadUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            final Uri mediaStoreUri = getMediaStoreUri(downloadUri);

            assertEquals(fileContents, readContentsFromUri(mediaStoreUri));

            // Delete entry in DownloadProvider and verify it's deleted from MediaProvider as well.
            assertRemoveDownload(downloadId, 0);
            try (Cursor cursor = mContext.getContentResolver().query(
                    mediaStoreUri, null, null, null)) {
                assertEquals(0, cursor.getCount());
            }
        }
    }
}