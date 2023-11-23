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

import static com.android.adservices.data.common.AdservicesEntryPointConstant.ADSERVICES_ENTRY_POINT_STATUS_DISABLE;
import static com.android.adservices.data.common.AdservicesEntryPointConstant.ADSERVICES_ENTRY_POINT_STATUS_ENABLE;
import static com.android.adservices.data.common.AdservicesEntryPointConstant.KEY_ADSERVICES_ENTRY_POINT_STATUS;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;

import android.adservices.common.IAdServicesCommonCallback;
import android.adservices.common.IsAdServicesEnabledResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.TelephonyManager;

import com.android.adservices.service.Flags;
import com.android.adservices.service.common.compat.PackageManagerCompatUtils;
import com.android.adservices.service.consent.AdServicesApiConsent;
import com.android.adservices.service.consent.ConsentManager;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AdServicesCommonServiceImplTest {
    private AdServicesCommonServiceImpl mCommonService;
    private CountDownLatch mGetCommonCallbackLatch;
    @Mock private Flags mFlags;
    @Mock private Context mContext;
    @Mock private PackageManager mPackageManager;
    @Mock private SharedPreferences mSharedPreferences;
    @Mock private SharedPreferences.Editor mEditor;
    @Mock private ConsentManager mConsentManager;
    @Mock private TelephonyManager mTelephonyManager;
    @Captor ArgumentCaptor<String> mStringArgumentCaptor;
    @Captor ArgumentCaptor<Integer> mIntegerArgumentCaptor;
    private static final int BINDER_CONNECTION_TIMEOUT_MS = 5_000;

    private MockitoSession mStaticMockSession = null;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(ConsentNotificationJobService.class)
                        .spyStatic(ConsentManager.class)
                        .spyStatic(BackgroundJobsManager.class)
                        .mockStatic(PackageManagerCompatUtils.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        mCommonService = new AdServicesCommonServiceImpl(mContext, mFlags);
        doReturn(true).when(mFlags).getAdServicesEnabled();
        ExtendedMockito.doNothing()
                .when(() -> BackgroundJobsManager.scheduleAllBackgroundJobs(any(Context.class)));
        doNothing()
                .when(
                        () ->
                                ConsentNotificationJobService.schedule(
                                        any(Context.class),
                                        any(Boolean.class),
                                        any(Boolean.class)));

        doReturn(mSharedPreferences).when(mContext).getSharedPreferences(anyString(), anyInt());
        doReturn(mPackageManager).when(mContext).getPackageManager();
        doReturn(mEditor).when(mSharedPreferences).edit();
        doReturn(mEditor).when(mEditor).putInt(anyString(), anyInt());
        doReturn(true).when(mEditor).commit();
        doReturn(true).when(mSharedPreferences).contains(anyString());

        ExtendedMockito.doReturn(mConsentManager)
                .when(() -> ConsentManager.getInstance(any(Context.class)));

        // Set device to EU
        doReturn(Flags.UI_EEA_COUNTRIES).when(mFlags).getUiEeaCountries();
        doReturn("pl").when(mTelephonyManager).getSimCountryIso();
        doReturn(true).when(mPackageManager).hasSystemFeature(anyString());
        doReturn(mPackageManager).when(mContext).getPackageManager();
        doReturn(mTelephonyManager).when(mContext).getSystemService(TelephonyManager.class);
    }


    @After
    public void teardown() {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    @Test
    public void getAdserviceStatusTest() throws InterruptedException {
        doReturn(false).when(mFlags).getGaUxFeatureEnabled();
        mCommonService = new AdServicesCommonServiceImpl(mContext, mFlags);
        // Calling get adservice status, init set the flag to true, expect to return true
        IsAdServicesEnabledResult[] capturedResponseParcel = getStatusResult();
        assertThat(
                        mGetCommonCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        IsAdServicesEnabledResult getStatusResult1 = capturedResponseParcel[0];
        assertThat(getStatusResult1.getAdServicesEnabled()).isTrue();

        // Set the flag to false
        doReturn(false).when(mFlags).getAdServicesEnabled();

        // Calling again, expect to false
        capturedResponseParcel = getStatusResult();
        assertThat(
                        mGetCommonCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        IsAdServicesEnabledResult getStatusResult2 = capturedResponseParcel[0];
        assertThat(getStatusResult2.getAdServicesEnabled()).isFalse();
    }

    @Test
    public void getAdserviceStatusWithCheckActivityTest() throws InterruptedException {
        doReturn(true).when(mFlags).isBackCompatActivityFeatureEnabled();

        doReturn(false).when(mFlags).getGaUxFeatureEnabled();
        mCommonService = new AdServicesCommonServiceImpl(mContext, mFlags);
        ExtendedMockito.doReturn(true)
                .when(() -> PackageManagerCompatUtils.isAdServicesActivityEnabled(any()));
        // Calling get adservice status, set the activity to enabled, expect to return true
        IsAdServicesEnabledResult[] capturedResponseParcel = getStatusResult();
        assertThat(
                        mGetCommonCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        IsAdServicesEnabledResult getStatusResult1 = capturedResponseParcel[0];
        assertThat(getStatusResult1.getAdServicesEnabled()).isTrue();

        // Set the activity to disabled
        ExtendedMockito.doReturn(false)
                .when(() -> PackageManagerCompatUtils.isAdServicesActivityEnabled(any()));

        // Calling again, expect to false
        capturedResponseParcel = getStatusResult();
        assertThat(
                        mGetCommonCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        IsAdServicesEnabledResult getStatusResult2 = capturedResponseParcel[0];
        assertThat(getStatusResult2.getAdServicesEnabled()).isFalse();
    }

    @Test
    public void isAdservicesEnabledReconsentTest_happycase() throws InterruptedException {
        // Happy case
        // Calling get adservice status, init set the flag to true, expect to return true
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        IsAdServicesEnabledResult[] capturedResponseParcel = getStatusResult();
        assertThat(
                        mGetCommonCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        IsAdServicesEnabledResult getStatusResult1 = capturedResponseParcel[0];
        assertThat(getStatusResult1.getAdServicesEnabled()).isTrue();
        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), anyBoolean(), anyBoolean()),
                times(1));
    }

    @Test
    public void isAdservicesEnabledReconsentTest_gaUxFeatureDisabled() throws InterruptedException {
        // GA UX feature disable, should not execute scheduler
        doReturn(false).when(mFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        IsAdServicesEnabledResult[] capturedResponseParcel = getStatusResult();
        assertThat(
                        mGetCommonCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        IsAdServicesEnabledResult getStatusResult1 = capturedResponseParcel[0];
        assertThat(getStatusResult1.getAdServicesEnabled()).isTrue();
        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), anyBoolean(), anyBoolean()),
                times(0));
    }

    @Test
    public void isAdservicesEnabledReconsentTest_deviceNotEu() throws InterruptedException {
        // GA UX feature enable, set device to not EU, not execute scheduler
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn("us").when(mTelephonyManager).getSimCountryIso();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        IsAdServicesEnabledResult[] capturedResponseParcel = getStatusResult();
        assertThat(
                        mGetCommonCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        IsAdServicesEnabledResult getStatusResult1 = capturedResponseParcel[0];
        assertThat(getStatusResult1.getAdServicesEnabled()).isTrue();
        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), anyBoolean(), anyBoolean()),
                times(0));
    }

    @Test
    public void isAdservicesEnabledReconsentTest_gaUxNotificationDisplayed()
            throws InterruptedException {
        // GA UX feature enabled, device set to EU, GA UX notification set to displayed
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn("pl").when(mTelephonyManager).getSimCountryIso();
        doReturn(true).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        IsAdServicesEnabledResult[] capturedResponseParcel = getStatusResult();
        assertThat(
                        mGetCommonCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        IsAdServicesEnabledResult getStatusResult1 = capturedResponseParcel[0];
        assertThat(getStatusResult1.getAdServicesEnabled()).isTrue();
        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), anyBoolean(), anyBoolean()),
                times(0));
    }

    @Test
    public void isAdservicesEnabledReconsentTest_sharedPreferenceNotContain()
            throws InterruptedException {
        // GA UX notification set to not displayed, sharedpreference set to not contains
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(false).when(mSharedPreferences).contains(anyString());
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        IsAdServicesEnabledResult[] capturedResponseParcel = getStatusResult();
        assertThat(
                        mGetCommonCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        IsAdServicesEnabledResult getStatusResult1 = capturedResponseParcel[0];
        assertThat(getStatusResult1.getAdServicesEnabled()).isTrue();
        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), anyBoolean(), anyBoolean()),
                times(0));
    }

    @Test
    public void isAdservicesEnabledReconsentTest_userConsentRevoked() throws InterruptedException {
        // Sharedpreference set to contains, user consent set to revoke
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(false)).when(mConsentManager).getConsent();
        IsAdServicesEnabledResult[] capturedResponseParcel = getStatusResult();
        assertThat(
                        mGetCommonCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        IsAdServicesEnabledResult getStatusResult1 = capturedResponseParcel[0];
        assertThat(getStatusResult1.getAdServicesEnabled()).isTrue();
        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), anyBoolean(), anyBoolean()),
                times(0));
    }

    private IsAdServicesEnabledResult[] getStatusResult() {
        final IsAdServicesEnabledResult[] capturedResponseParcel = new IsAdServicesEnabledResult[1];
        mGetCommonCallbackLatch = new CountDownLatch(1);
        mCommonService.isAdServicesEnabled(
                new IAdServicesCommonCallback() {
                    @Override
                    public void onResult(IsAdServicesEnabledResult responseParcel)
                            throws RemoteException {
                        capturedResponseParcel[0] = responseParcel;
                        mGetCommonCallbackLatch.countDown();
                    }

                    @Override
                    public void onFailure(int statusCode) {
                        Assert.fail();
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });
        return capturedResponseParcel;
    }

    @Test
    public void setAdservicesEntryPointStatusTest() throws InterruptedException {
        // Not reconsent, as not ROW devices, Not first Consent, as notification displayed is true
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        mCommonService.setAdServicesEnabled(true, false);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), any(Boolean.class)),
                times(0));
        ExtendedMockito.verify(
                () -> BackgroundJobsManager.scheduleAllBackgroundJobs(any(Context.class)));

        Mockito.verify(mEditor)
                .putInt(mStringArgumentCaptor.capture(), mIntegerArgumentCaptor.capture());
        assertThat(mStringArgumentCaptor.getValue()).isEqualTo(KEY_ADSERVICES_ENTRY_POINT_STATUS);
        assertThat(mIntegerArgumentCaptor.getValue())
                .isEqualTo(ADSERVICES_ENTRY_POINT_STATUS_ENABLE);

        // Not executed, as entry point enabled status is false
        doReturn(false).when(mConsentManager).wasNotificationDisplayed();
        mCommonService.setAdServicesEnabled(false, true);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), any(Boolean.class)),
                times(0));
        Mockito.verify(mEditor, times(2))
                .putInt(mStringArgumentCaptor.capture(), mIntegerArgumentCaptor.capture());
        assertThat(mStringArgumentCaptor.getValue()).isEqualTo(KEY_ADSERVICES_ENTRY_POINT_STATUS);
        assertThat(mIntegerArgumentCaptor.getValue())
                .isEqualTo(ADSERVICES_ENTRY_POINT_STATUS_DISABLE);
    }

    @Test
    public void setAdservicesEnabledConsentTest_happycase() throws InterruptedException {
        // Set device to ROW
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn("us").when(mTelephonyManager).getSimCountryIso();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        mCommonService.setAdServicesEnabled(true, false);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), eq(true)),
                times(1));
    }

    @Test
    public void setAdservicesEnabledConsentTest_ReconsentGaUxFeatureDisabled()
            throws InterruptedException {
        // GA UX feature disable
        doReturn("us").when(mTelephonyManager).getSimCountryIso();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(false).when(mFlags).getGaUxFeatureEnabled();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        mCommonService.setAdServicesEnabled(true, false);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), eq(true)),
                times(0));
    }

    @Test
    public void setAdservicesEnabledConsentTest_ReconsentEUDevice() throws InterruptedException {
        // enable GA UX feature, but EU device
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        mCommonService.setAdServicesEnabled(true, false);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), eq(true)),
                times(0));
    }

    @Test
    public void setAdservicesEnabledConsentTest_ReconsentGaUxNotificationDisplayed()
            throws InterruptedException {
        // ROW device, GA UX notification displayed
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn("us").when(mTelephonyManager).getSimCountryIso();
        doReturn(true).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        mCommonService.setAdServicesEnabled(true, false);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), eq(true)),
                times(0));
    }

    @Test
    public void setAdservicesEnabledConsentTest_ReconsentNotificationNotDisplayed()
            throws InterruptedException {
        // GA UX notification not displayed, notification not displayed, this also trigger
        // first consent case, but we verify here for reconsentStatus as true
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn("us").when(mTelephonyManager).getSimCountryIso();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(false).when(mConsentManager).wasNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        mCommonService.setAdServicesEnabled(true, false);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), eq(true)),
                times(0));
    }

    @Test
    public void setAdservicesEnabledConsentTest_ReconsentUserConsentRevoked()
            throws InterruptedException {
        // Notification displayed, user consent is revoked
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn("us").when(mTelephonyManager).getSimCountryIso();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(false)).when(mConsentManager).getConsent();
        mCommonService.setAdServicesEnabled(true, false);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), eq(true)),
                times(0));
    }

    @Test
    public void setAdservicesEnabledConsentTest_FirstConsentHappycase()
            throws InterruptedException {
        // First Consent happy case, should be executed
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(false).when(mConsentManager).wasNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        mCommonService.setAdServicesEnabled(true, false);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), eq(false)),
                times(1));
    }

    @Test
    public void setAdservicesEnabledConsentTest_FirstConsentGaUxNotificationDisplayed()
            throws InterruptedException {
        // GA UX notification was displayed
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        mCommonService.setAdServicesEnabled(true, false);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), eq(false)),
                times(0));
    }

    @Test
    public void setAdservicesEnabledConsentTest_FirstConsentNotificationDisplayed()
            throws InterruptedException {
        // Notification was displayed
        doReturn(true).when(mFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(AdServicesApiConsent.getConsent(true)).when(mConsentManager).getConsent();
        mCommonService.setAdServicesEnabled(true, false);
        Thread.sleep(1000);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), eq(false)),
                times(0));
    }


}
