/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.device.metric;

import com.android.tradefed.config.Option;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.Timer;
import java.util.TimerTask;

/**
 * A {@link IMetricCollector} that allows to run a collection task periodically at a set interval.
 */
public abstract class ScheduledDeviceMetricCollector extends BaseDeviceMetricCollector {

    @Option(
        name = "fixed-schedule-rate",
        description = "Schedule the timetask as a fixed schedule rate"
    )
    private boolean mFixedScheduleRate = false;

    @Option(
        name = "interval",
        description = "the interval between two tasks being scheduled",
        isTimeVal = true
    )
    private long mIntervalMs = 60 * 1000l;

    private Timer timer;

    @Override
    public final void onTestRunStart(DeviceMetricData runData) {
        CLog.d("starting");
        onStart(runData);
        timer = new Timer();
        TimerTask timerTask =
                new TimerTask() {
                    @Override
                    public void run() {
                        try {
                            collect(runData);
                        } catch (InterruptedException e) {
                            timer.cancel();
                            Thread.currentThread().interrupt();
                            CLog.e("Interrupted exception thrown from task:");
                            CLog.e(e);
                        }
                    }
                };

        if (mFixedScheduleRate) {
            timer.scheduleAtFixedRate(timerTask, 0, mIntervalMs);
        } else {
            timer.schedule(timerTask, 0, mIntervalMs);
        }
    }

    @Override
    public final void onTestRunEnd(DeviceMetricData runData) {
        if (timer != null) {
            timer.cancel();
            timer.purge();
        }
        onEnd(runData);
        CLog.d("finished");
    }

    /**
     * Task periodically & asynchronously run during the test running.
     *
     * @param runData the {@link DeviceMetricData} where to put metrics.
     * @throws InterruptedException
     */
    abstract void collect(DeviceMetricData runData) throws InterruptedException;

    /**
     * Executed when entering this collector.
     *
     * @param runData the {@link DeviceMetricData} where to put metrics.
     */
    void onStart(DeviceMetricData runData) {
        // Does nothing.
    }

    /**
     * Executed when finishing this collector.
     *
     * @param runData the {@link DeviceMetricData} where to put metrics.
     */
    void onEnd(DeviceMetricData runData) {
        // Does nothing.
    }
}
