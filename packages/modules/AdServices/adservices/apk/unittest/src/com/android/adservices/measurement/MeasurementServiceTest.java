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
package com.android.adservices.measurement;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.download.MddJobService;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.AppImportanceFilter;
import com.android.adservices.service.common.PackageChangedReceiver;
import com.android.adservices.service.consent.AdServicesApiConsent;
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.enrollment.EnrollmentData;
import com.android.adservices.service.measurement.DeleteExpiredJobService;
import com.android.adservices.service.measurement.DeleteUninstalledJobService;
import com.android.adservices.service.measurement.MeasurementImpl;
import com.android.adservices.service.measurement.attribution.AttributionJobService;
import com.android.adservices.service.measurement.registration.AsyncRegistrationQueueJobService;
import com.android.adservices.service.measurement.reporting.AggregateFallbackReportingJobService;
import com.android.adservices.service.measurement.reporting.AggregateReportingJobService;
import com.android.adservices.service.measurement.reporting.EventFallbackReportingJobService;
import com.android.adservices.service.measurement.reporting.EventReportingJobService;
import com.android.compatibility.common.util.TestUtils;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.List;

/** Unit test for {@link com.android.adservices.measurement.MeasurementService}. */
public class MeasurementServiceTest {
    @Mock ConsentManager mMockConsentManager;
    @Mock Flags mMockFlags;
    @Mock MeasurementImpl mMockMeasurementImpl;
    @Mock EnrollmentDao mMockEnrollmentDao;
    @Mock AppImportanceFilter mMockAppImportanceFilter;

    private static final EnrollmentData ENROLLMENT =
            new EnrollmentData.Builder()
                    .setEnrollmentId("E1")
                    .setCompanyId("1001")
                    .setSdkNames("sdk1")
                    .setAttributionSourceRegistrationUrl(List.of("https://test.com/source"))
                    .setAttributionTriggerRegistrationUrl(List.of("https://test.com/trigger"))
                    .setAttributionReportingUrl(List.of("https://test.com"))
                    .setRemarketingResponseBasedRegistrationUrl(List.of("https://test.com"))
                    .setEncryptionKeyUrl(List.of("https://test.com/keys"))
                    .build();

    /** Setup for tests */
    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    /** Test kill switch off with consent given */
    @Test
    public void testBindableMeasurementService_killSwitchOff_gaUxDisabled_consentGiven()
            throws Exception {
        runWithMocks(
                /* killSwitchOff */ false,
                /* consentGiven */ true,
                /* isGaUxEnabled */ false,
                () -> {
                    // Execute
                    final IBinder binder = onCreateAndOnBindService();

                    // Verification
                    assertNotNull(binder);
                    verify(mMockConsentManager, times(1)).getConsent();
                    ExtendedMockito.verify(
                            () -> PackageChangedReceiver.enableReceiver(any(Context.class), any()));
                    assertJobScheduled(/* timesCalled */ 1);
                });
    }

    /** Test kill switch off with consent revoked */
    @Test
    public void testBindableMeasurementService_killSwitchOff_gaUxDisabled_consentRevoked()
            throws Exception {
        runWithMocks(
                /* killSwitchOff */ false,
                /* consentRevoked */ false,
                /* isGaUxEnabled */ false,
                () -> {
                    // Execute
                    final IBinder binder = onCreateAndOnBindService();

                    // Verification
                    assertNotNull(binder);
                    verify(mMockConsentManager, times(1)).getConsent();
                    assertJobScheduled(/* timesCalled */ 0);
                });
    }

    /** Test kill switch on */
    @Test
    public void testBindableMeasurementService_killSwitchOn_gaUxDisabled() throws Exception {
        runWithMocks(
                /* killSwitchOn */ true,
                /* consentGiven */ true,
                /* isGaUxEnabled */ false,
                () -> {
                    // Execute
                    final IBinder binder = onCreateAndOnBindService();

                    // Verification
                    assertNull(binder);
                    verify(mMockConsentManager, never()).getConsent();
                    assertJobScheduled(/* timesCalled */ 0);
                });
    }

    /** Test kill switch off with consent given */
    @Test
    public void testBindableMeasurementService_killSwitchOff_gaUxEnabled_consentGiven()
            throws Exception {
        runWithMocks(
                /* killSwitchOff */ false,
                /* consentGiven */ true,
                /* isGaUxEnabled */ true,
                () -> {
                    // Execute
                    final IBinder binder = onCreateAndOnBindService();

                    // Verification
                    assertNotNull(binder);
                    verify(mMockConsentManager, never()).getConsent();
                    verify(mMockConsentManager, times(1))
                            .getConsent(eq(AdServicesApiType.MEASUREMENTS));
                    ExtendedMockito.verify(
                            () -> PackageChangedReceiver.enableReceiver(any(Context.class), any()));
                    assertJobScheduled(/* timesCalled */ 1);
                });
    }

    /** Test kill switch off with consent revoked */
    @Test
    public void testBindableMeasurementService_killSwitchOff_gaUxEnabled_consentRevoked()
            throws Exception {
        runWithMocks(
                /* killSwitchOff */ false,
                /* consentRevoked */ false,
                /* isGaUxEnabled */ true,
                () -> {
                    // Execute
                    final IBinder binder = onCreateAndOnBindService();

                    // Verification
                    assertNotNull(binder);
                    verify(mMockConsentManager, never()).getConsent();
                    verify(mMockConsentManager, times(1))
                            .getConsent(eq(AdServicesApiType.MEASUREMENTS));
                    assertJobScheduled(/* timesCalled */ 0);
                });
    }

    /** Test kill switch on */
    @Test
    public void testBindableMeasurementService_killSwitchOn_gaUxEnabled() throws Exception {
        runWithMocks(
                /* killSwitchOn */ true,
                /* consentGiven */ true,
                /* isGaUxEnabled */ false,
                () -> {
                    // Execute
                    final IBinder binder = onCreateAndOnBindService();

                    // Verification
                    assertNull(binder);
                    verify(mMockConsentManager, never()).getConsent();
                    verify(mMockConsentManager, never()).getConsent(any());
                    assertJobScheduled(/* timesCalled */ 0);
                });
    }

    private Intent getIntentForMeasurementService() {
        return new Intent(ApplicationProvider.getApplicationContext(), MeasurementService.class);
    }

    private IBinder onCreateAndOnBindService() {
        MeasurementService spyMeasurementService = spy(new MeasurementService());
        doReturn(mock(PackageManager.class)).when(spyMeasurementService).getPackageManager();
        spyMeasurementService.onCreate();
        return spyMeasurementService.onBind(getIntentForMeasurementService());
    }

    private void runWithMocks(
            boolean killSwitchStatus,
            boolean consentStatus,
            boolean isGaUxEnabled,
            TestUtils.RunnableWithThrow execute)
            throws Exception {
        // Start a mockitoSession to mock static method
        final MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(AggregateReportingJobService.class)
                        .spyStatic(AggregateFallbackReportingJobService.class)
                        .spyStatic(AppImportanceFilter.class)
                        .spyStatic(AttributionJobService.class)
                        .spyStatic(ConsentManager.class)
                        .spyStatic(EnrollmentDao.class)
                        .spyStatic(EventReportingJobService.class)
                        .spyStatic(PackageChangedReceiver.class)
                        .spyStatic(EventFallbackReportingJobService.class)
                        .spyStatic(DeleteExpiredJobService.class)
                        .spyStatic(DeleteUninstalledJobService.class)
                        .spyStatic(MddJobService.class)
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(MeasurementImpl.class)
                        .spyStatic(AsyncRegistrationQueueJobService.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            doReturn(killSwitchStatus).when(mMockFlags).getMeasurementKillSwitch();
            doReturn(isGaUxEnabled).when(mMockFlags).getGaUxFeatureEnabled();

            ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

            ExtendedMockito.doReturn(mMockConsentManager)
                    .when(() -> ConsentManager.getInstance(any()));

            final AdServicesApiConsent mockConsent = mock(AdServicesApiConsent.class);
            doReturn(consentStatus).when(mockConsent).isGiven();

            if (isGaUxEnabled) {
                doReturn(mockConsent)
                        .when(mMockConsentManager)
                        .getConsent(eq(AdServicesApiType.MEASUREMENTS));
            } else {
                doReturn(mockConsent).when(mMockConsentManager).getConsent();
            }

            ExtendedMockito.doReturn(mMockEnrollmentDao)
                    .when(() -> EnrollmentDao.getInstance(any()));
            doReturn(ENROLLMENT)
                    .when(mMockEnrollmentDao)
                    .getEnrollmentDataFromMeasurementUrl(any());

            ExtendedMockito.doReturn(mMockMeasurementImpl)
                    .when(() -> MeasurementImpl.getInstance(any()));

            ExtendedMockito.doReturn(mMockAppImportanceFilter)
                    .when(() -> AppImportanceFilter.create(any(), anyInt(), any()));

            ExtendedMockito.doReturn(true)
                    .when(() -> PackageChangedReceiver.enableReceiver(any(Context.class), any()));
            ExtendedMockito.doNothing()
                    .when(() -> AggregateReportingJobService.scheduleIfNeeded(any(), anyBoolean()));
            ExtendedMockito.doNothing()
                    .when(
                            () ->
                                    AggregateFallbackReportingJobService.scheduleIfNeeded(
                                            any(), anyBoolean()));
            ExtendedMockito.doNothing()
                    .when(() -> AttributionJobService.scheduleIfNeeded(any(), anyBoolean()));

            ExtendedMockito.doNothing()
                    .when(() -> EventReportingJobService.scheduleIfNeeded(any(), anyBoolean()));
            ExtendedMockito.doNothing()
                    .when(
                            () ->
                                    EventFallbackReportingJobService.scheduleIfNeeded(
                                            any(), anyBoolean()));
            ExtendedMockito.doNothing()
                    .when(() -> DeleteExpiredJobService.scheduleIfNeeded(any(), anyBoolean()));
            ExtendedMockito.doNothing()
                    .when(() -> DeleteUninstalledJobService.scheduleIfNeeded(any(), anyBoolean()));
            ExtendedMockito.doReturn(true)
                    .when(() -> MddJobService.scheduleIfNeeded(any(), anyBoolean()));
            ExtendedMockito.doNothing()
                    .when(
                            () ->
                                    AsyncRegistrationQueueJobService.scheduleIfNeeded(
                                            any(), anyBoolean()));

            // Execute
            execute.run();
        } finally {
            session.finishMocking();
        }
    }

    private void assertJobScheduled(int timesCalled) {
        ExtendedMockito.verify(
                () -> AggregateReportingJobService.scheduleIfNeeded(any(), anyBoolean()),
                times(timesCalled));
        ExtendedMockito.verify(
                () -> AggregateFallbackReportingJobService.scheduleIfNeeded(any(), anyBoolean()),
                times(timesCalled));
        ExtendedMockito.verify(
                () -> AttributionJobService.scheduleIfNeeded(any(), anyBoolean()),
                times(timesCalled));
        ExtendedMockito.verify(
                () -> EventReportingJobService.scheduleIfNeeded(any(), anyBoolean()),
                times(timesCalled));
        ExtendedMockito.verify(
                () -> EventFallbackReportingJobService.scheduleIfNeeded(any(), anyBoolean()),
                times(timesCalled));
        ExtendedMockito.verify(
                () -> DeleteExpiredJobService.scheduleIfNeeded(any(), anyBoolean()),
                times(timesCalled));
        ExtendedMockito.verify(
                () -> DeleteUninstalledJobService.scheduleIfNeeded(any(), anyBoolean()),
                times(timesCalled));
        ExtendedMockito.verify(
                () -> MddJobService.scheduleIfNeeded(any(), anyBoolean()), times(timesCalled));
        ExtendedMockito.verify(
                () -> AsyncRegistrationQueueJobService.scheduleIfNeeded(any(), anyBoolean()),
                times(timesCalled));
    }
}
