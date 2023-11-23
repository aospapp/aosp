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

package android.telephony.satellite.cts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.telephony.satellite.AntennaDirection;
import android.telephony.satellite.AntennaPosition;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.stub.ISatelliteCapabilitiesConsumer;
import android.telephony.satellite.stub.ISatelliteListener;
import android.telephony.satellite.stub.NTRadioTechnology;
import android.telephony.satellite.stub.PointingInfo;
import android.telephony.satellite.stub.SatelliteCapabilities;
import android.telephony.satellite.stub.SatelliteDatagram;
import android.telephony.satellite.stub.SatelliteError;
import android.telephony.satellite.stub.SatelliteImplBase;
import android.telephony.satellite.stub.SatelliteModemState;
import android.telephony.satellite.stub.SatelliteService;
import android.util.Log;

import com.android.internal.telephony.IBooleanConsumer;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.util.FunctionalUtils;
import com.android.telephony.Rlog;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class MockSatelliteService extends SatelliteImplBase {
    private static final String TAG = "MockSatelliteService";

    // Hardcoded values below
    private static final int SATELLITE_ALWAYS_VISIBLE = 0;
    /** SatelliteCapabilities constant indicating that the radio technology is proprietary. */
    private static final int[] SUPPORTED_RADIO_TECHNOLOGIES =
            new int[]{NTRadioTechnology.PROPRIETARY};
    /** SatelliteCapabilities constant indicating that pointing to satellite is required. */
    private static final boolean POINTING_TO_SATELLITE_REQUIRED = true;
    /** SatelliteCapabilities constant indicating the maximum number of characters per datagram. */
    private static final int MAX_BYTES_PER_DATAGRAM = 339;
    /** SatelliteCapabilities constant keys which are used to fill mAntennaPositionMap. */
    private static final int[] ANTENNA_POSITION_KEYS = new int[]{
            SatelliteManager.DISPLAY_MODE_OPENED, SatelliteManager.DISPLAY_MODE_CLOSED};
    /** SatelliteCapabilities constant values which are used to fill mAntennaPositionMap. */
    private static final AntennaPosition[] ANTENNA_POSITION_VALUES = new AntennaPosition[] {
            new AntennaPosition(new AntennaDirection(1,1,1),
                    SatelliteManager.DEVICE_HOLD_POSITION_PORTRAIT),
            new AntennaPosition(new AntennaDirection(2,2,2),
                    SatelliteManager.DEVICE_HOLD_POSITION_LANDSCAPE_LEFT)
    };

    @NonNull
    private final Map<IBinder, ISatelliteListener> mRemoteListeners = new HashMap<>();
    @Nullable private ILocalSatelliteListener mLocalListener;
    private final LocalBinder mBinder = new LocalBinder();
    @SatelliteError
    private int mErrorCode = SatelliteError.ERROR_NONE;

    // For local access of this Service.
    class LocalBinder extends Binder {
        MockSatelliteService getService() {
            return MockSatelliteService.this;
        }
    }

    private boolean mIsCommunicationAllowedInLocation;
    private boolean mIsEnabled;
    private boolean mIsProvisioned;
    private boolean mIsSupported;
    private int mModemState;
    private final AtomicBoolean mWaitToSend = new AtomicBoolean(false);
    private SatelliteDatagram mDatagramToBeSent;
    private boolean mIsEmergencyDatagram;
    private IIntegerConsumer mSendDatagramErrorCallback;
    private Object mSendDatagramWithDelayLock = new Object();
    private static final long TIMEOUT = 1000;
    private final AtomicBoolean mShouldRespondTelephony = new AtomicBoolean(true);

    /**
     * Create MockSatelliteService using the Executor specified for methods being called from
     * the framework.
     *
     * @param executor The executor for the framework to use when executing satellite methods.
     */
    public MockSatelliteService(@NonNull Executor executor) {
        super(executor);
        mIsCommunicationAllowedInLocation = true;
        mIsEnabled = false;
        mIsProvisioned = false;
        mIsSupported = true;
        mModemState = SatelliteModemState.SATELLITE_MODEM_STATE_OFF;
    }

    /**
     * Zero-argument constructor to prevent service binding exception.
     */
    public MockSatelliteService() {
        this(Runnable::run);
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SatelliteService.SERVICE_INTERFACE.equals(intent.getAction())) {
            logd("Remote service bound");
            return getBinder();
        }
        logd("Local service bound");
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        logd("onCreate");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logd("onDestroy");
    }

    @Override
    public void setSatelliteListener(@NonNull ISatelliteListener listener) {
        logd("setSatelliteListener");
        mRemoteListeners.put(listener.asBinder(), listener);
        notifyRemoteServiceConnected();
    }

    @Override
    public void requestSatelliteListeningEnabled(boolean enabled, int timeout,
            @NonNull IIntegerConsumer errorCallback) {
        logd("requestSatelliteListeningEnabled: mErrorCode=" + mErrorCode);

        if (mLocalListener != null) {
            runWithExecutor(() -> mLocalListener.onSatelliteListeningEnabled(enabled));
        } else {
            loge("requestSatelliteListeningEnabled: mLocalListener is null");
        }

        if (!verifySatelliteModemState(errorCallback)) {
            return;
        }
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }

        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> errorCallback.accept(SatelliteError.ERROR_NONE));
        }
        if (enabled) {
            updateSatelliteModemState(SatelliteModemState.SATELLITE_MODEM_STATE_LISTENING);
        } else {
            updateSatelliteModemState(SatelliteModemState.SATELLITE_MODEM_STATE_IDLE);
        }
    }

    @Override
    public void requestSatelliteEnabled(boolean enableSatellite, boolean enableDemoMode,
            @NonNull IIntegerConsumer errorCallback) {
        logd("requestSatelliteEnabled: mErrorCode=" + mErrorCode
                + ", enableSatellite=" + enableSatellite
                + ", enableDemoMode=" + enableDemoMode
                + ", mShouldRespondTelephony=" + mShouldRespondTelephony.get());
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }

        if (enableSatellite) {
            enableSatellite(errorCallback);
        } else {
            disableSatellite(errorCallback);
        }
    }

    private void enableSatellite(@NonNull IIntegerConsumer errorCallback) {
        mIsEnabled = true;
        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> errorCallback.accept(SatelliteError.ERROR_NONE));
        }
        updateSatelliteModemState(SatelliteModemState.SATELLITE_MODEM_STATE_IDLE);
    }

    private void disableSatellite(@NonNull IIntegerConsumer errorCallback) {
        mIsEnabled = false;
        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> errorCallback.accept(SatelliteError.ERROR_NONE));
        }
        updateSatelliteModemState(SatelliteModemState.SATELLITE_MODEM_STATE_OFF);
    }

    @Override
    public void requestIsSatelliteEnabled(@NonNull IIntegerConsumer errorCallback,
            @NonNull IBooleanConsumer callback) {
        logd("requestIsSatelliteEnabled: mErrorCode=" + mErrorCode);
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }
        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> callback.accept(mIsEnabled));
        }
    }

    @Override
    public void requestIsSatelliteSupported(@NonNull IIntegerConsumer errorCallback,
            @NonNull IBooleanConsumer callback) {
        logd("requestIsSatelliteSupported");
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }
        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> callback.accept(mIsSupported));
        }
    }

    @Override
    public void requestSatelliteCapabilities(@NonNull IIntegerConsumer errorCallback,
            @NonNull ISatelliteCapabilitiesConsumer callback) {
        logd("requestSatelliteCapabilities: mErrorCode=" + mErrorCode);
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }

        if (mShouldRespondTelephony.get()) {
            SatelliteCapabilities capabilities = new SatelliteCapabilities();
            capabilities.supportedRadioTechnologies = SUPPORTED_RADIO_TECHNOLOGIES;
            capabilities.isPointingRequired = POINTING_TO_SATELLITE_REQUIRED;
            capabilities.maxBytesPerOutgoingDatagram = MAX_BYTES_PER_DATAGRAM;
            capabilities.antennaPositionKeys = ANTENNA_POSITION_KEYS;
            capabilities.antennaPositionValues = ANTENNA_POSITION_VALUES;
            runWithExecutor(() -> callback.accept(capabilities));
        }
    }

    @Override
    public void startSendingSatellitePointingInfo(@NonNull IIntegerConsumer errorCallback) {
        logd("startSendingSatellitePointingInfo: mErrorCode=" + mErrorCode);
        if (!verifySatelliteModemState(errorCallback)) {
            if (mLocalListener != null) {
                runWithExecutor(() -> mLocalListener.onStartSendingSatellitePointingInfo());
            } else {
                loge("startSendingSatellitePointingInfo: mLocalListener is null");
            }
            return;
        }

        if (mShouldRespondTelephony.get()) {
            if (mErrorCode != SatelliteError.ERROR_NONE) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            } else {
                runWithExecutor(() -> errorCallback.accept(SatelliteError.ERROR_NONE));
            }
        }

        if (mLocalListener != null) {
            runWithExecutor(() -> mLocalListener.onStartSendingSatellitePointingInfo());
        } else {
            loge("startSendingSatellitePointingInfo: mLocalListener is null");
        }
    }

    @Override
    public void stopSendingSatellitePointingInfo(@NonNull IIntegerConsumer errorCallback) {
        logd("stopSendingSatellitePointingInfo: mErrorCode=" + mErrorCode);
        if (mShouldRespondTelephony.get()) {
            if (mErrorCode != SatelliteError.ERROR_NONE) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            } else {
                runWithExecutor(() -> errorCallback.accept(SatelliteError.ERROR_NONE));
            }
        }

        if (mLocalListener != null) {
            runWithExecutor(() -> mLocalListener.onStopSendingSatellitePointingInfo());
        } else {
            loge("stopSendingSatellitePointingInfo: mLocalListener is null");
        }
    }

    @Override
    public void provisionSatelliteService(@NonNull String token, @NonNull byte[] provisionData,
            @NonNull IIntegerConsumer errorCallback) {
        logd("provisionSatelliteService: mErrorCode=" + mErrorCode);
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }
        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> errorCallback.accept(SatelliteError.ERROR_NONE));
        }
        updateSatelliteProvisionState(true);
    }

    @Override
    public void deprovisionSatelliteService(@NonNull String token,
            @NonNull IIntegerConsumer errorCallback) {
        logd("deprovisionSatelliteService: mErrorCode=" + mErrorCode);
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }
        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> errorCallback.accept(SatelliteError.ERROR_NONE));
        }
        updateSatelliteProvisionState(false);
    }

    @Override
    public void requestIsSatelliteProvisioned(@NonNull IIntegerConsumer errorCallback,
            @NonNull IBooleanConsumer callback) {
        logd("requestIsSatelliteProvisioned: mErrorCode=" + mErrorCode);
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }
        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> callback.accept(mIsProvisioned));
        }
    }

    @Override
    public void pollPendingSatelliteDatagrams(@NonNull IIntegerConsumer errorCallback) {
        logd("pollPendingSatelliteDatagrams: mErrorCode=" + mErrorCode);
        updateSatelliteModemState(
                SatelliteModemState.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING);
        if (mShouldRespondTelephony.get()) {
            if (mErrorCode != SatelliteError.ERROR_NONE) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            } else {
                runWithExecutor(() -> errorCallback.accept(SatelliteError.ERROR_NONE));
            }
        }

        if (mLocalListener != null) {
            runWithExecutor(() -> mLocalListener.onPollPendingSatelliteDatagrams());
        } else {
            loge("pollPendingSatelliteDatagrams: mLocalListener is null");
        }
    }

    @Override
    public void sendSatelliteDatagram(@NonNull SatelliteDatagram datagram, boolean isEmergency,
            @NonNull IIntegerConsumer errorCallback) {
        logd("sendSatelliteDatagram: mErrorCode=" + mErrorCode);

        if (mWaitToSend.get()) {
            synchronized (mSendDatagramWithDelayLock) {
                // Save the datagram
                mDatagramToBeSent = datagram;
                mIsEmergencyDatagram = isEmergency;
                mSendDatagramErrorCallback = errorCallback;
            }

            if (mLocalListener != null) {
                runWithExecutor(() -> mLocalListener.onSendSatelliteDatagram(
                        datagram, isEmergency));
            } else {
                loge("sendSatelliteDatagram: mLocalListener is null");
            }
        } else {
            updateSatelliteModemState(
                    SatelliteModemState.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING);
            if (mShouldRespondTelephony.get()) {
                if (mErrorCode != SatelliteError.ERROR_NONE) {
                    runWithExecutor(() -> errorCallback.accept(mErrorCode));
                } else {
                    runWithExecutor(() -> errorCallback.accept(SatelliteError.ERROR_NONE));
                }
            }

            if (mLocalListener != null) {
                runWithExecutor(() -> mLocalListener.onSendSatelliteDatagram(
                        datagram, isEmergency));
            } else {
                loge("sendSatelliteDatagram: mLocalListener is null");
            }
            updateSatelliteModemState(SatelliteModemState.SATELLITE_MODEM_STATE_IDLE);
        }
    }

    @Override
    public void requestSatelliteModemState(@NonNull IIntegerConsumer errorCallback,
            @NonNull IIntegerConsumer callback) {
        logd("requestSatelliteModemState: mErrorCode=" + mErrorCode);
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }
        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> callback.accept(mModemState));
        }
    }

    @Override
    public void requestIsSatelliteCommunicationAllowedForCurrentLocation(
            @NonNull IIntegerConsumer errorCallback, @NonNull IBooleanConsumer callback) {
        logd("requestIsSatelliteCommunicationAllowedForCurrentLocation: mErrorCode=" + mErrorCode);
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }

        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> callback.accept(mIsCommunicationAllowedInLocation));
        }
    }

    @Override
    public void requestTimeForNextSatelliteVisibility(@NonNull IIntegerConsumer errorCallback,
            @NonNull IIntegerConsumer callback) {
        logd("requestTimeForNextSatelliteVisibility: mErrorCode=" + mErrorCode);
        if (mErrorCode != SatelliteError.ERROR_NONE) {
            if (mShouldRespondTelephony.get()) {
                runWithExecutor(() -> errorCallback.accept(mErrorCode));
            }
            return;
        }
        if (mShouldRespondTelephony.get()) {
            runWithExecutor(() -> callback.accept(SATELLITE_ALWAYS_VISIBLE));
        }
    }

    public void setLocalSatelliteListener(@NonNull ILocalSatelliteListener listener) {
        logd("setLocalSatelliteListener: listener=" + listener);
        mLocalListener = listener;
    }

    public void setErrorCode(@SatelliteError int errorCode) {
        logd("setErrorCode: errorCode=" + errorCode);
        mErrorCode = errorCode;
    }

    public void setSatelliteSupport(boolean supported) {
        logd("setSatelliteSupport: supported=" + supported);
        mIsSupported = supported;
    }

    public void setShouldRespondTelephony(boolean shouldRespondTelephony) {
        mShouldRespondTelephony.set(shouldRespondTelephony);
    }

    public void sendOnSatelliteDatagramReceived(SatelliteDatagram datagram, int pendingCount) {
        logd("sendOnSatelliteDatagramReceived");
        mRemoteListeners.values().forEach(listener -> runWithExecutor(() ->
                listener.onSatelliteDatagramReceived(datagram, pendingCount)));
        updateSatelliteModemState(SatelliteModemState.SATELLITE_MODEM_STATE_IDLE);
    }

    public void sendOnPendingDatagrams() {
        logd("sendOnPendingDatagrams");
        mRemoteListeners.values().forEach(listener -> runWithExecutor(() ->
                listener.onPendingDatagrams()));
    }

    public void sendOnSatellitePositionChanged(PointingInfo pointingInfo) {
        logd("sendOnSatellitePositionChanged");
        mRemoteListeners.values().forEach(listener -> runWithExecutor(() ->
                listener.onSatellitePositionChanged(pointingInfo)));
    }

    public void setWaitToSend(boolean wait) {
        mWaitToSend.set(wait);
    }

    public boolean sendSavedDatagram() {
        synchronized (mSendDatagramWithDelayLock) {
            logd("sendSavedDatagram");
            if (mSendDatagramErrorCallback == null) {
                return false;
            }

            if (mShouldRespondTelephony.get()) {
                if (mErrorCode != SatelliteError.ERROR_NONE) {
                    runWithExecutor(() -> mSendDatagramErrorCallback.accept(mErrorCode));
                } else {
                    runWithExecutor(
                            () -> mSendDatagramErrorCallback.accept(SatelliteError.ERROR_NONE));
                }
            }
            mSendDatagramErrorCallback = null;
            return true;
        }
    }

    /**
     * Helper method to verify that the satellite modem is properly configured to receive
     * requests.
     *
     * @param errorCallback The callback to notify of any errors preventing satellite requests.
     * @return {@code true} if the satellite modem is configured to receive requests and
     * {@code false} if it is not.
     */
    private boolean verifySatelliteModemState(@NonNull IIntegerConsumer errorCallback) {
        if (!mIsSupported) {
            runWithExecutor(() -> errorCallback.accept(SatelliteError.REQUEST_NOT_SUPPORTED));
            return false;
        }
        if (!mIsProvisioned) {
            runWithExecutor(() -> errorCallback.accept(SatelliteError.SERVICE_NOT_PROVISIONED));
            return false;
        }
        if (!mIsEnabled) {
            runWithExecutor(() -> errorCallback.accept(SatelliteError.INVALID_MODEM_STATE));
            return false;
        }
        return true;
    }

    /**
     * Update the satellite modem state and notify listeners if it changed.
     *
     * @param modemState The {@link SatelliteModemState} to update.
     */
    private void updateSatelliteModemState(int modemState) {
        if (modemState == mModemState) {
            return;
        }
        logd("updateSatelliteModemState modemState=" + modemState);
        mRemoteListeners.values().forEach(listener -> runWithExecutor(() ->
                listener.onSatelliteModemStateChanged(modemState)));
        mModemState = modemState;
    }

    /**
     * Update the satellite provision state and notify listeners if it changed.
     *
     * @param isProvisioned {@code true} if the satellite is currently provisioned and
     *                      {@code false} if it is not.
     */
    private void updateSatelliteProvisionState(boolean isProvisioned) {
        logd("updateSatelliteProvisionState: isProvisioned=" + isProvisioned
                + ", mIsProvisioned=" + mIsProvisioned);
        if (isProvisioned == mIsProvisioned) {
            return;
        }
        mIsProvisioned = isProvisioned;
        logd("updateSatelliteProvisionState: mRemoteListeners.size=" + mRemoteListeners.size());
        mRemoteListeners.values().forEach(listener -> runWithExecutor(() ->
                listener.onSatelliteProvisionStateChanged(mIsProvisioned)));
    }

    /**
     * Execute the given runnable using the executor that this service was created with.
     *
     * @param r A runnable that can throw an exception.
     */
    private void runWithExecutor(@NonNull FunctionalUtils.ThrowingRunnable r) {
        mExecutor.execute(() -> Binder.withCleanCallingIdentity(r));
    }

    private void notifyRemoteServiceConnected() {
        logd("notifyRemoteServiceConnected");
        runWithExecutor(() -> mLocalListener.onRemoteServiceConnected());
    }

    /**
     * Log the message to the radio buffer with {@code DEBUG} priority.
     *
     * @param log The message to log.
     */
    private static void logd(@NonNull String log) {
        Rlog.d(TAG, log);
    }

    /**
     * Log with error attribute
     *
     * @param s is string log
     */
    protected void loge(@NonNull String s) {
        Log.e(TAG, s);
    }
}
