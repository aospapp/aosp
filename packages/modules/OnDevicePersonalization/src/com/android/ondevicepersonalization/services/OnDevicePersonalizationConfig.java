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

package com.android.ondevicepersonalization.services;

import com.android.ondevicepersonalization.services.data.user.UserDataCollectionJobService;
import com.android.ondevicepersonalization.services.download.OnDevicePersonalizationDownloadProcessingJobService;
import com.android.ondevicepersonalization.services.maintenance.OnDevicePersonalizationMaintenanceJobService;

/**
 * Hard-coded configs for OnDevicePersonalization
 */
public class OnDevicePersonalizationConfig {
    private OnDevicePersonalizationConfig() {
    }

    /** Job ID for Mdd Maintenance Task
     * ({@link com.android.ondevicepersonalization.services.download.mdd.MddJobService}) */
    public static final int MDD_MAINTENANCE_PERIODIC_TASK_JOB_ID = 1000;

    /**
     * Job ID for Mdd Charging Periodic Task
     * ({@link com.android.ondevicepersonalization.services.download.mdd.MddJobService})
     */
    public static final int MDD_CHARGING_PERIODIC_TASK_JOB_ID = 1001;

    /**
     * Job ID for Mdd Cellular Charging Task
     * ({@link com.android.ondevicepersonalization.services.download.mdd.MddJobService})
     */
    public static final int MDD_CELLULAR_CHARGING_PERIODIC_TASK_JOB_ID = 1002;

    /** Job ID for Mdd Wifi Charging Task
     * ({@link com.android.ondevicepersonalization.services.download.mdd.MddJobService}) */
    public static final int MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID = 1003;

    /** Job ID for Download Processing Task
     * ({@link OnDevicePersonalizationDownloadProcessingJobService}) */
    public static final int DOWNLOAD_PROCESSING_TASK_JOB_ID = 1004;

    /** Job ID for Maintenance Task
     * ({@link OnDevicePersonalizationMaintenanceJobService}) */
    public static final int MAINTENANCE_TASK_JOB_ID = 1005;

    /** Job ID for User Data Collection Task
     * ({@link UserDataCollectionJobService}) */
    public static final int USER_DATA_COLLECTION_ID = 1006;
}
