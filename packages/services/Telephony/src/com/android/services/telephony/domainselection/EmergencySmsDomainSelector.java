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

package com.android.services.telephony.domainselection;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Looper;
import android.os.PersistableBundle;
import android.telephony.AccessNetworkConstants;
import android.telephony.BarringInfo;
import android.telephony.CarrierConfigManager;
import android.telephony.DataSpecificRegistrationInfo;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.VopsSupportInfo;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Implements an emergency SMS domain selector for sending an emergency SMS.
 */
public class EmergencySmsDomainSelector extends SmsDomainSelector implements
        ImsStateTracker.BarringInfoListener, ImsStateTracker.ServiceStateListener {
    /**
     * Stores the configuration value of
     * {@link CarrierConfigManager#KEY_SUPPORT_EMERGENCY_SMS_OVER_IMS_BOOL}.
     * This value is always updated whenever the domain selection is requested.
     */
    private Boolean mEmergencySmsOverImsSupportedByConfig;
    private ServiceState mServiceState;
    private boolean mServiceStateReceived;
    private BarringInfo mBarringInfo;
    private boolean mBarringInfoReceived;

    public EmergencySmsDomainSelector(Context context, int slotId, int subId,
            @NonNull Looper looper, @NonNull ImsStateTracker imsStateTracker,
            @NonNull DestroyListener listener) {
        super(context, slotId, subId, looper, imsStateTracker, listener,
                "DomainSelector-EmergencySMS");

        mImsStateTracker.addServiceStateListener(this);
        mImsStateTracker.addBarringInfoListener(this);
    }

    @Override
    public void destroy() {
        if (mDestroyed) {
            return;
        }
        mImsStateTracker.removeServiceStateListener(this);
        mImsStateTracker.removeBarringInfoListener(this);
        super.destroy();
    }

    @Override
    public void finishSelection() {
        super.finishSelection();
        mServiceStateReceived = false;
        mServiceState = null;
        mBarringInfoReceived = false;
        mBarringInfo = null;
        mEmergencySmsOverImsSupportedByConfig = null;
    }

    @Override
    public void onBarringInfoUpdated(BarringInfo barringInfo) {
        mBarringInfoReceived = true;
        mBarringInfo = barringInfo;
        sendMessageForDomainSelection();
    }

    @Override
    public void onServiceStateUpdated(ServiceState serviceState) {
        mServiceStateReceived = true;
        mServiceState = serviceState;
        sendMessageForDomainSelection();
    }

    /**
     * Checks whether the domain selector is ready to select the domain or not.
     * The emergency SMS requires to be updated for the {@link ServiceState} and
     * {@link BarringInfo} to confirm that the cellular network supports to send emergency SMS
     * messages over IMS.
     */
    @VisibleForTesting
    public boolean isDomainSelectionReady() {
        return mServiceStateReceived && mBarringInfoReceived;
    }

    @Override
    protected boolean isSmsOverImsAvailable() {
        if (super.isSmsOverImsAvailable()) {
            /**
             * Even though IMS is successfully registered, the cellular domain should be
             * available for the emergency SMS according to the carrier's requirement
             * when {@link CarrierConfigManager#KEY_SUPPORT_EMERGENCY_SMS_OVER_IMS_BOOL} is set
             * to true.
             */
            if (isEmergencySmsOverImsSupportedIfLteLimitedOrInService()) {
                /**
                 * Emergency SMS should be supported via emergency PDN.
                 * If this condition is false, then need to fallback to CS network
                 * because the current PS network does not allow the emergency service.
                 */
                return isNetworkAvailableForImsEmergencySms();
            }

            // Emergency SMS is supported via IMS PDN.
            return true;
        }

        return isImsEmergencySmsAvailable();
    }

    @Override
    protected void selectDomain() {
        if (!isDomainSelectionRequested()) {
            logi("Domain selection is not requested!");
            return;
        }

        if (!isDomainSelectionReady()) {
            logd("Wait for the readiness of the domain selection!");
            return;
        }

        logi("selectDomain: " + mImsStateTracker.imsStateToString());

        if (isSmsOverImsAvailable()) {
            boolean isEmergencySmsOverImsSupportedIfLteLimitedOrInService =
                    isEmergencySmsOverImsSupportedIfLteLimitedOrInService();

            if (mImsStateTracker.isImsRegisteredOverWlan()) {
                /**
                 * When {@link CarrierConfigManager#KEY_SUPPORT_EMERGENCY_SMS_OVER_IMS_BOOL}
                 * is set to true, the emergency SMS supports on the LTE network using the
                 * emergency PDN. As of now, since the emergency SMS doesn't use the emergency PDN
                 * over WLAN, the domain selector reports the domain as WLAN only if
                 * {@code isEmergencySmsOverImsSupportedIfLteLimitedOrInService} is set to false
                 * and IMS is registered over WLAN.
                 * Otherwise, the domain selector reports the domain as WWAN.
                 */
                if (!isEmergencySmsOverImsSupportedIfLteLimitedOrInService) {
                    notifyWlanSelected(false);
                    return;
                }

                logi("DomainSelected: WLAN >> WWAN");
            }
            notifyWwanSelected(NetworkRegistrationInfo.DOMAIN_PS,
                    isEmergencySmsOverImsSupportedIfLteLimitedOrInService);
        } else {
            notifyWwanSelected(NetworkRegistrationInfo.DOMAIN_CS, false);
        }
    }

    /**
     * Checks if the emergency SMS messages over IMS is available according to the carrier
     * configuration and the current network states.
     */
    private boolean isImsEmergencySmsAvailable() {
        boolean isEmergencySmsOverImsSupportedIfLteLimitedOrInService =
                isEmergencySmsOverImsSupportedIfLteLimitedOrInService();
        boolean networkAvailable = isNetworkAvailableForImsEmergencySms();

        logi("isImsEmergencySmsAvailable: "
                + "emergencySmsOverIms=" + isEmergencySmsOverImsSupportedIfLteLimitedOrInService
                + ", mmTelFeatureAvailable=" + mImsStateTracker.isMmTelFeatureAvailable()
                + ", networkAvailable=" + networkAvailable);

        return isEmergencySmsOverImsSupportedIfLteLimitedOrInService
                && mImsStateTracker.isMmTelFeatureAvailable()
                && networkAvailable;
    }

    /**
     * Checks if sending emergency SMS messages over IMS is supported when in LTE/limited LTE
     * (Emergency only) service mode from the carrier configuration.
     */
    private boolean isEmergencySmsOverImsSupportedIfLteLimitedOrInService() {
        if (mEmergencySmsOverImsSupportedByConfig == null) {
            CarrierConfigManager ccm = mContext.getSystemService(CarrierConfigManager.class);

            if (ccm == null) {
                loge("CarrierConfigManager is null");
                return false;
            }

            PersistableBundle b = ccm.getConfigForSubId(getSubId());

            if (b == null) {
                loge("PersistableBundle is null");
                return false;
            }

            mEmergencySmsOverImsSupportedByConfig = b.getBoolean(
                    CarrierConfigManager.KEY_SUPPORT_EMERGENCY_SMS_OVER_IMS_BOOL);
        }

        return mEmergencySmsOverImsSupportedByConfig;
    }

    /**
     * Checks if the emergency service is available in the LTE service mode.
     */
    private boolean isLteEmergencyAvailableInService() {
        if (mServiceState == null) {
            return false;
        }

        final NetworkRegistrationInfo regInfo = mServiceState.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);

        if (regInfo != null
                && regInfo.getAccessNetworkTechnology() == TelephonyManager.NETWORK_TYPE_LTE
                && regInfo.isRegistered()) {
            return isEmergencyServiceSupported(regInfo) && isEmergencyServiceAllowed();
        }
        return false;
    }

    /**
     * Checks if the emergency service is available in the limited LTE service(Emergency only) mode.
     */
    private boolean isLteEmergencyAvailableInLimitedService() {
        if (mServiceState == null) {
            return false;
        }

        final NetworkRegistrationInfo regInfo = mServiceState.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regInfo != null
                && regInfo.getAccessNetworkTechnology() == TelephonyManager.NETWORK_TYPE_LTE
                && regInfo.isEmergencyEnabled()) {
            return isEmergencyServiceSupported(regInfo) && isEmergencyServiceAllowed();
        }
        return false;
    }

    /**
     * Checks if the network is available for the IMS emergency SMS.
     */
    private boolean isNetworkAvailableForImsEmergencySms() {
        return isLteEmergencyAvailableInService()
                || isLteEmergencyAvailableInLimitedService();
    }

    /**
     * Checks if the emergency service is supported by the network.
     *
     * This checks if "Emergency bearer services indicator (EMC-BS)" field (bits) set to
     * the "Emergency bearer services in S1 mode supported".
     *
     * @return {@code true} if the emergency service is supported by the network,
     *         {@code false} otherwise.
     */
    private boolean isEmergencyServiceSupported(@NonNull NetworkRegistrationInfo regInfo) {
        final DataSpecificRegistrationInfo dsRegInfo = regInfo.getDataSpecificInfo();
        if (dsRegInfo != null) {
            final VopsSupportInfo vopsSupportInfo = dsRegInfo.getVopsSupportInfo();
            return vopsSupportInfo != null
                    && vopsSupportInfo.isEmergencyServiceSupported();
        }
        return false;
    }

    /**
     * Checks if the emergency service is allowed (not barred) by the network.
     *
     * This checks if SystemInformationBlockType2 includes the ac-BarringInfo and
     * with the ac-BarringForEmergency set to FALSE or
     * if the SystemInformationBlockType2 does not include the ac-BarringInfo.
     *
     * @return {@code true} if the emergency service is allowed by the network,
     *         {@code false} otherwise.
     */
    private boolean isEmergencyServiceAllowed() {
        if (mBarringInfo == null) {
            return true;
        }
        final BarringInfo.BarringServiceInfo bsi =
                mBarringInfo.getBarringServiceInfo(BarringInfo.BARRING_SERVICE_TYPE_EMERGENCY);
        return !bsi.isBarred();
    }
}
