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

package android.provider.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.platform.test.annotations.Presubmit;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.util.Optional;

@Presubmit
@RunWith(Parameterized.class)
public class MediaStorePlacementTest {
    static final String TAG = "MediaStorePlacementTest";

    private Context mContext;
    private ContentResolver mContentResolver;

    private Uri mExternalImages;

    @Parameter(0)
    public String mVolumeName;

    @Parameters
    public static Iterable<? extends Object> data() {
        return ProviderTestUtils.getSharedVolumeNames();
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mContentResolver = mContext.getContentResolver();

        Log.d(TAG, "Using volume " + mVolumeName);
        mExternalImages = MediaStore.Images.Media.getContentUri(mVolumeName);
    }

    @Test
    public void testDefault() throws Exception {
        final Uri uri = ProviderTestUtils.stageMedia(R.drawable.scenery,
                mExternalImages, "image/jpeg");

        // By default placed under "Pictures" with sane name
        final File before = ProviderTestUtils.getRelativeFile(uri);
        assertTrue(before.getName().startsWith("cts"));
        assertTrue(before.getName().endsWith("jpg"));
        assertEquals("Pictures", before.getParent());
    }

    @Test
    public void testIgnored() throws Exception {
        final Uri uri = ProviderTestUtils.stageMedia(R.drawable.scenery,
                mExternalImages, "image/jpeg");

        {
            final ContentValues values = new ContentValues();
            values.put(MediaColumns.SIZE, 0);
            assertEquals(0, mContentResolver.update(uri, values, null, null));
        }

        // Make sure shady paths can't be passed in
        for (String probe : new String[] {
                "path/.to/dir",
                ".dir",
                "path/../dir",
        }) {
            final ContentValues values = new ContentValues();
            values.put(MediaColumns.RELATIVE_PATH, probe);
            try {
                mContentResolver.update(uri, values, null, null);
                fail();
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void testDisplayName_SameMime() throws Exception {
        final Uri uri = ProviderTestUtils.stageMedia(R.drawable.scenery,
                mExternalImages, "image/jpeg");

        // Movement within same MIME type is okay
        final File before = ProviderTestUtils.getRelativeFile(uri);
        final String name = "CTS" +  System.nanoTime() + ".JPEG";
        assertTrue(updatePlacement(uri, null, Optional.of(name)));

        final File after = ProviderTestUtils.getRelativeFile(uri);
        assertEquals(before.getParent(), after.getParent());
        assertEquals(name, after.getName());
    }

    @Test
    public void testDisplayName_DifferentMime() throws Exception {
        final Uri uri = ProviderTestUtils.stageMedia(R.drawable.scenery,
                mExternalImages, "image/jpeg");

        final File before = ProviderTestUtils.getRelativeFile(uri);
        assertTrue(before.getName().endsWith(".jpg"));

        // Movement across MIME types is not okay; verify that original MIME
        // type remains intact
        final String name = "cts" +  System.nanoTime() + ".png";
        assertTrue(updatePlacement(uri, null, Optional.of(name)));

        final File after = ProviderTestUtils.getRelativeFile(uri);
        assertTrue(after.getName().startsWith(name));
        assertTrue(after.getName().endsWith(".jpg"));
    }

    @Test
    public void testDirectory_Valid() throws Exception {
        final Uri uri = ProviderTestUtils.stageMedia(R.drawable.scenery,
                mExternalImages, "image/jpeg");

        final File before = ProviderTestUtils.getRelativeFile(uri);
        assertEquals("Pictures", before.getParent());

        {
            assertTrue(updatePlacement(uri,
                    Optional.ofNullable(null), null));
            final File after = ProviderTestUtils.getRelativeFile(uri);
            assertEquals("Pictures", after.getParent());
        }
        {
            assertTrue(updatePlacement(uri,
                    Optional.of("DCIM/Vacation"), null));
            final File after = ProviderTestUtils.getRelativeFile(uri);
            assertEquals("DCIM/Vacation", after.getParent());
        }
        {
            assertTrue(updatePlacement(uri,
                    Optional.of("DCIM/Misc"), null));
            final File after = ProviderTestUtils.getRelativeFile(uri);
            assertEquals("DCIM/Misc", after.getParent());
        }
        {
            assertTrue(updatePlacement(uri,
                    Optional.of("Pictures/Misc"), null));
            final File after = ProviderTestUtils.getRelativeFile(uri);
            assertEquals("Pictures/Misc", after.getParent());
        }
        {
            assertTrue(updatePlacement(uri,
                    Optional.of("Pictures"), null));
            final File after = ProviderTestUtils.getRelativeFile(uri);
            assertEquals("Pictures", after.getParent());
        }
    }

    @Test
    public void testDirectory_Invalid() throws Exception {
        final Uri uri = ProviderTestUtils.stageMedia(R.drawable.scenery,
                mExternalImages, "image/jpeg");

        assertFalse(updatePlacement(uri,
                Optional.of("Random"), null));
        assertFalse(updatePlacement(uri,
                Optional.of(Environment.DIRECTORY_ALARMS), null));
    }

    @Test
    public void testDirectory_InsideSandbox() throws Exception {
        Assume.assumeFalse(MediaStore.VOLUME_EXTERNAL.equals(mVolumeName));

        final File dir = MediaStore.getVolumePath(mVolumeName);
        final File file = ProviderTestUtils.stageFile(R.drawable.scenery, Environment.buildPath(dir,
                "Android", "media", "android.provider.cts", System.nanoTime() + ".jpg"));
        final Uri uri = ProviderTestUtils.scanFile(file);

        assertFalse(updatePlacement(uri,
                Optional.of("Android/media/android.provider.cts/foo"), null));
        assertFalse(updatePlacement(uri,
                Optional.of("Android/media/com.example/foo"), null));
        assertFalse(updatePlacement(uri,
                Optional.of("DCIM"), null));
    }

    @Test
    public void testDirectory_OutsideSandbox() throws Exception {
        Assume.assumeFalse(MediaStore.VOLUME_EXTERNAL.equals(mVolumeName));

        final Uri uri = ProviderTestUtils.stageMedia(R.drawable.scenery,
                mExternalImages, "image/jpeg");

        assertFalse(updatePlacement(uri,
                Optional.of("Android/media/android.provider.cts/foo"), null));
        assertFalse(updatePlacement(uri,
                Optional.of("Android/media/com.example/foo"), null));
        assertTrue(updatePlacement(uri,
                Optional.of("DCIM"), null));
    }

    private boolean updatePlacement(Uri uri, Optional<String> path, Optional<String> displayName)
            throws Exception {
        final ContentValues values = new ContentValues();
        if (path != null) {
            values.put(MediaColumns.RELATIVE_PATH, path.orElse(null));
        }
        if (displayName != null) {
            values.put(MediaColumns.DISPLAY_NAME, displayName.orElse(null));
        }
        try {
            return (mContentResolver.update(uri, values, null, null) == 1);
        } catch (Exception tolerated) {
            return false;
        }
    }
}
