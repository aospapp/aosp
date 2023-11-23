/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.adservices.data.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Pair;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BooleanFileDatastoreTest {
    private static final Context PPAPI_CONTEXT = ApplicationProvider.getApplicationContext();
    private static final String FILENAME = "BooleanFileDatastoreTest.xml";
    private static final int DATASTORE_VERSION = 1;
    private static final String TEST_KEY = "key";

    private BooleanFileDatastore mDatastore;

    @Before
    public void setup() throws IOException {
        mDatastore = new BooleanFileDatastore(PPAPI_CONTEXT, FILENAME, DATASTORE_VERSION);
        mDatastore.initialize();
    }

    @After
    public void cleanup() {
        mDatastore.tearDownForTesting();
    }

    @Test
    public void testInitializeEmptyBooleanFileDatastore() {
        assertTrue(mDatastore.keySet().isEmpty());
    }

    @Test
    public void testNullOrEmptyKeyFails() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    mDatastore.put(null, true);
                });

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    mDatastore.put("", true);
                });

        assertThrows(
                NullPointerException.class,
                () -> {
                    mDatastore.putIfNew(null, true);
                });

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    mDatastore.putIfNew("", true);
                });

        assertThrows(
                NullPointerException.class,
                () -> {
                    mDatastore.get(null);
                });

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    mDatastore.get("");
                });

        assertThrows(
                NullPointerException.class,
                () -> {
                    mDatastore.remove(null);
                });

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    mDatastore.remove("");
                });

        assertThrows(
                NullPointerException.class,
                () -> {
                    mDatastore.removeByPrefix(null);
                });

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    mDatastore.removeByPrefix("");
                });
    }

    @Test
    public void testPutGetUpdateRemove() throws IOException {
        // Should not exist yet
        assertNull(mDatastore.get(TEST_KEY));

        // Create
        boolean insertedValue = false;
        mDatastore.put(TEST_KEY, insertedValue);

        // Read
        Boolean readValue = mDatastore.get(TEST_KEY);
        assertNotNull(readValue);
        assertEquals(readValue, insertedValue);

        // Update
        insertedValue = true;
        mDatastore.put(TEST_KEY, insertedValue);
        readValue = mDatastore.get(TEST_KEY);
        assertNotNull(readValue);
        assertEquals(readValue, insertedValue);

        Set<String> keys = mDatastore.keySet();
        assertEquals(keys.size(), 1);
        assertTrue(keys.contains(TEST_KEY));

        // Delete
        mDatastore.remove(TEST_KEY);
        assertNull(mDatastore.get(TEST_KEY));
        assertTrue(mDatastore.keySet().isEmpty());

        // Should not throw when removing a nonexistent key
        mDatastore.remove(TEST_KEY);
    }

    @Test
    public void testPutIfNew() throws IOException {
        // Should not exist yet
        assertNull(mDatastore.get(TEST_KEY));

        // Create because it's new
        assertFalse(mDatastore.putIfNew(TEST_KEY, false));
        Boolean readValue = mDatastore.get(TEST_KEY);
        assertNotNull(readValue);
        assertFalse(readValue);

        // Force overwrite
        mDatastore.put(TEST_KEY, true);
        readValue = mDatastore.get(TEST_KEY);
        assertNotNull(readValue);
        assertTrue(readValue);

        // Put should read the existing value
        assertTrue(mDatastore.putIfNew(TEST_KEY, false));
        readValue = mDatastore.get(TEST_KEY);
        assertNotNull(readValue);
        assertTrue(readValue);
    }

    @Test
    public void testKeySetClear() throws IOException {
        int numEntries = 10;
        for (int i = 0; i < numEntries; i++) {
            // Even entries are true, odd are false
            mDatastore.put(TEST_KEY + i, (i & 1) == 0);
        }

        Set<String> trueKeys = mDatastore.keySetTrue();
        Set<String> falseKeys = mDatastore.keySetFalse();

        assertEquals(trueKeys.size(), numEntries / 2);
        assertEquals(falseKeys.size(), numEntries / 2);

        for (int i = 0; i < numEntries; i++) {
            assertEquals(trueKeys.contains(TEST_KEY + i), (i & 1) == 0);
            assertEquals(falseKeys.contains(TEST_KEY + i), (i & 1) != 0);
        }

        mDatastore.clear();
        assertTrue(mDatastore.keySet().isEmpty());
    }

    @Test
    public void testKeySetClearAllTrue() throws IOException {
        int numEntries = 10;
        for (int i = 0; i < numEntries; i++) {
            // Even entries are true, odd are false
            mDatastore.put(TEST_KEY + i, (i & 1) == 0);
        }

        Set<String> trueKeys = mDatastore.keySetTrue();
        Set<String> falseKeys = mDatastore.keySetFalse();

        assertEquals(trueKeys.size(), numEntries / 2);
        assertEquals(falseKeys.size(), numEntries / 2);

        for (int i = 0; i < numEntries; i++) {
            assertEquals(trueKeys.contains(TEST_KEY + i), (i & 1) == 0);
            assertEquals(falseKeys.contains(TEST_KEY + i), (i & 1) != 0);
        }

        mDatastore.clearAllTrue();

        trueKeys = mDatastore.keySetTrue();
        falseKeys = mDatastore.keySetFalse();

        assertTrue(trueKeys.isEmpty());
        assertEquals(numEntries / 2, falseKeys.size());

        for (int i = 0; i < numEntries; i++) {
            assertEquals((i & 1) != 0, falseKeys.contains(TEST_KEY + i));
        }
    }

    @Test
    public void testKeySetClearAllFalse() throws IOException {
        int numEntries = 10;
        for (int i = 0; i < numEntries; i++) {
            // Even entries are true, odd are false
            mDatastore.put(TEST_KEY + i, (i & 1) == 0);
        }

        Set<String> trueKeys = mDatastore.keySetTrue();
        Set<String> falseKeys = mDatastore.keySetFalse();

        assertEquals(trueKeys.size(), numEntries / 2);
        assertEquals(falseKeys.size(), numEntries / 2);

        for (int i = 0; i < numEntries; i++) {
            assertEquals(trueKeys.contains(TEST_KEY + i), (i & 1) == 0);
            assertEquals(falseKeys.contains(TEST_KEY + i), (i & 1) != 0);
        }

        mDatastore.clearAllFalse();

        trueKeys = mDatastore.keySetTrue();
        falseKeys = mDatastore.keySetFalse();

        assertEquals(numEntries / 2, trueKeys.size());
        assertTrue(falseKeys.isEmpty());

        for (int i = 0; i < numEntries; i++) {
            assertEquals((i & 1) == 0, trueKeys.contains(TEST_KEY + i));
        }
    }

    @Test
    public void testRemovePrefix() throws IOException {
        // Create
        final int numEntries = 10;
        // Keys begin with either TEST_KEY+0 or TEST_KEY+1. Even entries are true, odd are false.
        final List<Pair<String, Boolean>> entriesToAdd =
                IntStream.range(0, numEntries)
                        .mapToObj(i -> new Pair<>(TEST_KEY + (i & 1) + i, (i & 1) == 0))
                        .collect(Collectors.toList());

        // Add to data store
        for (Pair<String, Boolean> entry : entriesToAdd) {
            assertNull(mDatastore.get(entry.first)); // Should not exist yet
            mDatastore.put(entry.first, entry.second);
        }

        // Delete everything beginning with TEST_KEY + 0.
        // This should leave behind all keys with starting with TEST_KEY + 1.
        mDatastore.removeByPrefix(TEST_KEY + 0);

        // Compute the expected set of entries that should remain.
        final Set<Pair<String, Boolean>> entriesThatShouldRemain =
                entriesToAdd.stream()
                        .filter(s -> s.first.startsWith(TEST_KEY + 1))
                        .collect(Collectors.toSet());

        // Verify that all the expected keys remain in the data store, with the right values.
        assertEquals(entriesThatShouldRemain.size(), mDatastore.keySet().size());
        for (Pair<String, Boolean> item : entriesThatShouldRemain) {
            assertEquals(item.second, mDatastore.get(item.first));
        }
    }
}
