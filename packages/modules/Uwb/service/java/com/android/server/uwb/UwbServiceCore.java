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

package com.android.server.uwb;

import static android.uwb.UwbManager.MESSAGE_TYPE_COMMAND;

import static com.android.server.uwb.data.UwbUciConstants.FIRA_VERSION_MAJOR_2;
import static com.android.server.uwb.data.UwbUciConstants.STATUS_CODE_OK;

import android.annotation.NonNull;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.uwb.IOnUwbActivityEnergyInfoListener;
import android.uwb.IUwbAdapterStateCallbacks;
import android.uwb.IUwbOemExtensionCallback;
import android.uwb.IUwbRangingCallbacks;
import android.uwb.IUwbVendorUciCallback;
import android.uwb.RangingChangeReason;
import android.uwb.SessionHandle;
import android.uwb.StateChangeReason;
import android.uwb.UwbActivityEnergyInfo;
import android.uwb.UwbAddress;
import android.uwb.UwbManager.AdapterStateCallback;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.uwb.data.UwbUciConstants;
import com.android.server.uwb.data.UwbVendorUciResponse;
import com.android.server.uwb.info.UwbPowerStats;
import com.android.server.uwb.jni.INativeUwbManager;
import com.android.server.uwb.jni.NativeUwbManager;

import com.google.uwb.support.base.Params;
import com.google.uwb.support.ccc.CccOpenRangingParams;
import com.google.uwb.support.ccc.CccParams;
import com.google.uwb.support.ccc.CccRangingReconfiguredParams;
import com.google.uwb.support.ccc.CccStartRangingParams;
import com.google.uwb.support.fira.FiraControleeParams;
import com.google.uwb.support.fira.FiraOpenSessionParams;
import com.google.uwb.support.fira.FiraParams;
import com.google.uwb.support.fira.FiraRangingReconfigureParams;
import com.google.uwb.support.generic.GenericParams;
import com.google.uwb.support.generic.GenericSpecificationParams;
import com.google.uwb.support.oemextension.DeviceStatus;
import com.google.uwb.support.profile.UuidBundleWrapper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

/**
 * Core UWB stack.
 */
public class UwbServiceCore implements INativeUwbManager.DeviceNotification,
        INativeUwbManager.VendorNotification, UwbCountryCode.CountryCodeChangedListener {
    private static final String TAG = "UwbServiceCore";

    @VisibleForTesting
    public static final int TASK_ENABLE = 1;
    @VisibleForTesting
    public static final int TASK_DISABLE = 2;
    @VisibleForTesting
    public static final int TASK_RESTART = 3;
    @VisibleForTesting
    public static final int TASK_GET_POWER_STATS = 4;

    @VisibleForTesting
    public static final int WATCHDOG_MS = 10000;
    private static final int SEND_VENDOR_CMD_TIMEOUT_MS = 10000;

    private boolean mIsDiagnosticsEnabled = false;
    private byte mDiagramsFrameReportsFieldsFlags = 0;

    private final PowerManager.WakeLock mUwbWakeLock;
    private final Context mContext;
    private final RemoteCallbackList<IUwbAdapterStateCallbacks>
            mAdapterStateCallbacksList = new RemoteCallbackList<>();
    private final UwbTask mUwbTask;

    private final UwbSessionManager mSessionManager;
    private final UwbConfigurationManager mConfigurationManager;
    private final NativeUwbManager mNativeUwbManager;
    private final UwbMetrics mUwbMetrics;
    private final UwbCountryCode mUwbCountryCode;
    private final UwbInjector mUwbInjector;
    private final Map<String, /* @UwbManager.AdapterStateCallback.State */ Integer>
            mChipIdToStateMap;
    private @StateChangeReason int mLastAdapterStateChangedReason = StateChangeReason.UNKNOWN;
    private @AdapterStateCallback.State int mLastAdapterStateNotification = -1;
    private  IUwbVendorUciCallback mCallBack = null;
    private IUwbOemExtensionCallback mOemExtensionCallback = null;
    private final Handler mHandler;
    private GenericSpecificationParams mCachedSpecificationParams;
    private final Set<InitializationFailureListener> mListeners = new ArraySet<>();

    public UwbServiceCore(Context uwbApplicationContext, NativeUwbManager nativeUwbManager,
            UwbMetrics uwbMetrics, UwbCountryCode uwbCountryCode,
            UwbSessionManager uwbSessionManager, UwbConfigurationManager uwbConfigurationManager,
            UwbInjector uwbInjector, Looper serviceLooper) {
        mContext = uwbApplicationContext;

        Log.d(TAG, "Starting Uwb");

        mUwbWakeLock = mContext.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "UwbServiceCore:mUwbWakeLock");

        mNativeUwbManager = nativeUwbManager;

        mNativeUwbManager.setDeviceListener(this);
        mNativeUwbManager.setVendorListener(this);
        mUwbMetrics = uwbMetrics;
        mUwbCountryCode = uwbCountryCode;
        mUwbCountryCode.addListener(this);
        mSessionManager = uwbSessionManager;
        mConfigurationManager = uwbConfigurationManager;
        mUwbInjector = uwbInjector;

        mChipIdToStateMap = new HashMap<>();
        mUwbInjector.getMultichipData().setOnInitializedListener(
                () -> {
                    for (String chipId : mUwbInjector.getMultichipData().getChipIds()) {
                        updateState(AdapterStateCallback.STATE_DISABLED, chipId);
                    }
                });

        mUwbTask = new UwbTask(serviceLooper);
        mHandler = new Handler(serviceLooper);
    }

    /**
     * Interface for external classes to listen for any initialization failures.
     * Added to avoid introducing circular dependency between UwbServiceCore & UwbServiceImpl.
     */
    public interface InitializationFailureListener {
        void onFailure();
    }

    public void addInitializationFailureListener(@NonNull InitializationFailureListener listener) {
        mListeners.add(listener);
    }
    public void removeInitializationFailureListener(
            @NonNull InitializationFailureListener listener) {
        mListeners.remove(listener);
    }

    public Handler getHandler() {
        return mHandler;
    }

    public boolean isOemExtensionCbRegistered() {
        return mOemExtensionCallback != null;
    }

    public IUwbOemExtensionCallback getOemExtensionCallback() {
        return mOemExtensionCallback;
    }

    private void updateState(int state, String chipId) {
        Log.d(TAG, "updateState(): state=" + state + ", chipId=" + chipId);
        synchronized (UwbServiceCore.this) {
            mChipIdToStateMap.put(chipId, state);
            Log.d(TAG, "chipIdToStateMap = " + mChipIdToStateMap);
        }
    }

    private boolean isUwbEnabled() {
        synchronized (UwbServiceCore.this) {
            return getInternalAdapterState() != AdapterStateCallback.STATE_DISABLED;
        }
    }

    String getDeviceStateString(int state) {
        String ret = "";
        switch (state) {
            case UwbUciConstants.DEVICE_STATE_OFF:
                ret = "OFF";
                break;
            case UwbUciConstants.DEVICE_STATE_READY:
                ret = "READY";
                break;
            case UwbUciConstants.DEVICE_STATE_ACTIVE:
                ret = "ACTIVE";
                break;
            case UwbUciConstants.DEVICE_STATE_ERROR:
                ret = "ERROR";
                break;
        }
        return ret;
    }

    @Override
    public void onVendorUciNotificationReceived(int gid, int oid, byte[] payload) {
        Log.i(TAG, "onVendorUciNotificationReceived");
        if (mCallBack != null) {
            try {
                mCallBack.onVendorNotificationReceived(gid, oid, payload);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send vendor notification", e);
            }
        }
    }

    @Override
    public void onDeviceStatusNotificationReceived(int deviceState, String chipId) {
        Log.d(TAG, "onDeviceStatusNotificationReceived(): deviceState = " + deviceState
                + ", current country code = " + mUwbCountryCode.getCountryCode());

        // If error status is received, toggle UWB off to reset stack state.
        // TODO(b/227488208): Should we try to restart (like wifi) instead?
        if (!mUwbInjector.getMultichipData().getChipIds().contains(chipId)) {
            Log.e(TAG, "onDeviceStatusNotificationReceived with invalid chipId " + chipId
                    + ". Ignoring...");
            return;
        }

        if ((byte) deviceState == UwbUciConstants.DEVICE_STATE_ERROR) {
            Log.e(TAG, "Error device status received. Restarting...");
            mUwbMetrics.incrementDeviceStatusErrorCount();
            takBugReportAfterDeviceError("UWB Bugreport: restarting UWB due to device error");
            mUwbTask.execute(TASK_RESTART);
            oemExtensionDeviceStatusUpdate(deviceState, chipId);
            return;
        }

        updateDeviceState(deviceState, chipId);

        mUwbTask.computeAndNotifyAdapterStateChange(
                getReasonFromDeviceState(deviceState),
                mUwbCountryCode.getCountryCode(),
                Optional.empty());
    }

    void updateDeviceState(int deviceState, String chipId) {
        Log.i(TAG, "updateDeviceState(): deviceState = " + getDeviceStateString(deviceState)
                + ", current internal adapter state = " + getInternalAdapterState());

        oemExtensionDeviceStatusUpdate(deviceState, chipId);
        updateState(getAdapterStateFromDeviceState(deviceState), chipId);
    }

    void oemExtensionDeviceStatusUpdate(int deviceState, String chipId) {
        if (mOemExtensionCallback != null) {
            PersistableBundle deviceStateBundle = new DeviceStatus.Builder()
                    .setDeviceState(deviceState)
                    .setChipId(chipId)
                    .build()
                    .toBundle();
            try {
                mOemExtensionCallback.onDeviceStatusNotificationReceived(deviceStateBundle);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send status notification to oem", e);
            }
        }
    }

    void notifyAdapterState(int adapterState, int reason) {
        // Check if the current adapter state is same as the state in the last adapter state
        // notification, to avoid sending extra onAdapterStateChanged() notifications. For example,
        // this can happen when UWB is toggled on and a valid country code is already set.
        if (mLastAdapterStateNotification == adapterState
                && mLastAdapterStateChangedReason == reason) {
            return;
        }
        Log.d(TAG, "notifyAdapterState(): adapterState = " + adapterState + ", reason = " + reason);

        synchronized (mAdapterStateCallbacksList) {
            if (mAdapterStateCallbacksList.getRegisteredCallbackCount() > 0) {
                final int count = mAdapterStateCallbacksList.beginBroadcast();
                for (int i = 0; i < count; i++) {
                    try {
                        mAdapterStateCallbacksList.getBroadcastItem(i)
                                .onAdapterStateChanged(adapterState, reason);
                    } catch (RemoteException e) {
                        Log.e(TAG, "onAdapterStateChanged is failed");
                    }
                }
                mAdapterStateCallbacksList.finishBroadcast();
            }
        }

        mLastAdapterStateNotification = adapterState;
        mLastAdapterStateChangedReason = reason;
    }

    int getAdapterStateFromDeviceState(int deviceState) {
        int adapterState = AdapterStateCallback.STATE_DISABLED;
        if (deviceState == UwbUciConstants.DEVICE_STATE_OFF) {
            adapterState = AdapterStateCallback.STATE_DISABLED;
        } else if (deviceState == UwbUciConstants.DEVICE_STATE_READY) {
            adapterState = AdapterStateCallback.STATE_ENABLED_INACTIVE;
        } else if (deviceState == UwbUciConstants.DEVICE_STATE_ACTIVE) {
            adapterState = AdapterStateCallback.STATE_ENABLED_ACTIVE;
        }
        return adapterState;
    }

    int getReasonFromDeviceState(int deviceState) {
        int reason = StateChangeReason.UNKNOWN;
        if (deviceState == UwbUciConstants.DEVICE_STATE_OFF) {
            reason = StateChangeReason.SYSTEM_POLICY;
        } else if (deviceState == UwbUciConstants.DEVICE_STATE_READY) {
            reason = StateChangeReason.SYSTEM_POLICY;
        } else if (deviceState == UwbUciConstants.DEVICE_STATE_ACTIVE) {
            reason = StateChangeReason.SESSION_STARTED;
        }
        return reason;
    }

    @Override
    public void onCoreGenericErrorNotificationReceived(int status, String chipId) {
        if (!mUwbInjector.getMultichipData().getChipIds().contains(chipId)) {
            Log.e(TAG, "onCoreGenericErrorNotificationReceived with invalid chipId "
                    + chipId + ". Ignoring...");
            return;
        }
        Log.e(TAG, "onCoreGenericErrorNotificationReceived status = " + status);
        mUwbMetrics.incrementUciGenericErrorCount();
    }

    @Override
    public void onCountryCodeChanged(@Nullable String countryCode) {
        Log.i(TAG, "Received onCountryCodeChanged() with countryCode = " + countryCode);

        // Notify the current UWB adapter state. For example:
        // - If UWB was earlier enabled and at that time the country code was not valid (so
        //   STATE_DISABLED was notified), can now notify STATE_ENABLED_INACTIVE.
        // - If UWB is in STATE_ENABLED_INACTIVE and country code is no longer valid, should
        //   notify STATE_DISABLED.
        mUwbTask.computeAndNotifyAdapterStateChange(
                getReasonFromDeviceState(getInternalAdapterState()),
                countryCode,
                Optional.empty());
        Log.d(TAG, "Resetting cached specifications");
        mCachedSpecificationParams = null;
    }

    public void registerAdapterStateCallbacks(IUwbAdapterStateCallbacks adapterStateCallbacks)
            throws RemoteException {
        synchronized (mAdapterStateCallbacksList) {
            mAdapterStateCallbacksList.register(adapterStateCallbacks);
        }

        int adapterState = getAdapterState();
        Log.d(TAG, "registerAdapterStateCallbacks(): notify adapterState = " + adapterState
                + ", reason = " + mLastAdapterStateChangedReason);
        // We have a new listener being registered (there is no UWB event), so we send the current
        // adapter state with the last known StateChangeReason.
        adapterStateCallbacks.onAdapterStateChanged(adapterState, mLastAdapterStateChangedReason);
    }

    public void unregisterAdapterStateCallbacks(IUwbAdapterStateCallbacks callbacks) {
        synchronized (mAdapterStateCallbacksList) {
            mAdapterStateCallbacksList.unregister(callbacks);
        }
    }

    public void registerVendorExtensionCallback(IUwbVendorUciCallback callbacks) {
        Log.e(TAG, "Register the callback");
        mCallBack = callbacks;
    }

    public void unregisterVendorExtensionCallback(IUwbVendorUciCallback callbacks) {
        Log.e(TAG, "Unregister the callback");
        mCallBack = null;
    }

    public void registerOemExtensionCallback(IUwbOemExtensionCallback callback) {
        if (isOemExtensionCbRegistered()) {
            Log.w(TAG, "Oem extension callback being re-registered");
        }
        Log.e(TAG, "Register Oem Extension callback");
        mOemExtensionCallback = callback;
    }

    public void unregisterOemExtensionCallback(IUwbOemExtensionCallback callback) {
        Log.e(TAG, "Unregister Oem Extension callback");
        mOemExtensionCallback = null;
    }

    /**
     * Get cached specification params
     */
    public GenericSpecificationParams getCachedSpecificationParams(String chipId) {
        if (mCachedSpecificationParams != null) return mCachedSpecificationParams;
        // If nothing in cache, populate it.
        getSpecificationInfo(chipId);
        return mCachedSpecificationParams;
    }

    /**
     * Get specification info
     */
    public PersistableBundle getSpecificationInfo(String chipId) {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }
        Trace.beginSection("UWB#getSpecificationInfo");
        // TODO(b/211445008): Consolidate to a single uwb thread.
        Pair<Integer, GenericSpecificationParams> specificationParams =
                mConfigurationManager.getCapsInfo(
                        GenericParams.PROTOCOL_NAME, GenericSpecificationParams.class, chipId);
        Trace.endSection();
        if (specificationParams.first != UwbUciConstants.STATUS_CODE_OK
                || specificationParams.second == null)  {
            Log.e(TAG, "Failed to retrieve specification params");
            return new PersistableBundle();
        }
        mCachedSpecificationParams = specificationParams.second;
        return specificationParams.second.toBundle();
    }

    public long getTimestampResolutionNanos() {
        return mNativeUwbManager.getTimestampResolutionNanos();
    }

    /** Set whether diagnostics is enabled and set enabled fields */
    public void enableDiagnostics(boolean enabled, byte flags) {
        this.mIsDiagnosticsEnabled = enabled;
        this.mDiagramsFrameReportsFieldsFlags = flags;
    }

    public void openRanging(
            AttributionSource attributionSource,
            SessionHandle sessionHandle,
            IUwbRangingCallbacks rangingCallbacks,
            PersistableBundle params,
            String chipId) throws RemoteException {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }
        int sessionId = 0;
        int sessionType = 0;

        if (UuidBundleWrapper.isUuidBundle(params)) {
            UuidBundleWrapper uuidBundleWrapper = UuidBundleWrapper.fromBundle(params);
            mUwbInjector.getProfileManager().activateProfile(
                    attributionSource,
                    sessionHandle,
                    uuidBundleWrapper.getServiceInstanceID().get(),
                    rangingCallbacks,
                    chipId
            );
        } else if (FiraParams.isCorrectProtocol(params)) {
            FiraOpenSessionParams.Builder builder =
                    new FiraOpenSessionParams.Builder(FiraOpenSessionParams.fromBundle(params));
            if (getCachedSpecificationParams(chipId)
                    .getFiraSpecificationParams().hasRssiReportingSupport()) {
                builder.setIsRssiReportingEnabled(true);
            }
            if (this.mIsDiagnosticsEnabled && getCachedSpecificationParams(chipId)
                    .getFiraSpecificationParams().hasDiagnosticsSupport()) {
                builder.setIsDiagnosticsEnabled(true);
                builder.setDiagramsFrameReportsFieldsFlags(mDiagramsFrameReportsFieldsFlags);
            }
            FiraOpenSessionParams firaOpenSessionParams = builder.build();
            sessionId = firaOpenSessionParams.getSessionId();
            sessionType = firaOpenSessionParams.getSessionType();
            mSessionManager.initSession(attributionSource, sessionHandle, sessionId,
                    (byte) sessionType, firaOpenSessionParams.getProtocolName(),
                    firaOpenSessionParams, rangingCallbacks, chipId);
        } else if (CccParams.isCorrectProtocol(params)) {
            CccOpenRangingParams cccOpenRangingParams = CccOpenRangingParams.fromBundle(params);
            sessionId = cccOpenRangingParams.getSessionId();
            sessionType = cccOpenRangingParams.getSessionType();
            mSessionManager.initSession(attributionSource, sessionHandle, sessionId,
                    (byte) sessionType, cccOpenRangingParams.getProtocolName(),
                    cccOpenRangingParams, rangingCallbacks, chipId);
        } else {
            Log.e(TAG, "openRanging - Wrong parameters");
            try {
                rangingCallbacks.onRangingOpenFailed(sessionHandle,
                        RangingChangeReason.BAD_PARAMETERS, new PersistableBundle());
            } catch (RemoteException e) { }
        }
    }

    public void startRanging(SessionHandle sessionHandle, PersistableBundle params)
            throws IllegalStateException {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }
        Params  startRangingParams = null;
        if (CccParams.isCorrectProtocol(params)) {
            startRangingParams = CccStartRangingParams.fromBundle(params);
        }

        if (mUwbInjector.getProfileManager().hasSession(sessionHandle)) {
            mUwbInjector.getProfileManager().startRanging(sessionHandle);
        } else {
            mSessionManager.startRanging(sessionHandle, startRangingParams);
        }
    }

    public void reconfigureRanging(SessionHandle sessionHandle, PersistableBundle params) {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }
        Params  reconfigureRangingParams = null;
        if (FiraParams.isCorrectProtocol(params)) {
            reconfigureRangingParams = FiraRangingReconfigureParams.fromBundle(params);
        } else if (CccParams.isCorrectProtocol(params)) {
            reconfigureRangingParams = CccRangingReconfiguredParams.fromBundle(params);
        }
        mSessionManager.reconfigure(sessionHandle, reconfigureRangingParams);
    }

    public void stopRanging(SessionHandle sessionHandle) {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }
        if (mUwbInjector.getProfileManager().hasSession(sessionHandle)) {
            mUwbInjector.getProfileManager().stopRanging(sessionHandle);
        } else {
            mSessionManager.stopRanging(sessionHandle);
        }
    }

    public void closeRanging(SessionHandle sessionHandle) {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }
        if (mUwbInjector.getProfileManager().hasSession(sessionHandle)) {
            mUwbInjector.getProfileManager().closeRanging(sessionHandle);
        } else {
            mSessionManager.deInitSession(sessionHandle);
        }
    }

    public void addControlee(SessionHandle sessionHandle, PersistableBundle params) {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }
        Params  reconfigureRangingParams = null;
        if (FiraParams.isCorrectProtocol(params)) {
            FiraControleeParams controleeParams = FiraControleeParams.fromBundle(params);
            reconfigureRangingParams = new FiraRangingReconfigureParams.Builder()
                    .setAction(controleeParams.getAction())
                    .setAddressList(controleeParams.getAddressList())
                    .setSubSessionIdList(controleeParams.getSubSessionIdList())
                    .setSubSessionKeyList(controleeParams.getSubSessionKeyList())
                    .build();
        }
        mSessionManager.reconfigure(sessionHandle, reconfigureRangingParams);
    }

    public void removeControlee(SessionHandle sessionHandle, PersistableBundle params) {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }
        Params  reconfigureRangingParams = null;
        if (FiraParams.isCorrectProtocol(params)) {
            FiraControleeParams controleeParams = FiraControleeParams.fromBundle(params);
            reconfigureRangingParams = new FiraRangingReconfigureParams.Builder()
                    .setAction(controleeParams.getAction())
                    .setAddressList(controleeParams.getAddressList())
                    .setSubSessionIdList(controleeParams.getSubSessionIdList())
                    .setSubSessionKeyList(controleeParams.getSubSessionKeyList())
                    .build();
        }
        mSessionManager.reconfigure(sessionHandle, reconfigureRangingParams);
    }

    /** Send the payload data to a remote device in the UWB session */
    public void sendData(SessionHandle sessionHandle, UwbAddress remoteDeviceAddress,
            PersistableBundle params, byte[] data) throws RemoteException {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }

        mSessionManager.sendData(sessionHandle, remoteDeviceAddress, params, data);
    }

    /**
     * Get the UWB Adapter State.
     */
    public /* @UwbManager.AdapterStateCallback.State */ int getAdapterState() {
        return computeAdapterState(mUwbCountryCode.getCountryCode(), Optional.empty());
    }

    private int computeAdapterState(String countryCode, Optional<Integer> setCountryCodeStatus) {
        // When either the country code is not valid or setting it in UWBS failed with an error,
        // notify the UWB stack state as DISABLED (even though internally the UWB device state
        // may be stored as READY), so that applications wait for starting a ranging session.
        if (!mUwbCountryCode.isValid(countryCode)
                || (setCountryCodeStatus.isPresent()
                && setCountryCodeStatus.get() != STATUS_CODE_OK)) {
            return AdapterStateCallback.STATE_DISABLED;
        }
        return getInternalAdapterState();
    }

    private /* @UwbManager.AdapterStateCallback.State */ int getInternalAdapterState() {
        synchronized (UwbServiceCore.this) {
            if (mChipIdToStateMap.isEmpty()) {
                return AdapterStateCallback.STATE_DISABLED;
            }

            boolean isActive = false;
            for (int state : mChipIdToStateMap.values()) {
                if (state == AdapterStateCallback.STATE_DISABLED) {
                    return AdapterStateCallback.STATE_DISABLED;
                }
                if (state == AdapterStateCallback.STATE_ENABLED_ACTIVE) {
                    isActive = true;
                }
            }
            return isActive ? AdapterStateCallback.STATE_ENABLED_ACTIVE
                    : AdapterStateCallback.STATE_ENABLED_INACTIVE;
        }
    }

    public synchronized void setEnabled(boolean enabled) {
        int task = enabled ? TASK_ENABLE : TASK_DISABLE;

        if (enabled && isUwbEnabled()) {
            Log.w(TAG, "Uwb is already enabled");
        } else if (!enabled && !isUwbEnabled()) {
            Log.w(TAG, "Uwb is already disabled");
        }

        mUwbTask.execute(task);
    }

    private void sendVendorUciResponse(int gid, int oid, byte[] payload) {
        Log.i(TAG, "onVendorUciResponseReceived");
        if (mCallBack != null) {
            try {
                mCallBack.onVendorResponseReceived(gid, oid, payload);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send vendor response", e);
            }
        }
    }

    /**
     * Send vendor UCI message
     *
     * @param chipId : Identifier of UWB chip for multi-HAL devices
     */
    public synchronized int sendVendorUciMessage(int mt, int gid, int oid, byte[] payload,
            String chipId) {
        if ((!isUwbEnabled())) {
            Log.e(TAG, "sendRawVendor : Uwb is not enabled");
            return UwbUciConstants.STATUS_CODE_FAILED;
        }
        // Testing message type is only allowed in version FiRa 2.0 and above.
        if (mt != MESSAGE_TYPE_COMMAND && getCachedSpecificationParams(chipId)
                .getFiraSpecificationParams()
                .getMaxMacVersionSupported()
                .getMajor() < FIRA_VERSION_MAJOR_2) {
            Log.e(TAG, "Message Type  " + mt + " not supported in this FiRa version");
            return  UwbUciConstants.STATUS_CODE_FAILED;
        }
        // TODO(b/211445008): Consolidate to a single uwb thread.
        FutureTask<Integer> sendVendorCmdTask = new FutureTask<>(
                () -> {
                    UwbVendorUciResponse response =
                            mNativeUwbManager.sendRawVendorCmd(mt, gid, oid, payload, chipId);
                    if (response.status == UwbUciConstants.STATUS_CODE_OK) {
                        sendVendorUciResponse(response.gid, response.oid, response.payload);
                    }
                    return Integer.valueOf(response.status);
                });
        int status = UwbUciConstants.STATUS_CODE_FAILED;
        try {
            status = mUwbInjector.runTaskOnSingleThreadExecutor(sendVendorCmdTask,
                    SEND_VENDOR_CMD_TIMEOUT_MS);
        } catch (TimeoutException e) {
            Log.i(TAG, "Failed to send vendor command - status : TIMEOUT");
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        return status;
    }

    public void rangingRoundsUpdateDtTag(SessionHandle sessionHandle,
            PersistableBundle params) throws RemoteException {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }
        mSessionManager.rangingRoundsUpdateDtTag(sessionHandle, params);
    }

    /**
     * Query max application data size that can be sent by UWBS in one ranging round.
     */
    public int queryMaxDataSizeBytes(SessionHandle sessionHandle) {
        if (!isUwbEnabled()) {
            throw new IllegalStateException("Uwb is not enabled");
        }

        return mSessionManager.queryMaxDataSizeBytes(sessionHandle);
    }

    /**
     * Update the pose used by the filter engine to distinguish tag position changes from device
     * position changes.
     */
    public void updatePose(SessionHandle sessionHandle, PersistableBundle params) {
        mSessionManager.updatePose(sessionHandle, params);
    }

    private class UwbTask extends Handler {

        UwbTask(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            int type = msg.what;
            switch (type) {
                case TASK_ENABLE:
                    handleEnable();
                    break;

                case TASK_DISABLE:
                    mSessionManager.deinitAllSession();
                    handleDisable();
                    break;

                case TASK_RESTART:
                    mSessionManager.deinitAllSession();
                    handleDisable();
                    handleEnable();
                    break;

                case TASK_GET_POWER_STATS:
                    invokeUwbActivityEnergyInfoListener((IOnUwbActivityEnergyInfoListener) msg.obj);
                    break;

                default:
                    Log.d(TAG, "UwbTask : Undefined Task");
                    break;
            }
        }

        public void execute(int task) {
            Message msg = mUwbTask.obtainMessage();
            msg.what = task;
            this.sendMessage(msg);
        }
        public void execute(int task, int arg1, int arg2) {
            Message msg = mUwbTask.obtainMessage();
            msg.what = task;
            msg.arg1 = arg1;
            msg.arg2 = arg2;
            this.sendMessage(msg);
        }

        public void execute(int task, Object obj) {
            Message msg = mUwbTask.obtainMessage();
            msg.what = task;
            msg.obj = obj;
            this.sendMessage(msg);
        }

        private void executeUnique(int task, int arg1, int arg2) {
            mUwbTask.removeMessages(task);
            Message msg = mUwbTask.obtainMessage();
            msg.what = task;
            msg.arg1 = arg1;
            msg.arg2 = arg2;
            this.sendMessage(msg);
        }

        private void delayedExecute(int task, int arg1, int arg2, int delayMillis) {
            Message msg = mUwbTask.obtainMessage();
            msg.what = task;
            msg.arg1 = arg1;
            msg.arg2 = arg2;
            this.sendMessageDelayed(msg, delayMillis);
        }

        private void handleEnable() {
            if (isUwbEnabled()) {
                Log.i(TAG, "UWB service is already enabled");
                return;
            }
            try {
                WatchDogThread watchDog = new WatchDogThread("handleEnable", WATCHDOG_MS);
                watchDog.start();

                Log.i(TAG, "Initialization start ...");
                synchronized (mUwbWakeLock) {
                    mUwbWakeLock.acquire();
                }

                try {
                    if (!mNativeUwbManager.doInitialize()) {
                        Log.e(TAG, "Error enabling UWB");
                        mUwbMetrics.incrementDeviceInitFailureCount();
                        takBugReportAfterDeviceError("UWB Bugreport: error enabling UWB");
                        for (String chipId : mUwbInjector.getMultichipData().getChipIds()) {
                            updateState(AdapterStateCallback.STATE_DISABLED, chipId);
                        }
                        for (InitializationFailureListener listener : mListeners) {
                            listener.onFailure();
                        }
                    } else {
                        Log.i(TAG, "Initialization success");
                        /* TODO : keep it until MW, FW fix b/196943897 */
                        mUwbMetrics.incrementDeviceInitSuccessCount();

                        for (String chipId : mUwbInjector.getMultichipData().getChipIds()) {
                            Log.d(TAG, "enabling chip " + chipId);
                            updateDeviceState(UwbUciConstants.DEVICE_STATE_READY, chipId);
                        }

                        // Set country code on every enable (example: for the scenario when the
                        // country code was determined/changed while the UWB stack was disabled).
                        //
                        // TODO(b/255977441): Handle the case when the countryCode is valid and
                        // setting the country code returned an error by doing a UWBS reset.
                        Pair<Integer, String> setCountryCodeResult =
                                mUwbCountryCode.setCountryCode(true);
                        Optional<Integer> setCountryCodeStatus =
                                Optional.of(setCountryCodeResult.first);
                        String countryCode = setCountryCodeResult.second;
                        Log.i(TAG, "Current country code = " + countryCode);
                        computeAndNotifyAdapterStateChange(
                                getReasonFromDeviceState(UwbUciConstants.DEVICE_STATE_READY),
                                countryCode,
                                setCountryCodeStatus);
                    }
                } finally {
                    synchronized (mUwbWakeLock) {
                        if (mUwbWakeLock.isHeld()) {
                            mUwbWakeLock.release();
                        }
                    }
                    watchDog.cancel();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void handleDisable() {
            if (!isUwbEnabled()) {
                Log.i(TAG, "UWB service is already disabled");
                return;
            }

            WatchDogThread watchDog = new WatchDogThread("handleDisable", WATCHDOG_MS);
            watchDog.start();

            try {
                Log.i(TAG, "Deinitialization start ...");
                synchronized (mUwbWakeLock) {
                    mUwbWakeLock.acquire();
                }

                if (!mNativeUwbManager.doDeinitialize()) {
                    Log.w(TAG, "Error disabling UWB");
                } else {
                    Log.i(TAG, "Deinitialization success");
                }
                /* UWBS_STATUS_OFF is not the valid state. so handle device state directly */
                for (String chipId : mUwbInjector.getMultichipData().getChipIds()) {
                    updateDeviceState(UwbUciConstants.DEVICE_STATE_OFF, chipId);
                }
                notifyAdapterState(
                        getAdapterStateFromDeviceState(UwbUciConstants.DEVICE_STATE_OFF),
                        getReasonFromDeviceState(UwbUciConstants.DEVICE_STATE_OFF));
            } finally {
                synchronized (mUwbWakeLock) {
                    if (mUwbWakeLock.isHeld()) {
                        mUwbWakeLock.release();
                    }
                }
                watchDog.cancel();
            }
        }

        private void computeAndNotifyAdapterStateChange(int reason,
                String countryCode, Optional<Integer> setCountryCodeStatus) {
            // When either the country code is not valid or setting it in UWBS failed with the error
            // STATUS_CODE_ANDROID_REGULATION_UWB_OFF, notify with the reason SYSTEM_REGULATION.
            if (!mUwbCountryCode.isValid(countryCode)
                    || (setCountryCodeStatus.isPresent()
                        && setCountryCodeStatus.get()
                        == UwbUciConstants.STATUS_CODE_ANDROID_REGULATION_UWB_OFF)) {
                reason = StateChangeReason.SYSTEM_REGULATION;
            }

            notifyAdapterState(computeAdapterState(countryCode, setCountryCodeStatus), reason);
        }

        public class WatchDogThread extends Thread {
            final Object mCancelWaiter = new Object();
            final int mTimeout;
            boolean mCanceled = false;

            WatchDogThread(String threadName, int timeout) {
                super(threadName);

                mTimeout = timeout;
            }

            @Override
            public void run() {
                try {
                    synchronized (mCancelWaiter) {
                        mCancelWaiter.wait(mTimeout);
                        if (mCanceled) {
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    interrupt();
                }

                synchronized (mUwbWakeLock) {
                    if (mUwbWakeLock.isHeld()) {
                        mUwbWakeLock.release();
                    }
                }
            }

            public synchronized void cancel() {
                synchronized (mCancelWaiter) {
                    mCanceled = true;
                    mCancelWaiter.notify();
                }
            }
        }
    }

    private void takBugReportAfterDeviceError(String bugTitle) {
        if (mUwbInjector.getDeviceConfigFacade().isDeviceErrorBugreportEnabled()) {
            mUwbInjector.getUwbDiagnostics().takeBugReport(bugTitle);
        }
    }

    /**
     * Dump the UWB session manager debug info
     */
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("---- Dump of UwbServiceCore ----");
        for (String chipId : mUwbInjector.getMultichipData().getChipIds()) {
            pw.println("Device state = " + getDeviceStateString(mChipIdToStateMap.get(chipId))
                    + " for chip id = " + chipId);
        }
        pw.println("mLastAdapterStateChangedReason = " + mLastAdapterStateChangedReason);
        pw.println("mLastAdapterStateNotification = " + mLastAdapterStateNotification);
        pw.println("---- Dump of UwbServiceCore ----");
    }

    /**
     * Report the UWB power stats to the listener
     */
    public synchronized void reportUwbActivityEnergyInfo(
            IOnUwbActivityEnergyInfoListener listener) {
        mUwbTask.execute(TASK_GET_POWER_STATS, listener);
    }

    private void invokeUwbActivityEnergyInfoListener(IOnUwbActivityEnergyInfoListener listener) {
        try {
            listener.onUwbActivityEnergyInfo(getUwbActivityEnergyInfo());
        } catch (RemoteException e) {
            Log.e(TAG, "onUwbActivityEnergyInfo: RemoteException -- ", e);
        }
    }

    private UwbActivityEnergyInfo getUwbActivityEnergyInfo() {
        try {
            String chipId = mUwbInjector.getMultichipData().getDefaultChipId();
            PersistableBundle bundle = getSpecificationInfo(chipId);
            GenericSpecificationParams params = GenericSpecificationParams.fromBundle(bundle);
            if (!isUwbEnabled() || params == null || !params.hasPowerStatsSupport()) {
                return null;
            }
            UwbPowerStats stats = mNativeUwbManager.getPowerStats(chipId);
            if (stats == null) {
                return null;
            }

            Log.d(TAG, " getUwbActivityEnergyInfo: "
                    + " tx_time_millis=" + stats.getTxTimeMs()
                    + " rx_time_millis=" + stats.getRxTimeMs()
                    + " rxIdleTimeMillis=" + stats.getIdleTimeMs()
                    + " wake_count=" + stats.getTotalWakeCount());

            return new UwbActivityEnergyInfo.Builder()
                    .setTimeSinceBootMillis(SystemClock.elapsedRealtime())
                    .setStackState(getInternalAdapterState())
                    .setControllerTxDurationMillis(stats.getTxTimeMs())
                    .setControllerRxDurationMillis(stats.getRxTimeMs())
                    .setControllerIdleDurationMillis(stats.getIdleTimeMs())
                    .setControllerWakeCount(stats.getTotalWakeCount())
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
