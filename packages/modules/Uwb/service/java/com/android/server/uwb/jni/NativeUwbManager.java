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
package com.android.server.uwb.jni;

import android.annotation.NonNull;
import android.util.Log;

import com.android.internal.annotations.Keep;
import com.android.server.uwb.UciLogModeStore;
import com.android.server.uwb.UwbInjector;
import com.android.server.uwb.data.DtTagUpdateRangingRoundsStatus;
import com.android.server.uwb.data.UwbConfigStatusData;
import com.android.server.uwb.data.UwbMulticastListUpdateStatus;
import com.android.server.uwb.data.UwbRangingData;
import com.android.server.uwb.data.UwbTlvData;
import com.android.server.uwb.data.UwbUciConstants;
import com.android.server.uwb.data.UwbVendorUciResponse;
import com.android.server.uwb.info.UwbPowerStats;
import com.android.server.uwb.multchip.UwbMultichipData;

import java.util.Arrays;

@Keep
public class NativeUwbManager {
    private static final String TAG = NativeUwbManager.class.getSimpleName();

    public final Object mNativeLock = new Object();
    private final UwbInjector mUwbInjector;
    private final UciLogModeStore mUciLogModeStore;
    private final UwbMultichipData mUwbMultichipData;
    protected INativeUwbManager.DeviceNotification mDeviceListener;
    protected INativeUwbManager.SessionNotification mSessionListener;
    private long mDispatcherPointer;
    protected INativeUwbManager.VendorNotification mVendorListener;

    public NativeUwbManager(@NonNull UwbInjector uwbInjector, UciLogModeStore uciLogModeStore,
            UwbMultichipData uwbMultichipData) {
        mUwbInjector = uwbInjector;
        mUciLogModeStore = uciLogModeStore;
        mUwbMultichipData = uwbMultichipData;
        loadLibrary();
    }

    protected void loadLibrary() {
        System.loadLibrary("uwb_uci_jni_rust");
        synchronized (mNativeLock) {
            nativeInit();
        }
    }

    public void setDeviceListener(INativeUwbManager.DeviceNotification deviceListener) {
        mDeviceListener = deviceListener;
    }

    public void setSessionListener(INativeUwbManager.SessionNotification sessionListener) {
        mSessionListener = sessionListener;
    }

    public void setVendorListener(INativeUwbManager.VendorNotification vendorListener) {
        mVendorListener = vendorListener;
    }

    /**
     * Device status callback invoked via the JNI
     */
    public void onDeviceStatusNotificationReceived(int deviceState, String chipId) {
        Log.d(TAG, "onDeviceStatusNotificationReceived(" + deviceState + ", " + chipId + ")");
        mDeviceListener.onDeviceStatusNotificationReceived(deviceState, chipId);
    }

    /**
     * Error callback invoked via the JNI
     */
    public void onCoreGenericErrorNotificationReceived(int status, String chipId) {
        Log.d(TAG, "onCoreGenericErrorNotificationReceived(" + status + ", " + chipId + ")");
        mDeviceListener.onCoreGenericErrorNotificationReceived(status, chipId);
    }

    public void onSessionStatusNotificationReceived(long id, int state, int reasonCode) {
        Log.d(TAG, "onSessionStatusNotificationReceived(" + id + ", " + state + ", " + reasonCode
                + ")");
        mSessionListener.onSessionStatusNotificationReceived(id, state, reasonCode);
    }

    public void onRangeDataNotificationReceived(UwbRangingData rangeData) {
        Log.d(TAG, "onRangeDataNotificationReceived : " + rangeData);
        mSessionListener.onRangeDataNotificationReceived(rangeData);
    }

    public void onMulticastListUpdateNotificationReceived(
            UwbMulticastListUpdateStatus multicastListUpdateData) {
        Log.d(TAG, "onMulticastListUpdateNotificationReceived : " + multicastListUpdateData);
        mSessionListener.onMulticastListUpdateNotificationReceived(multicastListUpdateData);
    }

    /**
     * Vendor callback invoked via the JNI
     */
    public void onVendorUciNotificationReceived(int gid, int oid, byte[] payload) {
        Log.d(TAG, "onVendorUciNotificationReceived: " + gid + ", " + oid + ", "
                + Arrays.toString(payload));
        mVendorListener.onVendorUciNotificationReceived(gid, oid, payload);
    }

    /**
     * Enable UWB hardware.
     *
     * @return : If this returns true, UWB is on
     */
    public boolean doInitialize() {
        synchronized (mNativeLock) {
            mDispatcherPointer = nativeDispatcherNew(mUwbMultichipData.getChipIds().toArray());
            for (String chipId : mUwbMultichipData.getChipIds()) {
                if (!nativeDoInitialize(chipId)) {
                    return false;
                }
            }
            nativeSetLogMode(mUciLogModeStore.getMode());
        }
        return true;
    }

    /**
     * Disable UWB hardware.
     *
     * @return : If this returns true, UWB is off
     */
    public boolean doDeinitialize() {
        synchronized (mNativeLock) {
            for (String chipId : mUwbMultichipData.getChipIds()) {
                nativeDoDeinitialize(chipId);
            }

            nativeDispatcherDestroy();
            mDispatcherPointer = 0L;
        }
        return true;
    }

    /**
     * Gets the timestamp resolution in nanosecond
     *
     * @return : timestamp resolution in nanosecond
     */
    public long getTimestampResolutionNanos() {
        return 0L;
        /* TODO: Not Implemented in native stack
        return nativeGetTimestampResolutionNanos(); */
    }

    /**
     * Retrieves power related stats
     */
    public UwbPowerStats getPowerStats(String chipId) {
        synchronized (mNativeLock) {
            return nativeGetPowerStats(chipId);
        }
    }

    /**
     * Creates the new UWB session with parameter session ID and type of the session.
     *
     * @param sessionId   : Session ID is 4 Octets unique random number generated by application
     * @param sessionType : Type of session 0x00: Ranging session 0x01: Data transfer 0x02-0x9F: RFU
     *                    0xA0-0xCF: Reserved for Vendor Specific use case 0xD0: Device Test Mode
     *                    0xD1-0xDF: RFU 0xE0-0xFF: Vendor Specific use
     * @param chipId      : Identifier of UWB chip for multi-HAL devices
     * @return : {@link UwbUciConstants}  Status code
     */
    public byte initSession(int sessionId, byte sessionType, String chipId) {
        synchronized (mNativeLock) {
            return nativeSessionInit(sessionId, sessionType, chipId);
        }
    }

    /**
     * De-initializes the session.
     *
     * @param sessionId : Session ID for which session to be de-initialized
     * @param chipId    : Identifier of UWB chip for multi-HAL devices
     * @return : {@link UwbUciConstants}  Status code
     */
    public byte deInitSession(int sessionId, String chipId) {
        synchronized (mNativeLock) {
            return nativeSessionDeInit(sessionId, chipId);
        }
    }

    /**
     * reset the UWBs
     *
     * @param resetConfig : Reset config
     * @param chipId      : Identifier of UWB chip for multi-HAL devices
     * @return : {@link UwbUciConstants}  Status code
     */
    public byte deviceReset(byte resetConfig, String chipId) {
        synchronized (mNativeLock) {
            return nativeDeviceReset(resetConfig, chipId);
        }
    }

    /**
     * Retrieves number of UWB sessions in the UWBS.
     *
     * @param chipId : Identifier of UWB chip for multi-HAL devices
     * @return : Number of UWB sessions present in the UWBS.
     */
    public byte getSessionCount(String chipId) {
        synchronized (mNativeLock) {
            return nativeGetSessionCount(chipId);
        }
    }

    /**
     * Queries the current state of the UWB session.
     *
     * @param sessionId : Session of the UWB session for which current session state to be queried
     * @param chipId    : Identifier of UWB chip for multi-HAL devices
     * @return : {@link UwbUciConstants}  Session State
     */
    public byte getSessionState(int sessionId, String chipId) {
        synchronized (mNativeLock) {
            return nativeGetSessionState(sessionId, chipId);
        }
    }

    /**
     * Starts a UWB session.
     *
     * @param sessionId : Session ID for which ranging shall start
     * @param chipId    : Identifier of UWB chip for multi-HAL devices
     * @return : {@link UwbUciConstants}  Status code
     */
    public byte startRanging(int sessionId, String chipId) {
        synchronized (mNativeLock) {
            return nativeRangingStart(sessionId, chipId);
        }
    }

    /**
     * Stops the ongoing UWB session.
     *
     * @param sessionId : Stop the requested ranging session.
     * @param chipId    : Identifier of UWB chip for multi-HAL devices
     * @return : {@link UwbUciConstants}  Status code
     */
    public byte stopRanging(int sessionId, String chipId) {
        synchronized (mNativeLock) {
            return nativeRangingStop(sessionId, chipId);
        }
    }

    /**
     * set APP Configuration Parameters for the requested UWB session
     *
     * @param noOfParams        : The number (n) of APP Configuration Parameters
     * @param appConfigParamLen : The length of APP Configuration Parameters
     * @param appConfigParams   : APP Configuration Parameter
     * @param chipId            : Identifier of UWB chip for multi-HAL devices
     * @return : {@link UwbConfigStatusData} : Contains statuses for all cfg_id
     */
    public UwbConfigStatusData setAppConfigurations(int sessionId, int noOfParams,
            int appConfigParamLen, byte[] appConfigParams, String chipId) {
        synchronized (mNativeLock) {
            return nativeSetAppConfigurations(sessionId, noOfParams, appConfigParamLen,
                    appConfigParams, chipId);
        }
    }

    /**
     * Get APP Configuration Parameters for the requested UWB session
     *
     * @param noOfParams        : The number (n) of APP Configuration Parameters
     * @param appConfigParamLen : The length of APP Configuration Parameters
     * @param appConfigIds      : APP Configuration Parameter
     * @param chipId            : Identifier of UWB chip for multi-HAL devices
     * @return :  {@link UwbTlvData} : All tlvs that are to be decoded
     */
    public UwbTlvData getAppConfigurations(int sessionId, int noOfParams, int appConfigParamLen,
            byte[] appConfigIds, String chipId) {
        synchronized (mNativeLock) {
            return nativeGetAppConfigurations(sessionId, noOfParams, appConfigParamLen,
                    appConfigIds, chipId);
        }
    }

    /**
     * Get Core Capabilities information
     *
     * @param chipId : Identifier of UWB chip for multi-HAL devices
     * @return :  {@link UwbTlvData} : All tlvs that are to be decoded
     */
    public UwbTlvData getCapsInfo(String chipId) {
        synchronized (mNativeLock) {
            return nativeGetCapsInfo(chipId);
        }
    }

    /**
     * Update Multicast list for the requested UWB session using V1 command.
     *
     * @param sessionId         : Session ID to which multicast list to be updated
     * @param action            : Update the multicast list by adding or removing
     *                          0x00 - Adding
     *                          0x01 - removing
     *                          0x02 - Adding with 16 bits sub-session key
     *                          0x03 - Adding with 32 bits sub-session key
     * @param noOfControlee     : The number(n) of Controlees
     * @param addresses         : address list of Controlees
     * @param subSessionIds     : Specific sub-session ID list of Controlees
     * @param subSessionKeyList : Sub-session key list of Controlees
     * @return : refer to SESSION_SET_APP_CONFIG_RSP
     * in the Table 16: Control messages to set Application configurations
     */
    public byte controllerMulticastListUpdate(int sessionId, int action, int noOfControlee,
            byte[] addresses, int[] subSessionIds, byte[] subSessionKeyList,
            String chipId) {
        synchronized (mNativeLock) {
            return nativeControllerMulticastListUpdate(sessionId, (byte) action,
                    (byte) noOfControlee, addresses, subSessionIds, subSessionKeyList, chipId);
        }
    }

    /**
     * Set country code.
     *
     * @param countryCode 2 char ISO country code
     */
    public byte setCountryCode(byte[] countryCode) {
        Log.i(TAG, "setCountryCode: " + new String(countryCode));

        synchronized (mNativeLock) {
            for (String chipId : mUwbMultichipData.getChipIds()) {
                byte status = nativeSetCountryCode(countryCode, chipId);
                if (status != UwbUciConstants.STATUS_CODE_OK) {
                    return status;
                }
            }
            return UwbUciConstants.STATUS_CODE_OK;
        }
    }

    /**
     * Sets the log mode for the current and future UWB UCI messages.
     *
     * @param logModeStr is one of Disabled, Filtered, or Unfiltered (case insensitive).
     * @return true if the log mode is set successfully, false otherwise.
     */
    public boolean setLogMode(String logModeStr) {
        synchronized (mNativeLock) {
            return nativeSetLogMode(mUciLogModeStore.getMode());
        }
    }

    @NonNull
    public UwbVendorUciResponse sendRawVendorCmd(int mt, int gid, int oid, byte[] payload,
            String chipId) {
        synchronized (mNativeLock) {
            return nativeSendRawVendorCmd(mt, gid, oid, payload, chipId);
        }
    }

    /**
     * Receive payload data from a remote device in a UWB ranging session.
     */
    public void onDataReceived(
            long sessionID, int status, long sequenceNum, byte[] address,
            int sourceEndPoint, int destEndPoint, byte[] data) {
        Log.d(TAG, "onDataReceived ");
        mSessionListener.onDataReceived(
                sessionID, status, sequenceNum, address, sourceEndPoint, destEndPoint, data);
    }

    /**
     * Send payload data to a remote device in a UWB ranging session.
     */
    public byte sendData(
            int sessionId, byte[] address, byte destEndPoint, byte sequenceNum, byte[] appData,
            String chipId) {
        synchronized (mNativeLock) {
            return nativeSendData(sessionId, address, destEndPoint, sequenceNum, appData, chipId);
        }
    }

    /**
     * Receive the data transfer status for a UCI data packet earlier sent from Host to UWBS.
     */
    public void onDataSendStatus(long sessionId, int dataTransferStatus, long sequenceNum) {
        Log.d(TAG, "onDataSendStatus ");
        mSessionListener.onDataSendStatus(sessionId, dataTransferStatus, sequenceNum);
    }

    /**
     * Update Ranging Rounds for DT Tag
     *
     * @param sessionId Session ID to which ranging round to be updated
     * @param noOfRangingRounds new active ranging round
     * @param rangingRoundIndexes Indexes of ranging rounds
     * @return refer to SESSION_SET_APP_CONFIG_RSP
     * in the Table 16: Control messages to set Application configurations
     */
    public DtTagUpdateRangingRoundsStatus sessionUpdateDtTagRangingRounds(int sessionId,
            int noOfRangingRounds, byte[] rangingRoundIndexes, String chipId) {
        synchronized (mNativeLock) {
            return nativeSessionUpdateDtTagRangingRounds(sessionId, noOfRangingRounds,
                    rangingRoundIndexes, chipId);
        }
    }

    /**
     * Queries the max Application data size for the UWB session.
     *
     * @param sessionId : Session of the UWB session for which current max data size to be queried
     * @param chipId    : Identifier of UWB chip for multi-HAL devices
     * @return : Max application data size that can be sent by UWBS.
     */
    public int queryMaxDataSizeBytes(int sessionId, String chipId) {
        synchronized (mNativeLock) {
            return nativeQueryDataSize(sessionId, chipId);
        }
    }

    /**
     * Get session token from session id.
     *
     * @param sessionId : session id of uwb session
     * @param chipId : Identifier of UWB chip for multi-HAL devices
     * @return : session token generated for the session.
     */
    public int getSessionToken(int sessionId, String chipId) {
        synchronized (mNativeLock) {
            return nativeGetSessionToken(sessionId, chipId);
        }
    }

    private native byte nativeSendData(int sessionId, byte[] address, byte destEndPoint,
            byte sequenceNum, byte[] appData, String chipId);

    private native long nativeDispatcherNew(Object[] chipIds);

    private native void nativeDispatcherDestroy();

    private native boolean nativeInit();

    private native boolean nativeDoInitialize(String chipIds);

    private native boolean nativeDoDeinitialize(String chipId);

    private native long nativeGetTimestampResolutionNanos();

    private native UwbPowerStats nativeGetPowerStats(String chipId);

    private native byte nativeDeviceReset(byte resetConfig, String chipId);

    private native byte nativeSessionInit(int sessionId, byte sessionType, String chipId);

    private native byte nativeSessionDeInit(int sessionId, String chipId);

    private native byte nativeGetSessionCount(String chipId);

    private native byte nativeRangingStart(int sessionId, String chipId);

    private native byte nativeRangingStop(int sessionId, String chipId);

    private native byte nativeGetSessionState(int sessionId, String chipId);

    private native UwbConfigStatusData nativeSetAppConfigurations(int sessionId, int noOfParams,
            int appConfigParamLen, byte[] appConfigParams, String chipId);

    private native UwbTlvData nativeGetAppConfigurations(int sessionId, int noOfParams,
            int appConfigParamLen, byte[] appConfigParams, String chipId);

    private native UwbTlvData nativeGetCapsInfo(String chipId);

    private native byte nativeControllerMulticastListUpdate(int sessionId, byte action,
            byte noOfControlee, byte[] address, int[] subSessionId, byte[] subSessionKeyList,
            String chipId);

    private native byte nativeSetCountryCode(byte[] countryCode, String chipId);

    private native boolean nativeSetLogMode(String logMode);

    private native UwbVendorUciResponse nativeSendRawVendorCmd(int mt, int gid, int oid,
            byte[] payload, String chipId);

    private native DtTagUpdateRangingRoundsStatus nativeSessionUpdateDtTagRangingRounds(
            int sessionId, int noOfActiveRangingRounds, byte[] rangingRoundIndexes, String chipId);

    private native short nativeQueryDataSize(int sessionId, String chipId);

    private native int nativeGetSessionToken(int sessionId, String chipId);
}
