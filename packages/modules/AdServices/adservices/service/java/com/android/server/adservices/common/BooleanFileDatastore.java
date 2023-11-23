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

package com.android.server.adservices.common;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PersistableBundle;
import android.util.AtomicFile;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.adservices.LogUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * A simple datastore utilizing {@link android.util.AtomicFile} and {@link
 * android.os.PersistableBundle} to read/write a simple key/value map to file.
 *
 * <p>The datastore is loaded from file only when initialized and written to file on every write.
 * When using this datastore, it is up to the caller to ensure that each datastore file is accessed
 * by exactly one datastore object. If multiple writing threads or processes attempt to use
 * different instances pointing to the same file, transactions may be lost.
 *
 * <p>Keys must be non-null, non-empty strings, and values must be booleans.
 *
 * @threadsafe
 * @hide
 */
public class BooleanFileDatastore {
    public static final int NO_PREVIOUS_VERSION = -1;

    private final int mDatastoreVersion;

    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();
    private final Lock mReadLock = mReadWriteLock.readLock();
    private final Lock mWriteLock = mReadWriteLock.writeLock();

    private final AtomicFile mAtomicFile;
    private final Map<String, Boolean> mLocalMap = new HashMap<>();

    private final String mVersionKey;
    private int mPreviousStoredVersion;

    public BooleanFileDatastore(
            @NonNull String parentPath,
            @NonNull String filename,
            int datastoreVersion,
            String versionKey) {
        Objects.requireNonNull(parentPath);
        Objects.requireNonNull(filename);
        Preconditions.checkStringNotEmpty(filename, "Filename must not be empty");
        Preconditions.checkArgumentNonnegative(datastoreVersion, "Version must not be negative");

        mAtomicFile = new AtomicFile(new File(parentPath, filename));
        mDatastoreVersion = datastoreVersion;
        mVersionKey = versionKey;
    }

    /**
     * Loads data from the datastore file.
     *
     * @throws IOException if file read fails
     */
    public void initialize() throws IOException {
        LogUtil.d("Reading from store file: " + mAtomicFile.getBaseFile());
        mReadLock.lock();
        try {
            readFromFile();
        } finally {
            mReadLock.unlock();
        }

        // In the future, this could be a good place for upgrade/rollback for schemas
    }

    // Writes the class member map to a PersistableBundle which is then written to file.
    @GuardedBy("mWriteLock")
    private void writeToFile() throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final PersistableBundle bundleToWrite = new PersistableBundle();

        for (Map.Entry<String, Boolean> entry : mLocalMap.entrySet()) {
            bundleToWrite.putBoolean(entry.getKey(), entry.getValue());
        }

        // Version unused for now. May be needed in the future for handling migrations.
        bundleToWrite.putInt(mVersionKey, mDatastoreVersion);
        bundleToWrite.writeToStream(outputStream);

        FileOutputStream out = null;
        try {
            out = mAtomicFile.startWrite();
            out.write(outputStream.toByteArray());
            mAtomicFile.finishWrite(out);
        } catch (IOException e) {
            if (out != null) {
                mAtomicFile.failWrite(out);
            }
            LogUtil.e(e, "Write to file failed");
            throw e;
        }
    }

    // Note that this completely replaces the loaded datastore with the file's data, instead of
    // appending new file data.
    @GuardedBy("mReadLock")
    private void readFromFile() throws IOException {
        try {
            final ByteArrayInputStream inputStream =
                    new ByteArrayInputStream(mAtomicFile.readFully());
            final PersistableBundle bundleRead = PersistableBundle.readFromStream(inputStream);

            mPreviousStoredVersion = bundleRead.getInt(mVersionKey, NO_PREVIOUS_VERSION);
            bundleRead.remove(mVersionKey);
            mLocalMap.clear();
            for (String key : bundleRead.keySet()) {
                mLocalMap.put(key, bundleRead.getBoolean(key));
            }
        } catch (FileNotFoundException e) {
            LogUtil.v("File not found; continuing with clear database");
            mPreviousStoredVersion = NO_PREVIOUS_VERSION;
            mLocalMap.clear();
        } catch (IOException e) {
            LogUtil.e(e, "Read from store file failed");
            throw e;
        }
    }

    /**
     * Stores a value to the datastore file.
     *
     * <p>This change is committed immediately to file.
     *
     * @param key A non-null, non-empty String key to store the {@code value} against
     * @param value A boolean to be stored
     * @throws IllegalArgumentException if {@code key} is an empty string
     * @throws IOException if file write fails
     * @throws NullPointerException if {@code key} is null
     */
    public void put(@NonNull String key, boolean value) throws IOException {
        Objects.requireNonNull(key);
        Preconditions.checkStringNotEmpty(key, "Key must not be empty");

        mWriteLock.lock();
        try {
            mLocalMap.put(key, value);
            writeToFile();
        } finally {
            mWriteLock.unlock();
        }
    }

    /**
     * Stores a value to the datastore file, but only if the key does not already exist.
     *
     * <p>If a change is made to the datastore, it is committed immediately to file.
     *
     * @param key A non-null, non-empty String key to store the {@code value} against
     * @param value A boolean to be stored
     * @return the value that exists in the datastore after the operation completes
     * @throws IllegalArgumentException if {@code key} is an empty string
     * @throws IOException if file write fails
     * @throws NullPointerException if {@code key} is null
     */
    public boolean putIfNew(@NonNull String key, boolean value) throws IOException {
        Objects.requireNonNull(key);
        Preconditions.checkStringNotEmpty(key, "Key must not be empty");

        // Try not to block readers first before trying to write
        mReadLock.lock();
        try {
            Boolean valueInLocalMap = mLocalMap.get(key);
            if (valueInLocalMap != null) {
                return valueInLocalMap;
            }
        } finally {
            mReadLock.unlock();
        }

        // Double check that the key wasn't written after the first check
        mWriteLock.lock();
        try {
            Boolean valueInLocalMap = mLocalMap.get(key);
            if (valueInLocalMap != null) {
                return valueInLocalMap;
            } else {
                mLocalMap.put(key, value);
                writeToFile();
                return value;
            }
        } finally {
            mWriteLock.unlock();
        }
    }

    /**
     * Retrieves a boolean value from the loaded datastore file.
     *
     * @param key A non-null, non-empty String key to fetch a value from
     * @return The value stored against a {@code key}, or null if it doesn't exist
     * @throws IllegalArgumentException if {@code key} is an empty string
     * @throws NullPointerException if {@code key} is null
     */
    @Nullable
    public Boolean get(@NonNull String key) {
        Objects.requireNonNull(key);
        Preconditions.checkStringNotEmpty(key, "Key must not be empty");

        mReadLock.lock();
        try {
            return mLocalMap.get(key);
        } finally {
            mReadLock.unlock();
        }
    }

    /**
     * Retrieves a boolean value from the loaded datastore file.
     *
     * @param key A non-null, non-empty String key to fetch a value from
     * @param defaultValue Value to return if this key does not exist.
     * @throws IllegalArgumentException if {@code key} is an empty string
     * @throws NullPointerException if {@code key} is null
     */
    @Nullable
    public Boolean get(@NonNull String key, boolean defaultValue) {
        Objects.requireNonNull(key);
        Preconditions.checkStringNotEmpty(key, "Key must not be empty");

        mReadLock.lock();
        try {
            return mLocalMap.containsKey(key) ? mLocalMap.get(key) : defaultValue;
        } finally {
            mReadLock.unlock();
        }
    }

    /** Returns the version that was written prior to the device starting. */
    @NonNull
    public int getPreviousStoredVersion() {
        return mPreviousStoredVersion;
    }

    /**
     * Retrieves a {@link Set} of all keys loaded from the datastore file.
     *
     * @return A {@link Set} of {@link String} keys currently in the loaded datastore
     */
    @NonNull
    public Set<String> keySet() {
        mReadLock.lock();
        try {
            return Set.copyOf(mLocalMap.keySet());
        } finally {
            mReadLock.unlock();
        }
    }

    @NonNull
    private Set<String> keySetFilter(boolean filter) {
        mReadLock.lock();
        try {
            return Set.copyOf(
                    mLocalMap.entrySet().stream()
                            .filter(entry -> entry.getValue().equals(filter))
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toSet()));
        } finally {
            mReadLock.unlock();
        }
    }

    /**
     * Retrieves a Set of all keys with value {@code true} loaded from the datastore file.
     *
     * @return A Set of String keys currently in the loaded datastore that have value {@code true}
     */
    @NonNull
    public Set<String> keySetTrue() {
        return keySetFilter(true);
    }

    /**
     * Retrieves a Set of all keys with value {@code false} loaded from the datastore file.
     *
     * @return A Set of String keys currently in the loaded datastore that have value {@code false}
     */
    @NonNull
    public Set<String> keySetFalse() {
        return keySetFilter(false);
    }

    /**
     * Clears all entries from the datastore file.
     *
     * <p>This change is committed immediately to file.
     *
     * @throws IOException if file write fails
     */
    public void clear() throws IOException {
        LogUtil.d("Clearing all entries from datastore");

        mWriteLock.lock();
        try {
            mLocalMap.clear();
            writeToFile();
        } finally {
            mWriteLock.unlock();
        }
    }

    private void clearByFilter(boolean filter) throws IOException {
        mWriteLock.lock();
        try {
            mLocalMap.entrySet().removeIf(entry -> entry.getValue().equals(filter));
            writeToFile();
        } finally {
            mWriteLock.unlock();
        }
    }

    /**
     * Clears all entries from the datastore file that have value {@code true}. Entries with value
     * {@code false} are not removed.
     *
     * <p>This change is committed immediately to file.
     *
     * @throws IOException if file write fails
     */
    public void clearAllTrue() throws IOException {
        clearByFilter(true);
    }

    /**
     * Clears all entries from the datastore file that have value {@code false}. Entries with value
     * {@code true} are not removed.
     *
     * <p>This change is committed immediately to file.
     *
     * @throws IOException if file write fails
     */
    public void clearAllFalse() throws IOException {
        clearByFilter(false);
    }

    /**
     * Removes an entry from the datastore file.
     *
     * <p>This change is committed immediately to file.
     *
     * @param key A non-null, non-empty String key to remove
     * @throws IllegalArgumentException if {@code key} is an empty string
     * @throws IOException if file write fails
     * @throws NullPointerException if {@code key} is null
     */
    public void remove(@NonNull String key) throws IOException {
        Objects.requireNonNull(key);
        Preconditions.checkStringNotEmpty(key, "Key must not be empty");

        mWriteLock.lock();
        try {
            mLocalMap.remove(key);
            writeToFile();
        } finally {
            mWriteLock.unlock();
        }
    }

    @VisibleForTesting
    public void tearDownForTesting() {
        mWriteLock.lock();
        try {
            mAtomicFile.delete();
            mLocalMap.clear();
        } finally {
            mWriteLock.unlock();
        }
    }
}
