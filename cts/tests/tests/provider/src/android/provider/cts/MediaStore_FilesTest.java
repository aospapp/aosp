/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.provider.cts;

import static android.provider.cts.MediaStoreTest.TAG;
import static android.provider.cts.ProviderTestUtils.containsId;
import static android.provider.cts.ProviderTestUtils.resolveVolumeName;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.Presubmit;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.io.IOException;

@Presubmit
@RunWith(Parameterized.class)
public class MediaStore_FilesTest {
    private Context mContext;
    private ContentResolver mResolver;

    private Uri mExternalImages;
    private Uri mExternalFiles;

    @Parameter(0)
    public String mVolumeName;

    @Parameters
    public static Iterable<? extends Object> data() {
        return ProviderTestUtils.getSharedVolumeNames();
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mResolver = mContext.getContentResolver();

        Log.d(TAG, "Using volume " + mVolumeName);
        mExternalImages = MediaStore.Images.Media.getContentUri(mVolumeName);
        mExternalFiles = MediaStore.Files.getContentUri(mVolumeName);
    }

    @Test
    public void testGetContentUri() throws Exception {
        Uri allFilesUri = mExternalFiles;

        ContentValues values = new ContentValues();

        // Add a path for a file and check that the returned uri appends a
        // path properly.
        String dataPath = new File(ProviderTestUtils.stageDir(mVolumeName),
                "does_not_really_exist.txt").getAbsolutePath();
        values.put(MediaColumns.DATA, dataPath);
        Uri fileUri = mResolver.insert(allFilesUri, values);
        long fileId = ContentUris.parseId(fileUri);
        assertEquals(fileUri, ContentUris.withAppendedId(allFilesUri, fileId));

        // Check that getContentUri with the file id produces the same url
        Uri rowUri = ContentUris.withAppendedId(mExternalFiles, fileId);
        assertEquals(fileUri, rowUri);

        // Check that the file count has increased.
        assertTrue(containsId(allFilesUri, fileId));

        // Check that the path we inserted was stored properly.
        assertStringColumn(fileUri, MediaColumns.DATA, dataPath);

        // Update the path and check that the database changed.
        String updatedPath = new File(ProviderTestUtils.stageDir(mVolumeName),
                "still_does_not_exist.txt").getAbsolutePath();
        values.put(MediaColumns.DATA, updatedPath);
        assertEquals(1, mResolver.update(fileUri, values, null, null));
        assertStringColumn(fileUri, MediaColumns.DATA, updatedPath);

        // check that inserting a duplicate entry fails
        Uri foo = mResolver.insert(allFilesUri, values);
        assertNull(foo);

        // Delete the file and observe that the file count decreased.
        assertEquals(1, mResolver.delete(fileUri, null, null));
        assertFalse(containsId(allFilesUri, fileId));

        // Make sure the deleted file is not returned by the cursor.
        Cursor cursor = mResolver.query(fileUri, null, null, null, null);
        try {
            assertFalse(cursor.moveToNext());
        } finally {
            cursor.close();
        }

        // insert file and check its parent
        values.clear();
        try {
            File stageDir = new File(ProviderTestUtils.stageDir(mVolumeName),
                    Environment.DIRECTORY_MUSIC);
            stageDir.mkdirs();
            String b = stageDir.getAbsolutePath();
            values.put(MediaColumns.DATA, b + "/testing" + System.nanoTime());
            fileUri = mResolver.insert(allFilesUri, values);
            cursor = mResolver.query(fileUri, new String[] { MediaStore.Files.FileColumns.PARENT },
                    null, null, null);
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            long parentid = cursor.getLong(0);
            assertTrue("got 0 parent for non root file", parentid != 0);

            cursor.close();
            cursor = mResolver.query(ContentUris.withAppendedId(allFilesUri, parentid),
                    new String[] { MediaColumns.DATA }, null, null, null);
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            String parentPath = cursor.getString(0);
            assertEquals(b, parentPath);

            mResolver.delete(fileUri, null, null);
        } catch (IOException e) {
            fail(e.getMessage());
        } finally {
            cursor.close();
        }
    }

    @Test
    public void testCaseSensitivity() throws IOException {
        final String name = "Test-" + System.nanoTime() + ".Mp3";
        final File dir = ProviderTestUtils.stageDir(mVolumeName);
        final File file = new File(dir, name);
        final File fileLower = new File(dir, name.toLowerCase());
        ProviderTestUtils.stageFile(R.raw.testmp3, file);

        Uri allFilesUri = mExternalFiles;
        ContentValues values = new ContentValues();
        values.put(MediaColumns.DATA, fileLower.getAbsolutePath());
        Uri fileUri = mResolver.insert(allFilesUri, values);
        try {
            ParcelFileDescriptor pfd = mResolver.openFileDescriptor(fileUri, "r");
            pfd.close();
        } finally {
            mResolver.delete(fileUri, null, null);
        }
    }

    @Test
    public void testAccessInternal() throws Exception {
        final Uri internalFiles = MediaStore.Files.getContentUri(MediaStore.VOLUME_INTERNAL);

        for (String valid : new String[] {
                "/system/media/" + System.nanoTime() + ".ogg",
        }) {
            final ContentValues values = new ContentValues();
            values.put(MediaColumns.DATA, valid);

            final Uri uri = mResolver.insert(internalFiles, values);
            assertNotNull(valid, uri);
            mResolver.delete(uri, null, null);
        }

        for (String invalid : new String[] {
                "/data/media/" + System.nanoTime() + ".jpg",
                "/data/system/appops.xml",
                "/data/data/com.android.providers.media/databases/internal.db",
                new File(Environment.getExternalStorageDirectory(), System.nanoTime() + ".jpg")
                        .getAbsolutePath(),
        }) {
            final ContentValues values = new ContentValues();
            values.put(MediaColumns.DATA, invalid);
            assertNull(invalid, mResolver.insert(internalFiles, values));
        }
    }

    @Test
    public void testAccess() throws Exception {
        final String path = MediaStore.getVolumePath(resolveVolumeName(mVolumeName))
                .getAbsolutePath();
        final Uri updateUri = ContentUris.withAppendedId(mExternalFiles,
                ContentUris.parseId(ProviderTestUtils.stageMedia(R.raw.volantis, mExternalImages)));

        for (String valid : new String[] {
                path + "/" + System.nanoTime() + ".jpg",
                path + "/DCIM/" + System.nanoTime() + ".jpg",
        }) {
            final ContentValues values = new ContentValues();
            values.put(MediaColumns.DATA, valid);

            final Uri uri = mResolver.insert(mExternalFiles, values);
            assertNotNull(valid, uri);
            mResolver.delete(uri, null, null);

            final int count = mResolver.update(updateUri, values, null, null);
            assertEquals(valid, 1, count);
        }

        for (String invalid : new String[] {
                "/data/media/" + System.nanoTime() + ".jpg",
                "/data/system/appops.xml",
                "/data/data/com.android.providers.media/databases/internal.db",
                path + "/../../../../../data/system/appops.xml",
        }) {
            final ContentValues values = new ContentValues();
            values.put(MediaColumns.DATA, invalid);

            try {
                assertNull(invalid, mResolver.insert(mExternalFiles, values));
            } catch (SecurityException tolerated) {
            }

            try {
                assertEquals(invalid, 0, mResolver.update(updateUri, values, null, null));
            } catch (SecurityException tolerated) {
            }
        }
    }

    @Test
    public void testUpdateMediaType() throws Exception {
        final File file = new File(ProviderTestUtils.stageDir(mVolumeName),
                "test" + System.nanoTime() + ".mp3");
        ProviderTestUtils.stageFile(R.raw.testmp3, file);

        Uri allFilesUri = mExternalFiles;
        ContentValues values = new ContentValues();
        values.put(MediaColumns.DATA, file.getAbsolutePath());
        values.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_AUDIO);
        Uri fileUri = mResolver.insert(allFilesUri, values);

        // There is special logic in MediaProvider#update() to update paths when a folder was moved
        // or renamed. It only checks whether newValues only has one column but assumes the provided
        // column is _data. We need to guard the case where there is only one column in newValues
        // and it's not _data.
        ContentValues newValues = new ContentValues(1);
        newValues.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_NONE);
        mResolver.update(fileUri, newValues, null, null);

        try (Cursor c = mResolver.query(
                fileUri, new String[] { FileColumns.MEDIA_TYPE }, null, null, null)) {
            c.moveToNext();
            assertEquals(FileColumns.MEDIA_TYPE_NONE,
                    c.getInt(c.getColumnIndex(FileColumns.MEDIA_TYPE)));
        }
    }

    @Test
    public void testDateAddedFrozen() throws Exception {
        final long startTime = (System.currentTimeMillis() / 1000);
        final File file = new File(ProviderTestUtils.stageDir(mVolumeName),
                "test" + System.nanoTime() + ".mp3");
        ProviderTestUtils.stageFile(R.raw.testmp3, file);

        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DATA, file.getAbsolutePath());
        values.put(MediaColumns.DATE_ADDED, 32);
        final Uri uri = mResolver.insert(mExternalFiles, values);

        assertTrue(queryLong(uri, MediaColumns.DATE_ADDED) >= startTime);

        values.clear();
        values.put(MediaColumns.DATE_ADDED, 64);
        mResolver.update(uri, values, null, null);

        assertTrue(queryLong(uri, MediaColumns.DATE_ADDED) >= startTime);
    }

    @Test
    public void testInPlaceUpdate_mediaFileWithInvalidRelativePath() throws Exception {
        final File file = new File(ProviderTestUtils.stageDownloadDir(mVolumeName),
                "test" + System.nanoTime() + ".jpg");
        ProviderTestUtils.stageFile(R.raw.scenery, file);
        Log.d(TAG, "Staged image file at " + file.getAbsolutePath());

        final ContentValues insertValues = new ContentValues();
        insertValues.put(MediaColumns.DATA, file.getAbsolutePath());
        insertValues.put(MediaStore.Images.ImageColumns.DESCRIPTION, "Not a cat photo");
        final Uri uri = mResolver.insert(mExternalImages, insertValues);
        assertEquals(0, queryLong(uri, MediaStore.Images.ImageColumns.IS_PRIVATE));
        assertStringColumn(uri, MediaStore.Images.ImageColumns.DESCRIPTION, "Not a cat photo");

        final ContentValues updateValues = new ContentValues();
        updateValues.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_IMAGE);
        updateValues.put(FileColumns.MIME_TYPE, "image/jpeg");
        updateValues.put(MediaStore.Images.ImageColumns.IS_PRIVATE, 1);
        int updateRows = mResolver.update(uri, updateValues, null, null);
        assertEquals(1, updateRows);
        // Only interested in update not throwing exception. No need in checking whenever values
        // were actually updates, as it is not in the scope of this test.
    }

    private long queryLong(Uri uri, String columnName) {
        try (Cursor c = mResolver.query(uri, new String[] { columnName }, null, null, null)) {
            assertTrue(c.moveToFirst());
            return c.getLong(0);
        }
    }

    private String queryString(Uri uri, String columnName) {
        try (Cursor c = mResolver.query(uri, new String[] { columnName }, null, null, null)) {
            assertTrue(c.moveToFirst());
            return c.getString(0);
        }
    }

    private void assertStringColumn(Uri fileUri, String columnName, String expectedValue) {
        assertEquals(expectedValue, queryString(fileUri, columnName));
    }
}
