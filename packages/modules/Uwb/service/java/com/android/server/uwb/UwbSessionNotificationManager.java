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

import android.annotation.NonNull;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.Log;
import android.uwb.AngleMeasurement;
import android.uwb.AngleOfArrivalMeasurement;
import android.uwb.DistanceMeasurement;
import android.uwb.IUwbRangingCallbacks;
import android.uwb.RangingChangeReason;
import android.uwb.RangingMeasurement;
import android.uwb.RangingReport;
import android.uwb.SessionHandle;
import android.uwb.UwbAddress;

import com.android.modules.utils.build.SdkLevel;
import com.android.server.uwb.UwbSessionManager.UwbSession;
import com.android.server.uwb.data.UwbDlTDoAMeasurement;
import com.android.server.uwb.data.UwbOwrAoaMeasurement;
import com.android.server.uwb.data.UwbRangingData;
import com.android.server.uwb.data.UwbTwoWayMeasurement;
import com.android.server.uwb.data.UwbUciConstants;
import com.android.server.uwb.params.TlvUtil;
import com.android.server.uwb.util.UwbUtil;

import com.google.uwb.support.base.Params;
import com.google.uwb.support.ccc.CccParams;
import com.google.uwb.support.ccc.CccRangingReconfiguredParams;
import com.google.uwb.support.dltdoa.DlTDoAMeasurement;
import com.google.uwb.support.fira.FiraOpenSessionParams;
import com.google.uwb.support.fira.FiraParams;
import com.google.uwb.support.oemextension.RangingReportMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class UwbSessionNotificationManager {
    private static final String TAG = "UwbSessionNotiManager";
    private final UwbInjector mUwbInjector;

    public UwbSessionNotificationManager(@NonNull UwbInjector uwbInjector) {
        mUwbInjector = uwbInjector;
    }

    public void onRangingResult(UwbSession uwbSession, UwbRangingData rangingData) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        boolean permissionGranted = mUwbInjector.checkUwbRangingPermissionForDataDelivery(
                uwbSession.getAttributionSource(), "uwb ranging result");
        if (!permissionGranted) {
            Log.e(TAG, "Not delivering ranging result because of permission denial"
                    + sessionHandle);
            return;
        }

        RangingReport rangingReport = null;
        try {
            rangingReport = getRangingReport(rangingData, uwbSession.getProtocolName(),
                    uwbSession.getParams(), mUwbInjector.getElapsedSinceBootNanos(), uwbSession);
        } catch (Exception e) {
            Log.e(TAG, "getRangingReport Failed.");
            e.printStackTrace();
        }

        try {
            RangingMeasurement filteredRangingMeasurement = rangingReport != null
                    ? rangingReport.getMeasurements().get(0) : null;
            mUwbInjector.getUwbMetrics().logRangingResult(uwbSession.getProfileType(), rangingData,
                    filteredRangingMeasurement);
        } catch (Exception e) {
            Log.e(TAG, "logRangingResult Failed.");
            e.printStackTrace();
        }

        if (mUwbInjector.getUwbServiceCore().isOemExtensionCbRegistered()) {
            try {
                rangingReport = mUwbInjector.getUwbServiceCore().getOemExtensionCallback()
                                .onRangingReportReceived(rangingReport);
            } catch (RemoteException e) {
                Log.e(TAG, "UwbInjector - onRangingReportReceived : Failed.");
                e.printStackTrace();
            }
        }
        try {
            uwbRangingCallbacks.onRangingResult(sessionHandle, rangingReport);
            Log.i(TAG, "IUwbRangingCallbacks - onRangingResult");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingResult : Failed");
            e.printStackTrace();
        }
    }

    public void onRangingOpened(UwbSession uwbSession) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onRangingOpened(sessionHandle);
            Log.i(TAG, "IUwbRangingCallbacks - onRangingOpened");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingOpened : Failed");
            e.printStackTrace();
        }
    }

    public void onRangingOpenFailed(UwbSession uwbSession, int status) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();

        try {
            uwbRangingCallbacks.onRangingOpenFailed(sessionHandle,
                    UwbSessionNotificationHelper.convertUciStatusToApiReasonCode(status),
                    UwbSessionNotificationHelper.convertUciStatusToParam(
                            uwbSession.getProtocolName(), status));
            Log.i(TAG, "IUwbRangingCallbacks - onRangingOpenFailed");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingOpenFailed : Failed");
            e.printStackTrace();
        }
    }

    public void onRangingStarted(UwbSession uwbSession, Params rangingStartedParams) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onRangingStarted(sessionHandle, rangingStartedParams.toBundle());
            Log.i(TAG, "IUwbRangingCallbacks - onRangingStarted");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingStarted : Failed");
            e.printStackTrace();
        }
    }


    public void onRangingStartFailed(UwbSession uwbSession, int status) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onRangingStartFailed(sessionHandle,
                    UwbSessionNotificationHelper.convertUciStatusToApiReasonCode(status),
                    UwbSessionNotificationHelper.convertUciStatusToParam(
                            uwbSession.getProtocolName(), status));
            Log.i(TAG, "IUwbRangingCallbacks - onRangingStartFailed");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingStartFailed : Failed");
            e.printStackTrace();
        }
    }

    private void onRangingStoppedInternal(UwbSession uwbSession, int reason,
            PersistableBundle params)  {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onRangingStopped(sessionHandle, reason, params);
            Log.i(TAG, "IUwbRangingCallbacks - onRangingStopped");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingStopped : Failed");
            e.printStackTrace();
        }
    }

    public void onRangingStoppedWithUciReasonCode(UwbSession uwbSession, int reasonCode)  {
        onRangingStoppedInternal(uwbSession,
                UwbSessionNotificationHelper.convertUciReasonCodeToApiReasonCode(reasonCode),
                new PersistableBundle());
    }

    public void onRangingStoppedWithApiReasonCode(
            UwbSession uwbSession, @RangingChangeReason int reasonCode) {
        onRangingStoppedInternal(uwbSession, reasonCode, new PersistableBundle());
    }

    public void onRangingStopped(UwbSession uwbSession, int status)  {
        onRangingStoppedInternal(uwbSession,
                UwbSessionNotificationHelper.convertUciStatusToApiReasonCode(
                        status),
                UwbSessionNotificationHelper.convertUciStatusToParam(
                        uwbSession.getProtocolName(), status));
    }

    public void onRangingStopFailed(UwbSession uwbSession, int status) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onRangingStopFailed(sessionHandle,
                    UwbSessionNotificationHelper.convertUciStatusToApiReasonCode(
                            status),
                    UwbSessionNotificationHelper.convertUciStatusToParam(
                            uwbSession.getProtocolName(), status));
            Log.i(TAG, "IUwbRangingCallbacks - onRangingStopFailed");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingStopFailed : Failed");
            e.printStackTrace();
        }
    }

    public void onRangingReconfigured(UwbSession uwbSession) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        PersistableBundle params;
        if (Objects.equals(uwbSession.getProtocolName(), CccParams.PROTOCOL_NAME)) {
            // Why are there no params defined for this bundle?
            params = new CccRangingReconfiguredParams.Builder().build().toBundle();
        } else {
            // No params defined for FiRa reconfigure.
            params = new PersistableBundle();
        }
        try {
            uwbRangingCallbacks.onRangingReconfigured(sessionHandle, params);
            Log.i(TAG, "IUwbRangingCallbacks - onRangingReconfigured");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingReconfigured : Failed");
            e.printStackTrace();
        }
    }

    public void onRangingReconfigureFailed(UwbSession uwbSession, int status) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onRangingReconfigureFailed(sessionHandle,
                    UwbSessionNotificationHelper.convertUciStatusToApiReasonCode(
                            status),
                    UwbSessionNotificationHelper.convertUciStatusToParam(
                            uwbSession.getProtocolName(), status));
            Log.i(TAG, "IUwbRangingCallbacks - onRangingReconfigureFailed");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingReconfigureFailed : Failed");
            e.printStackTrace();
        }
    }

    public void onControleeAdded(UwbSession uwbSession) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onControleeAdded(sessionHandle, new PersistableBundle());
            Log.i(TAG, "IUwbRangingCallbacks - onControleeAdded");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onControleeAdded: Failed");
            e.printStackTrace();
        }
    }

    public void onControleeAddFailed(UwbSession uwbSession, int status) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onControleeAddFailed(sessionHandle,
                    UwbSessionNotificationHelper.convertUciStatusToApiReasonCode(
                            status),
                    UwbSessionNotificationHelper.convertUciStatusToParam(
                            uwbSession.getProtocolName(), status));
            Log.i(TAG, "IUwbRangingCallbacks - onControleeAddFailed");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onControleeAddFailed : Failed");
            e.printStackTrace();
        }
    }

    public void onControleeRemoved(UwbSession uwbSession) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onControleeRemoved(sessionHandle, new PersistableBundle());
            Log.i(TAG, "IUwbRangingCallbacks - onControleeRemoved");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onControleeRemoved: Failed");
            e.printStackTrace();
        }
    }

    public void onControleeRemoveFailed(UwbSession uwbSession, int status) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onControleeRemoveFailed(sessionHandle,
                    UwbSessionNotificationHelper.convertUciStatusToApiReasonCode(
                            status),
                    UwbSessionNotificationHelper.convertUciStatusToParam(
                            uwbSession.getProtocolName(), status));
            Log.i(TAG, "IUwbRangingCallbacks - onControleeRemoveFailed");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onControleeRemoveFailed : Failed");
            e.printStackTrace();
        }
    }

    public void onRangingClosed(UwbSession uwbSession, int status) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onRangingClosed(sessionHandle,
                    UwbSessionNotificationHelper.convertUciStatusToApiReasonCode(
                            status),
                    UwbSessionNotificationHelper.convertUciStatusToParam(
                            uwbSession.getProtocolName(), status));
            Log.i(TAG, "IUwbRangingCallbacks - onRangingClosed");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingClosed : Failed");
            e.printStackTrace();
        }
    }

    public void onRangingClosedWithApiReasonCode(
            UwbSession uwbSession, @RangingChangeReason int reasonCode) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onRangingClosed(sessionHandle, reasonCode, new PersistableBundle());
            Log.i(TAG, "IUwbRangingCallbacks - onRangingClosed");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingClosed : Failed");
            e.printStackTrace();
        }
    }

    /** Notify about payload data received during the UWB ranging session. */
    public void onDataReceived(
            UwbSession uwbSession, UwbAddress remoteDeviceAddress,
            PersistableBundle parameters, byte[] data) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onDataReceived(
                    sessionHandle, remoteDeviceAddress, parameters, data);
            Log.i(TAG, "IUwbRangingCallbacks - onDataReceived");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onDataReceived : Failed");
            e.printStackTrace();
        }
    }

    /** Notify about failure in receiving payload data during the UWB ranging session. */
    public void onDataReceiveFailed(
            UwbSession uwbSession, UwbAddress remoteDeviceAddress,
            int reason, PersistableBundle parameters) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onDataReceiveFailed(
                    sessionHandle, remoteDeviceAddress, reason, parameters);
            Log.i(TAG, "IUwbRangingCallbacks - onDataReceiveFailed");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onDataReceiveFailed : Failed");
            e.printStackTrace();
        }
    }

    /** Notify about payload data sent during the UWB ranging session. */
    public void onDataSent(
            UwbSession uwbSession, UwbAddress remoteDeviceAddress, PersistableBundle parameters) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onDataSent(
                    sessionHandle, remoteDeviceAddress, parameters);
            Log.i(TAG, "IUwbRangingCallbacks - onDataSent");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onDataSent : Failed");
            e.printStackTrace();
        }
    }

    /** Notify about failure in sending payload data during the UWB ranging session. */
    public void onDataSendFailed(
            UwbSession uwbSession, UwbAddress remoteDeviceAddress,
            int reason, PersistableBundle parameters) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onDataSendFailed(
                    sessionHandle, remoteDeviceAddress, reason, parameters);
            Log.i(TAG, "IUwbRangingCallbacks - onDataSendFailed");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onDataSendFailed : Failed");
            e.printStackTrace();
        }
    }

    /** Notify the response for Ranging rounds update status for Dt Tag. */
    public void onRangingRoundsUpdateStatus(
            UwbSession uwbSession, PersistableBundle parameters) {
        SessionHandle sessionHandle = uwbSession.getSessionHandle();
        IUwbRangingCallbacks uwbRangingCallbacks = uwbSession.getIUwbRangingCallbacks();
        try {
            uwbRangingCallbacks.onRangingRoundsUpdateDtTagStatus(sessionHandle,
                    parameters);
            Log.i(TAG, "IUwbRangingCallbacks - onRangingRoundsUpdateDtTagStatus");
        } catch (Exception e) {
            Log.e(TAG, "IUwbRangingCallbacks - onRangingRoundsUpdateDtTagStatus : Failed");
            e.printStackTrace();
        }
    }

    private static RangingReport getRangingReport(
            @NonNull UwbRangingData rangingData, String protocolName,
            Params sessionParams, long elapsedRealtimeNanos, UwbSession uwbSession) {
        if (rangingData.getRangingMeasuresType() != UwbUciConstants.RANGING_MEASUREMENT_TYPE_TWO_WAY
                && rangingData.getRangingMeasuresType()
                    != UwbUciConstants.RANGING_MEASUREMENT_TYPE_OWR_AOA
                && rangingData.getRangingMeasuresType()
                    != UwbUciConstants.RANGING_MEASUREMENT_TYPE_DL_TDOA) {
            return null;
        }
        boolean isAoaAzimuthEnabled = true;
        boolean isAoaElevationEnabled = true;
        boolean isDestAoaAzimuthEnabled = false;
        boolean isDestAoaElevationEnabled = false;
        long sessionId = 0;

        // For FIRA sessions, check if AOA is enabled for the session or not.
        if (protocolName.equals(FiraParams.PROTOCOL_NAME)) {
            FiraOpenSessionParams openSessionParams = (FiraOpenSessionParams) sessionParams;
            sessionId = openSessionParams.getSessionId();
            switch (openSessionParams.getAoaResultRequest()) {
                case FiraParams.AOA_RESULT_REQUEST_MODE_NO_AOA_REPORT:
                    isAoaAzimuthEnabled = false;
                    isAoaElevationEnabled = false;
                    break;
                case FiraParams.AOA_RESULT_REQUEST_MODE_REQ_AOA_RESULTS:
                case FiraParams.AOA_RESULT_REQUEST_MODE_REQ_AOA_RESULTS_INTERLEAVED:
                    isAoaAzimuthEnabled = true;
                    isAoaElevationEnabled = true;
                    break;
                case FiraParams.AOA_RESULT_REQUEST_MODE_REQ_AOA_RESULTS_AZIMUTH_ONLY:
                    isAoaAzimuthEnabled = true;
                    isAoaElevationEnabled = false;
                    break;
                case FiraParams.AOA_RESULT_REQUEST_MODE_REQ_AOA_RESULTS_ELEVATION_ONLY:
                    isAoaAzimuthEnabled = false;
                    isAoaElevationEnabled = true;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid AOA result req");
            }
            if (openSessionParams.hasRangingResultReportMessage()) {
                if (openSessionParams.hasAngleOfArrivalAzimuthReport()) {
                    isDestAoaAzimuthEnabled = true;
                }
                if (openSessionParams.hasAngleOfArrivalElevationReport()) {
                    isDestAoaElevationEnabled = true;
                }
            }
        }

        // TODO(b/256734264): The unit tests are currently not checking for this field, as
        //  RangingReport.equals() does not compare it.
        PersistableBundle rangingReportMetadata = new RangingReportMetadata.Builder()
                .setSessionId(sessionId)
                .setRawNtfData(rangingData.getRawNtfData())
                .build()
                .toBundle();
        RangingReport.Builder rangingReportBuilder = new RangingReport.Builder()
                .addRangingReportMetadata(rangingReportMetadata);

        if (rangingData.getRangingMeasuresType()
                == UwbUciConstants.RANGING_MEASUREMENT_TYPE_TWO_WAY) {
            List<RangingMeasurement> rangingMeasurements = new ArrayList<>();
            UwbTwoWayMeasurement[] uwbTwoWayMeasurement = rangingData.getRangingTwoWayMeasures();
            for (int i = 0; i < rangingData.getNoOfRangingMeasures(); ++i) {
                int rangingStatus = uwbTwoWayMeasurement[i].convertStatusCode();

                RangingMeasurement.Builder rangingMeasurementBuilder = buildRangingMeasurement(
                        uwbTwoWayMeasurement[i].getMacAddress(), rangingStatus,
                        elapsedRealtimeNanos, uwbTwoWayMeasurement[i].getNLoS());
                int rssi = uwbTwoWayMeasurement[i].getRssi();
                if (rssi < 0) {
                    rangingMeasurementBuilder.setRssiDbm(rssi);
                }

                if (uwbTwoWayMeasurement[i].isStatusCodeOk()) {
                    // Distance measurement is mandatory
                    rangingMeasurementBuilder.setDistanceMeasurement(
                            buildDistanceMeasurement(uwbTwoWayMeasurement[i].getDistance()));

                    // Aoa measurement is optional based on configuration.
                    AngleOfArrivalMeasurement angleOfArrivalMeasurement =
                            computeAngleOfArrivalMeasurement(
                                    isAoaAzimuthEnabled, isAoaElevationEnabled,
                                    uwbTwoWayMeasurement[i].getAoaAzimuth(),
                                    uwbTwoWayMeasurement[i].getAoaAzimuthFom(),
                                    uwbTwoWayMeasurement[i].getAoaElevation(),
                                    uwbTwoWayMeasurement[i].getAoaElevationFom());
                    if (angleOfArrivalMeasurement != null) {
                        rangingMeasurementBuilder.setAngleOfArrivalMeasurement(
                                angleOfArrivalMeasurement);
                    }

                    // Dest AngleOfArrivalMeasurement
                    AngleOfArrivalMeasurement destinationAngleOfArrivalMeasurement =
                            computeAngleOfArrivalMeasurement(
                                    isDestAoaAzimuthEnabled, isDestAoaElevationEnabled,
                                    uwbTwoWayMeasurement[i].getAoaDestAzimuth(),
                                    uwbTwoWayMeasurement[i].getAoaDestAzimuthFom(),
                                    uwbTwoWayMeasurement[i].getAoaDestElevation(),
                                    uwbTwoWayMeasurement[i].getAoaDestElevationFom());
                    if (destinationAngleOfArrivalMeasurement != null) {
                        rangingMeasurementBuilder.setDestinationAngleOfArrivalMeasurement(
                                destinationAngleOfArrivalMeasurement);
                    }
                }

                // TODO: No ranging measurement metadata defined, added for future usage
                PersistableBundle rangingMeasurementMetadata = new PersistableBundle();
                rangingMeasurementBuilder.setRangingMeasurementMetadata(rangingMeasurementMetadata);

                UwbAddress addr = getComputedMacAddress(uwbTwoWayMeasurement[i].getMacAddress());
                UwbControlee controlee = uwbSession.getControlee(addr);
                if (controlee != null) {
                    controlee.filterMeasurement(rangingMeasurementBuilder);
                }

                rangingMeasurements.add(rangingMeasurementBuilder.build());
            }

            rangingReportBuilder.addMeasurements(rangingMeasurements);
        } else if (rangingData.getRangingMeasuresType()
                == UwbUciConstants.RANGING_MEASUREMENT_TYPE_OWR_AOA) {
            UwbOwrAoaMeasurement uwbOwrAoaMeasurement = rangingData.getRangingOwrAoaMeasure();

            int rangingStatus = uwbOwrAoaMeasurement.getRangingStatus();
            RangingMeasurement.Builder rangingMeasurementBuilder = buildRangingMeasurement(
                    uwbOwrAoaMeasurement.getMacAddress(), rangingStatus, elapsedRealtimeNanos,
                    uwbOwrAoaMeasurement.getNLoS());

            if (rangingStatus == FiraParams.STATUS_CODE_OK) {
                // AngleOfArrivalMeasurement
                AngleOfArrivalMeasurement angleOfArrivalMeasurement =
                        computeAngleOfArrivalMeasurement(
                                isAoaAzimuthEnabled, isAoaElevationEnabled,
                                uwbOwrAoaMeasurement.getAoaAzimuth(),
                                uwbOwrAoaMeasurement.getAoaAzimuthFom(),
                                uwbOwrAoaMeasurement.getAoaElevation(),
                                uwbOwrAoaMeasurement.getAoaElevationFom());
                if (angleOfArrivalMeasurement != null) {
                    rangingMeasurementBuilder.setAngleOfArrivalMeasurement(
                            angleOfArrivalMeasurement);
                }
            }

            rangingReportBuilder.addMeasurement(rangingMeasurementBuilder.build());
        } else if (rangingData.getRangingMeasuresType()
                == UwbUciConstants.RANGING_MEASUREMENT_TYPE_DL_TDOA) {
            List<RangingMeasurement> rangingMeasurements = new ArrayList<>();
            UwbDlTDoAMeasurement[] uwbDlTDoAMeasurements = rangingData.getUwbDlTDoAMeasurements();
            for (int i = 0; i < rangingData.getNoOfRangingMeasures(); ++i) {
                int rangingStatus = uwbDlTDoAMeasurements[i].getStatus();

                RangingMeasurement.Builder rangingMeasurementBuilder = buildRangingMeasurement(
                        uwbDlTDoAMeasurements[i].getMacAddress(), rangingStatus,
                        elapsedRealtimeNanos, uwbDlTDoAMeasurements[i].getNLoS());
                int rssi = uwbDlTDoAMeasurements[i].getRssi();
                if (rssi < 0) {
                    rangingMeasurementBuilder.setRssiDbm(rssi);
                }
                if (rangingStatus == FiraParams.STATUS_CODE_OK) {
                    AngleOfArrivalMeasurement angleOfArrivalMeasurement =
                            computeAngleOfArrivalMeasurement(
                                    isAoaAzimuthEnabled, isAoaElevationEnabled,
                                    uwbDlTDoAMeasurements[i].getAoaAzimuth(),
                                    uwbDlTDoAMeasurements[i].getAoaAzimuthFom(),
                                    uwbDlTDoAMeasurements[i].getAoaElevation(),
                                    uwbDlTDoAMeasurements[i].getAoaElevationFom());
                    if (angleOfArrivalMeasurement != null) {
                        rangingMeasurementBuilder.setAngleOfArrivalMeasurement(
                                angleOfArrivalMeasurement);
                    }
                }
                DlTDoAMeasurement dlTDoAMeasurement = new DlTDoAMeasurement.Builder()
                        .setMessageType(uwbDlTDoAMeasurements[i].getMessageType())
                        .setMessageControl(uwbDlTDoAMeasurements[i].getMessageControl())
                        .setBlockIndex(uwbDlTDoAMeasurements[i].getBlockIndex())
                        .setNLoS(uwbDlTDoAMeasurements[i].getNLoS())
                        .setTxTimestamp(uwbDlTDoAMeasurements[i].getTxTimestamp())
                        .setRxTimestamp(uwbDlTDoAMeasurements[i].getRxTimestamp())
                        .setAnchorCfo(uwbDlTDoAMeasurements[i].getAnchorCfo())
                        .setCfo(uwbDlTDoAMeasurements[i].getCfo())
                        .setInitiatorReplyTime(uwbDlTDoAMeasurements[i].getInitiatorReplyTime())
                        .setResponderReplyTime(uwbDlTDoAMeasurements[i].getResponderReplyTime())
                        .setInitiatorResponderTof(uwbDlTDoAMeasurements[i]
                                .getInitiatorResponderTof())
                        .setAnchorLocation(uwbDlTDoAMeasurements[i].getAnchorLocation())
                        .setActiveRangingRounds(uwbDlTDoAMeasurements[i].getActiveRangingRounds())
                        .setRoundIndex(uwbDlTDoAMeasurements[i].getRoundIndex())
                        .build();

                rangingMeasurementBuilder.setRangingMeasurementMetadata(
                        dlTDoAMeasurement.toBundle());

                rangingMeasurements.add(rangingMeasurementBuilder.build());
            }

            rangingReportBuilder.addMeasurements(rangingMeasurements);
        }
        return rangingReportBuilder.build();
    }

    private static AngleOfArrivalMeasurement computeAngleOfArrivalMeasurement(
            boolean isAoaAzimuthEnabled, boolean isAoaElevationEnabled, float aoaAzimuth,
            int aoaAzimuthFom, float aoaElevation, int aoaElevationFom) {
        // Azimuth is required field (and elevation is an optional field), to build the
        // AngleOfArrivalMeasurement.
        if (isAoaAzimuthEnabled) {
            AngleMeasurement azimuthAngleMeasurement = new AngleMeasurement(
                    UwbUtil.degreeToRadian(aoaAzimuth), 0, aoaAzimuthFom / (double) 100);
            // AngleOfArrivalMeasurement
            AngleOfArrivalMeasurement.Builder angleOfArrivalMeasurementBuilder =
                    new AngleOfArrivalMeasurement.Builder(azimuthAngleMeasurement);

            // Elevation is optional field, to build the AngleOfArrivalMeasurement.
            if (isAoaElevationEnabled) {
                AngleMeasurement altitudeAngleMeasurement = new AngleMeasurement(
                        UwbUtil.degreeToRadian(aoaElevation), 0, aoaElevationFom / (double) 100);
                angleOfArrivalMeasurementBuilder.setAltitude(altitudeAngleMeasurement);
            }

            return angleOfArrivalMeasurementBuilder.build();
        }

        return null;
    }

    private static RangingMeasurement.Builder buildRangingMeasurement(
            byte[] macAddress, int rangingStatus, long elapsedRealtimeNanos, int los) {
        return new RangingMeasurement.Builder()
                .setRemoteDeviceAddress(getComputedMacAddress(macAddress))
                .setStatus(rangingStatus)
                .setElapsedRealtimeNanos(elapsedRealtimeNanos)
                .setLineOfSight(los);
    }

    private static DistanceMeasurement buildDistanceMeasurement(int distance) {
        return new DistanceMeasurement.Builder()
                .setMeters(distance / (double) 100)
                .setErrorMeters(0)
                // TODO: Need to fetch distance FOM once it is added to UCI spec.
                .setConfidenceLevel(0)
                .build();
    }

    private static UwbAddress getComputedMacAddress(byte[] address) {
        if (!SdkLevel.isAtLeastU()) {
            return UwbAddress.fromBytes(TlvUtil.getReverseBytes(address));
        }
        return UwbAddress.fromBytes(address);
    }
}
