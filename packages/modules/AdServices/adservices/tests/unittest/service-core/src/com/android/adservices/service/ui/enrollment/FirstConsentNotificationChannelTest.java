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

package com.android.adservices.service.ui.enrollment;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;

import android.content.Context;

import com.android.adservices.service.common.ConsentNotificationJobService;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.ui.data.UxStatesManager;
import com.android.adservices.service.ui.ux.PrivacySandboxUxCollection;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.IOException;

public class FirstConsentNotificationChannelTest {
    private final FirstConsentNotificationChannel mFirstConsentNotificationChannel =
            new FirstConsentNotificationChannel();

    @Mock private Context mContext;
    @Mock private PrivacySandboxUxCollection mPrivacySandboxUxCollection;
    @Mock private UxStatesManager mUxStatesManager;
    @Mock private ConsentManager mConsentManager;
    private MockitoSession mStaticMockSession;

    @Before
    public void setup() throws IOException {
        MockitoAnnotations.initMocks(this);

        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(UxStatesManager.class)
                        .spyStatic(ConsentManager.class)
                        .spyStatic(ConsentNotificationJobService.class)
                        .strictness(Strictness.WARN)
                        .initMocks(this)
                        .startMocking();

        // Do not trigger real notifications.
        doNothing()
                .when(
                        () ->
                                ConsentNotificationJobService.schedule(
                                        any(Context.class), anyBoolean(), anyBoolean()));
    }

    @After
    public void teardown() throws IOException {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    @Test
    public void isEligibleTest_gaNotificationAlreadyDisplayed() {
        doReturn(true).when(mConsentManager).wasGaUxNotificationDisplayed();

        assertThat(
                        mFirstConsentNotificationChannel.isEligible(
                                mPrivacySandboxUxCollection.GA_UX,
                                mConsentManager,
                                mUxStatesManager))
                .isFalse();
    }

    @Test
    public void isEligibleTest_betaNotificationAlreadyDisplayed() {
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();

        assertThat(
                        mFirstConsentNotificationChannel.isEligible(
                                mPrivacySandboxUxCollection.BETA_UX,
                                mConsentManager,
                                mUxStatesManager))
                .isFalse();
    }

    @Test
    public void isEligibleTest_u18NotificationAlreadyDisplayed() {
        doReturn(true).when(mConsentManager).wasU18NotificationDisplayed();

        assertThat(
                        mFirstConsentNotificationChannel.isEligible(
                                mPrivacySandboxUxCollection.U18_UX,
                                mConsentManager,
                                mUxStatesManager))
                .isFalse();
    }

    @Test
    public void isEligibleTest_noNotificationEverDisplayed() {
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(false).when(mConsentManager).wasNotificationDisplayed();
        doReturn(false).when(mConsentManager).wasU18NotificationDisplayed();

        assertThat(
                        mFirstConsentNotificationChannel.isEligible(
                                mPrivacySandboxUxCollection.U18_UX,
                                mConsentManager,
                                mUxStatesManager))
                .isTrue();
    }

    @Test
    public void enrollTest_nonReconsentNotification() {
        mFirstConsentNotificationChannel.enroll(mContext, mConsentManager);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), anyBoolean(), eq(false)),
                times(1));
    }

    @Test
    public void enrollTest_adIdEnabledFirstConsentNotification() {
        doReturn(true).when(mConsentManager).isAdIdEnabled();

        mFirstConsentNotificationChannel.enroll(mContext, mConsentManager);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(true), eq(false)),
                times(1));
    }

    @Test
    public void enrollTest_adIdDisabledFirstConsentNotification() {
        doReturn(false).when(mConsentManager).isAdIdEnabled();

        mFirstConsentNotificationChannel.enroll(mContext, mConsentManager);

        verify(
                () ->
                        ConsentNotificationJobService.schedule(
                                any(Context.class), eq(false), eq(false)),
                times(1));
    }
}
