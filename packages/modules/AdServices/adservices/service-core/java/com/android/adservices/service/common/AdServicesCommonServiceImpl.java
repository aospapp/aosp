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

package com.android.adservices.service.common;

import static android.adservices.common.AdServicesPermissions.ACCESS_ADSERVICES_STATE;
import static android.adservices.common.AdServicesPermissions.MODIFY_ADSERVICES_STATE;
import static android.adservices.common.AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
import static android.adservices.common.AdServicesStatusUtils.STATUS_UNAUTHORIZED;

import static com.android.adservices.data.common.AdservicesEntryPointConstant.ADSERVICES_ENTRY_POINT_STATUS_DISABLE;
import static com.android.adservices.data.common.AdservicesEntryPointConstant.ADSERVICES_ENTRY_POINT_STATUS_ENABLE;
import static com.android.adservices.data.common.AdservicesEntryPointConstant.KEY_ADSERVICES_ENTRY_POINT_STATUS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__AD_SERVICES_ENTRY_POINT_FAILURE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__API_CALLBACK_ERROR;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__SHARED_PREF_UPDATE_FAILURE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__UX;

import android.adservices.common.IAdServicesCommonCallback;
import android.adservices.common.IAdServicesCommonService;
import android.adservices.common.IsAdServicesEnabledResult;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.RemoteException;

import androidx.annotation.RequiresApi;

import com.android.adservices.LogUtil;
import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.errorlogging.ErrorLogUtil;
import com.android.adservices.service.Flags;
import com.android.adservices.service.common.compat.PackageManagerCompatUtils;
import com.android.adservices.service.common.feature.PrivacySandboxFeatureType;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.consent.DeviceRegionProvider;

import java.util.concurrent.Executor;

/**
 * Implementation of {@link IAdServicesCommonService}.
 *
 * @hide
 */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class AdServicesCommonServiceImpl extends IAdServicesCommonService.Stub {

    private final Context mContext;
    private static final Executor sBackgroundExecutor = AdServicesExecutors.getBackgroundExecutor();
    private final Flags mFlags;
    public final String ADSERVICES_STATUS_SHARED_PREFERENCE = "AdserviceStatusSharedPreference";

    public AdServicesCommonServiceImpl(Context context, Flags flags) {
        mContext = context;
        mFlags = flags;
    }

    @Override
    @RequiresPermission(ACCESS_ADSERVICES_STATE)
    public void isAdServicesEnabled(@NonNull IAdServicesCommonCallback callback) {
        boolean hasAccessAdServicesStatePermission =
                PermissionHelper.hasAccessAdServicesStatePermission(mContext);

        sBackgroundExecutor.execute(
                () -> {
                    try {
                        if (!hasAccessAdServicesStatePermission) {
                            callback.onFailure(STATUS_UNAUTHORIZED);
                            return;
                        }
                        reconsentIfNeededForEU();
                        boolean isAdServicesEnabled = mFlags.getAdServicesEnabled();
                        if (mFlags.isBackCompatActivityFeatureEnabled()) {
                            isAdServicesEnabled &=
                                    PackageManagerCompatUtils.isAdServicesActivityEnabled(mContext);
                        }
                        callback.onResult(
                                new IsAdServicesEnabledResult.Builder()
                                        .setAdServicesEnabled(isAdServicesEnabled)
                                        .build());
                    } catch (Exception e) {
                        try {
                            callback.onFailure(STATUS_INTERNAL_ERROR);
                        } catch (RemoteException re) {
                            LogUtil.e(re, "Unable to send result to the callback");
                            ErrorLogUtil.e(
                                    re,
                                    AD_SERVICES_ERROR_REPORTED__ERROR_CODE__API_CALLBACK_ERROR,
                                    AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__UX);
                        }
                    }
                });
    }

    /**
     * Set the adservices entry point Status from UI side, and also check adid zero-out status, and
     * Schedule notification if both adservices entry point enabled and adid not opt-out and
     * Adservice Is enabled
     */
    @Override
    @RequiresPermission(MODIFY_ADSERVICES_STATE)
    public void setAdServicesEnabled(boolean adServicesEntryPointEnabled, boolean adIdEnabled) {
        boolean hasModifyAdServicesStatePermission =
                PermissionHelper.hasModifyAdServicesStatePermission(mContext);
        sBackgroundExecutor.execute(
                () -> {
                    try {
                        if (!hasModifyAdServicesStatePermission) {
                            // TODO(b/242578032): handle the security exception in a better way
                            LogUtil.d("Caller is not authorized to control AdServices state");
                            return;
                        }

                        SharedPreferences preferences =
                                mContext.getSharedPreferences(
                                        ADSERVICES_STATUS_SHARED_PREFERENCE, Context.MODE_PRIVATE);

                        int adServiceEntryPointStatusInt =
                                adServicesEntryPointEnabled
                                        ? ADSERVICES_ENTRY_POINT_STATUS_ENABLE
                                        : ADSERVICES_ENTRY_POINT_STATUS_DISABLE;
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putInt(
                                KEY_ADSERVICES_ENTRY_POINT_STATUS, adServiceEntryPointStatusInt);
                        if (!editor.commit()) {
                            LogUtil.e("saving to the sharedpreference failed");
                            ErrorLogUtil.e(
                                    AD_SERVICES_ERROR_REPORTED__ERROR_CODE__SHARED_PREF_UPDATE_FAILURE,
                                    AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__UX,
                                    this.getClass().getSimpleName(),
                                    new Object() {}.getClass().getEnclosingMethod().getName());
                        }
                        LogUtil.d(
                                "adid status is "
                                        + adIdEnabled
                                        + ", adservice status is "
                                        + mFlags.getAdServicesEnabled());
                        LogUtil.d("entry point: " + adServicesEntryPointEnabled);

                        ConsentManager consentManager = ConsentManager.getInstance(mContext);
                        if (mFlags.getAdServicesEnabled() && adServicesEntryPointEnabled) {
                            // Check if it is reconsent for ROW.
                            if (reconsentIfNeededForROW()) {
                                LogUtil.d("Reconsent for ROW.");

                                if (mFlags.isUiFeatureTypeLoggingEnabled()) {
                                    if (mFlags.getEuNotifFlowChangeEnabled()) {
                                        consentManager.setCurrentPrivacySandboxFeature(
                                                PrivacySandboxFeatureType
                                                        .PRIVACY_SANDBOX_RECONSENT_FF);
                                    } else {
                                        consentManager.setCurrentPrivacySandboxFeature(
                                                PrivacySandboxFeatureType
                                                        .PRIVACY_SANDBOX_RECONSENT);
                                    }
                                }

                                ConsentNotificationJobService.schedule(mContext, adIdEnabled, true);
                            } else if (getFirstConsentStatus()) {
                                if (mFlags.isUiFeatureTypeLoggingEnabled()) {
                                    if (mFlags.getEuNotifFlowChangeEnabled()) {
                                        consentManager.setCurrentPrivacySandboxFeature(
                                                PrivacySandboxFeatureType
                                                        .PRIVACY_SANDBOX_FIRST_CONSENT_FF);
                                    } else {
                                        consentManager.setCurrentPrivacySandboxFeature(
                                                PrivacySandboxFeatureType
                                                        .PRIVACY_SANDBOX_FIRST_CONSENT);
                                    }
                                }

                                ConsentNotificationJobService.schedule(
                                        mContext, adIdEnabled, false);
                            }

                            if (ConsentManager.getInstance(mContext).getConsent().isGiven()) {
                                PackageChangedReceiver.enableReceiver(mContext, mFlags);
                                BackgroundJobsManager.scheduleAllBackgroundJobs(mContext);
                            }

                        } else {
                            if (mFlags.isUiFeatureTypeLoggingEnabled()) {
                                consentManager.setCurrentPrivacySandboxFeature(
                                        PrivacySandboxFeatureType.PRIVACY_SANDBOX_UNSUPPORTED);
                            }
                        }
                    } catch (Exception e) {
                        LogUtil.e(
                                "unable to save the adservices entry point status of "
                                        + e.getMessage());
                        ErrorLogUtil.e(
                                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__AD_SERVICES_ENTRY_POINT_FAILURE,
                                AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__UX,
                                this.getClass().getSimpleName(),
                                this.getClass().getEnclosingMethod().getName());
                    }
                });
    }

    /** Init the AdServices Status Service. */
    public void init() {}

    /** Check EU device and reconsent logic and schedule the notification if needed. */
    public void reconsentIfNeededForEU() {
        boolean adserviceEnabled = mFlags.getAdServicesEnabled();
        if (adserviceEnabled
                && mFlags.getGaUxFeatureEnabled()
                && DeviceRegionProvider.isEuDevice(mContext, mFlags)) {
            // Check if GA UX was notice before
            ConsentManager consentManager = ConsentManager.getInstance(mContext);
            if (!consentManager.wasGaUxNotificationDisplayed()) {
                // Check Beta notification displayed and user opt-in, we will re-consent
                SharedPreferences preferences =
                        mContext.getSharedPreferences(
                                ADSERVICES_STATUS_SHARED_PREFERENCE, Context.MODE_PRIVATE);
                // Check the setAdServicesEnabled was called before
                if (preferences.contains(KEY_ADSERVICES_ENTRY_POINT_STATUS)
                        && consentManager.getConsent().isGiven()) {
                    // AdidEnabled status does not matter here as this is only for EU device, it
                    // will override by the EU in the scheduler
                    if (mFlags.isUiFeatureTypeLoggingEnabled()) {
                        if (mFlags.getEuNotifFlowChangeEnabled()) {
                            consentManager.setCurrentPrivacySandboxFeature(
                                    PrivacySandboxFeatureType.PRIVACY_SANDBOX_RECONSENT_FF);
                        } else {
                            consentManager.setCurrentPrivacySandboxFeature(
                                    PrivacySandboxFeatureType.PRIVACY_SANDBOX_RECONSENT);
                        }
                    }

                    ConsentNotificationJobService.schedule(mContext, false, true);
                }
            }
        }
    }

    /** Check if user is first time consent */
    public boolean getFirstConsentStatus() {
        ConsentManager consentManager = ConsentManager.getInstance(mContext);
        return (!consentManager.wasGaUxNotificationDisplayed()
                        && !consentManager.wasNotificationDisplayed())
                || mFlags.getConsentNotificationDebugMode();
    }

    /** Check ROW device and see if it fit reconsent */
    public boolean reconsentIfNeededForROW() {
        ConsentManager consentManager = ConsentManager.getInstance(mContext);
        return mFlags.getGaUxFeatureEnabled()
                && !DeviceRegionProvider.isEuDevice(mContext, mFlags)
                && !consentManager.wasGaUxNotificationDisplayed()
                && consentManager.wasNotificationDisplayed()
                && consentManager.getConsent().isGiven();
    }
}
