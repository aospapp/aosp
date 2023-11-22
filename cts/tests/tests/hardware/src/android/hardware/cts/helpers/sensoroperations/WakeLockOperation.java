/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.cts.helpers.sensoroperations;

import android.content.Context;
import android.hardware.cts.helpers.SensorStats;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

/**
 * An {@link ISensorOperation} which holds a wakelock while performing another
 * {@link ISensorOperation}.
 */
public class WakeLockOperation extends AbstractSensorOperation {
    private static final String TAG = "WakeLockOperation";

    private final ISensorOperation mOperation;
    private final Context mContext;
    private final int mWakelockFlags;

    /**
     * Constructor for {@link WakeLockOperation}.
     *
     * @param operation the child {@link ISensorOperation} to perform after the delay
     * @param context the context used to access the power manager
     * @param wakelockFlags the flags used when acquiring the wakelock
     */
    public WakeLockOperation(ISensorOperation operation, Context context, int wakelockFlags) {
        mOperation = operation;
        mContext = context;
        mWakelockFlags = wakelockFlags;
    }

    /**
     * Constructor for {@link WakeLockOperation}.
     *
     * @param operation the child {@link ISensorOperation} to perform after the delay
     * @param context the context used to access the power manager
     */
    public WakeLockOperation(ISensorOperation operation, Context context) {
        this(operation, context, PowerManager.PARTIAL_WAKE_LOCK);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws InterruptedException {
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        WakeLock wakeLock = pm.newWakeLock(mWakelockFlags, TAG);

        wakeLock.acquire();
        try {
            mOperation.execute();
        } finally {
            wakeLock.release();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SensorStats getStats() {
        return mOperation.getStats();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ISensorOperation clone() {
        return new WakeLockOperation(mOperation, mContext, mWakelockFlags);
    }
}
