/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.ons;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Stack;

public class ONSProfileDownloader {

    interface IONSProfileDownloaderListener {
        void onDownloadComplete(int primarySubId);
        void onDownloadError(DownloadRetryOperationCode operationCode, int pSIMSubId);
    }

    private static final String TAG = ONSProfileDownloader.class.getName();
    public static final String ACTION_ONS_ESIM_DOWNLOAD = "com.android.ons.action.ESIM_DOWNLOAD";

    @VisibleForTesting protected static final String PARAM_PRIMARY_SUBID = "PrimarySubscriptionID";
    @VisibleForTesting protected static final String PARAM_REQUEST_TYPE = "REQUEST";
    @VisibleForTesting protected static final int REQUEST_CODE_DOWNLOAD_SUB = 1;

    private final Handler mHandler;
    private final Context mContext;
    private final CarrierConfigManager mCarrierConfigManager;
    private final EuiccManager mEuiccManager;
    private final ONSProfileConfigurator mONSProfileConfig;
    private IONSProfileDownloaderListener mListener;

    @VisibleForTesting
    protected enum DownloadRetryOperationCode{
        DOWNLOAD_SUCCESSFUL,
        ERR_UNRESOLVABLE,
        ERR_MEMORY_FULL,
        ERR_INSTALL_ESIM_PROFILE_FAILED,
        ERR_RETRY_DOWNLOAD,
        BACKOFF_TIMER_EXPIRED
    };

    public ONSProfileDownloader(Context context, CarrierConfigManager carrierConfigManager,
                                EuiccManager euiccManager,
                                ONSProfileConfigurator onsProfileConfigurator,
                                IONSProfileDownloaderListener listener) {
        mContext = context;
        mListener = listener;
        mEuiccManager = euiccManager;
        mONSProfileConfig = onsProfileConfigurator;
        mCarrierConfigManager = carrierConfigManager;

        mHandler = new DownloadHandler();
    }

    class DownloadHandler extends Handler {
        DownloadHandler() {
            super(Looper.myLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REQUEST_CODE_DOWNLOAD_SUB: { //arg1 -> ResultCode
                    Log.d(TAG, "REQUEST_CODE_DOWNLOAD_SUB callback received");
                    int pSIMSubId = ((Intent) msg.obj).getIntExtra(PARAM_PRIMARY_SUBID, 0);
                    int detailedErrCode = ((Intent) msg.obj).getIntExtra(
                            EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, 0);
                    int operationCode = ((Intent) msg.obj).getIntExtra(
                            EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_OPERATION_CODE, 0);
                    int errorCode = ((Intent) msg.obj).getIntExtra(
                            EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_ERROR_CODE, 0);

                    Log.d(TAG, "Result Code : " + detailedErrCode);
                    Log.d(TAG, "Operation Code : " + operationCode);
                    Log.d(TAG, "Error Code : " + errorCode);

                    DownloadRetryOperationCode opCode = mapDownloaderErrorCode(msg.arg1,
                            detailedErrCode, operationCode, errorCode);
                    Log.d(TAG, "DownloadRetryOperationCode: " + opCode);

                    switch (opCode) {
                        case DOWNLOAD_SUCCESSFUL:
                            mListener.onDownloadComplete(pSIMSubId);
                            break;

                        case ERR_UNRESOLVABLE:
                            mListener.onDownloadError(opCode, pSIMSubId);
                            Log.e(TAG, "Unresolvable download error: "
                                    + getUnresolvableErrorDescription(errorCode));
                            break;

                        default:
                            mListener.onDownloadError(opCode, pSIMSubId);
                            break;
                    }
                }
                break;
            }
        }

        @VisibleForTesting
        protected DownloadRetryOperationCode mapDownloaderErrorCode(int resultCode,
                                                                    int detailedErrCode,
                                                                    int operationCode,
                                                                    int errorCode) {

            if (operationCode == EuiccManager.OPERATION_SMDX_SUBJECT_REASON_CODE) {
                //SMDP Error codes handling
                Pair<String, String> errCode = decodeSmdxSubjectAndReasonCode(detailedErrCode);
                Log.e(TAG, " Subject Code: " + errCode.first + " Reason Code: "
                        + errCode.second);

                //8.1 - eUICC, 4.8 - Insufficient Memory
                // eUICC does not have sufficient space for this Profile.
                if (errCode.equals(Pair.create("8.1.0", "4.8"))) {
                    return DownloadRetryOperationCode.ERR_MEMORY_FULL;
                }

                //8.8.5 - Download order, 4.10 - Time to Live Expired
                //The Download order has expired
                if (errCode.equals(Pair.create("8.8.5", "4.10"))) {
                    return DownloadRetryOperationCode.ERR_RETRY_DOWNLOAD;
                }

                //All other errors are unresolvable or retry after SIM State Change
                return DownloadRetryOperationCode.ERR_UNRESOLVABLE;

            }

            switch (errorCode) {

                //Success Cases
                case EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK: {
                    return DownloadRetryOperationCode.DOWNLOAD_SUCCESSFUL;
                }

                //Low eUICC memory cases
                case EuiccManager.ERROR_EUICC_INSUFFICIENT_MEMORY: {
                    Log.d(TAG, "Download ERR: EUICC_INSUFFICIENT_MEMORY");
                    return DownloadRetryOperationCode.ERR_MEMORY_FULL;
                }

                //Temporary download error cases
                case EuiccManager.ERROR_TIME_OUT:
                case EuiccManager.ERROR_CONNECTION_ERROR:
                case EuiccManager.ERROR_OPERATION_BUSY: {
                    return DownloadRetryOperationCode.ERR_RETRY_DOWNLOAD;
                }

                //Profile installation failure cases
                case EuiccManager.ERROR_INSTALL_PROFILE: {
                    return DownloadRetryOperationCode.ERR_INSTALL_ESIM_PROFILE_FAILED;
                }

                default: {
                    return DownloadRetryOperationCode.ERR_UNRESOLVABLE;
                }
            }
        }
    }

    private String getUnresolvableErrorDescription(int errorCode) {
        switch (errorCode) {
            case EuiccManager.ERROR_INVALID_ACTIVATION_CODE:
                return "ERROR_INVALID_ACTIVATION_CODE";

            case EuiccManager.ERROR_UNSUPPORTED_VERSION:
                return "ERROR_UNSUPPORTED_VERSION";

            case EuiccManager.ERROR_INSTALL_PROFILE:
                return "ERROR_INSTALL_PROFILE";

            case EuiccManager.ERROR_SIM_MISSING:
                return "ERROR_SIM_MISSING";

            case EuiccManager.ERROR_ADDRESS_MISSING:
                return "ERROR_ADDRESS_MISSING";

            case EuiccManager.ERROR_CERTIFICATE_ERROR:
                return "ERROR_CERTIFICATE_ERROR";

            case EuiccManager.ERROR_NO_PROFILES_AVAILABLE:
                return "ERROR_NO_PROFILES_AVAILABLE";

            case EuiccManager.ERROR_CARRIER_LOCKED:
                return "ERROR_CARRIER_LOCKED";
        }

        return "Unknown";
    }

    @VisibleForTesting
    protected void downloadProfile(int primarySubId) {
        Log.d(TAG, "downloadProfile");

        //Get SMDP address from carrier configuration.
        String smdpAddress = getSMDPServerAddress(primarySubId);
        if (smdpAddress == null || smdpAddress.length() <= 0) {
            return;
        }

        //Generate Activation code 1${SM-DP+ FQDN}$
        String activationCode = "1$" + smdpAddress + "$";
        Intent intent = new Intent(mContext, ONSProfileResultReceiver.class);
        intent.setAction(ACTION_ONS_ESIM_DOWNLOAD);
        intent.putExtra(PARAM_REQUEST_TYPE, REQUEST_CODE_DOWNLOAD_SUB);
        intent.putExtra(PARAM_PRIMARY_SUBID, primarySubId);
        PendingIntent callbackIntent = PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_DOWNLOAD_SUB, intent, PendingIntent.FLAG_MUTABLE);

        Log.d(TAG, "Download Request sent to EUICC Manager");
        mEuiccManager.downloadSubscription(DownloadableSubscription.forActivationCode(
                activationCode), true, callbackIntent);
    }

    /**
     * Retrieves SMDP+ server address of the given subscription from carrier configuration.
     *
     * @param subscriptionId subscription Id of the primary SIM.
     * @return FQDN of SMDP+ server.
     */
    private String getSMDPServerAddress(int subscriptionId) {
        PersistableBundle config = mCarrierConfigManager.getConfigForSubId(subscriptionId);
        return config.getString(CarrierConfigManager.KEY_SMDP_SERVER_ADDRESS_STRING);
    }

    /**
     * Given encoded error code described in
     * {@link android.telephony.euicc.EuiccManager#OPERATION_SMDX_SUBJECT_REASON_CODE} decode it
     * into SubjectCode[5.2.6.1] and ReasonCode[5.2.6.2] from GSMA (SGP.22 v2.2)
     *
     * @param resultCode from
     *               {@link android.telephony.euicc.EuiccManager#OPERATION_SMDX_SUBJECT_REASON_CODE}
     *
     * @return a pair containing SubjectCode[5.2.6.1] and ReasonCode[5.2.6.2] from GSMA (SGP.22
     * v2.2)
     */
    @VisibleForTesting
    protected static Pair<String, String> decodeSmdxSubjectAndReasonCode(int resultCode) {
        final int numOfSections = 6;
        final int bitsPerSection = 4;
        final int sectionMask = 0xF;

        final Stack<Integer> sections = new Stack<>();

        // Extracting each section of digits backwards.
        for (int i = 0; i < numOfSections; ++i) {
            int sectionDigit = resultCode & sectionMask;
            sections.push(sectionDigit);
            resultCode = resultCode >>> bitsPerSection;
        }

        String subjectCode = sections.pop() + "." + sections.pop() + "." + sections.pop();
        String reasonCode = sections.pop() + "." + sections.pop() + "." + sections.pop();

        // drop the leading zeros, e.g. 0.1 -> 1, 0.0.3 -> 3, 0.5.1 -> 5.1
        subjectCode = subjectCode.replaceAll("^(0\\.)*", "");
        reasonCode = reasonCode.replaceAll("^(0\\.)*", "");

        return Pair.create(subjectCode, reasonCode);
    }

    /**
     * Callback to receive result for subscription activate request and process the same.
     * @param intent
     * @param resultCode
     */
    public void onCallbackIntentReceived(Intent intent, int resultCode) {
        Message msg = new Message();
        msg.what = REQUEST_CODE_DOWNLOAD_SUB;
        msg.arg1 = resultCode;
        msg.obj = intent;
        mHandler.sendMessage(msg);
    }
}
