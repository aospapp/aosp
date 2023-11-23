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

package com.android.adservices.service.measurement.reporting;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;

import com.android.adservices.service.Flags;
import com.android.adservices.service.measurement.EventSurfaceType;
import com.android.adservices.service.measurement.PrivacyParams;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.SourceFixture;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.concurrent.TimeUnit;

@RunWith(MockitoJUnitRunner.class)
public class EventReportWindowCalcDelegateTest {
    private static final String MALFORMED_WINDOW_CONFIG = "malformedCfg1,malformedCfg2";
    private static final String VALID_1H_1D_WINDOW_CONFIG = "3600,86400";
    private static final String VALID_2H_2D_WINDOW_CONFIG = "7200,172800";
    /** It's invalid because only 2 early reporting windows are permitted. */
    private static final String INVALID_1H_1D_2D_WINDOW_CONFIG = "3600,86400,172800";

    private static final long ONE_HOUR_IN_MILLIS = TimeUnit.HOURS.toMillis(1);

    @Mock private Flags mFlags;

    EventReportWindowCalcDelegate mEventReportWindowCalcDelegate;

    @Before
    public void setup() {
        doReturn(false).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        mEventReportWindowCalcDelegate = new EventReportWindowCalcDelegate(mFlags);
    }

    @Test
    public void getReportingTime_eventSourceAppDestination() {
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_eventSrcInstallAttributedAppDestinationTrigger1stWindow() {
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                sourceEventTime
                        + PrivacyParams.INSTALL_ATTR_EVENT_EARLY_REPORTING_WINDOW_MILLISECONDS[0]
                        + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_eventSrcInstallAttributedAppDestinationTrigger2ndWindow() {
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_eventSrcInstallAttributedWebDestinationTrigger1stWindow() {
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.WEB));
    }

    @Test
    public void getReportingTime_eventSrcInstallAttributedWebDestinationTrigger2ndWindow() {
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.WEB));
    }

    @Test
    public void getReportingTime_eventSourceWebDestination() {
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.WEB));
    }

    @Test
    public void getReportingTime_navigationSourceTriggerInFirstWindow() {
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(25);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime
                        + PrivacyParams.NAVIGATION_EARLY_REPORTING_WINDOW_MILLISECONDS[0]
                        + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_navigationSourceTriggerInSecondWindow() {
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(25);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime
                        + PrivacyParams.NAVIGATION_EARLY_REPORTING_WINDOW_MILLISECONDS[1]
                        + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_navigationSecondExpiry() {
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(2);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceExpiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_navigationLast() {
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(1);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(20);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceExpiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void testMaxReportCount() {
        Source eventSourceInstallNotAttributed =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setInstallAttributed(false)
                        .build();
        assertEquals(
                PrivacyParams.EVENT_SOURCE_MAX_REPORTS,
                mEventReportWindowCalcDelegate.getMaxReportCount(
                        eventSourceInstallNotAttributed, false));
        assertEquals(
                PrivacyParams.EVENT_SOURCE_MAX_REPORTS,
                mEventReportWindowCalcDelegate.getMaxReportCount(
                        eventSourceInstallNotAttributed, false));

        Source navigationSourceInstallNotAttributed =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setInstallAttributed(false)
                        .build();
        assertEquals(
                PrivacyParams.NAVIGATION_SOURCE_MAX_REPORTS,
                mEventReportWindowCalcDelegate.getMaxReportCount(
                        navigationSourceInstallNotAttributed, false));
        assertEquals(
                PrivacyParams.NAVIGATION_SOURCE_MAX_REPORTS,
                mEventReportWindowCalcDelegate.getMaxReportCount(
                        navigationSourceInstallNotAttributed, false));

        Source eventSourceInstallAttributed =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                PrivacyParams.INSTALL_ATTR_EVENT_SOURCE_MAX_REPORTS,
                mEventReportWindowCalcDelegate.getMaxReportCount(
                        eventSourceInstallAttributed, true));
        // Install attribution state does not matter for web destination
        assertEquals(
                PrivacyParams.EVENT_SOURCE_MAX_REPORTS,
                mEventReportWindowCalcDelegate.getMaxReportCount(
                        eventSourceInstallAttributed, false));

        Source navigationSourceInstallAttributed =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                PrivacyParams.NAVIGATION_SOURCE_MAX_REPORTS,
                mEventReportWindowCalcDelegate.getMaxReportCount(
                        navigationSourceInstallAttributed, true));
        assertEquals(
                PrivacyParams.NAVIGATION_SOURCE_MAX_REPORTS,
                mEventReportWindowCalcDelegate.getMaxReportCount(
                        navigationSourceInstallAttributed, true));
    }

    @Test
    public void getMaxReportCount_configuredConversionsNonInstall_returnsConfiguredCount() {
        // Setup
        doReturn(true).when(mFlags).getMeasurementEnableVtcConfigurableMaxEventReports();
        doReturn(3).when(mFlags).getMeasurementVtcConfigurableMaxEventReportsCount();
        Source nonInstallEventSource =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setInstallAttributed(false)
                        .build();

        // Execution & assertion
        Assert.assertEquals(
                3, mEventReportWindowCalcDelegate.getMaxReportCount(nonInstallEventSource, false));
    }

    @Test
    public void getMaxReportCount_configuredConversionsInstallCase_returnsConfiguredCount() {
        // Setup
        doReturn(true).when(mFlags).getMeasurementEnableVtcConfigurableMaxEventReports();
        doReturn(2).when(mFlags).getMeasurementVtcConfigurableMaxEventReportsCount();
        Source installEventSource =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setInstallAttributed(true)
                        .build();

        // Execution & assertion
        Assert.assertEquals(
                2, mEventReportWindowCalcDelegate.getMaxReportCount(installEventSource, true));
    }

    @Test
    public void getMaxReportCount_configuredConversionsToOneInstallCase_incrementConfiguredCount() {
        // Setup
        doReturn(true).when(mFlags).getMeasurementEnableVtcConfigurableMaxEventReports();
        doReturn(1).when(mFlags).getMeasurementVtcConfigurableMaxEventReportsCount();
        Source installEventSource =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setInstallAttributed(true)
                        .build();

        // Execution & assertion
        Assert.assertEquals(
                2, mEventReportWindowCalcDelegate.getMaxReportCount(installEventSource, true));
    }

    @Test
    public void getMaxReportCount_configuredConversionsToOneInstallCase_noEffectOnCtc() {
        // Setup
        doReturn(true).when(mFlags).getMeasurementEnableVtcConfigurableMaxEventReports();
        doReturn(2).when(mFlags).getMeasurementVtcConfigurableMaxEventReportsCount();
        Source navigationSource =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setInstallAttributed(false)
                        .build();

        // Execution & assertion
        Assert.assertEquals(
                3, mEventReportWindowCalcDelegate.getMaxReportCount(navigationSource, false));
    }

    @Test
    public void noiseReportingTimeByIndex_event() {
        long eventTime = System.currentTimeMillis();

        // Expected: 1 window at expiry
        Source eventSource10d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(10))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(10) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(10) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 1, /* isInstallCase */ false));

        // Expected: 1 window at expiry
        Source eventSource7d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(7))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(7) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource7d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(7) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource7d, /* windowIndex= */ 1, /* isInstallCase */ false));

        // Expected: 1 window at expiry
        Source eventSource2d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(2))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource2d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource2d, /* windowIndex= */ 1, /* isInstallCase */ false));
    }

    @Test
    public void getReportingTimeForNoising_eventSrcWithConfiguredReportingWindows() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_2H_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long eventTime = System.currentTimeMillis();

        // Expected: 1 window at expiry
        Source eventSource10d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(10))
                        .build();
        assertEquals(
                eventTime + ONE_HOUR_IN_MILLIS + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(1) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 1, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(10) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 2, /* isInstallCase */ false));
    }

    @Test
    public void getReportingTimeForNoising_eventSrcInstallAttWithConfiguredReportingWindows() {
        // Addition another window for install attribution is ignored when configurable windows
        // are applied.
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_2H_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long eventTime = System.currentTimeMillis();

        // Expected: 1 window at expiry
        long expiry = eventTime + TimeUnit.DAYS.toMillis(10);
        Source eventSource10d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventTime(eventTime)
                        .setEventReportWindow(expiry)
                        .setInstallCooldownWindow(expiry)
                        .build();
        assertEquals(
                eventTime + ONE_HOUR_IN_MILLIS + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 0, /* isInstallCase */ true));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(1) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 1, /* isInstallCase */ true));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(10) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 2, /* isInstallCase */ true));
    }

    @Test
    public void getReportingTimeForNoising_navigationSrcWithConfiguredReportingWindows() {
        // Addition another window for install attribution is ignored when configurable windows
        // are applied.
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_2H_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long eventTime = System.currentTimeMillis();

        // Expected: 1 window at expiry
        Source eventSource10d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(10))
                        .build();
        assertEquals(
                eventTime + TimeUnit.HOURS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 1, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(10) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 2, /* isInstallCase */ false));
    }

    @Test
    public void getReportingTimeForNoising_navigationSrcInstallAttWithConfiguredReportingWindows() {
        // Addition another window for install attribution is ignored when configurable windows
        // are applied.
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_2H_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long eventTime = System.currentTimeMillis();

        // Expected: 1 window at expiry
        long expiry = eventTime + TimeUnit.DAYS.toMillis(10);
        Source eventSource10d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventTime(eventTime)
                        .setEventReportWindow(expiry)
                        .setInstallCooldownWindow(expiry)
                        .build();
        assertEquals(
                eventTime + TimeUnit.HOURS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 0, /* isInstallCase */ true));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 1, /* isInstallCase */ true));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(10) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 2, /* isInstallCase */ true));
    }

    @Test
    public void reportingTimeByIndex_eventWithInstallAttribution() {
        long eventTime = System.currentTimeMillis();

        // Expected: 2 windows at 2d, expiry(10d)
        Source eventSource10d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setInstallCooldownWindow(TimeUnit.DAYS.toMillis(1))
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(10))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 0, /* isInstallCase */ true));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(10) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 1, /* isInstallCase */ true));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(10) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource10d, /* windowIndex= */ 2, /* isInstallCase */ true));

        // Expected: 1 window at 2d(expiry)
        Source eventSource2d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(2))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource2d, /* windowIndex= */ 0, /* isInstallCase */ true));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        eventSource2d, /* windowIndex= */ 1, /* isInstallCase */ true));
    }

    @Test
    public void reportingTimeByIndex_navigation() {
        long eventTime = System.currentTimeMillis();

        // Expected: 3 windows at 2d, 7d & expiry(20d)
        Source navigationSource20d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(20))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource20d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(7) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource20d, /* windowIndex= */ 1, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(20) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource20d, /* windowIndex= */ 2, /* isInstallCase */ false));

        // Expected: 2 windows at 2d & expiry(7d)
        Source navigationSource7d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(7))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource7d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(7) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource7d, /* windowIndex= */ 1, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(7) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource7d, /* windowIndex= */ 2, /* isInstallCase */ false));

        // Expected: 1 window at 2d(expiry)
        Source navigationSource2d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(2))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource2d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource2d, /* windowIndex= */ 1, /* isInstallCase */ false));
    }

    @Test
    public void reportingTimeByIndex_navigationWithInstallAttribution() {
        long eventTime = System.currentTimeMillis();

        // Expected: 3 windows at 2d, 7d & expiry(20d)
        Source navigationSource20d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setInstallCooldownWindow(TimeUnit.DAYS.toMillis(1))
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(20))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource20d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(7) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource20d, /* windowIndex= */ 1, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(20) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource20d, /* windowIndex= */ 2, /* isInstallCase */ false));

        // Expected: 2 windows at 2d & expiry(7d)
        Source navigationSource7d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setInstallCooldownWindow(TimeUnit.DAYS.toMillis(1))
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(7))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource7d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(7) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource7d, /* windowIndex= */ 1, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(7) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource7d, /* windowIndex= */ 2, /* isInstallCase */ false));

        // Expected: 1 window at 2d(expiry)
        Source navigationSource2d =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setInstallCooldownWindow(TimeUnit.DAYS.toMillis(1))
                        .setEventTime(eventTime)
                        .setEventReportWindow(eventTime + TimeUnit.DAYS.toMillis(2))
                        .build();
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource2d, /* windowIndex= */ 0, /* isInstallCase */ false));
        assertEquals(
                eventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                        navigationSource2d, /* windowIndex= */ 1, /* isInstallCase */ false));
    }

    @Test
    public void getReportingTime_nullWindowConfigEventSrcAppDest_fallbackToDefault() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_nullWindowConfigEventSrcInstallAttAppDestTrigger1stWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                sourceEventTime
                        + PrivacyParams.INSTALL_ATTR_EVENT_EARLY_REPORTING_WINDOW_MILLISECONDS[0]
                        + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_nullWindowConfigEventSrcInstallAttAppDestTrigger2ndWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_nullWindowConfigEventSrcInstallAttWebDestTrigger1stWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.WEB));
    }

    @Test
    public void getReportingTime_nullWindowConfigEventSrcInstallAttWebDestTrigger2ndWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.WEB));
    }

    @Test
    public void getReportingTime_nullWindowConfigEventSourceWebDestination_fallbackToDefault() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.WEB));
    }

    @Test
    public void getReportingTime_nullWindowConfigNavigationSourceTriggerInFirstWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(25);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime
                        + PrivacyParams.NAVIGATION_EARLY_REPORTING_WINDOW_MILLISECONDS[0]
                        + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_emptyWindowConfigNavigationSourceTriggerNextHour() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn("").when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn("").when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(25);
        long sourceEventTime = triggerTime - TimeUnit.HOURS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceExpiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_emptyWindowConfigEventSrcTriggerNextHour() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn("").when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn("").when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(25);
        long sourceEventTime = triggerTime - TimeUnit.HOURS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAppDestinations(
                                SourceFixture.ValidSourceParams.ATTRIBUTION_DESTINATIONS)
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceExpiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_emptyWindowConfigEventSrcInstallAttTriggerNextHour() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn("").when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn("").when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(25);
        long sourceEventTime = triggerTime - TimeUnit.HOURS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAppDestinations(
                                SourceFixture.ValidSourceParams.ATTRIBUTION_DESTINATIONS)
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                sourceEventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_emptyWindowConfigNavigationSrcInstallAttTriggerNextHour() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn("").when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn("").when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(25);
        long sourceEventTime = triggerTime - TimeUnit.HOURS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAppDestinations(
                                SourceFixture.ValidSourceParams.ATTRIBUTION_DESTINATIONS)
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                sourceExpiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_nullWindowConfigNavigationSourceTriggerInSecondWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(25);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime
                        + PrivacyParams.NAVIGATION_EARLY_REPORTING_WINDOW_MILLISECONDS[1]
                        + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_nullWindowConfigNavigationSecondExpiry() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(2);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceExpiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_nullWindowConfigNavigationLast_fallbackToDefault() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(null).when(mFlags).getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(1);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(20);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceExpiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_MalformedWindowConfigEventSourceAppDestination_returnsDefault() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_MalformedWindowConfigEventSrcInstallAttAppDestTrigger1stWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                sourceEventTime
                        + PrivacyParams.INSTALL_ATTR_EVENT_EARLY_REPORTING_WINDOW_MILLISECONDS[0]
                        + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_MalformedWindowConfigEventSrcInstallAttAppDestTrigger2ndWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_MalformedWindowConfigEventSrcInstallAttWebDestTrigger1stWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.WEB));
    }

    @Test
    public void getReportingTime_MalformedWindowConfigEventSrcInstallAttWebDestTrigger2ndWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.WEB));
    }

    @Test
    public void getReportingTime_MalformedWindowConfigEventSourceWebDestination() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.WEB));
    }

    @Test
    public void getReportingTime_MalformedWindowConfigNavigationSourceTriggerInFirstWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(25);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime
                        + PrivacyParams.NAVIGATION_EARLY_REPORTING_WINDOW_MILLISECONDS[0]
                        + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_MalformedWindowConfigNavigationSourceTriggerInSecondWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(25);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime
                        + PrivacyParams.NAVIGATION_EARLY_REPORTING_WINDOW_MILLISECONDS[1]
                        + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_MalformedWindowConfigNavigationSecondExpiry_fallbackToDefault() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(2);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(3);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceExpiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_MalformedWindowConfigNavigationLast_fallbackToDefault() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long sourceExpiryTime = triggerTime + TimeUnit.DAYS.toMillis(1);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(20);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(sourceExpiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceExpiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_validWindowConfigEventSourceTriggerIn1stWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_2H_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.MINUTES.toMillis(30);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime + TimeUnit.HOURS.toMillis(2),
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_validWindowConfigEventSourceTriggerIn2ndWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_2H_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.HOURS.toMillis(2);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime + TimeUnit.DAYS.toMillis(1) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_validWindowConfigEventSourceTriggerInLastWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(20);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(2);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_validWindowConfigNavigationSourceTriggerIn1stWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_2H_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(30);
        long sourceEventTime = triggerTime - TimeUnit.MINUTES.toMillis(30);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime + TimeUnit.HOURS.toMillis(3),
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_validWindowConfigNavigationSourceTriggerIn2ndWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_2H_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(20);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(1);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_validWindowConfigNavigationSourceTriggerInLastWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(MALFORMED_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_2H_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(20);
        long sourceEventTime = triggerTime - TimeUnit.DAYS.toMillis(5);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .setInstallAttributed(true)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_invalidWindowConfigEventSourceTriggerIn1stWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(INVALID_1H_1D_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(VALID_2H_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(10);
        long sourceEventTime = triggerTime - TimeUnit.MINUTES.toMillis(30);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.EVENT)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                expiryTime + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }

    @Test
    public void getReportingTime_invalidWindowConfigNavigationSourceTriggerIn1stWindow() {
        doReturn(true).when(mFlags).getMeasurementEnableConfigurableEventReportingWindows();
        doReturn(VALID_1H_1D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsVtcEarlyReportingWindows();
        doReturn(INVALID_1H_1D_2D_WINDOW_CONFIG)
                .when(mFlags)
                .getMeasurementEventReportsCtcEarlyReportingWindows();
        long triggerTime = System.currentTimeMillis();
        long expiryTime = triggerTime + TimeUnit.DAYS.toMillis(10);
        long sourceEventTime = triggerTime - TimeUnit.MINUTES.toMillis(30);
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setSourceType(Source.SourceType.NAVIGATION)
                        .setEventReportWindow(expiryTime)
                        .setEventTime(sourceEventTime)
                        .build();
        assertEquals(
                sourceEventTime + TimeUnit.DAYS.toMillis(2) + ONE_HOUR_IN_MILLIS,
                mEventReportWindowCalcDelegate.getReportingTime(
                        source, triggerTime, EventSurfaceType.APP));
    }
}
