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

package com.android.car.hal;

import static android.hardware.automotive.vehicle.V2_0.VehicleProperty.EPOCH_TIME;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.automotive.vehicle.V2_0.VehicleArea;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyStatus;
import android.util.IndentingPrintWriter;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Writes the Android System time to EPOCH_TIME in the VHAL, if supported. */
public final class TimeHalService extends HalServiceBase {

    private static final int[] SUPPORTED_PROPERTIES = new int[]{EPOCH_TIME};

    private final Context mContext;

    private final VehicleHal mHal;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())) {
                updateProperty(System.currentTimeMillis());
            }
        }
    };

    private boolean mReceiverRegistered;

    @Nullable
    private Instant mLastAndroidTimeReported;

    private boolean mAndroidTimeSupported;

    TimeHalService(Context context, VehicleHal hal) {
        mContext = requireNonNull(context);
        mHal = requireNonNull(hal);
    }

    @Override
    public void init() {
        if (!mAndroidTimeSupported) {
            return;
        }

        updateProperty(System.currentTimeMillis());

        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
        mReceiverRegistered = true;
    }

    @Override
    public void release() {
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(mReceiver);
            mReceiverRegistered = false;
        }

        mAndroidTimeSupported = false;
        mLastAndroidTimeReported = null;
    }

    @Override
    public int[] getAllSupportedProperties() {
        return SUPPORTED_PROPERTIES;
    }

    @Override
    public void takeProperties(Collection<VehiclePropConfig> properties) {
        for (VehiclePropConfig property : properties) {
            switch (property.prop) {
                case EPOCH_TIME:
                    mAndroidTimeSupported = true;
                    return;
            }
        }
    }

    @Override
    public void onHalEvents(List<VehiclePropValue> values) {
    }

    public boolean isAndroidTimeSupported() {
        return mAndroidTimeSupported;
    }

    private void updateProperty(long timeMillis) {
        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = EPOCH_TIME;
        propValue.areaId = VehicleArea.GLOBAL;
        propValue.status = VehiclePropertyStatus.AVAILABLE;
        propValue.timestamp = timeMillis;
        propValue.value.int64Values.add(timeMillis);

        mHal.set(propValue);
        mLastAndroidTimeReported = Instant.ofEpochMilli(timeMillis);
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(PrintWriter printWriter) {
        IndentingPrintWriter writer = new IndentingPrintWriter(printWriter);
        writer.println("*ExternalTime HAL*");
        writer.increaseIndent();
        writer.printf(
                "mLastAndroidTimeReported: %d millis",
                mLastAndroidTimeReported.toEpochMilli());
        writer.decreaseIndent();
        writer.flush();
    }
}
