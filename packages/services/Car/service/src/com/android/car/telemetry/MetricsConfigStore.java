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

import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_ALREADY_EXISTS;
import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_NONE;
import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_UNKNOWN;
import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_VERSION_TOO_OLD;

import android.car.telemetry.MetricsConfigKey;
import android.util.ArrayMap;
import android.util.AtomicFile;

import com.android.car.CarLog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class is responsible for storing, retrieving, and deleting {@link
 * TelemetryProto.MetricsConfig}. All of the methods are blocking so the class should only be
 * accessed on the telemetry thread.
 */
public class MetricsConfigStore {
    @VisibleForTesting
    static final String METRICS_CONFIG_DIR = "metrics_configs";

    private final File mConfigDirectory;
    private Map<String, TelemetryProto.MetricsConfig> mActiveConfigs;

    public MetricsConfigStore(File rootDirectory) {
        mConfigDirectory = new File(rootDirectory, METRICS_CONFIG_DIR);
        mConfigDirectory.mkdirs();
        mActiveConfigs = new ArrayMap<>();
        // TODO(b/197336485): Add expiration date check for MetricsConfig
        for (File file : mConfigDirectory.listFiles()) {
            AtomicFile atomicFile = new AtomicFile(file);
            try {
                TelemetryProto.MetricsConfig config =
                        TelemetryProto.MetricsConfig.parseFrom(atomicFile.readFully());
                mActiveConfigs.put(config.getName(), config);
            } catch (IOException e) {
                // TODO(b/197336655): record failure
                atomicFile.delete();
            }
        }
    }

    /**
     * Returns all active {@link TelemetryProto.MetricsConfig} from disk.
     */
    public List<TelemetryProto.MetricsConfig> getActiveMetricsConfigs() {
        return new ArrayList<>(mActiveConfigs.values());
    }

    /**
     * Stores the MetricsConfig to disk if it is valid. It checks both config name and version for
     * validity.
     *
     * @param metricsConfig the config to be persisted to disk.
     * @return {@link android.car.telemetry.CarTelemetryManager.MetricsConfigError} status code.
     */
    public int addMetricsConfig(TelemetryProto.MetricsConfig metricsConfig) {
        // TODO(b/197336485): Check expiration date for MetricsConfig
        if (metricsConfig.getVersion() <= 0) {
            return ERROR_METRICS_CONFIG_VERSION_TOO_OLD;
        }
        if (mActiveConfigs.containsKey(metricsConfig.getName())) {
            int currentVersion = mActiveConfigs.get(metricsConfig.getName()).getVersion();
            if (currentVersion > metricsConfig.getVersion()) {
                return ERROR_METRICS_CONFIG_VERSION_TOO_OLD;
            } else if (currentVersion == metricsConfig.getVersion()) {
                return ERROR_METRICS_CONFIG_ALREADY_EXISTS;
            }
        }
        mActiveConfigs.put(metricsConfig.getName(), metricsConfig);
        AtomicFile atomicFile = new AtomicFile(new File(mConfigDirectory, metricsConfig.getName()));
        FileOutputStream fos = null;
        try {
            fos = atomicFile.startWrite();
            fos.write(metricsConfig.toByteArray());
            atomicFile.finishWrite(fos);
        } catch (IOException e) {
            // TODO(b/197336655): record failure
            atomicFile.failWrite(fos);
            Slogf.w(CarLog.TAG_TELEMETRY, "Failed to write metrics config to disk", e);
            return ERROR_METRICS_CONFIG_UNKNOWN;
        }
        return ERROR_METRICS_CONFIG_NONE;
    }

    /**
     * Deletes the MetricsConfig from disk.
     *
     * @param key the unique identifier of the metrics config that should be deleted.
     * @return true for successful removal, false otherwise.
     */
    public boolean removeMetricsConfig(MetricsConfigKey key) {
        String metricsConfigName = key.getName();
        if (!mActiveConfigs.containsKey(key.getName())
                || mActiveConfigs.get(key.getName()).getVersion() != key.getVersion()) {
            return false; // no match found, nothing to remove
        }
        mActiveConfigs.remove(metricsConfigName);
        try {
            return Files.deleteIfExists(Paths.get(
                    mConfigDirectory.getAbsolutePath(), metricsConfigName));
        } catch (IOException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "Failed to remove MetricsConfig: " + key.getName(), e);
            // TODO(b/197336655): record failure
        }
        return false;
    }

    /** Deletes all MetricsConfigs from disk. */
    public void removeAllMetricsConfigs() {
        mActiveConfigs.clear();
        for (File file : mConfigDirectory.listFiles()) {
            if (!file.delete()) {
                Slogf.w(CarLog.TAG_TELEMETRY, "Failed to remove MetricsConfig: " + file.getName());
            }
        }
    }
}
