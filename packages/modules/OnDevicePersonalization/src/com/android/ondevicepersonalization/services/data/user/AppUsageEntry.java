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

package com.android.ondevicepersonalization.services.data.user;

import androidx.annotation.NonNull;

/** App usage record for ODA internal use. */
public class AppUsageEntry {
    // App package name
    public String packageName = null;

    // Starting timestamp of the collection cycle
    public long startTimeMillis = 0L;

    // Ending timestamp of the collection cycle
    public long endTimeMillis = 0L;

    // Total time spent
    public long totalTimeUsedMillis = 0L;

    public AppUsageEntry(@NonNull AppUsageEntry other) {
        this.packageName = other.packageName;
        this.startTimeMillis = other.startTimeMillis;
        this.endTimeMillis = other.endTimeMillis;
        this.totalTimeUsedMillis = other.totalTimeUsedMillis;
    }

    public AppUsageEntry(@NonNull String packageName,
            @NonNull long startTimeMillis,
            @NonNull long endTimeMillis,
            @NonNull long totalTimeUsedMillis) {
        this.packageName = packageName;
        this.startTimeMillis = startTimeMillis;
        this.endTimeMillis = endTimeMillis;
        this.totalTimeUsedMillis = totalTimeUsedMillis;
    }
}
