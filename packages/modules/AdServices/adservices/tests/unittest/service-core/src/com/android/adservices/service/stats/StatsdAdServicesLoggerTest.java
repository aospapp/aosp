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

package com.android.adservices.service.stats;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_CONSENT_MIGRATED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_ATTRIBUTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_DEBUG_KEYS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_DEBUG_KEYS__ATTRIBUTION_TYPE__APP_WEB;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_DELAYED_SOURCE_REGISTRATION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_WIPEOUT;
import static com.android.adservices.service.stats.EpochComputationClassifierStats.ClassifierType;
import static com.android.adservices.service.stats.EpochComputationClassifierStats.OnDeviceClassifierStatus;
import static com.android.adservices.service.stats.EpochComputationClassifierStats.PrecomputedClassifierStatus;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.staticMockMarker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.android.adservices.errorlogging.AdServicesErrorStats;
import com.android.adservices.service.Flags;
import com.android.adservices.service.consent.DeviceRegionProvider;
import com.android.adservices.service.measurement.WipeoutStatus;
import com.android.adservices.service.measurement.attribution.AttributionStatus;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.MockedVoidMethod;
import com.android.modules.utils.build.SdkLevel;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;

public class StatsdAdServicesLoggerTest {
    // Atom IDs
    private static final int TOPICS_REPORTED_ATOM_ID = 535;
    private static final int EPOCH_COMPUTATION_CLASSIFIER_ATOM_ID = 537;
    private static final int TOPICS_REPORTED_COMPAT_ATOM_ID = 598;
    private static final int EPOCH_COMPUTATION_CLASSIFIER_COMPAT_ATOM_ID = 599;

    // Test params for GetTopicsReportedStats
    private static final int FILTERED_BLOCKED_TOPIC_COUNT = 0;
    private static final int DUPLICATE_TOPIC_COUNT = 0;
    private static final int TOPIC_IDS_COUNT = 1;

    private static final GetTopicsReportedStats TOPICS_REPORTED_STATS_DATA =
            GetTopicsReportedStats.builder()
                    .setFilteredBlockedTopicCount(FILTERED_BLOCKED_TOPIC_COUNT)
                    .setDuplicateTopicCount(DUPLICATE_TOPIC_COUNT)
                    .setTopicIdsCount(TOPIC_IDS_COUNT)
                    .build();

    // Test params for EpochComputationClassifierStats
    private static final ImmutableList<Integer> TOPIC_IDS = ImmutableList.of(10230, 10227);
    private static final int BUILD_ID = 8;
    private static final String ASSET_VERSION = "2";

    private static final EpochComputationClassifierStats EPOCH_COMPUTATION_CLASSIFIER_STATS_DATA =
            EpochComputationClassifierStats.builder()
                    .setTopicIds(TOPIC_IDS)
                    .setBuildId(BUILD_ID)
                    .setAssetVersion(ASSET_VERSION)
                    .setClassifierType(ClassifierType.ON_DEVICE_CLASSIFIER)
                    .setOnDeviceClassifierStatus(
                            OnDeviceClassifierStatus.ON_DEVICE_CLASSIFIER_STATUS_SUCCESS)
                    .setPrecomputedClassifierStatus(
                            PrecomputedClassifierStatus.PRECOMPUTED_CLASSIFIER_STATUS_NOT_INVOKED)
                    .build();

    private MockitoSession mMockitoSession;
    private StatsdAdServicesLogger mLogger;
    @Mock private Flags mFlags;

    @Before
    public void setUp() {
        mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(SdkLevel.class)
                        .mockStatic(AdServicesStatsLog.class)
                        .initMocks(this)
                        .spyStatic(DeviceRegionProvider.class)
                        .startMocking();

        mLogger = new StatsdAdServicesLogger(mFlags);
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testLogGetTopicsReportedStats_tPlus() {
        // Mocks
        when(mFlags.getCompatLoggingKillSwitch()).thenReturn(false);
        ExtendedMockito.doReturn(true).when(SdkLevel::isAtLeastT);
        ExtendedMockito.doNothing()
                .when(() -> AdServicesStatsLog.write(anyInt(), anyInt(), anyInt(), anyInt()));
        ExtendedMockito.doNothing()
                .when(
                        () ->
                                AdServicesStatsLog.write(
                                        anyInt(), any(int[].class), anyInt(), anyInt(), anyInt()));

        // Invoke logging call
        mLogger.logGetTopicsReportedStats(TOPICS_REPORTED_STATS_DATA);

        // Verify compat logging
        ExtendedMockito.verify(
                () ->
                        AdServicesStatsLog.write(
                                TOPICS_REPORTED_COMPAT_ATOM_ID,
                                FILTERED_BLOCKED_TOPIC_COUNT,
                                DUPLICATE_TOPIC_COUNT,
                                TOPIC_IDS_COUNT));
        // Verify T+ logging
        ExtendedMockito.verify(
                () ->
                        AdServicesStatsLog.write(
                                TOPICS_REPORTED_ATOM_ID,
                                /* topic_ids = */ new int[] {},
                                FILTERED_BLOCKED_TOPIC_COUNT,
                                DUPLICATE_TOPIC_COUNT,
                                TOPIC_IDS_COUNT));

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void testLogGetTopicsReportedStats_tPlus_noCompatLoggingDueToKillSwitch() {
        // Mocks
        when(mFlags.getCompatLoggingKillSwitch()).thenReturn(true);
        ExtendedMockito.doReturn(true).when(SdkLevel::isAtLeastT);
        ExtendedMockito.doNothing()
                .when(
                        () ->
                                AdServicesStatsLog.write(
                                        anyInt(), any(int[].class), anyInt(), anyInt(), anyInt()));

        // Invoke logging call
        mLogger.logGetTopicsReportedStats(TOPICS_REPORTED_STATS_DATA);

        // Verify T+ logging only
        ExtendedMockito.verify(
                () ->
                        AdServicesStatsLog.write(
                                TOPICS_REPORTED_ATOM_ID,
                                /* topic_ids = */ new int[] {},
                                FILTERED_BLOCKED_TOPIC_COUNT,
                                DUPLICATE_TOPIC_COUNT,
                                TOPIC_IDS_COUNT));

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void testLogGetTopicsReportedStats_sMinus() {
        // Mocks
        when(mFlags.getCompatLoggingKillSwitch()).thenReturn(false);
        ExtendedMockito.doReturn(false).when(SdkLevel::isAtLeastT);
        ExtendedMockito.doNothing()
                .when(() -> AdServicesStatsLog.write(anyInt(), anyInt(), anyInt(), anyInt()));

        // Invoke logging call
        mLogger.logGetTopicsReportedStats(TOPICS_REPORTED_STATS_DATA);

        // Verify only compat logging took place
        ExtendedMockito.verify(
                () ->
                        AdServicesStatsLog.write(
                                TOPICS_REPORTED_COMPAT_ATOM_ID,
                                FILTERED_BLOCKED_TOPIC_COUNT,
                                DUPLICATE_TOPIC_COUNT,
                                TOPIC_IDS_COUNT));

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void testLogGetTopicsReportedStats_sMinus_noLoggingDueToKillSwitch() {
        // Mocks
        when(mFlags.getCompatLoggingKillSwitch()).thenReturn(true);
        ExtendedMockito.doReturn(false).when(SdkLevel::isAtLeastT);

        // Invoke logging call
        mLogger.logGetTopicsReportedStats(TOPICS_REPORTED_STATS_DATA);

        // No compat (and T+) logging should happen
        verifyZeroInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void testLogEpochComputationClassifierStats_tPlus() {
        // Mocks
        when(mFlags.getCompatLoggingKillSwitch()).thenReturn(false);
        ExtendedMockito.doReturn(true).when(SdkLevel::isAtLeastT);
        ExtendedMockito.doNothing()
                .when(
                        () ->
                                AdServicesStatsLog.write(
                                        anyInt(),
                                        any(byte[].class),
                                        anyInt(),
                                        anyString(),
                                        anyInt(),
                                        anyInt(),
                                        anyInt()));
        ExtendedMockito.doNothing()
                .when(
                        () ->
                                AdServicesStatsLog.write(
                                        anyInt(),
                                        any(int[].class),
                                        anyInt(),
                                        anyString(),
                                        anyInt(),
                                        anyInt(),
                                        anyInt()));

        // Invoke logging call
        mLogger.logEpochComputationClassifierStats(EPOCH_COMPUTATION_CLASSIFIER_STATS_DATA);

        // Verify compat logging
        ExtendedMockito.verify(
                () ->
                        AdServicesStatsLog.write(
                                eq(EPOCH_COMPUTATION_CLASSIFIER_COMPAT_ATOM_ID),
                                any(byte[].class), // topic ids converted into byte[]
                                eq(BUILD_ID),
                                eq(ASSET_VERSION),
                                eq(ClassifierType.ON_DEVICE_CLASSIFIER.getCompatLoggingValue()),
                                eq(
                                        OnDeviceClassifierStatus.ON_DEVICE_CLASSIFIER_STATUS_SUCCESS
                                                .getCompatLoggingValue()),
                                eq(
                                        PrecomputedClassifierStatus
                                                .PRECOMPUTED_CLASSIFIER_STATUS_NOT_INVOKED
                                                .getCompatLoggingValue())));
        // Verify T+ logging
        ExtendedMockito.verify(
                () ->
                        AdServicesStatsLog.write(
                                EPOCH_COMPUTATION_CLASSIFIER_ATOM_ID,
                                TOPIC_IDS.stream().mapToInt(Integer::intValue).toArray(),
                                BUILD_ID,
                                ASSET_VERSION,
                                ClassifierType.ON_DEVICE_CLASSIFIER.getLoggingValue(),
                                OnDeviceClassifierStatus.ON_DEVICE_CLASSIFIER_STATUS_SUCCESS
                                        .getLoggingValue(),
                                PrecomputedClassifierStatus
                                        .PRECOMPUTED_CLASSIFIER_STATUS_NOT_INVOKED
                                        .getLoggingValue()));

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void testLogEpochComputationClassifierStats_tPlus_noCompatLoggingDueToKillSwitch() {
        // Mocks
        when(mFlags.getCompatLoggingKillSwitch()).thenReturn(true);
        ExtendedMockito.doReturn(true).when(SdkLevel::isAtLeastT);
        ExtendedMockito.doNothing()
                .when(
                        () ->
                                AdServicesStatsLog.write(
                                        anyInt(),
                                        any(int[].class),
                                        anyInt(),
                                        anyString(),
                                        anyInt(),
                                        anyInt(),
                                        anyInt()));

        // Invoke logging call
        mLogger.logEpochComputationClassifierStats(EPOCH_COMPUTATION_CLASSIFIER_STATS_DATA);

        // Verify T+ logging
        ExtendedMockito.verify(
                () ->
                        AdServicesStatsLog.write(
                                EPOCH_COMPUTATION_CLASSIFIER_ATOM_ID,
                                TOPIC_IDS.stream().mapToInt(Integer::intValue).toArray(),
                                BUILD_ID,
                                ASSET_VERSION,
                                ClassifierType.ON_DEVICE_CLASSIFIER.getLoggingValue(),
                                OnDeviceClassifierStatus.ON_DEVICE_CLASSIFIER_STATUS_SUCCESS
                                        .getLoggingValue(),
                                PrecomputedClassifierStatus
                                        .PRECOMPUTED_CLASSIFIER_STATUS_NOT_INVOKED
                                        .getLoggingValue()));

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void testLogEpochComputationClassifierStats_sMinus() {
        // Mocks
        when(mFlags.getCompatLoggingKillSwitch()).thenReturn(false);
        ExtendedMockito.doReturn(false).when(SdkLevel::isAtLeastT);
        ExtendedMockito.doNothing()
                .when(
                        () ->
                                AdServicesStatsLog.write(
                                        anyInt(),
                                        any(byte[].class),
                                        anyInt(),
                                        anyString(),
                                        anyInt(),
                                        anyInt(),
                                        anyInt()));

        // Invoke logging call
        mLogger.logEpochComputationClassifierStats(EPOCH_COMPUTATION_CLASSIFIER_STATS_DATA);

        // Verify only compat logging took place
        ExtendedMockito.verify(
                () ->
                        AdServicesStatsLog.write(
                                eq(EPOCH_COMPUTATION_CLASSIFIER_COMPAT_ATOM_ID),
                                any(byte[].class), // topic ids converted into byte[]
                                eq(BUILD_ID),
                                eq(ASSET_VERSION),
                                eq(ClassifierType.ON_DEVICE_CLASSIFIER.getCompatLoggingValue()),
                                eq(
                                        OnDeviceClassifierStatus.ON_DEVICE_CLASSIFIER_STATUS_SUCCESS
                                                .getCompatLoggingValue()),
                                eq(
                                        PrecomputedClassifierStatus
                                                .PRECOMPUTED_CLASSIFIER_STATUS_NOT_INVOKED
                                                .getCompatLoggingValue())));

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void testLogEpochComputationClassifierStats_sMinus_noLoggingDueToKillSwitch() {
        // Mocks
        when(mFlags.getCompatLoggingKillSwitch()).thenReturn(true);
        ExtendedMockito.doReturn(false).when(SdkLevel::isAtLeastT);

        // Invoke logging call
        mLogger.logEpochComputationClassifierStats(EPOCH_COMPUTATION_CLASSIFIER_STATS_DATA);

        // No compat (and T+) logging should happen
        verifyZeroInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void logMeasurementDebugKeysMatch_success() {
        final String enrollmentId = "EnrollmentId";
        long hashedValue = 5000L;
        long hashLimit = 10000L;
        int attributionType = AD_SERVICES_MEASUREMENT_DEBUG_KEYS__ATTRIBUTION_TYPE__APP_WEB;
        MsmtDebugKeysMatchStats stats =
                MsmtDebugKeysMatchStats.builder()
                        .setAdTechEnrollmentId(enrollmentId)
                        .setMatched(true)
                        .setAttributionType(attributionType)
                        .setDebugJoinKeyHashedValue(hashedValue)
                        .setDebugJoinKeyHashLimit(hashLimit)
                        .build();
        ExtendedMockito.doNothing()
                .when(
                        () ->
                                AdServicesStatsLog.write(
                                        anyInt(),
                                        anyString(),
                                        anyInt(),
                                        anyBoolean(),
                                        anyLong(),
                                        anyLong()));

        // Invoke logging call
        mLogger.logMeasurementDebugKeysMatch(stats);

        // Verify only compat logging took place
        MockedVoidMethod writeInvocation =
                () ->
                        AdServicesStatsLog.write(
                                eq(AD_SERVICES_MEASUREMENT_DEBUG_KEYS),
                                eq(enrollmentId),
                                // topic ids converted into byte[]
                                eq(attributionType),
                                eq(true),
                                eq(hashedValue),
                                eq(hashLimit));
        ExtendedMockito.verify(writeInvocation);

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void logMeasurementAttribution_success() {
        MeasurementAttributionStats stats =
                new MeasurementAttributionStats.Builder()
                        .setCode(AD_SERVICES_MEASUREMENT_ATTRIBUTION)
                        .setSourceType(AttributionStatus.SourceType.EVENT.ordinal())
                        .setSurfaceType(AttributionStatus.AttributionSurface.APP_WEB.ordinal())
                        .setResult(AttributionStatus.AttributionResult.SUCCESS.ordinal())
                        .setFailureType(AttributionStatus.FailureType.UNKNOWN.ordinal())
                        .setSourceDerived(false)
                        .setInstallAttribution(true)
                        .setAttributionDelay(100L)
                        .build();
        ExtendedMockito.doNothing()
                .when(
                        () ->
                                AdServicesStatsLog.write(
                                        anyInt(),
                                        anyInt(),
                                        anyInt(),
                                        anyInt(),
                                        anyInt(),
                                        anyBoolean(),
                                        anyBoolean(),
                                        anyLong()));

        // Invoke logging call
        mLogger.logMeasurementAttributionStats(stats);

        // Verify only compat logging took place
        MockedVoidMethod writeInvocation =
                () ->
                        AdServicesStatsLog.write(
                                eq(AD_SERVICES_MEASUREMENT_ATTRIBUTION),
                                eq(AttributionStatus.SourceType.EVENT.ordinal()),
                                eq(AttributionStatus.AttributionSurface.APP_WEB.ordinal()),
                                eq(AttributionStatus.AttributionResult.SUCCESS.ordinal()),
                                eq(AttributionStatus.FailureType.UNKNOWN.ordinal()),
                                eq(false),
                                eq(true),
                                eq(100L));

        ExtendedMockito.verify(writeInvocation);

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void logMeasurementWipeout_success() {
        MeasurementWipeoutStats stats =
                new MeasurementWipeoutStats.Builder()
                        .setCode(AD_SERVICES_MEASUREMENT_WIPEOUT)
                        .setWipeoutType(WipeoutStatus.WipeoutType.CONSENT_FLIP.ordinal())
                        .build();
        ExtendedMockito.doNothing().when(() -> AdServicesStatsLog.write(anyInt(), anyInt()));

        // Invoke logging call
        mLogger.logMeasurementWipeoutStats(stats);

        // Verify only compat logging took place
        MockedVoidMethod writeInvocation =
                () ->
                        AdServicesStatsLog.write(
                                eq(AD_SERVICES_MEASUREMENT_WIPEOUT),
                                eq(WipeoutStatus.WipeoutType.CONSENT_FLIP.ordinal()));

        ExtendedMockito.verify(writeInvocation);

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void logMeasurementDelayedSourceRegistration_success() {
        int UnknownEnumValue = 0;
        long registrationDelay = 500L;
        MeasurementDelayedSourceRegistrationStats stats =
                new MeasurementDelayedSourceRegistrationStats.Builder()
                        .setCode(AD_SERVICES_MEASUREMENT_DELAYED_SOURCE_REGISTRATION)
                        .setRegistrationStatus(UnknownEnumValue)
                        .setRegistrationDelay(registrationDelay)
                        .build();
        ExtendedMockito.doNothing()
                .when(() -> AdServicesStatsLog.write(anyInt(), anyInt(), anyLong()));

        // Invoke logging call
        mLogger.logMeasurementDelayedSourceRegistrationStats(stats);

        // Verify only compat logging took place
        MockedVoidMethod writeInvocation =
                () ->
                        AdServicesStatsLog.write(
                                eq(AD_SERVICES_MEASUREMENT_DELAYED_SOURCE_REGISTRATION),
                                eq(UnknownEnumValue),
                                eq(registrationDelay));

        ExtendedMockito.verify(writeInvocation);

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void logAdServicesError_success() {
        String className = "TopicsService";
        String methodName = "getTopics";
        int lineNumber = 100;
        String exceptionName = "SQLiteException";
        AdServicesErrorStats stats =
                AdServicesErrorStats.builder()
                        .setErrorCode(
                                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION)
                        .setPpapiName(AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS)
                        .setClassName(className)
                        .setMethodName(methodName)
                        .setLineNumber(lineNumber)
                        .setLastObservedExceptionName(exceptionName)
                        .build();
        ExtendedMockito.doNothing()
                .when(
                        () ->
                                AdServicesStatsLog.write(
                                        anyInt(),
                                        anyInt(),
                                        anyInt(),
                                        anyString(),
                                        anyString(),
                                        anyInt(),
                                        anyString()));

        // Invoke logging call
        mLogger.logAdServicesError(stats);

        // Verify only compat logging took place
        MockedVoidMethod writeInvocation =
                () ->
                        AdServicesStatsLog.write(
                                eq(AD_SERVICES_ERROR_REPORTED),
                                eq(AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION),
                                eq(AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS),
                                eq(className),
                                eq(methodName),
                                eq(lineNumber),
                                eq(exceptionName));
        ExtendedMockito.verify(writeInvocation);

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void logConsentMigrationStats_success() {
        when(mFlags.getAdservicesConsentMigrationLoggingEnabled()).thenReturn(true);
        ExtendedMockito.doNothing()
                .when(
                        () ->
                                AdServicesStatsLog.write(
                                        anyInt(),
                                        anyBoolean(),
                                        anyBoolean(),
                                        anyBoolean(),
                                        anyBoolean(),
                                        anyInt(),
                                        anyInt(),
                                        anyInt()));

        ConsentMigrationStats consentMigrationStats =
                ConsentMigrationStats.builder()
                        .setTopicsConsent(true)
                        .setFledgeConsent(true)
                        .setMsmtConsent(true)
                        .setDefaultConsent(true)
                        .setMigrationStatus(
                                ConsentMigrationStats.MigrationStatus
                                        .SUCCESS_WITH_SHARED_PREF_UPDATED)
                        .setMigrationType(
                                ConsentMigrationStats.MigrationType.APPSEARCH_TO_SYSTEM_SERVICE)
                        .setRegion(2)
                        .build();

        // Invoke logging call
        mLogger.logConsentMigrationStats(consentMigrationStats);

        // Verify only compat logging took place
        MockedVoidMethod writeInvocation =
                () ->
                        AdServicesStatsLog.write(
                                eq(AD_SERVICES_CONSENT_MIGRATED),
                                eq(true),
                                eq(true),
                                eq(true),
                                eq(true),
                                eq(2),
                                eq(2),
                                eq(2));
        ExtendedMockito.verify(writeInvocation);

        verifyNoMoreInteractions(staticMockMarker(AdServicesStatsLog.class));
    }

    @Test
    public void logConsentMigrationStats_disabled() {
        when(mFlags.getAdservicesConsentMigrationLoggingEnabled()).thenReturn(false);

        ConsentMigrationStats consentMigrationStats =
                ConsentMigrationStats.builder()
                        .setTopicsConsent(true)
                        .setFledgeConsent(true)
                        .setMsmtConsent(true)
                        .setDefaultConsent(true)
                        .setMigrationStatus(
                                ConsentMigrationStats.MigrationStatus
                                        .SUCCESS_WITH_SHARED_PREF_UPDATED)
                        .setMigrationType(
                                ConsentMigrationStats.MigrationType.APPSEARCH_TO_SYSTEM_SERVICE)
                        .setRegion(2)
                        .build();

        // Invoke logging call
        mLogger.logConsentMigrationStats(consentMigrationStats);

        verifyZeroInteractions(staticMockMarker(AdServicesStatsLog.class));
    }
}
