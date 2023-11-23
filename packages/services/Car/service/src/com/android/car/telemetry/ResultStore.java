/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.telemetry;

import android.car.telemetry.MetricsConfigKey;
import android.os.PersistableBundle;
import android.util.ArrayMap;
import android.util.AtomicFile;

import com.android.car.CarLog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Disk storage for interim and final metrics statistics.
 * All methods in this class should be invoked from the telemetry thread.
 */
public class ResultStore {

    private static final long STALE_THRESHOLD_MILLIS =
            TimeUnit.MILLISECONDS.convert(30, TimeUnit.DAYS);
    @VisibleForTesting
    static final String INTERIM_RESULT_DIR = "interim";
    @VisibleForTesting
    static final String ERROR_RESULT_DIR = "error";
    @VisibleForTesting
    static final String FINAL_RESULT_DIR = "final";

    /** Map keys are MetricsConfig names, which are also the file names in disk. */
    private final Map<String, InterimResult> mInterimResultCache = new ArrayMap<>();

    private final File mInterimResultDirectory;
    private final File mErrorResultDirectory;
    private final File mFinalResultDirectory;

    ResultStore(File rootDirectory) {
        mInterimResultDirectory = new File(rootDirectory, INTERIM_RESULT_DIR);
        mErrorResultDirectory = new File(rootDirectory, ERROR_RESULT_DIR);
        mFinalResultDirectory = new File(rootDirectory, FINAL_RESULT_DIR);
        mInterimResultDirectory.mkdirs();
        mErrorResultDirectory.mkdirs();
        mFinalResultDirectory.mkdirs();
        // load results into memory to reduce the frequency of disk access
        loadInterimResultsIntoMemory();
    }

    /** Reads interim results into memory for faster access. */
    private void loadInterimResultsIntoMemory() {
        for (File file : mInterimResultDirectory.listFiles()) {
            PersistableBundle interimResultBundle = readPersistableBundle(new AtomicFile(file));
            if (interimResultBundle != null) {
                mInterimResultCache.put(file.getName(), new InterimResult(interimResultBundle));
            }
        }
    }

    /**
     * Retrieves interim metrics for the given
     * {@link com.android.car.telemetry.TelemetryProto.MetricsConfig}.
     */
    public PersistableBundle getInterimResult(String metricsConfigName) {
        if (!mInterimResultCache.containsKey(metricsConfigName)) {
            return null;
        }
        return mInterimResultCache.get(metricsConfigName).getBundle();
    }

    /**
     * Stores interim metrics results in memory for the given
     * {@link com.android.car.telemetry.TelemetryProto.MetricsConfig}.
     */
    public void putInterimResult(String metricsConfigName, PersistableBundle result) {
        mInterimResultCache.put(metricsConfigName, new InterimResult(result, /* dirty = */ true));
    }

    /**
     * Retrieves final metrics for the given
     * {@link com.android.car.telemetry.TelemetryProto.MetricsConfig}.
     *
     * @param metricsConfigName name of the MetricsConfig.
     * @param deleteResult      if true, the final result will be deleted from disk.
     * @return the final result as PersistableBundle if exists, null otherwise
     */
    public PersistableBundle getFinalResult(String metricsConfigName, boolean deleteResult) {
        AtomicFile atomicFile = new AtomicFile(new File(mFinalResultDirectory, metricsConfigName));
        // if no final result exists for this metrics config, return immediately
        if (!atomicFile.getBaseFile().exists()) {
            return null;
        }
        PersistableBundle result = readPersistableBundle(atomicFile);
        if (deleteResult) {
            atomicFile.delete();
        }
        return result;
    }

    /**
     * Stores final metrics in memory for the given
     * {@link com.android.car.telemetry.TelemetryProto.MetricsConfig}.
     */
    public void putFinalResult(String metricsConfigName, PersistableBundle result) {
        writePersistableBundle(mFinalResultDirectory, metricsConfigName, result);
        deleteFileInDirectory(mInterimResultDirectory, metricsConfigName);
        mInterimResultCache.remove(metricsConfigName);
    }

    /** Returns the error result produced by the metrics config if exists, null otherwise. */
    public TelemetryProto.TelemetryError getError(
            String metricsConfigName, boolean deleteResult) {
        AtomicFile atomicFile = new AtomicFile(new File(mErrorResultDirectory, metricsConfigName));
        // if no error exists for this metrics config, return immediately
        if (!atomicFile.getBaseFile().exists()) {
            return null;
        }
        TelemetryProto.TelemetryError result = null;
        try {
            result = TelemetryProto.TelemetryError.parseFrom(atomicFile.readFully());
        } catch (IOException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "Failed to get error result from disk.", e);
        }
        if (deleteResult) {
            atomicFile.delete();
        }
        return result;
    }

    /** Stores the error object produced by the script. */
    public void putError(String metricsConfigName, TelemetryProto.TelemetryError error) {
        AtomicFile errorFile = new AtomicFile(new File(mErrorResultDirectory, metricsConfigName));
        FileOutputStream fos = null;
        try {
            fos = errorFile.startWrite();
            fos.write(error.toByteArray());
            errorFile.finishWrite(fos);
            deleteFileInDirectory(mInterimResultDirectory, metricsConfigName);
            mInterimResultCache.remove(metricsConfigName);
        } catch (IOException e) {
            errorFile.failWrite(fos);
            Slogf.w(CarLog.TAG_TELEMETRY, "Failed to write data to file", e);
            // TODO(b/197153560): record failure
        }
    }

    /** Persists data to disk. */
    public void flushToDisk() {
        writeInterimResultsToFile();
        deleteAllStaleData(mInterimResultDirectory, mFinalResultDirectory);
    }

    /**
     * Deletes script result associated with the given config name. If result does not exist, this
     * method does not do anything.
     */
    public void removeResult(MetricsConfigKey key) {
        String metricsConfigName = key.getName();
        mInterimResultCache.remove(metricsConfigName);
        deleteFileInDirectory(mInterimResultDirectory, metricsConfigName);
        deleteFileInDirectory(mFinalResultDirectory, metricsConfigName);
    }

    /** Deletes all interim and final results stored in disk. */
    public void removeAllResults() {
        mInterimResultCache.clear();
        for (File interimResult : mInterimResultDirectory.listFiles()) {
            interimResult.delete();
        }
        for (File finalResult : mFinalResultDirectory.listFiles()) {
            finalResult.delete();
        }
    }

    /** Writes dirty interim results to disk. */
    private void writeInterimResultsToFile() {
        mInterimResultCache.forEach((metricsConfigName, interimResult) -> {
            // only write dirty data
            if (!interimResult.isDirty()) {
                return;
            }
            writePersistableBundle(
                    mInterimResultDirectory, metricsConfigName, interimResult.getBundle());
        });
    }

    /** Deletes data that are older than some threshold in the given directories. */
    private void deleteAllStaleData(File... dirs) {
        long currTimeMs = System.currentTimeMillis();
        for (File dir : dirs) {
            for (File file : dir.listFiles()) {
                // delete stale data
                if (file.lastModified() + STALE_THRESHOLD_MILLIS < currTimeMs) {
                    file.delete();
                }
            }
        }
    }

    /**
     * Converts a {@link PersistableBundle} into byte array and saves the results to a file.
     */
    private void writePersistableBundle(
            File dir, String metricsConfigName, PersistableBundle result) {
        AtomicFile bundleFile = new AtomicFile(new File(dir, metricsConfigName));
        FileOutputStream fos = null;
        try {
            fos = bundleFile.startWrite();
            result.writeToStream(fos);
            bundleFile.finishWrite(fos);
        } catch (IOException e) {
            bundleFile.failWrite(fos);
            Slogf.w(CarLog.TAG_TELEMETRY, "Failed to write result to file", e);
            // TODO(b/197153560): record failure
        }
    }

    /**
     * Reads a {@link PersistableBundle} from the file system.
     */
    private PersistableBundle readPersistableBundle(AtomicFile atomicFile) {
        try (FileInputStream fis = atomicFile.openRead()) {
            return PersistableBundle.readFromStream(fis);
        } catch (IOException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "Failed to read from disk.", e);
            // TODO(b/197153560): record failure
        }
        return null;
    }

    /** Deletes a the given file in the given directory if it exists. */
    private void deleteFileInDirectory(File interimResultDirectory, String metricsConfigName) {
        File file = new File(interimResultDirectory, metricsConfigName);
        file.delete();
    }

    /** Wrapper around a result and whether the result should be written to disk. */
    static final class InterimResult {
        private final PersistableBundle mBundle;
        private final boolean mDirty;

        InterimResult(PersistableBundle bundle) {
            mBundle = bundle;
            mDirty = false;
        }

        InterimResult(PersistableBundle bundle, boolean dirty) {
            mBundle = bundle;
            mDirty = dirty;
        }

        PersistableBundle getBundle() {
            return mBundle;
        }

        boolean isDirty() {
            return mDirty;
        }
    }
}
