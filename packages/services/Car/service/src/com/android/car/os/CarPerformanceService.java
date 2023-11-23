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

package com.android.car.os;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.Car;
import android.car.builtin.util.Slogf;
import android.car.os.CpuAvailabilityMonitoringConfig;
import android.car.os.ICarPerformanceService;
import android.car.os.ICpuAvailabilityChangeListener;
import android.content.Context;
import android.util.Log;

import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;

/**
 * Service to implement CarPerformanceManager API.
 */
public final class CarPerformanceService extends ICarPerformanceService.Stub
        implements CarServiceBase {
    private static final String TAG = CarLog.tagFor(CarPerformanceService.class);
    private static final boolean DEBUG = Slogf.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;

    public CarPerformanceService(Context context) {
        mContext = context;
    }

    @Override
    public void init() {
        // TODO(b/156400843): Connect to the watchdog daemon helper instance for the thread priority
        // API. CarPerformanceService and CarWatchdogService must use the same watchdog daemon
        // helper instance.
        if (DEBUG) {
            Slogf.d(TAG, "CarPerformanceService is initialized");
        }
    }

    @Override
    public void release() {
        // TODO(b/156400843): Disconnect from the watchdog daemon helper instance.
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.printf("*%s*\n", getClass().getSimpleName());
        writer.increaseIndent();
        // TODO(b/217422127): Dump CPU availability info.
        writer.decreaseIndent();
    }

    /**
     * Adds {@link android.car.performance.ICpuAvailabilityChangeListener} for CPU availability
     * change notifications.
     */
    @Override
    public void addCpuAvailabilityChangeListener(CpuAvailabilityMonitoringConfig config,
            ICpuAvailabilityChangeListener listener) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_COLLECT_CAR_CPU_INFO);
        // TODO(b/217422127): Implement this.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Removes the previously added {@link android.car.performance.ICpuAvailabilityChangeListener}.
     */
    @Override
    public void removeCpuAvailabilityChangeListener(ICpuAvailabilityChangeListener listener) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_COLLECT_CAR_CPU_INFO);
        // TODO(b/217422127): Implement this.
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
