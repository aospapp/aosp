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

package com.android.adservices.service.ui;

import static com.android.adservices.service.PhFlags.KEY_ADSERVICES_ENABLED;
import static com.android.adservices.service.PhFlags.KEY_CONSENT_NOTIFICATION_DEBUG_MODE;
import static com.android.adservices.service.PhFlags.KEY_GA_UX_FEATURE_ENABLED;
import static com.android.adservices.service.PhFlags.KEY_U18_UX_ENABLED;
import static com.android.adservices.service.consent.ConsentManager.MANUAL_INTERACTIONS_RECORDED;
import static com.android.adservices.service.consent.ConsentManager.NO_MANUAL_INTERACTIONS_RECORDED;
import static com.android.adservices.service.consent.ConsentManager.UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;

import com.android.adservices.service.consent.AdServicesApiConsent;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.ui.data.UxStatesManager;
import com.android.adservices.service.ui.enrollment.BetaUxEnrollmentChannelCollection;
import com.android.adservices.service.ui.enrollment.GaUxEnrollmentChannelCollection;
import com.android.adservices.service.ui.enrollment.U18UxEnrollmentChannelCollection;
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

public class UxEngineTest {
    @Mock private UxStatesManager mUxStatesManager;
    @Mock private ConsentManager mConsentManager;
    @Mock private AdServicesApiConsent mAdServicesApiConsent;
    private MockitoSession mStaticMockSession;
    private UxEngine mUxEngine;

    @Before
    public void setup() throws IOException {
        MockitoAnnotations.initMocks(this);

        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(UxStatesManager.class)
                        .spyStatic(ConsentManager.class)
                        .spyStatic(AdServicesApiConsent.class)
                        .strictness(Strictness.WARN)
                        .initMocks(this)
                        .startMocking();

        // Default states for testing supported UXs.
        doReturn(true).when(mConsentManager).isEntryPointEnabled();
        doReturn(true).when(mUxStatesManager).getFlag(KEY_ADSERVICES_ENABLED);

        mUxEngine = new UxEngine(mConsentManager, mUxStatesManager);
    }

    @After
    public void teardown() throws IOException {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    // ====================================================================
    // getEligibleUxCollectionTest
    // ====================================================================
    @Test
    public void getEligibleUxCollectionTest_adServicesDisabled() {
        doReturn(false).when(mUxStatesManager).getFlag(KEY_ADSERVICES_ENABLED);

        assertThat(mUxEngine.getEligibleUxCollection())
                .isEqualTo(PrivacySandboxUxCollection.UNSUPPORTED_UX);
    }

    @Test
    public void getEligibleUxCollectionTest_entryPointDisabled() {
        doReturn(false).when(mConsentManager).isEntryPointEnabled();

        assertThat(mUxEngine.getEligibleUxCollection())
                .isEqualTo(PrivacySandboxUxCollection.UNSUPPORTED_UX);
    }

    @Test
    public void getEligibleUxCollectionTest_adultUserGaFlagOn() {
        doReturn(true).when(mUxStatesManager).getFlag(KEY_GA_UX_FEATURE_ENABLED);
        doReturn(true).when(mConsentManager).isAdultAccount();

        assertThat(mUxEngine.getEligibleUxCollection()).isEqualTo(PrivacySandboxUxCollection.GA_UX);
    }

    @Test
    public void getEligibleUxCollectionTest_adultUserGaFlagOff() {
        doReturn(false).when(mUxStatesManager).getFlag(KEY_GA_UX_FEATURE_ENABLED);
        doReturn(true).when(mConsentManager).isAdultAccount();

        assertThat(mUxEngine.getEligibleUxCollection())
                .isEqualTo(PrivacySandboxUxCollection.BETA_UX);
    }

    @Test
    public void getEligibleUxCollectionTest_u18UserU18FlagOn() {
        doReturn(true).when(mUxStatesManager).getFlag(KEY_U18_UX_ENABLED);
        doReturn(true).when(mConsentManager).isU18Account();

        assertThat(mUxEngine.getEligibleUxCollection())
                .isEqualTo(PrivacySandboxUxCollection.U18_UX);
    }

    @Test
    public void getEligibleUxCollectionTest_u18UserU18FlagOff() {
        doReturn(false).when(mUxStatesManager).getFlag(KEY_U18_UX_ENABLED);
        doReturn(true).when(mConsentManager).isU18Account();

        assertThat(mUxEngine.getEligibleUxCollection())
                .isEqualTo(PrivacySandboxUxCollection.UNSUPPORTED_UX);
    }

    @Test
    public void getEligibleUxCollectionTest_gaAndU18Eligible() {
        doReturn(true).when(mUxStatesManager).getFlag(KEY_U18_UX_ENABLED);
        doReturn(true).when(mUxStatesManager).getFlag(KEY_GA_UX_FEATURE_ENABLED);
        doReturn(true).when(mConsentManager).isAdultAccount();
        doReturn(true).when(mConsentManager).isU18Account();

        // U18 UX should have higher priority than adult UX.
        assertThat(mUxEngine.getEligibleUxCollection())
                .isEqualTo(PrivacySandboxUxCollection.U18_UX);
    }

    @Test
    public void getEligibleUxCollectionTest_betaAndU18Eligible() {
        doReturn(true).when(mUxStatesManager).getFlag(KEY_U18_UX_ENABLED);
        doReturn(false).when(mUxStatesManager).getFlag(KEY_GA_UX_FEATURE_ENABLED);
        doReturn(true).when(mConsentManager).isAdultAccount();
        doReturn(true).when(mConsentManager).isU18Account();

        // U18 UX should have higher priority than adult UX.
        assertThat(mUxEngine.getEligibleUxCollection())
                .isEqualTo(PrivacySandboxUxCollection.U18_UX);
    }

    @Test
    public void getEligibleUxCollectionTest_nonAdultAndNonU18() {
        doReturn(false).when(mConsentManager).isAdultAccount();
        doReturn(false).when(mConsentManager).isU18Account();

        assertThat(mUxEngine.getEligibleUxCollection())
                .isEqualTo(PrivacySandboxUxCollection.UNSUPPORTED_UX);
    }

    // ====================================================================
    // getEligibleEnrollmentChannelTest_gaUx
    // ====================================================================
    @Test
    public void getEligibleEnrollmentChannelTest_gaUxConsentDebugModeOn() {
        doReturn(true).when(mUxStatesManager).getFlag(KEY_CONSENT_NOTIFICATION_DEBUG_MODE);

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.GA_UX))
                .isEqualTo(GaUxEnrollmentChannelCollection.CONSENT_NOTIFICATION_DEBUG_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_gaUxNotificationAlreadyDisplayed() {
        doReturn(true).when(mConsentManager).wasGaUxNotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.GA_UX))
                .isEqualTo(GaUxEnrollmentChannelCollection.ALREADY_ENROLLED_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_gaUxNotificationNeverDisplayed() {
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.GA_UX))
                .isEqualTo(GaUxEnrollmentChannelCollection.FIRST_CONSENT_NOTIFICATION_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_gaUxManaulOptInBetaUser() {
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(mAdServicesApiConsent).when(mConsentManager).getConsent();
        doReturn(true).when(mAdServicesApiConsent).isGiven();
        doReturn(MANUAL_INTERACTIONS_RECORDED)
                .when(mConsentManager)
                .getUserManualInteractionWithConsent();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.GA_UX))
                .isEqualTo(GaUxEnrollmentChannelCollection.RECONSENT_NOTIFICATION_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_gaUxNoManaulOptInBetaUser() {
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(mAdServicesApiConsent).when(mConsentManager).getConsent();
        doReturn(true).when(mAdServicesApiConsent).isGiven();
        doReturn(NO_MANUAL_INTERACTIONS_RECORDED)
                .when(mConsentManager)
                .getUserManualInteractionWithConsent();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.GA_UX))
                .isEqualTo(GaUxEnrollmentChannelCollection.RECONSENT_NOTIFICATION_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_gaUxUnknownOptInBetaUser() {
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(mAdServicesApiConsent).when(mConsentManager).getConsent();
        doReturn(true).when(mAdServicesApiConsent).isGiven();
        doReturn(UNKNOWN).when(mConsentManager).getUserManualInteractionWithConsent();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.GA_UX))
                .isEqualTo(GaUxEnrollmentChannelCollection.RECONSENT_NOTIFICATION_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_gaUxManaulOptOutBetaUser() {
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(mAdServicesApiConsent).when(mConsentManager).getConsent();
        doReturn(false).when(mAdServicesApiConsent).isGiven();
        doReturn(MANUAL_INTERACTIONS_RECORDED)
                .when(mConsentManager)
                .getUserManualInteractionWithConsent();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.GA_UX))
                .isNull();
    }

    @Test
    public void getEligibleEnrollmentChannelTest_gaUxNoManaulOptOutBetaUser() {
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(mAdServicesApiConsent).when(mConsentManager).getConsent();
        doReturn(false).when(mAdServicesApiConsent).isGiven();
        doReturn(NO_MANUAL_INTERACTIONS_RECORDED)
                .when(mConsentManager)
                .getUserManualInteractionWithConsent();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.GA_UX))
                .isEqualTo(GaUxEnrollmentChannelCollection.RECONSENT_NOTIFICATION_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_gaUxUnknownOptOutBetaUser() {
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();
        doReturn(mAdServicesApiConsent).when(mConsentManager).getConsent();
        doReturn(false).when(mAdServicesApiConsent).isGiven();
        doReturn(UNKNOWN).when(mConsentManager).getUserManualInteractionWithConsent();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.GA_UX))
                .isNull();
    }

    @Test
    public void getEligibleEnrollmentChannelTest_gaUxGraduationDisabled() {
        doReturn(true).when(mConsentManager).wasU18NotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.GA_UX))
                .isNull();
    }

    // ====================================================================
    // getEligibleEnrollmentChannelTest_betaUx
    // ====================================================================
    @Test
    public void getEligibleEnrollmentChannelTest_betaUxConsentDebugModeOn() {
        doReturn(true).when(mUxStatesManager).getFlag(KEY_CONSENT_NOTIFICATION_DEBUG_MODE);

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.BETA_UX))
                .isEqualTo(BetaUxEnrollmentChannelCollection.CONSENT_NOTIFICATION_DEBUG_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_betaUxNotificationAlreadyDisplayed() {
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.BETA_UX))
                .isEqualTo(BetaUxEnrollmentChannelCollection.ALREADY_ENROLLED_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_betaUxNotificationNeverDisplayed() {
        doReturn(false).when(mConsentManager).wasNotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.BETA_UX))
                .isEqualTo(BetaUxEnrollmentChannelCollection.FIRST_CONSENT_NOTIFICATION_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_betaUxU18User() {
        doReturn(true).when(mConsentManager).wasU18NotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.BETA_UX))
                .isNull();
    }

    // ====================================================================
    // getEligibleEnrollmentChannelTest_U18Ux
    // ====================================================================
    @Test
    public void getEligibleEnrollmentChannelTest_u18UxConsentDebugModeOn() {
        doReturn(true).when(mUxStatesManager).getFlag(KEY_CONSENT_NOTIFICATION_DEBUG_MODE);

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.U18_UX))
                .isEqualTo(U18UxEnrollmentChannelCollection.CONSENT_NOTIFICATION_DEBUG_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_u18UxNotificationAlreadyDisplayed() {
        doReturn(true).when(mConsentManager).wasU18NotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.U18_UX))
                .isEqualTo(U18UxEnrollmentChannelCollection.ALREADY_ENROLLED_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_u18UxNotificationNeverDisplayed() {
        doReturn(false).when(mConsentManager).wasU18NotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.U18_UX))
                .isEqualTo(U18UxEnrollmentChannelCollection.FIRST_CONSENT_NOTIFICATION_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_u18UxDetention() {
        doReturn(true).when(mConsentManager).wasGaUxNotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.U18_UX))
                .isEqualTo(U18UxEnrollmentChannelCollection.U18_DETENTION_CHANNEL);
    }

    @Test
    public void getEligibleEnrollmentChannelTest_u18UxBetaUser() {
        doReturn(true).when(mConsentManager).wasNotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.U18_UX))
                .isNull();
    }

    // =============================================================================================
    // getEligibleEnrollmentChannelTest_unsupportedUx
    // =============================================================================================
    @Test
    public void getEligibleEnrollmentChannelTest_unsupportedUxConsentDebugModeOn() {
        doReturn(true).when(mUxStatesManager).getFlag(KEY_CONSENT_NOTIFICATION_DEBUG_MODE);

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.UNSUPPORTED_UX))
                .isNull();
    }

    @Test
    public void getEligibleEnrollmentChannelTest_unsupportedUxNotificationNeverDisplayed() {
        doReturn(false).when(mConsentManager).wasU18NotificationDisplayed();
        doReturn(false).when(mConsentManager).wasNotificationDisplayed();
        doReturn(false).when(mConsentManager).wasGaUxNotificationDisplayed();

        assertThat(
                        mUxEngine.getEligibleEnrollmentChannelCollection(
                                PrivacySandboxUxCollection.UNSUPPORTED_UX))
                .isNull();
    }
}
