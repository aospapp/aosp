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

package com.android.adservices.service.adselection;

import static android.adservices.adselection.AdSelectionFromOutcomesConfigFixture.SAMPLE_SELLER;
import static android.adservices.common.AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
import static android.adservices.common.AdServicesStatusUtils.STATUS_INVALID_ARGUMENT;

import static com.android.adservices.data.adselection.AdSelectionDatabase.DATABASE_NAME;
import static com.android.adservices.service.PhFlagsFixture.EXTENDED_FLEDGE_AD_SELECTION_FROM_OUTCOMES_OVERALL_TIMEOUT_MS;
import static com.android.adservices.service.PhFlagsFixture.EXTENDED_FLEDGE_AD_SELECTION_SELECTING_OUTCOME_TIMEOUT_MS;
import static com.android.adservices.service.adselection.AdOutcomeSelectorImpl.OUTCOME_SELECTION_JS_RETURNED_UNEXPECTED_RESULT;
import static com.android.adservices.service.adselection.OutcomeSelectionRunner.SELECTED_OUTCOME_MUST_BE_ONE_OF_THE_INPUTS;
import static com.android.adservices.service.adselection.PrebuiltLogicGenerator.AD_OUTCOME_SELECTION_WATERFALL_MEDIATION_TRUNCATION;
import static com.android.adservices.service.adselection.PrebuiltLogicGenerator.AD_SELECTION_FROM_OUTCOMES_USE_CASE;
import static com.android.adservices.service.adselection.PrebuiltLogicGenerator.AD_SELECTION_PREBUILT_SCHEMA;
import static com.android.adservices.service.stats.AdSelectionExecutionLoggerTest.DB_AD_SELECTION_FILE_SIZE;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.adservices.adselection.AdSelectionCallback;
import android.adservices.adselection.AdSelectionFromOutcomesConfig;
import android.adservices.adselection.AdSelectionFromOutcomesConfigFixture;
import android.adservices.adselection.AdSelectionFromOutcomesInput;
import android.adservices.adselection.AdSelectionResponse;
import android.adservices.adselection.CustomAudienceSignalsFixture;
import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.CallerMetadata;
import android.adservices.common.CallingAppUidSupplierProcessImpl;
import android.adservices.common.CommonFixture;
import android.adservices.common.FledgeErrorResponse;
import android.adservices.http.MockWebServerRule;
import android.content.Context;
import android.net.Uri;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.MockWebServerRuleFactory;
import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.data.DbTestUtil;
import com.android.adservices.data.adselection.AdSelectionDatabase;
import com.android.adservices.data.adselection.AdSelectionEntryDao;
import com.android.adservices.data.adselection.AppInstallDao;
import com.android.adservices.data.adselection.CustomAudienceSignals;
import com.android.adservices.data.adselection.DBAdSelection;
import com.android.adservices.data.adselection.FrequencyCapDao;
import com.android.adservices.data.adselection.SharedStorageDatabase;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.CustomAudienceDatabase;
import com.android.adservices.data.customaudience.DBCustomAudience;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.AdSelectionServiceFilter;
import com.android.adservices.service.common.FledgeAuthorizationFilter;
import com.android.adservices.service.common.Throttler;
import com.android.adservices.service.common.cache.CacheProviderFactory;
import com.android.adservices.service.common.httpclient.AdServicesHttpsClient;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.devapi.DevContext;
import com.android.adservices.service.devapi.DevContextFilter;
import com.android.adservices.service.js.JSScriptEngine;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.adservices.service.stats.AdServicesStatsLog;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.mockwebserver.Dispatcher;
import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;
import com.google.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.Spy;
import org.mockito.quality.Strictness;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class AdSelectionFromOutcomesE2ETest {
    private static final int CALLER_UID = Process.myUid();
    private static final String SELECTION_PICK_HIGHEST_LOGIC_JS_PATH = "/selectionPickHighestJS/";
    private static final String SELECTION_PICK_NONE_LOGIC_JS_PATH = "/selectionPickNoneJS/";
    private static final String SELECTION_WATERFALL_LOGIC_JS_PATH = "/selectionWaterfallJS/";
    private static final String SELECTION_FAULTY_LOGIC_JS_PATH = "/selectionFaultyJS/";
    private static final String SELECTION_PICK_HIGHEST_LOGIC_JS =
            "function selectOutcome(outcomes, selection_signals) {\n"
                    + "    let max_bid = 0;\n"
                    + "    let winner_outcome = null;\n"
                    + "    for (let outcome of outcomes) {\n"
                    + "        if (outcome.bid > max_bid) {\n"
                    + "            max_bid = outcome.bid;\n"
                    + "            winner_outcome = outcome;\n"
                    + "        }\n"
                    + "    }\n"
                    + "    return {'status': 0, 'result': winner_outcome};\n"
                    + "}";
    private static final String SELECTION_PICK_NONE_LOGIC_JS =
            "function selectOutcome(outcomes, selection_signals) {\n"
                    + "    return {'status': 0, 'result': null};\n"
                    + "}";
    private static final String SELECTION_WATERFALL_LOGIC_JS =
            "function selectOutcome(outcomes, selection_signals) {\n"
                    + "    if (outcomes.length != 1 || selection_signals.bid_floor =="
                    + " undefined) return null;\n"
                    + "\n"
                    + "    const outcome_1p = outcomes[0];\n"
                    + "    return {'status': 0, 'result': (outcome_1p.bid >"
                    + " selection_signals.bid_floor) ? outcome_1p : null};\n"
                    + "}";
    private static final String SELECTION_FAULTY_LOGIC_JS =
            "function selectOutcome(outcomes, selection_signals) {\n"
                    + "    return {'status': 0, 'result': {\"id\": outcomes[0].id + 1, \"bid\": "
                    + "outcomes[0].bid}};\n"
                    + "}";
    private static final String BID_FLOOR_SELECTION_SIGNAL_TEMPLATE = "{\"bid_floor\":%s}";

    private static final AdTechIdentifier SELLER_INCONSISTENT_WITH_SELECTION_URI =
            AdTechIdentifier.fromString("inconsistent.developer.android.com");
    private static final long BINDER_ELAPSED_TIME_MS = 100L;
    private static final String CALLER_PACKAGE_NAME = CommonFixture.TEST_PACKAGE_NAME;
    private static final long AD_SELECTION_ID_1 = 12345L;
    private static final long AD_SELECTION_ID_2 = 123456L;
    private static final long AD_SELECTION_ID_3 = 1234567L;

    private final AdServicesLogger mAdServicesLoggerMock =
            ExtendedMockito.mock(AdServicesLoggerImpl.class);
    private final Flags mFlags = new AdSelectionFromOutcomesE2ETest.TestFlags();

    @Rule public MockWebServerRule mMockWebServerRule = MockWebServerRuleFactory.createForHttps();
    // Mocking DevContextFilter to test behavior with and without override api authorization
    @Mock DevContextFilter mDevContextFilter;
    @Mock CallerMetadata mMockCallerMetadata;

    @Spy private Context mContext = ApplicationProvider.getApplicationContext();
    @Mock private File mMockDBAdSelectionFile;
    @Mock private ConsentManager mConsentManagerMock;

    FledgeAuthorizationFilter mFledgeAuthorizationFilter =
            new FledgeAuthorizationFilter(
                    mContext.getPackageManager(),
                    new EnrollmentDao(mContext, DbTestUtil.getSharedDbHelperForTest(), mFlags),
                    mAdServicesLoggerMock);

    private MockitoSession mStaticMockSession = null;
    private ExecutorService mLightweightExecutorService;
    private ExecutorService mBackgroundExecutorService;
    private ScheduledThreadPoolExecutor mScheduledExecutor;
    private CustomAudienceDao mCustomAudienceDao;
    private AppInstallDao mAppInstallDao;
    @Spy private AdSelectionEntryDao mAdSelectionEntryDaoSpy;
    private AdServicesHttpsClient mAdServicesHttpsClient;
    private AdSelectionServiceImpl mAdSelectionService;
    private Dispatcher mDispatcher;
    private AdFilteringFeatureFactory mAdFilteringFeatureFactory;

    @Mock private AdSelectionServiceFilter mAdSelectionServiceFilter;

    @Before
    public void setUp() throws Exception {
        // Every test in this class requires that the JS Sandbox be available. The JS Sandbox
        // availability depends on an external component (the system webview) being higher than a
        // certain minimum version. Marking that as an assumption that the test is making.
        Assume.assumeTrue(JSScriptEngine.AvailabilityChecker.isJSSandboxAvailable());
        mAdSelectionEntryDaoSpy =
                Room.inMemoryDatabaseBuilder(mContext, AdSelectionDatabase.class)
                        .build()
                        .adSelectionEntryDao();

        // Test applications don't have the required permissions to read config P/H flags, and
        // injecting mocked flags everywhere is annoying and non-trivial for static methods
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .initMocks(this)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        // Initialize dependencies for the AdSelectionService
        mLightweightExecutorService = AdServicesExecutors.getLightWeightExecutor();
        mBackgroundExecutorService = AdServicesExecutors.getBackgroundExecutor();
        mScheduledExecutor = AdServicesExecutors.getScheduler();
        mCustomAudienceDao =
                Room.inMemoryDatabaseBuilder(mContext, CustomAudienceDatabase.class)
                        .addTypeConverter(new DBCustomAudience.Converters(true))
                        .build()
                        .customAudienceDao();
        SharedStorageDatabase sharedDb =
                Room.inMemoryDatabaseBuilder(mContext, SharedStorageDatabase.class).build();
        mAppInstallDao = sharedDb.appInstallDao();
        FrequencyCapDao frequencyCapDao = sharedDb.frequencyCapDao();
        mAdFilteringFeatureFactory =
                new AdFilteringFeatureFactory(mAppInstallDao, frequencyCapDao, mFlags);
        mAdServicesHttpsClient =
                new AdServicesHttpsClient(
                        AdServicesExecutors.getBlockingExecutor(),
                        CacheProviderFactory.createNoOpCache());

        when(mDevContextFilter.createDevContext())
                .thenReturn(DevContext.createForDevOptionsDisabled());
        when(mMockCallerMetadata.getBinderElapsedTimestamp())
                .thenReturn(SystemClock.elapsedRealtime() - BINDER_ELAPSED_TIME_MS);
        // Create an instance of AdSelection Service with real dependencies
        mAdSelectionService =
                new AdSelectionServiceImpl(
                        mAdSelectionEntryDaoSpy,
                        mAppInstallDao,
                        mCustomAudienceDao,
                        frequencyCapDao,
                        mAdServicesHttpsClient,
                        mDevContextFilter,
                        mLightweightExecutorService,
                        mBackgroundExecutorService,
                        mScheduledExecutor,
                        mContext,
                        mAdServicesLoggerMock,
                        mFlags,
                        CallingAppUidSupplierProcessImpl.create(),
                        mFledgeAuthorizationFilter,
                        mAdSelectionServiceFilter,
                        mAdFilteringFeatureFactory,
                        mConsentManagerMock);

        // Create a dispatcher that helps map a request -> response in mockWebServer
        mDispatcher =
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        switch (request.getPath()) {
                            case SELECTION_PICK_HIGHEST_LOGIC_JS_PATH:
                                return new MockResponse().setBody(SELECTION_PICK_HIGHEST_LOGIC_JS);
                            case SELECTION_PICK_NONE_LOGIC_JS_PATH:
                                return new MockResponse().setBody(SELECTION_PICK_NONE_LOGIC_JS);
                            case SELECTION_WATERFALL_LOGIC_JS_PATH:
                                return new MockResponse().setBody(SELECTION_WATERFALL_LOGIC_JS);
                            case SELECTION_FAULTY_LOGIC_JS_PATH:
                                return new MockResponse().setBody(SELECTION_FAULTY_LOGIC_JS);
                            default:
                                return new MockResponse().setResponseCode(404);
                        }
                    }
                };

        when(mContext.getDatabasePath(DATABASE_NAME)).thenReturn(mMockDBAdSelectionFile);
        when(mMockDBAdSelectionFile.length()).thenReturn(DB_AD_SELECTION_FILE_SIZE);
        doNothing()
                .when(mAdSelectionServiceFilter)
                .filterRequest(
                        SAMPLE_SELLER,
                        CALLER_PACKAGE_NAME,
                        false,
                        true,
                        CALLER_UID,
                        AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__SELECT_ADS,
                        Throttler.ApiKey.FLEDGE_API_SELECT_ADS);
    }

    @After
    public void tearDown() {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    @Test
    public void testSelectAdsFromOutcomesPickHighestSuccess() throws Exception {
        doReturn(new AdSelectionFromOutcomesE2ETest.TestFlags()).when(FlagsFactory::getFlags);
        MockWebServer server = mMockWebServerRule.startMockWebServer(mDispatcher);
        final String selectionLogicPath = SELECTION_PICK_HIGHEST_LOGIC_JS_PATH;

        Map<Long, Double> adSelectionIdToBidMap =
                Map.of(
                        AD_SELECTION_ID_1, 10.0,
                        AD_SELECTION_ID_2, 20.0,
                        AD_SELECTION_ID_3, 30.0);
        persistAdSelectionEntryDaoResults(adSelectionIdToBidMap);

        AdSelectionFromOutcomesConfig config =
                AdSelectionFromOutcomesConfigFixture.anAdSelectionFromOutcomesConfig(
                        List.of(AD_SELECTION_ID_1, AD_SELECTION_ID_2, AD_SELECTION_ID_3),
                        AdSelectionSignals.EMPTY,
                        mMockWebServerRule.uriForPath(selectionLogicPath));

        AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback resultsCallback =
                invokeSelectAdsFromOutcomes(mAdSelectionService, config, CALLER_PACKAGE_NAME);

        assertThat(resultsCallback.mIsSuccess).isTrue();
        assertThat(resultsCallback.mAdSelectionResponse).isNotNull();
        assertEquals(resultsCallback.mAdSelectionResponse.getAdSelectionId(), AD_SELECTION_ID_3);
        mMockWebServerRule.verifyMockServerRequests(
                server, 1, Collections.singletonList(selectionLogicPath), String::equals);
    }

    @Test
    public void testSelectAdsFromOutcomesWaterfallMediationAdBidHigherThanBidFloorSuccess()
            throws Exception {
        doReturn(new AdSelectionFromOutcomesE2ETest.TestFlags()).when(FlagsFactory::getFlags);
        MockWebServer server = mMockWebServerRule.startMockWebServer(mDispatcher);
        final String selectionLogicPath = SELECTION_WATERFALL_LOGIC_JS_PATH;

        Map<Long, Double> adSelectionIdToBidMap = Map.of(AD_SELECTION_ID_1, 10.0);
        persistAdSelectionEntryDaoResults(adSelectionIdToBidMap);

        AdSelectionFromOutcomesConfig config =
                AdSelectionFromOutcomesConfigFixture.anAdSelectionFromOutcomesConfig(
                        Collections.singletonList(AD_SELECTION_ID_1),
                        AdSelectionSignals.fromString(
                                String.format(BID_FLOOR_SELECTION_SIGNAL_TEMPLATE, 9)),
                        mMockWebServerRule.uriForPath(selectionLogicPath));

        AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback resultsCallback =
                invokeSelectAdsFromOutcomes(mAdSelectionService, config, CALLER_PACKAGE_NAME);

        assertThat(resultsCallback.mIsSuccess).isTrue();
        assertThat(resultsCallback.mAdSelectionResponse).isNotNull();
        assertEquals(resultsCallback.mAdSelectionResponse.getAdSelectionId(), AD_SELECTION_ID_1);
        mMockWebServerRule.verifyMockServerRequests(
                server, 1, Collections.singletonList(selectionLogicPath), String::equals);
    }

    @Test
    public void testSelectAdsFromOutcomesWaterfallMediationPrebuiltUriSuccess() throws Exception {
        doReturn(new AdSelectionFromOutcomesE2ETest.TestFlags()).when(FlagsFactory::getFlags);
        MockWebServer server = mMockWebServerRule.startMockWebServer(mDispatcher);

        Map<Long, Double> adSelectionIdToBidMap = Map.of(AD_SELECTION_ID_1, 10.0);
        persistAdSelectionEntryDaoResults(adSelectionIdToBidMap);

        String paramKey = "bidFloor";
        String paramValue = "bid_floor";
        Uri prebuiltUri =
                Uri.parse(
                        String.format(
                                "%s://%s/%s/?%s=%s",
                                AD_SELECTION_PREBUILT_SCHEMA,
                                AD_SELECTION_FROM_OUTCOMES_USE_CASE,
                                AD_OUTCOME_SELECTION_WATERFALL_MEDIATION_TRUNCATION,
                                paramKey,
                                paramValue));

        AdSelectionFromOutcomesConfig config =
                AdSelectionFromOutcomesConfigFixture.anAdSelectionFromOutcomesConfig(
                        Collections.singletonList(AD_SELECTION_ID_1),
                        AdSelectionSignals.fromString(
                                String.format(BID_FLOOR_SELECTION_SIGNAL_TEMPLATE, 9)),
                        prebuiltUri);

        AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback resultsCallback =
                invokeSelectAdsFromOutcomes(mAdSelectionService, config, CALLER_PACKAGE_NAME);

        assertThat(resultsCallback.mIsSuccess).isTrue();
        assertThat(resultsCallback.mAdSelectionResponse).isNotNull();
        assertEquals(resultsCallback.mAdSelectionResponse.getAdSelectionId(), AD_SELECTION_ID_1);
        mMockWebServerRule.verifyMockServerRequests(
                server, 0, Collections.emptyList(), String::equals);
    }

    @Test
    public void testSelectAdsFromOutcomesWaterfallMediationAdBidLowerThanBidFloorSuccess()
            throws Exception {
        doReturn(new AdSelectionFromOutcomesE2ETest.TestFlags()).when(FlagsFactory::getFlags);
        MockWebServer server = mMockWebServerRule.startMockWebServer(mDispatcher);
        final String selectionLogicPath = SELECTION_WATERFALL_LOGIC_JS_PATH;

        Map<Long, Double> adSelectionIdToBidMap = Map.of(AD_SELECTION_ID_1, 10.0);
        persistAdSelectionEntryDaoResults(adSelectionIdToBidMap);

        AdSelectionFromOutcomesConfig config =
                AdSelectionFromOutcomesConfigFixture.anAdSelectionFromOutcomesConfig(
                        Collections.singletonList(AD_SELECTION_ID_1),
                        AdSelectionSignals.fromString(
                                String.format(BID_FLOOR_SELECTION_SIGNAL_TEMPLATE, 11)),
                        mMockWebServerRule.uriForPath(selectionLogicPath));

        AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback resultsCallback =
                invokeSelectAdsFromOutcomes(mAdSelectionService, config, CALLER_PACKAGE_NAME);

        assertThat(resultsCallback.mIsSuccess).isTrue();
        assertThat(resultsCallback.mAdSelectionResponse).isNull();
        mMockWebServerRule.verifyMockServerRequests(
                server, 1, Collections.singletonList(selectionLogicPath), String::equals);
    }

    @Test
    public void testSelectAdsFromOutcomesReturnsNullSuccess() throws Exception {
        doReturn(new AdSelectionFromOutcomesE2ETest.TestFlags()).when(FlagsFactory::getFlags);
        MockWebServer server = mMockWebServerRule.startMockWebServer(mDispatcher);
        final String selectionLogicPath = SELECTION_PICK_NONE_LOGIC_JS_PATH;

        Map<Long, Double> adSelectionIdToBidMap =
                Map.of(
                        AD_SELECTION_ID_1, 10.0,
                        AD_SELECTION_ID_2, 20.0,
                        AD_SELECTION_ID_3, 30.0);
        persistAdSelectionEntryDaoResults(adSelectionIdToBidMap);

        AdSelectionFromOutcomesConfig config =
                AdSelectionFromOutcomesConfigFixture.anAdSelectionFromOutcomesConfig(
                        List.of(AD_SELECTION_ID_1, AD_SELECTION_ID_2, AD_SELECTION_ID_3),
                        AdSelectionSignals.EMPTY,
                        mMockWebServerRule.uriForPath(selectionLogicPath));

        AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback resultsCallback =
                invokeSelectAdsFromOutcomes(mAdSelectionService, config, CALLER_PACKAGE_NAME);

        assertThat(resultsCallback.mIsSuccess).isTrue();
        assertThat(resultsCallback.mAdSelectionResponse).isNull();
        mMockWebServerRule.verifyMockServerRequests(
                server, 1, Collections.singletonList(selectionLogicPath), String::equals);
    }

    @Test
    public void testSelectAdsFromOutcomesInvalidAdSelectionConfigFromOutcomes() throws Exception {
        doReturn(new AdSelectionFromOutcomesE2ETest.TestFlags()).when(FlagsFactory::getFlags);
        MockWebServer server = mMockWebServerRule.startMockWebServer(mDispatcher);
        final String selectionLogicPath = "/unreachableLogicJS/";

        long adSelectionId1 = 12345L;
        long adSelectionId2 = 123456L;
        long adSelectionId3 = 1234567L;

        Map<Long, Double> adSelectionIdToBidMap =
                Map.of(
                        adSelectionId1, 10.0,
                        adSelectionId2, 20.0,
                        adSelectionId3, 30.0);
        persistAdSelectionEntryDaoResults(adSelectionIdToBidMap);

        AdSelectionFromOutcomesConfig config =
                AdSelectionFromOutcomesConfigFixture.anAdSelectionFromOutcomesConfig(
                        SELLER_INCONSISTENT_WITH_SELECTION_URI,
                        List.of(adSelectionId1, adSelectionId2, adSelectionId3),
                        AdSelectionSignals.EMPTY,
                        mMockWebServerRule.uriForPath(selectionLogicPath));

        AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback resultsCallback =
                invokeSelectAdsFromOutcomes(mAdSelectionService, config, CALLER_PACKAGE_NAME);

        assertThat(resultsCallback.mIsSuccess).isFalse();
        assertThat(resultsCallback.mFledgeErrorResponse).isNotNull();
        assertThat(resultsCallback.mFledgeErrorResponse.getStatusCode())
                .isEqualTo(STATUS_INVALID_ARGUMENT);
        mMockWebServerRule.verifyMockServerRequests(
                server, 0, Collections.emptyList(), String::equals);
    }

    @Test
    public void testSelectAdsFromOutcomesJsReturnsFaultyAdSelectionIdFailure() throws Exception {
        doReturn(new AdSelectionFromOutcomesE2ETest.TestFlags()).when(FlagsFactory::getFlags);
        MockWebServer server = mMockWebServerRule.startMockWebServer(mDispatcher);
        final String selectionLogicPath = SELECTION_FAULTY_LOGIC_JS_PATH;

        Map<Long, Double> adSelectionIdToBidMap =
                Map.of(
                        AD_SELECTION_ID_1, 10.0,
                        AD_SELECTION_ID_2, 20.0,
                        AD_SELECTION_ID_3, 30.0);
        persistAdSelectionEntryDaoResults(adSelectionIdToBidMap);

        AdSelectionFromOutcomesConfig config =
                AdSelectionFromOutcomesConfigFixture.anAdSelectionFromOutcomesConfig(
                        List.of(AD_SELECTION_ID_1, AD_SELECTION_ID_2, AD_SELECTION_ID_3),
                        AdSelectionSignals.EMPTY,
                        mMockWebServerRule.uriForPath(selectionLogicPath));

        AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback resultsCallback =
                invokeSelectAdsFromOutcomes(mAdSelectionService, config, CALLER_PACKAGE_NAME);

        assertThat(resultsCallback.mIsSuccess).isFalse();
        assertThat(resultsCallback.mFledgeErrorResponse).isNotNull();
        assertThat(resultsCallback.mFledgeErrorResponse.getStatusCode())
                .isEqualTo(STATUS_INTERNAL_ERROR);
        assertThat(resultsCallback.mFledgeErrorResponse.getErrorMessage())
                .contains(SELECTED_OUTCOME_MUST_BE_ONE_OF_THE_INPUTS);
        mMockWebServerRule.verifyMockServerRequests(
                server, 1, Collections.singletonList(selectionLogicPath), String::equals);
    }

    @Test
    public void testSelectAdsFromOutcomesWaterfallMalformedPrebuiltUriFailed() throws Exception {
        doReturn(new AdSelectionFromOutcomesE2ETest.TestFlags()).when(FlagsFactory::getFlags);
        MockWebServer server = mMockWebServerRule.startMockWebServer(mDispatcher);

        Map<Long, Double> adSelectionIdToBidMap = Map.of(AD_SELECTION_ID_1, 10.0);
        persistAdSelectionEntryDaoResults(adSelectionIdToBidMap);

        String unknownUseCase = "unknown-usecase";
        Uri prebuiltUri =
                Uri.parse(String.format("%s://%s/", AD_SELECTION_PREBUILT_SCHEMA, unknownUseCase));

        AdSelectionFromOutcomesConfig config =
                AdSelectionFromOutcomesConfigFixture.anAdSelectionFromOutcomesConfig(
                        Collections.singletonList(AD_SELECTION_ID_1),
                        AdSelectionSignals.EMPTY,
                        prebuiltUri);
        AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback resultsCallback =
                invokeSelectAdsFromOutcomes(mAdSelectionService, config, CALLER_PACKAGE_NAME);

        assertThat(resultsCallback.mIsSuccess).isFalse();
        assertThat(resultsCallback.mFledgeErrorResponse).isNotNull();
        assertThat(resultsCallback.mFledgeErrorResponse.getStatusCode())
                .isEqualTo(STATUS_INTERNAL_ERROR);
        assertThat(resultsCallback.mFledgeErrorResponse.getErrorMessage())
                .contains(OUTCOME_SELECTION_JS_RETURNED_UNEXPECTED_RESULT);
        mMockWebServerRule.verifyMockServerRequests(
                server, 0, Collections.emptyList(), String::equals);
    }

    private AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback
            invokeSelectAdsFromOutcomes(
                    AdSelectionServiceImpl adSelectionService,
                    AdSelectionFromOutcomesConfig adSelectionFromOutcomesConfig,
                    String callerPackageName)
                    throws InterruptedException, RemoteException {

        CountDownLatch countdownLatch = new CountDownLatch(1);
        AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback adSelectionTestCallback =
                new AdSelectionFromOutcomesE2ETest.AdSelectionFromOutcomesTestCallback(
                        countdownLatch);

        AdSelectionFromOutcomesInput input =
                new AdSelectionFromOutcomesInput.Builder()
                        .setAdSelectionFromOutcomesConfig(adSelectionFromOutcomesConfig)
                        .setCallerPackageName(callerPackageName)
                        .build();

        adSelectionService.selectAdsFromOutcomes(
                input, mMockCallerMetadata, adSelectionTestCallback);
        adSelectionTestCallback.mCountDownLatch.await();
        return adSelectionTestCallback;
    }

    private void persistAdSelectionEntryDaoResults(Map<Long, Double> adSelectionIdToBidMap) {
        persistAdSelectionEntryDaoResults(adSelectionIdToBidMap, CALLER_PACKAGE_NAME);
    }

    private void persistAdSelectionEntryDaoResults(
            Map<Long, Double> adSelectionIdToBidMap, String callerPackageName) {
        final Uri biddingLogicUri1 = Uri.parse("https://www.domain.com/logic/1");
        final Uri renderUri = Uri.parse("https://www.domain.com/advert/");
        final Instant activationTime = Instant.now();
        final String contextualSignals = "contextual_signals";
        final CustomAudienceSignals customAudienceSignals =
                CustomAudienceSignalsFixture.aCustomAudienceSignals();

        for (Map.Entry<Long, Double> entry : adSelectionIdToBidMap.entrySet()) {
            final DBAdSelection dbAdSelectionEntry =
                    new DBAdSelection.Builder()
                            .setAdSelectionId(entry.getKey())
                            .setCustomAudienceSignals(customAudienceSignals)
                            .setContextualSignals(contextualSignals)
                            .setBiddingLogicUri(biddingLogicUri1)
                            .setWinningAdRenderUri(renderUri)
                            .setWinningAdBid(entry.getValue())
                            .setCreationTimestamp(activationTime)
                            .setCallerPackageName(callerPackageName)
                            .build();
            mAdSelectionEntryDaoSpy.persistAdSelection(dbAdSelectionEntry);
        }
    }

    static class AdSelectionFromOutcomesTestCallback extends AdSelectionCallback.Stub {

        final CountDownLatch mCountDownLatch;
        boolean mIsSuccess = false;
        AdSelectionResponse mAdSelectionResponse;
        FledgeErrorResponse mFledgeErrorResponse;

        AdSelectionFromOutcomesTestCallback(CountDownLatch countDownLatch) {
            mCountDownLatch = countDownLatch;
            mAdSelectionResponse = null;
            mFledgeErrorResponse = null;
        }

        @Override
        public void onSuccess(AdSelectionResponse adSelectionResponse) throws RemoteException {
            mIsSuccess = true;
            mAdSelectionResponse = adSelectionResponse;
            mCountDownLatch.countDown();
        }

        @Override
        public void onFailure(FledgeErrorResponse fledgeErrorResponse) throws RemoteException {
            mIsSuccess = false;
            mFledgeErrorResponse = fledgeErrorResponse;
            mCountDownLatch.countDown();
        }
    }

    private static class TestFlags implements Flags {
        @Override
        public boolean getEnforceIsolateMaxHeapSize() {
            return false;
        }

        @Override
        public boolean getEnforceForegroundStatusForFledgeRunAdSelection() {
            return true;
        }

        @Override
        public boolean getEnforceForegroundStatusForFledgeReportImpression() {
            return true;
        }

        @Override
        public boolean getEnforceForegroundStatusForFledgeOverrides() {
            return true;
        }

        @Override
        public boolean getDisableFledgeEnrollmentCheck() {
            return true;
        }

        @Override
        public long getAdSelectionSelectingOutcomeTimeoutMs() {
            return EXTENDED_FLEDGE_AD_SELECTION_SELECTING_OUTCOME_TIMEOUT_MS;
        }

        @Override
        public long getAdSelectionFromOutcomesOverallTimeoutMs() {
            return EXTENDED_FLEDGE_AD_SELECTION_FROM_OUTCOMES_OVERALL_TIMEOUT_MS;
        }

        @Override
        public float getSdkRequestPermitsPerSecond() {
            // Unlimited rate for unit tests to avoid flake in tests due to rate
            // limiting
            return -1;
        }

        @Override
        public boolean getFledgeAdSelectionFilteringEnabled() {
            return false;
        }

        @Override
        public boolean getFledgeAdSelectionPrebuiltUriEnabled() {
            return true;
        }
    }
}
