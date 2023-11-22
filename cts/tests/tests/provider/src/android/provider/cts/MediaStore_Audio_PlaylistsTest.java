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

import static android.provider.cts.MediaStoreTest.TAG;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.platform.test.annotations.Presubmit;
import android.provider.MediaStore.Audio.Playlists;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;

@Presubmit
@RunWith(Parameterized.class)
public class MediaStore_Audio_PlaylistsTest {
    private Context mContext;
    private ContentResolver mContentResolver;

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
    }

    @Test
    public void testGetContentUri() {
        Cursor c = null;
        assertNotNull(c = mContentResolver.query(
                Playlists.getContentUri(mVolumeName), null, null,
                null, null));
        c.close();
    }

    @Test
    public void testStoreAudioPlaylistsExternal() throws Exception {
        final String externalPlaylistPath = new File(ProviderTestUtils.stageDir(mVolumeName),
                "my_favorites.pl").getAbsolutePath();
        ContentValues values = new ContentValues();
        values.put(Playlists.NAME, "My favourites");
        values.put(Playlists.DATA, externalPlaylistPath);
        long dateAdded = System.currentTimeMillis() / 1000;
        long dateModified = System.currentTimeMillis() / 1000;
        values.put(Playlists.DATE_MODIFIED, dateModified);
        // insert
        Uri uri = mContentResolver.insert(Playlists.getContentUri(mVolumeName), values);
        assertNotNull(uri);

        try {
            // query
            Cursor c = mContentResolver.query(uri, null, null, null, null);
            assertEquals(1, c.getCount());
            c.moveToFirst();
            assertEquals("My favourites", c.getString(c.getColumnIndex(Playlists.NAME)));
            assertEquals(externalPlaylistPath,
                    c.getString(c.getColumnIndex(Playlists.DATA)));

            long realDateAdded = c.getLong(c.getColumnIndex(Playlists.DATE_ADDED));
            assertTrue(realDateAdded >= dateAdded);
            assertEquals(dateModified, c.getLong(c.getColumnIndex(Playlists.DATE_MODIFIED)));
            assertTrue(c.getLong(c.getColumnIndex(Playlists._ID)) > 0);
            c.close();
        } finally {
            assertEquals(1, mContentResolver.delete(uri, null, null));
        }
    }
}
