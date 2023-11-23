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

import static android.telephony.DomainSelectionService.SELECTOR_TYPE_CALLING;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Looper;
import android.os.PersistableBundle;
import android.telecom.TelecomManager;
import android.telephony.Annotation.DisconnectCauses;
import android.telephony.CarrierConfigManager;
import android.telephony.DisconnectCause;
import android.telephony.DomainSelectionService.SelectionAttributes;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TransportSelectorCallback;
import android.telephony.ims.ImsReasonInfo;

import com.android.internal.telephony.domainselection.NormalCallDomainSelectionConnection;

/**
 * Implements domain selector for outgoing non-emergency calls.
 */
public class NormalCallDomainSelector extends DomainSelectorBase implements
        ImsStateTracker.ImsStateListener, ImsStateTracker.ServiceStateListener {

    private static final String LOG_TAG = "NCDS";

    private boolean mStopDomainSelection = true;
    private ServiceState mServiceState;
    private boolean mImsRegStateReceived;
    private boolean mMmTelCapabilitiesReceived;
    private boolean mReselectDomain;

    public NormalCallDomainSelector(Context context, int slotId, int subId, @NonNull Looper looper,
                                    @NonNull ImsStateTracker imsStateTracker,
                                    @NonNull DestroyListener destroyListener) {
        super(context, slotId, subId, looper, imsStateTracker, destroyListener, LOG_TAG);

        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            logd("Subscribing to state callbacks. Subid:" + subId);
            mImsStateTracker.addServiceStateListener(this);
            mImsStateTracker.addImsStateListener(this);
        } else {
            loge("Invalid Subscription. Subid:" + subId);
        }
    }

    @Override
    public void selectDomain(SelectionAttributes attributes, TransportSelectorCallback callback) {
        mSelectionAttributes = attributes;
        mTransportSelectorCallback = callback;
        mStopDomainSelection = false;

        if (callback == null) {
            loge("Invalid params: TransportSelectorCallback is null");
            return;
        }

        if (attributes == null) {
            loge("Invalid params: SelectionAttributes are null");
            notifySelectionTerminated(DisconnectCause.OUTGOING_FAILURE);
            return;
        }

        int subId = attributes.getSubId();
        boolean validSubscriptionId = SubscriptionManager.isValidSubscriptionId(subId);
        if (attributes.getSelectorType() != SELECTOR_TYPE_CALLING || attributes.isEmergency()
                || !validSubscriptionId) {
            loge("Domain Selection stopped. SelectorType:" + attributes.getSelectorType()
                    + ", isEmergency:" + attributes.isEmergency()
                    + ", ValidSubscriptionId:" + validSubscriptionId);

            notifySelectionTerminated(DisconnectCause.OUTGOING_FAILURE);
            return;
        }

        if (subId == getSubId()) {
            logd("NormalCallDomainSelection triggered. Sub-id:" + subId);
            post(() -> selectDomain());
        } else {
            loge("Subscription-ids doesn't match. This instance is associated with sub-id:"
                    + getSubId() + ", requested sub-id:" + subId);
            // TODO: Throw anamoly here. This condition should never occur.
        }
    }

    @Override
    public void reselectDomain(SelectionAttributes attributes) {
        logd("reselectDomain called");
        mReselectDomain = true;
        selectDomain(attributes, mTransportSelectorCallback);
    }

    @Override
    public synchronized void finishSelection() {
        logd("finishSelection");
        mStopDomainSelection = true;
        mImsStateTracker.removeServiceStateListener(this);
        mImsStateTracker.removeImsStateListener(this);
        mSelectionAttributes = null;
        mTransportSelectorCallback = null;
    }

    /**
     * Cancel an ongoing selection operation. It is up to the DomainSelectionService
     * to clean up all ongoing operations with the framework.
     */
    @Override
    public void cancelSelection() {
        logd("cancelSelection");
        mStopDomainSelection = true;
        mReselectDomain = false;
        if (mTransportSelectorCallback != null) {
            mTransportSelectorCallback.onSelectionTerminated(DisconnectCause.OUTGOING_CANCELED);
        }
        finishSelection();
    }

    @Override
    public void onImsRegistrationStateChanged() {
        logd("onImsRegistrationStateChanged. IsImsRegistered: "
                + mImsStateTracker.isImsRegistered());
        mImsRegStateReceived = true;
        selectDomain();
    }

    @Override
    public void onImsMmTelCapabilitiesChanged() {
        logd("onImsMmTelCapabilitiesChanged. ImsVoiceCap: " + mImsStateTracker.isImsVoiceCapable()
                + " ImsVideoCap: " + mImsStateTracker.isImsVideoCapable());
        mMmTelCapabilitiesReceived = true;
        selectDomain();
    }

    @Override
    public void onImsMmTelFeatureAvailableChanged() {
        logd("onImsMmTelFeatureAvailableChanged");
        selectDomain();
    }

    @Override
    public void onServiceStateUpdated(ServiceState serviceState) {
        logd("onServiceStateUpdated");
        mServiceState = serviceState;
        selectDomain();
    }

    private void notifyPsSelected() {
        logd("notifyPsSelected");
        mStopDomainSelection = true;
        if (mImsStateTracker.isImsRegisteredOverWlan()) {
            logd("WLAN selected");
            mTransportSelectorCallback.onWlanSelected(false);
        } else {
            if (mWwanSelectorCallback == null) {
                mTransportSelectorCallback.onWwanSelected((callback) -> {
                    mWwanSelectorCallback = callback;
                    notifyPsSelectedInternal();
                });
            } else {
                notifyPsSelectedInternal();
            }
        }
    }

    private void notifyPsSelectedInternal() {
        if (mWwanSelectorCallback != null) {
            logd("notifyPsSelected - onWwanSelected");
            mWwanSelectorCallback.onDomainSelected(NetworkRegistrationInfo.DOMAIN_PS, false);
        } else {
            loge("wwanSelectorCallback is null");
            mTransportSelectorCallback.onSelectionTerminated(DisconnectCause.OUTGOING_FAILURE);
        }
    }

    private void notifyCsSelected() {
        logd("notifyCsSelected");
        mStopDomainSelection = true;
        if (mWwanSelectorCallback == null) {
            mTransportSelectorCallback.onWwanSelected((callback) -> {
                mWwanSelectorCallback = callback;
                notifyCsSelectedInternal();
            });
        } else {
            notifyCsSelectedInternal();
        }
    }

    private void notifyCsSelectedInternal() {
        if (mWwanSelectorCallback != null) {
            logd("wwanSelectorCallback -> onDomainSelected(DOMAIN_CS)");
            mWwanSelectorCallback.onDomainSelected(NetworkRegistrationInfo.DOMAIN_CS, false);
        } else {
            loge("wwanSelectorCallback is null");
            mTransportSelectorCallback.onSelectionTerminated(DisconnectCause.OUTGOING_FAILURE);
        }
    }

    private void notifySelectionTerminated(@DisconnectCauses int cause) {
        mStopDomainSelection = true;
        if (mTransportSelectorCallback != null) {
            mTransportSelectorCallback.onSelectionTerminated(cause);
            finishSelection();
        }
    }

    private boolean isOutOfService() {
        return (mServiceState.getState() == ServiceState.STATE_OUT_OF_SERVICE
                || mServiceState.getState() == ServiceState.STATE_POWER_OFF
                || mServiceState.getState() == ServiceState.STATE_EMERGENCY_ONLY);
    }

    private boolean isWpsCallSupportedByIms() {
        CarrierConfigManager configManager = mContext.getSystemService(CarrierConfigManager.class);

        PersistableBundle config = null;
        if (configManager != null) {
            config = configManager.getConfigForSubId(mSelectionAttributes.getSubId());
        }

        return (config != null)
                ? config.getBoolean(CarrierConfigManager.KEY_SUPPORT_WPS_OVER_IMS_BOOL) : false;
    }

    private void handleWpsCall() {
        if (isWpsCallSupportedByIms()) {
            logd("WPS call placed over PS");
            notifyPsSelected();
        } else {
            if (isOutOfService()) {
                loge("Cannot place call in current ServiceState: " + mServiceState.getState());
                notifySelectionTerminated(DisconnectCause.OUT_OF_SERVICE);
            } else {
                logd("WPS call placed over CS");
                notifyCsSelected();
            }
        }
    }

    private boolean isTtySupportedByIms() {
        CarrierConfigManager configManager = mContext.getSystemService(CarrierConfigManager.class);

        PersistableBundle config = null;
        if (configManager != null) {
            config = configManager.getConfigForSubId(mSelectionAttributes.getSubId());
        }

        return (config != null)
                && config.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL);
    }

    private boolean isTtyModeEnabled() {
        TelecomManager tm = mContext.getSystemService(TelecomManager.class);
        if (tm == null) {
            loge("isTtyModeEnabled: telecom not available");
            return false;
        }
        return tm.getCurrentTtyMode() != TelecomManager.TTY_MODE_OFF;
    }

    private synchronized void selectDomain() {
        if (mStopDomainSelection || mSelectionAttributes == null
                || mTransportSelectorCallback == null) {
            logd("Domain Selection is stopped.");
            return;
        }

        if (mServiceState == null) {
            logd("Waiting for ServiceState callback.");
            return;
        }

        // Check if this is a re-dial scenario
        // IMS -> CS
        ImsReasonInfo imsReasonInfo = mSelectionAttributes.getPsDisconnectCause();
        if (mReselectDomain && imsReasonInfo != null) {
            logd("PsDisconnectCause:" + imsReasonInfo.mCode);
            mReselectDomain = false;
            if (imsReasonInfo.mCode == ImsReasonInfo.CODE_LOCAL_CALL_CS_RETRY_REQUIRED) {
                if (isOutOfService()) {
                    loge("Cannot place call in current ServiceState: " + mServiceState.getState());
                    notifySelectionTerminated(DisconnectCause.OUT_OF_SERVICE);
                } else {
                    logd("Redialing over CS");
                    notifyCsSelected();
                }
                return;
            } else {
                logd("Redialing cancelled.");
                // Not a valid redial
                notifySelectionTerminated(DisconnectCause.NOT_VALID);
                return;
            }
        }

        // CS -> IMS
        // TODO: @PreciseDisconnectCauses doesn't contain cause code related to redial on IMS.
        if (mReselectDomain /*mSelectionAttributes.getCsDisconnectCause() == IMS_REDIAL_CODE*/) {
            logd("Redialing cancelled.");
            // Not a valid redial
            notifySelectionTerminated(DisconnectCause.NOT_VALID);
            return;
        }

        if (!mImsStateTracker.isMmTelFeatureAvailable()) {
            logd("MmTelFeatureAvailable unavailable");
            if (isOutOfService()) {
                loge("Cannot place call in current ServiceState: " + mServiceState.getState());
                notifySelectionTerminated(DisconnectCause.OUT_OF_SERVICE);
            } else {
                notifyCsSelected();
            }
            return;
        }

        if (!mImsRegStateReceived || !mMmTelCapabilitiesReceived) {
            loge("Waiting for ImsState and MmTelCapabilities callbacks");
            return;
        }

        // Check IMS registration state.
        if (!mImsStateTracker.isImsRegistered()) {
            logd("IMS is NOT registered");
            if (isOutOfService()) {
                loge("Cannot place call in current ServiceState: " + mServiceState.getState());
                notifySelectionTerminated(DisconnectCause.OUT_OF_SERVICE);
            } else {
                notifyCsSelected();
            }
            return;
        }

        // Check TTY
        if (isTtyModeEnabled() && !isTtySupportedByIms()) {
            if (isOutOfService()) {
                loge("Cannot place call in current ServiceState: " + mServiceState.getState());
                notifySelectionTerminated(DisconnectCause.OUT_OF_SERVICE);
            } else {
                notifyCsSelected();
            }
            return;
        }

        // Handle video call.
        if (mSelectionAttributes.isVideoCall()) {
            logd("It's a video call");
            if (mImsStateTracker.isImsVideoCapable()) {
                logd("IMS is video capable");
                notifyPsSelected();
            } else {
                logd("IMS is not video capable. Ending the call");
                notifySelectionTerminated(DisconnectCause.OUTGOING_FAILURE);
            }
            return;
        }

        // Handle voice call.
        if (mImsStateTracker.isImsVoiceCapable()) {
            logd("IMS is voice capable");
            // TODO(b/266175810) Remove this dependency.
            if (NormalCallDomainSelectionConnection
                    .isWpsCall(mSelectionAttributes.getNumber())) {
                handleWpsCall();
            } else {
                notifyPsSelected();
            }
        } else {
            logd("IMS is not voice capable");
            // Voice call CS fallback
            if (isOutOfService()) {
                loge("Cannot place call in current ServiceState: " + mServiceState.getState());
                notifySelectionTerminated(DisconnectCause.OUT_OF_SERVICE);
            } else {
                notifyCsSelected();
            }
        }
    }
}
