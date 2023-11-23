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

package com.android.adservices.data.measurement;

import static com.android.adservices.data.measurement.MeasurementTables.ALL_MSMT_TABLES;
import static com.android.adservices.data.measurement.MeasurementTables.AsyncRegistrationContract;
import static com.android.adservices.data.measurement.MeasurementTables.AttributionContract;
import static com.android.adservices.data.measurement.MeasurementTables.EventReportContract;
import static com.android.adservices.data.measurement.MeasurementTables.MSMT_TABLE_PREFIX;
import static com.android.adservices.data.measurement.MeasurementTables.SourceContract;
import static com.android.adservices.data.measurement.MeasurementTables.TriggerContract;
import static com.android.adservices.data.measurement.MeasurementTables.XnaIgnoredSourcesContract;
import static com.android.adservices.service.measurement.PrivacyParams.MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS;
import static com.android.adservices.service.measurement.SourceFixture.ValidSourceParams.SHARED_AGGREGATE_KEYS;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.adservices.measurement.DeletionRequest;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Pair;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.measurement.AsyncRegistrationFixture;
import com.android.adservices.service.measurement.AsyncRegistrationFixture.ValidAsyncRegistrationParams;
import com.android.adservices.service.measurement.Attribution;
import com.android.adservices.service.measurement.EventReport;
import com.android.adservices.service.measurement.EventSurfaceType;
import com.android.adservices.service.measurement.KeyValueData;
import com.android.adservices.service.measurement.KeyValueData.DataType;
import com.android.adservices.service.measurement.PrivacyParams;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.SourceFixture;
import com.android.adservices.service.measurement.Trigger;
import com.android.adservices.service.measurement.TriggerFixture;
import com.android.adservices.service.measurement.WebUtil;
import com.android.adservices.service.measurement.aggregation.AggregateEncryptionKey;
import com.android.adservices.service.measurement.aggregation.AggregateReport;
import com.android.adservices.service.measurement.aggregation.AggregateReportFixture;
import com.android.adservices.service.measurement.noising.SourceNoiseHandler;
import com.android.adservices.service.measurement.registration.AsyncRegistration;
import com.android.adservices.service.measurement.reporting.DebugReport;
import com.android.adservices.service.measurement.reporting.EventReportWindowCalcDelegate;
import com.android.adservices.service.measurement.util.UnsignedLong;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultiset;

import org.json.JSONException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class MeasurementDaoTest {
    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final Uri APP_TWO_SOURCES = Uri.parse("android-app://com.example1.two-sources");
    private static final Uri APP_ONE_SOURCE = Uri.parse("android-app://com.example2.one-source");
    private static final String DEFAULT_ENROLLMENT_ID = "enrollment-id";
    private static final Uri APP_TWO_PUBLISHER =
            Uri.parse("android-app://com.publisher2.two-sources");
    private static final Uri APP_ONE_PUBLISHER =
            Uri.parse("android-app://com.publisher1.one-source");
    private static final Uri APP_NO_PUBLISHER =
            Uri.parse("android-app://com.publisher3.no-sources");
    private static final Uri APP_BROWSER = Uri.parse("android-app://com.example1.browser");
    private static final Uri WEB_ONE_DESTINATION = WebUtil.validUri("https://www.example1.test");
    private static final Uri WEB_ONE_DESTINATION_DIFFERENT_SUBDOMAIN =
            WebUtil.validUri("https://store.example1.test");
    private static final Uri WEB_ONE_DESTINATION_DIFFERENT_SUBDOMAIN_2 =
            WebUtil.validUri("https://foo.example1.test");
    private static final Uri WEB_TWO_DESTINATION = WebUtil.validUri("https://www.example2.test");
    private static final Uri WEB_TWO_DESTINATION_WITH_PATH =
            WebUtil.validUri("https://www.example2.test/ad/foo");
    private static final Uri APP_ONE_DESTINATION =
            Uri.parse("android-app://com.example1.one-trigger");
    private static final Uri APP_TWO_DESTINATION =
            Uri.parse("android-app://com.example1.two-triggers");
    private static final Uri APP_THREE_DESTINATION =
            Uri.parse("android-app://com.example1.three-triggers");
    private static final Uri APP_THREE_DESTINATION_PATH1 =
            Uri.parse("android-app://com.example1.three-triggers/path1");
    private static final Uri APP_THREE_DESTINATION_PATH2 =
            Uri.parse("android-app://com.example1.three-triggers/path2");
    private static final Uri APP_NO_TRIGGERS = Uri.parse("android-app://com.example1.no-triggers");
    private static final Uri INSTALLED_PACKAGE = Uri.parse("android-app://com.example.installed");
    private static final Uri WEB_PUBLISHER_ONE = WebUtil.validUri("https://not.example.test");
    private static final Uri WEB_PUBLISHER_TWO = WebUtil.validUri("https://notexample.test");
    // Differs from WEB_PUBLISHER_ONE by scheme.
    private static final Uri WEB_PUBLISHER_THREE = WebUtil.validUri("http://not.example.test");
    private static final Uri APP_DESTINATION = Uri.parse("android-app://com.destination.example");
    private static final Uri REGISTRATION_ORIGIN =
            WebUtil.validUri("https://subdomain.example.test");

    // Fake ID count for initializing triggers.
    private int mValueId = 1;
    private MockitoSession mStaticMockSession;
    private Flags mFlags;
    public static final Uri REGISTRATION_ORIGIN_2 =
            WebUtil.validUri("https://subdomain_2.example.test");

    @Before
    public void before() {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .strictness(Strictness.WARN)
                        .startMocking();
        ExtendedMockito.doReturn(FlagsFactory.getFlagsForTest()).when(FlagsFactory::getFlags);
        mFlags = FlagsFactory.getFlagsForTest();
    }

    @After
    public void cleanup() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        for (String table : ALL_MSMT_TABLES) {
            db.delete(table, null, null);
        }

        mStaticMockSession.finishMocking();
    }

    @Test
    public void testInsertSource() {
        Source validSource = SourceFixture.getValidSource();
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertSource(validSource));

        String sourceId = getFirstSourceIdFromDatastore();
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        Source source =
                dm.runInTransactionWithResult(measurementDao -> measurementDao.getSource(sourceId))
                        .get();

        assertNotNull(source);
        assertNotNull(source.getId());
        assertNull(source.getAppDestinations());
        assertNull(source.getWebDestinations());
        assertEquals(validSource.getEnrollmentId(), source.getEnrollmentId());
        assertEquals(validSource.getRegistrant(), source.getRegistrant());
        assertEquals(validSource.getEventTime(), source.getEventTime());
        assertEquals(validSource.getExpiryTime(), source.getExpiryTime());
        assertEquals(validSource.getEventReportWindow(), source.getEventReportWindow());
        assertEquals(
                validSource.getAggregatableReportWindow(), source.getAggregatableReportWindow());
        assertEquals(validSource.getPriority(), source.getPriority());
        assertEquals(validSource.getSourceType(), source.getSourceType());
        assertEquals(
                validSource.getInstallAttributionWindow(), source.getInstallAttributionWindow());
        assertEquals(validSource.getInstallCooldownWindow(), source.getInstallCooldownWindow());
        assertEquals(validSource.getAttributionMode(), source.getAttributionMode());
        assertEquals(validSource.getAggregateSource(), source.getAggregateSource());
        assertEquals(validSource.getFilterDataString(), source.getFilterDataString());
        assertEquals(validSource.getAggregateContributions(), source.getAggregateContributions());
        assertEquals(validSource.isDebugReporting(), source.isDebugReporting());
        assertEquals(validSource.getSharedAggregationKeys(), source.getSharedAggregationKeys());
        assertEquals(validSource.getRegistrationId(), source.getRegistrationId());
        assertEquals(validSource.getInstallTime(), source.getInstallTime());
        assertEquals(validSource.getPlatformAdId(), source.getPlatformAdId());
        assertEquals(validSource.getDebugAdId(), source.getDebugAdId());
        assertEquals(validSource.getRegistrationOrigin(), source.getRegistrationOrigin());
        assertEquals(
                validSource.getCoarseEventReportDestinations(),
                source.getCoarseEventReportDestinations());

        // Assert destinations were inserted into the source destination table.

        Pair<List<Uri>, List<Uri>> destinations = dm.runInTransactionWithResult(
                measurementDao -> measurementDao.getSourceDestinations(source.getId())).get();
        assertTrue(
                ImmutableMultiset.copyOf(validSource.getAppDestinations())
                        .equals(ImmutableMultiset.copyOf(destinations.first)));
        assertTrue(
                ImmutableMultiset.copyOf(validSource.getWebDestinations())
                        .equals(ImmutableMultiset.copyOf(destinations.second)));
    }

    @Test
    public void testInsertSource_reachedDbSizeLimitOnEdgeCase_doNotInsert() {
        insertSourceReachingDbSizeLimit(/* dbSize= */ 100L, /* dbSizeMaxLimit= */ 100L);
    }

    @Test
    public void testInsertSource_reachedDbSizeLimitUpperEdgeCase_doNotInsert() {
        insertSourceReachingDbSizeLimit(/* dbSize= */ 101L, /* dbSizeMaxLimit= */ 100L);
    }

    private void insertSourceReachingDbSizeLimit(long dbSize, long dbSizeMaxLimit) {
        final Source validSource = SourceFixture.getValidSource();

        final MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(MeasurementDbHelper.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            // Mocking that the DB file has a size of 100 bytes
            final MeasurementDbHelper spyMeasurementDbHelper =
                    Mockito.spy(MeasurementDbHelper.getInstance(sContext));
            ExtendedMockito.doReturn(spyMeasurementDbHelper)
                    .when(() -> MeasurementDbHelper.getInstance(ArgumentMatchers.any()));
            ExtendedMockito.doReturn(dbSize).when(spyMeasurementDbHelper).getDbFileSize();

            // Mocking that the flags return a max limit size of 100 bytes
            Flags mockFlags = Mockito.mock(Flags.class);
            ExtendedMockito.doReturn(mockFlags).when(FlagsFactory::getFlags);
            ExtendedMockito.doReturn(dbSizeMaxLimit).when(mockFlags).getMeasurementDbSizeLimit();

            DatastoreManagerFactory.getDatastoreManager(sContext)
                    .runInTransaction((dao) -> dao.insertSource(validSource));

            try (Cursor sourceCursor =
                    MeasurementDbHelper.getInstance(sContext)
                            .getReadableDatabase()
                            .query(SourceContract.TABLE, null, null, null, null, null, null)) {
                assertFalse(sourceCursor.moveToNext());
            }
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testInsertTrigger() {
        Trigger validTrigger = TriggerFixture.getValidTrigger();
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertTrigger(validTrigger));

        try (Cursor triggerCursor =
                MeasurementDbHelper.getInstance(sContext)
                        .getReadableDatabase()
                        .query(TriggerContract.TABLE, null, null, null, null, null, null)) {
            assertTrue(triggerCursor.moveToNext());
            Trigger trigger = SqliteObjectMapper.constructTriggerFromCursor(triggerCursor);
            assertNotNull(trigger);
            assertNotNull(trigger.getId());
            assertEquals(
                    validTrigger.getAttributionDestination(), trigger.getAttributionDestination());
            assertEquals(validTrigger.getDestinationType(), trigger.getDestinationType());
            assertEquals(validTrigger.getEnrollmentId(), trigger.getEnrollmentId());
            assertEquals(validTrigger.getRegistrant(), trigger.getRegistrant());
            assertEquals(validTrigger.getTriggerTime(), trigger.getTriggerTime());
            assertEquals(validTrigger.getEventTriggers(), trigger.getEventTriggers());
            assertEquals(validTrigger.getAttributionConfig(), trigger.getAttributionConfig());
            assertEquals(validTrigger.getAdtechKeyMapping(), trigger.getAdtechKeyMapping());
            assertEquals(validTrigger.getPlatformAdId(), trigger.getPlatformAdId());
            assertEquals(validTrigger.getDebugAdId(), trigger.getDebugAdId());
            assertEquals(validTrigger.getRegistrationOrigin(), trigger.getRegistrationOrigin());
        }
    }

    @Test
    public void testInsertTrigger_reachedDbSizeLimitOnEdgeCase_doNotInsert() {
        insertTriggerReachingDbSizeLimit(/* dbSize= */ 100L, /* dbSizeMaxLimit= */ 100L);
    }

    @Test
    public void testInsertTrigger_reachedDbSizeLimitUpperEdgeCase_doNotInsert() {
        insertTriggerReachingDbSizeLimit(/* dbSize= */ 101L, /* dbSizeMaxLimit= */ 100L);
    }

    private void insertTriggerReachingDbSizeLimit(long dbSize, long dbSizeMaxLimit) {
        final Trigger validTrigger = TriggerFixture.getValidTrigger();

        final MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(MeasurementDbHelper.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            // Mocking that the DB file has a size of 100 bytes
            final MeasurementDbHelper spyMeasurementDbHelper =
                    Mockito.spy(MeasurementDbHelper.getInstance(sContext));
            ExtendedMockito.doReturn(spyMeasurementDbHelper)
                    .when(() -> MeasurementDbHelper.getInstance(ArgumentMatchers.any()));
            ExtendedMockito.doReturn(dbSize).when(spyMeasurementDbHelper).getDbFileSize();

            // Mocking that the flags return a max limit size of 100 bytes
            Flags mockFlags = Mockito.mock(Flags.class);
            ExtendedMockito.doReturn(mockFlags).when(FlagsFactory::getFlags);
            ExtendedMockito.doReturn(dbSizeMaxLimit).when(mockFlags).getMeasurementDbSizeLimit();

            DatastoreManagerFactory.getDatastoreManager(sContext)
                    .runInTransaction((dao) -> dao.insertTrigger(validTrigger));

            try (Cursor sourceCursor =
                    MeasurementDbHelper.getInstance(sContext)
                            .getReadableDatabase()
                            .query(TriggerContract.TABLE, null, null, null, null, null, null)) {
                assertFalse(sourceCursor.moveToNext());
            }
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testGetNumSourcesPerPublisher_publisherTypeApp() {
        setupSourceAndTriggerData();
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            2,
                            measurementDao.getNumSourcesPerPublisher(
                                    APP_TWO_PUBLISHER, EventSurfaceType.APP));
                });
        dm.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            1,
                            measurementDao.getNumSourcesPerPublisher(
                                    APP_ONE_PUBLISHER, EventSurfaceType.APP));
                });
        dm.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            0,
                            measurementDao.getNumSourcesPerPublisher(
                                    APP_NO_PUBLISHER, EventSurfaceType.APP));
                });
    }

    @Test
    public void testGetNumSourcesPerPublisher_publisherTypeWeb() {
        setupSourceDataForPublisherTypeWeb();
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            1,
                            measurementDao.getNumSourcesPerPublisher(
                                    WEB_PUBLISHER_ONE, EventSurfaceType.WEB));
                });
        dm.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            2,
                            measurementDao.getNumSourcesPerPublisher(
                                    WEB_PUBLISHER_TWO, EventSurfaceType.WEB));
                });
        dm.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            1,
                            measurementDao.getNumSourcesPerPublisher(
                                    WEB_PUBLISHER_THREE, EventSurfaceType.WEB));
                });
    }

    @Test
    public void testCountDistinctEnrollmentsPerPublisherXDestinationInAttribution_atWindow() {
        Uri sourceSite = Uri.parse("android-app://publisher.app");
        Uri appDestination = Uri.parse("android-app://destination.app");
        String registrant = "android-app://registrant.app";
        List<Attribution> attributionsWithAppDestinations =
                getAttributionsWithDifferentEnrollments(
                        4, appDestination, 5000000001L, sourceSite, registrant);
        for (Attribution attribution : attributionsWithAppDestinations) {
            insertAttribution(attribution);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        String excludedEnrollmentId = "enrollment-id-0";
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(3),
                            measurementDao
                                    .countDistinctEnrollmentsPerPublisherXDestinationInAttribution(
                                            sourceSite,
                                            appDestination,
                                            excludedEnrollmentId,
                                            5000000000L,
                                            6000000000L));
                });
    }

    @Test
    public void testCountDistinctEnrollmentsPerPublisherXDestinationInAttribution_beyondWindow() {
        Uri sourceSite = Uri.parse("android-app://publisher.app");
        Uri appDestination = Uri.parse("android-app://destination.app");
        String registrant = "android-app://registrant.app";
        List<Attribution> attributionsWithAppDestinations =
                getAttributionsWithDifferentEnrollments(
                        4, appDestination, 5000000000L, sourceSite, registrant);
        for (Attribution attribution : attributionsWithAppDestinations) {
            insertAttribution(attribution);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        String excludedEnrollmentId = "enrollment-id-0";
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(0),
                            measurementDao
                                    .countDistinctEnrollmentsPerPublisherXDestinationInAttribution(
                                            sourceSite,
                                            appDestination,
                                            excludedEnrollmentId,
                                            5000000000L,
                                            6000000000L));
                });
    }

    @Test
    public void testInsertDebugReport() {
        DebugReport debugReport = createDebugReport();
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertDebugReport(debugReport));

        try (Cursor cursor =
                MeasurementDbHelper.getInstance(sContext)
                        .getReadableDatabase()
                        .query(
                                MeasurementTables.DebugReportContract.TABLE,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)) {
            assertTrue(cursor.moveToNext());
            DebugReport report = SqliteObjectMapper.constructDebugReportFromCursor(cursor);
            assertNotNull(report);
            assertNotNull(report.getId());
            assertEquals(debugReport.getType(), report.getType());
            assertEquals(debugReport.getBody().toString(), report.getBody().toString());
            assertEquals(debugReport.getEnrollmentId(), report.getEnrollmentId());
            assertEquals(debugReport.getRegistrationOrigin(), report.getRegistrationOrigin());
        }
    }

    @Test
    public void singleAppTrigger_triggersPerDestination_returnsOne() {
        List<Trigger> triggerList = new ArrayList<>();
        triggerList.add(createAppTrigger(APP_ONE_DESTINATION, APP_ONE_DESTINATION));
        addTriggersToDatabase(triggerList);

        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao ->
                        assertThat(
                                        measurementDao.getNumTriggersPerDestination(
                                                APP_ONE_DESTINATION, EventSurfaceType.APP))
                                .isEqualTo(1));
    }

    @Test
    public void multipleAppTriggers_similarUris_triggersPerDestination() {
        List<Trigger> triggerList = new ArrayList<>();
        triggerList.add(createAppTrigger(APP_TWO_DESTINATION, APP_TWO_DESTINATION));
        triggerList.add(createAppTrigger(APP_TWO_DESTINATION, APP_TWO_DESTINATION));
        addTriggersToDatabase(triggerList);

        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao ->
                        assertThat(
                                        measurementDao.getNumTriggersPerDestination(
                                                APP_TWO_DESTINATION, EventSurfaceType.APP))
                                .isEqualTo(2));
    }

    @Test
    public void noAppTriggers_triggersPerDestination_returnsNone() {
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao ->
                        assertThat(
                                        measurementDao.getNumTriggersPerDestination(
                                                APP_NO_TRIGGERS, EventSurfaceType.APP))
                                .isEqualTo(0));
    }

    @Test
    public void multipleAppTriggers_differentPaths_returnsAllMatching() {
        List<Trigger> triggerList = new ArrayList<>();
        triggerList.add(createAppTrigger(APP_THREE_DESTINATION, APP_THREE_DESTINATION));
        triggerList.add(createAppTrigger(APP_THREE_DESTINATION, APP_THREE_DESTINATION_PATH1));
        triggerList.add(createAppTrigger(APP_THREE_DESTINATION, APP_THREE_DESTINATION_PATH2));
        addTriggersToDatabase(triggerList);

        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao -> {
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            APP_THREE_DESTINATION, EventSurfaceType.APP))
                            .isEqualTo(3);
                    // Try the same thing, but use the app uri with path to find number of triggers.
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            APP_THREE_DESTINATION_PATH1, EventSurfaceType.APP))
                            .isEqualTo(3);
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            APP_THREE_DESTINATION_PATH2, EventSurfaceType.APP))
                            .isEqualTo(3);
                    Uri unseenAppThreePath =
                            Uri.parse("android-app://com.example1.three-triggers/path3");
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            unseenAppThreePath, EventSurfaceType.APP))
                            .isEqualTo(3);
                });
    }

    @Test
    public void singleWebTrigger_triggersPerDestination_returnsOne() {
        List<Trigger> triggerList = new ArrayList<>();
        triggerList.add(createWebTrigger(WEB_ONE_DESTINATION));
        addTriggersToDatabase(triggerList);

        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao -> {
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            WEB_ONE_DESTINATION, EventSurfaceType.WEB))
                            .isEqualTo(1);
                });
    }

    @Test
    public void webTriggerMultipleSubDomains_triggersPerDestination_returnsAllMatching() {
        List<Trigger> triggerList = new ArrayList<>();
        triggerList.add(createWebTrigger(WEB_ONE_DESTINATION));
        triggerList.add(createWebTrigger(WEB_ONE_DESTINATION_DIFFERENT_SUBDOMAIN));
        triggerList.add(createWebTrigger(WEB_ONE_DESTINATION_DIFFERENT_SUBDOMAIN_2));
        addTriggersToDatabase(triggerList);

        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao -> {
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            WEB_ONE_DESTINATION, EventSurfaceType.WEB))
                            .isEqualTo(3);
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            WEB_ONE_DESTINATION_DIFFERENT_SUBDOMAIN,
                                            EventSurfaceType.WEB))
                            .isEqualTo(3);
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            WEB_ONE_DESTINATION_DIFFERENT_SUBDOMAIN_2,
                                            EventSurfaceType.WEB))
                            .isEqualTo(3);
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            WebUtil.validUri("https://new-subdomain.example1.test"),
                                            EventSurfaceType.WEB))
                            .isEqualTo(3);
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            WebUtil.validUri("https://example1.test"),
                                            EventSurfaceType.WEB))
                            .isEqualTo(3);
                });
    }

    @Test
    public void webTriggerWithoutSubdomains_triggersPerDestination_returnsAllMatching() {
        List<Trigger> triggerList = new ArrayList<>();
        Uri webDestinationWithoutSubdomain = WebUtil.validUri("https://example1.test");
        Uri webDestinationWithoutSubdomainPath1 = WebUtil.validUri("https://example1.test/path1");
        Uri webDestinationWithoutSubdomainPath2 = WebUtil.validUri("https://example1.test/path2");
        Uri webDestinationWithoutSubdomainPath3 = WebUtil.validUri("https://example1.test/path3");
        triggerList.add(createWebTrigger(webDestinationWithoutSubdomain));
        triggerList.add(createWebTrigger(webDestinationWithoutSubdomainPath1));
        triggerList.add(createWebTrigger(webDestinationWithoutSubdomainPath2));
        addTriggersToDatabase(triggerList);

        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao -> {
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            webDestinationWithoutSubdomain, EventSurfaceType.WEB))
                            .isEqualTo(3);
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            webDestinationWithoutSubdomainPath1,
                                            EventSurfaceType.WEB))
                            .isEqualTo(3);
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            webDestinationWithoutSubdomainPath2,
                                            EventSurfaceType.WEB))
                            .isEqualTo(3);
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            webDestinationWithoutSubdomainPath3,
                                            EventSurfaceType.WEB))
                            .isEqualTo(3);
                });
    }

    @Test
    public void webTriggerDifferentPaths_triggersPerDestination_returnsAllMatching() {
        List<Trigger> triggerList = new ArrayList<>();
        triggerList.add(createWebTrigger(WEB_TWO_DESTINATION));
        triggerList.add(createWebTrigger(WEB_TWO_DESTINATION_WITH_PATH));
        addTriggersToDatabase(triggerList);

        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao -> {
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            WEB_TWO_DESTINATION, EventSurfaceType.WEB))
                            .isEqualTo(2);
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            WEB_TWO_DESTINATION_WITH_PATH, EventSurfaceType.WEB))
                            .isEqualTo(2);
                });
    }

    @Test
    public void noMathingWebTriggers_triggersPerDestination_returnsZero() {
        List<Trigger> triggerList = new ArrayList<>();
        triggerList.add(createWebTrigger(WEB_ONE_DESTINATION));
        addTriggersToDatabase(triggerList);

        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                measurementDao -> {
                    Uri differentScheme = WebUtil.validUri("http://www.example1.test");
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            differentScheme, EventSurfaceType.WEB))
                            .isEqualTo(0);

                    Uri notMatchingUrl2 = WebUtil.validUri("https://www.not-example1.test");
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            notMatchingUrl2, EventSurfaceType.WEB))
                            .isEqualTo(0);

                    Uri notMatchingUrl = WebUtil.validUri("https://www.not-example-1.test");
                    assertThat(
                                    measurementDao.getNumTriggersPerDestination(
                                            notMatchingUrl, EventSurfaceType.WEB))
                            .isEqualTo(0);
                });
    }

    @Test
    public void testCountDistinctEnrollmentsPerPublisherXDestinationInAttribution_appDestination() {
        Uri sourceSite = Uri.parse("android-app://publisher.app");
        Uri webDestination = WebUtil.validUri("https://web-destination.test");
        Uri appDestination = Uri.parse("android-app://destination.app");
        String registrant = "android-app://registrant.app";
        List<Attribution> attributionsWithAppDestinations1 =
                getAttributionsWithDifferentEnrollments(
                        4, appDestination, 5000000000L, sourceSite, registrant);
        List<Attribution> attributionsWithAppDestinations2 =
                getAttributionsWithDifferentEnrollments(
                        2, appDestination, 5000000000L, sourceSite, registrant);
        List<Attribution> attributionsWithWebDestinations =
                getAttributionsWithDifferentEnrollments(
                        2, webDestination, 5500000000L, sourceSite, registrant);
        List<Attribution> attributionsOutOfWindow =
                getAttributionsWithDifferentEnrollments(
                        10, appDestination, 50000000000L, sourceSite, registrant);
        for (Attribution attribution : attributionsWithAppDestinations1) {
            insertAttribution(attribution);
        }
        for (Attribution attribution : attributionsWithAppDestinations2) {
            insertAttribution(attribution);
        }
        for (Attribution attribution : attributionsWithWebDestinations) {
            insertAttribution(attribution);
        }
        for (Attribution attribution : attributionsOutOfWindow) {
            insertAttribution(attribution);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        String excludedEnrollmentId = "enrollment-id-0";
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(3),
                            measurementDao
                                    .countDistinctEnrollmentsPerPublisherXDestinationInAttribution(
                                            sourceSite,
                                            appDestination,
                                            excludedEnrollmentId,
                                            4000000000L,
                                            6000000000L));
                });
    }

    @Test
    public void testCountDistinctEnrollmentsPerPublisherXDestinationInAttribution_webDestination() {
        Uri sourceSite = Uri.parse("android-app://publisher.app");
        Uri webDestination = WebUtil.validUri("https://web-destination.test");
        Uri appDestination = Uri.parse("android-app://destination.app");
        String registrant = "android-app://registrant.app";
        List<Attribution> attributionsWithAppDestinations =
                getAttributionsWithDifferentEnrollments(
                        2, appDestination, 5000000000L, sourceSite, registrant);
        List<Attribution> attributionsWithWebDestinations1 =
                getAttributionsWithDifferentEnrollments(
                        4, webDestination, 5000000000L, sourceSite, registrant);
        List<Attribution> attributionsWithWebDestinations2 =
                getAttributionsWithDifferentEnrollments(
                        2, webDestination, 5500000000L, sourceSite, registrant);
        List<Attribution> attributionsOutOfWindow =
                getAttributionsWithDifferentEnrollments(
                        10, webDestination, 50000000000L, sourceSite, registrant);
        for (Attribution attribution : attributionsWithAppDestinations) {
            insertAttribution(attribution);
        }
        for (Attribution attribution : attributionsWithWebDestinations1) {
            insertAttribution(attribution);
        }
        for (Attribution attribution : attributionsWithWebDestinations2) {
            insertAttribution(attribution);
        }
        for (Attribution attribution : attributionsOutOfWindow) {
            insertAttribution(attribution);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        String excludedEnrollmentId = "enrollment-id-3";
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(3),
                            measurementDao
                                    .countDistinctEnrollmentsPerPublisherXDestinationInAttribution(
                                            sourceSite,
                                            webDestination,
                                            excludedEnrollmentId,
                                            4000000000L,
                                            6000000000L));
                });
    }

    @Test
    public void testCountDistinctDestinationsPerPublisherInActiveSource_atWindow() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentDestinations(
                        4,
                        true,
                        true,
                        4500000001L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        List<Uri> excludedDestinations =
                List.of(WebUtil.validUri("https://web-destination-2.test"));
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(3),
                            measurementDao
                                    .countDistinctDestinationsPerPublisherXEnrollmentInActiveSource(
                                            publisher,
                                            EventSurfaceType.APP,
                                            SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                            excludedDestinations,
                                            EventSurfaceType.WEB,
                                            4500000000L,
                                            6000000000L));
                });
    }

    @Test
    public void testCountDistinctDestinationsPerPublisherInActiveSource_expiredSource() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentDestinations(
                        4,
                        true,
                        true,
                        4500000001L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> expiredSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentDestinations(
                        6,
                        true,
                        true,
                        4500000001L,
                        6000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE,
                        REGISTRATION_ORIGIN);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        for (Source source : expiredSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        List<Uri> excludedDestinations =
                List.of(WebUtil.validUri("https://web-destination-2.test"));
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(3),
                            measurementDao
                                    .countDistinctDestinationsPerPublisherXEnrollmentInActiveSource(
                                            publisher,
                                            EventSurfaceType.APP,
                                            SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                            excludedDestinations,
                                            EventSurfaceType.WEB,
                                            4500000000L,
                                            6000000000L));
                });
    }

    @Test
    public void testCountDistinctDestinationsPerPublisherInActiveSource_beyondWindow() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentDestinations(
                        4,
                        true,
                        true,
                        4500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        List<Uri> excludedDestinations =
                List.of(WebUtil.validUri("https://web-destination-2.test"));
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(0),
                            measurementDao
                                    .countDistinctDestinationsPerPublisherXEnrollmentInActiveSource(
                                            publisher,
                                            EventSurfaceType.APP,
                                            SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                            excludedDestinations,
                                            EventSurfaceType.WEB,
                                            4500000000L,
                                            6000000000L));
                });
    }

    @Test
    public void testCountDistinctDestinationsPerPublisherInActiveSource_appPublisher() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentDestinations(
                        4,
                        true,
                        true,
                        4500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithAppDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        true,
                        false,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithWebDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        false,
                        true,
                        5500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesOutOfWindow =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        50000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> ignoredSources =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.IGNORED);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithAppDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesOutOfWindow) {
            insertSource(source);
        }
        for (Source source : ignoredSources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        List<Uri> excludedDestinations =
                List.of(WebUtil.validUri("https://web-destination-2.test"));
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(3),
                            measurementDao
                                    .countDistinctDestinationsPerPublisherXEnrollmentInActiveSource(
                                            publisher,
                                            EventSurfaceType.APP,
                                            SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                            excludedDestinations,
                                            EventSurfaceType.WEB,
                                            4000000000L,
                                            6000000000L));
                });
    }

    // (Testing countDistinctDestinationsPerPublisherInActiveSource)
    @Test
    public void testCountDistinctDestinations_appPublisher_enrollmentMismatch() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentDestinations(
                        4,
                        true,
                        true,
                        4500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithAppDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        true,
                        false,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithWebDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        false,
                        true,
                        5500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesOutOfWindow =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        50000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> ignoredSources =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.IGNORED);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithAppDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesOutOfWindow) {
            insertSource(source);
        }
        for (Source source : ignoredSources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        List<Uri> excludedDestinations =
                List.of(WebUtil.validUri("https://web-destination-2.test"));
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(0),
                            measurementDao
                                    .countDistinctDestinationsPerPublisherXEnrollmentInActiveSource(
                                            publisher,
                                            EventSurfaceType.APP,
                                            "unmatched-enrollment-id",
                                            excludedDestinations,
                                            EventSurfaceType.WEB,
                                            4000000000L,
                                            6000000000L));
                });
    }

    @Test
    public void testCountDistinctDestinationsPerPublisherInActiveSource_webPublisher_exactMatch() {
        Uri publisher = WebUtil.validUri("https://publisher.test");
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentDestinations(
                        4,
                        true,
                        true,
                        4500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithAppDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        true,
                        false,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithWebDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        false,
                        true,
                        5500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesOutOfWindow =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        50000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> ignoredSources =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.IGNORED);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithAppDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesOutOfWindow) {
            insertSource(source);
        }
        for (Source source : ignoredSources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        List<Uri> excludedDestinations =
                List.of(WebUtil.validUri("https://web-destination-2.test"));
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(3),
                            measurementDao
                                    .countDistinctDestinationsPerPublisherXEnrollmentInActiveSource(
                                            publisher,
                                            EventSurfaceType.WEB,
                                            SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                            excludedDestinations,
                                            EventSurfaceType.WEB,
                                            4000000000L,
                                            6000000000L));
                });
    }

    // (Testing countDistinctDestinationsPerPublisherXEnrollmentInActiveSource)
    @Test
    public void testCountDistinctDestinations_webPublisher_doesNotMatchDomainAsSuffix() {
        Uri publisher = WebUtil.validUri("https://publisher.test");
        Uri publisherAsSuffix = WebUtil.validUri("https://prefix-publisher.test");
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentDestinations(
                        4,
                        true,
                        true,
                        4500000000L,
                        publisherAsSuffix,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithAppDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        true,
                        false,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithWebDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        false,
                        true,
                        5500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesOutOfWindow =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        50000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> ignoredSources =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.IGNORED);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithAppDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesOutOfWindow) {
            insertSource(source);
        }
        for (Source source : ignoredSources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        List<Uri> excludedDestinations =
                List.of(WebUtil.validUri("https://web-destination-2.test"));
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(2),
                            measurementDao
                                    .countDistinctDestinationsPerPublisherXEnrollmentInActiveSource(
                                            publisher,
                                            EventSurfaceType.WEB,
                                            SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                            excludedDestinations,
                                            EventSurfaceType.WEB,
                                            4000000000L,
                                            6000000000L));
                });
    }

    // (Testing countDistinctDestinationsPerPublisherXEnrollmentInActiveSource)
    @Test
    public void testCountDistinctDestinations_webPublisher_doesNotMatchDifferentScheme() {
        Uri publisher = WebUtil.validUri("https://publisher.test");
        Uri publisherWithDifferentScheme = WebUtil.validUri("http://publisher.test");
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentDestinations(
                        4,
                        true,
                        true,
                        4500000000L,
                        publisherWithDifferentScheme,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithAppDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        true,
                        false,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithWebDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        false,
                        true,
                        5500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesOutOfWindow =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        50000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> ignoredSources =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.IGNORED);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithAppDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesOutOfWindow) {
            insertSource(source);
        }
        for (Source source : ignoredSources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        List<Uri> excludedDestinations =
                List.of(WebUtil.validUri("https://web-destination-2.test"));
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(2),
                            measurementDao
                                    .countDistinctDestinationsPerPublisherXEnrollmentInActiveSource(
                                            publisher,
                                            EventSurfaceType.WEB,
                                            SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                            excludedDestinations,
                                            EventSurfaceType.WEB,
                                            4000000000L,
                                            6000000000L));
                });
    }

    // countDistinctDestinationsPerPublisherXEnrollmentInActiveSource
    @Test
    public void countDistinctDestinationsPerPublisher_webPublisher_multipleDestinations() {
        Uri publisher = WebUtil.validUri("https://publisher.test");
        // One source with multiple destinations
        Source activeSourceWithAppAndWebDestinations =
                getSourceWithDifferentDestinations(
                        3,
                        true,
                        true,
                        4500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithAppDestinations =
                getSourcesWithDifferentDestinations(
                        2,
                        true,
                        false,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithWebDestinations =
                getSourcesWithDifferentDestinations(
                        1,
                        false,
                        true,
                        5500000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesOutOfWindow =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        50000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE);
        List<Source> ignoredSources =
                getSourcesWithDifferentDestinations(
                        10,
                        true,
                        true,
                        5000000000L,
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.IGNORED);
        insertSource(activeSourceWithAppAndWebDestinations);
        for (Source source : activeSourcesWithAppDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesOutOfWindow) {
            insertSource(source);
        }
        for (Source source : ignoredSources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        List<Uri> excludedDestinations =
                List.of(
                        WebUtil.validUri("https://web-destination-1.test"),
                        WebUtil.validUri("https://web-destination-2.test"));
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(1),
                            measurementDao
                                    .countDistinctDestinationsPerPublisherXEnrollmentInActiveSource(
                                            publisher,
                                            EventSurfaceType.WEB,
                                            SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                            excludedDestinations,
                                            EventSurfaceType.WEB,
                                            4000000000L,
                                            6000000000L));
                });
    }

    // Tests countSourcesPerPublisherXEnrollmentExcludingRegistrationOriginSinceTime
    @Test
    public void testCountSourcesExclRegOrigin_forSameOrigin_returnsZero() {
        // Positive case. For same registration origin we always pass the 1 origin
        // per site limit and return 0
        Uri appPublisher = Uri.parse("android-app://publisher.app");
        List<Source> sourcesMoreThanOneDayOld =
                getSourcesWithDifferentDestinations(
                        5,
                        true,
                        true,
                        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2),
                        appPublisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE,
                        REGISTRATION_ORIGIN);

        List<Source> sourcesRecent =
                getSourcesWithDifferentDestinations(
                        5,
                        true,
                        true,
                        System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2),
                        appPublisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE,
                        REGISTRATION_ORIGIN);

        for (Source source : sourcesMoreThanOneDayOld) {
            insertSource(source);
        }
        for (Source source : sourcesRecent) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(0),
                            measurementDao.countSourcesPerPublisherXEnrollmentExcludingRegOrigin(
                                    REGISTRATION_ORIGIN,
                                    appPublisher,
                                    EventSurfaceType.APP,
                                    SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                    System.currentTimeMillis(),
                                    PrivacyParams.MIN_REPORTING_ORIGIN_UPDATE_WINDOW));
                });
    }

    @Test
    public void testCountSourcesExclRegOrigin_forDifferentAppPublisher_returnsZero() {
        // Positive case. For different publisher we always pass the 1 origin
        // per site limit and return 0
        Uri appPublisher = Uri.parse("android-app://publisher.app");
        Uri appPublisher2 = Uri.parse("android-app://publisher2.app");
        List<Source> sources =
                getSourcesWithDifferentDestinations(
                        5,
                        true,
                        true,
                        System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2),
                        appPublisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE,
                        REGISTRATION_ORIGIN);
        for (Source source : sources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(0),
                            measurementDao.countSourcesPerPublisherXEnrollmentExcludingRegOrigin(
                                    REGISTRATION_ORIGIN_2,
                                    appPublisher2,
                                    EventSurfaceType.APP,
                                    SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                    System.currentTimeMillis(),
                                    PrivacyParams.MIN_REPORTING_ORIGIN_UPDATE_WINDOW));
                });
    }

    @Test
    public void testCountSourcesExclRegOrigin_forDifferentWebPublisher_returnsZero() {
        // Positive case. For different publisher we always pass the 1 origin
        // per site limit and return 0
        Uri publisher = WebUtil.validUri("https://publisher.test");
        Uri publisher2 = WebUtil.validUri("https://publisher2.test");
        List<Source> sources =
                getSourcesWithDifferentDestinations(
                        5,
                        true,
                        true,
                        System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2),
                        publisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE,
                        REGISTRATION_ORIGIN);
        for (Source source : sources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(0),
                            measurementDao.countSourcesPerPublisherXEnrollmentExcludingRegOrigin(
                                    REGISTRATION_ORIGIN_2,
                                    publisher2,
                                    EventSurfaceType.WEB,
                                    SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                    System.currentTimeMillis(),
                                    PrivacyParams.MIN_REPORTING_ORIGIN_UPDATE_WINDOW));
                });
    }

    @Test
    public void testCountSourcesExclRegOrigin_forDifferentEnrollment_returnsZero() {
        // Positive case. For different enrollment (aka reporting site)
        // we always pass the 1 origin per site limit and return 0
        String differentEnrollment = "new-enrollment";
        Uri differentSite = WebUtil.validUri("https://subdomain.different-site.test");
        Uri appPublisher = Uri.parse("android-app://publisher.app");
        List<Source> sources =
                getSourcesWithDifferentDestinations(
                        5,
                        true,
                        true,
                        System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2),
                        appPublisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE,
                        REGISTRATION_ORIGIN);
        for (Source source : sources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(0),
                            measurementDao.countSourcesPerPublisherXEnrollmentExcludingRegOrigin(
                                    differentSite,
                                    appPublisher,
                                    EventSurfaceType.APP,
                                    differentEnrollment,
                                    System.currentTimeMillis(),
                                    PrivacyParams.MIN_REPORTING_ORIGIN_UPDATE_WINDOW));
                });
    }

    @Test
    public void testCountSourcesExclRegOrigin_forDifferentOriginMoreThanTimeWindow_returnsZero() {
        // Positive case. For different origin with same enrollment
        // more than time window of 1 day we always pass the 1 origin per site
        // limit and return 0
        Uri appPublisher = Uri.parse("android-app://publisher.app");
        List<Source> sources =
                getSourcesWithDifferentDestinations(
                        5,
                        true,
                        true,
                        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2),
                        appPublisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE,
                        REGISTRATION_ORIGIN);
        for (Source source : sources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(0),
                            measurementDao.countSourcesPerPublisherXEnrollmentExcludingRegOrigin(
                                    REGISTRATION_ORIGIN_2,
                                    appPublisher,
                                    EventSurfaceType.APP,
                                    SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                    System.currentTimeMillis(),
                                    PrivacyParams.MIN_REPORTING_ORIGIN_UPDATE_WINDOW));
                });
    }
    // Tests countSourcesPerPublisherXEnrollmentExcludingRegistrationOriginSinceTime
    @Test
    public void testCountSources_forDifferentOriginWithinTimeWindow_returnsNumOfSources() {
        // Negative case. For different origin with same enrollment
        // we always fail the 1 origin per site limit and return 1
        Uri appPublisher = Uri.parse("android-app://publisher.app");
        List<Source> sources =
                getSourcesWithDifferentDestinations(
                        5,
                        true,
                        true,
                        System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2),
                        appPublisher,
                        SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                        Source.Status.ACTIVE,
                        REGISTRATION_ORIGIN);
        for (Source source : sources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(5),
                            measurementDao.countSourcesPerPublisherXEnrollmentExcludingRegOrigin(
                                    REGISTRATION_ORIGIN_2,
                                    appPublisher,
                                    EventSurfaceType.APP,
                                    SourceFixture.ValidSourceParams.ENROLLMENT_ID,
                                    System.currentTimeMillis(),
                                    PrivacyParams.MIN_REPORTING_ORIGIN_UPDATE_WINDOW));
                });
    }

    @Test
    public void testCountDistinctEnrollmentsPerPublisherXDestinationInSource_atWindow() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Uri> webDestinations = List.of(WebUtil.validUri("https://web-destination.test"));
        List<Uri> appDestinations = List.of(Uri.parse("android-app://destination.app"));
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentEnrollments(
                        2,
                        appDestinations,
                        webDestinations,
                        4500000001L,
                        publisher,
                        Source.Status.ACTIVE);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        String excludedEnrollmentId = "enrollment-id-1";
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(1),
                            measurementDao.countDistinctEnrollmentsPerPublisherXDestinationInSource(
                                    publisher,
                                    EventSurfaceType.APP,
                                    appDestinations,
                                    excludedEnrollmentId,
                                    4500000000L,
                                    6000000000L));
                });
    }

    @Test
    public void testCountDistinctEnrollmentsPerPublisherXDestinationInSource_beyondWindow() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Uri> webDestinations = List.of(WebUtil.validUri("https://web-destination.test"));
        List<Uri> appDestinations = List.of(Uri.parse("android-app://destination.app"));
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentEnrollments(
                        2,
                        appDestinations,
                        webDestinations,
                        4500000000L,
                        publisher,
                        Source.Status.ACTIVE);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        String excludedEnrollmentId = "enrollment-id-1";
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(0),
                            measurementDao.countDistinctEnrollmentsPerPublisherXDestinationInSource(
                                    publisher,
                                    EventSurfaceType.APP,
                                    appDestinations,
                                    excludedEnrollmentId,
                                    4500000000L,
                                    6000000000L));
                });
    }

    @Test
    public void testCountDistinctEnrollmentsPerPublisherXDestinationInSource_expiredSource() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Uri> webDestinations = List.of(WebUtil.validUri("https://web-destination.test"));
        List<Uri> appDestinations = List.of(Uri.parse("android-app://destination.app"));
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentEnrollments(
                        2,
                        appDestinations,
                        webDestinations,
                        4500000001L,
                        publisher,
                        Source.Status.ACTIVE);
        List<Source> expiredSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentEnrollments(
                        4,
                        appDestinations,
                        webDestinations,
                        4500000000L,
                        6000000000L,
                        publisher,
                        Source.Status.ACTIVE);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        for (Source source : expiredSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        String excludedEnrollmentId = "enrollment-id-1";
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(1),
                            measurementDao.countDistinctEnrollmentsPerPublisherXDestinationInSource(
                                    publisher,
                                    EventSurfaceType.APP,
                                    appDestinations,
                                    excludedEnrollmentId,
                                    4500000000L,
                                    6000000000L));
                });
    }

    @Test
    public void testCountDistinctEnrollmentsPerPublisherXDestinationInSource_appDestination() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Uri> webDestinations = List.of(WebUtil.validUri("https://web-destination.test"));
        List<Uri> appDestinations = List.of(Uri.parse("android-app://destination.app"));
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentEnrollments(
                        2,
                        appDestinations,
                        webDestinations,
                        4500000000L,
                        publisher,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithAppDestinations =
                getSourcesWithDifferentEnrollments(
                        2, appDestinations, null, 5000000000L, publisher, Source.Status.ACTIVE);
        List<Source> activeSourcesWithWebDestinations =
                getSourcesWithDifferentEnrollments(
                        2, null, webDestinations, 5500000000L, publisher, Source.Status.ACTIVE);
        List<Source> activeSourcesOutOfWindow =
                getSourcesWithDifferentEnrollments(
                        10,
                        appDestinations,
                        webDestinations,
                        50000000000L,
                        publisher,
                        Source.Status.ACTIVE);
        List<Source> ignoredSources =
                getSourcesWithDifferentEnrollments(
                        3,
                        appDestinations,
                        webDestinations,
                        5000000000L,
                        publisher,
                        Source.Status.IGNORED);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithAppDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesOutOfWindow) {
            insertSource(source);
        }
        for (Source source : ignoredSources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        String excludedEnrollmentId = "enrollment-id-1";
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(2),
                            measurementDao.countDistinctEnrollmentsPerPublisherXDestinationInSource(
                                    publisher,
                                    EventSurfaceType.APP,
                                    appDestinations,
                                    excludedEnrollmentId,
                                    4000000000L,
                                    6000000000L));
                });
    }

    @Test
    public void testCountDistinctEnrollmentsPerPublisherXDestinationInSource_webDestination() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Uri> webDestinations = List.of(WebUtil.validUri("https://web-destination.test"));
        List<Uri> appDestinations = List.of(Uri.parse("android-app://destination.app"));
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentEnrollments(
                        2,
                        appDestinations,
                        webDestinations,
                        4500000000L,
                        publisher,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithAppDestinations =
                getSourcesWithDifferentEnrollments(
                        2, appDestinations, null, 5000000000L, publisher, Source.Status.ACTIVE);
        List<Source> activeSourcesWithWebDestinations =
                getSourcesWithDifferentEnrollments(
                        2, null, webDestinations, 5500000000L, publisher, Source.Status.ACTIVE);
        List<Source> activeSourcesOutOfWindow =
                getSourcesWithDifferentEnrollments(
                        10,
                        appDestinations,
                        webDestinations,
                        50000000000L,
                        publisher,
                        Source.Status.ACTIVE);
        List<Source> ignoredSources =
                getSourcesWithDifferentEnrollments(
                        3,
                        appDestinations,
                        webDestinations,
                        5000000000L,
                        publisher,
                        Source.Status.IGNORED);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithAppDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesOutOfWindow) {
            insertSource(source);
        }
        for (Source source : ignoredSources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        String excludedEnrollmentId = "enrollment-id-22";
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(3),
                            measurementDao.countDistinctEnrollmentsPerPublisherXDestinationInSource(
                                    publisher,
                                    EventSurfaceType.WEB,
                                    webDestinations,
                                    excludedEnrollmentId,
                                    4000000000L,
                                    6000000000L));
                });
    }

    // countDistinctEnrollmentsPerPublisherXDestinationInSource
    @Test
    public void countDistinctEnrollmentsPerPublisher_webDestination_multipleDestinations() {
        Uri publisher = Uri.parse("android-app://publisher.app");
        List<Uri> webDestinations1 = List.of(WebUtil.validUri("https://web-destination-1.test"));
        List<Uri> webDestinations2 =
                List.of(
                        WebUtil.validUri("https://web-destination-1.test"),
                        WebUtil.validUri("https://web-destination-2.test"));
        List<Uri> appDestinations = List.of(Uri.parse("android-app://destination.app"));
        List<Source> activeSourcesWithAppAndWebDestinations =
                getSourcesWithDifferentEnrollments(
                        3,
                        appDestinations,
                        webDestinations1,
                        4500000000L,
                        publisher,
                        Source.Status.ACTIVE);
        List<Source> activeSourcesWithAppDestinations =
                getSourcesWithDifferentEnrollments(
                        2, appDestinations, null, 5000000000L, publisher, Source.Status.ACTIVE);
        List<Source> activeSourcesWithWebDestinations =
                getSourcesWithDifferentEnrollments(
                        2, null, webDestinations2, 5500000000L, publisher, Source.Status.ACTIVE);
        List<Source> activeSourcesOutOfWindow =
                getSourcesWithDifferentEnrollments(
                        10,
                        appDestinations,
                        webDestinations2,
                        50000000000L,
                        publisher,
                        Source.Status.ACTIVE);
        List<Source> ignoredSources =
                getSourcesWithDifferentEnrollments(
                        2,
                        appDestinations,
                        webDestinations1,
                        5000000000L,
                        publisher,
                        Source.Status.IGNORED);
        for (Source source : activeSourcesWithAppAndWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithAppDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesWithWebDestinations) {
            insertSource(source);
        }
        for (Source source : activeSourcesOutOfWindow) {
            insertSource(source);
        }
        for (Source source : ignoredSources) {
            insertSource(source);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);
        String excludedEnrollmentId = "enrollment-id-1";
        datastoreManager.runInTransaction(
                measurementDao -> {
                    assertEquals(
                            Integer.valueOf(2),
                            measurementDao.countDistinctEnrollmentsPerPublisherXDestinationInSource(
                                    publisher,
                                    EventSurfaceType.WEB,
                                    webDestinations2,
                                    excludedEnrollmentId,
                                    4000000000L,
                                    6000000000L));
                });
    }

    @Test
    public void testInstallAttribution_selectHighestPriority() {
        long currentTimestamp = System.currentTimeMillis();

        insertSource(
                createSourceForIATest(
                                "IA1", currentTimestamp, 100, -1, false, DEFAULT_ENROLLMENT_ID)
                        .build(),
                "IA1");
        insertSource(
                createSourceForIATest("IA2", currentTimestamp, 50, -1, false, DEFAULT_ENROLLMENT_ID)
                        .build(),
                "IA2");
        // Should select id IA1 because it has higher priority
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao -> {
                                    measurementDao.doInstallAttribution(
                                            INSTALLED_PACKAGE, currentTimestamp);
                                }));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        assertTrue(getInstallAttributionStatus("IA1", db));
        assertFalse(getInstallAttributionStatus("IA2", db));
        removeSources(Arrays.asList("IA1", "IA2"), db);
    }

    @Test
    public void testInstallAttribution_selectLatest() {
        long currentTimestamp = System.currentTimeMillis();
        insertSource(
                createSourceForIATest("IA1", currentTimestamp, -1, 10, false, DEFAULT_ENROLLMENT_ID)
                        .build(),
                "IA1");
        insertSource(
                createSourceForIATest("IA2", currentTimestamp, -1, 5, false, DEFAULT_ENROLLMENT_ID)
                        .build(),
                "IA2");
        // Should select id=IA2 as it is latest
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao -> {
                                    measurementDao.doInstallAttribution(
                                            INSTALLED_PACKAGE, currentTimestamp);
                                }));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        assertFalse(getInstallAttributionStatus("IA1", db));
        assertTrue(getInstallAttributionStatus("IA2", db));

        removeSources(Arrays.asList("IA1", "IA2"), db);
    }

    @Test
    public void testInstallAttribution_ignoreNewerSources() {
        long currentTimestamp = System.currentTimeMillis();
        insertSource(
                createSourceForIATest("IA1", currentTimestamp, -1, 10, false, DEFAULT_ENROLLMENT_ID)
                        .build(),
                "IA1");
        insertSource(
                createSourceForIATest("IA2", currentTimestamp, -1, 5, false, DEFAULT_ENROLLMENT_ID)
                        .build(),
                "IA2");
        // Should select id=IA1 as it is the only valid choice.
        // id=IA2 is newer than the evenTimestamp of install event.
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao -> {
                                    measurementDao.doInstallAttribution(
                                            INSTALLED_PACKAGE,
                                            currentTimestamp - TimeUnit.DAYS.toMillis(7));
                                }));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        assertTrue(getInstallAttributionStatus("IA1", db));
        assertFalse(getInstallAttributionStatus("IA2", db));
        removeSources(Arrays.asList("IA1", "IA2"), db);
    }

    @Test
    public void testInstallAttribution_noValidSource() {
        long currentTimestamp = System.currentTimeMillis();
        insertSource(
                createSourceForIATest("IA1", currentTimestamp, 10, 10, true, DEFAULT_ENROLLMENT_ID)
                        .build(),
                "IA1");
        insertSource(
                createSourceForIATest("IA2", currentTimestamp, 10, 11, true, DEFAULT_ENROLLMENT_ID)
                        .build(),
                "IA2");
        // Should not update any sources.
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.doInstallAttribution(
                                                INSTALLED_PACKAGE, currentTimestamp)));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        assertFalse(getInstallAttributionStatus("IA1", db));
        assertFalse(getInstallAttributionStatus("IA2", db));
        removeSources(Arrays.asList("IA1", "IA2"), db);
    }

    @Test
    public void installAttribution_install_installTimeEqualsEventTime() {
        long currentTimestamp = System.currentTimeMillis();
        insertSource(
                createSourceForIATest("IA1", currentTimestamp, -1, 10, false, DEFAULT_ENROLLMENT_ID)
                        .build(),
                "IA1");
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao -> {
                                    measurementDao.doInstallAttribution(
                                            INSTALLED_PACKAGE,
                                            currentTimestamp - TimeUnit.DAYS.toMillis(7));
                                }));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        assertEquals(
                currentTimestamp - TimeUnit.DAYS.toMillis(7),
                getInstallAttributionInstallTime("IA1", db).longValue());
        removeSources(Arrays.asList("IA1"), db);
    }

    @Test
    public void doInstallAttribution_noValidSourceStatus_IgnoresSources() {
        long currentTimestamp = System.currentTimeMillis();
        Source source =
                createSourceForIATest(
                                "IA1", currentTimestamp, 100, -1, false, DEFAULT_ENROLLMENT_ID)
                        .build();

        // Execution
        // Active source should get install attributed
        source.setStatus(Source.Status.ACTIVE);
        insertSource(source, source.getId());
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.doInstallAttribution(
                                                INSTALLED_PACKAGE, currentTimestamp)));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        assertTrue(getInstallAttributionStatus("IA1", db));
        removeSources(Collections.singletonList("IA1"), db);

        // Active source should not get install attributed
        source.setStatus(Source.Status.IGNORED);
        insertSource(source, source.getId());
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.doInstallAttribution(
                                                INSTALLED_PACKAGE, currentTimestamp)));
        assertFalse(getInstallAttributionStatus("IA1", db));
        removeSources(Collections.singletonList("IA1"), db);

        // MARKED_TO_DELETE source should not get install attributed
        source.setStatus(Source.Status.MARKED_TO_DELETE);
        insertSource(source, source.getId());
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.doInstallAttribution(
                                                INSTALLED_PACKAGE, currentTimestamp)));
        assertFalse(getInstallAttributionStatus("IA1", db));
        removeSources(Collections.singletonList("IA1"), db);
    }

    @Test
    public void
            doInstallAttribution_withSourcesAcrossEnrollments_marksOneInstallFromEachRegOrigin() {
        long currentTimestamp = System.currentTimeMillis();

        // Enrollment1: Choose IA2 because that's newer and still occurred before install
        insertSource(
                createSourceForIATest(
                                "IA1",
                                currentTimestamp,
                                -1,
                                10,
                                false,
                                DEFAULT_ENROLLMENT_ID + "_1")
                        .setRegistrationOrigin(WebUtil.validUri("https://subdomain.example1.test"))
                        .build(),
                "IA1");
        insertSource(
                createSourceForIATest(
                                "IA2", currentTimestamp, -1, 9, false, DEFAULT_ENROLLMENT_ID + "_1")
                        .setRegistrationOrigin(WebUtil.validUri("https://subdomain.example1.test"))
                        .build(),
                "IA2");

        // Enrollment2: Choose IA4 because IA3's install attribution window has expired
        insertSource(
                createSourceForIATest(
                                "IA3", currentTimestamp, -1, 10, true, DEFAULT_ENROLLMENT_ID + "_2")
                        .setRegistrationOrigin(WebUtil.validUri("https://subdomain.example2.test"))
                        .build(),
                "IA3");
        insertSource(
                createSourceForIATest(
                                "IA4", currentTimestamp, -1, 9, false, DEFAULT_ENROLLMENT_ID + "_2")
                        .setRegistrationOrigin(WebUtil.validUri("https://subdomain.example2.test"))
                        .build(),
                "IA4");

        // Enrollment3: Choose IA5 because IA6 was registered after install event
        insertSource(
                createSourceForIATest(
                                "IA5",
                                currentTimestamp,
                                -1,
                                10,
                                false,
                                DEFAULT_ENROLLMENT_ID + "_3")
                        .setRegistrationOrigin(WebUtil.validUri("https://subdomain.example3.test"))
                        .build(),
                "IA5");
        insertSource(
                createSourceForIATest(
                                "IA6", currentTimestamp, -1, 5, false, DEFAULT_ENROLLMENT_ID + "_3")
                        .setRegistrationOrigin(WebUtil.validUri("https://subdomain.example3.test"))
                        .build(),
                "IA6");

        // Enrollment4: Choose IA8 due to higher priority
        insertSource(
                createSourceForIATest(
                                "IA7", currentTimestamp, 5, 10, false, DEFAULT_ENROLLMENT_ID + "_4")
                        .setRegistrationOrigin(WebUtil.validUri("https://subdomain.example4.test"))
                        .build(),
                "IA7");
        insertSource(
                createSourceForIATest(
                                "IA8",
                                currentTimestamp,
                                10,
                                10,
                                false,
                                DEFAULT_ENROLLMENT_ID + "_4")
                        .setRegistrationOrigin(WebUtil.validUri("https://subdomain.example4.test"))
                        .build(),
                "IA8");

        // Enrollment5: Choose none because both sources are ineligible
        // Expired install attribution window
        insertSource(
                createSourceForIATest(
                                "IA9", currentTimestamp, 5, 31, true, DEFAULT_ENROLLMENT_ID + "_5")
                        .setRegistrationOrigin(WebUtil.validUri("https://subdomain.example5.test"))
                        .build(),
                "IA9");
        // Registered after install attribution
        insertSource(
                createSourceForIATest(
                                "IA10",
                                currentTimestamp,
                                10,
                                3,
                                false,
                                DEFAULT_ENROLLMENT_ID + "_5")
                        .setRegistrationOrigin(WebUtil.validUri("https://subdomain.example5.test"))
                        .build(),
                "IA10");

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao -> {
                                    measurementDao.doInstallAttribution(
                                            INSTALLED_PACKAGE,
                                            currentTimestamp - TimeUnit.DAYS.toMillis(7));
                                }));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        assertTrue(getInstallAttributionStatus("IA2", db));
        assertTrue(getInstallAttributionStatus("IA4", db));
        assertTrue(getInstallAttributionStatus("IA5", db));
        assertTrue(getInstallAttributionStatus("IA8", db));

        assertFalse(getInstallAttributionStatus("IA1", db));
        assertFalse(getInstallAttributionStatus("IA3", db));
        assertFalse(getInstallAttributionStatus("IA6", db));
        assertFalse(getInstallAttributionStatus("IA7", db));
        assertFalse(getInstallAttributionStatus("IA9", db));
        assertFalse(getInstallAttributionStatus("IA10", db));

        removeSources(
                Arrays.asList(
                        "IA1", "IA2", "IA3", "IA4", "IA5", "IA6", "IA7", "IA8", "IA8", "IA10"),
                db);
    }

    @Test
    public void deleteSources_providedIds_deletesMatchingSourcesAndRelatedData()
            throws JSONException {
        // Setup - Creates the following -
        // source - S1, S2, S3, S4
        // trigger - T1, T2, T3, T4
        // event reports - E11, E12, E21, E22, E23, E33, E44
        // aggregate reports - AR11, AR12, AR21, AR34
        // attributions - ATT11, ATT12, ATT21, ATT22, ATT33, ATT44
        prepareDataForSourceAndTriggerDeletion();

        // Execution
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        measurementDao -> {
                            measurementDao.deleteSources(List.of("S1", "S2"));

                            assertThrows(
                                    DatastoreException.class,
                                    () -> {
                                        measurementDao.getSource("S1");
                                    });
                            assertThrows(
                                    DatastoreException.class,
                                    () -> {
                                        measurementDao.getSource("S2");
                                    });

                            assertNotNull(measurementDao.getSource("S3"));
                            assertNotNull(measurementDao.getSource("S4"));
                            assertNotNull(measurementDao.getTrigger("T1"));
                            assertNotNull(measurementDao.getTrigger("T2"));
                            assertNotNull(measurementDao.getTrigger("T3"));
                            assertNotNull(measurementDao.getTrigger("T4"));

                            assertThrows(
                                    DatastoreException.class,
                                    () -> {
                                        measurementDao.getEventReport("E11");
                                    });
                            assertThrows(
                                    DatastoreException.class,
                                    () -> {
                                        measurementDao.getEventReport("E12");
                                    });
                            assertThrows(
                                    DatastoreException.class,
                                    () -> {
                                        measurementDao.getEventReport("E21");
                                    });
                            assertThrows(
                                    DatastoreException.class,
                                    () -> {
                                        measurementDao.getEventReport("E22");
                                    });
                            assertThrows(
                                    DatastoreException.class,
                                    () -> {
                                        measurementDao.getEventReport("E23");
                                    });
                            assertNotNull(measurementDao.getEventReport("E33"));
                            assertNotNull(measurementDao.getEventReport("E44"));

                            assertThrows(
                                    DatastoreException.class,
                                    () -> {
                                        measurementDao.getAggregateReport("AR11");
                                    });
                            assertThrows(
                                    DatastoreException.class,
                                    () -> {
                                        measurementDao.getAggregateReport("AR12");
                                    });
                            assertThrows(
                                    DatastoreException.class,
                                    () -> {
                                        measurementDao.getAggregateReport("AR21");
                                    });
                            assertNotNull(measurementDao.getAggregateReport("AR34"));
                        });

        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        assertEquals(2, DatabaseUtils.queryNumEntries(db, AttributionContract.TABLE));
    }

    @Test
    public void deleteSource_providedId_deletesMatchingXnaIgnoredSource() {
        // Setup
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();

        Source s1 =
                SourceFixture.getValidSourceBuilder()
                        .setEventId(new UnsignedLong(1L))
                        .setId("S1")
                        .setEnrollmentId("1")
                        .build();

        ContentValues sourceValues = new ContentValues();
        sourceValues.put(SourceContract.ID, s1.getId());
        sourceValues.put(SourceContract.EVENT_ID, s1.getEventId().getValue());

        ContentValues xnaIgnoredSourceValues = new ContentValues();
        xnaIgnoredSourceValues.put(XnaIgnoredSourcesContract.SOURCE_ID, s1.getId());
        xnaIgnoredSourceValues.put(XnaIgnoredSourcesContract.ENROLLMENT_ID, s1.getEnrollmentId());

        // Execution
        db.insert(SourceContract.TABLE, null, sourceValues);
        db.insert(XnaIgnoredSourcesContract.TABLE, null, xnaIgnoredSourceValues);

        // Assertion
        assertEquals(1, DatabaseUtils.queryNumEntries(db, SourceContract.TABLE));
        assertEquals(1, DatabaseUtils.queryNumEntries(db, XnaIgnoredSourcesContract.TABLE));

        // Execution
        removeSources(Collections.singletonList(s1.getId()), db);

        // Assertion
        assertEquals(0, DatabaseUtils.queryNumEntries(db, SourceContract.TABLE));
        assertEquals(0, DatabaseUtils.queryNumEntries(db, XnaIgnoredSourcesContract.TABLE));
    }

    @Test
    public void deleteTriggers_providedIds_deletesMatchingTriggersAndRelatedData()
            throws JSONException {
        // Setup - Creates the following -
        // source - S1, S2, S3, S4
        // trigger - T1, T2, T3, T4
        // event reports - E11, E12, E21, E22, E23, E33, E44
        // aggregate reports - AR11, AR12, AR21, AR34
        // attributions - ATT11, ATT12, ATT21, ATT22, ATT33, ATT44
        prepareDataForSourceAndTriggerDeletion();

        // Execution
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        measurementDao -> {
                            measurementDao.deleteTriggers(List.of("T1", "T2"));

                            assertNotNull(measurementDao.getSource("S1"));
                            assertNotNull(measurementDao.getSource("S2"));
                            assertNotNull(measurementDao.getSource("S3"));
                            assertNotNull(measurementDao.getSource("S4"));
                            assertThrows(
                                    DatastoreException.class,
                                    () -> measurementDao.getTrigger("T1"));
                            assertThrows(
                                    DatastoreException.class,
                                    () -> measurementDao.getTrigger("T2"));
                            assertNotNull(measurementDao.getTrigger("T3"));
                            assertNotNull(measurementDao.getTrigger("T4"));

                            assertThrows(
                                    DatastoreException.class,
                                    () -> measurementDao.getEventReport("E11"));
                            assertThrows(
                                    DatastoreException.class,
                                    () -> measurementDao.getEventReport("E12"));
                            assertThrows(
                                    DatastoreException.class,
                                    () -> measurementDao.getEventReport("E21"));
                            assertThrows(
                                    DatastoreException.class,
                                    () -> measurementDao.getEventReport("E22"));
                            assertNotNull(measurementDao.getEventReport("E23"));
                            assertNotNull(measurementDao.getEventReport("E33"));
                            assertNotNull(measurementDao.getEventReport("E44"));

                            assertThrows(
                                    DatastoreException.class,
                                    () -> measurementDao.getAggregateReport("AR11"));
                            assertThrows(
                                    DatastoreException.class,
                                    () -> measurementDao.getAggregateReport("AR12"));
                            assertThrows(
                                    DatastoreException.class,
                                    () -> measurementDao.getAggregateReport("AR21"));

                            assertNotNull(measurementDao.getAggregateReport("AR34"));
                        });

        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        assertEquals(2, DatabaseUtils.queryNumEntries(db, AttributionContract.TABLE));
    }

    private void prepareDataForSourceAndTriggerDeletion() throws JSONException {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Source s1 =
                SourceFixture.getValidSourceBuilder()
                        .setEventId(new UnsignedLong(1L))
                        .setId("S1")
                        .build(); // deleted
        Source s2 =
                SourceFixture.getValidSourceBuilder()
                        .setEventId(new UnsignedLong(2L))
                        .setId("S2")
                        .build(); // deleted
        Source s3 =
                SourceFixture.getValidSourceBuilder()
                        .setEventId(new UnsignedLong(3L))
                        .setId("S3")
                        .build();
        Source s4 =
                SourceFixture.getValidSourceBuilder()
                        .setEventId(new UnsignedLong(4L))
                        .setId("S4")
                        .build();
        Trigger t1 =
                TriggerFixture.getValidTriggerBuilder()
                        .setEventTriggers(TriggerFixture.ValidTriggerParams.EVENT_TRIGGERS)
                        .setId("T1")
                        .build();
        Trigger t2 =
                TriggerFixture.getValidTriggerBuilder()
                        .setEventTriggers(TriggerFixture.ValidTriggerParams.EVENT_TRIGGERS)
                        .setId("T2")
                        .build();
        Trigger t3 =
                TriggerFixture.getValidTriggerBuilder()
                        .setEventTriggers(TriggerFixture.ValidTriggerParams.EVENT_TRIGGERS)
                        .setId("T3")
                        .build();
        Trigger t4 =
                TriggerFixture.getValidTriggerBuilder()
                        .setEventTriggers(TriggerFixture.ValidTriggerParams.EVENT_TRIGGERS)
                        .setId("T4")
                        .build();
        EventReport e11 = createEventReportForSourceAndTrigger("E11", s1, t1);
        EventReport e12 = createEventReportForSourceAndTrigger("E12", s1, t2);
        EventReport e21 = createEventReportForSourceAndTrigger("E21", s2, t1);
        EventReport e22 = createEventReportForSourceAndTrigger("E22", s2, t2);
        EventReport e23 = createEventReportForSourceAndTrigger("E23", s2, t3);
        EventReport e33 = createEventReportForSourceAndTrigger("E33", s3, t3);
        EventReport e44 = createEventReportForSourceAndTrigger("E44", s4, t4);
        AggregateReport ar11 = createAggregateReportForSourceAndTrigger("AR11", s1, t1);
        AggregateReport ar12 = createAggregateReportForSourceAndTrigger("AR12", s1, t2);
        AggregateReport ar21 = createAggregateReportForSourceAndTrigger("AR21", s2, t1);
        AggregateReport ar34 = createAggregateReportForSourceAndTrigger("AR34", s3, t4);
        Attribution att11 =
                createAttributionWithSourceAndTriggerIds(
                        "ATT11", s1.getId(), t1.getId()); // deleted
        Attribution att12 =
                createAttributionWithSourceAndTriggerIds(
                        "ATT12", s1.getId(), t2.getId()); // deleted
        Attribution att21 =
                createAttributionWithSourceAndTriggerIds(
                        "ATT21", s2.getId(), t1.getId()); // deleted
        Attribution att22 =
                createAttributionWithSourceAndTriggerIds(
                        "ATT22", s2.getId(), t2.getId()); // deleted
        Attribution att33 =
                createAttributionWithSourceAndTriggerIds("ATT33", s3.getId(), t3.getId());
        Attribution att44 =
                createAttributionWithSourceAndTriggerIds("ATT44", s4.getId(), t4.getId());

        insertSource(s1, s1.getId());
        insertSource(s2, s2.getId());
        insertSource(s3, s3.getId());
        insertSource(s4, s4.getId());

        AbstractDbIntegrationTest.insertToDb(t1, db);
        AbstractDbIntegrationTest.insertToDb(t2, db);
        AbstractDbIntegrationTest.insertToDb(t3, db);
        AbstractDbIntegrationTest.insertToDb(t4, db);

        AbstractDbIntegrationTest.insertToDb(e11, db);
        AbstractDbIntegrationTest.insertToDb(e12, db);
        AbstractDbIntegrationTest.insertToDb(e21, db);
        AbstractDbIntegrationTest.insertToDb(e22, db);
        AbstractDbIntegrationTest.insertToDb(e23, db);
        AbstractDbIntegrationTest.insertToDb(e33, db);
        AbstractDbIntegrationTest.insertToDb(e44, db);

        AbstractDbIntegrationTest.insertToDb(ar11, db);
        AbstractDbIntegrationTest.insertToDb(ar12, db);
        AbstractDbIntegrationTest.insertToDb(ar21, db);
        AbstractDbIntegrationTest.insertToDb(ar34, db);

        AbstractDbIntegrationTest.insertToDb(att11, db);
        AbstractDbIntegrationTest.insertToDb(att12, db);
        AbstractDbIntegrationTest.insertToDb(att21, db);
        AbstractDbIntegrationTest.insertToDb(att22, db);
        AbstractDbIntegrationTest.insertToDb(att33, db);
        AbstractDbIntegrationTest.insertToDb(att44, db);
    }

    @Test
    public void testUndoInstallAttribution_noMarkedSource() {
        long currentTimestamp = System.currentTimeMillis();
        Source source =
                createSourceForIATest("IA1", currentTimestamp, 10, 10, false, DEFAULT_ENROLLMENT_ID)
                        .build();
        source.setInstallAttributed(true);
        insertSource(source, source.getId());
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.undoInstallAttribution(INSTALLED_PACKAGE)));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        // Should set installAttributed = false for id=IA1
        assertFalse(getInstallAttributionStatus("IA1", db));
    }

    @Test
    public void undoInstallAttribution_uninstall_nullInstallTime() {
        long currentTimestamp = System.currentTimeMillis();
        Source source =
                createSourceForIATest("IA1", currentTimestamp, 10, 10, false, DEFAULT_ENROLLMENT_ID)
                        .build();
        source.setInstallAttributed(true);
        insertSource(source, source.getId());
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.undoInstallAttribution(INSTALLED_PACKAGE)));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        // Should set installTime = null for id=IA1
        assertNull(getInstallAttributionInstallTime("IA1", db));
    }

    @Test
    public void getSourceDestinations_returnsExpected() {
        // Insert two sources with some intersection of destinations
        // and assert all destination queries work.

        // First source
        List<Uri> webDestinations1 =
                List.of(
                        Uri.parse("https://first-place.test"),
                        Uri.parse("https://second-place.test"),
                        Uri.parse("https://third-place.test"));
        List<Uri> appDestinations1 = List.of(Uri.parse("android-app://test.first-place"));
        Source source1 =
                SourceFixture.getValidSourceBuilder()
                        .setId("1")
                        .setAppDestinations(appDestinations1)
                        .setWebDestinations(webDestinations1)
                        .build();
        insertSource(source1, source1.getId());

        // Second source
        List<Uri> webDestinations2 =
                List.of(
                        Uri.parse("https://not-first-place.test"),
                        Uri.parse("https://not-second-place.test"),
                        Uri.parse("https://third-place.test"));
        List<Uri> appDestinations2 = List.of(Uri.parse("android-app://test.not-first-place"));
        Source source2 =
                SourceFixture.getValidSourceBuilder()
                        .setId("2")
                        .setAppDestinations(appDestinations2)
                        .setWebDestinations(webDestinations2)
                        .build();
        insertSource(source2, source2.getId());

        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);

        Pair<List<Uri>, List<Uri>> result1 = dm.runInTransactionWithResult(
                measurementDao -> measurementDao.getSourceDestinations(source1.getId())).get();
        // Assert first app destinations
        assertTrue(
                ImmutableMultiset.copyOf(source1.getAppDestinations())
                        .equals(ImmutableMultiset.copyOf(result1.first)));
        // Assert first web destinations
        assertTrue(
                ImmutableMultiset.copyOf(source1.getWebDestinations())
                        .equals(ImmutableMultiset.copyOf(result1.second)));

        Pair<List<Uri>, List<Uri>> result2 = dm.runInTransactionWithResult(
                measurementDao -> measurementDao.getSourceDestinations(source2.getId())).get();
        // Assert second app destinations
        assertTrue(
                ImmutableMultiset.copyOf(source2.getAppDestinations())
                        .equals(ImmutableMultiset.copyOf(result2.first)));
        // Assert second web destinations
        assertTrue(
                ImmutableMultiset.copyOf(source2.getWebDestinations())
                        .equals(ImmutableMultiset.copyOf(result2.second)));
    }

    @Test
    public void getNumAggregateReportsPerDestination_returnsExpected() {
        List<AggregateReport> reportsWithPlainDestination =
                Arrays.asList(
                        generateMockAggregateReport(
                                WebUtil.validUrl("https://destination-1.test"), 1));
        List<AggregateReport> reportsWithPlainAndSubDomainDestination =
                Arrays.asList(
                        generateMockAggregateReport(
                                WebUtil.validUrl("https://destination-2.test"), 2),
                        generateMockAggregateReport(
                                WebUtil.validUrl("https://subdomain.destination-2.test"), 3));
        List<AggregateReport> reportsWithPlainAndPathDestination =
                Arrays.asList(
                        generateMockAggregateReport(
                                WebUtil.validUrl("https://subdomain.destination-3.test"), 4),
                        generateMockAggregateReport(
                                WebUtil.validUrl("https://subdomain.destination-3.test/abcd"), 5));
        List<AggregateReport> reportsWithAll3Types =
                Arrays.asList(
                        generateMockAggregateReport(
                                WebUtil.validUrl("https://destination-4.test"), 6),
                        generateMockAggregateReport(
                                WebUtil.validUrl("https://subdomain.destination-4.test"), 7),
                        generateMockAggregateReport(
                                WebUtil.validUrl("https://subdomain.destination-4.test/abcd"), 8));
        List<AggregateReport> reportsWithAndroidAppDestination =
                Arrays.asList(generateMockAggregateReport("android-app://destination-5.app", 9));

        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Objects.requireNonNull(db);
        Stream.of(
                        reportsWithPlainDestination,
                        reportsWithPlainAndSubDomainDestination,
                        reportsWithPlainAndPathDestination,
                        reportsWithAll3Types,
                        reportsWithAndroidAppDestination)
                .flatMap(Collection::stream)
                .forEach(
                        aggregateReport -> {
                            ContentValues values = new ContentValues();
                            values.put(
                                    MeasurementTables.AggregateReport.ID, aggregateReport.getId());
                            values.put(
                                    MeasurementTables.AggregateReport.ATTRIBUTION_DESTINATION,
                                    aggregateReport.getAttributionDestination().toString());
                            db.insert(MeasurementTables.AggregateReport.TABLE, null, values);
                        });

        List<String> attributionDestinations1 = createWebDestinationVariants(1);
        List<String> attributionDestinations2 = createWebDestinationVariants(2);
        List<String> attributionDestinations3 = createWebDestinationVariants(3);
        List<String> attributionDestinations4 = createWebDestinationVariants(4);
        List<String> attributionDestinations5 = createAppDestinationVariants(5);

        // expected query return values for attribution destination variants
        List<Integer> destination1ExpectedCounts = Arrays.asList(1, 1, 1, 1, 0);
        List<Integer> destination2ExpectedCounts = Arrays.asList(2, 2, 2, 2, 0);
        List<Integer> destination3ExpectedCounts = Arrays.asList(2, 2, 2, 2, 0);
        List<Integer> destination4ExpectedCounts = Arrays.asList(3, 3, 3, 3, 0);
        List<Integer> destination5ExpectedCounts = Arrays.asList(0, 0, 1, 1, 0);
        assertAggregateReportCount(
                attributionDestinations1, EventSurfaceType.WEB, destination1ExpectedCounts);
        assertAggregateReportCount(
                attributionDestinations2, EventSurfaceType.WEB, destination2ExpectedCounts);
        assertAggregateReportCount(
                attributionDestinations3, EventSurfaceType.WEB, destination3ExpectedCounts);
        assertAggregateReportCount(
                attributionDestinations4, EventSurfaceType.WEB, destination4ExpectedCounts);
        assertAggregateReportCount(
                attributionDestinations5, EventSurfaceType.APP, destination5ExpectedCounts);
    }

    @Test
    public void getNumEventReportsPerDestination_returnsExpected() {
        List<EventReport> reportsWithPlainDestination =
                Arrays.asList(
                        generateMockEventReport(WebUtil.validUrl("https://destination-1.test"), 1));
        List<EventReport> reportsWithPlainAndSubDomainDestination =
                Arrays.asList(
                        generateMockEventReport(WebUtil.validUrl("https://destination-2.test"), 2),
                        generateMockEventReport(
                                WebUtil.validUrl("https://subdomain.destination-2.test"), 3));
        List<EventReport> reportsWithPlainAndPathDestination =
                Arrays.asList(
                        generateMockEventReport(
                                WebUtil.validUrl("https://subdomain.destination-3.test"), 4),
                        generateMockEventReport(
                                WebUtil.validUrl("https://subdomain.destination-3.test/abcd"), 5));
        List<EventReport> reportsWithAll3Types =
                Arrays.asList(
                        generateMockEventReport(WebUtil.validUrl("https://destination-4.test"), 6),
                        generateMockEventReport(
                                WebUtil.validUrl("https://subdomain.destination-4.test"), 7),
                        generateMockEventReport(
                                WebUtil.validUrl("https://subdomain.destination-4.test/abcd"), 8));
        List<EventReport> reportsWithAndroidAppDestination =
                Arrays.asList(generateMockEventReport("android-app://destination-5.app", 9));

        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Objects.requireNonNull(db);
        Stream.of(
                        reportsWithPlainDestination,
                        reportsWithPlainAndSubDomainDestination,
                        reportsWithPlainAndPathDestination,
                        reportsWithAll3Types,
                        reportsWithAndroidAppDestination)
                .flatMap(Collection::stream)
                .forEach(
                        eventReport -> {
                            ContentValues values = new ContentValues();
                            values.put(
                                    MeasurementTables.EventReportContract.ID, eventReport.getId());
                            values.put(
                                    MeasurementTables.EventReportContract.ATTRIBUTION_DESTINATION,
                                    eventReport.getAttributionDestinations().get(0).toString());
                            db.insert(MeasurementTables.EventReportContract.TABLE, null, values);
                        });

        List<String> attributionDestinations1 = createWebDestinationVariants(1);
        List<String> attributionDestinations2 = createWebDestinationVariants(2);
        List<String> attributionDestinations3 = createWebDestinationVariants(3);
        List<String> attributionDestinations4 = createWebDestinationVariants(4);
        List<String> attributionDestinations5 = createAppDestinationVariants(5);

        // expected query return values for attribution destination variants
        List<Integer> destination1ExpectedCounts = Arrays.asList(1, 1, 1, 1, 0);
        List<Integer> destination2ExpectedCounts = Arrays.asList(2, 2, 2, 2, 0);
        List<Integer> destination3ExpectedCounts = Arrays.asList(2, 2, 2, 2, 0);
        List<Integer> destination4ExpectedCounts = Arrays.asList(3, 3, 3, 3, 0);
        List<Integer> destination5ExpectedCounts = Arrays.asList(0, 0, 1, 1, 0);
        assertEventReportCount(
                attributionDestinations1, EventSurfaceType.WEB, destination1ExpectedCounts);
        assertEventReportCount(
                attributionDestinations2, EventSurfaceType.WEB, destination2ExpectedCounts);
        assertEventReportCount(
                attributionDestinations3, EventSurfaceType.WEB, destination3ExpectedCounts);
        assertEventReportCount(
                attributionDestinations4, EventSurfaceType.WEB, destination4ExpectedCounts);
        assertEventReportCount(
                attributionDestinations5, EventSurfaceType.APP, destination5ExpectedCounts);
    }

    @Test
    public void testGetSourceEventReports() {
        List<Source> sourceList =
                Arrays.asList(
                        SourceFixture.getValidSourceBuilder()
                                .setId("1")
                                .setEventId(new UnsignedLong(3L))
                                .setEnrollmentId("1")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setId("2")
                                .setEventId(new UnsignedLong(4L))
                                .setEnrollmentId("1")
                                .build(),
                        // Should always be ignored
                        SourceFixture.getValidSourceBuilder()
                                .setId("3")
                                .setEventId(new UnsignedLong(4L))
                                .setEnrollmentId("2")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setId("15")
                                .setEventId(new UnsignedLong(15L))
                                .setEnrollmentId("2")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setId("16")
                                .setEventId(new UnsignedLong(16L))
                                .setEnrollmentId("2")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setId("20")
                                .setEventId(new UnsignedLong(20L))
                                .setEnrollmentId("2")
                                .build());

        List<Trigger> triggers =
                Arrays.asList(
                        TriggerFixture.getValidTriggerBuilder()
                                .setId("101")
                                .setEnrollmentId("2")
                                .build(),
                        TriggerFixture.getValidTriggerBuilder()
                                .setId("102")
                                .setEnrollmentId("2")
                                .build(),
                        TriggerFixture.getValidTriggerBuilder()
                                .setId("201")
                                .setEnrollmentId("2")
                                .build(),
                        TriggerFixture.getValidTriggerBuilder()
                                .setId("202")
                                .setEnrollmentId("2")
                                .build(),
                        TriggerFixture.getValidTriggerBuilder()
                                .setId("1001")
                                .setEnrollmentId("2")
                                .build());

        // Should match with source 1
        List<EventReport> reportList1 = new ArrayList<>();
        reportList1.add(
                new EventReport.Builder()
                        .setId("1")
                        .setSourceEventId(new UnsignedLong(3L))
                        .setEnrollmentId("1")
                        .setAttributionDestinations(sourceList.get(0).getAppDestinations())
                        .setSourceType(sourceList.get(0).getSourceType())
                        .setSourceId("1")
                        .setTriggerId("101")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        reportList1.add(
                new EventReport.Builder()
                        .setId("7")
                        .setSourceEventId(new UnsignedLong(3L))
                        .setEnrollmentId("1")
                        .setAttributionDestinations(List.of(APP_DESTINATION))
                        .setSourceType(sourceList.get(0).getSourceType())
                        .setSourceId("1")
                        .setTriggerId("102")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());

        // Should match with source 2
        List<EventReport> reportList2 = new ArrayList<>();
        reportList2.add(
                new EventReport.Builder()
                        .setId("3")
                        .setSourceEventId(new UnsignedLong(4L))
                        .setEnrollmentId("1")
                        .setAttributionDestinations(sourceList.get(1).getAppDestinations())
                        .setSourceType(sourceList.get(1).getSourceType())
                        .setSourceId("2")
                        .setTriggerId("201")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        reportList2.add(
                new EventReport.Builder()
                        .setId("8")
                        .setSourceEventId(new UnsignedLong(4L))
                        .setEnrollmentId("1")
                        .setAttributionDestinations(sourceList.get(1).getAppDestinations())
                        .setSourceType(sourceList.get(1).getSourceType())
                        .setSourceId("2")
                        .setTriggerId("202")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());

        List<EventReport> reportList3 = new ArrayList<>();
        // Should not match with any source
        reportList3.add(
                new EventReport.Builder()
                        .setId("2")
                        .setSourceEventId(new UnsignedLong(5L))
                        .setEnrollmentId("1")
                        .setSourceType(Source.SourceType.EVENT)
                        .setAttributionDestinations(List.of(APP_DESTINATION))
                        .setSourceId("15")
                        .setTriggerId("1001")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        reportList3.add(
                new EventReport.Builder()
                        .setId("4")
                        .setSourceEventId(new UnsignedLong(6L))
                        .setEnrollmentId("1")
                        .setSourceType(Source.SourceType.EVENT)
                        .setAttributionDestinations(List.of(APP_DESTINATION))
                        .setSourceId("16")
                        .setTriggerId("1001")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        reportList3.add(
                new EventReport.Builder()
                        .setId("5")
                        .setSourceEventId(new UnsignedLong(1L))
                        .setEnrollmentId("1")
                        .setSourceType(Source.SourceType.EVENT)
                        .setAttributionDestinations(List.of(APP_DESTINATION))
                        .setSourceId("15")
                        .setTriggerId("1001")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        reportList3.add(
                new EventReport.Builder()
                        .setId("6")
                        .setSourceEventId(new UnsignedLong(2L))
                        .setEnrollmentId("1")
                        .setSourceType(Source.SourceType.EVENT)
                        .setAttributionDestinations(List.of(APP_DESTINATION))
                        .setSourceId("20")
                        .setTriggerId("1001")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());

        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Objects.requireNonNull(db);
        sourceList.forEach(source -> insertSource(source, source.getId()));
        triggers.forEach(trigger -> AbstractDbIntegrationTest.insertToDb(trigger, db));

        Stream.of(reportList1, reportList2, reportList3)
                .flatMap(Collection::stream)
                .forEach(
                        (eventReport -> {
                            DatastoreManagerFactory.getDatastoreManager(sContext)
                                    .runInTransaction((dao) -> dao.insertEventReport(eventReport));
                        }));

        assertEquals(
                reportList1,
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.getSourceEventReports(sourceList.get(0)))
                        .orElseThrow());

        assertEquals(
                reportList2,
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.getSourceEventReports(sourceList.get(1)))
                        .orElseThrow());
    }

    @Test
    public void getSourceEventReports_sourcesWithSameEventId_haveSeparateEventReportsMatch() {
        List<Source> sourceList =
                Arrays.asList(
                        SourceFixture.getValidSourceBuilder()
                                .setId("1")
                                .setEventId(new UnsignedLong(1L))
                                .setEnrollmentId("1")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setId("2")
                                .setEventId(new UnsignedLong(1L))
                                .setEnrollmentId("1")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setId("3")
                                .setEventId(new UnsignedLong(2L))
                                .setEnrollmentId("2")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setId("4")
                                .setEventId(new UnsignedLong(2L))
                                .setEnrollmentId("2")
                                .build());

        List<Trigger> triggers =
                Arrays.asList(
                        TriggerFixture.getValidTriggerBuilder()
                                .setId("101")
                                .setEnrollmentId("2")
                                .build(),
                        TriggerFixture.getValidTriggerBuilder()
                                .setId("102")
                                .setEnrollmentId("2")
                                .build());

        // Should match with source 1
        List<EventReport> reportList1 = new ArrayList<>();
        reportList1.add(
                new EventReport.Builder()
                        .setId("1")
                        .setSourceEventId(new UnsignedLong(1L))
                        .setEnrollmentId("1")
                        .setAttributionDestinations(sourceList.get(0).getAppDestinations())
                        .setSourceType(sourceList.get(0).getSourceType())
                        .setSourceId("1")
                        .setTriggerId("101")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        reportList1.add(
                new EventReport.Builder()
                        .setId("2")
                        .setSourceEventId(new UnsignedLong(1L))
                        .setEnrollmentId("1")
                        .setAttributionDestinations(List.of(APP_DESTINATION))
                        .setSourceType(sourceList.get(0).getSourceType())
                        .setSourceId("1")
                        .setTriggerId("102")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());

        // Should match with source 2
        List<EventReport> reportList2 = new ArrayList<>();
        reportList2.add(
                new EventReport.Builder()
                        .setId("3")
                        .setSourceEventId(new UnsignedLong(2L))
                        .setEnrollmentId("1")
                        .setAttributionDestinations(sourceList.get(1).getAppDestinations())
                        .setSourceType(sourceList.get(1).getSourceType())
                        .setSourceId("2")
                        .setTriggerId("101")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        reportList2.add(
                new EventReport.Builder()
                        .setId("4")
                        .setSourceEventId(new UnsignedLong(2L))
                        .setEnrollmentId("1")
                        .setAttributionDestinations(sourceList.get(1).getAppDestinations())
                        .setSourceType(sourceList.get(1).getSourceType())
                        .setSourceId("2")
                        .setTriggerId("102")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());

        // Match with source3
        List<EventReport> reportList3 = new ArrayList<>();
        reportList3.add(
                new EventReport.Builder()
                        .setId("5")
                        .setSourceEventId(new UnsignedLong(2L))
                        .setEnrollmentId("2")
                        .setSourceType(Source.SourceType.EVENT)
                        .setAttributionDestinations(List.of(APP_DESTINATION))
                        .setSourceId("3")
                        .setTriggerId("101")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        reportList3.add(
                new EventReport.Builder()
                        .setId("6")
                        .setSourceEventId(new UnsignedLong(2L))
                        .setEnrollmentId("2")
                        .setSourceType(Source.SourceType.EVENT)
                        .setAttributionDestinations(List.of(APP_DESTINATION))
                        .setSourceId("3")
                        .setTriggerId("102")
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());

        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Objects.requireNonNull(db);
        sourceList.forEach(source -> insertSource(source, source.getId()));
        triggers.forEach(trigger -> AbstractDbIntegrationTest.insertToDb(trigger, db));

        Stream.of(reportList1, reportList2, reportList3)
                .flatMap(Collection::stream)
                .forEach(
                        (eventReport -> {
                            DatastoreManagerFactory.getDatastoreManager(sContext)
                                    .runInTransaction((dao) -> dao.insertEventReport(eventReport));
                        }));

        assertEquals(
                reportList1,
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.getSourceEventReports(sourceList.get(0)))
                        .orElseThrow());

        assertEquals(
                reportList2,
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.getSourceEventReports(sourceList.get(1)))
                        .orElseThrow());

        assertEquals(
                reportList3,
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.getSourceEventReports(sourceList.get(2)))
                        .orElseThrow());
    }

    @Test
    public void testUpdateSourceStatus() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Objects.requireNonNull(db);

        List<Source> sourceList = new ArrayList<>();
        sourceList.add(SourceFixture.getValidSourceBuilder().setId("1").build());
        sourceList.add(SourceFixture.getValidSourceBuilder().setId("2").build());
        sourceList.add(SourceFixture.getValidSourceBuilder().setId("3").build());
        sourceList.forEach(
                source -> {
                    ContentValues values = new ContentValues();
                    values.put(SourceContract.ID, source.getId());
                    values.put(SourceContract.STATUS, 1);
                    db.insert(SourceContract.TABLE, null, values);
                });

        // Multiple Elements
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.updateSourceStatus(
                                                List.of("1", "2", "3"), Source.Status.IGNORED)));

        // Single Element
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.updateSourceStatus(
                                                List.of("1", "2"), Source.Status.IGNORED)));
    }

    @Test
    public void testGetMatchingActiveSources() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Objects.requireNonNull(db);
        String enrollmentId = "enrollment-id";
        Uri appDestination = Uri.parse("android-app://com.example.abc");
        Uri webDestination = WebUtil.validUri("https://example.test");
        Uri webDestinationWithSubdomain = WebUtil.validUri("https://xyz.example.test");
        Source sApp1 =
                SourceFixture.getValidSourceBuilder()
                        .setId("1")
                        .setEventTime(10)
                        .setExpiryTime(20)
                        .setAppDestinations(List.of(appDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sApp2 =
                SourceFixture.getValidSourceBuilder()
                        .setId("2")
                        .setEventTime(10)
                        .setExpiryTime(50)
                        .setAppDestinations(List.of(appDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sApp3 =
                SourceFixture.getValidSourceBuilder()
                        .setId("3")
                        .setEventTime(20)
                        .setExpiryTime(50)
                        .setAppDestinations(List.of(appDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sApp4 =
                SourceFixture.getValidSourceBuilder()
                        .setId("4")
                        .setEventTime(30)
                        .setExpiryTime(50)
                        .setAppDestinations(List.of(appDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sWeb5 =
                SourceFixture.getValidSourceBuilder()
                        .setId("5")
                        .setEventTime(10)
                        .setExpiryTime(20)
                        .setWebDestinations(List.of(webDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sWeb6 =
                SourceFixture.getValidSourceBuilder()
                        .setId("6")
                        .setEventTime(10)
                        .setExpiryTime(50)
                        .setWebDestinations(List.of(webDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sAppWeb7 =
                SourceFixture.getValidSourceBuilder()
                        .setId("7")
                        .setEventTime(10)
                        .setExpiryTime(20)
                        .setAppDestinations(List.of(appDestination))
                        .setWebDestinations(List.of(webDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();

        List<Source> sources = Arrays.asList(sApp1, sApp2, sApp3, sApp4, sWeb5, sWeb6, sAppWeb7);
        sources.forEach(source -> insertInDb(db, source));

        Function<Trigger, List<Source>> runFunc =
                trigger -> {
                    List<Source> result =
                            DatastoreManagerFactory.getDatastoreManager(sContext)
                                    .runInTransactionWithResult(
                                            measurementDao ->
                                                    measurementDao.getMatchingActiveSources(
                                                            trigger))
                                    .orElseThrow();
                    result.sort(Comparator.comparing(Source::getId));
                    return result;
                };

        // Trigger Time > sApp1's eventTime and < sApp1's expiryTime
        // Trigger Time > sApp2's eventTime and < sApp2's expiryTime
        // Trigger Time < sApp3's eventTime
        // Trigger Time < sApp4's eventTime
        // sApp5 and sApp6 don't have app destination
        // Trigger Time > sAppWeb7's eventTime and < sAppWeb7's expiryTime
        // Expected: Match with sApp1, sApp2, sAppWeb7
        Trigger trigger1MatchSource1And2 =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(12)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .build();
        List<Source> result1 = runFunc.apply(trigger1MatchSource1And2);
        assertEquals(3, result1.size());
        assertEquals(sApp1.getId(), result1.get(0).getId());
        assertEquals(sApp2.getId(), result1.get(1).getId());
        assertEquals(sAppWeb7.getId(), result1.get(2).getId());

        // Trigger Time > sApp1's eventTime and = sApp1's expiryTime
        // Trigger Time > sApp2's eventTime and < sApp2's expiryTime
        // Trigger Time = sApp3's eventTime
        // Trigger Time < sApp4's eventTime
        // sApp5 and sApp6 don't have app destination
        // Trigger Time > sAppWeb7's eventTime and = sAppWeb7's expiryTime
        // Expected: Match with sApp2, sApp3
        Trigger trigger2MatchSource127 =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(20)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .build();

        List<Source> result2 = runFunc.apply(trigger2MatchSource127);
        assertEquals(2, result2.size());
        assertEquals(sApp2.getId(), result2.get(0).getId());
        assertEquals(sApp3.getId(), result2.get(1).getId());

        // Trigger Time > sApp1's expiryTime
        // Trigger Time > sApp2's eventTime and < sApp2's expiryTime
        // Trigger Time > sApp3's eventTime and < sApp3's expiryTime
        // Trigger Time < sApp4's eventTime
        // sApp5 and sApp6 don't have app destination
        // Trigger Time > sAppWeb7's expiryTime
        // Expected: Match with sApp2, sApp3
        Trigger trigger3MatchSource237 =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(21)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .build();

        List<Source> result3 = runFunc.apply(trigger3MatchSource237);
        assertEquals(2, result3.size());
        assertEquals(sApp2.getId(), result3.get(0).getId());
        assertEquals(sApp3.getId(), result3.get(1).getId());

        // Trigger Time > sApp1's expiryTime
        // Trigger Time > sApp2's eventTime and < sApp2's expiryTime
        // Trigger Time > sApp3's eventTime and < sApp3's expiryTime
        // Trigger Time > sApp4's eventTime and < sApp4's expiryTime
        // sApp5 and sApp6 don't have app destination
        // Trigger Time > sAppWeb7's expiryTime
        // Expected: Match with sApp2, sApp3 and sApp4
        Trigger trigger4MatchSource1And2And3 =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(31)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .build();

        List<Source> result4 = runFunc.apply(trigger4MatchSource1And2And3);
        assertEquals(3, result4.size());
        assertEquals(sApp2.getId(), result4.get(0).getId());
        assertEquals(sApp3.getId(), result4.get(1).getId());
        assertEquals(sApp4.getId(), result4.get(2).getId());

        // sApp1, sApp2, sApp3, sApp4 don't have web destination
        // Trigger Time > sWeb5's eventTime and < sApp5's expiryTime
        // Trigger Time > sWeb6's eventTime and < sApp6's expiryTime
        // Trigger Time > sAppWeb7's eventTime and < sAppWeb7's expiryTime
        // Expected: Match with sApp5, sApp6, sAppWeb7
        Trigger trigger5MatchSource567 =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(12)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(webDestination)
                        .setDestinationType(EventSurfaceType.WEB)
                        .build();
        List<Source> result5 = runFunc.apply(trigger5MatchSource567);
        assertEquals(3, result1.size());
        assertEquals(sWeb5.getId(), result5.get(0).getId());
        assertEquals(sWeb6.getId(), result5.get(1).getId());
        assertEquals(sAppWeb7.getId(), result5.get(2).getId());

        // sApp1, sApp2, sApp3, sApp4 don't have web destination
        // Trigger Time > sWeb5's expiryTime
        // Trigger Time > sWeb6's eventTime and < sApp6's expiryTime
        // Trigger Time > sWeb7's expiryTime
        // Expected: Match with sApp6 only
        Trigger trigger6MatchSource67 =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(21)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(webDestinationWithSubdomain)
                        .setDestinationType(EventSurfaceType.WEB)
                        .build();

        List<Source> result6 = runFunc.apply(trigger6MatchSource67);
        assertEquals(1, result6.size());
        assertEquals(sWeb6.getId(), result6.get(0).getId());

        // Trigger with different subdomain than source
        // Expected: No Match found
        Trigger triggerDifferentRegistrationOrigin =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(12)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .setRegistrationOrigin(
                                WebUtil.validUri("https://subdomain-different.example.test"))
                        .build();

        List<Source> result7 = runFunc.apply(triggerDifferentRegistrationOrigin);
        assertTrue(result7.isEmpty());

        // Trigger with different domain than source
        // Expected: No Match found
        Trigger triggerDifferentDomainOrigin =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(12)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .setRegistrationOrigin(
                                WebUtil.validUri("https://subdomain.example-different.test"))
                        .build();

        List<Source> result8 = runFunc.apply(triggerDifferentDomainOrigin);
        assertTrue(result8.isEmpty());

        // Trigger with different port than source
        // Expected: No Match found
        Trigger triggerDifferentPort =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(12)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .setRegistrationOrigin(
                                WebUtil.validUri("https://subdomain.example.test:8083"))
                        .build();

        List<Source> result9 = runFunc.apply(triggerDifferentPort);
        assertTrue(result9.isEmpty());

        // Enrollment id for trigger and source not same
        // Registration Origin for trigger and source same
        // Expected: Match with sApp1, sApp2, sAppWeb7
        Trigger triggerDifferentEnrollmentSameRegistration =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(12)
                        .setEnrollmentId("different-enrollment-id")
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .build();
        List<Source> result10 = runFunc.apply(triggerDifferentEnrollmentSameRegistration);
        assertEquals(3, result10.size());
        assertEquals(sApp1.getId(), result10.get(0).getId());
        assertEquals(sApp2.getId(), result10.get(1).getId());
        assertEquals(sAppWeb7.getId(), result10.get(2).getId());
    }

    @Test
    public void testGetMatchingActiveSources_multipleDestinations() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        String enrollmentId = "enrollment-id";
        Uri webDestination1 = WebUtil.validUri("https://example.test");
        Uri webDestination1WithSubdomain = WebUtil.validUri("https://xyz.example.test");
        Uri webDestination2 = WebUtil.validUri("https://example2.test");
        Source sWeb1 =
                SourceFixture.getValidSourceBuilder()
                        .setId("1")
                        .setEventTime(10)
                        .setExpiryTime(20)
                        .setWebDestinations(List.of(webDestination1))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sWeb2 =
                SourceFixture.getValidSourceBuilder()
                        .setId("2")
                        .setEventTime(10)
                        .setExpiryTime(50)
                        .setWebDestinations(List.of(webDestination1, webDestination2))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sAppWeb3 =
                SourceFixture.getValidSourceBuilder()
                        .setId("3")
                        .setEventTime(10)
                        .setExpiryTime(20)
                        .setWebDestinations(List.of(webDestination1))
                        .setEnrollmentId(enrollmentId)
                        .build();

        List<Source> sources = Arrays.asList(sWeb1, sWeb2, sAppWeb3);
        sources.forEach(source -> insertInDb(db, source));

        Function<Trigger, List<Source>> getMatchingSources =
                trigger -> {
                    List<Source> result =
                            DatastoreManagerFactory.getDatastoreManager(sContext)
                                    .runInTransactionWithResult(
                                            measurementDao ->
                                                    measurementDao.getMatchingActiveSources(
                                                            trigger))
                                    .orElseThrow();
                    result.sort(Comparator.comparing(Source::getId));
                    return result;
                };

        Trigger triggerMatchSourceWeb2 =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(21)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(webDestination1WithSubdomain)
                        .setDestinationType(EventSurfaceType.WEB)
                        .build();

        List<Source> result = getMatchingSources.apply(triggerMatchSourceWeb2);
        assertEquals(1, result.size());
        assertEquals(sWeb2.getId(), result.get(0).getId());
    }

    @Test
    public void testGetMatchingActiveDelayedSources() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Objects.requireNonNull(db);
        String enrollmentId = "enrollment-id";
        Uri appDestination = Uri.parse("android-app://com.example.abc");
        Uri webDestination = WebUtil.validUri("https://example.test");
        Source sApp1 =
                SourceFixture.getValidSourceBuilder()
                        .setId("1")
                        .setEventTime(10)
                        .setExpiryTime(20)
                        .setAppDestinations(List.of(appDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sApp2 =
                SourceFixture.getValidSourceBuilder()
                        .setId("2")
                        .setEventTime(140)
                        .setExpiryTime(200)
                        .setAppDestinations(List.of(appDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sApp3 =
                SourceFixture.getValidSourceBuilder()
                        .setId("3")
                        .setEventTime(20)
                        .setExpiryTime(50)
                        .setAppDestinations(List.of(appDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sApp4 =
                SourceFixture.getValidSourceBuilder()
                        .setId("4")
                        .setEventTime(16)
                        .setExpiryTime(50)
                        .setAppDestinations(List.of(appDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sWeb5 =
                SourceFixture.getValidSourceBuilder()
                        .setId("5")
                        .setEventTime(13)
                        .setExpiryTime(20)
                        .setWebDestinations(List.of(webDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sWeb6 =
                SourceFixture.getValidSourceBuilder()
                        .setId("6")
                        .setEventTime(14)
                        .setExpiryTime(50)
                        .setWebDestinations(List.of(webDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sAppWeb7 =
                SourceFixture.getValidSourceBuilder()
                        .setId("7")
                        .setEventTime(10)
                        .setExpiryTime(20)
                        .setAppDestinations(List.of(appDestination))
                        .setWebDestinations(List.of(webDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();
        Source sAppWeb8 =
                SourceFixture.getValidSourceBuilder()
                        .setId("8")
                        .setEventTime(15)
                        .setExpiryTime(25)
                        .setAppDestinations(List.of(appDestination))
                        .setWebDestinations(List.of(webDestination))
                        .setEnrollmentId(enrollmentId)
                        .build();

        List<Source> sources =
                Arrays.asList(sApp1, sApp2, sApp3, sApp4, sWeb5, sWeb6, sAppWeb7, sAppWeb8);
        sources.forEach(source -> insertInDb(db, source));

        Function<Trigger, Optional<Source>> runFunc =
                trigger -> {
                    Optional<Source> result =
                            DatastoreManagerFactory.getDatastoreManager(sContext)
                                    .runInTransactionWithResult(
                                            measurementDao ->
                                                    measurementDao
                                                            .getNearestDelayedMatchingActiveSource(
                                                                    trigger))
                                    .orElseThrow();
                    return result;
                };

        // sApp1's eventTime <= Trigger Time
        // Trigger Time + MAX_DELAYED_SOURCE_REGISTRATION_WINDOW > sApp2's eventTime
        // Trigger Time < sApp3's eventTime <= Trigger Time + MAX_DELAYED_SOURCE_REGISTRATION_WINDOW
        // Trigger Time < sApp4's eventTime <= Trigger Time + MAX_DELAYED_SOURCE_REGISTRATION_WINDOW
        // sWeb5 and sWeb6 don't have app destination
        // sAppWeb7's eventTime <= Trigger Time
        // Trigger Time < sAppWeb8's eventTime <= Trigger Time +
        // MAX_DELAYED_SOURCE_REGISTRATION_WINDOW
        // Expected: Match with sAppWeb8
        Trigger trigger1MatchSource8 =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(12)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .build();
        Optional<Source> result1 = runFunc.apply(trigger1MatchSource8);
        assertEquals(sAppWeb8.getId(), result1.get().getId());

        // sApp1's eventTime <= Trigger Time
        // Trigger Time + MAX_DELAYED_SOURCE_REGISTRATION_WINDOW > sApp2's eventTime
        // Trigger Time < sApp3's eventTime <= Trigger Time + MAX_DELAYED_SOURCE_REGISTRATION_WINDOW
        // Trigger Time < sApp4's eventTime <= Trigger Time + MAX_DELAYED_SOURCE_REGISTRATION_WINDOW
        // sWeb5 and sWeb6 don't have app destination
        // sAppWeb7's eventTime <= Trigger Time
        // sAppWeb8's eventTime <= Trigger Time
        // Expected: Match with sApp4
        Trigger trigger2MatchSource4 =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(15)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .build();
        Optional<Source> result2 = runFunc.apply(trigger2MatchSource4);
        assertEquals(sApp4.getId(), result2.get().getId());

        // sApp1's eventTime <= Trigger Time
        // sApp2's eventTime <= Trigger Time
        // sApp3's eventTime <= Trigger Time
        // sApp4's eventTime <= Trigger Time
        // sWeb5 and sWeb6 don't have app destination
        // sAppWeb7's eventTime <= Trigger Time
        // sAppWeb8's eventTime <= Trigger Time
        // Expected: no match
        Trigger trigger3NoMatchingSource =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(150)
                        .setEnrollmentId(enrollmentId)
                        .setAttributionDestination(appDestination)
                        .setDestinationType(EventSurfaceType.APP)
                        .build();
        Optional<Source> result3 = runFunc.apply(trigger3NoMatchingSource);
        assertFalse(result3.isPresent());
    }

    private void insertInDb(SQLiteDatabase db, Source source) {
        ContentValues values = new ContentValues();
        values.put(SourceContract.ID, source.getId());
        values.put(SourceContract.STATUS, Source.Status.ACTIVE);
        values.put(SourceContract.EVENT_TIME, source.getEventTime());
        values.put(SourceContract.EXPIRY_TIME, source.getExpiryTime());
        values.put(SourceContract.ENROLLMENT_ID, source.getEnrollmentId());
        values.put(SourceContract.PUBLISHER, source.getPublisher().toString());
        values.put(SourceContract.REGISTRANT, source.getRegistrant().toString());
        values.put(SourceContract.REGISTRATION_ORIGIN, source.getRegistrationOrigin().toString());

        db.insert(SourceContract.TABLE, null, values);

        // Insert source destinations
        if (source.getAppDestinations() != null) {
            for (Uri appDestination : source.getAppDestinations()) {
                ContentValues destinationValues = new ContentValues();
                destinationValues.put(
                        MeasurementTables.SourceDestination.SOURCE_ID, source.getId());
                destinationValues.put(
                        MeasurementTables.SourceDestination.DESTINATION_TYPE, EventSurfaceType.APP);
                destinationValues.put(
                        MeasurementTables.SourceDestination.DESTINATION, appDestination.toString());
                db.insert(MeasurementTables.SourceDestination.TABLE, null, destinationValues);
            }
        }

        if (source.getWebDestinations() != null) {
            for (Uri webDestination : source.getWebDestinations()) {
                ContentValues destinationValues = new ContentValues();
                destinationValues.put(
                        MeasurementTables.SourceDestination.SOURCE_ID, source.getId());
                destinationValues.put(
                        MeasurementTables.SourceDestination.DESTINATION_TYPE, EventSurfaceType.WEB);
                destinationValues.put(
                        MeasurementTables.SourceDestination.DESTINATION, webDestination.toString());
                db.insert(MeasurementTables.SourceDestination.TABLE, null, destinationValues);
            }
        }
    }

    @Test
    public void testInsertAggregateEncryptionKey() {
        String keyId = "38b1d571-f924-4dc0-abe1-e2bac9b6a6be";
        String publicKey = "/amqBgfDOvHAIuatDyoHxhfHaMoYA4BDxZxwtWBRQhc=";
        long expiry = 1653620135831L;

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        (dao) ->
                                dao.insertAggregateEncryptionKey(
                                        new AggregateEncryptionKey.Builder()
                                                .setKeyId(keyId)
                                                .setPublicKey(publicKey)
                                                .setExpiry(expiry)
                                                .build()));

        try (Cursor cursor =
                MeasurementDbHelper.getInstance(sContext)
                        .getReadableDatabase()
                        .query(
                                MeasurementTables.AggregateEncryptionKey.TABLE,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)) {
            assertTrue(cursor.moveToNext());
            AggregateEncryptionKey aggregateEncryptionKey =
                    SqliteObjectMapper.constructAggregateEncryptionKeyFromCursor(cursor);
            assertNotNull(aggregateEncryptionKey);
            assertNotNull(aggregateEncryptionKey.getId());
            assertEquals(keyId, aggregateEncryptionKey.getKeyId());
            assertEquals(publicKey, aggregateEncryptionKey.getPublicKey());
            assertEquals(expiry, aggregateEncryptionKey.getExpiry());
        }
    }

    @Test
    public void testInsertAggregateReport() {
        AggregateReport validAggregateReport = AggregateReportFixture.getValidAggregateReport();
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertAggregateReport(validAggregateReport));

        try (Cursor cursor =
                MeasurementDbHelper.getInstance(sContext)
                        .getReadableDatabase()
                        .query(
                                MeasurementTables.AggregateReport.TABLE,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)) {
            assertTrue(cursor.moveToNext());
            AggregateReport aggregateReport = SqliteObjectMapper.constructAggregateReport(cursor);
            assertNotNull(aggregateReport);
            assertNotNull(aggregateReport.getId());
            assertTrue(Objects.equals(validAggregateReport, aggregateReport));
        }
    }

    @Test
    public void testDeleteAllMeasurementDataWithEmptyList() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();

        Source source = SourceFixture.getValidSourceBuilder().setId("S1").build();
        ContentValues sourceValue = new ContentValues();
        sourceValue.put("_id", source.getId());
        db.insert(SourceContract.TABLE, null, sourceValue);

        Trigger trigger = TriggerFixture.getValidTriggerBuilder().setId("T1").build();
        ContentValues triggerValue = new ContentValues();
        triggerValue.put("_id", trigger.getId());
        db.insert(TriggerContract.TABLE, null, triggerValue);

        EventReport eventReport = new EventReport.Builder().setId("E1").build();
        ContentValues eventReportValue = new ContentValues();
        eventReportValue.put("_id", eventReport.getId());
        db.insert(EventReportContract.TABLE, null, eventReportValue);

        AggregateReport aggregateReport = new AggregateReport.Builder().setId("A1").build();
        ContentValues aggregateReportValue = new ContentValues();
        aggregateReportValue.put("_id", aggregateReport.getId());
        db.insert(MeasurementTables.AggregateReport.TABLE, null, aggregateReportValue);

        ContentValues rateLimitValue = new ContentValues();
        rateLimitValue.put(AttributionContract.ID, "ARL1");
        rateLimitValue.put(AttributionContract.SOURCE_SITE, "sourceSite");
        rateLimitValue.put(AttributionContract.SOURCE_ORIGIN, "sourceOrigin");
        rateLimitValue.put(AttributionContract.DESTINATION_SITE, "destinationSite");
        rateLimitValue.put(AttributionContract.TRIGGER_TIME, 5L);
        rateLimitValue.put(AttributionContract.REGISTRANT, "registrant");
        rateLimitValue.put(AttributionContract.ENROLLMENT_ID, "enrollmentId");

        db.insert(AttributionContract.TABLE, null, rateLimitValue);

        AggregateEncryptionKey key =
                new AggregateEncryptionKey.Builder()
                        .setId("K1")
                        .setKeyId("keyId")
                        .setPublicKey("publicKey")
                        .setExpiry(1)
                        .build();
        ContentValues keyValues = new ContentValues();
        keyValues.put("_id", key.getId());

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.deleteAllMeasurementData(Collections.emptyList()));

        for (String table : ALL_MSMT_TABLES) {
            assertThat(
                            db.query(
                                            /* table */ table,
                                            /* columns */ null,
                                            /* selection */ null,
                                            /* selectionArgs */ null,
                                            /* groupBy */ null,
                                            /* having */ null,
                                            /* orderedBy */ null)
                                    .getCount())
                    .isEqualTo(0);
        }
    }

    @Test
    public void testDeleteAllMeasurementDataWithNonEmptyList() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();

        Source source = SourceFixture.getValidSourceBuilder().setId("S1").build();
        ContentValues sourceValue = new ContentValues();
        sourceValue.put("_id", source.getId());
        db.insert(SourceContract.TABLE, null, sourceValue);

        Trigger trigger = TriggerFixture.getValidTriggerBuilder().setId("T1").build();
        ContentValues triggerValue = new ContentValues();
        triggerValue.put("_id", trigger.getId());
        db.insert(TriggerContract.TABLE, null, triggerValue);

        EventReport eventReport = new EventReport.Builder().setId("E1").build();
        ContentValues eventReportValue = new ContentValues();
        eventReportValue.put("_id", eventReport.getId());
        db.insert(EventReportContract.TABLE, null, eventReportValue);

        AggregateReport aggregateReport = new AggregateReport.Builder().setId("A1").build();
        ContentValues aggregateReportValue = new ContentValues();
        aggregateReportValue.put("_id", aggregateReport.getId());
        db.insert(MeasurementTables.AggregateReport.TABLE, null, aggregateReportValue);

        ContentValues rateLimitValue = new ContentValues();
        rateLimitValue.put(AttributionContract.ID, "ARL1");
        rateLimitValue.put(AttributionContract.SOURCE_SITE, "sourceSite");
        rateLimitValue.put(AttributionContract.SOURCE_ORIGIN, "sourceOrigin");
        rateLimitValue.put(AttributionContract.DESTINATION_SITE, "destinationSite");
        rateLimitValue.put(AttributionContract.TRIGGER_TIME, 5L);
        rateLimitValue.put(AttributionContract.REGISTRANT, "registrant");
        rateLimitValue.put(AttributionContract.ENROLLMENT_ID, "enrollmentId");
        db.insert(AttributionContract.TABLE, null, rateLimitValue);

        AggregateEncryptionKey key =
                new AggregateEncryptionKey.Builder()
                        .setId("K1")
                        .setKeyId("keyId")
                        .setPublicKey("publicKey")
                        .setExpiry(1)
                        .build();
        ContentValues keyValues = new ContentValues();
        keyValues.put("_id", key.getId());

        List<String> excludedTables = List.of(SourceContract.TABLE);

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.deleteAllMeasurementData(excludedTables));

        for (String table : ALL_MSMT_TABLES) {
            if (!excludedTables.contains(table)) {
                assertThat(
                                db.query(
                                                /* table */ table,
                                                /* columns */ null,
                                                /* selection */ null,
                                                /* selectionArgs */ null,
                                                /* groupBy */ null,
                                                /* having */ null,
                                                /* orderedBy */ null)
                                        .getCount())
                        .isEqualTo(0);
            } else {
                assertThat(
                                db.query(
                                                /* table */ table,
                                                /* columns */ null,
                                                /* selection */ null,
                                                /* selectionArgs */ null,
                                                /* groupBy */ null,
                                                /* having */ null,
                                                /* orderedBy */ null)
                                        .getCount())
                        .isNotEqualTo(0);
            }
        }
    }

    /** Test that the variable ALL_MSMT_TABLES actually has all the measurement related tables. */
    @Test
    public void testAllMsmtTables() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Cursor cursor =
                db.query(
                        "sqlite_master",
                        /* columns */ null,
                        /* selection */ "type = ? AND name like ?",
                        /* selectionArgs*/ new String[] {"table", MSMT_TABLE_PREFIX + "%"},
                        /* groupBy */ null,
                        /* having */ null,
                        /* orderBy */ null);

        List<String> tableNames = new ArrayList<>();
        while (cursor.moveToNext()) {
            String tableName = cursor.getString(cursor.getColumnIndex("name"));
            tableNames.add(tableName);
        }
        assertThat(tableNames.size()).isEqualTo(ALL_MSMT_TABLES.length);
        for (String tableName : tableNames) {
            assertThat(ALL_MSMT_TABLES).asList().contains(tableName);
        }
    }

    @Test
    public void insertAttributionRateLimit() {
        // Setup
        Source source = SourceFixture.getValidSource();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(source.getEventTime() + TimeUnit.HOURS.toMillis(1))
                        .build();
        Attribution attribution =
                new Attribution.Builder()
                        .setEnrollmentId(source.getEnrollmentId())
                        .setDestinationOrigin(source.getWebDestinations().get(0).toString())
                        .setDestinationSite(source.getAppDestinations().get(0).toString())
                        .setSourceOrigin(source.getPublisher().toString())
                        .setSourceSite(source.getPublisher().toString())
                        .setRegistrant(source.getRegistrant().toString())
                        .setTriggerTime(trigger.getTriggerTime())
                        .build();
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);

        // Execution
        dm.runInTransaction(
                (dao) -> {
                    dao.insertAttribution(attribution);
                });

        // Assertion
        AtomicLong attributionsCount = new AtomicLong();
        dm.runInTransaction(
                (dao) -> {
                    attributionsCount.set(dao.getAttributionsPerRateLimitWindow(source, trigger));
                });

        assertEquals(1L, attributionsCount.get());
    }

    @Test
    public void testGetAttributionsPerRateLimitWindow_atTimeWindow() {
        // Setup
        Source source = SourceFixture.getValidSource();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(source.getEventTime() + TimeUnit.HOURS.toMillis(1))
                        .build();
        Attribution attribution =
                new Attribution.Builder()
                        .setEnrollmentId(source.getEnrollmentId())
                        .setDestinationOrigin(source.getWebDestinations().get(0).toString())
                        .setDestinationSite(source.getAppDestinations().get(0).toString())
                        .setSourceOrigin(source.getPublisher().toString())
                        .setSourceSite(source.getPublisher().toString())
                        .setRegistrant(source.getRegistrant().toString())
                        .setTriggerTime(
                                trigger.getTriggerTime()
                                        - PrivacyParams.RATE_LIMIT_WINDOW_MILLISECONDS
                                        + 1)
                        .build();
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);

        // Execution
        dm.runInTransaction(
                (dao) -> {
                    dao.insertAttribution(attribution);
                });

        // Assertion
        AtomicLong attributionsCount = new AtomicLong();
        dm.runInTransaction(
                (dao) -> {
                    attributionsCount.set(dao.getAttributionsPerRateLimitWindow(source, trigger));
                });

        assertEquals(1L, attributionsCount.get());
    }

    @Test
    public void testGetAttributionsPerRateLimitWindow_beyondTimeWindow() {
        // Setup
        Source source = SourceFixture.getValidSource();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setTriggerTime(source.getEventTime() + TimeUnit.HOURS.toMillis(1))
                        .build();
        Attribution attribution =
                new Attribution.Builder()
                        .setEnrollmentId(source.getEnrollmentId())
                        .setDestinationOrigin(source.getWebDestinations().get(0).toString())
                        .setDestinationSite(source.getAppDestinations().get(0).toString())
                        .setSourceOrigin(source.getPublisher().toString())
                        .setSourceSite(source.getPublisher().toString())
                        .setRegistrant(source.getRegistrant().toString())
                        .setTriggerTime(
                                trigger.getTriggerTime()
                                        - PrivacyParams.RATE_LIMIT_WINDOW_MILLISECONDS)
                        .build();
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);

        // Execution
        dm.runInTransaction(
                (dao) -> {
                    dao.insertAttribution(attribution);
                });

        // Assertion
        AtomicLong attributionsCount = new AtomicLong();
        dm.runInTransaction(
                (dao) -> {
                    attributionsCount.set(dao.getAttributionsPerRateLimitWindow(source, trigger));
                });

        assertEquals(0L, attributionsCount.get());
    }

    @Test
    public void testTransactionRollbackForRuntimeException() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DatastoreManagerFactory.getDatastoreManager(sContext)
                                .runInTransaction(
                                        (dao) -> {
                                            dao.insertSource(SourceFixture.getValidSource());
                                            // build() call throws IllegalArgumentException
                                            Trigger trigger = new Trigger.Builder().build();
                                            dao.insertTrigger(trigger);
                                        }));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Objects.requireNonNull(db);
        // There should be no insertions
        assertEquals(
                0,
                db.query(MeasurementTables.SourceContract.TABLE, null, null, null, null, null, null)
                        .getCount());
        assertEquals(
                0,
                db.query(
                                MeasurementTables.TriggerContract.TABLE,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)
                        .getCount());
    }

    @Test
    public void testDeleteAppRecordsNotPresentForSources() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();

        List<Source> sourceList = new ArrayList<>();
        // Source registrant is still installed, record is not deleted.
        sourceList.add(
                new Source.Builder()
                        .setId("1")
                        .setEventId(new UnsignedLong(1L))
                        .setAppDestinations(
                                List.of(Uri.parse("android-app://installed-app-destination")))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://installed-registrant"))
                        .setPublisher(Uri.parse("android-app://installed-registrant"))
                        .setStatus(Source.Status.ACTIVE)
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        // Source registrant is not installed, record is deleted.
        sourceList.add(
                new Source.Builder()
                        .setId("2")
                        .setEventId(new UnsignedLong(2L))
                        .setAppDestinations(
                                List.of(Uri.parse("android-app://installed-app-destination")))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://not-installed-registrant"))
                        .setPublisher(Uri.parse("android-app://not-installed-registrant"))
                        .setStatus(Source.Status.ACTIVE)
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        // Source registrant is installed and status is active on not installed destination, record
        // is not deleted.
        sourceList.add(
                new Source.Builder()
                        .setId("3")
                        .setEventId(new UnsignedLong(3L))
                        .setAppDestinations(
                                List.of(Uri.parse("android-app://not-installed-app-destination")))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://installed-registrant"))
                        .setPublisher(Uri.parse("android-app://installed-registrant"))
                        .setStatus(Source.Status.ACTIVE)
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());

        // Source registrant is installed and status is ignored on not installed destination, record
        // is deleted.
        sourceList.add(
                new Source.Builder()
                        .setId("4")
                        .setEventId(new UnsignedLong(4L))
                        .setAppDestinations(
                                List.of(Uri.parse("android-app://not-installed-app-destination")))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://installed-registrant"))
                        .setPublisher(Uri.parse("android-app://installed-registrant"))
                        .setStatus(Source.Status.IGNORED)
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());

        // Source registrant is installed and status is ignored on installed destination, record is
        // not deleted.
        sourceList.add(
                new Source.Builder()
                        .setId("5")
                        .setEventId(new UnsignedLong(5L))
                        .setAppDestinations(
                                List.of(Uri.parse("android-app://installed-app-destination")))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://installed-registrant"))
                        .setPublisher(Uri.parse("android-app://installed-registrant"))
                        .setStatus(Source.Status.IGNORED)
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());

        sourceList.forEach(
                source -> {
                    ContentValues values = new ContentValues();
                    values.put(SourceContract.ID, source.getId());
                    values.put(SourceContract.EVENT_ID, source.getEventId().toString());
                    values.put(SourceContract.ENROLLMENT_ID, source.getEnrollmentId());
                    values.put(SourceContract.REGISTRANT, source.getRegistrant().toString());
                    values.put(SourceContract.PUBLISHER, source.getPublisher().toString());
                    values.put(SourceContract.STATUS, source.getStatus());
                    values.put(
                            SourceContract.REGISTRATION_ORIGIN,
                            source.getRegistrationOrigin().toString());
                    db.insert(SourceContract.TABLE, /* nullColumnHack */ null, values);

                    maybeInsertSourceDestinations(db, source, source.getId());
                });

        long count = DatabaseUtils.queryNumEntries(db, SourceContract.TABLE, /* selection */ null);
        assertEquals(5, count);

        List<Uri> installedUriList = new ArrayList<>();
        installedUriList.add(Uri.parse("android-app://installed-registrant"));
        installedUriList.add(Uri.parse("android-app://installed-app-destination"));

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.deleteAppRecordsNotPresent(installedUriList))
                        .get());

        count = DatabaseUtils.queryNumEntries(db, SourceContract.TABLE, /* selection */ null);
        assertEquals(3, count);

        Cursor cursor =
                db.query(
                        SourceContract.TABLE,
                        /* columns */ null,
                        /* selection */ null,
                        /* selectionArgs */ null,
                        /* groupBy */ null,
                        /* having */ null,
                        /* orderBy */ null);
        while (cursor.moveToNext()) {
            String id =
                    cursor.getString(cursor.getColumnIndex(MeasurementTables.SourceContract.ID));
            assertThat(Arrays.asList("1", "3", "5")).contains(id);
        }
    }

    @Test
    public void testDeleteAppRecordsNotPresentForTriggers() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        List<Trigger> triggerList = new ArrayList<>();
        // Trigger registrant is still installed, record will not be deleted.
        triggerList.add(
                new Trigger.Builder()
                        .setId("1")
                        .setAttributionDestination(
                                Uri.parse("android-app://attribution-destination"))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://installed-registrant"))
                        .setRegistrationOrigin(
                                TriggerFixture.ValidTriggerParams.REGISTRATION_ORIGIN)
                        .build());

        // Trigger registrant is not installed, record will be deleted.
        triggerList.add(
                new Trigger.Builder()
                        .setId("2")
                        .setAttributionDestination(
                                Uri.parse("android-app://attribution-destination"))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://not-installed-registrant"))
                        .setRegistrationOrigin(
                                TriggerFixture.ValidTriggerParams.REGISTRATION_ORIGIN)
                        .build());

        triggerList.forEach(
                trigger -> {
                    ContentValues values = new ContentValues();
                    values.put(TriggerContract.ID, trigger.getId());
                    values.put(
                            TriggerContract.ATTRIBUTION_DESTINATION,
                            trigger.getAttributionDestination().toString());
                    values.put(TriggerContract.ENROLLMENT_ID, trigger.getEnrollmentId());
                    values.put(TriggerContract.REGISTRANT, trigger.getRegistrant().toString());
                    values.put(
                            TriggerContract.REGISTRATION_ORIGIN,
                            trigger.getRegistrationOrigin().toString());
                    db.insert(TriggerContract.TABLE, /* nullColumnHack */ null, values);
                });

        long count = DatabaseUtils.queryNumEntries(db, TriggerContract.TABLE, /* selection */ null);
        assertEquals(2, count);

        List<Uri> installedUriList = new ArrayList<>();
        installedUriList.add(Uri.parse("android-app://installed-registrant"));

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.deleteAppRecordsNotPresent(installedUriList))
                        .get());

        count = DatabaseUtils.queryNumEntries(db, TriggerContract.TABLE, /* selection */ null);
        assertEquals(1, count);

        Cursor cursor =
                db.query(
                        TriggerContract.TABLE,
                        /* columns */ null,
                        /* selection */ null,
                        /* selectionArgs */ null,
                        /* groupBy */ null,
                        /* having */ null,
                        /* orderBy */ null);
        while (cursor.moveToNext()) {
            Trigger trigger = SqliteObjectMapper.constructTriggerFromCursor(cursor);
            assertEquals("1", trigger.getId());
        }
    }

    @Test
    public void testDeleteAppRecordsNotPresentForEventReports() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        List<EventReport> eventReportList = new ArrayList<>();
        // Event report attribution destination is still installed, record will not be deleted.
        eventReportList.add(
                new EventReport.Builder()
                        .setId("1")
                        .setAttributionDestinations(List.of(
                                Uri.parse(
                                        "android-app://installed-attribution-destination")))
                        .build());
        // Event report attribution destination is not installed, record will be deleted.
        eventReportList.add(
                new EventReport.Builder()
                        .setId("2")
                        .setAttributionDestinations(List.of(
                                Uri.parse(
                                        "android-app://not-installed-attribution-destination")))
                        .build());
        eventReportList.forEach(
                eventReport -> {
                    ContentValues values = new ContentValues();
                    values.put(EventReportContract.ID, eventReport.getId());
                    values.put(
                            EventReportContract.ATTRIBUTION_DESTINATION,
                            eventReport.getAttributionDestinations().get(0).toString());
                    db.insert(EventReportContract.TABLE, /* nullColumnHack */ null, values);
                });

        long count =
                DatabaseUtils.queryNumEntries(db, EventReportContract.TABLE, /* selection */ null);
        assertEquals(2, count);

        List<Uri> installedUriList = new ArrayList<>();
        installedUriList.add(Uri.parse("android-app://installed-attribution-destination"));

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.deleteAppRecordsNotPresent(installedUriList))
                        .get());

        count = DatabaseUtils.queryNumEntries(db, EventReportContract.TABLE, /* selection */ null);
        assertEquals(1, count);

        Cursor cursor =
                db.query(
                        EventReportContract.TABLE,
                        /* columns */ null,
                        /* selection */ null,
                        /* selectionArgs */ null,
                        /* groupBy */ null,
                        /* having */ null,
                        /* orderBy */ null);
        while (cursor.moveToNext()) {
            EventReport eventReport = SqliteObjectMapper.constructEventReportFromCursor(cursor);
            assertEquals("1", eventReport.getId());
        }
    }

    @Test
    public void testDeleteAppRecordsNotPresentForAggregateReports() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        List<AggregateReport> aggregateReportList = new ArrayList<>();
        // Aggregate report attribution destination and publisher is still installed, record will
        // not be deleted.
        aggregateReportList.add(
                new AggregateReport.Builder()
                        .setId("1")
                        .setAttributionDestination(
                                Uri.parse("android-app://installed-attribution-destination"))
                        .setPublisher(Uri.parse("android-app://installed-publisher"))
                        .build());
        // Aggregate report attribution destination is not installed, record will be deleted.
        aggregateReportList.add(
                new AggregateReport.Builder()
                        .setId("2")
                        .setAttributionDestination(
                                Uri.parse("android-app://not-installed-attribution-destination"))
                        .setPublisher(Uri.parse("android-app://installed-publisher"))
                        .build());
        // Aggregate report publisher is not installed, record will be deleted.
        aggregateReportList.add(
                new AggregateReport.Builder()
                        .setId("3")
                        .setAttributionDestination(
                                Uri.parse("android-app://installed-attribution-destination"))
                        .setPublisher(Uri.parse("android-app://not-installed-publisher"))
                        .build());
        aggregateReportList.forEach(
                aggregateReport -> {
                    ContentValues values = new ContentValues();
                    values.put(MeasurementTables.AggregateReport.ID, aggregateReport.getId());
                    values.put(
                            MeasurementTables.AggregateReport.ATTRIBUTION_DESTINATION,
                            aggregateReport.getAttributionDestination().toString());
                    values.put(
                            MeasurementTables.AggregateReport.PUBLISHER,
                            aggregateReport.getPublisher().toString());
                    db.insert(
                            MeasurementTables.AggregateReport.TABLE, /* nullColumnHack */
                            null,
                            values);
                });

        long count =
                DatabaseUtils.queryNumEntries(
                        db, MeasurementTables.AggregateReport.TABLE, /* selection */ null);
        assertEquals(3, count);

        List<Uri> installedUriList = new ArrayList<>();
        installedUriList.add(Uri.parse("android-app://installed-attribution-destination"));
        installedUriList.add(Uri.parse("android-app://installed-publisher"));

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.deleteAppRecordsNotPresent(installedUriList))
                        .get());

        count =
                DatabaseUtils.queryNumEntries(
                        db, MeasurementTables.AggregateReport.TABLE, /* selection */ null);
        assertEquals(1, count);

        Cursor cursor =
                db.query(
                        MeasurementTables.AggregateReport.TABLE,
                        /* columns */ null,
                        /* selection */ null,
                        /* selectionArgs */ null,
                        /* groupBy */ null,
                        /* having */ null,
                        /* orderBy */ null);
        while (cursor.moveToNext()) {
            AggregateReport aggregateReport = SqliteObjectMapper.constructAggregateReport(cursor);
            assertEquals("1", aggregateReport.getId());
        }
    }

    @Test
    public void testDeleteAppRecordsNotPresentForAttributions() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        List<Attribution> attributionList = new ArrayList<>();
        // Attribution has source site and destination site still installed, record will not be
        // deleted.
        attributionList.add(
                new Attribution.Builder()
                        .setId("1")
                        .setSourceSite("android-app://installed-source-site")
                        .setSourceOrigin("android-app://installed-source-site")
                        .setDestinationSite("android-app://installed-destination-site")
                        .setDestinationOrigin("android-app://installed-destination-site")
                        .setRegistrant("android-app://installed-source-site")
                        .setEnrollmentId("enrollment-id")
                        .build());
        // Attribution has source site not installed, record will be deleted.
        attributionList.add(
                new Attribution.Builder()
                        .setId("2")
                        .setSourceSite("android-app://not-installed-source-site")
                        .setSourceOrigin("android-app://not-installed-source-site")
                        .setDestinationSite("android-app://installed-destination-site")
                        .setDestinationOrigin("android-app://installed-destination-site")
                        .setRegistrant("android-app://installed-source-site")
                        .setEnrollmentId("enrollment-id")
                        .build());
        // Attribution has destination site not installed, record will be deleted.
        attributionList.add(
                new Attribution.Builder()
                        .setId("3")
                        .setSourceSite("android-app://installed-source-site")
                        .setSourceOrigin("android-app://installed-source-site")
                        .setDestinationSite("android-app://not-installed-destination-site")
                        .setDestinationOrigin("android-app://not-installed-destination-site")
                        .setRegistrant("android-app://installed-source-site")
                        .setEnrollmentId("enrollment-id")
                        .build());
        attributionList.forEach(
                attribution -> {
                    ContentValues values = new ContentValues();
                    values.put(AttributionContract.ID, attribution.getId());
                    values.put(AttributionContract.SOURCE_SITE, attribution.getSourceSite());
                    values.put(AttributionContract.SOURCE_ORIGIN, attribution.getSourceOrigin());
                    values.put(
                            AttributionContract.DESTINATION_SITE, attribution.getDestinationSite());
                    values.put(
                            AttributionContract.DESTINATION_ORIGIN,
                            attribution.getDestinationOrigin());
                    values.put(AttributionContract.REGISTRANT, attribution.getRegistrant());
                    values.put(AttributionContract.ENROLLMENT_ID, attribution.getEnrollmentId());
                    db.insert(AttributionContract.TABLE, /* nullColumnHack */ null, values);
                });

        long count =
                DatabaseUtils.queryNumEntries(db, AttributionContract.TABLE, /* selection */ null);
        assertEquals(3, count);

        List<Uri> installedUriList = new ArrayList<>();
        installedUriList.add(Uri.parse("android-app://installed-source-site"));
        installedUriList.add(Uri.parse("android-app://installed-destination-site"));

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.deleteAppRecordsNotPresent(installedUriList))
                        .get());

        count = DatabaseUtils.queryNumEntries(db, AttributionContract.TABLE, /* selection */ null);
        assertEquals(1, count);

        Cursor cursor =
                db.query(
                        AttributionContract.TABLE,
                        /* columns */ null,
                        /* selection */ null,
                        /* selectionArgs */ null,
                        /* groupBy */ null,
                        /* having */ null,
                        /* orderBy */ null);
        while (cursor.moveToNext()) {
            Attribution attribution = constructAttributionFromCursor(cursor);
            assertEquals("1", attribution.getId());
        }
    }

    @Test
    public void testDeleteAppRecordsNotPresentForEventReportsFromSources() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();

        List<Source> sourceList = new ArrayList<>();
        sourceList.add(
                new Source.Builder() // deleted
                        .setId("1")
                        .setEventId(new UnsignedLong(1L))
                        .setAppDestinations(List.of(Uri.parse("android-app://app-destination-1")))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://uninstalled-app"))
                        .setPublisher(Uri.parse("android-app://uninstalled-app"))
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        sourceList.add(
                new Source.Builder()
                        .setId("2")
                        .setEventId(new UnsignedLong(2L))
                        .setAppDestinations(List.of(Uri.parse("android-app://app-destination-2")))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://installed-app"))
                        .setPublisher(Uri.parse("android-app://installed-app"))
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        sourceList.forEach(source -> insertSource(source, source.getId()));

        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setId("1")
                        .setEventTriggers(TriggerFixture.ValidTriggerParams.EVENT_TRIGGERS)
                        .setRegistrant(Uri.parse("android-app://installed-app"))
                        .build();
        AbstractDbIntegrationTest.insertToDb(trigger, db);

        List<EventReport> reportList = new ArrayList<>();
        reportList.add(
                new EventReport.Builder()
                        .setId("1") // deleted
                        .setSourceEventId(new UnsignedLong(1L))
                        .setAttributionDestinations(
                                List.of(Uri.parse("android-app://app-destination-1")))
                        .setEnrollmentId("enrollment-id")
                        .setTriggerData(new UnsignedLong(5L))
                        .setSourceId(sourceList.get(0).getId())
                        .setTriggerId(trigger.getId())
                        .setSourceType(sourceList.get(0).getSourceType())
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        reportList.add(
                new EventReport.Builder()
                        .setId("2")
                        .setSourceEventId(new UnsignedLong(2L))
                        .setAttributionDestinations(
                                List.of(Uri.parse("android-app://app-destination-2")))
                        .setEnrollmentId("enrollment-id")
                        .setTriggerData(new UnsignedLong(5L))
                        .setSourceId(sourceList.get(1).getId())
                        .setTriggerId(trigger.getId())
                        .setSourceType(sourceList.get(1).getSourceType())
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        reportList.forEach(report -> AbstractDbIntegrationTest.insertToDb(report, db));

        long count =
                DatabaseUtils.queryNumEntries(db, EventReportContract.TABLE, /* selection */ null);
        assertEquals(2, count);

        List<Uri> installedUriList = new ArrayList<>();
        installedUriList.add(Uri.parse("android-app://installed-app"));
        installedUriList.add(Uri.parse("android-app://app-destination-1"));
        installedUriList.add(Uri.parse("android-app://app-destination-2"));

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.deleteAppRecordsNotPresent(installedUriList))
                        .get());

        count = DatabaseUtils.queryNumEntries(db, EventReportContract.TABLE, /* selection */ null);
        assertEquals(1, count);

        Cursor cursor =
                db.query(
                        EventReportContract.TABLE,
                        /* columns */ null,
                        /* selection */ null,
                        /* selectionArgs */ null,
                        /* groupBy */ null,
                        /* having */ null,
                        /* orderBy */ null);
        while (cursor.moveToNext()) {
            EventReport eventReport = SqliteObjectMapper.constructEventReportFromCursor(cursor);
            assertEquals("2", eventReport.getId());
        }
    }

    @Test
    public void testDeleteAppRecordsNotPresentForLargeAppList() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        List<Source> sourceList = new ArrayList<>();

        int limit = 5000;
        sourceList.add(
                new Source.Builder()
                        .setId("1")
                        .setEventId(new UnsignedLong(1L))
                        .setAppDestinations(List.of(Uri.parse("android-app://app-destination-1")))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://installed-app" + limit))
                        .setPublisher(Uri.parse("android-app://installed-app" + limit))
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        sourceList.add(
                new Source.Builder()
                        .setId("2")
                        .setEventId(new UnsignedLong(1L))
                        .setAppDestinations(List.of(Uri.parse("android-app://app-destination-1")))
                        .setEnrollmentId("enrollment-id")
                        .setRegistrant(Uri.parse("android-app://installed-app" + (limit + 1)))
                        .setPublisher(Uri.parse("android-app://installed-app" + (limit + 1)))
                        .setRegistrationOrigin(REGISTRATION_ORIGIN)
                        .build());
        sourceList.forEach(
                source -> {
                    ContentValues values = new ContentValues();
                    values.put(SourceContract.ID, source.getId());
                    values.put(SourceContract.EVENT_ID, source.getEventId().toString());
                    values.put(SourceContract.ENROLLMENT_ID, source.getEnrollmentId());
                    values.put(SourceContract.REGISTRANT, source.getRegistrant().toString());
                    values.put(SourceContract.PUBLISHER, source.getPublisher().toString());
                    values.put(
                            SourceContract.REGISTRATION_ORIGIN,
                            source.getRegistrationOrigin().toString());
                    db.insert(SourceContract.TABLE, /* nullColumnHack */ null, values);

                    maybeInsertSourceDestinations(db, source, source.getId());
                });

        long count = DatabaseUtils.queryNumEntries(db, SourceContract.TABLE, /* selection */ null);
        assertEquals(2, count);

        List<Uri> installedUriList = new ArrayList<>();
        for (int i = 0; i <= limit; i++) {
            installedUriList.add(Uri.parse("android-app://installed-app" + i));
        }

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao ->
                                        measurementDao.deleteAppRecordsNotPresent(installedUriList))
                        .get());

        count = DatabaseUtils.queryNumEntries(db, SourceContract.TABLE, /* selection */ null);
        assertEquals(1, count);
    }

    @Test
    public void testDeleteAppRecordsNotPresentForAsyncRegistrations() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();

        List<AsyncRegistration> asyncRegistrationList = new ArrayList<>();

        asyncRegistrationList.add(buildAsyncRegistration("1"));

        asyncRegistrationList.add(buildAsyncRegistration("2"));

        asyncRegistrationList.add(buildAsyncRegistrationWithNotRegistrant("3"));

        asyncRegistrationList.add(buildAsyncRegistration("4"));

        asyncRegistrationList.add(buildAsyncRegistrationWithNotRegistrant("5"));

        asyncRegistrationList.forEach(
                asyncRegistration -> {
                    ContentValues values = new ContentValues();
                    values.put(AsyncRegistrationContract.ID, asyncRegistration.getId());
                    values.put(
                            AsyncRegistrationContract.REGISTRANT,
                            asyncRegistration.getRegistrant().toString());
                    values.put(
                            AsyncRegistrationContract.TOP_ORIGIN,
                            asyncRegistration.getTopOrigin().toString());
                    values.put(
                            AsyncRegistrationContract.AD_ID_PERMISSION,
                            asyncRegistration.getDebugKeyAllowed());
                    values.put(
                            AsyncRegistrationContract.TYPE, asyncRegistration.getType().toString());
                    values.put(
                            AsyncRegistrationContract.REGISTRATION_ID,
                            asyncRegistration.getRegistrationId());
                    db.insert(AsyncRegistrationContract.TABLE, /* nullColumnHack */ null, values);
                });

        long count =
                DatabaseUtils.queryNumEntries(
                        db, AsyncRegistrationContract.TABLE, /* selection */ null);
        assertEquals(5, count);

        List<Uri> installedUriList = new ArrayList<>();
        installedUriList.add(Uri.parse("android-app://installed-registrant"));

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.deleteAppRecordsNotPresent(
                                                installedUriList)));

        count =
                DatabaseUtils.queryNumEntries(
                        db, AsyncRegistrationContract.TABLE, /* selection */ null);
        assertEquals(3, count);

        Cursor cursor =
                db.query(
                        AsyncRegistrationContract.TABLE,
                        /* columns */ null,
                        /* selection */ null,
                        /* selectionArgs */ null,
                        /* groupBy */ null,
                        /* having */ null,
                        /* orderBy */ null);

        Set<String> ids = new HashSet<>(Arrays.asList("1", "2", "4"));
        List<AsyncRegistration> asyncRegistrations = new ArrayList<>();
        while (cursor.moveToNext()) {
            AsyncRegistration asyncRegistration =
                    SqliteObjectMapper.constructAsyncRegistration(cursor);
            asyncRegistrations.add(asyncRegistration);
        }
        assertTrue(asyncRegistrations.size() == 3);
        for (AsyncRegistration asyncRegistration : asyncRegistrations) {
            assertTrue(ids.contains(asyncRegistration.getId()));
        }
    }

    @Test
    public void testDeleteAppRecordsForAsyncRegistrations() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();

        List<AsyncRegistration> asyncRegistrationList = new ArrayList<>();

        asyncRegistrationList.add(buildAsyncRegistration("1"));

        asyncRegistrationList.add(buildAsyncRegistrationWithNotDestination("2"));

        asyncRegistrationList.add(buildAsyncRegistrationWithNotRegistrant("3"));

        asyncRegistrationList.add(buildAsyncRegistration("4"));

        asyncRegistrationList.add(buildAsyncRegistrationWithNotRegistrant("5"));

        asyncRegistrationList.forEach(
                asyncRegistration -> {
                    ContentValues values = new ContentValues();
                    values.put(AsyncRegistrationContract.ID, asyncRegistration.getId());
                    values.put(
                            AsyncRegistrationContract.REGISTRANT,
                            asyncRegistration.getRegistrant().toString());
                    values.put(
                            AsyncRegistrationContract.TOP_ORIGIN,
                            asyncRegistration.getTopOrigin().toString());
                    values.put(
                            AsyncRegistrationContract.OS_DESTINATION,
                            asyncRegistration.getOsDestination().toString());
                    values.put(
                            AsyncRegistrationContract.AD_ID_PERMISSION,
                            asyncRegistration.getDebugKeyAllowed());
                    values.put(
                            AsyncRegistrationContract.TYPE, asyncRegistration.getType().toString());
                    values.put(
                            AsyncRegistrationContract.REGISTRATION_ID,
                            asyncRegistration.getRegistrationId());
                    db.insert(AsyncRegistrationContract.TABLE, /* nullColumnHack */ null, values);
                });

        long count =
                DatabaseUtils.queryNumEntries(
                        db, AsyncRegistrationContract.TABLE, /* selection */ null);
        assertEquals(5, count);

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.deleteAppRecords(
                                                Uri.parse("android-app://installed-registrant"))));

        count =
                DatabaseUtils.queryNumEntries(
                        db, AsyncRegistrationContract.TABLE, /* selection */ null);
        assertEquals(2, count);

        Cursor cursor =
                db.query(
                        AsyncRegistrationContract.TABLE,
                        /* columns */ null,
                        /* selection */ null,
                        /* selectionArgs */ null,
                        /* groupBy */ null,
                        /* having */ null,
                        /* orderBy */ null);

        Set<String> ids = new HashSet<>(Arrays.asList("3", "5"));
        List<AsyncRegistration> asyncRegistrations = new ArrayList<>();
        while (cursor.moveToNext()) {
            AsyncRegistration asyncRegistration =
                    SqliteObjectMapper.constructAsyncRegistration(cursor);
            asyncRegistrations.add(asyncRegistration);
        }
        for (AsyncRegistration asyncRegistration : asyncRegistrations) {
            assertTrue(ids.contains(asyncRegistration.getId()));
        }
    }

    private static AsyncRegistration buildAsyncRegistration(String id) {

        return new AsyncRegistration.Builder()
                .setId(id)
                .setOsDestination(Uri.parse("android-app://installed-app-destination"))
                .setRegistrant(Uri.parse("android-app://installed-registrant"))
                .setTopOrigin(Uri.parse("android-app://installed-registrant"))
                .setAdIdPermission(false)
                .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                .setRegistrationId(UUID.randomUUID().toString())
                .build();
    }

    private static AsyncRegistration buildAsyncRegistrationWithNotDestination(String id) {
        return new AsyncRegistration.Builder()
                .setId(id)
                .setOsDestination(Uri.parse("android-app://not-installed-app-destination"))
                .setRegistrant(Uri.parse("android-app://installed-registrant"))
                .setTopOrigin(Uri.parse("android-app://installed-registrant"))
                .setAdIdPermission(false)
                .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                .setRequestTime(Long.MAX_VALUE)
                .setRegistrationId(UUID.randomUUID().toString())
                .build();
    }

    private static AsyncRegistration buildAsyncRegistrationWithNotRegistrant(String id) {
        return new AsyncRegistration.Builder()
                .setId(id)
                .setOsDestination(Uri.parse("android-app://installed-app-destination"))
                .setRegistrant(Uri.parse("android-app://not-installed-registrant"))
                .setTopOrigin(Uri.parse("android-app://not-installed-registrant"))
                .setAdIdPermission(false)
                .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                .setRegistrationId(UUID.randomUUID().toString())
                .build();
    }

    @Test
    public void testDeleteDebugReport() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        DebugReport debugReport = createDebugReport();

        ContentValues values = new ContentValues();
        values.put(MeasurementTables.DebugReportContract.ID, debugReport.getId());
        values.put(MeasurementTables.DebugReportContract.TYPE, debugReport.getType());
        values.put(MeasurementTables.DebugReportContract.BODY, debugReport.getBody().toString());
        values.put(
                MeasurementTables.DebugReportContract.ENROLLMENT_ID, debugReport.getEnrollmentId());
        values.put(
                MeasurementTables.DebugReportContract.REGISTRATION_ORIGIN,
                debugReport.getRegistrationOrigin().toString());
        db.insert(MeasurementTables.DebugReportContract.TABLE, null, values);

        long count =
                DatabaseUtils.queryNumEntries(
                        db, MeasurementTables.DebugReportContract.TABLE, /* selection */ null);
        assertEquals(1, count);

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        measurementDao.deleteDebugReport(debugReport.getId())));

        count =
                DatabaseUtils.queryNumEntries(
                        db, MeasurementTables.DebugReportContract.TABLE, /* selection */ null);
        assertEquals(0, count);
    }

    @Test
    public void testGetDebugReportIds() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        DebugReport debugReport = createDebugReport();

        ContentValues values = new ContentValues();
        values.put(MeasurementTables.DebugReportContract.ID, debugReport.getId());
        values.put(MeasurementTables.DebugReportContract.TYPE, debugReport.getType());
        values.put(MeasurementTables.DebugReportContract.BODY, debugReport.getBody().toString());
        values.put(
                MeasurementTables.DebugReportContract.ENROLLMENT_ID, debugReport.getEnrollmentId());
        values.put(
                MeasurementTables.DebugReportContract.REGISTRATION_ORIGIN,
                debugReport.getRegistrationOrigin().toString());
        db.insert(MeasurementTables.DebugReportContract.TABLE, null, values);

        long count =
                DatabaseUtils.queryNumEntries(
                        db, MeasurementTables.DebugReportContract.TABLE, /* selection */ null);
        assertEquals(1, count);

        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao ->
                                        assertEquals(
                                                List.of(debugReport.getId()),
                                                measurementDao.getDebugReportIds())));
    }

    @Test
    public void testDeleteExpiredRecordsForAsyncRegistrations() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();

        List<AsyncRegistration> asyncRegistrationList = new ArrayList<>();

        asyncRegistrationList.add(
                new AsyncRegistration.Builder()
                        .setId("1")
                        .setOsDestination(Uri.parse("android-app://installed-app-destination"))
                        .setRegistrant(Uri.parse("android-app://installed-registrant"))
                        .setTopOrigin(Uri.parse("android-app://installed-registrant"))
                        .setAdIdPermission(false)
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setRequestTime(1)
                        .setRegistrationId(UUID.randomUUID().toString())
                        .build());

        asyncRegistrationList.add(
                new AsyncRegistration.Builder()
                        .setId("2")
                        .setOsDestination(Uri.parse("android-app://not-installed-app-destination"))
                        .setRegistrant(Uri.parse("android-app://installed-registrant"))
                        .setTopOrigin(Uri.parse("android-app://installed-registrant"))
                        .setAdIdPermission(false)
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setRequestTime(Long.MAX_VALUE)
                        .setRegistrationId(UUID.randomUUID().toString())
                        .build());

        asyncRegistrationList.forEach(
                asyncRegistration -> {
                    ContentValues values = new ContentValues();
                    values.put(AsyncRegistrationContract.ID, asyncRegistration.getId());
                    values.put(
                            AsyncRegistrationContract.REGISTRANT,
                            asyncRegistration.getRegistrant().toString());
                    values.put(
                            AsyncRegistrationContract.TOP_ORIGIN,
                            asyncRegistration.getTopOrigin().toString());
                    values.put(
                            AsyncRegistrationContract.OS_DESTINATION,
                            asyncRegistration.getOsDestination().toString());
                    values.put(
                            AsyncRegistrationContract.AD_ID_PERMISSION,
                            asyncRegistration.getDebugKeyAllowed());
                    values.put(
                            AsyncRegistrationContract.TYPE, asyncRegistration.getType().toString());
                    values.put(
                            AsyncRegistrationContract.REQUEST_TIME,
                            asyncRegistration.getRequestTime());
                    values.put(
                            AsyncRegistrationContract.REGISTRATION_ID,
                            asyncRegistration.getRegistrationId());
                    db.insert(AsyncRegistrationContract.TABLE, /* nullColumnHack */ null, values);
                });

        long count =
                DatabaseUtils.queryNumEntries(
                        db, AsyncRegistrationContract.TABLE, /* selection */ null);
        assertEquals(2, count);

        long earliestValidInsertion = System.currentTimeMillis() - 2;
        assertTrue(
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransaction(
                                measurementDao -> measurementDao.deleteExpiredRecords(
                                        earliestValidInsertion)));

        count =
                DatabaseUtils.queryNumEntries(
                        db, AsyncRegistrationContract.TABLE, /* selection */ null);
        assertEquals(1, count);

        Cursor cursor =
                db.query(
                        AsyncRegistrationContract.TABLE,
                        /* columns */ null,
                        /* selection */ null,
                        /* selectionArgs */ null,
                        /* groupBy */ null,
                        /* having */ null,
                        /* orderBy */ null);

        Set<String> ids = new HashSet<>(Arrays.asList("2"));
        List<AsyncRegistration> asyncRegistrations = new ArrayList<>();
        while (cursor.moveToNext()) {
            AsyncRegistration asyncRegistration =
                    SqliteObjectMapper.constructAsyncRegistration(cursor);
            asyncRegistrations.add(asyncRegistration);
        }
        for (AsyncRegistration asyncRegistration : asyncRegistrations) {
            assertTrue(ids.contains(asyncRegistration.getId()));
        }
    }

    @Test
    public void deleteExpiredRecords_registrationRedirectCount() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        List<Pair<String, String>> regIdCounts =
                List.of(
                        new Pair<>("reg1", "1"),
                        new Pair<>("reg2", "2"),
                        new Pair<>("reg3", "3"),
                        new Pair<>("reg4", "4"));
        for (Pair<String, String> regIdCount : regIdCounts) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(
                    MeasurementTables.KeyValueDataContract.DATA_TYPE,
                    KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT.toString());
            contentValues.put(MeasurementTables.KeyValueDataContract.KEY, regIdCount.first);
            contentValues.put(MeasurementTables.KeyValueDataContract.VALUE, regIdCount.second);
            db.insert(MeasurementTables.KeyValueDataContract.TABLE, null, contentValues);
        }
        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);

        AsyncRegistration asyncRegistration1 =
                AsyncRegistrationFixture.getValidAsyncRegistrationBuilder()
                        .setRegistrationId("reg1")
                        .setRequestTime(System.currentTimeMillis() + 60000) // Avoid deletion
                        .build();
        AsyncRegistration asyncRegistration2 =
                AsyncRegistrationFixture.getValidAsyncRegistrationBuilder()
                        .setRegistrationId("reg2")
                        .setRequestTime(System.currentTimeMillis() + 60000) // Avoid deletion
                        .build();
        List<AsyncRegistration> asyncRegistrations =
                List.of(asyncRegistration1, asyncRegistration2);
        asyncRegistrations.forEach(
                asyncRegistration ->
                        datastoreManager.runInTransaction(
                                dao -> dao.insertAsyncRegistration(asyncRegistration)));

        long earliestValidInsertion = System.currentTimeMillis() - 60000;
        assertTrue(datastoreManager.runInTransaction((dao) ->
                  dao.deleteExpiredRecords(earliestValidInsertion)));

        Cursor cursor =
                db.query(
                        MeasurementTables.KeyValueDataContract.TABLE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        MeasurementTables.KeyValueDataContract.KEY);
        assertEquals(2, cursor.getCount());
        cursor.moveToNext();
        assertEquals(
                KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT.toString(),
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.DATA_TYPE)));
        assertEquals(
                "reg1",
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.KEY)));
        assertEquals(
                "1",
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.VALUE)));
        cursor.moveToNext();
        assertEquals(
                KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT.toString(),
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.DATA_TYPE)));
        assertEquals(
                "reg2",
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.KEY)));
        assertEquals(
                "2",
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.VALUE)));
        cursor.close();
    }

    @Test
    public void deleteExpiredRecords_skipDeliveredEventReportsOutsideWindow() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        ContentValues sourceValid = new ContentValues();
        sourceValid.put(SourceContract.ID, "s1");
        sourceValid.put(SourceContract.EVENT_TIME, System.currentTimeMillis());

        ContentValues sourceExpired = new ContentValues();
        sourceExpired.put(SourceContract.ID, "s2");
        sourceExpired.put(
                SourceContract.EVENT_TIME, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(20));

        ContentValues triggerValid = new ContentValues();
        triggerValid.put(TriggerContract.ID, "t1");
        triggerValid.put(TriggerContract.TRIGGER_TIME, System.currentTimeMillis());

        ContentValues triggerExpired = new ContentValues();
        triggerExpired.put(TriggerContract.ID, "t2");
        triggerExpired.put(
                TriggerContract.TRIGGER_TIME,
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(20));

        db.insert(SourceContract.TABLE, null, sourceValid);
        db.insert(SourceContract.TABLE, null, sourceExpired);
        db.insert(TriggerContract.TABLE, null, triggerValid);
        db.insert(TriggerContract.TABLE, null, triggerExpired);

        ContentValues eventReport_NotDelivered_WithinWindow = new ContentValues();
        eventReport_NotDelivered_WithinWindow.put(EventReportContract.ID, "e1");
        eventReport_NotDelivered_WithinWindow.put(
                EventReportContract.REPORT_TIME, System.currentTimeMillis());
        eventReport_NotDelivered_WithinWindow.put(
                EventReportContract.STATUS, EventReport.Status.PENDING);
        eventReport_NotDelivered_WithinWindow.put(
                EventReportContract.SOURCE_ID, sourceValid.getAsString(SourceContract.ID));
        eventReport_NotDelivered_WithinWindow.put(
                EventReportContract.TRIGGER_ID, triggerValid.getAsString(TriggerContract.ID));
        db.insert(EventReportContract.TABLE, null, eventReport_NotDelivered_WithinWindow);

        ContentValues eventReport_Delivered_WithinWindow = new ContentValues();
        eventReport_Delivered_WithinWindow.put(EventReportContract.ID, "e2");
        eventReport_Delivered_WithinWindow.put(
                EventReportContract.REPORT_TIME, System.currentTimeMillis());
        eventReport_Delivered_WithinWindow.put(
                EventReportContract.STATUS, EventReport.Status.DELIVERED);
        eventReport_Delivered_WithinWindow.put(
                EventReportContract.SOURCE_ID, sourceValid.getAsString(SourceContract.ID));
        eventReport_Delivered_WithinWindow.put(
                EventReportContract.TRIGGER_ID, triggerValid.getAsString(TriggerContract.ID));
        db.insert(EventReportContract.TABLE, null, eventReport_Delivered_WithinWindow);

        ContentValues eventReport_Delivered_OutsideWindow = new ContentValues();
        eventReport_Delivered_OutsideWindow.put(EventReportContract.ID, "e3");
        eventReport_Delivered_OutsideWindow.put(
                EventReportContract.REPORT_TIME,
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(20));
        eventReport_Delivered_OutsideWindow.put(
                EventReportContract.STATUS, EventReport.Status.DELIVERED);
        eventReport_Delivered_OutsideWindow.put(
                EventReportContract.SOURCE_ID, sourceValid.getAsString(SourceContract.ID));
        eventReport_Delivered_OutsideWindow.put(
                EventReportContract.TRIGGER_ID, triggerValid.getAsString(TriggerContract.ID));
        db.insert(EventReportContract.TABLE, null, eventReport_Delivered_OutsideWindow);

        ContentValues eventReport_NotDelivered_OutsideWindow = new ContentValues();
        eventReport_NotDelivered_OutsideWindow.put(EventReportContract.ID, "e4");
        eventReport_NotDelivered_OutsideWindow.put(
                EventReportContract.REPORT_TIME,
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(20));
        eventReport_NotDelivered_OutsideWindow.put(
                EventReportContract.STATUS, EventReport.Status.PENDING);
        eventReport_NotDelivered_OutsideWindow.put(
                EventReportContract.SOURCE_ID, sourceValid.getAsString(SourceContract.ID));
        eventReport_NotDelivered_OutsideWindow.put(
                EventReportContract.TRIGGER_ID, triggerValid.getAsString(TriggerContract.ID));
        db.insert(EventReportContract.TABLE, null, eventReport_NotDelivered_OutsideWindow);

        ContentValues eventReport_expiredSource = new ContentValues();
        eventReport_expiredSource.put(EventReportContract.ID, "e5");
        eventReport_expiredSource.put(EventReportContract.REPORT_TIME, System.currentTimeMillis());
        eventReport_expiredSource.put(EventReportContract.STATUS, EventReport.Status.PENDING);
        eventReport_expiredSource.put(
                EventReportContract.SOURCE_ID, sourceExpired.getAsString(SourceContract.ID));
        eventReport_expiredSource.put(
                EventReportContract.TRIGGER_ID, triggerValid.getAsString(TriggerContract.ID));
        db.insert(EventReportContract.TABLE, null, eventReport_expiredSource);

        ContentValues eventReport_expiredTrigger = new ContentValues();
        eventReport_expiredTrigger.put(EventReportContract.ID, "e6");
        eventReport_expiredTrigger.put(EventReportContract.REPORT_TIME, System.currentTimeMillis());
        eventReport_expiredTrigger.put(EventReportContract.STATUS, EventReport.Status.PENDING);
        eventReport_expiredTrigger.put(
                EventReportContract.SOURCE_ID, sourceValid.getAsString(SourceContract.ID));
        eventReport_expiredTrigger.put(
                EventReportContract.TRIGGER_ID, triggerExpired.getAsString(TriggerContract.ID));
        db.insert(EventReportContract.TABLE, null, eventReport_expiredTrigger);

        long earliestValidInsertion = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10);
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        measurementDao ->
                                measurementDao.deleteExpiredRecords(earliestValidInsertion));

        List<ContentValues> deletedReports =
                List.of(eventReport_expiredSource, eventReport_expiredTrigger);

        List<ContentValues> notDeletedReports =
                List.of(
                        eventReport_Delivered_OutsideWindow,
                        eventReport_Delivered_WithinWindow,
                        eventReport_NotDelivered_OutsideWindow,
                        eventReport_NotDelivered_WithinWindow);

        assertEquals(
                notDeletedReports.size(),
                DatabaseUtils.longForQuery(
                        db,
                        "SELECT COUNT("
                                + EventReportContract.ID
                                + ") FROM "
                                + EventReportContract.TABLE
                                + " WHERE "
                                + EventReportContract.ID
                                + " IN ("
                                + notDeletedReports.stream()
                                        .map(
                                                (eR) -> {
                                                    return DatabaseUtils.sqlEscapeString(
                                                            eR.getAsString(EventReportContract.ID));
                                                })
                                        .collect(Collectors.joining(","))
                                + ")",
                        null));

        assertEquals(
                0,
                DatabaseUtils.longForQuery(
                        db,
                        "SELECT COUNT("
                                + EventReportContract.ID
                                + ") FROM "
                                + EventReportContract.TABLE
                                + " WHERE "
                                + EventReportContract.ID
                                + " IN ("
                                + deletedReports.stream()
                                        .map(
                                                (eR) -> {
                                                    return DatabaseUtils.sqlEscapeString(
                                                            eR.getAsString(EventReportContract.ID));
                                                })
                                        .collect(Collectors.joining(","))
                                + ")",
                        null));
    }

    @Test
    public void getRegistrationRedirectCount_keyMissing() {
        Optional<KeyValueData> optKeyValueData =
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                (dao) ->
                                        dao.getKeyValueData(
                                                "missing_random_id",
                                                DataType.REGISTRATION_REDIRECT_COUNT));
        assertTrue(optKeyValueData.isPresent());
        KeyValueData keyValueData = optKeyValueData.get();
        assertEquals(KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT, keyValueData.getDataType());
        assertEquals("missing_random_id", keyValueData.getKey());
        assertNull(keyValueData.getValue());
        assertEquals(1, keyValueData.getRegistrationRedirectCount());
    }

    @Test
    public void getRegistrationRedirectCount_keyExists() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        ContentValues contentValues = new ContentValues();
        contentValues.put(
                MeasurementTables.KeyValueDataContract.DATA_TYPE,
                KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT.toString());
        contentValues.put(MeasurementTables.KeyValueDataContract.KEY, "random_id");
        contentValues.put(MeasurementTables.KeyValueDataContract.VALUE, "2");
        db.insert(MeasurementTables.KeyValueDataContract.TABLE, null, contentValues);
        Optional<KeyValueData> optKeyValueData =
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                (dao) ->
                                        dao.getKeyValueData(
                                                "random_id",
                                                KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT));
        assertTrue(optKeyValueData.isPresent());
        KeyValueData keyValueData = optKeyValueData.get();
        assertEquals(KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT, keyValueData.getDataType());
        assertEquals("random_id", keyValueData.getKey());
        assertEquals("2", keyValueData.getValue());
        assertEquals(2, keyValueData.getRegistrationRedirectCount());
    }

    @Test
    public void updateRegistrationRedirectCount_keyMissing() {
        KeyValueData keyValueData =
                new KeyValueData.Builder()
                        .setDataType(KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT)
                        .setKey("key_1")
                        .setValue("4")
                        .build();
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertOrUpdateKeyValueData(keyValueData));
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Cursor cursor =
                db.query(
                        MeasurementTables.KeyValueDataContract.TABLE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals(
                KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT.toString(),
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.DATA_TYPE)));
        assertEquals(
                "key_1",
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.KEY)));
        assertEquals(
                "4",
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.VALUE)));
        cursor.close();
    }

    @Test
    public void updateRegistrationRedirectCount_keyExists() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        ContentValues contentValues = new ContentValues();
        contentValues.put(
                MeasurementTables.KeyValueDataContract.DATA_TYPE,
                KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT.toString());
        contentValues.put(MeasurementTables.KeyValueDataContract.KEY, "key_1");
        contentValues.put(MeasurementTables.KeyValueDataContract.VALUE, "2");
        db.insert(MeasurementTables.KeyValueDataContract.TABLE, null, contentValues);

        KeyValueData keyValueData =
                new KeyValueData.Builder()
                        .setDataType(KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT)
                        .setKey("key_1")
                        .setValue("4")
                        .build();
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertOrUpdateKeyValueData(keyValueData));

        Cursor cursor =
                db.query(
                        MeasurementTables.KeyValueDataContract.TABLE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals(
                KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT.toString(),
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.DATA_TYPE)));
        assertEquals(
                "key_1",
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.KEY)));
        assertEquals(
                "4",
                cursor.getString(
                        cursor.getColumnIndex(MeasurementTables.KeyValueDataContract.VALUE)));
        cursor.close();
    }

    @Test
    public void keyValueDataTable_PrimaryKeyConstraint() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        ContentValues contentValues1 = new ContentValues();
        contentValues1.put(
                MeasurementTables.KeyValueDataContract.DATA_TYPE,
                KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT.toString());
        contentValues1.put(MeasurementTables.KeyValueDataContract.KEY, "key_1");
        contentValues1.put(MeasurementTables.KeyValueDataContract.VALUE, "2");

        assertNotEquals(
                -1, db.insert(MeasurementTables.KeyValueDataContract.TABLE, null, contentValues1));

        // Should fail because we are using <DataType, Key> as primary key
        assertEquals(
                -1, db.insert(MeasurementTables.KeyValueDataContract.TABLE, null, contentValues1));

        ContentValues contentValues2 = new ContentValues();
        contentValues2.put(
                MeasurementTables.KeyValueDataContract.DATA_TYPE,
                KeyValueData.DataType.REGISTRATION_REDIRECT_COUNT.toString());
        contentValues2.put(MeasurementTables.KeyValueDataContract.KEY, "key_2");
        contentValues2.put(MeasurementTables.KeyValueDataContract.VALUE, "2");

        assertNotEquals(
                -1, db.insert(MeasurementTables.KeyValueDataContract.TABLE, null, contentValues2));
    }

    private static Source getSourceWithDifferentDestinations(
            int numDestinations,
            boolean hasAppDestinations,
            boolean hasWebDestinations,
            long eventTime,
            Uri publisher,
            String enrollmentId,
            @Source.Status int sourceStatus) {
        List<Uri> appDestinations = null;
        List<Uri> webDestinations = null;
        if (hasAppDestinations) {
            appDestinations = new ArrayList<>();
            appDestinations.add(Uri.parse("android-app://com.app-destination"));
        }
        if (hasWebDestinations) {
            webDestinations = new ArrayList<>();
            for (int i = 0; i < numDestinations; i++) {
                webDestinations.add(
                        Uri.parse("https://web-destination-" + String.valueOf(i) + ".com"));
            }
        }
        long expiryTime =
                eventTime
                        + TimeUnit.SECONDS.toMillis(
                                MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS);
        return new Source.Builder()
                .setEventId(new UnsignedLong(0L))
                .setEventTime(eventTime)
                .setExpiryTime(expiryTime)
                .setPublisher(publisher)
                .setAppDestinations(appDestinations)
                .setWebDestinations(webDestinations)
                .setEnrollmentId(enrollmentId)
                .setRegistrant(SourceFixture.ValidSourceParams.REGISTRANT)
                .setStatus(sourceStatus)
                .setRegistrationOrigin(REGISTRATION_ORIGIN)
                .build();
    }

    private static List<Source> getSourcesWithDifferentDestinations(
            int numSources,
            boolean hasAppDestinations,
            boolean hasWebDestinations,
            long eventTime,
            Uri publisher,
            String enrollmentId,
            @Source.Status int sourceStatus,
            Uri registrationOrigin) {
        long expiryTime =
                eventTime
                        + TimeUnit.SECONDS.toMillis(
                                MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS);
        return getSourcesWithDifferentDestinations(
                numSources,
                hasAppDestinations,
                hasWebDestinations,
                eventTime,
                expiryTime,
                publisher,
                enrollmentId,
                sourceStatus,
                registrationOrigin);
    }

    private static List<Source> getSourcesWithDifferentDestinations(
            int numSources,
            boolean hasAppDestinations,
            boolean hasWebDestinations,
            long eventTime,
            Uri publisher,
            String enrollmentId,
            @Source.Status int sourceStatus) {
        long expiryTime =
                eventTime
                        + TimeUnit.SECONDS.toMillis(
                                MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS);
        return getSourcesWithDifferentDestinations(
                numSources,
                hasAppDestinations,
                hasWebDestinations,
                eventTime,
                expiryTime,
                publisher,
                enrollmentId,
                sourceStatus,
                REGISTRATION_ORIGIN);
    }

    private static List<Source> getSourcesWithDifferentDestinations(
            int numSources,
            boolean hasAppDestinations,
            boolean hasWebDestinations,
            long eventTime,
            long expiryTime,
            Uri publisher,
            String enrollmentId,
            @Source.Status int sourceStatus,
            Uri registrationOrigin) {
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < numSources; i++) {
            Source.Builder sourceBuilder =
                    new Source.Builder()
                            .setEventId(new UnsignedLong(0L))
                            .setEventTime(eventTime)
                            .setExpiryTime(expiryTime)
                            .setPublisher(publisher)
                            .setEnrollmentId(enrollmentId)
                            .setRegistrant(SourceFixture.ValidSourceParams.REGISTRANT)
                            .setStatus(sourceStatus)
                            .setRegistrationOrigin(registrationOrigin);
            if (hasAppDestinations) {
                sourceBuilder.setAppDestinations(
                        List.of(Uri.parse("android-app://app-destination-" + String.valueOf(i))));
            }
            if (hasWebDestinations) {
                sourceBuilder.setWebDestinations(
                        List.of(
                                Uri.parse(
                                        "https://web-destination-" + String.valueOf(i) + ".com")));
            }
            sources.add(sourceBuilder.build());
        }
        return sources;
    }

    private static List<Source> getSourcesWithDifferentEnrollments(
            int numSources,
            List<Uri> appDestinations,
            List<Uri> webDestinations,
            long eventTime,
            Uri publisher,
            @Source.Status int sourceStatus) {
        long expiryTime =
                eventTime
                        + TimeUnit.SECONDS.toMillis(
                                MAX_REPORTING_REGISTER_SOURCE_EXPIRATION_IN_SECONDS);
        return getSourcesWithDifferentEnrollments(
                numSources,
                appDestinations,
                webDestinations,
                eventTime,
                expiryTime,
                publisher,
                sourceStatus);
    }

    private static List<Source> getSourcesWithDifferentEnrollments(
            int numSources,
            List<Uri> appDestinations,
            List<Uri> webDestinations,
            long eventTime,
            long expiryTime,
            Uri publisher,
            @Source.Status int sourceStatus) {
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < numSources; i++) {
            Source.Builder sourceBuilder =
                    new Source.Builder()
                            .setEventId(new UnsignedLong(0L))
                            .setEventTime(eventTime)
                            .setExpiryTime(expiryTime)
                            .setPublisher(publisher)
                            .setRegistrant(SourceFixture.ValidSourceParams.REGISTRANT)
                            .setStatus(sourceStatus)
                            .setAppDestinations(getNullableUriList(appDestinations))
                            .setWebDestinations(getNullableUriList(webDestinations))
                            .setEnrollmentId("enrollment-id-" + i)
                            .setRegistrationOrigin(REGISTRATION_ORIGIN);
            sources.add(sourceBuilder.build());
        }
        return sources;
    }

    private static List<Attribution> getAttributionsWithDifferentEnrollments(
            int numAttributions,
            Uri destinationSite,
            long triggerTime,
            Uri sourceSite,
            String registrant) {
        List<Attribution> attributions = new ArrayList<>();
        for (int i = 0; i < numAttributions; i++) {
            Attribution.Builder attributionBuilder =
                    new Attribution.Builder()
                            .setTriggerTime(triggerTime)
                            .setSourceSite(sourceSite.toString())
                            .setSourceOrigin(sourceSite.toString())
                            .setDestinationSite(destinationSite.toString())
                            .setDestinationOrigin(destinationSite.toString())
                            .setEnrollmentId("enrollment-id-" + i)
                            .setRegistrant(registrant);
            attributions.add(attributionBuilder.build());
        }
        return attributions;
    }

    private static void insertAttribution(Attribution attribution) {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(AttributionContract.ID, UUID.randomUUID().toString());
        values.put(AttributionContract.SOURCE_SITE, attribution.getSourceSite());
        values.put(AttributionContract.DESTINATION_SITE, attribution.getDestinationSite());
        values.put(AttributionContract.ENROLLMENT_ID, attribution.getEnrollmentId());
        values.put(AttributionContract.TRIGGER_TIME, attribution.getTriggerTime());
        values.put(AttributionContract.SOURCE_ID, attribution.getSourceId());
        values.put(AttributionContract.TRIGGER_ID, attribution.getTriggerId());
        long row = db.insert("msmt_attribution", null, values);
        assertNotEquals("Attribution insertion failed", -1, row);
    }

    private static Attribution createAttributionWithSourceAndTriggerIds(
            String attributionId, String sourceId, String triggerId) {
        return new Attribution.Builder()
                .setId(attributionId)
                .setTriggerTime(0L)
                .setSourceSite("android-app://source.app")
                .setSourceOrigin("android-app://source.app")
                .setDestinationSite("android-app://destination.app")
                .setDestinationOrigin("android-app://destination.app")
                .setEnrollmentId("enrollment-id-")
                .setRegistrant("android-app://registrant.app")
                .setSourceId(sourceId)
                .setTriggerId(triggerId)
                .build();
    }

    private static void insertSource(Source source) {
        insertSource(source, UUID.randomUUID().toString());
    }

    // This is needed because MeasurementDao::insertSource inserts a default value for status.
    private static void insertSource(Source source, String sourceId) {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SourceContract.ID, sourceId);
        if (source.getEventId() != null) {
            values.put(SourceContract.EVENT_ID, source.getEventId().getValue());
        }
        values.put(SourceContract.PUBLISHER, source.getPublisher().toString());
        values.put(SourceContract.PUBLISHER_TYPE, source.getPublisherType());
        values.put(SourceContract.ENROLLMENT_ID, source.getEnrollmentId());
        values.put(SourceContract.EVENT_TIME, source.getEventTime());
        values.put(SourceContract.EXPIRY_TIME, source.getExpiryTime());
        values.put(SourceContract.PRIORITY, source.getPriority());
        values.put(SourceContract.STATUS, source.getStatus());
        values.put(SourceContract.SOURCE_TYPE, source.getSourceType().toString());
        values.put(SourceContract.REGISTRANT, source.getRegistrant().toString());
        values.put(SourceContract.INSTALL_ATTRIBUTION_WINDOW, source.getInstallAttributionWindow());
        values.put(SourceContract.INSTALL_COOLDOWN_WINDOW, source.getInstallCooldownWindow());
        values.put(SourceContract.ATTRIBUTION_MODE, source.getAttributionMode());
        values.put(SourceContract.AGGREGATE_SOURCE, source.getAggregateSource());
        values.put(SourceContract.FILTER_DATA, source.getFilterDataString());
        values.put(SourceContract.AGGREGATE_CONTRIBUTIONS, source.getAggregateContributions());
        values.put(SourceContract.DEBUG_REPORTING, source.isDebugReporting());
        values.put(SourceContract.INSTALL_TIME, source.getInstallTime());
        values.put(SourceContract.REGISTRATION_ID, source.getRegistrationId());
        values.put(SourceContract.SHARED_AGGREGATION_KEYS, source.getSharedAggregationKeys());
        values.put(SourceContract.REGISTRATION_ORIGIN, source.getRegistrationOrigin().toString());
        long row = db.insert(SourceContract.TABLE, null, values);
        assertNotEquals("Source insertion failed", -1, row);

        maybeInsertSourceDestinations(db, source, sourceId);
    }

    private static String getNullableUriString(List<Uri> uriList) {
        return Optional.ofNullable(uriList).map(uris -> uris.get(0).toString()).orElse(null);
    }

    /** Test that the AsyncRegistration is inserted correctly. */
    @Test
    public void testInsertAsyncRegistration() {
        AsyncRegistration validAsyncRegistration =
                AsyncRegistrationFixture.getValidAsyncRegistration();
        String validAsyncRegistrationId = validAsyncRegistration.getId();

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertAsyncRegistration(validAsyncRegistration));

        try (Cursor cursor =
                MeasurementDbHelper.getInstance(sContext)
                        .getReadableDatabase()
                        .query(
                                MeasurementTables.AsyncRegistrationContract.TABLE,
                                null,
                                MeasurementTables.AsyncRegistrationContract.ID + " = ? ",
                                new String[] {validAsyncRegistrationId},
                                null,
                                null,
                                null)) {

            assertTrue(cursor.moveToNext());
            AsyncRegistration asyncRegistration =
                    SqliteObjectMapper.constructAsyncRegistration(cursor);
            assertNotNull(asyncRegistration);
            assertNotNull(asyncRegistration.getId());
            assertEquals(asyncRegistration.getId(), validAsyncRegistration.getId());
            assertNotNull(asyncRegistration.getRegistrationUri());
            assertNotNull(asyncRegistration.getTopOrigin());
            assertEquals(asyncRegistration.getTopOrigin(), validAsyncRegistration.getTopOrigin());
            assertNotNull(asyncRegistration.getRegistrant());
            assertEquals(asyncRegistration.getRegistrant(), validAsyncRegistration.getRegistrant());
            assertNotNull(asyncRegistration.getSourceType());
            assertEquals(asyncRegistration.getSourceType(), validAsyncRegistration.getSourceType());
            assertNotNull(asyncRegistration.getDebugKeyAllowed());
            assertEquals(
                    asyncRegistration.getDebugKeyAllowed(),
                    validAsyncRegistration.getDebugKeyAllowed());
            assertNotNull(asyncRegistration.getRetryCount());
            assertEquals(asyncRegistration.getRetryCount(), validAsyncRegistration.getRetryCount());
            assertNotNull(asyncRegistration.getRequestTime());
            assertEquals(
                    asyncRegistration.getRequestTime(), validAsyncRegistration.getRequestTime());
            assertNotNull(asyncRegistration.getOsDestination());
            assertEquals(
                    asyncRegistration.getOsDestination(),
                    validAsyncRegistration.getOsDestination());
            assertNotNull(asyncRegistration.getRegistrationUri());
            assertEquals(
                    asyncRegistration.getRegistrationUri(),
                    validAsyncRegistration.getRegistrationUri());
            assertNotNull(asyncRegistration.getDebugKeyAllowed());
            assertEquals(
                    asyncRegistration.getDebugKeyAllowed(),
                    validAsyncRegistration.getDebugKeyAllowed());
            assertEquals(
                    asyncRegistration.getPlatformAdId(), validAsyncRegistration.getPlatformAdId());
        }
    }

    /** Test that records in AsyncRegistration queue are fetched properly. */
    @Test
    public void testFetchNextQueuedAsyncRegistration_validRetryLimit() {
        AsyncRegistration asyncRegistration = AsyncRegistrationFixture.getValidAsyncRegistration();
        String asyncRegistrationId = asyncRegistration.getId();

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertAsyncRegistration(asyncRegistration));
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        (dao) -> {
                            AsyncRegistration fetchedAsyncRegistration =
                                    dao.fetchNextQueuedAsyncRegistration(
                                            (short) 1, new HashSet<>());
                            assertNotNull(fetchedAsyncRegistration);
                            assertEquals(fetchedAsyncRegistration.getId(), asyncRegistrationId);
                            fetchedAsyncRegistration.incrementRetryCount();
                            dao.updateRetryCount(fetchedAsyncRegistration);
                        });

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        (dao) -> {
                            AsyncRegistration fetchedAsyncRegistration =
                                    dao.fetchNextQueuedAsyncRegistration(
                                            (short) 1, new HashSet<>());
                            assertNull(fetchedAsyncRegistration);
                        });
    }

    /** Test that records in AsyncRegistration queue are fetched properly. */
    @Test
    public void testFetchNextQueuedAsyncRegistration_excludeByOrigin() {
        Uri origin1 = Uri.parse("https://adtech1.test");
        Uri origin2 = Uri.parse("https://adtech2.test");
        Uri regUri1 = origin1.buildUpon().appendPath("/hello").build();
        Uri regUri2 = origin2;
        AsyncRegistration asyncRegistration1 =
                AsyncRegistrationFixture.getValidAsyncRegistrationBuilder()
                        .setRegistrationUri(regUri1)
                        .build();
        AsyncRegistration asyncRegistration2 =
                AsyncRegistrationFixture.getValidAsyncRegistrationBuilder()
                        .setRegistrationUri(regUri2)
                        .build();

        DatastoreManager datastoreManager = DatastoreManagerFactory.getDatastoreManager(sContext);

        datastoreManager.runInTransaction(
                (dao) -> {
                    dao.insertAsyncRegistration(asyncRegistration1);
                    dao.insertAsyncRegistration(asyncRegistration2);
                });
        // Should fetch none
        Set<Uri> excludedOrigins1 = Set.of(origin1, origin2);
        Optional<AsyncRegistration> optAsyncRegistration =
                datastoreManager.runInTransactionWithResult(
                        (dao) -> dao.fetchNextQueuedAsyncRegistration((short) 4, excludedOrigins1));
        assertTrue(optAsyncRegistration.isEmpty());

        // Should fetch only origin1
        Set<Uri> excludedOrigins2 = Set.of(origin2);
        optAsyncRegistration =
                datastoreManager.runInTransactionWithResult(
                        (dao) -> dao.fetchNextQueuedAsyncRegistration((short) 4, excludedOrigins2));
        assertTrue(optAsyncRegistration.isPresent());
        assertEquals(regUri1, optAsyncRegistration.get().getRegistrationUri());

        // Should fetch only origin2
        Set<Uri> excludedOrigins3 = Set.of(origin1);
        optAsyncRegistration =
                datastoreManager.runInTransactionWithResult(
                        (dao) -> dao.fetchNextQueuedAsyncRegistration((short) 4, excludedOrigins3));
        assertTrue(optAsyncRegistration.isPresent());
        assertEquals(regUri2, optAsyncRegistration.get().getRegistrationUri());
    }

    /** Test that AsyncRegistration is deleted correctly. */
    @Test
    public void testDeleteAsyncRegistration() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        AsyncRegistration asyncRegistration = AsyncRegistrationFixture.getValidAsyncRegistration();
        String asyncRegistrationID = asyncRegistration.getId();

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertAsyncRegistration(asyncRegistration));
        try (Cursor cursor =
                MeasurementDbHelper.getInstance(sContext)
                        .getReadableDatabase()
                        .query(
                                MeasurementTables.AsyncRegistrationContract.TABLE,
                                null,
                                MeasurementTables.AsyncRegistrationContract.ID + " = ? ",
                                new String[] {asyncRegistration.getId().toString()},
                                null,
                                null,
                                null)) {
            assertTrue(cursor.moveToNext());
            AsyncRegistration updateAsyncRegistration =
                    SqliteObjectMapper.constructAsyncRegistration(cursor);
            assertNotNull(updateAsyncRegistration);
        }
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.deleteAsyncRegistration(asyncRegistration.getId()));

        db.query(
                /* table */ MeasurementTables.AsyncRegistrationContract.TABLE,
                /* columns */ null,
                /* selection */ MeasurementTables.AsyncRegistrationContract.ID + " = ? ",
                /* selectionArgs */ new String[] {asyncRegistrationID.toString()},
                /* groupBy */ null,
                /* having */ null,
                /* orderedBy */ null);

        assertThat(
                        db.query(
                                        /* table */ MeasurementTables.AsyncRegistrationContract
                                                .TABLE,
                                        /* columns */ null,
                                        /* selection */ MeasurementTables.AsyncRegistrationContract
                                                        .ID
                                                + " = ? ",
                                        /* selectionArgs */ new String[] {
                                            asyncRegistrationID.toString()
                                        },
                                        /* groupBy */ null,
                                        /* having */ null,
                                        /* orderedBy */ null)
                                .getCount())
                .isEqualTo(0);
    }

    @Test
    public void testDeleteAsyncRegistration_missingRecord() {
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        (dao) ->
                                assertThrows(
                                        "Async Registration already deleted",
                                        DatastoreException.class,
                                        () -> dao.deleteAsyncRegistration("missingAsyncRegId")));
    }

    @Test
    public void testDeleteAsyncRegistrationsProvidedRegistrant() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        AsyncRegistration ar1 =
                new AsyncRegistration.Builder()
                        .setId("1")
                        .setRegistrant(Uri.parse("android-app://installed-registrant1"))
                        .setTopOrigin(Uri.parse("android-app://installed-registrant1"))
                        .setAdIdPermission(false)
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setRequestTime(1)
                        .setRegistrationId(ValidAsyncRegistrationParams.REGISTRATION_ID)
                        .build();

        AsyncRegistration ar2 =
                new AsyncRegistration.Builder()
                        .setId("2")
                        .setRegistrant(Uri.parse("android-app://installed-registrant2"))
                        .setTopOrigin(Uri.parse("android-app://installed-registrant2"))
                        .setAdIdPermission(false)
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setRequestTime(Long.MAX_VALUE)
                        .setRegistrationId(ValidAsyncRegistrationParams.REGISTRATION_ID)
                        .build();

        AsyncRegistration ar3 =
                new AsyncRegistration.Builder()
                        .setId("3")
                        .setRegistrant(Uri.parse("android-app://installed-registrant3"))
                        .setTopOrigin(Uri.parse("android-app://installed-registrant3"))
                        .setAdIdPermission(false)
                        .setType(AsyncRegistration.RegistrationType.APP_SOURCE)
                        .setRequestTime(Long.MAX_VALUE)
                        .setRegistrationId(ValidAsyncRegistrationParams.REGISTRATION_ID)
                        .build();

        List<AsyncRegistration> asyncRegistrationList = List.of(ar1, ar2, ar3);
        asyncRegistrationList.forEach(
                asyncRegistration -> {
                    ContentValues values = new ContentValues();
                    values.put(AsyncRegistrationContract.ID, asyncRegistration.getId());
                    values.put(
                            AsyncRegistrationContract.REQUEST_TIME,
                            asyncRegistration.getRequestTime());
                    values.put(
                            AsyncRegistrationContract.REGISTRANT,
                            asyncRegistration.getRegistrant().toString());
                    values.put(
                            AsyncRegistrationContract.TOP_ORIGIN,
                            asyncRegistration.getTopOrigin().toString());
                    values.put(
                            AsyncRegistrationContract.REGISTRATION_ID,
                            asyncRegistration.getRegistrationId());
                    db.insert(AsyncRegistrationContract.TABLE, /* nullColumnHack */ null, values);
                });

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        (dao) -> {
                            dao.deleteAsyncRegistrationsProvidedRegistrant(
                                    "android-app://installed-registrant1");
                            dao.deleteAsyncRegistrationsProvidedRegistrant(
                                    "android-app://installed-registrant2");
                        });

        assertThat(
                        db.query(
                                        /* table */ MeasurementTables.AsyncRegistrationContract
                                                .TABLE,
                                        /* columns */ null,
                                        /* selection */ null,
                                        /* selectionArgs */ null,
                                        /* groupBy */ null,
                                        /* having */ null,
                                        /* orderedBy */ null)
                                .getCount())
                .isEqualTo(1);

        assertThat(
                        db.query(
                                        /* table */ MeasurementTables.AsyncRegistrationContract
                                                .TABLE,
                                        /* columns */ null,
                                        /* selection */ MeasurementTables.AsyncRegistrationContract
                                                        .ID
                                                + " = ? ",
                                        /* selectionArgs */ new String[] {"3"},
                                        /* groupBy */ null,
                                        /* having */ null,
                                        /* orderedBy */ null)
                                .getCount())
                .isEqualTo(1);
    }

    /** Test that retry count in AsyncRegistration is updated correctly. */
    @Test
    public void testUpdateAsyncRegistrationRetryCount() {
        AsyncRegistration asyncRegistration = AsyncRegistrationFixture.getValidAsyncRegistration();
        String asyncRegistrationId = asyncRegistration.getId();
        long originalRetryCount = asyncRegistration.getRetryCount();

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertAsyncRegistration(asyncRegistration));
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        (dao) -> {
                            asyncRegistration.incrementRetryCount();
                            dao.updateRetryCount(asyncRegistration);
                        });

        try (Cursor cursor =
                MeasurementDbHelper.getInstance(sContext)
                        .getReadableDatabase()
                        .query(
                                MeasurementTables.AsyncRegistrationContract.TABLE,
                                null,
                                MeasurementTables.AsyncRegistrationContract.ID + " = ? ",
                                new String[] {asyncRegistrationId},
                                null,
                                null,
                                null)) {
            assertTrue(cursor.moveToNext());
            AsyncRegistration updateAsyncRegistration =
                    SqliteObjectMapper.constructAsyncRegistration(cursor);
            assertNotNull(updateAsyncRegistration);
            assertTrue(updateAsyncRegistration.getRetryCount() == originalRetryCount + 1);
        }
    }

    @Test
    public void getSource_fetchesMatchingSourceFromDb() {
        // Setup - insert 2 sources with different IDs
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        String sourceId1 = "source1";
        Source source1WithoutDestinations =
                SourceFixture.getValidSourceBuilder()
                        .setId(sourceId1)
                        .setAppDestinations(null)
                        .setWebDestinations(null)
                        .build();
        Source source1WithDestinations =
                SourceFixture.getValidSourceBuilder()
                        .setId(sourceId1)
                        .setAppDestinations(null)
                        .setWebDestinations(null)
                        .build();
        insertInDb(db, source1WithDestinations);
        String sourceId2 = "source2";
        Source source2WithoutDestinations =
                SourceFixture.getValidSourceBuilder()
                        .setId(sourceId2)
                        .setAppDestinations(null)
                        .setWebDestinations(null)
                        .build();
        Source source2WithDestinations =
                SourceFixture.getValidSourceBuilder()
                        .setId(sourceId2)
                        .setAppDestinations(null)
                        .setWebDestinations(null)
                        .build();
        insertInDb(db, source2WithDestinations);

        // Execution
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        (dao) -> {
                            assertEquals(source1WithoutDestinations, dao.getSource(sourceId1));
                            assertEquals(source2WithoutDestinations, dao.getSource(sourceId2));
                        });
    }

    @Test
    public void fetchMatchingAggregateReports_returnsMatchingReports() {
        // setup - create reports for 3*3 combinations of source and trigger
        List<Source> sources =
                Arrays.asList(
                        SourceFixture.getValidSourceBuilder()
                                .setEventId(new UnsignedLong(1L))
                                .setId("source1")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setEventId(new UnsignedLong(2L))
                                .setId("source2")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setEventId(new UnsignedLong(3L))
                                .setId("source3")
                                .build());
        List<Trigger> triggers =
                Arrays.asList(
                        TriggerFixture.getValidTriggerBuilder().setId("trigger1").build(),
                        TriggerFixture.getValidTriggerBuilder().setId("trigger2").build(),
                        TriggerFixture.getValidTriggerBuilder().setId("trigger3").build());
        List<AggregateReport> reports =
                ImmutableList.of(
                        createAggregateReportForSourceAndTrigger(sources.get(0), triggers.get(0)),
                        createAggregateReportForSourceAndTrigger(sources.get(0), triggers.get(1)),
                        createAggregateReportForSourceAndTrigger(sources.get(0), triggers.get(2)),
                        createAggregateReportForSourceAndTrigger(sources.get(1), triggers.get(0)),
                        createAggregateReportForSourceAndTrigger(sources.get(1), triggers.get(1)),
                        createAggregateReportForSourceAndTrigger(sources.get(1), triggers.get(2)),
                        createAggregateReportForSourceAndTrigger(sources.get(2), triggers.get(0)),
                        createAggregateReportForSourceAndTrigger(sources.get(2), triggers.get(1)),
                        createAggregateReportForSourceAndTrigger(sources.get(2), triggers.get(2)));

        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).getWritableDatabase();
        sources.forEach(source -> insertSource(source, source.getId()));
        triggers.forEach(trigger -> AbstractDbIntegrationTest.insertToDb(trigger, db));
        reports.forEach(
                report ->
                        DatastoreManagerFactory.getDatastoreManager(sContext)
                                .runInTransaction((dao) -> dao.insertAggregateReport(report)));

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        (dao) -> {
                            // Execution
                            List<AggregateReport> aggregateReports =
                                    dao.fetchMatchingAggregateReports(
                                            Arrays.asList(
                                                    sources.get(1).getId(), "nonMatchingSource"),
                                            Arrays.asList(
                                                    triggers.get(2).getId(), "nonMatchingTrigger"));
                            assertEquals(5, aggregateReports.size());

                            aggregateReports =
                                    dao.fetchMatchingAggregateReports(
                                            Arrays.asList(
                                                    sources.get(0).getId(), sources.get(1).getId()),
                                            Collections.emptyList());
                            assertEquals(6, aggregateReports.size());

                            aggregateReports =
                                    dao.fetchMatchingAggregateReports(
                                            Collections.emptyList(),
                                            Arrays.asList(
                                                    triggers.get(0).getId(),
                                                    triggers.get(2).getId()));
                            assertEquals(6, aggregateReports.size());

                            aggregateReports =
                                    dao.fetchMatchingAggregateReports(
                                            Arrays.asList(
                                                    sources.get(0).getId(),
                                                    sources.get(1).getId(),
                                                    sources.get(2).getId()),
                                            Arrays.asList(
                                                    triggers.get(0).getId(),
                                                    triggers.get(1).getId(),
                                                    triggers.get(2).getId()));
                            assertEquals(9, aggregateReports.size());
                        });
    }

    @Test
    public void fetchMatchingEventReports_returnsMatchingReports() throws JSONException {
        // setup - create reports for 3*3 combinations of source and trigger
        List<Source> sources =
                Arrays.asList(
                        SourceFixture.getValidSourceBuilder()
                                .setEventId(new UnsignedLong(1L))
                                .setId("source1")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setEventId(new UnsignedLong(2L))
                                .setId("source2")
                                .build(),
                        SourceFixture.getValidSourceBuilder()
                                .setEventId(new UnsignedLong(3L))
                                .setId("source3")
                                .build());
        List<Trigger> triggers =
                Arrays.asList(
                        TriggerFixture.getValidTriggerBuilder()
                                .setEventTriggers(TriggerFixture.ValidTriggerParams.EVENT_TRIGGERS)
                                .setId("trigger1")
                                .build(),
                        TriggerFixture.getValidTriggerBuilder()
                                .setEventTriggers(TriggerFixture.ValidTriggerParams.EVENT_TRIGGERS)
                                .setId("trigger2")
                                .build(),
                        TriggerFixture.getValidTriggerBuilder()
                                .setEventTriggers(TriggerFixture.ValidTriggerParams.EVENT_TRIGGERS)
                                .setId("trigger3")
                                .build());
        List<EventReport> reports =
                ImmutableList.of(
                        createEventReportForSourceAndTrigger(sources.get(0), triggers.get(0)),
                        createEventReportForSourceAndTrigger(sources.get(0), triggers.get(1)),
                        createEventReportForSourceAndTrigger(sources.get(0), triggers.get(2)),
                        createEventReportForSourceAndTrigger(sources.get(1), triggers.get(0)),
                        createEventReportForSourceAndTrigger(sources.get(1), triggers.get(1)),
                        createEventReportForSourceAndTrigger(sources.get(1), triggers.get(2)),
                        createEventReportForSourceAndTrigger(sources.get(2), triggers.get(0)),
                        createEventReportForSourceAndTrigger(sources.get(2), triggers.get(1)),
                        createEventReportForSourceAndTrigger(sources.get(2), triggers.get(2)));

        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).getWritableDatabase();
        sources.forEach(source -> insertSource(source, source.getId()));
        triggers.forEach(trigger -> AbstractDbIntegrationTest.insertToDb(trigger, db));
        reports.forEach(
                report ->
                        DatastoreManagerFactory.getDatastoreManager(sContext)
                                .runInTransaction((dao) -> dao.insertEventReport(report)));

        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        (dao) -> {
                            // Execution
                            List<EventReport> eventReports =
                                    dao.fetchMatchingEventReports(
                                            Arrays.asList(
                                                    sources.get(1).getId(), "nonMatchingSource"),
                                            Arrays.asList(
                                                    triggers.get(2).getId(), "nonMatchingTrigger"));
                            assertEquals(5, eventReports.size());

                            eventReports =
                                    dao.fetchMatchingEventReports(
                                            Arrays.asList(
                                                    sources.get(0).getId(), sources.get(1).getId()),
                                            Collections.emptyList());
                            assertEquals(6, eventReports.size());

                            eventReports =
                                    dao.fetchMatchingEventReports(
                                            Collections.emptyList(),
                                            Arrays.asList(
                                                    triggers.get(0).getId(),
                                                    triggers.get(2).getId()));
                            assertEquals(6, eventReports.size());

                            eventReports =
                                    dao.fetchMatchingEventReports(
                                            Arrays.asList(
                                                    sources.get(0).getId(),
                                                    sources.get(1).getId(),
                                                    sources.get(2).getId()),
                                            Arrays.asList(
                                                    triggers.get(0).getId(),
                                                    triggers.get(1).getId(),
                                                    triggers.get(2).getId()));
                            assertEquals(9, eventReports.size());
                        });
    }

    @Test
    public void fetchMatchingSources_bringsMatchingSources() {
        // Setup
        Source source1 =
                SourceFixture.getValidSourceBuilder()
                        .setEventId(new UnsignedLong(1L))
                        .setPublisher(WebUtil.validUri("https://subdomain1.site1.test"))
                        .setEventTime(5000)
                        .setRegistrant(Uri.parse("android-app://com.registrant1"))
                        .setId("source1")
                        .build();
        Source source2 =
                SourceFixture.getValidSourceBuilder()
                        .setEventId(new UnsignedLong(2L))
                        .setPublisher(WebUtil.validUri("https://subdomain1.site1.test"))
                        .setEventTime(10000)
                        .setRegistrant(Uri.parse("android-app://com.registrant1"))
                        .setId("source2")
                        .build();
        Source source3 =
                SourceFixture.getValidSourceBuilder()
                        .setEventId(new UnsignedLong(3L))
                        .setPublisher(WebUtil.validUri("https://subdomain2.site1.test"))
                        .setEventTime(15000)
                        .setRegistrant(Uri.parse("android-app://com.registrant1"))
                        .setId("source3")
                        .build();
        Source source4 =
                SourceFixture.getValidSourceBuilder()
                        .setEventId(new UnsignedLong(4L))
                        .setPublisher(WebUtil.validUri("https://subdomain2.site2.test"))
                        .setEventTime(15000)
                        .setRegistrant(Uri.parse("android-app://com.registrant1"))
                        .setId("source4")
                        .build();
        Source source5 =
                SourceFixture.getValidSourceBuilder()
                        .setEventId(new UnsignedLong(5L))
                        .setPublisher(WebUtil.validUri("https://subdomain2.site1.test"))
                        .setEventTime(20000)
                        .setRegistrant(Uri.parse("android-app://com.registrant2"))
                        .setId("source5")
                        .build();
        List<Source> sources = List.of(source1, source2, source3, source4, source5);

        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).getWritableDatabase();
        sources.forEach(source -> insertInDb(db, source));

        // Execution
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        dao -> {
                            // --- DELETE behaviour ---
                            // Delete Nothing
                            // No matches
                            List<String> actualSources =
                                    dao.fetchMatchingSources(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_DELETE);
                            assertEquals(0, actualSources.size());

                            // 1 & 2 match registrant1 and "https://subdomain1.site1.test" publisher
                            // origin
                            actualSources =
                                    dao.fetchMatchingSources(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain1.site1.test")),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_DELETE);
                            assertEquals(2, actualSources.size());

                            // Only 2 matches registrant1 and "https://subdomain1.site1.test"
                            // publisher origin within
                            // the range
                            actualSources =
                                    dao.fetchMatchingSources(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(8000),
                                            Instant.ofEpochMilli(50000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain1.site1.test")),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_DELETE);
                            assertEquals(1, actualSources.size());

                            // 1,2 & 3 matches registrant1 and "https://site1.test" publisher origin
                            actualSources =
                                    dao.fetchMatchingSources(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(),
                                            List.of(WebUtil.validUri("https://site1.test")),
                                            DeletionRequest.MATCH_BEHAVIOR_DELETE);
                            assertEquals(3, actualSources.size());

                            // 3 matches origin and 4 matches domain URI
                            actualSources =
                                    dao.fetchMatchingSources(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(10000),
                                            Instant.ofEpochMilli(20000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain2.site1.test")),
                                            List.of(WebUtil.validUri("https://site2.test")),
                                            DeletionRequest.MATCH_BEHAVIOR_DELETE);
                            assertEquals(2, actualSources.size());

                            // --- PRESERVE (anti-match exception registrant) behaviour ---
                            // Preserve Nothing
                            // 1,2,3 & 4 are match registrant1
                            actualSources =
                                    dao.fetchMatchingSources(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_PRESERVE);
                            assertEquals(4, actualSources.size());

                            // 3 & 4 match registrant1 and don't match
                            // "https://subdomain1.site1.test" publisher origin
                            actualSources =
                                    dao.fetchMatchingSources(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain1.site1.test")),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_PRESERVE);
                            assertEquals(2, actualSources.size());

                            // 3 & 4 match registrant1, in range and don't match
                            // "https://subdomain1.site1.test"
                            actualSources =
                                    dao.fetchMatchingSources(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(8000),
                                            Instant.ofEpochMilli(50000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain1.site1.test")),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_PRESERVE);
                            assertEquals(2, actualSources.size());

                            // Only 4 matches registrant1, in range and don't match
                            // "https://site1.test"
                            actualSources =
                                    dao.fetchMatchingSources(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(),
                                            List.of(WebUtil.validUri("https://site1.test")),
                                            DeletionRequest.MATCH_BEHAVIOR_PRESERVE);
                            assertEquals(1, actualSources.size());

                            // only 2 is registrant1 based, in range and does not match either
                            // site2.test or subdomain2.site1.test
                            actualSources =
                                    dao.fetchMatchingSources(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(10000),
                                            Instant.ofEpochMilli(20000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain2.site1.test")),
                                            List.of(WebUtil.validUri("https://site2.test")),
                                            DeletionRequest.MATCH_BEHAVIOR_PRESERVE);
                            assertEquals(1, actualSources.size());
                        });
    }

    @Test
    public void fetchMatchingTriggers_bringsMatchingTriggers() {
        // Setup
        Trigger trigger1 =
                TriggerFixture.getValidTriggerBuilder()
                        .setAttributionDestination(
                                WebUtil.validUri("https://subdomain1.site1.test"))
                        .setTriggerTime(5000)
                        .setRegistrant(Uri.parse("android-app://com.registrant1"))
                        .setId("trigger1")
                        .build();
        Trigger trigger2 =
                TriggerFixture.getValidTriggerBuilder()
                        .setAttributionDestination(
                                WebUtil.validUri("https://subdomain1.site1.test"))
                        .setTriggerTime(10000)
                        .setRegistrant(Uri.parse("android-app://com.registrant1"))
                        .setId("trigger2")
                        .build();
        Trigger trigger3 =
                TriggerFixture.getValidTriggerBuilder()
                        .setAttributionDestination(
                                WebUtil.validUri("https://subdomain2.site1.test"))
                        .setTriggerTime(15000)
                        .setRegistrant(Uri.parse("android-app://com.registrant1"))
                        .setId("trigger3")
                        .build();
        Trigger trigger4 =
                TriggerFixture.getValidTriggerBuilder()
                        .setAttributionDestination(
                                WebUtil.validUri("https://subdomain2.site2.test"))
                        .setTriggerTime(15000)
                        .setRegistrant(Uri.parse("android-app://com.registrant1"))
                        .setId("trigger4")
                        .build();
        Trigger trigger5 =
                TriggerFixture.getValidTriggerBuilder()
                        .setAttributionDestination(
                                WebUtil.validUri("https://subdomain2.site1.test"))
                        .setTriggerTime(20000)
                        .setRegistrant(Uri.parse("android-app://com.registrant2"))
                        .setId("trigger5")
                        .build();
        List<Trigger> triggers = List.of(trigger1, trigger2, trigger3, trigger4, trigger5);

        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).getWritableDatabase();
        triggers.forEach(
                trigger -> {
                    ContentValues values = new ContentValues();
                    values.put(TriggerContract.ID, trigger.getId());
                    values.put(
                            TriggerContract.ATTRIBUTION_DESTINATION,
                            trigger.getAttributionDestination().toString());
                    values.put(TriggerContract.TRIGGER_TIME, trigger.getTriggerTime());
                    values.put(TriggerContract.ENROLLMENT_ID, trigger.getEnrollmentId());
                    values.put(TriggerContract.REGISTRANT, trigger.getRegistrant().toString());
                    values.put(TriggerContract.STATUS, trigger.getStatus());
                    db.insert(TriggerContract.TABLE, /* nullColumnHack */ null, values);
                });

        // Execution
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction(
                        dao -> {
                            // --- DELETE behaviour ---
                            // Delete Nothing
                            // No Matches
                            List<String> actualSources =
                                    dao.fetchMatchingTriggers(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_DELETE);
                            assertEquals(0, actualSources.size());

                            // 1 & 2 match registrant1 and "https://subdomain1.site1.test" publisher
                            // origin
                            actualSources =
                                    dao.fetchMatchingTriggers(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain1.site1.test")),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_DELETE);
                            assertEquals(2, actualSources.size());

                            // Only 2 matches registrant1 and "https://subdomain1.site1.test"
                            // publisher origin within
                            // the range
                            actualSources =
                                    dao.fetchMatchingTriggers(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(8000),
                                            Instant.ofEpochMilli(50000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain1.site1.test")),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_DELETE);
                            assertEquals(1, actualSources.size());

                            // 1,2 & 3 matches registrant1 and "https://site1.test" publisher origin
                            actualSources =
                                    dao.fetchMatchingTriggers(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(),
                                            List.of(WebUtil.validUri("https://site1.test")),
                                            DeletionRequest.MATCH_BEHAVIOR_DELETE);
                            assertEquals(3, actualSources.size());

                            // 3 matches origin and 4 matches domain URI
                            actualSources =
                                    dao.fetchMatchingTriggers(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(10000),
                                            Instant.ofEpochMilli(20000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain2.site1.test")),
                                            List.of(WebUtil.validUri("https://site2.test")),
                                            DeletionRequest.MATCH_BEHAVIOR_DELETE);
                            assertEquals(2, actualSources.size());

                            // --- PRESERVE (anti-match exception registrant) behaviour ---
                            // Preserve Nothing
                            // 1,2,3 & 4 are match registrant1
                            actualSources =
                                    dao.fetchMatchingTriggers(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_PRESERVE);
                            assertEquals(4, actualSources.size());

                            // 3 & 4 match registrant1 and don't match
                            // "https://subdomain1.site1.test" publisher origin
                            actualSources =
                                    dao.fetchMatchingTriggers(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain1.site1.test")),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_PRESERVE);
                            assertEquals(2, actualSources.size());

                            // 3 & 4 match registrant1, in range and don't match
                            // "https://subdomain1.site1.test"
                            actualSources =
                                    dao.fetchMatchingTriggers(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(8000),
                                            Instant.ofEpochMilli(50000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain1.site1.test")),
                                            List.of(),
                                            DeletionRequest.MATCH_BEHAVIOR_PRESERVE);
                            assertEquals(2, actualSources.size());

                            // Only 4 matches registrant1, in range and don't match
                            // "https://site1.test"
                            actualSources =
                                    dao.fetchMatchingTriggers(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(0),
                                            Instant.ofEpochMilli(50000),
                                            List.of(),
                                            List.of(WebUtil.validUri("https://site1.test")),
                                            DeletionRequest.MATCH_BEHAVIOR_PRESERVE);
                            assertEquals(1, actualSources.size());

                            // only 2 is registrant1 based, in range and does not match either
                            // site2.test or subdomain2.site1.test
                            actualSources =
                                    dao.fetchMatchingTriggers(
                                            Uri.parse("android-app://com.registrant1"),
                                            Instant.ofEpochMilli(10000),
                                            Instant.ofEpochMilli(20000),
                                            List.of(
                                                    WebUtil.validUri(
                                                            "https://subdomain2.site1.test")),
                                            List.of(WebUtil.validUri("https://site2.test")),
                                            DeletionRequest.MATCH_BEHAVIOR_PRESERVE);
                            assertEquals(1, actualSources.size());
                        });
    }

    @Test
    public void testUpdateSourceAggregateReportDedupKeys_updatesKeysInList() {
        Source validSource = SourceFixture.getValidSource();
        assertTrue(validSource.getAggregateReportDedupKeys().equals(new ArrayList<UnsignedLong>()));
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.insertSource(validSource));

        String sourceId = getFirstSourceIdFromDatastore();
        Source source =
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao -> measurementDao.getSource(sourceId))
                        .get();

        source.getAggregateReportDedupKeys().add(new UnsignedLong(10L));
        DatastoreManagerFactory.getDatastoreManager(sContext)
                .runInTransaction((dao) -> dao.updateSourceAggregateReportDedupKeys(source));

        Source sourceAfterUpdate =
                DatastoreManagerFactory.getDatastoreManager(sContext)
                        .runInTransactionWithResult(
                                measurementDao -> measurementDao.getSource(sourceId))
                        .get();

        assertTrue(sourceAfterUpdate.getAggregateReportDedupKeys().size() == 1);
        assertTrue(sourceAfterUpdate.getAggregateReportDedupKeys().get(0).getValue() == 10L);
    }

    @Test
    public void fetchTriggerMatchingSourcesForXna_filtersSourcesCorrectly() {
        // Setup
        Uri matchingDestination = APP_ONE_DESTINATION;
        Uri nonMatchingDestination = APP_TWO_DESTINATION;
        String mmpMatchingEnrollmentId = "mmp1";
        String mmpNonMatchingEnrollmentId = "mmpx";
        String san1MatchingEnrollmentId = "san1EnrollmentId";
        String san2MatchingEnrollmentId = "san2EnrollmentId";
        String san3MatchingEnrollmentId = "san3EnrollmentId";
        String san4NonMatchingEnrollmentId = "san4EnrollmentId";
        String san5MatchingEnrollmentId = "san5EnrollmentId";

        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAttributionDestination(matchingDestination)
                        .setEnrollmentId(mmpMatchingEnrollmentId)
                        .setRegistrant(TriggerFixture.ValidTriggerParams.REGISTRANT)
                        .setTriggerTime(TriggerFixture.ValidTriggerParams.TRIGGER_TIME)
                        .setEventTriggers(TriggerFixture.ValidTriggerParams.EVENT_TRIGGERS)
                        .setAggregateTriggerData(
                                TriggerFixture.ValidTriggerParams.AGGREGATE_TRIGGER_DATA)
                        .setAggregateValues(TriggerFixture.ValidTriggerParams.AGGREGATE_VALUES)
                        .setFilters(TriggerFixture.ValidTriggerParams.TOP_LEVEL_FILTERS_JSON_STRING)
                        .setNotFilters(
                                TriggerFixture.ValidTriggerParams.TOP_LEVEL_NOT_FILTERS_JSON_STRING)
                        .build();
        Source s1MmpMatchingWithDestinations =
                createSourceBuilder()
                        .setId("s1")
                        .setEnrollmentId(mmpMatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .build();
        Source s1MmpMatchingWithoutDestinations =
                createSourceBuilder()
                        .setId("s1")
                        .setEnrollmentId(mmpMatchingEnrollmentId)
                        .setAppDestinations(null)
                        .setWebDestinations(null)
                        .setRegistrationId(s1MmpMatchingWithDestinations.getRegistrationId())
                        .build();
        Source s2MmpDiffDestination =
                createSourceBuilder()
                        .setId("s2")
                        .setEnrollmentId(mmpMatchingEnrollmentId)
                        .setAppDestinations(List.of(nonMatchingDestination))
                        .build();
        Source s3MmpExpired =
                createSourceBuilder()
                        .setId("s3")
                        .setEnrollmentId(mmpMatchingEnrollmentId)
                        .setAppDestinations(List.of(nonMatchingDestination))
                        // expired before trigger time
                        .setExpiryTime(trigger.getTriggerTime() - TimeUnit.DAYS.toMillis(1))
                        .build();
        Source s4NonMatchingMmp =
                createSourceBuilder()
                        .setId("s4")
                        .setEnrollmentId(mmpNonMatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .build();
        Source s5MmpMatchingWithDestinations =
                createSourceBuilder()
                        .setId("s5")
                        .setEnrollmentId(mmpMatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .build();
        Source s5MmpMatchingWithoutDestinations =
                createSourceBuilder()
                        .setId("s5")
                        .setEnrollmentId(mmpMatchingEnrollmentId)
                        .setAppDestinations(null)
                        .setWebDestinations(null)
                        .setRegistrationId(s5MmpMatchingWithDestinations.getRegistrationId())
                        .build();
        Source s6San1MatchingWithDestinations =
                createSourceBuilder()
                        .setId("s6")
                        .setEnrollmentId(san1MatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .setSharedAggregationKeys(SHARED_AGGREGATE_KEYS)
                        .build();
        Source s6San1MatchingWithoutDestinations =
                createSourceBuilder()
                        .setId("s6")
                        .setEnrollmentId(san1MatchingEnrollmentId)
                        .setAppDestinations(null)
                        .setWebDestinations(null)
                        .setRegistrationId(s6San1MatchingWithDestinations.getRegistrationId())
                        .setSharedAggregationKeys(SHARED_AGGREGATE_KEYS)
                        .build();
        Source s7San1DiffDestination =
                createSourceBuilder()
                        .setId("s7")
                        .setEnrollmentId(san1MatchingEnrollmentId)
                        .setAppDestinations(List.of(nonMatchingDestination))
                        .setSharedAggregationKeys(SHARED_AGGREGATE_KEYS)
                        .build();
        Source s8San2MatchingWithDestinations =
                createSourceBuilder()
                        .setId("s8")
                        .setEnrollmentId(san2MatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .setSharedAggregationKeys(SHARED_AGGREGATE_KEYS)
                        .build();
        Source s8San2MatchingWithoutDestinations =
                createSourceBuilder()
                        .setId("s8")
                        .setEnrollmentId(san2MatchingEnrollmentId)
                        .setAppDestinations(null)
                        .setWebDestinations(null)
                        .setRegistrationId(s8San2MatchingWithDestinations.getRegistrationId())
                        .setSharedAggregationKeys(SHARED_AGGREGATE_KEYS)
                        .build();
        Source s9San3XnaIgnored =
                createSourceBuilder()
                        .setId("s9")
                        .setEnrollmentId(san3MatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .setSharedAggregationKeys(SHARED_AGGREGATE_KEYS)
                        .build();
        Source s10San3MatchingWithDestinations =
                createSourceBuilder()
                        .setId("s10")
                        .setEnrollmentId(san3MatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .setSharedAggregationKeys(SHARED_AGGREGATE_KEYS)
                        .build();
        Source s10San3MatchingWithoutDestinations =
                createSourceBuilder()
                        .setId("s10")
                        .setEnrollmentId(san3MatchingEnrollmentId)
                        .setAppDestinations(null)
                        .setWebDestinations(null)
                        .setRegistrationId(s10San3MatchingWithDestinations.getRegistrationId())
                        .setSharedAggregationKeys(SHARED_AGGREGATE_KEYS)
                        .build();
        Source s11San4EnrollmentNonMatching =
                createSourceBuilder()
                        .setId("s11")
                        .setEnrollmentId(san4NonMatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .setSharedAggregationKeys(SHARED_AGGREGATE_KEYS)
                        .build();
        Source s12San1NullSharedAggregationKeys =
                createSourceBuilder()
                        .setId("s12")
                        .setEnrollmentId(san1MatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .setSharedAggregationKeys(null)
                        .build();
        Source s13San1Expired =
                createSourceBuilder()
                        .setId("s13")
                        .setEnrollmentId(san1MatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        // expired before trigger time
                        .setExpiryTime(trigger.getTriggerTime() - TimeUnit.DAYS.toMillis(1))
                        .build();
        String registrationIdForTriggerAndOtherRegistration = UUID.randomUUID().toString();
        Source s14San5RegIdClasesWithMmp =
                createSourceBuilder()
                        .setId("s14")
                        .setEnrollmentId(san5MatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .setRegistrationId(registrationIdForTriggerAndOtherRegistration)
                        .build();
        Source s15MmpMatchingWithDestinations =
                createSourceBuilder()
                        .setId("s15")
                        .setEnrollmentId(mmpMatchingEnrollmentId)
                        .setAppDestinations(List.of(matchingDestination))
                        .setRegistrationId(registrationIdForTriggerAndOtherRegistration)
                        .build();
        Source s15MmpMatchingWithoutDestinations =
                createSourceBuilder()
                        .setId("s15")
                        .setEnrollmentId(mmpMatchingEnrollmentId)
                        .setAppDestinations(null)
                        .setWebDestinations(null)
                        .setRegistrationId(registrationIdForTriggerAndOtherRegistration)
                        .build();
        List<Source> sources =
                Arrays.asList(
                        s1MmpMatchingWithDestinations,
                        s2MmpDiffDestination,
                        s3MmpExpired,
                        s4NonMatchingMmp,
                        s5MmpMatchingWithDestinations,
                        s6San1MatchingWithDestinations,
                        s7San1DiffDestination,
                        s8San2MatchingWithDestinations,
                        s9San3XnaIgnored,
                        s10San3MatchingWithDestinations,
                        s11San4EnrollmentNonMatching,
                        s12San1NullSharedAggregationKeys,
                        s13San1Expired,
                        s14San5RegIdClasesWithMmp,
                        s15MmpMatchingWithDestinations);
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        Objects.requireNonNull(db);
        // Insert all sources to the DB
        sources.forEach(source -> insertSource(source, source.getId()));

        // Insert XNA ignored sources
        ContentValues values = new ContentValues();
        values.put(XnaIgnoredSourcesContract.SOURCE_ID, s9San3XnaIgnored.getId());
        values.put(XnaIgnoredSourcesContract.ENROLLMENT_ID, san3MatchingEnrollmentId);
        long row = db.insert(XnaIgnoredSourcesContract.TABLE, null, values);
        assertEquals(1, row);

        List<Source> expectedMatchingSources =
                Arrays.asList(
                        s1MmpMatchingWithoutDestinations,
                        s5MmpMatchingWithoutDestinations,
                        s6San1MatchingWithoutDestinations,
                        s8San2MatchingWithoutDestinations,
                        s10San3MatchingWithoutDestinations,
                        s15MmpMatchingWithoutDestinations);
        Comparator<Source> sortingComparator = Comparator.comparing(Source::getId);
        expectedMatchingSources.sort(sortingComparator);

        // Execution
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        dm.runInTransaction(
                dao -> {
                    List<String> matchingSanEnrollmentIds =
                            Arrays.asList(
                                    san1MatchingEnrollmentId,
                                    san2MatchingEnrollmentId,
                                    san3MatchingEnrollmentId,
                                    san5MatchingEnrollmentId);
                    List<Source> actualMatchingSources =
                            dao.fetchTriggerMatchingSourcesForXna(
                                    trigger, matchingSanEnrollmentIds);
                    actualMatchingSources.sort(sortingComparator);
                    // Assertion
                    assertEquals(expectedMatchingSources, actualMatchingSources);
                });
    }

    @Test
    public void insertIgnoredSourceForEnrollment_success() {
        // Setup
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);
        // Need to insert sources before, to honor the foreign key constraint
        insertSource(createSourceBuilder().setId("s1").build(), "s1");
        insertSource(createSourceBuilder().setId("s2").build(), "s2");

        Pair<String, String> entry11 = new Pair<>("s1", "e1");
        Pair<String, String> entry21 = new Pair<>("s2", "e1");
        Pair<String, String> entry22 = new Pair<>("s2", "e2");

        dm.runInTransaction(
                dao -> {
                    // Execution
                    dao.insertIgnoredSourceForEnrollment(entry11.first, entry11.second);
                    dao.insertIgnoredSourceForEnrollment(entry21.first, entry21.second);
                    dao.insertIgnoredSourceForEnrollment(entry22.first, entry22.second);

                    // Assertion
                    queryAndAssertSourceEntries(db, "e1", Arrays.asList("s1", "s2"));
                    queryAndAssertSourceEntries(db, "e2", Collections.singletonList("s2"));
                });
    }

    @Test
    public void countDistinctDebugAdIdsUsedByEnrollment_oneTriggerAndSource() {
        // Setup
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);

        Source.Builder sourceBuilder =
                new Source.Builder()
                        .setPublisher(SourceFixture.ValidSourceParams.PUBLISHER)
                        .setRegistrant(SourceFixture.ValidSourceParams.REGISTRANT)
                        .setSourceType(SourceFixture.ValidSourceParams.SOURCE_TYPE)
                        .setEventId(SourceFixture.ValidSourceParams.SOURCE_EVENT_ID)
                        .setRegistrationOrigin(SourceFixture.ValidSourceParams.REGISTRATION_ORIGIN);
        Source s1 =
                sourceBuilder
                        .setId("s1")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s1));

        Trigger.Builder triggerBuilder =
                new Trigger.Builder()
                        .setAttributionDestination(
                                TriggerFixture.ValidTriggerParams.ATTRIBUTION_DESTINATION)
                        .setRegistrant(TriggerFixture.ValidTriggerParams.REGISTRANT)
                        .setRegistrationOrigin(
                                TriggerFixture.ValidTriggerParams.REGISTRATION_ORIGIN);
        Trigger t1 =
                triggerBuilder
                        .setId("t1")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t1));

        // Assertion
        assertTrue(
                dm.runInTransaction(
                        dao ->
                                assertEquals(
                                        1,
                                        dao.countDistinctDebugAdIdsUsedByEnrollment(
                                                "enrollment-id-1"))));
    }

    @Test
    public void countDistinctDebugAdIdsUsedByEnrollment_nullValuesPresent() {
        // Setup
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);

        Source.Builder sourceBuilder =
                new Source.Builder()
                        .setPublisher(SourceFixture.ValidSourceParams.PUBLISHER)
                        .setRegistrant(SourceFixture.ValidSourceParams.REGISTRANT)
                        .setSourceType(SourceFixture.ValidSourceParams.SOURCE_TYPE)
                        .setEventId(SourceFixture.ValidSourceParams.SOURCE_EVENT_ID)
                        .setRegistrationOrigin(SourceFixture.ValidSourceParams.REGISTRATION_ORIGIN);
        // Source with debug AdId present
        Source s1 =
                sourceBuilder
                        .setId("s1")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s1));
        // Source with no debug AdId
        Source s2 =
                sourceBuilder
                        .setId("s2")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s2));

        Trigger.Builder triggerBuilder =
                new Trigger.Builder()
                        .setAttributionDestination(
                                TriggerFixture.ValidTriggerParams.ATTRIBUTION_DESTINATION)
                        .setRegistrant(TriggerFixture.ValidTriggerParams.REGISTRANT)
                        .setRegistrationOrigin(
                                TriggerFixture.ValidTriggerParams.REGISTRATION_ORIGIN);
        // Trigger with debug AdId present
        Trigger t1 =
                triggerBuilder
                        .setId("t1")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t1));
        // Trigger with no debug AdId
        Trigger t2 =
                triggerBuilder
                        .setId("t2")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t2));

        // Assertion
        assertTrue(
                dm.runInTransaction(
                        dao ->
                                assertEquals(
                                        1,
                                        dao.countDistinctDebugAdIdsUsedByEnrollment(
                                                "enrollment-id-1"))));
    }

    @Test
    public void countDistinctDebugAdIdsUsedByEnrollment_multipleSourcesAndTriggers() {
        // Setup
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);

        Source.Builder sourceBuilder =
                new Source.Builder()
                        .setPublisher(SourceFixture.ValidSourceParams.PUBLISHER)
                        .setRegistrant(SourceFixture.ValidSourceParams.REGISTRANT)
                        .setSourceType(SourceFixture.ValidSourceParams.SOURCE_TYPE)
                        .setEventId(SourceFixture.ValidSourceParams.SOURCE_EVENT_ID)
                        .setRegistrationOrigin(SourceFixture.ValidSourceParams.REGISTRATION_ORIGIN);
        // Multiple sources with same AdId
        Source s1 =
                sourceBuilder
                        .setId("s1")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s1));
        Source s2 =
                sourceBuilder
                        .setId("s2")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s2));
        Source s3 =
                sourceBuilder
                        .setId("s3")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s3));

        Trigger.Builder triggerBuilder =
                new Trigger.Builder()
                        .setAttributionDestination(
                                TriggerFixture.ValidTriggerParams.ATTRIBUTION_DESTINATION)
                        .setRegistrant(TriggerFixture.ValidTriggerParams.REGISTRANT)
                        .setRegistrationOrigin(
                                TriggerFixture.ValidTriggerParams.REGISTRATION_ORIGIN);
        // Multiple triggers with same AdId
        Trigger t1 =
                triggerBuilder
                        .setId("t1")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t1));
        Trigger t2 =
                triggerBuilder
                        .setId("t2")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t2));
        Trigger t3 =
                triggerBuilder
                        .setId("t3")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t3));

        // Assertion
        assertTrue(
                dm.runInTransaction(
                        dao ->
                                assertEquals(
                                        1,
                                        dao.countDistinctDebugAdIdsUsedByEnrollment(
                                                "enrollment-id-1"))));
    }

    @Test
    public void countDistinctDebugAdIdsUsedByEnrollment_multipleAdIdsPresent() {
        // Setup
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);

        Source.Builder sourceBuilder =
                new Source.Builder()
                        .setPublisher(SourceFixture.ValidSourceParams.PUBLISHER)
                        .setRegistrant(SourceFixture.ValidSourceParams.REGISTRANT)
                        .setSourceType(SourceFixture.ValidSourceParams.SOURCE_TYPE)
                        .setEventId(SourceFixture.ValidSourceParams.SOURCE_EVENT_ID)
                        .setRegistrationOrigin(SourceFixture.ValidSourceParams.REGISTRATION_ORIGIN);
        // Multiple sources with different AdIds but the same enrollmentId
        Source s1 =
                sourceBuilder
                        .setId("s1")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s1));
        Source s2 =
                sourceBuilder
                        .setId("s2")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-2")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s2));
        Source s3 =
                sourceBuilder
                        .setId("s3")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-3")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s3));

        Trigger.Builder triggerBuilder =
                new Trigger.Builder()
                        .setAttributionDestination(
                                TriggerFixture.ValidTriggerParams.ATTRIBUTION_DESTINATION)
                        .setRegistrant(TriggerFixture.ValidTriggerParams.REGISTRANT)
                        .setRegistrationOrigin(
                                TriggerFixture.ValidTriggerParams.REGISTRATION_ORIGIN);
        // Multiple triggers with different AdIds but the same enrollmentId
        Trigger t1 =
                triggerBuilder
                        .setId("t1")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-4")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t1));
        Trigger t2 =
                triggerBuilder
                        .setId("t2")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-5")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t2));
        Trigger t3 =
                triggerBuilder
                        .setId("t3")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-6")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t3));

        // Assertion
        assertTrue(
                dm.runInTransaction(
                        dao ->
                                assertEquals(
                                        6,
                                        dao.countDistinctDebugAdIdsUsedByEnrollment(
                                                "enrollment-id-1"))));
    }

    @Test
    public void countDistinctDebugAdIdsUsedByEnrollment_multipleEnrollmentIdsPresent() {
        // Setup
        DatastoreManager dm = DatastoreManagerFactory.getDatastoreManager(sContext);

        Source.Builder sourceBuilder =
                new Source.Builder()
                        .setPublisher(SourceFixture.ValidSourceParams.PUBLISHER)
                        .setRegistrant(SourceFixture.ValidSourceParams.REGISTRANT)
                        .setSourceType(SourceFixture.ValidSourceParams.SOURCE_TYPE)
                        .setEventId(SourceFixture.ValidSourceParams.SOURCE_EVENT_ID)
                        .setRegistrationOrigin(SourceFixture.ValidSourceParams.REGISTRATION_ORIGIN);
        // Multiple sources with different AdIds and differing enrollmentIds
        Source s1 =
                sourceBuilder
                        .setId("s1")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-1")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s1));
        Source s2 =
                sourceBuilder
                        .setId("s2")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-2")
                        .setEnrollmentId("enrollment-id-2")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s2));
        Source s3 =
                sourceBuilder
                        .setId("s3")
                        .setPublisherType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-3")
                        .setEnrollmentId("enrollment-id-2")
                        .build();
        dm.runInTransaction(dao -> dao.insertSource(s3));

        Trigger.Builder triggerBuilder =
                new Trigger.Builder()
                        .setAttributionDestination(
                                TriggerFixture.ValidTriggerParams.ATTRIBUTION_DESTINATION)
                        .setRegistrant(TriggerFixture.ValidTriggerParams.REGISTRANT)
                        .setRegistrationOrigin(
                                TriggerFixture.ValidTriggerParams.REGISTRATION_ORIGIN);
        // Multiple triggers with different AdIds and differing enrollmentIds
        Trigger t1 =
                triggerBuilder
                        .setId("t1")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-4")
                        .setEnrollmentId("enrollment-id-1")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t1));
        Trigger t2 =
                triggerBuilder
                        .setId("t2")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-5")
                        .setEnrollmentId("enrollment-id-2")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t2));
        Trigger t3 =
                triggerBuilder
                        .setId("t3")
                        .setDestinationType(EventSurfaceType.WEB)
                        .setDebugAdId("debug-ad-id-6")
                        .setEnrollmentId("enrollment-id-2")
                        .build();
        dm.runInTransaction(dao -> dao.insertTrigger(t3));

        // Assertion
        assertTrue(
                dm.runInTransaction(
                        dao ->
                                assertEquals(
                                        2,
                                        dao.countDistinctDebugAdIdsUsedByEnrollment(
                                                "enrollment-id-1"))));
        assertTrue(
                dm.runInTransaction(
                        dao ->
                                assertEquals(
                                        4,
                                        dao.countDistinctDebugAdIdsUsedByEnrollment(
                                                "enrollment-id-2"))));
    }

    private void queryAndAssertSourceEntries(
            SQLiteDatabase db, String enrollmentId, List<String> expectedSourceIds) {
        try (Cursor cursor =
                db.query(
                        XnaIgnoredSourcesContract.TABLE,
                        new String[] {XnaIgnoredSourcesContract.SOURCE_ID},
                        XnaIgnoredSourcesContract.ENROLLMENT_ID + " = ?",
                        new String[] {enrollmentId},
                        null,
                        null,
                        null)) {
            assertEquals(expectedSourceIds.size(), cursor.getCount());
            for (int i = 0; i < expectedSourceIds.size() && cursor.moveToNext(); i++) {
                assertEquals(expectedSourceIds.get(i), cursor.getString(0));
            }
        }
    }

    private Source.Builder createSourceBuilder() {
        return new Source.Builder()
                .setEventId(SourceFixture.ValidSourceParams.SOURCE_EVENT_ID)
                .setPublisher(SourceFixture.ValidSourceParams.PUBLISHER)
                .setAppDestinations(SourceFixture.ValidSourceParams.ATTRIBUTION_DESTINATIONS)
                .setWebDestinations(SourceFixture.ValidSourceParams.WEB_DESTINATIONS)
                .setEnrollmentId(SourceFixture.ValidSourceParams.ENROLLMENT_ID)
                .setRegistrant(SourceFixture.ValidSourceParams.REGISTRANT)
                .setEventTime(SourceFixture.ValidSourceParams.SOURCE_EVENT_TIME)
                .setExpiryTime(SourceFixture.ValidSourceParams.EXPIRY_TIME)
                .setPriority(SourceFixture.ValidSourceParams.PRIORITY)
                .setSourceType(SourceFixture.ValidSourceParams.SOURCE_TYPE)
                .setInstallAttributionWindow(
                        SourceFixture.ValidSourceParams.INSTALL_ATTRIBUTION_WINDOW)
                .setInstallCooldownWindow(SourceFixture.ValidSourceParams.INSTALL_COOLDOWN_WINDOW)
                .setAttributionMode(SourceFixture.ValidSourceParams.ATTRIBUTION_MODE)
                .setAggregateSource(SourceFixture.ValidSourceParams.buildAggregateSource())
                .setFilterData(SourceFixture.ValidSourceParams.buildFilterData())
                .setIsDebugReporting(true)
                .setRegistrationId(UUID.randomUUID().toString())
                .setSharedAggregationKeys(SHARED_AGGREGATE_KEYS)
                .setInstallTime(SourceFixture.ValidSourceParams.INSTALL_TIME)
                .setRegistrationOrigin(SourceFixture.ValidSourceParams.REGISTRATION_ORIGIN);
    }

    private AggregateReport createAggregateReportForSourceAndTrigger(
            Source source, Trigger trigger) {
        return createAggregateReportForSourceAndTrigger(
                UUID.randomUUID().toString(), source, trigger);
    }

    private EventReport createEventReportForSourceAndTrigger(Source source, Trigger trigger)
            throws JSONException {
        return createEventReportForSourceAndTrigger(UUID.randomUUID().toString(), source, trigger);
    }

    private AggregateReport createAggregateReportForSourceAndTrigger(
            String reportId, Source source, Trigger trigger) {
        return AggregateReportFixture.getValidAggregateReportBuilder()
                .setId(reportId)
                .setSourceId(source.getId())
                .setTriggerId(trigger.getId())
                .build();
    }

    private EventReport createEventReportForSourceAndTrigger(
            String reportId, Source source, Trigger trigger) throws JSONException {

        return new EventReport.Builder()
                .setId(reportId)
                .populateFromSourceAndTrigger(
                        source,
                        trigger,
                        trigger.parseEventTriggers().get(0),
                        new Pair<>(null, null),
                        new EventReportWindowCalcDelegate(mFlags),
                        new SourceNoiseHandler(mFlags),
                        source.getAttributionDestinations(trigger.getDestinationType()))
                .setSourceEventId(source.getEventId())
                .setSourceId(source.getId())
                .setTriggerId(trigger.getId())
                .build();
    }

    private DebugReport createDebugReport() {
        return new DebugReport.Builder()
                .setId("reportId")
                .setType("trigger-event-deduplicated")
                .setBody(
                        " {\n"
                                + "      \"attribution_destination\":"
                                + " \"https://destination.example\",\n"
                                + "      \"source_event_id\": \"45623\"\n"
                                + "    }")
                .setEnrollmentId("1")
                .setRegistrationOrigin(REGISTRATION_ORIGIN)
                .build();
    }

    private void setupSourceAndTriggerData() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        List<Source> sourcesList = new ArrayList<>();
        sourcesList.add(
                SourceFixture.getValidSourceBuilder()
                        .setId("S1")
                        .setRegistrant(APP_TWO_SOURCES)
                        .setPublisher(APP_TWO_PUBLISHER)
                        .setPublisherType(EventSurfaceType.APP)
                        .build());
        sourcesList.add(
                SourceFixture.getValidSourceBuilder()
                        .setId("S2")
                        .setRegistrant(APP_TWO_SOURCES)
                        .setPublisher(APP_TWO_PUBLISHER)
                        .setPublisherType(EventSurfaceType.APP)
                        .build());
        sourcesList.add(
                SourceFixture.getValidSourceBuilder()
                        .setId("S3")
                        .setRegistrant(APP_ONE_SOURCE)
                        .setPublisher(APP_ONE_PUBLISHER)
                        .setPublisherType(EventSurfaceType.APP)
                        .build());
        for (Source source : sourcesList) {
            ContentValues values = new ContentValues();
            values.put("_id", source.getId());
            values.put("registrant", source.getRegistrant().toString());
            values.put("publisher", source.getPublisher().toString());
            values.put("publisher_type", source.getPublisherType());

            long row = db.insert("msmt_source", null, values);
            assertNotEquals("Source insertion failed", -1, row);
        }
        List<Trigger> triggersList = new ArrayList<>();
        triggersList.add(
                TriggerFixture.getValidTriggerBuilder()
                        .setId("T1")
                        .setRegistrant(APP_TWO_DESTINATION)
                        .build());
        triggersList.add(
                TriggerFixture.getValidTriggerBuilder()
                        .setId("T2")
                        .setRegistrant(APP_TWO_DESTINATION)
                        .build());
        triggersList.add(
                TriggerFixture.getValidTriggerBuilder()
                        .setId("T3")
                        .setRegistrant(APP_ONE_DESTINATION)
                        .build());

        // Add web triggers.
        triggersList.add(
                TriggerFixture.getValidTriggerBuilder()
                        .setId("T4")
                        .setRegistrant(APP_BROWSER)
                        .setAttributionDestination(WEB_ONE_DESTINATION)
                        .build());
        triggersList.add(
                TriggerFixture.getValidTriggerBuilder()
                        .setId("T5")
                        .setRegistrant(APP_BROWSER)
                        .setAttributionDestination(WEB_ONE_DESTINATION_DIFFERENT_SUBDOMAIN)
                        .build());
        triggersList.add(
                TriggerFixture.getValidTriggerBuilder()
                        .setId("T7")
                        .setRegistrant(APP_BROWSER)
                        .setAttributionDestination(WEB_ONE_DESTINATION_DIFFERENT_SUBDOMAIN_2)
                        .build());
        triggersList.add(
                TriggerFixture.getValidTriggerBuilder()
                        .setId("T8")
                        .setRegistrant(APP_BROWSER)
                        .setAttributionDestination(WEB_TWO_DESTINATION)
                        .build());
        triggersList.add(
                TriggerFixture.getValidTriggerBuilder()
                        .setId("T9")
                        .setRegistrant(APP_BROWSER)
                        .setAttributionDestination(WEB_TWO_DESTINATION_WITH_PATH)
                        .build());

        for (Trigger trigger : triggersList) {
            ContentValues values = new ContentValues();
            values.put("_id", trigger.getId());
            values.put("registrant", trigger.getRegistrant().toString());
            values.put("attribution_destination", trigger.getAttributionDestination().toString());
            long row = db.insert("msmt_trigger", null, values);
            Assert.assertNotEquals("Trigger insertion failed", -1, row);
        }
    }

    private Trigger createWebTrigger(Uri attributionDestination) {
        return TriggerFixture.getValidTriggerBuilder()
                .setId("ID" + mValueId++)
                .setAttributionDestination(attributionDestination)
                .setRegistrant(APP_BROWSER)
                .build();
    }

    private Trigger createAppTrigger(Uri registrant, Uri destination) {
        return TriggerFixture.getValidTriggerBuilder()
                .setId("ID" + mValueId++)
                .setAttributionDestination(destination)
                .setRegistrant(registrant)
                .build();
    }

    private void addTriggersToDatabase(List<Trigger> triggersList) {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();

        for (Trigger trigger : triggersList) {
            ContentValues values = new ContentValues();
            values.put("_id", trigger.getId());
            values.put("registrant", trigger.getRegistrant().toString());
            values.put("attribution_destination", trigger.getAttributionDestination().toString());
            long row = db.insert("msmt_trigger", null, values);
            assertNotEquals("Trigger insertion failed", -1, row);
        }
    }

    private void setupSourceDataForPublisherTypeWeb() {
        SQLiteDatabase db = MeasurementDbHelper.getInstance(sContext).safeGetWritableDatabase();
        List<Source> sourcesList = new ArrayList<>();
        sourcesList.add(
                SourceFixture.getValidSourceBuilder()
                        .setId("W1")
                        .setPublisher(WEB_PUBLISHER_ONE)
                        .setPublisherType(EventSurfaceType.WEB)
                        .build());
        sourcesList.add(
                SourceFixture.getValidSourceBuilder()
                        .setId("W21")
                        .setPublisher(WEB_PUBLISHER_TWO)
                        .setPublisherType(EventSurfaceType.WEB)
                        .build());
        sourcesList.add(
                SourceFixture.getValidSourceBuilder()
                        .setId("W22")
                        .setPublisher(WEB_PUBLISHER_TWO)
                        .setPublisherType(EventSurfaceType.WEB)
                        .build());
        sourcesList.add(
                SourceFixture.getValidSourceBuilder()
                        .setId("S3")
                        .setPublisher(WEB_PUBLISHER_THREE)
                        .setPublisherType(EventSurfaceType.WEB)
                        .build());
        for (Source source : sourcesList) {
            ContentValues values = new ContentValues();
            values.put("_id", source.getId());
            values.put("publisher", source.getPublisher().toString());
            values.put("publisher_type", source.getPublisherType());

            long row = db.insert("msmt_source", null, values);
            assertNotEquals("Source insertion failed", -1, row);
        }
    }

    private Source.Builder createSourceForIATest(
            String id,
            long currentTime,
            long priority,
            int eventTimePastDays,
            boolean expiredIAWindow,
            String enrollmentId) {
        return new Source.Builder()
                .setId(id)
                .setPublisher(Uri.parse("android-app://com.example.sample"))
                .setRegistrant(Uri.parse("android-app://com.example.sample"))
                .setEnrollmentId(enrollmentId)
                .setExpiryTime(currentTime + TimeUnit.DAYS.toMillis(30))
                .setInstallAttributionWindow(TimeUnit.DAYS.toMillis(expiredIAWindow ? 0 : 30))
                .setAppDestinations(List.of(INSTALLED_PACKAGE))
                .setEventTime(
                        currentTime
                                - TimeUnit.DAYS.toMillis(
                                        eventTimePastDays == -1 ? 10 : eventTimePastDays))
                .setPriority(priority == -1 ? 100 : priority)
                .setRegistrationOrigin(REGISTRATION_ORIGIN);
    }

    private AggregateReport generateMockAggregateReport(String attributionDestination, int id) {
        return new AggregateReport.Builder()
                .setId(String.valueOf(id))
                .setAttributionDestination(Uri.parse(attributionDestination))
                .build();
    }

    private EventReport generateMockEventReport(String attributionDestination, int id) {
        return new EventReport.Builder()
                .setId(String.valueOf(id))
                .setAttributionDestinations(List.of(Uri.parse(attributionDestination)))
                .build();
    }

    private void assertAggregateReportCount(
            List<String> attributionDestinations,
            int destinationType,
            List<Integer> expectedCounts) {
        IntStream.range(0, attributionDestinations.size())
                .forEach(i -> Assert.assertEquals(expectedCounts.get(i),
                        DatastoreManagerFactory.getDatastoreManager(sContext)
                                .runInTransactionWithResult(measurementDao ->
                                        measurementDao.getNumAggregateReportsPerDestination(
                                                Uri.parse(attributionDestinations.get(i)),
                                                destinationType))
                                .orElseThrow()));
    }

    private void assertEventReportCount(
            List<String> attributionDestinations,
            int destinationType,
            List<Integer> expectedCounts) {
        IntStream.range(0, attributionDestinations.size())
                .forEach(i -> Assert.assertEquals(expectedCounts.get(i),
                        DatastoreManagerFactory.getDatastoreManager(sContext)
                                .runInTransactionWithResult(measurementDao ->
                                        measurementDao.getNumEventReportsPerDestination(
                                                Uri.parse(attributionDestinations.get(i)),
                                                destinationType))
                                .orElseThrow()));
    }

    private List<String> createAppDestinationVariants(int destinationNum) {
        return Arrays.asList(
                "android-app://subdomain.destination-" + destinationNum + ".app/abcd",
                "android-app://subdomain.destination-" + destinationNum + ".app",
                "android-app://destination-" + destinationNum + ".app/abcd",
                "android-app://destination-" + destinationNum + ".app",
                "android-app://destination-" + destinationNum + ".ap");
    }

    private List<String> createWebDestinationVariants(int destinationNum) {
        return Arrays.asList(
                "https://subdomain.destination-" + destinationNum + ".com/abcd",
                "https://subdomain.destination-" + destinationNum + ".com",
                "https://destination-" + destinationNum + ".com/abcd",
                "https://destination-" + destinationNum + ".com",
                "https://destination-" + destinationNum + ".co");
    }

    private boolean getInstallAttributionStatus(String sourceDbId, SQLiteDatabase db) {
        Cursor cursor =
                db.query(
                        SourceContract.TABLE,
                        new String[] {SourceContract.IS_INSTALL_ATTRIBUTED},
                        SourceContract.ID + " = ? ",
                        new String[] {sourceDbId},
                        null,
                        null,
                        null,
                        null);
        assertTrue(cursor.moveToFirst());
        return cursor.getInt(0) == 1;
    }

    private Long getInstallAttributionInstallTime(String sourceDbId, SQLiteDatabase db) {
        Cursor cursor =
                db.query(
                        SourceContract.TABLE,
                        new String[] {SourceContract.INSTALL_TIME},
                        SourceContract.ID + " = ? ",
                        new String[] {sourceDbId},
                        null,
                        null,
                        null,
                        null);
        assertTrue(cursor.moveToFirst());
        if (!cursor.isNull(0)) {
            return cursor.getLong(0);
        }
        return null;
    }

    private void removeSources(List<String> dbIds, SQLiteDatabase db) {
        db.delete(
                SourceContract.TABLE,
                SourceContract.ID + " IN ( ? )",
                new String[] {String.join(",", dbIds)});
    }

    private static void maybeInsertSourceDestinations(
            SQLiteDatabase db, Source source, String sourceId) {
        if (source.getAppDestinations() != null) {
            for (Uri appDestination : source.getAppDestinations()) {
                ContentValues values = new ContentValues();
                values.put(MeasurementTables.SourceDestination.SOURCE_ID, sourceId);
                values.put(
                        MeasurementTables.SourceDestination.DESTINATION_TYPE, EventSurfaceType.APP);
                values.put(
                        MeasurementTables.SourceDestination.DESTINATION, appDestination.toString());
                long row = db.insert(MeasurementTables.SourceDestination.TABLE, null, values);
                assertNotEquals("Source app destination insertion failed", -1, row);
            }
        }
        if (source.getWebDestinations() != null) {
            for (Uri webDestination : source.getWebDestinations()) {
                ContentValues values = new ContentValues();
                values.put(MeasurementTables.SourceDestination.SOURCE_ID, sourceId);
                values.put(
                        MeasurementTables.SourceDestination.DESTINATION_TYPE, EventSurfaceType.WEB);
                values.put(
                        MeasurementTables.SourceDestination.DESTINATION, webDestination.toString());
                long row = db.insert(MeasurementTables.SourceDestination.TABLE, null, values);
                assertNotEquals("Source web destination insertion failed", -1, row);
            }
        }
    }

    /** Create {@link Attribution} object from SQLite datastore. */
    private static Attribution constructAttributionFromCursor(Cursor cursor) {
        Attribution.Builder builder = new Attribution.Builder();
        int index = cursor.getColumnIndex(MeasurementTables.AttributionContract.ID);
        if (index > -1 && !cursor.isNull(index)) {
            builder.setId(cursor.getString(index));
        }
        index = cursor.getColumnIndex(MeasurementTables.AttributionContract.SOURCE_SITE);
        if (index > -1 && !cursor.isNull(index)) {
            builder.setSourceSite(cursor.getString(index));
        }
        index = cursor.getColumnIndex(MeasurementTables.AttributionContract.SOURCE_ORIGIN);
        if (index > -1 && !cursor.isNull(index)) {
            builder.setSourceOrigin(cursor.getString(index));
        }
        index = cursor.getColumnIndex(MeasurementTables.AttributionContract.DESTINATION_SITE);
        if (index > -1 && !cursor.isNull(index)) {
            builder.setDestinationSite(cursor.getString(index));
        }
        index = cursor.getColumnIndex(MeasurementTables.AttributionContract.DESTINATION_ORIGIN);
        if (index > -1 && !cursor.isNull(index)) {
            builder.setDestinationOrigin(cursor.getString(index));
        }
        index = cursor.getColumnIndex(MeasurementTables.AttributionContract.ENROLLMENT_ID);
        if (index > -1 && !cursor.isNull(index)) {
            builder.setEnrollmentId(cursor.getString(index));
        }
        index = cursor.getColumnIndex(MeasurementTables.AttributionContract.TRIGGER_TIME);
        if (index > -1 && !cursor.isNull(index)) {
            builder.setTriggerTime(cursor.getLong(index));
        }
        index = cursor.getColumnIndex(MeasurementTables.AttributionContract.REGISTRANT);
        if (index > -1 && !cursor.isNull(index)) {
            builder.setRegistrant(cursor.getString(index));
        }
        return builder.build();
    }

    private static String getFirstSourceIdFromDatastore() {
        try (Cursor cursor =
                MeasurementDbHelper.getInstance(sContext)
                        .getReadableDatabase()
                        .query(
                                SourceContract.TABLE,
                                new String[] {SourceContract.ID},
                                null,
                                null,
                                null,
                                null,
                                null)) {
            assertTrue(cursor.moveToNext());
            return cursor.getString(cursor.getColumnIndex(SourceContract.ID));
        }
    }

    private static List<Uri> getNullableUriList(List<Uri> uris) {
        return uris == null ? null : uris;
    }
}
