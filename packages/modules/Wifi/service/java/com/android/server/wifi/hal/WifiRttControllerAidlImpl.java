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

package com.android.server.wifi.hal;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.wifi.IWifiRttControllerEventCallback;
import android.hardware.wifi.RttBw;
import android.hardware.wifi.RttCapabilities;
import android.hardware.wifi.RttConfig;
import android.hardware.wifi.RttPeerType;
import android.hardware.wifi.RttPreamble;
import android.hardware.wifi.RttResult;
import android.hardware.wifi.RttStatus;
import android.hardware.wifi.RttType;
import android.hardware.wifi.WifiChannelInfo;
import android.hardware.wifi.WifiChannelWidthInMhz;
import android.net.MacAddress;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.ResponderConfig;
import android.net.wifi.rtt.ResponderLocation;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AIDL implementation of the IWifiRttController interface.
 */
public class WifiRttControllerAidlImpl implements IWifiRttController {
    private static final String TAG = "WifiRttControllerAidl";
    private boolean mVerboseLoggingEnabled = false;

    private android.hardware.wifi.IWifiRttController mWifiRttController;
    private WifiRttController.Capabilities mRttCapabilities;
    private WifiRttControllerEventCallback mHalCallback;
    private Set<WifiRttController.RttControllerRangingResultsCallback> mRangingResultsCallbacks =
            new HashSet<>();
    private final Object mLock = new Object();

    public WifiRttControllerAidlImpl(
            @NonNull android.hardware.wifi.IWifiRttController rttController) {
        mWifiRttController = rttController;
        mHalCallback = new WifiRttControllerEventCallback();
    }

    /**
     * See comments for {@link IWifiRttController#setup()}
     */
    @Override
    public boolean setup() {
        final String methodStr = "setup";
        synchronized (mLock) {
            if (!checkIfaceAndLogFailure(methodStr)) return false;
            try {
                mWifiRttController.registerEventCallback(mHalCallback);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            updateRttCapabilities();
            return true;
        }
    }

    /**
     * See comments for {@link IWifiRttController#enableVerboseLogging(boolean)}
     */
    @Override
    public void enableVerboseLogging(boolean verbose) {
        synchronized (mLock) {
            mVerboseLoggingEnabled = verbose;
        }
    }

    /**
     * See comments for {@link IWifiRttController#registerRangingResultsCallback(
     *                         WifiRttController.RttControllerRangingResultsCallback)}
     */
    @Override
    public void registerRangingResultsCallback(
            WifiRttController.RttControllerRangingResultsCallback callback) {
        synchronized (mLock) {
            if (!mRangingResultsCallbacks.add(callback)) {
                Log.e(TAG, "Ranging results callback was already registered");
            }
        }
    }

    /**
     * See comments for {@link IWifiRttController#validate()}
     */
    @Override
    public boolean validate() {
        final String methodStr = "validate";
        synchronized (mLock) {
            if (!checkIfaceAndLogFailure(methodStr)) return false;
            try {
                // Just check that we can call this method successfully.
                mWifiRttController.getBoundIface();
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * See comments for {@link IWifiRttController#getRttCapabilities()}
     */
    @Override
    @Nullable
    public WifiRttController.Capabilities getRttCapabilities() {
        synchronized (mLock) {
            return mRttCapabilities;
        }
    }

    /**
     * See comments for {@link IWifiRttController#rangeRequest(int, RangingRequest)}
     */
    @Override
    public boolean rangeRequest(int cmdId, RangingRequest request) {
        final String methodStr = "rangeRequest";
        synchronized (mLock) {
            if (!checkIfaceAndLogFailure(methodStr)) return false;
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "rangeRequest: cmdId=" + cmdId + ", # of requests="
                        + request.mRttPeers.size() + ", request=" + request);
            }

            updateRttCapabilities();
            try {
                RttConfig[] rttConfigs =
                        convertRangingRequestToRttConfigs(request, mRttCapabilities);
                if (rttConfigs == null) {
                    Log.e(TAG, methodStr + " received invalid request parameters");
                    return false;
                } else if (rttConfigs.length == 0) {
                    Log.e(TAG, methodStr + " invalidated all requests");
                    dispatchOnRangingResults(cmdId, new ArrayList<>());
                    return true;
                }
                mWifiRttController.rangeRequest(cmdId, rttConfigs);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * See comments for {@link IWifiRttController#rangeCancel(int, List)}
     */
    @Override
    public boolean rangeCancel(int cmdId, List<MacAddress> macAddresses) {
        final String methodStr = "rangeCancel";
        synchronized (mLock) {
            if (!checkIfaceAndLogFailure(methodStr)) return false;
            try {
                android.hardware.wifi.MacAddress[] halAddresses =
                        new android.hardware.wifi.MacAddress[macAddresses.size()];
                for (int i = 0; i < macAddresses.size(); i++) {
                    android.hardware.wifi.MacAddress halAddress =
                            new android.hardware.wifi.MacAddress();
                    halAddress.data = macAddresses.get(i).toByteArray();
                    halAddresses[i] = halAddress;
                }
                mWifiRttController.rangeCancel(cmdId, halAddresses);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * See comments for {@link IWifiRttController#dump(PrintWriter)}
     */
    @Override
    public void dump(PrintWriter pw) {
        pw.println("WifiRttController:");
        pw.println("  mIWifiRttController: " + mWifiRttController);
        pw.println("  mRttCapabilities: " + mRttCapabilities);
    }

    /**
     *  Callback for HAL events on the WifiRttController
     */
    private class WifiRttControllerEventCallback extends IWifiRttControllerEventCallback.Stub {
        @Override
        public void onResults(int cmdId, RttResult[] halResults) {
            if (mVerboseLoggingEnabled) {
                int numResults = halResults != null ? halResults.length : -1;
                Log.v(TAG, "onResults: cmdId=" + cmdId + ", # of results=" + numResults);
            }
            if (halResults == null) {
                halResults = new RttResult[0];
            }
            ArrayList<RangingResult> rangingResults = halToFrameworkRangingResults(halResults);
            dispatchOnRangingResults(cmdId, rangingResults);
        }

        @Override
        public String getInterfaceHash() {
            return IWifiRttControllerEventCallback.HASH;
        }

        @Override
        public int getInterfaceVersion() {
            return IWifiRttControllerEventCallback.VERSION;
        }
    }


    // Utilities

    private ArrayList<RangingResult> halToFrameworkRangingResults(RttResult[] halResults) {
        ArrayList<RangingResult> rangingResults = new ArrayList();
        for (RttResult rttResult : halResults) {
            if (rttResult == null) continue;
            byte[] lci = rttResult.lci.data;
            byte[] lcr = rttResult.lcr.data;
            ResponderLocation responderLocation;
            try {
                responderLocation = new ResponderLocation(lci, lcr);
                if (!responderLocation.isValid()) {
                    responderLocation = null;
                }
            } catch (Exception e) {
                responderLocation = null;
                Log.e(TAG, "ResponderLocation: lci/lcr parser failed exception -- " + e);
            }
            if (rttResult.successNumber <= 1 && rttResult.distanceSdInMm != 0) {
                if (mVerboseLoggingEnabled) {
                    Log.w(TAG, "postProcessResults: non-zero distance stdev with 0||1 num "
                            + "samples!? result=" + rttResult);
                }
                rttResult.distanceSdInMm = 0;
            }
            rangingResults.add(new RangingResult(
                    halToFrameworkRttStatus(rttResult.status),
                    MacAddress.fromBytes(rttResult.addr),
                    rttResult.distanceInMm, rttResult.distanceSdInMm,
                    rttResult.rssi / -2, rttResult.numberPerBurstPeer,
                    rttResult.successNumber, lci, lcr, responderLocation,
                    rttResult.timeStampInUs /  WifiRttController.CONVERSION_US_TO_MS,
                    rttResult.type == RttType.TWO_SIDED, rttResult.channelFreqMHz,
                    rttResult.packetBw));
        }
        return rangingResults;
    }

    private static @WifiRttController.FrameworkRttStatus int halToFrameworkRttStatus(
            int halStatus) {
        switch (halStatus) {
            case RttStatus.SUCCESS:
                return WifiRttController.FRAMEWORK_RTT_STATUS_SUCCESS;
            case RttStatus.FAILURE:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAILURE;
            case RttStatus.FAIL_NO_RSP:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_NO_RSP;
            case RttStatus.FAIL_REJECTED:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_REJECTED;
            case RttStatus.FAIL_NOT_SCHEDULED_YET:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_NOT_SCHEDULED_YET;
            case RttStatus.FAIL_TM_TIMEOUT:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_TM_TIMEOUT;
            case RttStatus.FAIL_AP_ON_DIFF_CHANNEL:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_AP_ON_DIFF_CHANNEL;
            case RttStatus.FAIL_NO_CAPABILITY:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_NO_CAPABILITY;
            case RttStatus.ABORTED:
                return WifiRttController.FRAMEWORK_RTT_STATUS_ABORTED;
            case RttStatus.FAIL_INVALID_TS:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_INVALID_TS;
            case RttStatus.FAIL_PROTOCOL:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_PROTOCOL;
            case RttStatus.FAIL_SCHEDULE:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_SCHEDULE;
            case RttStatus.FAIL_BUSY_TRY_LATER:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_BUSY_TRY_LATER;
            case RttStatus.INVALID_REQ:
                return WifiRttController.FRAMEWORK_RTT_STATUS_INVALID_REQ;
            case RttStatus.NO_WIFI:
                return WifiRttController.FRAMEWORK_RTT_STATUS_NO_WIFI;
            case RttStatus.FAIL_FTM_PARAM_OVERRIDE:
                return WifiRttController.FRAMEWORK_RTT_STATUS_FAIL_FTM_PARAM_OVERRIDE;
            default:
                Log.e(TAG, "Unrecognized RttStatus: " + halStatus);
                return WifiRttController.FRAMEWORK_RTT_STATUS_UNKNOWN;
        }
    }

    private void updateRttCapabilities() {
        final String methodStr = "updateRttCapabilities";
        if (mRttCapabilities != null) return;
        if (mVerboseLoggingEnabled) Log.v(TAG, "updateRttCapabilities");

        try {
            RttCapabilities halCapabilities = mWifiRttController.getCapabilities();
            mRttCapabilities = new WifiRttController.Capabilities(halCapabilities);
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
        } catch (ServiceSpecificException e) {
            handleServiceSpecificException(e, methodStr);
        }

        if (mRttCapabilities != null && !mRttCapabilities.rttFtmSupported) {
            Log.wtf(TAG, "Firmware indicates RTT is not supported - but device supports RTT - "
                    + "ignored!?");
        }
    }

    private static RttConfig[] convertRangingRequestToRttConfigs(RangingRequest request,
            WifiRttController.Capabilities cap) {
        ArrayList<RttConfig> rttConfigs = new ArrayList<>();

        // Skip any configurations which have an error (just print out a message).
        // The caller will only get results for valid configurations.
        for (ResponderConfig responder: request.mRttPeers) {
            RttConfig config = new RttConfig();
            config.addr = responder.macAddress.toByteArray();

            try {
                config.type = responder.supports80211mc ? RttType.TWO_SIDED : RttType.ONE_SIDED;
                if (config.type == RttType.ONE_SIDED && cap != null && !cap.oneSidedRttSupported) {
                    Log.w(TAG, "Device does not support one-sided RTT");
                    continue;
                }

                config.peer = frameworkToHalRttPeerType(responder.responderType);
                config.channel = new WifiChannelInfo();
                config.channel.width = frameworkToHalChannelWidth(
                        responder.channelWidth);
                config.channel.centerFreq = responder.frequency;
                config.channel.centerFreq0 = responder.centerFreq0;
                config.channel.centerFreq1 = responder.centerFreq1;
                config.bw = frameworkToHalChannelBandwidth(responder.channelWidth);
                config.preamble = frameworkToHalResponderPreamble(responder.preamble);
                validateBwAndPreambleCombination(config.bw, config.preamble);

                if (config.peer == RttPeerType.NAN_TYPE) {
                    config.mustRequestLci = false;
                    config.mustRequestLcr = false;
                    config.burstPeriod = 0;
                    config.numBurst = 0;
                    config.numFramesPerBurst = request.mRttBurstSize;
                    config.numRetriesPerRttFrame = 0; // irrelevant for 2-sided RTT
                    config.numRetriesPerFtmr = 3;
                    config.burstDuration = 9;
                } else { // AP + all non-NAN requests
                    config.mustRequestLci = true;
                    config.mustRequestLcr = true;
                    config.burstPeriod = 0;
                    config.numBurst = 0;
                    config.numFramesPerBurst = request.mRttBurstSize;
                    config.numRetriesPerRttFrame = (config.type == RttType.TWO_SIDED ? 0 : 3);
                    config.numRetriesPerFtmr = 3;
                    config.burstDuration = 9;

                    if (cap != null) { // constrain parameters per device capabilities
                        config.mustRequestLci = config.mustRequestLci && cap.lciSupported;
                        config.mustRequestLcr = config.mustRequestLcr && cap.lcrSupported;
                        config.bw = halRttChannelBandwidthCapabilityLimiter(config.bw, cap);
                        config.preamble = halRttPreambleCapabilityLimiter(config.preamble, cap);
                    }
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid configuration: " + e.getMessage());
                continue;
            }

            rttConfigs.add(config);
        }

        RttConfig[] configArray = new RttConfig[rttConfigs.size()];
        for (int i = 0; i < rttConfigs.size(); i++) {
            configArray[i] = rttConfigs.get(i);
        }
        return configArray;
    }

    private static void validateBwAndPreambleCombination(int bw, int preamble) {
        if (bw <= RttBw.BW_20MHZ) {
            return;
        }
        if (bw == RttBw.BW_40MHZ && preamble >= RttPreamble.HT) {
            return;
        }
        if (bw == RttBw.BW_320MHZ && preamble == RttPreamble.EHT) {
            return;
        }
        if (bw >= RttBw.BW_80MHZ && bw < RttBw.BW_320MHZ && preamble >= RttPreamble.VHT) {
            return;
        }
        throw new IllegalArgumentException(
                "bw and preamble combination is invalid, bw: " + bw + " preamble: " + preamble);
    }

    private static int frameworkToHalRttPeerType(int responderType)
            throws IllegalArgumentException {
        switch (responderType) {
            case ResponderConfig.RESPONDER_AP:
                return RttPeerType.AP;
            case ResponderConfig.RESPONDER_STA:
                return RttPeerType.STA;
            case ResponderConfig.RESPONDER_P2P_GO:
                return RttPeerType.P2P_GO;
            case ResponderConfig.RESPONDER_P2P_CLIENT:
                return RttPeerType.P2P_CLIENT;
            case ResponderConfig.RESPONDER_AWARE:
                return RttPeerType.NAN_TYPE;
            default:
                throw new IllegalArgumentException(
                        "frameworkToHalRttPeerType: bad " + responderType);
        }
    }

    private static int frameworkToHalChannelWidth(int responderChannelWidth)
            throws IllegalArgumentException {
        switch (responderChannelWidth) {
            case ResponderConfig.CHANNEL_WIDTH_20MHZ:
                return WifiChannelWidthInMhz.WIDTH_20;
            case ResponderConfig.CHANNEL_WIDTH_40MHZ:
                return WifiChannelWidthInMhz.WIDTH_40;
            case ResponderConfig.CHANNEL_WIDTH_80MHZ:
                return WifiChannelWidthInMhz.WIDTH_80;
            case ResponderConfig.CHANNEL_WIDTH_160MHZ:
                return WifiChannelWidthInMhz.WIDTH_160;
            case ResponderConfig.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return WifiChannelWidthInMhz.WIDTH_80P80;
            case ResponderConfig.CHANNEL_WIDTH_320MHZ:
                return WifiChannelWidthInMhz.WIDTH_320;
            default:
                throw new IllegalArgumentException(
                        "frameworkToHalChannelWidth: bad " + responderChannelWidth);
        }
    }

    private static int frameworkToHalChannelBandwidth(int responderChannelWidth)
            throws IllegalArgumentException {
        switch (responderChannelWidth) {
            case ResponderConfig.CHANNEL_WIDTH_20MHZ:
                return RttBw.BW_20MHZ;
            case ResponderConfig.CHANNEL_WIDTH_40MHZ:
                return RttBw.BW_40MHZ;
            case ResponderConfig.CHANNEL_WIDTH_80MHZ:
                return RttBw.BW_80MHZ;
            case ResponderConfig.CHANNEL_WIDTH_160MHZ:
            case ResponderConfig.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return RttBw.BW_160MHZ;
            case ResponderConfig.CHANNEL_WIDTH_320MHZ:
                return RttBw.BW_320MHZ;
            default:
                throw new IllegalArgumentException(
                        "halRttChannelBandwidthFromHalBandwidth: bad " + responderChannelWidth);
        }
    }

    private static int frameworkToHalResponderPreamble(int responderPreamble)
            throws IllegalArgumentException {
        switch (responderPreamble) {
            case ResponderConfig.PREAMBLE_LEGACY:
                return RttPreamble.LEGACY;
            case ResponderConfig.PREAMBLE_HT:
                return RttPreamble.HT;
            case ResponderConfig.PREAMBLE_VHT:
                return RttPreamble.VHT;
            case ResponderConfig.PREAMBLE_HE:
                return RttPreamble.HE;
            default:
                throw new IllegalArgumentException(
                        "frameworkToHalResponderPreamble: bad " + responderPreamble);
        }
    }

    /**
     * Check whether the selected RTT channel bandwidth is supported by the device.
     * If supported, return the requested bandwidth.
     * If not supported, return the next lower bandwidth which is supported.
     * If none, throw an IllegalArgumentException.
     *
     * Note: the halRttChannelBandwidth is a single bit flag from the HAL RttBw type.
     */
    private static int halRttChannelBandwidthCapabilityLimiter(int halRttChannelBandwidth,
            WifiRttController.Capabilities cap) throws IllegalArgumentException {
        int requestedBandwidth = halRttChannelBandwidth;
        while ((halRttChannelBandwidth != 0) && ((halRttChannelBandwidth & cap.bwSupported) == 0)) {
            halRttChannelBandwidth >>= 1;
        }

        if (halRttChannelBandwidth != 0) {
            return halRttChannelBandwidth;
        }

        throw new IllegalArgumentException(
                "RTT BW=" + requestedBandwidth + ", not supported by device capabilities=" + cap
                        + " - and no supported alternative");
    }

    /**
     * Check whether the selected RTT preamble is supported by the device.
     * If supported, return the requested preamble.
     * If not supported, return the next "lower" preamble which is supported.
     * If none, throw an IllegalArgumentException.
     *
     * Note: the halRttPreamble is a single bit flag from the HAL RttPreamble type.
     */
    private static int halRttPreambleCapabilityLimiter(int halRttPreamble,
            WifiRttController.Capabilities cap) throws IllegalArgumentException {
        int requestedPreamble = halRttPreamble;
        while ((halRttPreamble != 0) && ((halRttPreamble & cap.preambleSupported) == 0)) {
            halRttPreamble >>= 1;
        }

        if (halRttPreamble != 0) {
            return halRttPreamble;
        }

        throw new IllegalArgumentException(
                "RTT Preamble=" + requestedPreamble + ", not supported by device capabilities="
                        + cap + " - and no supported alternative");
    }

    private void dispatchOnRangingResults(int cmdId, List<RangingResult> rangingResults) {
        for (WifiRttController.RttControllerRangingResultsCallback
                callback : mRangingResultsCallbacks) {
            callback.onRangingResults(cmdId, rangingResults);
        }
    }

    private boolean checkIfaceAndLogFailure(String methodStr) {
        if (mWifiRttController == null) {
            Log.e(TAG, "Unable to call " + methodStr + " because iface is null.");
            return false;
        }
        return true;
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        mWifiRttController = null;
        Log.e(TAG, methodStr + " failed with remote exception: " + e);
    }

    private void handleServiceSpecificException(ServiceSpecificException e, String methodStr) {
        Log.e(TAG, methodStr + " failed with service-specific exception: " + e);
    }
}
