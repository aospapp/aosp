/*
 * Copyright (C) 2009 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.usage.StorageStatsManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.platform.test.annotations.Presubmit;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Presubmit
@RunWith(Parameterized.class)
public class MediaStoreTest {
    static final String TAG = "MediaStoreTest";

    private static final long SIZE_DELTA = 32_000;

    private Context mContext;
    private ContentResolver mContentResolver;

    private Uri mExternalImages;

    @Parameter(0)
    public String mVolumeName;

    @Parameters
    public static Iterable<? extends Object> data() {
        return ProviderTestUtils.getSharedVolumeNames();
    }

    private Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mContentResolver = mContext.getContentResolver();

        Log.d(TAG, "Using volume " + mVolumeName);
        mExternalImages = MediaStore.Images.Media.getContentUri(mVolumeName);
    }

    @After
    public void tearDown() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void testGetMediaScannerUri() {
        // query
        Cursor c = mContentResolver.query(MediaStore.getMediaScannerUri(), null,
                null, null, null);
        assertEquals(1, c.getCount());
        c.close();
    }

    @Test
    public void testGetVersion() {
        // We should have valid versions to help detect data wipes
        assertNotNull(MediaStore.getVersion(getContext(), MediaStore.VOLUME_INTERNAL));
        assertNotNull(MediaStore.getVersion(getContext(), MediaStore.VOLUME_EXTERNAL));
        assertNotNull(MediaStore.getVersion(getContext(), MediaStore.VOLUME_EXTERNAL_PRIMARY));
    }

    @Test
    public void testGetExternalVolumeNames() {
        Set<String> volumeNames = MediaStore.getExternalVolumeNames(getContext());

        assertFalse(volumeNames.contains(MediaStore.VOLUME_INTERNAL));
        assertFalse(volumeNames.contains(MediaStore.VOLUME_EXTERNAL));
        assertTrue(volumeNames.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY));
    }

    @Test
    public void testGetStorageVolume() throws Exception {
        Assume.assumeFalse(MediaStore.VOLUME_EXTERNAL.equals(mVolumeName));

        final Uri uri = ProviderTestUtils.stageMedia(R.raw.volantis, mExternalImages);

        final StorageManager sm = mContext.getSystemService(StorageManager.class);
        final StorageVolume sv = sm.getStorageVolume(uri);

        // We should always have a volume for media we just created
        assertNotNull(sv);

        if (MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(mVolumeName)) {
            assertEquals(sm.getPrimaryStorageVolume(), sv);
        }
    }

    @Test
    public void testGetStorageVolume_Unrelated() throws Exception {
        final StorageManager sm = mContext.getSystemService(StorageManager.class);
        try {
            sm.getStorageVolume(Uri.parse("content://com.example/path/to/item/"));
            fail("getStorageVolume unrelated should throw exception");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testContributedMedia() throws Exception {
        // STOPSHIP: remove this once isolated storage is always enabled
        Assume.assumeTrue(StorageManager.hasIsolatedStorage());
        Assume.assumeTrue(MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(mVolumeName));

        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                android.Manifest.permission.CLEAR_APP_USER_DATA,
                android.Manifest.permission.PACKAGE_USAGE_STATS);

        // Start by cleaning up contributed items
        MediaStore.deleteContributedMedia(getContext(), getContext().getPackageName(),
                android.os.Process.myUserHandle());

        // Force sync to try updating other views
        ProviderTestUtils.executeShellCommand("sync");
        SystemClock.sleep(500);

        // Measure usage before
        final long beforePackage = getExternalPackageSize();
        final long beforeTotal = getExternalTotalSize();
        final long beforeContributed = MediaStore.getContributedMediaSize(getContext(),
                getContext().getPackageName(), android.os.Process.myUserHandle());

        final long stageSize;
        try (AssetFileDescriptor fd = getContext().getResources()
                .openRawResourceFd(R.raw.volantis)) {
            stageSize = fd.getLength();
        }

        // Create media both inside and outside sandbox
        final Uri inside;
        final Uri outside;
        final File file = new File(ProviderTestUtils.stageDir(mVolumeName),
                "cts" + System.nanoTime() + ".jpg");
        ProviderTestUtils.stageFile(R.raw.volantis, file);
        inside = ProviderTestUtils.scanFileFromShell(file);
        outside = ProviderTestUtils.stageMedia(R.raw.volantis, mExternalImages);

        {
            final HashSet<Long> visible = getVisibleIds(mExternalImages);
            assertTrue(visible.contains(ContentUris.parseId(inside)));
            assertTrue(visible.contains(ContentUris.parseId(outside)));

            // Force sync to try updating other views
            ProviderTestUtils.executeShellCommand("sync");
            SystemClock.sleep(500);

            final long afterPackage = getExternalPackageSize();
            final long afterTotal = getExternalTotalSize();
            final long afterContributed = MediaStore.getContributedMediaSize(getContext(),
                    getContext().getPackageName(), android.os.Process.myUserHandle());

            assertMostlyEquals(beforePackage + stageSize, afterPackage, SIZE_DELTA);
            assertMostlyEquals(beforeTotal + stageSize + stageSize, afterTotal, SIZE_DELTA);
            assertMostlyEquals(beforeContributed + stageSize, afterContributed, SIZE_DELTA);
        }

        // Delete only contributed items
        MediaStore.deleteContributedMedia(getContext(), getContext().getPackageName(),
                android.os.Process.myUserHandle());
        {
            final HashSet<Long> visible = getVisibleIds(mExternalImages);
            assertTrue(visible.contains(ContentUris.parseId(inside)));
            assertFalse(visible.contains(ContentUris.parseId(outside)));

            // Force sync to try updating other views
            ProviderTestUtils.executeShellCommand("sync");
            SystemClock.sleep(500);

            final long afterPackage = getExternalPackageSize();
            final long afterTotal = getExternalTotalSize();
            final long afterContributed = MediaStore.getContributedMediaSize(getContext(),
                    getContext().getPackageName(), android.os.Process.myUserHandle());

            assertMostlyEquals(beforePackage + stageSize, afterPackage, SIZE_DELTA);
            assertMostlyEquals(beforeTotal + stageSize, afterTotal, SIZE_DELTA);
            assertMostlyEquals(0, afterContributed, SIZE_DELTA);
        }
    }

    private long getExternalPackageSize() throws Exception {
        final StorageManager storage = getContext().getSystemService(StorageManager.class);
        final StorageStatsManager stats = getContext().getSystemService(StorageStatsManager.class);

        final UUID externalUuid = storage.getUuidForPath(MediaStore.getVolumePath(mVolumeName));
        return stats.queryStatsForPackage(externalUuid, getContext().getPackageName(),
                android.os.Process.myUserHandle()).getDataBytes();
    }

    private long getExternalTotalSize() throws Exception {
        final StorageManager storage = getContext().getSystemService(StorageManager.class);
        final StorageStatsManager stats = getContext().getSystemService(StorageStatsManager.class);

        final UUID externalUuid = storage.getUuidForPath(MediaStore.getVolumePath(mVolumeName));
        return stats.queryExternalStatsForUser(externalUuid, android.os.Process.myUserHandle())
                .getTotalBytes();
    }

    private HashSet<Long> getVisibleIds(Uri collectionUri) {
        final HashSet<Long> res = new HashSet<>();
        try (Cursor c = mContentResolver.query(collectionUri,
                new String[] { MediaColumns._ID }, null, null)) {
            while (c.moveToNext()) {
                res.add(c.getLong(0));
            }
        }
        return res;
    }

    private static void assertMostlyEquals(long expected, long actual, long delta) {
        if (Math.abs(expected - actual) > delta) {
            fail("Expected roughly " + expected + " but was " + actual);
        }
    }
}
