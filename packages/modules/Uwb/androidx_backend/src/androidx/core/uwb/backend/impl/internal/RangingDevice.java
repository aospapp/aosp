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

package androidx.core.uwb.backend.impl.internal;

import static androidx.core.uwb.backend.impl.internal.RangingSessionCallback.REASON_FAILED_TO_START;
import static androidx.core.uwb.backend.impl.internal.RangingSessionCallback.REASON_STOP_RANGING_CALLED;
import static androidx.core.uwb.backend.impl.internal.RangingSessionCallback.REASON_WRONG_PARAMETERS;
import static androidx.core.uwb.backend.impl.internal.Utils.INVALID_API_CALL;
import static androidx.core.uwb.backend.impl.internal.Utils.RANGING_ALREADY_STARTED;
import static androidx.core.uwb.backend.impl.internal.Utils.STATUS_OK;
import static androidx.core.uwb.backend.impl.internal.Utils.TAG;
import static androidx.core.uwb.backend.impl.internal.Utils.UWB_SYSTEM_CALLBACK_FAILURE;

import static java.util.Objects.requireNonNull;

import android.annotation.WorkerThread;
import android.os.PersistableBundle;
import android.util.Log;
import android.uwb.RangingMeasurement;
import android.uwb.RangingReport;
import android.uwb.RangingSession;
import android.uwb.UwbManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import com.google.common.hash.Hashing;
import com.google.uwb.support.fira.FiraOpenSessionParams;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/** Implements start/stop ranging operations. */
public abstract class RangingDevice {

    private static final int SESSION_ID_UNSET = 0;

    /** Timeout value after ranging start call */
    private static final int RANGING_START_TIMEOUT_MILLIS = 3000;

    protected final UwbManager mUwbManager;

    private final OpAsyncCallbackRunner<Boolean> mOpAsyncCallbackRunner;

    @Nullable
    private UwbAddress mLocalAddress;

    @Nullable
    protected UwbComplexChannel mComplexChannel;

    @Nullable
    protected RangingParameters mRangingParameters;

    /** A serial thread used by System API to handle session callbacks. */
    private Executor mSystemCallbackExecutor;

    /** A serial thread used in system API callbacks to handle Backend callbacks */
    @Nullable
    private ExecutorService mBackendCallbackExecutor;

    /** NotNull when session opening is successful. Set to Null when session is closed. */
    @Nullable
    private RangingSession mRangingSession;

    private boolean mIsRanging = false;

    /** If true, local address and complex channel will be hardcoded */
    private Boolean mForTesting = false;

    @Nullable
    private RangingRoundFailureCallback mRangingRoundFailureCallback = null;

    private boolean mRangingReportedAllowed = false;

    @Nullable
    private String mChipId = null;

    @NonNull
    protected final UwbFeatureFlags mUwbFeatureFlags;

    RangingDevice(UwbManager manager, Executor executor,
            OpAsyncCallbackRunner<Boolean> opAsyncCallbackRunner, UwbFeatureFlags uwbFeatureFlags) {
        mUwbManager = manager;
        this.mSystemCallbackExecutor = executor;
        mOpAsyncCallbackRunner = opAsyncCallbackRunner;
        mOpAsyncCallbackRunner.setOperationTimeoutMillis(RANGING_START_TIMEOUT_MILLIS);
        mUwbFeatureFlags = uwbFeatureFlags;
    }

    /** Sets the chip ID. By default, the default chip is used. */
    public void setChipId(String chipId) {
        mChipId = chipId;
    }

    public Boolean isForTesting() {
        return mForTesting;
    }

    public void setForTesting(Boolean forTesting) {
        mForTesting = forTesting;
    }

    /** Gets local address. The first call will return a randomized short address. */
    public UwbAddress getLocalAddress() {
        if (mLocalAddress == null) {
            mLocalAddress = getRandomizedLocalAddress();
        }
        return mLocalAddress;
    }

    /** Sets local address. */
    public void setLocalAddress(UwbAddress localAddress) {
        mLocalAddress = localAddress;
    }

    /** Gets a randomized short address. */
    private UwbAddress getRandomizedLocalAddress() {
        return UwbAddress.getRandomizedShortAddress();
    }

    protected abstract int hashSessionId(RangingParameters rangingParameters);

    @VisibleForTesting
    static int calculateHashedSessionId(
            UwbAddress controllerAddress, UwbComplexChannel complexChannel) {
        return Hashing.sha256()
                .newHasher()
                .putBytes(controllerAddress.toBytes())
                .putInt(complexChannel.encode())
                .hash()
                .asInt();
    }

    /** Sets the ranging parameter for this session. */
    public synchronized void setRangingParameters(RangingParameters rangingParameters) {
        if (rangingParameters.getSessionId() == SESSION_ID_UNSET) {
            int sessionId = hashSessionId(rangingParameters);
            mRangingParameters =
                    new RangingParameters(
                            rangingParameters.getUwbConfigId(),
                            sessionId,
                            rangingParameters.getSubSessionId(),
                            rangingParameters.getSessionKeyInfo(),
                            rangingParameters.getSubSessionKeyInfo(),
                            rangingParameters.getComplexChannel(),
                            rangingParameters.getPeerAddresses(),
                            rangingParameters.getRangingUpdateRate(),
                            rangingParameters.getUwbRangeDataNtfConfig());
        } else {
            mRangingParameters = rangingParameters;
        }
    }

    /** Alive means the session is open. */
    public boolean isAlive() {
        return mRangingSession != null;
    }

    /**
     * Is the ranging ongoing or not. Since the device can be stopped by peer or scheduler, the
     * session can be open but not ranging
     */
    public boolean isRanging() {
        return mIsRanging;
    }

    protected boolean isKnownPeer(UwbAddress address) {
        requireNonNull(mRangingParameters);
        return mRangingParameters.getPeerAddresses().contains(address);
    }

    /**
     * Converts the {@link RangingReport} to {@link RangingPosition} and invokes the GMSCore
     * callback.
     */
    // Null-guard prevents this from being null
    private synchronized void onRangingDataReceived(
            RangingReport rangingReport, RangingSessionCallback callback) {
        List<RangingMeasurement> measurements = rangingReport.getMeasurements();
        for (RangingMeasurement measurement : measurements) {
            byte[] remoteAddressBytes = measurement.getRemoteDeviceAddress().toBytes();

            UwbAddress peerAddress = UwbAddress.fromBytes(remoteAddressBytes);
            if (!isKnownPeer(peerAddress)) {
                continue;
            }

            if (measurement.getStatus() != RangingMeasurement.RANGING_STATUS_SUCCESS
                    && mRangingRoundFailureCallback != null) {
                mRangingRoundFailureCallback.onRangingRoundFailed(peerAddress);
            }

            RangingPosition currentPosition = Conversions.convertToPosition(measurement);
            if (currentPosition == null) {
                continue;
            }
            UwbDevice uwbDevice = UwbDevice.createForAddress(peerAddress.toBytes());
            callback.onRangingResult(uwbDevice, currentPosition);
        }
    }

    /**
     * Run callbacks in {@link RangingSessionCallback} on this thread. Make sure that no lock is
     * acquired when the callbacks are called since the code is out of this class.
     */
    protected void runOnBackendCallbackThread(Runnable action) {
        requireNonNull(mBackendCallbackExecutor);
        mBackendCallbackExecutor.execute(action);
    }

    private UwbDevice getUwbDevice() {
        return UwbDevice.createForAddress(getLocalAddress().toBytes());
    }

    protected RangingSession.Callback convertCallback(RangingSessionCallback callback) {
        return new RangingSession.Callback() {

            @WorkerThread
            @Override
            public void onOpened(RangingSession session) {
                mRangingSession = session;
                mOpAsyncCallbackRunner.complete(true);
            }

            @WorkerThread
            @Override
            public void onOpenFailed(int reason, PersistableBundle params) {
                Log.i(TAG, String.format("Session open failed: reason %s", reason));
                int suspendedReason = Conversions.convertReason(reason);
                if (suspendedReason == REASON_UNKNOWN) {
                    suspendedReason = REASON_FAILED_TO_START;
                }
                mRangingSession = null;
                mOpAsyncCallbackRunner.complete(false);
                int finalSuspendedReason = suspendedReason;
                runOnBackendCallbackThread(
                        () -> callback.onRangingSuspended(getUwbDevice(), finalSuspendedReason));
            }

            @WorkerThread
            @Override
            public void onStarted(PersistableBundle sessionInfo) {
                callback.onRangingInitialized(getUwbDevice());
                mIsRanging = true;
                mOpAsyncCallbackRunner.complete(true);
            }

            @WorkerThread
            @Override
            public void onStartFailed(int reason, PersistableBundle params) {

                int suspendedReason = Conversions.convertReason(reason);
                if (suspendedReason != REASON_WRONG_PARAMETERS) {
                    suspendedReason = REASON_FAILED_TO_START;
                }
                int finalSuspendedReason = suspendedReason;
                runOnBackendCallbackThread(
                        () -> callback.onRangingSuspended(getUwbDevice(), finalSuspendedReason));
                if (mRangingSession != null) {
                    mRangingSession.close();
                }
                mRangingSession = null;
                mOpAsyncCallbackRunner.complete(false);
            }

            @WorkerThread
            @Override
            public void onReconfigured(PersistableBundle params) {
                mOpAsyncCallbackRunner.complete(true);
            }

            @WorkerThread
            @Override
            public void onReconfigureFailed(int reason, PersistableBundle params) {
                mOpAsyncCallbackRunner.complete(false);
            }

            @WorkerThread
            @Override
            public void onStopped(int reason, PersistableBundle params) {
                int suspendedReason = Conversions.convertReason(reason);
                UwbDevice device = getUwbDevice();
                runOnBackendCallbackThread(
                        () -> {
                            synchronized (RangingDevice.this) {
                                mIsRanging = false;
                            }
                            callback.onRangingSuspended(device, suspendedReason);
                        });
                if (suspendedReason == REASON_STOP_RANGING_CALLED
                        && mOpAsyncCallbackRunner.isActive()) {
                    mOpAsyncCallbackRunner.complete(true);
                }
            }

            @WorkerThread
            @Override
            public void onStopFailed(int reason, PersistableBundle params) {
                mOpAsyncCallbackRunner.complete(false);
            }

            @WorkerThread
            @Override
            public void onClosed(int reason, PersistableBundle parameters) {
                mRangingSession = null;
                mOpAsyncCallbackRunner.complete(true);
            }

            @WorkerThread
            @Override
            public void onReportReceived(RangingReport rangingReport) {
                if (mRangingReportedAllowed) {
                    runOnBackendCallbackThread(
                            () -> onRangingDataReceived(rangingReport, callback));
                }
            }
        };
    }

    protected abstract FiraOpenSessionParams getOpenSessionParams();

    private String getString(@Nullable Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof int[]) {
            return Arrays.toString((int[]) o);
        }

        if (o instanceof byte[]) {
            return Arrays.toString((byte[]) o);
        }

        if (o instanceof long[]) {
            return Arrays.toString((long[]) o);
        }

        return o.toString();
    }

    private void printStartRangingParameters(PersistableBundle parameters) {
        Log.i(TAG, "Opens UWB session with bundle parameters:");
        for (String key : parameters.keySet()) {
            Log.i(TAG, String.format(
                    "UWB parameter: %s, value: %s", key, getString(parameters.get(key))));
        }
    }

    /**
     * Starts ranging. if an active ranging session exists, return {@link
     * RangingSessionCallback#REASON_FAILED_TO_START}
     */
    @Utils.UwbStatusCodes
    public synchronized int startRanging(
            RangingSessionCallback callback, ExecutorService backendCallbackExecutor) {
        if (isAlive()) {
            return RANGING_ALREADY_STARTED;
        }

        if (mLocalAddress == null) {
            return INVALID_API_CALL;
        }

        PersistableBundle parameters = getOpenSessionParams().toBundle();
        printStartRangingParameters(parameters);
        mBackendCallbackExecutor = backendCallbackExecutor;
        boolean success =
                mOpAsyncCallbackRunner.execOperation(
                        () -> {
                            if (mChipId != null) {
                                mUwbManager.openRangingSession(
                                        parameters,
                                        mSystemCallbackExecutor,
                                        convertCallback(callback),
                                        mChipId);
                            } else {
                                mUwbManager.openRangingSession(
                                        parameters,
                                        mSystemCallbackExecutor,
                                        convertCallback(callback));
                            }
                        },
                        "Open session");

        Boolean result = mOpAsyncCallbackRunner.getResult();
        if (!success || result == null || !result) {
            requireNonNull(mBackendCallbackExecutor);
            mBackendCallbackExecutor.shutdown();
            mBackendCallbackExecutor = null;
            // onRangingSuspended should have been called in the callback.
            return STATUS_OK;
        }

        success =
                mOpAsyncCallbackRunner.execOperation(
                        () -> mRangingSession.start(new PersistableBundle()), "Start ranging");

        result = mOpAsyncCallbackRunner.getResult();
        requireNonNull(mBackendCallbackExecutor);
        if (!success || result == null || !result) {
            mBackendCallbackExecutor.shutdown();
            mBackendCallbackExecutor = null;
        } else {
            mRangingReportedAllowed = true;
        }
        return STATUS_OK;
    }

    /** Stops ranging if the session is ranging. */
    public synchronized int stopRanging() {
        if (!isAlive()) {
            Log.w(TAG, "UWB stopRanging called without an active session.");
            return INVALID_API_CALL;
        }
        mRangingReportedAllowed = false;
        if (mIsRanging) {
            mOpAsyncCallbackRunner.execOperation(
                    () -> requireNonNull(mRangingSession).stop(), "Stop Ranging");
        } else {
            Log.i(TAG, "UWB stopRanging called but isRanging is false.");
        }

        boolean success =
                mOpAsyncCallbackRunner.execOperation(
                        () -> requireNonNull(mRangingSession).close(), "Close Session");

        if (mBackendCallbackExecutor != null) {
            mBackendCallbackExecutor.shutdown();
            mBackendCallbackExecutor = null;
        }
        mLocalAddress = null;
        mComplexChannel = null;
        Boolean result = mOpAsyncCallbackRunner.getResult();
        if (!success || result == null || !result) {
            return UWB_SYSTEM_CALLBACK_FAILURE;
        }
        return STATUS_OK;
    }

    /**
     * Supports ranging configuration change. For example, a new peer is added to the active ranging
     * session.
     *
     * @return returns true if the session is not active or reconfiguration is successful.
     */
    protected synchronized boolean reconfigureRanging(PersistableBundle bundle) {
        boolean success =
                mOpAsyncCallbackRunner.execOperation(
                        () -> mRangingSession.reconfigure(bundle), "Reconfigure Ranging");
        Boolean result = mOpAsyncCallbackRunner.getResult();
        return success && result != null && result;
    }

    /** Notifies that a ranging round failed. We collect this info for Analytics only. */
    public interface RangingRoundFailureCallback {
        /** Reports ranging round failed. */
        void onRangingRoundFailed(UwbAddress peerAddress);
    }

    /** Sets RangingRoundFailureCallback. */
    public void setRangingRoundFailureCallback(
            @Nullable RangingRoundFailureCallback rangingRoundFailureCallback) {
        this.mRangingRoundFailureCallback = rangingRoundFailureCallback;
    }

    /** Sets the system callback executor. */
    public void setSystemCallbackExecutor(Executor executor) {
        this.mSystemCallbackExecutor = executor;
    }
}
