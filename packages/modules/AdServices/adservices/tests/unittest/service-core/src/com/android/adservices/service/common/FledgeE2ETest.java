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

import static com.android.adservices.data.adselection.AdSelectionDatabase.DATABASE_NAME;
import static com.android.adservices.service.adselection.ImpressionReporter.CALLER_PACKAGE_NAME_MISMATCH;
import static com.android.adservices.service.common.Throttler.ApiKey.FLEDGE_API_JOIN_CUSTOM_AUDIENCE;
import static com.android.adservices.service.common.Throttler.ApiKey.FLEDGE_API_LEAVE_CUSTOM_AUDIENCE;
import static com.android.adservices.service.common.Throttler.ApiKey.FLEDGE_API_REPORT_IMPRESSIONS;
import static com.android.adservices.service.common.Throttler.ApiKey.FLEDGE_API_SELECT_ADS;
import static com.android.adservices.service.stats.AdSelectionExecutionLoggerTest.DB_AD_SELECTION_FILE_SIZE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__REPORT_INTERACTION;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import android.adservices.adselection.AdSelectionCallback;
import android.adservices.adselection.AdSelectionConfig;
import android.adservices.adselection.AdSelectionConfigFixture;
import android.adservices.adselection.AdSelectionInput;
import android.adservices.adselection.AdSelectionOverrideCallback;
import android.adservices.adselection.AdSelectionResponse;
import android.adservices.adselection.BuyersDecisionLogic;
import android.adservices.adselection.ContextualAds;
import android.adservices.adselection.ContextualAdsFixture;
import android.adservices.adselection.DecisionLogic;
import android.adservices.adselection.ReportImpressionCallback;
import android.adservices.adselection.ReportImpressionInput;
import android.adservices.adselection.ReportInteractionCallback;
import android.adservices.adselection.ReportInteractionInput;
import android.adservices.adselection.ReportInteractionRequest;
import android.adservices.adselection.SetAppInstallAdvertisersCallback;
import android.adservices.adselection.SetAppInstallAdvertisersInput;
import android.adservices.adselection.UpdateAdCounterHistogramInput;
import android.adservices.common.AdData;
import android.adservices.common.AdDataFixture;
import android.adservices.common.AdFilters;
import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.AppInstallFilters;
import android.adservices.common.CallerMetadata;
import android.adservices.common.CallingAppUidSupplierProcessImpl;
import android.adservices.common.CommonFixture;
import android.adservices.common.FledgeErrorResponse;
import android.adservices.common.FrequencyCapFilters;
import android.adservices.common.KeyedFrequencyCap;
import android.adservices.common.KeyedFrequencyCapFixture;
import android.adservices.customaudience.CustomAudience;
import android.adservices.customaudience.CustomAudienceFixture;
import android.adservices.customaudience.CustomAudienceOverrideCallback;
import android.adservices.customaudience.ICustomAudienceCallback;
import android.adservices.customaudience.TrustedBiddingData;
import android.adservices.customaudience.TrustedBiddingDataFixture;
import android.adservices.http.MockWebServerRule;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.MockWebServerRuleFactory;
import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.data.adselection.AdSelectionDatabase;
import com.android.adservices.data.adselection.AdSelectionEntryDao;
import com.android.adservices.data.adselection.AppInstallDao;
import com.android.adservices.data.adselection.FrequencyCapDao;
import com.android.adservices.data.adselection.SharedStorageDatabase;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.CustomAudienceDatabase;
import com.android.adservices.data.customaudience.DBCustomAudience;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.adselection.AdFilteringFeatureFactory;
import com.android.adservices.service.adselection.AdSelectionServiceImpl;
import com.android.adservices.service.adselection.InteractionReporter;
import com.android.adservices.service.adselection.JsVersionHelper;
import com.android.adservices.service.adselection.JsVersionRegister;
import com.android.adservices.service.adselection.UpdateAdCounterHistogramWorkerTest;
import com.android.adservices.service.common.cache.CacheProviderFactory;
import com.android.adservices.service.common.httpclient.AdServicesHttpsClient;
import com.android.adservices.service.consent.AdServicesApiConsent;
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.customaudience.BackgroundFetchJobService;
import com.android.adservices.service.customaudience.CustomAudienceImpl;
import com.android.adservices.service.customaudience.CustomAudienceQuantityChecker;
import com.android.adservices.service.customaudience.CustomAudienceServiceImpl;
import com.android.adservices.service.customaudience.CustomAudienceValidator;
import com.android.adservices.service.devapi.DevContext;
import com.android.adservices.service.devapi.DevContextFilter;
import com.android.adservices.service.exception.FilterException;
import com.android.adservices.service.js.JSScriptEngine;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
public class FledgeE2ETest {
    public static final String CUSTOM_AUDIENCE_SEQ_1 = "/ca1";
    public static final String CUSTOM_AUDIENCE_SEQ_2 = "/ca2";
    public static final AppInstallFilters CURRENT_APP_FILTER =
            new AppInstallFilters.Builder()
                    .setPackageNames(new HashSet<>(Arrays.asList(CommonFixture.TEST_PACKAGE_NAME)))
                    .build();
    public static final FrequencyCapFilters CLICK_ONCE_PER_DAY_KEY1 =
            new FrequencyCapFilters.Builder()
                    .setKeyedFrequencyCapsForClickEvents(
                            Collections.singleton(
                                    new KeyedFrequencyCap.Builder()
                                            .setInterval(Duration.ofDays(1))
                                            .setAdCounterKey(KeyedFrequencyCapFixture.KEY1)
                                            .setMaxCount(0)
                                            .build()))
                    .build();
    @Spy private static final Context CONTEXT = ApplicationProvider.getApplicationContext();
    private static final Uri BUYER_DOMAIN_1 =
            CommonFixture.getUri(AdSelectionConfigFixture.BUYER_1, "");
    private static final Uri BUYER_DOMAIN_2 =
            CommonFixture.getUri(AdSelectionConfigFixture.BUYER_2, "");
    private static final String AD_URI_PREFIX = "/adverts/123";
    private static final String BUYER_BIDDING_LOGIC_URI_PATH = "/buyer/bidding/logic/";
    private static final String BUYER_TRUSTED_SIGNAL_URI_PATH = "/kv/buyer/signals/";
    private static final String SELLER_DECISION_LOGIC_URI_PATH = "/ssp/decision/logic/";
    private static final String SELLER_TRUSTED_SIGNAL_URI_PATH = "/kv/seller/signals/";
    private static final String SELLER_TRUSTED_SIGNAL_PARAMS = "?renderuris=";
    private static final String SELLER_REPORTING_PATH = "/reporting/seller";
    private static final String BUYER_REPORTING_PATH = "/reporting/buyer";
    private static final AdSelectionSignals TRUSTED_BIDDING_SIGNALS =
            AdSelectionSignals.fromString(
                    "{\n"
                            + "\t\"example\": \"example\",\n"
                            + "\t\"valid\": \"Also valid\",\n"
                            + "\t\"list\": \"list\",\n"
                            + "\t\"of\": \"of\",\n"
                            + "\t\"keys\": \"trusted bidding signal Values\"\n"
                            + "}");
    private static final AdSelectionSignals TRUSTED_SCORING_SIGNALS =
            AdSelectionSignals.fromString(
                    "{\n"
                            + "\t\"render_uri_1\": \"signals_for_1\",\n"
                            + "\t\"render_uri_2\": \"signals_for_2\"\n"
                            + "}");
    private static final BuyersDecisionLogic BUYERS_DECISION_LOGIC =
            new BuyersDecisionLogic(
                    ImmutableMap.of(
                            CommonFixture.VALID_BUYER_1, new DecisionLogic("reportWin()"),
                            CommonFixture.VALID_BUYER_2, new DecisionLogic("reportWin()")));

    // Interaction reporting contestants
    private static final String CLICK_INTERACTION = "click";

    private static final String CLICK_SELLER_PATH = "/click/seller";
    private static final String HOVER_SELLER_PATH = "/hover/seller";

    private static final String CLICK_BUYER_PATH = "/click/buyer";
    private static final String HOVER_BUYER_PATH = "/hover/buyer";

    private static final String INTERACTION_DATA = "{\"key\":\"value\"}";

    private static final int BUYER_DESTINATION =
            ReportInteractionRequest.FLAG_REPORTING_DESTINATION_BUYER;
    private static final int SELLER_DESTINATION =
            ReportInteractionRequest.FLAG_REPORTING_DESTINATION_SELLER;

    private static final long BINDER_ELAPSED_TIMESTAMP = 100L;
    private static final List<Double> BIDS_FOR_BUYER_1 = ImmutableList.of(1.1, 2.2);
    private static final List<Double> BIDS_FOR_BUYER_2 = ImmutableList.of(4.5, 6.7, 10.0);
    // A list of empty ad counter keys to apply to ads for buyer when not doing fcap filtering.
    private static final List<Set<String>> EMPTY_AD_COUNTER_KEYS_FOR_BUYER_2 =
            Arrays.asList(new HashSet[BIDS_FOR_BUYER_2.size()]);
    private static final List<Double> INVALID_BIDS = ImmutableList.of(0.0, -1.0, -2.0);
    private final AdServicesLogger mAdServicesLogger = AdServicesLoggerImpl.getInstance();
    @Rule public MockWebServerRule mMockWebServerRule = MockWebServerRuleFactory.createForHttps();
    @Mock private ConsentManager mConsentManagerMock;
    private MockitoSession mStaticMockSession = null;
    // This object access some system APIs
    @Mock private DevContextFilter mDevContextFilter;
    @Mock private AppImportanceFilter mAppImportanceFilter;
    @Mock private Throttler mMockThrottler;
    private AdSelectionConfig mAdSelectionConfig;
    private AdServicesHttpsClient mAdServicesHttpsClient;
    private CustomAudienceDao mCustomAudienceDao;
    private AdSelectionEntryDao mAdSelectionEntryDao;
    private AppInstallDao mAppInstallDao;
    private FrequencyCapDao mFrequencyCapDao;
    private ExecutorService mLightweightExecutorService;
    private ExecutorService mBackgroundExecutorService;
    private ScheduledThreadPoolExecutor mScheduledExecutor;
    private CustomAudienceServiceImpl mCustomAudienceService;
    private AdSelectionServiceImpl mAdSelectionService;

    private static final Flags DEFAULT_FLAGS = new FledgeE2ETestFlags(false, true, true, true);
    private MockWebServerRule.RequestMatcher<String> mRequestMatcherPrefixMatch;
    private Uri mLocalhostBuyerDomain;
    private final Supplier<Throttler> mThrottlerSupplier = () -> mMockThrottler;

    private AdFilteringFeatureFactory mAdFilteringFeatureFactory;

    @Spy
    FledgeAllowListsFilter mFledgeAllowListsFilterSpy =
            new FledgeAllowListsFilter(DEFAULT_FLAGS, mAdServicesLogger);

    @Mock FledgeAuthorizationFilter mFledgeAuthorizationFilterMock;

    @Mock private File mMockDBAdSelectionFile;

    @Mock private AdSelectionServiceFilter mAdSelectionServiceFilter;
    @Mock private AppImportanceFilter mAppImportanceFilterMock;

    @Before
    public void setUp() throws Exception {
        // Every test in this class requires that the JS Sandbox be available. The JS Sandbox
        // availability depends on an external component (the system webview) being higher than a
        // certain minimum version. Marking that as an assumption that the test is making.
        Assume.assumeTrue(JSScriptEngine.AvailabilityChecker.isJSSandboxAvailable());

        // Test applications don't have the required permissions to read config P/H flags, and
        // injecting mocked flags everywhere is annoying and non-trivial for static methods
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .mockStatic(BackgroundFetchJobService.class)
                        .mockStatic(ConsentManager.class)
                        .mockStatic(AppImportanceFilter.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
        doReturn(DEFAULT_FLAGS).when(FlagsFactory::getFlags);

        mCustomAudienceDao =
                Room.inMemoryDatabaseBuilder(CONTEXT, CustomAudienceDatabase.class)
                        .addTypeConverter(new DBCustomAudience.Converters(true))
                        .build()
                        .customAudienceDao();

        mAdSelectionEntryDao =
                Room.inMemoryDatabaseBuilder(CONTEXT, AdSelectionDatabase.class)
                        .build()
                        .adSelectionEntryDao();
        SharedStorageDatabase sharedDb =
                Room.inMemoryDatabaseBuilder(CONTEXT, SharedStorageDatabase.class).build();
        mAppInstallDao = sharedDb.appInstallDao();
        mFrequencyCapDao = sharedDb.frequencyCapDao();
        mAdFilteringFeatureFactory =
                new AdFilteringFeatureFactory(mAppInstallDao, mFrequencyCapDao, DEFAULT_FLAGS);

        mLightweightExecutorService = AdServicesExecutors.getLightWeightExecutor();
        mBackgroundExecutorService = AdServicesExecutors.getBackgroundExecutor();
        mScheduledExecutor = AdServicesExecutors.getScheduler();

        mAdServicesHttpsClient =
                new AdServicesHttpsClient(
                        AdServicesExecutors.getBlockingExecutor(),
                        CacheProviderFactory.createNoOpCache());

        initClients(false, true);

        mRequestMatcherPrefixMatch = (a, b) -> !b.isEmpty() && a.startsWith(b);

        when(mMockThrottler.tryAcquire(eq(FLEDGE_API_SELECT_ADS), anyString())).thenReturn(true);
        when(mMockThrottler.tryAcquire(eq(FLEDGE_API_REPORT_IMPRESSIONS), anyString()))
                .thenReturn(true);
        when(mMockThrottler.tryAcquire(eq(FLEDGE_API_JOIN_CUSTOM_AUDIENCE), anyString()))
                .thenReturn(true);
        when(mMockThrottler.tryAcquire(eq(FLEDGE_API_LEAVE_CUSTOM_AUDIENCE), anyString()))
                .thenReturn(true);

        mLocalhostBuyerDomain = Uri.parse(mMockWebServerRule.getServerBaseAddress());
        when(CONTEXT.getDatabasePath(DATABASE_NAME)).thenReturn(mMockDBAdSelectionFile);
        when(mMockDBAdSelectionFile.length()).thenReturn(DB_AD_SELECTION_FILE_SIZE);
        doNothing()
                .when(mAdSelectionServiceFilter)
                .filterRequest(
                        any(), anyString(), anyBoolean(), anyBoolean(), anyInt(), anyInt(), any());
        when(ConsentManager.getInstance(CONTEXT)).thenReturn(mConsentManagerMock);
        when(AppImportanceFilter.create(any(), anyInt(), any()))
                .thenReturn(mAppImportanceFilterMock);
        doNothing()
                .when(mAppImportanceFilterMock)
                .assertCallerIsInForeground(anyInt(), anyInt(), any());
    }

    @After
    public void teardown() {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    @Test
    public void testFledgeFlowSuccessWithDevOverridesRegisterAdBeaconDisabled() throws Exception {
        // Re init clients with registerAdBeacon false
        initClients(false, false);

        setupConsentGivenStubs();

        setupAdSelectionConfig();

        String decisionLogicJs = getDecisionLogicJs();
        String biddingLogicJs = getBiddingLogicJs();

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> new MockResponse().setResponseCode(404));

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(CommonFixture.TEST_PACKAGE_NAME)
                                .build());

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, BIDS_FOR_BUYER_2);

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        setupOverridesAndAssertSuccess(
                customAudience1, customAudience2, biddingLogicJs, decisionLogicJs);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad3"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultsCallback.mAdSelectionResponse.getAdSelectionId());
        mMockWebServerRule.verifyMockServerRequests(
                server, 0, ImmutableList.of(), mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithDevOverridesRegisterAdBeaconEnabled() throws Exception {
        setupConsentGivenStubs();

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs = getBiddingLogicWithBeacons();

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> new MockResponse().setResponseCode(404));

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(CommonFixture.TEST_PACKAGE_NAME)
                                .build());

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, BIDS_FOR_BUYER_2);

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        setupOverridesAndAssertSuccess(
                customAudience1, customAudience2, biddingLogicJs, decisionLogicJs);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad3"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultsCallback.mAdSelectionResponse.getAdSelectionId());

        reportInteractionAndAssertSuccess(resultsCallback);

        mMockWebServerRule.verifyMockServerRequests(
                server, 0, ImmutableList.of(), mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithDevOverridesGaUxEnabled() throws Exception {
        initClients(true, true);
        doReturn(AdServicesApiConsent.GIVEN)
                .when(mConsentManagerMock)
                .getConsent(AdServicesApiType.FLEDGE);
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs = getBiddingLogicWithBeacons();

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> new MockResponse().setResponseCode(404));

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(CommonFixture.TEST_PACKAGE_NAME)
                                .build());

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, BIDS_FOR_BUYER_2);

        // Join first custom audience
        joinCustomAudienceAndAssertSuccess(customAudience1);

        // Join second custom audience
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        setupOverridesAndAssertSuccess(
                customAudience1, customAudience2, biddingLogicJs, decisionLogicJs);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad3"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultsCallback.mAdSelectionResponse.getAdSelectionId());

        reportInteractionAndAssertSuccess(resultsCallback);

        mMockWebServerRule.verifyMockServerRequests(
                server, 0, ImmutableList.of(), mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithDevOverridesWithRevokedUserConsentForApp()
            throws Exception {
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        // Allow the first calls to succeed so that we can verify the rest of the flow works
        when(mConsentManagerMock.isFledgeConsentRevokedForApp(any()))
                .thenReturn(false)
                .thenReturn(true);
        when(mConsentManagerMock.isFledgeConsentRevokedForAppAfterSettingFledgeUse(any()))
                .thenReturn(false)
                .thenReturn(true);

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs = getBiddingLogicWithBeacons();

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> new MockResponse().setResponseCode(404));

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(CommonFixture.TEST_PACKAGE_NAME)
                                .build());

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, BIDS_FOR_BUYER_2);

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        setupOverridesAndAssertSuccess(
                customAudience1, customAudience2, biddingLogicJs, decisionLogicJs);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        // Verify that CA1/ad2 won because CA2 was not joined due to user consent
        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_1 + "/ad2"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultsCallback.mAdSelectionResponse.getAdSelectionId());

        reportInteractionAndAssertSuccess(resultsCallback);

        mMockWebServerRule.verifyMockServerRequests(
                server, 0, ImmutableList.of(), mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithDevOverridesWithRevokedUserConsentForAppGaUxEnabled()
            throws Exception {
        initClients(true, true);
        doReturn(AdServicesApiConsent.GIVEN)
                .when(mConsentManagerMock)
                .getConsent(AdServicesApiType.FLEDGE);
        // Allow the first calls to succeed so that we can verify the rest of the flow works
        when(mConsentManagerMock.isFledgeConsentRevokedForApp(any()))
                .thenReturn(false)
                .thenReturn(true);
        when(mConsentManagerMock.isFledgeConsentRevokedForAppAfterSettingFledgeUse(any()))
                .thenReturn(false)
                .thenReturn(true);

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs = getBiddingLogicWithBeacons();

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> new MockResponse().setResponseCode(404));

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(CommonFixture.TEST_PACKAGE_NAME)
                                .build());

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, BIDS_FOR_BUYER_2);

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        setupOverridesAndAssertSuccess(
                customAudience1, customAudience2, biddingLogicJs, decisionLogicJs);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        // Verify that CA1/ad2 won because CA2 was not joined due to user consent
        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_1 + "/ad2"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultsCallback.mAdSelectionResponse.getAdSelectionId());

        reportInteractionAndAssertSuccess(resultsCallback);

        mMockWebServerRule.verifyMockServerRequests(
                server, 0, ImmutableList.of(), mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowFailsWithMismatchedPackageNamesReportImpression() throws Exception {
        setupConsentGivenStubs();

        String otherPackageName = CommonFixture.TEST_PACKAGE_NAME + "different_package";

        // Mocking PackageManager so it passes package name validation, but fails impression
        // reporting
        // due to package mismatch
        PackageManager packageManagerMock = mock(PackageManager.class);
        when(CONTEXT.getPackageManager()).thenReturn(packageManagerMock);
        when(packageManagerMock.getPackagesForUid(Process.myUid()))
                .thenReturn(new String[] {CommonFixture.TEST_PACKAGE_NAME, otherPackageName});

        // Reinitializing service so mocking takes effect
        // Create an instance of AdSelection Service with real dependencies
        mAdSelectionService =
                new AdSelectionServiceImpl(
                        mAdSelectionEntryDao,
                        mAppInstallDao,
                        mCustomAudienceDao,
                        mFrequencyCapDao,
                        mAdServicesHttpsClient,
                        mDevContextFilter,
                        mLightweightExecutorService,
                        mBackgroundExecutorService,
                        mScheduledExecutor,
                        CONTEXT,
                        mAdServicesLogger,
                        DEFAULT_FLAGS,
                        CallingAppUidSupplierProcessImpl.create(),
                        mFledgeAuthorizationFilterMock,
                        mAdSelectionServiceFilter,
                        mAdFilteringFeatureFactory,
                        mConsentManagerMock);

        mAdSelectionConfig =
                AdSelectionConfigFixture.anAdSelectionConfigBuilder()
                        .setCustomAudienceBuyers(
                                ImmutableList.of(
                                        AdTechIdentifier.fromString(BUYER_DOMAIN_1.getHost()),
                                        AdTechIdentifier.fromString(BUYER_DOMAIN_2.getHost())))
                        .setSeller(
                                AdTechIdentifier.fromString(
                                        mMockWebServerRule
                                                .uriForPath(SELLER_DECISION_LOGIC_URI_PATH)
                                                .getHost()))
                        .setDecisionLogicUri(
                                mMockWebServerRule.uriForPath(SELLER_DECISION_LOGIC_URI_PATH))
                        .setTrustedScoringSignalsUri(
                                mMockWebServerRule.uriForPath(SELLER_TRUSTED_SIGNAL_URI_PATH))
                        .build();

        AdSelectionConfig adSelectionConfigWithDifferentCallerPackageName =
                AdSelectionConfigFixture.anAdSelectionConfigBuilder()
                        .setCustomAudienceBuyers(
                                ImmutableList.of(
                                        AdTechIdentifier.fromString(BUYER_DOMAIN_1.getHost()),
                                        AdTechIdentifier.fromString(BUYER_DOMAIN_2.getHost())))
                        .setSeller(
                                AdTechIdentifier.fromString(
                                        mMockWebServerRule
                                                .uriForPath(SELLER_DECISION_LOGIC_URI_PATH)
                                                .getHost()))
                        .setDecisionLogicUri(
                                mMockWebServerRule.uriForPath(SELLER_DECISION_LOGIC_URI_PATH))
                        .setTrustedScoringSignalsUri(
                                mMockWebServerRule.uriForPath(SELLER_TRUSTED_SIGNAL_URI_PATH))
                        .build();

        String decisionLogicJs =
                "function scoreAd(ad, bid, auction_config, seller_signals,"
                        + " trusted_scoring_signals, contextual_signal, user_signal,"
                        + " custom_audience_signal) { \n"
                        + "  return {'status': 0, 'score': bid };\n"
                        + "}\n"
                        + "function reportResult(ad_selection_config, render_uri, bid,"
                        + " contextual_signals) { \n"
                        + " return {'status': 0, 'results': {'signals_for_buyer':"
                        + " '{\"signals_for_buyer\":1}', 'reporting_uri': '"
                        + SELLER_REPORTING_PATH
                        + "' } };\n"
                        + "}";
        String biddingLogicJs =
                "function generateBid(ad, auction_signals, per_buyer_signals,"
                    + " trusted_bidding_signals, contextual_signals, custom_audience_signals) { \n"
                    + "  return {'status': 0, 'ad': ad, 'bid': ad.metadata.result };\n"
                    + "}\n"
                    + "function reportWin(ad_selection_signals, per_buyer_signals,"
                    + " signals_for_buyer, contextual_signals, custom_audience_signals) { \n"
                    + " return {'status': 0, 'results': {'reporting_uri': '"
                        + BUYER_REPORTING_PATH
                        + "' } };\n"
                        + "}";

        mMockWebServerRule.startMockWebServer(request -> new MockResponse().setResponseCode(404));

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(CommonFixture.TEST_PACKAGE_NAME)
                                .build());

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        CustomAudience customAudience1 = createCustomAudience(BUYER_DOMAIN_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 = createCustomAudience(BUYER_DOMAIN_2, BIDS_FOR_BUYER_2);

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        setupOverridesAndAssertSuccess(
                customAudience1, customAudience2, biddingLogicJs, decisionLogicJs);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(
                CommonFixture.getUri(BUYER_DOMAIN_2.getHost(), AD_URI_PREFIX + "/ad3"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        // Run Report Impression with different package name
        ReportImpressionInput input =
                new ReportImpressionInput.Builder()
                        .setAdSelectionConfig(adSelectionConfigWithDifferentCallerPackageName)
                        .setAdSelectionId(resultsCallback.mAdSelectionResponse.getAdSelectionId())
                        .setCallerPackageName(otherPackageName)
                        .build();

        ReportImpressionTestCallback reportImpressionTestCallback =
                callReportImpression(mAdSelectionService, input);

        assertFalse(reportImpressionTestCallback.mIsSuccess);
        assertEquals(
                reportImpressionTestCallback.mFledgeErrorResponse.getStatusCode(),
                AdServicesStatusUtils.STATUS_INVALID_ARGUMENT);
        assertEquals(
                reportImpressionTestCallback.mFledgeErrorResponse.getErrorMessage(),
                CALLER_PACKAGE_NAME_MISMATCH);

        // Run Report Interaction, should fail silently due to no registered beacons
        reportInteractionAndAssertSuccess(resultsCallback);
    }

    @Test
    public void testFledgeFlowFailsWithWrongPackageNameReportInteraction() throws Exception {
        setupConsentGivenStubs();

        String otherPackageName = CommonFixture.TEST_PACKAGE_NAME + "different_package";

        // Mocking PackageManager so it passes package name validation, but fails in interaction
        // reporting
        // due to package mismatch
        PackageManager packageManagerMock = mock(PackageManager.class);
        when(CONTEXT.getPackageManager()).thenReturn(packageManagerMock);
        when(packageManagerMock.getPackagesForUid(Process.myUid()))
                .thenReturn(new String[] {CommonFixture.TEST_PACKAGE_NAME, otherPackageName});

        // Reinitializing service so mocking takes effect
        // Create an instance of AdSelection Service with real dependencies
        mAdSelectionService =
                new AdSelectionServiceImpl(
                        mAdSelectionEntryDao,
                        mAppInstallDao,
                        mCustomAudienceDao,
                        mFrequencyCapDao,
                        mAdServicesHttpsClient,
                        mDevContextFilter,
                        mLightweightExecutorService,
                        mBackgroundExecutorService,
                        mScheduledExecutor,
                        CONTEXT,
                        mAdServicesLogger,
                        DEFAULT_FLAGS,
                        CallingAppUidSupplierProcessImpl.create(),
                        mFledgeAuthorizationFilterMock,
                        mAdSelectionServiceFilter,
                        mAdFilteringFeatureFactory,
                        mConsentManagerMock);

        mAdSelectionConfig =
                AdSelectionConfigFixture.anAdSelectionConfigBuilder()
                        .setCustomAudienceBuyers(
                                ImmutableList.of(
                                        AdTechIdentifier.fromString(BUYER_DOMAIN_1.getHost()),
                                        AdTechIdentifier.fromString(BUYER_DOMAIN_2.getHost())))
                        .setSeller(
                                AdTechIdentifier.fromString(
                                        mMockWebServerRule
                                                .uriForPath(SELLER_DECISION_LOGIC_URI_PATH)
                                                .getHost()))
                        .setDecisionLogicUri(
                                mMockWebServerRule.uriForPath(SELLER_DECISION_LOGIC_URI_PATH))
                        .setTrustedScoringSignalsUri(
                                mMockWebServerRule.uriForPath(SELLER_TRUSTED_SIGNAL_URI_PATH))
                        .build();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs = getBiddingLogicWithBeacons();

        mMockWebServerRule.startMockWebServer(request -> new MockResponse().setResponseCode(404));

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(CommonFixture.TEST_PACKAGE_NAME)
                                .build());

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        CustomAudience customAudience1 = createCustomAudience(BUYER_DOMAIN_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 = createCustomAudience(BUYER_DOMAIN_2, BIDS_FOR_BUYER_2);

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        setupOverridesAndAssertSuccess(
                customAudience1, customAudience2, biddingLogicJs, decisionLogicJs);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(
                CommonFixture.getUri(BUYER_DOMAIN_2.getHost(), AD_URI_PREFIX + "/ad3"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultsCallback.mAdSelectionResponse.getAdSelectionId());

        // Run Report Interaction with different package name
        ReportInteractionInput reportInteractionInput =
                new ReportInteractionInput.Builder()
                        .setAdSelectionId(resultsCallback.mAdSelectionResponse.getAdSelectionId())
                        .setInteractionKey(CLICK_INTERACTION)
                        .setInteractionData(INTERACTION_DATA)
                        .setCallerPackageName(otherPackageName)
                        .setReportingDestinations(BUYER_DESTINATION | SELLER_DESTINATION)
                        .build();

        ReportInteractionTestCallback reportInteractionTestCallback =
                callReportInteraction(mAdSelectionService, reportInteractionInput);
        assertFalse(reportInteractionTestCallback.mIsSuccess);

        assertEquals(
                reportInteractionTestCallback.mFledgeErrorResponse.getStatusCode(),
                AdServicesStatusUtils.STATUS_INVALID_ARGUMENT);
        assertEquals(
                reportInteractionTestCallback.mFledgeErrorResponse.getErrorMessage(),
                InteractionReporter.NO_MATCH_FOUND_IN_AD_SELECTION_DB);
    }

    @Test
    public void testFledgeFlowSuccessWithOneCAWithNegativeBidsWithDevOverrides() throws Exception {
        setupConsentGivenStubs();

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs = getBiddingLogicWithBeacons();

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> {
                            // With overrides the server should not be called
                            return new MockResponse().setResponseCode(404);
                        });

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(CommonFixture.TEST_PACKAGE_NAME)
                                .build());

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, INVALID_BIDS);
        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        setupOverridesAndAssertSuccess(
                customAudience1, customAudience2, biddingLogicJs, decisionLogicJs);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        // Expect that ad from buyer 1 won since buyer 2 had negative bids
        assertEquals(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_1 + "/ad2"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultsCallback.mAdSelectionResponse.getAdSelectionId());
        reportInteractionAndAssertSuccess(resultsCallback);

        mMockWebServerRule.verifyMockServerRequests(
                server, 0, ImmutableList.of(), mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithOneCAWithNegativeBidsWithDevOverrides_v3BiddingLogic()
            throws Exception {
        setupConsentGivenStubs();

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs =
                "function generateBid(custom_audience, auction_signals, per_buyer_signals,\n"
                        + "    trusted_bidding_signals, contextual_signals) {\n"
                        + "    const ads = custom_audience.ads;\n"
                        + "    let result = null;\n"
                        + "    for (const ad of ads) {\n"
                        + "        if (!result || ad.metadata.result > result.metadata.result) {\n"
                        + "            result = ad;\n"
                        + "        }\n"
                        + "    }\n"
                        + "    return { 'status': 0, 'ad': result, 'bid': result.metadata.result, "
                        + "'render': result.render_uri };\n"
                        + "}\n"
                        + "function reportWin(ad_selection_signals, per_buyer_signals,"
                        + " signals_for_buyer ,contextual_signals, custom_audience_signals) {\n"
                        + "    registerAdBeacon('click', '"
                        + mMockWebServerRule.uriForPath(CLICK_BUYER_PATH)
                        + "');\n"
                        + "    registerAdBeacon('hover', '"
                        + mMockWebServerRule.uriForPath(HOVER_BUYER_PATH)
                        + "');\n"
                        + " return {'status': 0, 'results': {'reporting_uri': '"
                        + mMockWebServerRule.uriForPath(BUYER_REPORTING_PATH)
                        + "' } };\n"
                        + "}";

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> {
                            // With overrides the server should not be called
                            return new MockResponse().setResponseCode(404);
                        });

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(CommonFixture.TEST_PACKAGE_NAME)
                                .build());

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, INVALID_BIDS);
        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        // Add AdSelection Override
        AdSelectionOverrideTestCallback adSelectionOverrideTestCallback =
                callAddAdSelectionOverride(
                        mAdSelectionService,
                        mAdSelectionConfig,
                        decisionLogicJs,
                        TRUSTED_SCORING_SIGNALS,
                        BUYERS_DECISION_LOGIC);

        assertTrue(adSelectionOverrideTestCallback.mIsSuccess);

        // Add Custom Audience Overrides
        CustomAudienceOverrideTestCallback customAudienceOverrideTestCallback1 =
                callAddCustomAudienceOverride(
                        CommonFixture.TEST_PACKAGE_NAME,
                        customAudience1.getBuyer(),
                        customAudience1.getName(),
                        biddingLogicJs,
                        JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3,
                        TRUSTED_BIDDING_SIGNALS,
                        mCustomAudienceService);

        assertTrue(customAudienceOverrideTestCallback1.mIsSuccess);

        CustomAudienceOverrideTestCallback customAudienceOverrideTestCallback2 =
                callAddCustomAudienceOverride(
                        CommonFixture.TEST_PACKAGE_NAME,
                        customAudience2.getBuyer(),
                        customAudience2.getName(),
                        biddingLogicJs,
                        JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3,
                        TRUSTED_BIDDING_SIGNALS,
                        mCustomAudienceService);

        assertTrue(customAudienceOverrideTestCallback2.mIsSuccess);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        // Expect that ad from buyer 1 won since buyer 2 had negative bids
        assertEquals(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_1 + "/ad2"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultsCallback.mAdSelectionResponse.getAdSelectionId());

        reportInteractionAndAssertSuccess(resultsCallback);

        mMockWebServerRule.verifyMockServerRequests(
                server, 0, ImmutableList.of(), mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowFailsWithBothCANegativeBidsWithDevOverrides() throws Exception {
        setupConsentGivenStubs();

        mAdSelectionConfig =
                AdSelectionConfigFixture.anAdSelectionConfigBuilder()
                        .setCustomAudienceBuyers(
                                ImmutableList.of(
                                        AdTechIdentifier.fromString(BUYER_DOMAIN_1.getHost()),
                                        AdTechIdentifier.fromString(BUYER_DOMAIN_2.getHost())))
                        .setSeller(
                                AdTechIdentifier.fromString(
                                        mMockWebServerRule
                                                .uriForPath(SELLER_DECISION_LOGIC_URI_PATH)
                                                .getHost()))
                        .setDecisionLogicUri(
                                mMockWebServerRule.uriForPath(SELLER_DECISION_LOGIC_URI_PATH))
                        .setTrustedScoringSignalsUri(
                                mMockWebServerRule.uriForPath(SELLER_TRUSTED_SIGNAL_URI_PATH))
                        .build();

        String decisionLogicJs =
                "function scoreAd(ad, bid, auction_config, seller_signals,"
                        + " trusted_scoring_signals, contextual_signal, user_signal,"
                        + " custom_audience_signal) { \n"
                        + "  return {'status': 0, 'score': bid };\n"
                        + "}\n"
                        + "function reportResult(ad_selection_config, render_uri, bid,"
                        + " contextual_signals) { \n"
                        + " return {'status': 0, 'results': {'signals_for_buyer':"
                        + " '{\"signals_for_buyer\":1}', 'reporting_uri': '"
                        + SELLER_REPORTING_PATH
                        + "' } };\n"
                        + "}";
        String biddingLogicJs =
                "function generateBid(ad, auction_signals, per_buyer_signals,"
                    + " trusted_bidding_signals, contextual_signals, custom_audience_signals) { \n"
                    + "  return {'status': 0, 'ad': ad, 'bid': ad.metadata.result };\n"
                    + "}\n"
                    + "function reportWin(ad_selection_signals, per_buyer_signals,"
                    + " signals_for_buyer, contextual_signals, custom_audience_signals) { \n"
                    + " return {'status': 0, 'results': {'reporting_uri': '"
                        + BUYER_REPORTING_PATH
                        + "' } };\n"
                        + "}";

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> {
                            // with overrides the server should not be invoked
                            return new MockResponse().setResponseCode(404);
                        });

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(CommonFixture.TEST_PACKAGE_NAME)
                                .build());

        CustomAudience customAudience1 = createCustomAudience(BUYER_DOMAIN_1, INVALID_BIDS);

        CustomAudience customAudience2 = createCustomAudience(BUYER_DOMAIN_2, INVALID_BIDS);

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        // Add AdSelection Override
        AdSelectionOverrideTestCallback adSelectionOverrideTestCallback =
                callAddAdSelectionOverride(
                        mAdSelectionService,
                        mAdSelectionConfig,
                        decisionLogicJs,
                        TRUSTED_SCORING_SIGNALS,
                        BUYERS_DECISION_LOGIC);

        assertTrue(adSelectionOverrideTestCallback.mIsSuccess);

        // Add Custom Audience Overrides
        CustomAudienceOverrideTestCallback customAudienceOverrideTestCallback1 =
                callAddCustomAudienceOverride(
                        CommonFixture.TEST_PACKAGE_NAME,
                        customAudience1.getBuyer(),
                        customAudience1.getName(),
                        biddingLogicJs,
                        null,
                        AdSelectionSignals.EMPTY,
                        mCustomAudienceService);

        assertTrue(customAudienceOverrideTestCallback1.mIsSuccess);

        CustomAudienceOverrideTestCallback customAudienceOverrideTestCallback2 =
                callAddCustomAudienceOverride(
                        CommonFixture.TEST_PACKAGE_NAME,
                        customAudience2.getBuyer(),
                        customAudience2.getName(),
                        biddingLogicJs,
                        null,
                        AdSelectionSignals.EMPTY,
                        mCustomAudienceService);

        assertTrue(customAudienceOverrideTestCallback2.mIsSuccess);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        // Assert that ad selection fails since both Custom Audiences have invalid bids
        assertFalse(resultsCallback.mIsSuccess);
        assertNull(resultsCallback.mAdSelectionResponse);

        // Run Report Impression with random ad selection id
        ReportImpressionInput input =
                new ReportImpressionInput.Builder()
                        .setAdSelectionConfig(mAdSelectionConfig)
                        .setAdSelectionId(1)
                        .setCallerPackageName(CommonFixture.TEST_PACKAGE_NAME)
                        .build();

        ReportImpressionTestCallback reportImpressionTestCallback =
                callReportImpression(mAdSelectionService, input);

        assertFalse(reportImpressionTestCallback.mIsSuccess);

        // Run Report Interaction with random ad selection id
        ReportInteractionInput reportInteractionInput =
                new ReportInteractionInput.Builder()
                        .setAdSelectionId(1)
                        .setInteractionKey(CLICK_INTERACTION)
                        .setInteractionData(INTERACTION_DATA)
                        .setCallerPackageName(CommonFixture.TEST_PACKAGE_NAME)
                        .setReportingDestinations(BUYER_DESTINATION | SELLER_DESTINATION)
                        .build();

        ReportInteractionTestCallback reportInteractionTestCallback =
                callReportInteraction(mAdSelectionService, reportInteractionInput);
        assertFalse(reportInteractionTestCallback.mIsSuccess);

        mMockWebServerRule.verifyMockServerRequests(
                server, 0, Collections.emptyList(), mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithMockServer_preV3BiddingLogic() throws Exception {
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs = getBiddingLogicWithBeacons();

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, BIDS_FOR_BUYER_2);

        // We add permits to the semaphores when the MWS is called and remove them in the asserts
        Semaphore impressionReportingSemaphore = new Semaphore(0);
        Semaphore interactionReportingSemaphore = new Semaphore(0);

        MockWebServer server =
                getMockWebServer(
                        decisionLogicJs,
                        biddingLogicJs,
                        impressionReportingSemaphore,
                        interactionReportingSemaphore,
                        false);

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        selectAdsAndReport(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad3"),
                impressionReportingSemaphore,
                interactionReportingSemaphore);
        verifyStandardServerRequests(server);
    }

    @Test
    public void testFledgeFlowSuccessWithMockServer_v3BiddingLogic() throws Exception {
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        setupAdSelectionConfig();

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, BIDS_FOR_BUYER_2);

        // We add permits to the semaphores when the MWS is called and remove them in the asserts
        Semaphore impressionReportingSemaphore = new Semaphore(0);
        Semaphore interactionReportingSemaphore = new Semaphore(0);

        MockWebServer server =
                getMockWebServer(
                        getDecisionLogicWithBeacons(),
                        getV3BiddingLogicJs(),
                        impressionReportingSemaphore,
                        interactionReportingSemaphore,
                        true);

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        // Run Ad Selection
        selectAdsAndReport(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad3"),
                impressionReportingSemaphore,
                interactionReportingSemaphore);
        verifyStandardServerRequests(server);
    }

    @Test
    public void testFledgeFlowSuccessWithMockServer_DoesNotReportToBuyerWhenEnrollmentFails()
            throws Exception {
        initClients(false, true, true, false);
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        setupAdSelectionConfig();

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, BIDS_FOR_BUYER_2);

        // We add permits to the semaphores when the MWS is called and remove them in the asserts
        Semaphore impressionReportingSemaphore = new Semaphore(0);
        Semaphore interactionReportingSemaphore = new Semaphore(0);

        MockWebServer server =
                getMockWebServer(
                        getDecisionLogicWithBeacons(),
                        getV3BiddingLogicJs(),
                        impressionReportingSemaphore,
                        interactionReportingSemaphore,
                        true);

        // Make buyer impression reporting fail enrollment check
        doThrow(new FledgeAuthorizationFilter.AdTechNotAllowedException())
                .when(mFledgeAuthorizationFilterMock)
                .assertAdTechEnrolled(
                        AdTechIdentifier.fromString(
                                mMockWebServerRule.uriForPath(BUYER_REPORTING_PATH).getHost()),
                        AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION);

        doThrow(new FledgeAuthorizationFilter.AdTechNotAllowedException())
                .when(mFledgeAuthorizationFilterMock)
                .assertAdTechEnrolled(
                        AdTechIdentifier.fromString(
                                mMockWebServerRule.uriForPath(BUYER_REPORTING_PATH).getHost()),
                        AD_SERVICES_API_CALLED__API_NAME__REPORT_INTERACTION);

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad3"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultSelectionId);
        reportOnlyBuyerInteractionAndAssertSuccess(resultsCallback);

        // Assert only seller impression reporting happened since buyer enrollment check fails
        assertTrue(impressionReportingSemaphore.tryAcquire(1, 10, TimeUnit.SECONDS));

        // Assert buyer interaction reporting did not happen
        assertTrue(interactionReportingSemaphore.tryAcquire(0, 10, TimeUnit.SECONDS));

        assertEquals(
                "Extra calls made to MockWebServer",
                0,
                impressionReportingSemaphore.availablePermits());
        assertEquals(
                "Extra calls made to MockWebServer",
                0,
                interactionReportingSemaphore.availablePermits());

        // Verify 3 less requests than normal since only seller impression reporting happens
        mMockWebServerRule.verifyMockServerRequests(
                server,
                7,
                ImmutableList.of(
                        SELLER_DECISION_LOGIC_URI_PATH,
                        BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_1,
                        BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_2,
                        BUYER_TRUSTED_SIGNAL_URI_PATH,
                        SELLER_TRUSTED_SIGNAL_URI_PATH + SELLER_TRUSTED_SIGNAL_PARAMS),
                mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithMockServer_allFilters() throws Exception {
        initClients(true, true);
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        setupAdSelectionConfig();
        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        // Using the same generic key across all ads in the CA
        List<Set<String>> adCounterKeysForCa2 =
                Arrays.asList(
                        Collections.singleton(KeyedFrequencyCapFixture.KEY1),
                        Collections.singleton(KeyedFrequencyCapFixture.KEY1),
                        Collections.singleton(KeyedFrequencyCapFixture.KEY1));
        /* The final ad with the highest bid has both fcap and app install filters, the second ad
         * with the middle bid has only an app install filter and the first ad with the lowest bid
         * in this ca has only a fcap filter.
         */
        List<AdFilters> adFiltersForCa2 =
                Arrays.asList(
                        new AdFilters.Builder()
                                .setFrequencyCapFilters(CLICK_ONCE_PER_DAY_KEY1)
                                .build(),
                        new AdFilters.Builder().setAppInstallFilters(CURRENT_APP_FILTER).build(),
                        new AdFilters.Builder()
                                .setAppInstallFilters(CURRENT_APP_FILTER)
                                .setFrequencyCapFilters(CLICK_ONCE_PER_DAY_KEY1)
                                .build());
        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain,
                        CUSTOM_AUDIENCE_SEQ_2,
                        BIDS_FOR_BUYER_2,
                        adCounterKeysForCa2,
                        adFiltersForCa2);

        // We add permits to the semaphores when the MWS is called and remove them in the asserts
        Semaphore impressionReportingSemaphore = new Semaphore(0);
        Semaphore interactionReportingSemaphore = new Semaphore(0);

        MockWebServer server =
                getMockWebServer(
                        getDecisionLogicWithBeacons(),
                        getV3BiddingLogicJs(),
                        impressionReportingSemaphore,
                        interactionReportingSemaphore,
                        true);

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        // Run Ad Selection no filters active
        selectAdsAndReport(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad3"),
                impressionReportingSemaphore,
                interactionReportingSemaphore);

        // Run Ad Selection with app install filtering
        registerForAppInstallFiltering();
        long adSelectionId =
                selectAdsAndReport(
                        CommonFixture.getUri(
                                mLocalhostBuyerDomain.getAuthority(),
                                AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad1"),
                        impressionReportingSemaphore,
                        interactionReportingSemaphore);

        // Run Ad Selection with both filters
        updateHistogramAndAssertSuccess(adSelectionId, FrequencyCapFilters.AD_EVENT_TYPE_CLICK);
        selectAdsAndReport(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_1 + "/ad2"),
                impressionReportingSemaphore,
                interactionReportingSemaphore);

        // Run Ad Selection with just fcap filtering
        deregisterForAppInstallFiltering();
        selectAdsAndReport(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad2"),
                impressionReportingSemaphore,
                interactionReportingSemaphore);

        // 30 requests for the 3 auctions with both CAs and 9 requests for the auctions with one CA
        mMockWebServerRule.verifyMockServerRequests(
                server,
                39,
                ImmutableList.of(
                        SELLER_DECISION_LOGIC_URI_PATH,
                        BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_1,
                        BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_2,
                        BUYER_TRUSTED_SIGNAL_URI_PATH,
                        SELLER_TRUSTED_SIGNAL_URI_PATH + SELLER_TRUSTED_SIGNAL_PARAMS),
                mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithMockServer_ContextualAdsFlow() throws Exception {
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        Uri sellerReportingUri = mMockWebServerRule.uriForPath(SELLER_REPORTING_PATH);
        Uri buyerReportingUri = mMockWebServerRule.uriForPath(BUYER_REPORTING_PATH);

        mAdSelectionConfig =
                AdSelectionConfigFixture.anAdSelectionConfigBuilder()
                        // Setting empty buyers
                        .setCustomAudienceBuyers(ImmutableList.of())
                        .setSeller(
                                AdTechIdentifier.fromString(
                                        mMockWebServerRule
                                                .uriForPath(SELLER_DECISION_LOGIC_URI_PATH)
                                                .getHost()))
                        .setDecisionLogicUri(
                                mMockWebServerRule.uriForPath(SELLER_DECISION_LOGIC_URI_PATH))
                        .setTrustedScoringSignalsUri(
                                mMockWebServerRule.uriForPath(SELLER_TRUSTED_SIGNAL_URI_PATH))
                        .setPerBuyerSignals(
                                ImmutableMap.of(
                                        AdTechIdentifier.fromString(
                                                mLocalhostBuyerDomain.getHost()),
                                        AdSelectionSignals.fromString("{\"buyer_signals\":0}")))
                        .setBuyerContextualAds(createContextualAds())
                        .build();

        String decisionLogicJs =
                "function scoreAd(ad, bid, auction_config, seller_signals,"
                        + " trusted_scoring_signals, contextual_signal, user_signal,"
                        + " custom_audience_signal) { \n"
                        + "  return {'status': 0, 'score': bid };\n"
                        + "}\n"
                        + "function reportResult(ad_selection_config, render_uri, bid,"
                        + " contextual_signals) { \n"
                        + " return {'status': 0, 'results': {'signals_for_buyer':"
                        + " '{\"signals_for_buyer\":1}', 'reporting_uri': '"
                        + sellerReportingUri
                        + "' } };\n"
                        + "}";

        String buyerDecisionLogic =
                "function reportWin(ad_selection_signals, per_buyer_signals,"
                        + " signals_for_buyer, contextual_signals, custom_audience_signals) { \n"
                        + " return {'status': 0, 'results': {'reporting_uri': '"
                        + buyerReportingUri
                        + "' } };\n"
                        + "}";

        CountDownLatch reportingResponseLatch = new CountDownLatch(2);

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> {
                            String versionHeaderName =
                                    JsVersionHelper.getVersionHeaderName(
                                            JsVersionHelper.JS_PAYLOAD_TYPE_BUYER_BIDDING_LOGIC_JS);
                            long jsVersion =
                                    JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3;
                            switch (request.getPath()) {
                                case SELLER_DECISION_LOGIC_URI_PATH:
                                    return new MockResponse().setBody(decisionLogicJs);
                                case BUYER_BIDDING_LOGIC_URI_PATH:
                                    return new MockResponse().setBody(buyerDecisionLogic);
                                case SELLER_REPORTING_PATH: // Intentional fallthrough
                                case BUYER_REPORTING_PATH:
                                    reportingResponseLatch.countDown();
                                    return new MockResponse().setResponseCode(200);
                            }

                            // The seller params vary based on runtime, so we are returning trusted
                            // signals based on correct path prefix
                            if (request.getPath()
                                    .startsWith(
                                            SELLER_TRUSTED_SIGNAL_URI_PATH
                                                    + SELLER_TRUSTED_SIGNAL_PARAMS)) {
                                return new MockResponse()
                                        .setBody(TRUSTED_SCORING_SIGNALS.toString());
                            }
                            if (request.getPath().startsWith(BUYER_TRUSTED_SIGNAL_URI_PATH)) {
                                return new MockResponse()
                                        .setBody(TRUSTED_BIDDING_SIGNALS.toString());
                            }
                            return new MockResponse().setResponseCode(404);
                        });

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(
                AdDataFixture.getValidRenderUriByBuyer(
                        AdTechIdentifier.fromString(
                                mMockWebServerRule
                                        .uriForPath(
                                                BUYER_BIDDING_LOGIC_URI_PATH
                                                        + CommonFixture.VALID_BUYER_1)
                                        .getHost()),
                        500),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        // Run Report Impression
        ReportImpressionInput reportImpressioninput =
                new ReportImpressionInput.Builder()
                        .setAdSelectionConfig(mAdSelectionConfig)
                        .setAdSelectionId(resultSelectionId)
                        .setCallerPackageName(CommonFixture.TEST_PACKAGE_NAME)
                        .build();

        ReportImpressionTestCallback reportImpressionTestCallback =
                callReportImpression(mAdSelectionService, reportImpressioninput);

        assertTrue(reportImpressionTestCallback.mIsSuccess);
        reportingResponseLatch.await();
        mMockWebServerRule.verifyMockServerRequests(
                server,
                6,
                ImmutableList.of(
                        SELLER_DECISION_LOGIC_URI_PATH,
                        BUYER_BIDDING_LOGIC_URI_PATH,
                        BUYER_REPORTING_PATH,
                        SELLER_REPORTING_PATH,
                        SELLER_TRUSTED_SIGNAL_URI_PATH + SELLER_TRUSTED_SIGNAL_PARAMS),
                mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithAppInstallWithMockServer() throws Exception {
        initClients(true, true);
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs =
                "function generateBid(ad, auction_signals, per_buyer_signals,"
                        + " trusted_bidding_signals, contextual_signals, custom_audience_signals) "
                        + "{ \n"
                        + "  return {'status': 0, 'ad': ad, 'bid': ad.metadata.result };\n"
                        + "}\n"
                        + "function reportWin(ad_selection_signals, per_buyer_signals,"
                        + " signals_for_buyer ,contextual_signals, custom_audience_signals) {\n"
                        + "    registerAdBeacon('click', '"
                        + mMockWebServerRule.uriForPath(CLICK_BUYER_PATH)
                        + "');\n"
                        + "    registerAdBeacon('hover', '"
                        + mMockWebServerRule.uriForPath(HOVER_BUYER_PATH)
                        + "');\n"
                        + " return {'status': 0, 'results': {'reporting_uri': '"
                        + mMockWebServerRule.uriForPath(BUYER_REPORTING_PATH)
                        + "' } };\n"
                        + "}";

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        List<AdFilters> adFiltersForCa2 =
                Arrays.asList(
                        null,
                        null,
                        new AdFilters.Builder().setAppInstallFilters(CURRENT_APP_FILTER).build());
        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain,
                        CUSTOM_AUDIENCE_SEQ_2,
                        BIDS_FOR_BUYER_2,
                        EMPTY_AD_COUNTER_KEYS_FOR_BUYER_2,
                        adFiltersForCa2);

        // We add permits to the semaphores when the MWS is called and remove them in the asserts
        Semaphore impressionReportingSemaphore = new Semaphore(0);
        Semaphore interactionReportingSemaphore = new Semaphore(0);

        MockWebServer server =
                getMockWebServer(
                        decisionLogicJs,
                        biddingLogicJs,
                        impressionReportingSemaphore,
                        interactionReportingSemaphore,
                        false);

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        registerForAppInstallFiltering();

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        // Run Ad Selection
        selectAdsAndReport(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad2"),
                impressionReportingSemaphore,
                interactionReportingSemaphore);
        verifyStandardServerRequests(server);
    }

    @Test
    public void testFledgeFlowSuccessWithAppInstallFlagOffWithMockServer() throws Exception {
        initClients(false, true, false, true);
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs =
                "function generateBid(ad, auction_signals, per_buyer_signals,"
                        + " trusted_bidding_signals, contextual_signals, custom_audience_signals) "
                        + "{ \n"
                        + "  return {'status': 0, 'ad': ad, 'bid': ad.metadata.result };\n"
                        + "}\n"
                        + "function reportWin(ad_selection_signals, per_buyer_signals,"
                        + " signals_for_buyer ,contextual_signals, custom_audience_signals) {\n"
                        + "    registerAdBeacon('click', '"
                        + mMockWebServerRule.uriForPath(CLICK_BUYER_PATH)
                        + "');\n"
                        + "    registerAdBeacon('hover', '"
                        + mMockWebServerRule.uriForPath(HOVER_BUYER_PATH)
                        + "');\n"
                        + " return {'status': 0, 'results': {'reporting_uri': '"
                        + mMockWebServerRule.uriForPath(BUYER_REPORTING_PATH)
                        + "' } };\n"
                        + "}";

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        List<AdFilters> filtersForCa2 =
                Arrays.asList(
                        null,
                        null,
                        new AdFilters.Builder().setAppInstallFilters(CURRENT_APP_FILTER).build());
        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain,
                        CUSTOM_AUDIENCE_SEQ_2,
                        BIDS_FOR_BUYER_2,
                        EMPTY_AD_COUNTER_KEYS_FOR_BUYER_2,
                        filtersForCa2);

        // We add permits to the semaphores when the MWS is called and remove them in the asserts
        Semaphore impressionReportingSemaphore = new Semaphore(0);
        Semaphore interactionReportingSemaphore = new Semaphore(0);

        MockWebServer server =
                getMockWebServer(
                        decisionLogicJs,
                        biddingLogicJs,
                        impressionReportingSemaphore,
                        interactionReportingSemaphore,
                        false);

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        // Registers the test app for app install filtering
        registerForAppInstallFiltering();

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        // CA 2's ad3 should win even though it tried to filter itself
        selectAdsAndReport(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_2 + "/ad3"),
                impressionReportingSemaphore,
                interactionReportingSemaphore);
        verifyStandardServerRequests(server);
    }

    @Test
    public void testFledgeFlowSuccessWithRevokedUserConsentForApp() throws Exception {
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        // Allow the first join call to succeed so that we can verify the rest of the flow works
        when(mConsentManagerMock.isFledgeConsentRevokedForAppAfterSettingFledgeUse(any()))
                .thenReturn(false)
                .thenReturn(true);

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs = getBiddingLogicWithBeacons();

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, BIDS_FOR_BUYER_2);

        // We add permits to the semaphores when the MWS is called and remove them in the asserts
        Semaphore impressionReportingSemaphore = new Semaphore(0);
        Semaphore interactionReportingSemaphore = new Semaphore(0);

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> {
                            switch (request.getPath()) {
                                case SELLER_DECISION_LOGIC_URI_PATH:
                                    return new MockResponse().setBody(decisionLogicJs);
                                case BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_1:
                                    return new MockResponse().setBody(biddingLogicJs);
                                case BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_2:
                                    throw new IllegalStateException(
                                            "This should not be called without user consent");
                                case CLICK_SELLER_PATH: // Intentional fallthrough
                                case CLICK_BUYER_PATH:
                                    interactionReportingSemaphore.release();
                                    return new MockResponse().setResponseCode(200);
                                case SELLER_REPORTING_PATH: // Intentional fallthrough
                                case BUYER_REPORTING_PATH:
                                    impressionReportingSemaphore.release();
                                    return new MockResponse().setResponseCode(200);
                            }

                            // The seller params vary based on runtime, so we are returning trusted
                            // signals based on correct path prefix
                            if (request.getPath()
                                    .startsWith(
                                            SELLER_TRUSTED_SIGNAL_URI_PATH
                                                    + SELLER_TRUSTED_SIGNAL_PARAMS)) {
                                return new MockResponse()
                                        .setBody(TRUSTED_SCORING_SIGNALS.toString());
                            }
                            if (request.getPath().startsWith(BUYER_TRUSTED_SIGNAL_URI_PATH)) {
                                return new MockResponse()
                                        .setBody(TRUSTED_BIDDING_SIGNALS.toString());
                            }
                            return new MockResponse().setResponseCode(404);
                        });

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        // Run Ad Selection
        selectAdsAndReport(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_1 + "/ad2"),
                impressionReportingSemaphore,
                interactionReportingSemaphore);
        mMockWebServerRule.verifyMockServerRequests(
                server,
                9,
                ImmutableList.of(
                        SELLER_DECISION_LOGIC_URI_PATH,
                        BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_1,
                        BUYER_TRUSTED_SIGNAL_URI_PATH,
                        SELLER_TRUSTED_SIGNAL_URI_PATH + SELLER_TRUSTED_SIGNAL_PARAMS),
                mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithRevokedUserConsentForFledge() throws Exception {
        doReturn(AdServicesApiConsent.REVOKED).when(mConsentManagerMock).getConsent();
        doReturn(true)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());
        doThrow(new FilterException(new ConsentManager.RevokedConsentException()))
                .when(mAdSelectionServiceFilter)
                .filterRequest(
                        any(), anyString(), anyBoolean(), anyBoolean(), anyInt(), anyInt(), any());

        setupAdSelectionConfig();

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, BIDS_FOR_BUYER_2);

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> {
                            throw new IllegalStateException(
                                    "No calls should be made without user consent");
                        });

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertFalse(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(Uri.EMPTY, resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultSelectionId);
        reportInteractionAndAssertSuccess(resultsCallback);

        mMockWebServerRule.verifyMockServerRequests(
                server, 0, ImmutableList.of(), mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowSuccessWithOneCAWithNegativeBidsWithMockServer() throws Exception {
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs = getBiddingLogicWithBeacons();

        CustomAudience customAudience1 =
                createCustomAudience(
                        mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_1, BIDS_FOR_BUYER_1);

        CustomAudience customAudience2 =
                createCustomAudience(mLocalhostBuyerDomain, CUSTOM_AUDIENCE_SEQ_2, INVALID_BIDS);

        // We add permits to the semaphores when the MWS is called and remove them in the asserts
        Semaphore impressionReportingSemaphore = new Semaphore(0);
        Semaphore interactionReportingSemaphore = new Semaphore(0);

        MockWebServer server =
                getMockWebServer(
                        decisionLogicJs,
                        biddingLogicJs,
                        impressionReportingSemaphore,
                        interactionReportingSemaphore,
                        false);

        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));

        joinCustomAudienceAndAssertSuccess(customAudience1);
        joinCustomAudienceAndAssertSuccess(customAudience2);

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));

        // Expect that ad from buyer 1 won since buyer 2 had negative bids
        assertEquals(
                CommonFixture.getUri(
                        mLocalhostBuyerDomain.getAuthority(),
                        AD_URI_PREFIX + CUSTOM_AUDIENCE_SEQ_1 + "/ad2"),
                resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultsCallback.mAdSelectionResponse.getAdSelectionId());

        reportInteractionAndAssertSuccess(resultsCallback);

        /* We add a permit on every call received by the MockWebServer and remove them in the
         * tryAcquires below. If there are any left over it means that there were extra calls to the
         * mockserver.
         */
        assertTrue(impressionReportingSemaphore.tryAcquire(2, 10, TimeUnit.SECONDS));
        assertTrue(interactionReportingSemaphore.tryAcquire(2, 10, TimeUnit.SECONDS));
        assertEquals(
                "Extra calls made to MockWebServer",
                0,
                impressionReportingSemaphore.availablePermits());
        assertEquals(
                "Extra calls made to MockWebServer",
                0,
                interactionReportingSemaphore.availablePermits());

        mMockWebServerRule.verifyMockServerRequests(
                server,
                10,
                ImmutableList.of(
                        SELLER_DECISION_LOGIC_URI_PATH,
                        BUYER_BIDDING_LOGIC_URI_PATH,
                        BUYER_TRUSTED_SIGNAL_URI_PATH,
                        SELLER_TRUSTED_SIGNAL_URI_PATH + SELLER_TRUSTED_SIGNAL_PARAMS),
                mRequestMatcherPrefixMatch);
    }

    @Test
    public void testFledgeFlowFailsWithOnlyCANegativeBidsWithMockServer() throws Exception {
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        setupAdSelectionConfig();
        String decisionLogicJs = getDecisionLogicWithBeacons();
        String biddingLogicJs = getBiddingLogicWithBeacons();

        CustomAudience customAudience1 = createCustomAudience(mLocalhostBuyerDomain, INVALID_BIDS);

        MockWebServer server =
                mMockWebServerRule.startMockWebServer(
                        request -> {
                            switch (request.getPath()) {
                                case SELLER_DECISION_LOGIC_URI_PATH:
                                    return new MockResponse().setBody(decisionLogicJs);
                                case BUYER_BIDDING_LOGIC_URI_PATH:
                                    return new MockResponse().setBody(biddingLogicJs);
                            }

                            // The seller params vary based on runtime, so we are returning trusted
                            // signals based on correct path prefix
                            if (request.getPath()
                                    .startsWith(
                                            SELLER_TRUSTED_SIGNAL_URI_PATH
                                                    + SELLER_TRUSTED_SIGNAL_PARAMS)) {
                                return new MockResponse()
                                        .setBody(TRUSTED_SCORING_SIGNALS.toString());
                            }
                            if (request.getPath().startsWith(BUYER_TRUSTED_SIGNAL_URI_PATH)) {
                                return new MockResponse()
                                        .setBody(TRUSTED_BIDDING_SIGNALS.toString());
                            }
                            return new MockResponse().setResponseCode(404);
                        });

        // Join custom audience
        joinCustomAudienceAndAssertSuccess(customAudience1);

        // Run Ad Selection
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertFalse(resultsCallback.mIsSuccess);
        assertNull(resultsCallback.mAdSelectionResponse);

        // Run Report Impression with random ad selection id
        ReportImpressionInput input =
                new ReportImpressionInput.Builder()
                        .setAdSelectionConfig(mAdSelectionConfig)
                        .setAdSelectionId(1)
                        .setCallerPackageName(CommonFixture.TEST_PACKAGE_NAME)
                        .build();

        ReportImpressionTestCallback reportImpressionTestCallback =
                callReportImpression(mAdSelectionService, input);

        assertFalse(reportImpressionTestCallback.mIsSuccess);

        // Run Report Interaction with random ad selection id
        ReportInteractionInput reportInteractionInput =
                new ReportInteractionInput.Builder()
                        .setAdSelectionId(1)
                        .setInteractionKey(CLICK_INTERACTION)
                        .setInteractionData(INTERACTION_DATA)
                        .setCallerPackageName(CommonFixture.TEST_PACKAGE_NAME)
                        .setReportingDestinations(BUYER_DESTINATION | SELLER_DESTINATION)
                        .build();

        ReportInteractionTestCallback reportInteractionTestCallback =
                callReportInteraction(mAdSelectionService, reportInteractionInput);
        assertFalse(reportInteractionTestCallback.mIsSuccess);

        mMockWebServerRule.verifyMockServerRequests(
                server,
                2,
                ImmutableList.of(BUYER_BIDDING_LOGIC_URI_PATH, BUYER_TRUSTED_SIGNAL_URI_PATH),
                mRequestMatcherPrefixMatch);
    }

    private void updateHistogramAndAssertSuccess(long adSelectionId, int adEventType)
            throws InterruptedException {
        UpdateAdCounterHistogramInput inputParams =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(adSelectionId)
                        .setAdEventType(adEventType)
                        .setCallerAdTech(
                                AdTechIdentifier.fromString(mLocalhostBuyerDomain.getHost()))
                        .setCallerPackageName(CommonFixture.TEST_PACKAGE_NAME)
                        .build();
        CountDownLatch callbackLatch = new CountDownLatch(1);
        UpdateAdCounterHistogramWorkerTest.UpdateAdCounterHistogramTestCallback callback =
                new UpdateAdCounterHistogramWorkerTest.UpdateAdCounterHistogramTestCallback(
                        callbackLatch);

        mAdSelectionService.updateAdCounterHistogram(inputParams, callback);

        assertThat(callbackLatch.await(5, TimeUnit.SECONDS)).isTrue();

        assertWithMessage("Callback failed, was: %s", callback).that(callback.isSuccess()).isTrue();
    }

    private long selectAdsAndReport(
            Uri winningRenderUri,
            Semaphore impressionReportingSemaphore,
            Semaphore interactionReportingSemaphore)
            throws Exception {
        AdSelectionTestCallback resultsCallback =
                invokeRunAdSelection(
                        mAdSelectionService, mAdSelectionConfig, CommonFixture.TEST_PACKAGE_NAME);

        assertTrue(resultsCallback.mIsSuccess);
        long resultSelectionId = resultsCallback.mAdSelectionResponse.getAdSelectionId();
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(resultSelectionId));
        assertEquals(winningRenderUri, resultsCallback.mAdSelectionResponse.getRenderUri());

        reportImpressionAndAssertSuccess(resultSelectionId);
        reportInteractionAndAssertSuccess(resultsCallback);

        /* We add a permit on every call received by the MockWebServer and remove them in the
         * tryAcquires below. If there are any left over it means that there were extra calls to the
         * mockserver.
         */
        assertTrue(impressionReportingSemaphore.tryAcquire(2, 10, TimeUnit.SECONDS));
        assertTrue(interactionReportingSemaphore.tryAcquire(2, 10, TimeUnit.SECONDS));
        assertEquals(
                "Extra calls made to MockWebServer",
                0,
                impressionReportingSemaphore.availablePermits());
        assertEquals(
                "Extra calls made to MockWebServer",
                0,
                interactionReportingSemaphore.availablePermits());
        return resultSelectionId;
    }

    void verifyStandardServerRequests(MockWebServer server) {
        /*
         * We expect ten requests:
         * 2 bidding logic requests (one for each CA)
         * 2 decision logic requests (scoring and reporting)
         * 1 trusted bidding signals requests
         * 1 trusted seller signals request
         * 1 reportWin
         * 1 reportResult
         * 1 buyer click interaction report
         * 1 seller click interaction report
         */
        mMockWebServerRule.verifyMockServerRequests(
                server,
                10,
                ImmutableList.of(
                        SELLER_DECISION_LOGIC_URI_PATH,
                        BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_1,
                        BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_2,
                        BUYER_TRUSTED_SIGNAL_URI_PATH,
                        SELLER_TRUSTED_SIGNAL_URI_PATH + SELLER_TRUSTED_SIGNAL_PARAMS),
                mRequestMatcherPrefixMatch);
    }

    private void registerForAppInstallFiltering() throws RemoteException, InterruptedException {
        setAppInstallAdvertisers(
                Collections.singleton(
                        AdTechIdentifier.fromString(mLocalhostBuyerDomain.getHost())));
    }

    private void deregisterForAppInstallFiltering() throws RemoteException, InterruptedException {
        setAppInstallAdvertisers(Collections.EMPTY_SET);
    }

    private void setAppInstallAdvertisers(Set<AdTechIdentifier> advertisers)
            throws RemoteException, InterruptedException {
        SetAppInstallAdvertisersInput setAppInstallAdvertisersInput =
                new SetAppInstallAdvertisersInput.Builder()
                        .setAdvertisers(advertisers)
                        .setCallerPackageName(CommonFixture.TEST_PACKAGE_NAME)
                        .build();
        CountDownLatch appInstallDone = new CountDownLatch(1);
        AppInstallResultCapturingCallback appInstallCallback =
                new AppInstallResultCapturingCallback(appInstallDone);
        mAdSelectionService.setAppInstallAdvertisers(
                setAppInstallAdvertisersInput, appInstallCallback);
        assertTrue(appInstallDone.await(5, TimeUnit.SECONDS));
        assertTrue(
                "App Install call failed with: " + appInstallCallback.getException(),
                appInstallCallback.isSuccess());
    }

    private String getV3BiddingLogicJs() {
        return "function generateBid(custom_audience, auction_signals, per_buyer_signals,\n"
                + "    trusted_bidding_signals, contextual_signals) {\n"
                + "    const ads = custom_audience.ads;\n"
                + "    let result = null;\n"
                + "    for (const ad of ads) {\n"
                + "        if (!result || ad.metadata.result > result.metadata.result) {\n"
                + "            result = ad;\n"
                + "        }\n"
                + "    }\n"
                + "    return { 'status': 0, 'ad': result, 'bid': result.metadata.result, "
                + "'render': result.render_uri };\n"
                + "}\n"
                + "function reportWin(ad_selection_signals, per_buyer_signals,"
                + " signals_for_buyer ,contextual_signals, custom_audience_signals) {\n"
                + "    registerAdBeacon('click', '"
                + mMockWebServerRule.uriForPath(CLICK_BUYER_PATH)
                + "');\n"
                + "    registerAdBeacon('hover', '"
                + mMockWebServerRule.uriForPath(HOVER_BUYER_PATH)
                + "');\n"
                + " return {'status': 0, 'results': {'reporting_uri': '"
                + mMockWebServerRule.uriForPath(BUYER_REPORTING_PATH)
                + "' } };\n"
                + "}";
    }

    private String getBiddingLogicJs() {
        return "function generateBid(ad, auction_signals, per_buyer_signals,"
                + " trusted_bidding_signals, contextual_signals, custom_audience_signals) { \n"
                + "  return {'status': 0, 'ad': ad, 'bid': ad.metadata.result };\n"
                + "}\n"
                + "function reportWin(ad_selection_signals, per_buyer_signals,"
                + " signals_for_buyer, contextual_signals, custom_audience_signals) { \n"
                + " return {'status': 0, 'results': {'reporting_uri': '"
                + mMockWebServerRule.uriForPath(BUYER_REPORTING_PATH)
                + "' } };\n"
                + "}";
    }

    private String getDecisionLogicJs() {
        return "function scoreAd(ad, bid, auction_config, seller_signals,"
                + " trusted_scoring_signals, contextual_signal, user_signal,"
                + " custom_audience_signal) { \n"
                + "  return {'status': 0, 'score': bid };\n"
                + "}\n"
                + "function reportResult(ad_selection_config, render_uri, bid,"
                + " contextual_signals) { \n"
                + " return {'status': 0, 'results': {'signals_for_buyer':"
                + " '{\"signals_for_buyer\":1}', 'reporting_uri': '"
                + mMockWebServerRule.uriForPath(SELLER_REPORTING_PATH)
                + "' } };\n"
                + "}";
    }

    private String getBiddingLogicWithBeacons() {
        return "function generateBid(ad, auction_signals, per_buyer_signals,"
                + " trusted_bidding_signals, contextual_signals, custom_audience_signals) { \n"
                + "  return {'status': 0, 'ad': ad, 'bid': ad.metadata.result };\n"
                + "}\n"
                + "function reportWin(ad_selection_signals, per_buyer_signals,"
                + " signals_for_buyer ,contextual_signals, custom_audience_signals) {\n"
                + "    registerAdBeacon('click', '"
                + mMockWebServerRule.uriForPath(CLICK_BUYER_PATH)
                + "');\n"
                + "    registerAdBeacon('hover', '"
                + mMockWebServerRule.uriForPath(HOVER_BUYER_PATH)
                + "');\n"
                + " return {'status': 0, 'results': {'reporting_uri': '"
                + mMockWebServerRule.uriForPath(BUYER_REPORTING_PATH)
                + "' } };\n"
                + "}";
    }

    private String getDecisionLogicWithBeacons() {
        return "function scoreAd(ad, bid, auction_config, seller_signals,"
                + " trusted_scoring_signals, contextual_signal, user_signal,"
                + " custom_audience_signal) { \n"
                + "  return {'status': 0, 'score': bid };\n"
                + "}\n"
                + "function reportResult(ad_selection_config, render_uri, bid,"
                + " contextual_signals) {\n"
                + "    registerAdBeacon('click', '"
                + mMockWebServerRule.uriForPath(CLICK_SELLER_PATH)
                + "');\n"
                + "    registerAdBeacon('hover', '"
                + mMockWebServerRule.uriForPath(HOVER_SELLER_PATH)
                + "');\n"
                + " return {'status': 0, 'results': {'signals_for_buyer':"
                + " '{\"signals_for_buyer\":1}', 'reporting_uri': '"
                + mMockWebServerRule.uriForPath(SELLER_REPORTING_PATH)
                + "' } };\n"
                + "}";
    }

    private MockWebServer getMockWebServer(
            String decisionLogicJs,
            String biddingLogicJs,
            Semaphore impressionReportingSemaphore,
            Semaphore interactionReportingSemaphore,
            boolean jsVersioning)
            throws Exception {
        String versionHeaderName =
                JsVersionHelper.getVersionHeaderName(
                        JsVersionHelper.JS_PAYLOAD_TYPE_BUYER_BIDDING_LOGIC_JS);
        long jsVersion = JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3;
        return mMockWebServerRule.startMockWebServer(
                request -> {
                    switch (request.getPath()) {
                        case SELLER_DECISION_LOGIC_URI_PATH:
                            return new MockResponse().setBody(decisionLogicJs);
                        case BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_1:
                        case BUYER_BIDDING_LOGIC_URI_PATH + CUSTOM_AUDIENCE_SEQ_2:
                            if (jsVersioning) {
                                if (Objects.equals(
                                        request.getHeader(versionHeaderName),
                                        Long.toString(jsVersion))) {
                                    return new MockResponse()
                                            .setBody(biddingLogicJs)
                                            .setHeader(versionHeaderName, jsVersion);
                                }
                                break;
                            } else {
                                return new MockResponse().setBody(biddingLogicJs);
                            }
                        case CLICK_SELLER_PATH: // Intentional fallthrough
                        case CLICK_BUYER_PATH:
                            interactionReportingSemaphore.release();
                            return new MockResponse().setResponseCode(200);
                        case SELLER_REPORTING_PATH: // Intentional fallthrough
                        case BUYER_REPORTING_PATH:
                            impressionReportingSemaphore.release();
                            return new MockResponse().setResponseCode(200);
                    }

                    // The seller params vary based on runtime, so we are returning trusted
                    // signals based on correct path prefix
                    if (request.getPath()
                            .startsWith(
                                    SELLER_TRUSTED_SIGNAL_URI_PATH
                                            + SELLER_TRUSTED_SIGNAL_PARAMS)) {
                        return new MockResponse().setBody(TRUSTED_SCORING_SIGNALS.toString());
                    }
                    if (request.getPath().startsWith(BUYER_TRUSTED_SIGNAL_URI_PATH)) {
                        return new MockResponse().setBody(TRUSTED_BIDDING_SIGNALS.toString());
                    }
                    return new MockResponse().setResponseCode(404);
                });
    }

    private void reportInteractionAndAssertSuccess(AdSelectionTestCallback resultsCallback)
            throws Exception {
        ReportInteractionInput reportInteractionInput =
                new ReportInteractionInput.Builder()
                        .setAdSelectionId(resultsCallback.mAdSelectionResponse.getAdSelectionId())
                        .setInteractionKey(CLICK_INTERACTION)
                        .setInteractionData(INTERACTION_DATA)
                        .setCallerPackageName(CommonFixture.TEST_PACKAGE_NAME)
                        .setReportingDestinations(BUYER_DESTINATION | SELLER_DESTINATION)
                        .build();

        ReportInteractionTestCallback reportInteractionTestCallback =
                callReportInteraction(mAdSelectionService, reportInteractionInput);
        assertTrue(reportInteractionTestCallback.mIsSuccess);
    }

    private void reportOnlyBuyerInteractionAndAssertSuccess(AdSelectionTestCallback resultsCallback)
            throws Exception {
        ReportInteractionInput reportInteractionInput =
                new ReportInteractionInput.Builder()
                        .setAdSelectionId(resultsCallback.mAdSelectionResponse.getAdSelectionId())
                        .setInteractionKey(CLICK_INTERACTION)
                        .setInteractionData(INTERACTION_DATA)
                        .setCallerPackageName(CommonFixture.TEST_PACKAGE_NAME)
                        .setReportingDestinations(BUYER_DESTINATION)
                        .build();

        ReportInteractionTestCallback reportInteractionTestCallback =
                callReportInteraction(mAdSelectionService, reportInteractionInput);
        assertTrue(reportInteractionTestCallback.mIsSuccess);
    }

    private void reportImpressionAndAssertSuccess(long adSelectionId) throws Exception {
        ReportImpressionInput input =
                new ReportImpressionInput.Builder()
                        .setAdSelectionConfig(mAdSelectionConfig)
                        .setAdSelectionId(adSelectionId)
                        .setCallerPackageName(CommonFixture.TEST_PACKAGE_NAME)
                        .build();

        ReportImpressionTestCallback reportImpressionTestCallback =
                callReportImpression(mAdSelectionService, input);

        assertTrue(reportImpressionTestCallback.mIsSuccess);
    }

    private void setupOverridesAndAssertSuccess(
            CustomAudience customAudience1,
            CustomAudience customAudience2,
            String biddingLogicJs,
            String decisionLogicJs)
            throws Exception {
        // Add AdSelection Override
        AdSelectionOverrideTestCallback adSelectionOverrideTestCallback =
                callAddAdSelectionOverride(
                        mAdSelectionService,
                        mAdSelectionConfig,
                        decisionLogicJs,
                        TRUSTED_SCORING_SIGNALS,
                        BUYERS_DECISION_LOGIC);

        assertTrue(adSelectionOverrideTestCallback.mIsSuccess);

        // Add Custom Audience Overrides
        CustomAudienceOverrideTestCallback customAudienceOverrideTestCallback1 =
                callAddCustomAudienceOverride(
                        CommonFixture.TEST_PACKAGE_NAME,
                        customAudience1.getBuyer(),
                        customAudience1.getName(),
                        biddingLogicJs,
                        null,
                        TRUSTED_BIDDING_SIGNALS,
                        mCustomAudienceService);

        assertTrue(customAudienceOverrideTestCallback1.mIsSuccess);

        CustomAudienceOverrideTestCallback customAudienceOverrideTestCallback2 =
                callAddCustomAudienceOverride(
                        CommonFixture.TEST_PACKAGE_NAME,
                        customAudience2.getBuyer(),
                        customAudience2.getName(),
                        biddingLogicJs,
                        null,
                        TRUSTED_BIDDING_SIGNALS,
                        mCustomAudienceService);

        assertTrue(customAudienceOverrideTestCallback2.mIsSuccess);
    }

    private void setupAdSelectionConfig() {
        mAdSelectionConfig =
                AdSelectionConfigFixture.anAdSelectionConfigBuilder()
                        .setCustomAudienceBuyers(
                                ImmutableList.of(
                                        AdTechIdentifier.fromString(
                                                mLocalhostBuyerDomain.getHost())))
                        .setSeller(
                                AdTechIdentifier.fromString(
                                        mMockWebServerRule
                                                .uriForPath(SELLER_DECISION_LOGIC_URI_PATH)
                                                .getHost()))
                        .setDecisionLogicUri(
                                mMockWebServerRule.uriForPath(SELLER_DECISION_LOGIC_URI_PATH))
                        .setTrustedScoringSignalsUri(
                                mMockWebServerRule.uriForPath(SELLER_TRUSTED_SIGNAL_URI_PATH))
                        .setPerBuyerSignals(
                                ImmutableMap.of(
                                        AdTechIdentifier.fromString(
                                                mLocalhostBuyerDomain.getHost()),
                                        AdSelectionSignals.fromString("{\"buyer_signals\":0}")))
                        .build();
    }

    private void setupConsentGivenStubs() {
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());
    }

    private void joinCustomAudienceAndAssertSuccess(CustomAudience ca) {
        ResultCapturingCallback joinCallback = new ResultCapturingCallback();
        mCustomAudienceService.joinCustomAudience(
                ca, CommonFixture.TEST_PACKAGE_NAME, joinCallback);
        assertTrue(joinCallback.isSuccess());
    }

    private void initClients(boolean gaUXEnabled, boolean registerAdBeaconEnabled) {
        initClients(gaUXEnabled, registerAdBeaconEnabled, true, true);
    }

    private void initClients(
            boolean gaUXEnabled,
            boolean registerAdBeaconEnabled,
            boolean filtersEnabled,
            boolean enrollmentCheckDisabled) {
        Flags flags =
                new FledgeE2ETestFlags(
                        gaUXEnabled,
                        registerAdBeaconEnabled,
                        filtersEnabled,
                        enrollmentCheckDisabled);

        mCustomAudienceService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        new CustomAudienceImpl(
                                mCustomAudienceDao,
                                new CustomAudienceQuantityChecker(
                                        mCustomAudienceDao, DEFAULT_FLAGS),
                                new CustomAudienceValidator(
                                        CommonFixture.FIXED_CLOCK_TRUNCATED_TO_MILLI,
                                        DEFAULT_FLAGS),
                                CommonFixture.FIXED_CLOCK_TRUNCATED_TO_MILLI,
                                DEFAULT_FLAGS),
                        mFledgeAuthorizationFilterMock,
                        mConsentManagerMock,
                        mDevContextFilter,
                        MoreExecutors.newDirectExecutorService(),
                        mAdServicesLogger,
                        mAppImportanceFilter,
                        flags,
                        CallingAppUidSupplierProcessImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                flags,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilterMock,
                                mFledgeAllowListsFilterSpy,
                                mThrottlerSupplier));

        when(mDevContextFilter.createDevContext())
                .thenReturn(DevContext.createForDevOptionsDisabled());
        mAdFilteringFeatureFactory =
                new AdFilteringFeatureFactory(mAppInstallDao, mFrequencyCapDao, flags);

        // Create an instance of AdSelection Service with real dependencies
        mAdSelectionService =
                new AdSelectionServiceImpl(
                        mAdSelectionEntryDao,
                        mAppInstallDao,
                        mCustomAudienceDao,
                        mFrequencyCapDao,
                        mAdServicesHttpsClient,
                        mDevContextFilter,
                        mLightweightExecutorService,
                        mBackgroundExecutorService,
                        mScheduledExecutor,
                        CONTEXT,
                        mAdServicesLogger,
                        flags,
                        CallingAppUidSupplierProcessImpl.create(),
                        mFledgeAuthorizationFilterMock,
                        mAdSelectionServiceFilter,
                        mAdFilteringFeatureFactory,
                        mConsentManagerMock);
    }

    private AdSelectionTestCallback invokeRunAdSelection(
            AdSelectionServiceImpl adSelectionService,
            AdSelectionConfig adSelectionConfig,
            String callerPackageName)
            throws InterruptedException {

        CountDownLatch countdownLatch = new CountDownLatch(1);
        AdSelectionTestCallback adSelectionTestCallback =
                new AdSelectionTestCallback(countdownLatch);

        AdSelectionInput input =
                new AdSelectionInput.Builder()
                        .setAdSelectionConfig(adSelectionConfig)
                        .setCallerPackageName(callerPackageName)
                        .build();
        CallerMetadata callerMetadata =
                new CallerMetadata.Builder()
                        .setBinderElapsedTimestamp(BINDER_ELAPSED_TIMESTAMP)
                        .build();
        adSelectionService.selectAds(input, callerMetadata, adSelectionTestCallback);
        adSelectionTestCallback.mCountDownLatch.await();
        return adSelectionTestCallback;
    }

    private AdSelectionOverrideTestCallback callAddAdSelectionOverride(
            AdSelectionServiceImpl adSelectionService,
            AdSelectionConfig adSelectionConfig,
            String decisionLogicJS,
            AdSelectionSignals trustedScoringSignals,
            BuyersDecisionLogic buyerDecisionLogicMap)
            throws Exception {
        // Counted down in 1) callback
        CountDownLatch resultLatch = new CountDownLatch(1);
        AdSelectionOverrideTestCallback callback = new AdSelectionOverrideTestCallback(resultLatch);

        adSelectionService.overrideAdSelectionConfigRemoteInfo(
                adSelectionConfig,
                decisionLogicJS,
                trustedScoringSignals,
                buyerDecisionLogicMap,
                callback);
        resultLatch.await();
        return callback;
    }

    private CustomAudienceOverrideTestCallback callAddCustomAudienceOverride(
            String owner,
            AdTechIdentifier buyer,
            String name,
            String biddingLogicJs,
            Long biddingLogicJsVersion,
            AdSelectionSignals trustedBiddingData,
            CustomAudienceServiceImpl customAudienceService)
            throws Exception {
        CountDownLatch resultLatch = new CountDownLatch(1);
        CustomAudienceOverrideTestCallback callback =
                new CustomAudienceOverrideTestCallback(resultLatch);

        customAudienceService.overrideCustomAudienceRemoteInfo(
                owner,
                buyer,
                name,
                biddingLogicJs,
                Optional.ofNullable(biddingLogicJsVersion).orElse(0L),
                trustedBiddingData,
                callback);
        resultLatch.await();
        return callback;
    }

    private ReportImpressionTestCallback callReportImpression(
            AdSelectionServiceImpl adSelectionService, ReportImpressionInput requestParams)
            throws Exception {
        // Counted down in 1) callback
        CountDownLatch resultLatch = new CountDownLatch(1);
        ReportImpressionTestCallback callback = new ReportImpressionTestCallback(resultLatch);

        adSelectionService.reportImpression(requestParams, callback);
        resultLatch.await();
        return callback;
    }

    private ReportInteractionTestCallback callReportInteraction(
            AdSelectionServiceImpl adSelectionService, ReportInteractionInput inputParams)
            throws Exception {
        // Counted down in 1) callback
        CountDownLatch resultLatch = new CountDownLatch(1);
        ReportInteractionTestCallback callback = new ReportInteractionTestCallback(resultLatch);

        adSelectionService.reportInteraction(inputParams, callback);
        resultLatch.await();
        return callback;
    }

    /** See {@link #createCustomAudience(Uri, String, List)}. */
    private CustomAudience createCustomAudience(final Uri buyerDomain, List<Double> bids) {
        return createCustomAudience(buyerDomain, "", bids);
    }

    private CustomAudience createCustomAudience(
            final Uri buyerDomain, final String customAudienceSeq, List<Double> bids) {
        return createCustomAudience(buyerDomain, customAudienceSeq, bids, null, null);
    }

    /**
     * @param buyerDomain The name of the buyer for this Custom Audience
     * @param customAudienceSeq optional numbering for ca name. Should start with slash.
     * @param bids these bids, are added to its metadata. Our JS logic then picks this value and
     *     creates ad with the provided value as bid
     * @param filtersForBids A parallel list to bids with the filter that should be added to each
     *     Ad. Can be left null.
     * @param adCounterKeysForBids A parallel list to bids with the adCounterKeys that should be
     *     added to each Ad. Can be left null.
     * @return a real Custom Audience object that can be persisted and used in bidding and scoring
     */
    private CustomAudience createCustomAudience(
            final Uri buyerDomain,
            final String customAudienceSeq,
            List<Double> bids,
            List<Set<String>> adCounterKeysForBids,
            List<AdFilters> filtersForBids) {

        // Generate ads for with bids provided
        List<AdData> ads = new ArrayList<>();

        // Create ads with the buyer name and bid number as the ad URI
        // Add the bid value to the metadata
        for (int i = 0; i < bids.size(); i++) {
            AdData.Builder builder =
                    new AdData.Builder()
                            .setRenderUri(
                                    CommonFixture.getUri(
                                            buyerDomain.getAuthority(),
                                            AD_URI_PREFIX + customAudienceSeq + "/ad" + (i + 1)))
                            .setMetadata("{\"result\":" + bids.get(i) + "}");
            if (adCounterKeysForBids != null && adCounterKeysForBids.get(i) != null) {
                builder.setAdCounterKeys(adCounterKeysForBids.get(i));
            }
            if (filtersForBids != null) {
                builder.setAdFilters(filtersForBids.get(i));
            }
            ads.add(builder.build());
        }

        return new CustomAudience.Builder()
                .setBuyer(AdTechIdentifier.fromString(buyerDomain.getHost()))
                .setName(
                        buyerDomain.getHost()
                                + customAudienceSeq
                                + CustomAudienceFixture.VALID_NAME)
                .setActivationTime(CustomAudienceFixture.VALID_ACTIVATION_TIME)
                .setExpirationTime(CustomAudienceFixture.VALID_EXPIRATION_TIME)
                .setDailyUpdateUri(
                        CustomAudienceFixture.getValidDailyUpdateUriByBuyer(
                                AdTechIdentifier.fromString(buyerDomain.getAuthority())))
                .setUserBiddingSignals(CustomAudienceFixture.VALID_USER_BIDDING_SIGNALS)
                .setTrustedBiddingData(
                        new TrustedBiddingData.Builder()
                                .setTrustedBiddingUri(
                                        CommonFixture.getUri(
                                                buyerDomain.getAuthority(),
                                                BUYER_TRUSTED_SIGNAL_URI_PATH))
                                .setTrustedBiddingKeys(
                                        TrustedBiddingDataFixture.getValidTrustedBiddingKeys())
                                .build())
                .setBiddingLogicUri(
                        CommonFixture.getUri(
                                buyerDomain.getAuthority(),
                                BUYER_BIDDING_LOGIC_URI_PATH + customAudienceSeq))
                .setAds(ads)
                .build();
    }

    private static class ResultCapturingCallback implements ICustomAudienceCallback {
        private boolean mIsSuccess;
        private Exception mException;

        public boolean isSuccess() {
            return mIsSuccess;
        }

        public Exception getException() {
            return mException;
        }

        @Override
        public void onSuccess() throws RemoteException {
            mIsSuccess = true;
        }

        @Override
        public void onFailure(FledgeErrorResponse responseParcel) throws RemoteException {
            mIsSuccess = false;
            mException = AdServicesStatusUtils.asException(responseParcel);
        }

        @Override
        public IBinder asBinder() {
            throw new RuntimeException("Should not be called.");
        }
    }

    private static class AppInstallResultCapturingCallback
            implements SetAppInstallAdvertisersCallback {
        private boolean mIsSuccess;
        private Exception mException;
        private final CountDownLatch mCountDownLatch;

        public boolean isSuccess() {
            return mIsSuccess;
        }

        public Exception getException() {
            return mException;
        }

        AppInstallResultCapturingCallback(CountDownLatch countDownLatch) {
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onSuccess() throws RemoteException {
            mIsSuccess = true;
            mCountDownLatch.countDown();
        }

        @Override
        public void onFailure(FledgeErrorResponse responseParcel) throws RemoteException {
            mIsSuccess = false;
            mException = AdServicesStatusUtils.asException(responseParcel);
            mCountDownLatch.countDown();
        }

        @Override
        public IBinder asBinder() {
            throw new RuntimeException("Should not be called.");
        }
    }

    static class AdSelectionTestCallback extends AdSelectionCallback.Stub {

        final CountDownLatch mCountDownLatch;
        boolean mIsSuccess = false;
        AdSelectionResponse mAdSelectionResponse;
        FledgeErrorResponse mFledgeErrorResponse;

        AdSelectionTestCallback(CountDownLatch countDownLatch) {
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

    public static class AdSelectionOverrideTestCallback extends AdSelectionOverrideCallback.Stub {
        private final CountDownLatch mCountDownLatch;
        boolean mIsSuccess = false;
        FledgeErrorResponse mFledgeErrorResponse;

        public AdSelectionOverrideTestCallback(CountDownLatch countDownLatch) {
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onSuccess() throws RemoteException {
            mIsSuccess = true;
            mCountDownLatch.countDown();
        }

        @Override
        public void onFailure(FledgeErrorResponse fledgeErrorResponse) throws RemoteException {
            mFledgeErrorResponse = fledgeErrorResponse;
            mCountDownLatch.countDown();
        }
    }

    public static class CustomAudienceOverrideTestCallback
            extends CustomAudienceOverrideCallback.Stub {
        private final CountDownLatch mCountDownLatch;
        boolean mIsSuccess = false;
        FledgeErrorResponse mFledgeErrorResponse;

        public CustomAudienceOverrideTestCallback(CountDownLatch countDownLatch) {
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onSuccess() throws RemoteException {
            mIsSuccess = true;
            mCountDownLatch.countDown();
        }

        @Override
        public void onFailure(FledgeErrorResponse fledgeErrorResponse) throws RemoteException {
            mFledgeErrorResponse = fledgeErrorResponse;
            mCountDownLatch.countDown();
        }
    }

    public static class ReportImpressionTestCallback extends ReportImpressionCallback.Stub {
        private final CountDownLatch mCountDownLatch;
        boolean mIsSuccess = false;
        FledgeErrorResponse mFledgeErrorResponse;

        public ReportImpressionTestCallback(CountDownLatch countDownLatch) {
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onSuccess() throws RemoteException {
            mIsSuccess = true;
            mCountDownLatch.countDown();
        }

        @Override
        public void onFailure(FledgeErrorResponse fledgeErrorResponse) throws RemoteException {
            mFledgeErrorResponse = fledgeErrorResponse;
            mCountDownLatch.countDown();
        }
    }

    static class ReportInteractionTestCallback extends ReportInteractionCallback.Stub {
        private final CountDownLatch mCountDownLatch;
        boolean mIsSuccess = false;
        FledgeErrorResponse mFledgeErrorResponse;

        ReportInteractionTestCallback(CountDownLatch countDownLatch) {
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onSuccess() throws RemoteException {
            mIsSuccess = true;
            mCountDownLatch.countDown();
        }

        @Override
        public void onFailure(FledgeErrorResponse fledgeErrorResponse) throws RemoteException {
            mFledgeErrorResponse = fledgeErrorResponse;
            mCountDownLatch.countDown();
        }
    }

    private Map<AdTechIdentifier, ContextualAds> createContextualAds() {
        Map<AdTechIdentifier, ContextualAds> buyerContextualAds = new HashMap<>();

        // In order to meet ETLd+1 requirements creating Contextual ads with MockWebserver's host
        AdTechIdentifier buyer =
                AdTechIdentifier.fromString(
                        mMockWebServerRule.uriForPath(BUYER_BIDDING_LOGIC_URI_PATH).getHost());
        ContextualAds contextualAds =
                ContextualAdsFixture.generateContextualAds(
                                buyer, ImmutableList.of(100.0, 200.0, 300.0, 400.0, 500.0))
                        .setDecisionLogicUri(
                                mMockWebServerRule.uriForPath(BUYER_BIDDING_LOGIC_URI_PATH))
                        .build();
        buyerContextualAds.put(buyer, contextualAds);

        return buyerContextualAds;
    }

    private static class FledgeE2ETestFlags implements Flags {
        private final boolean mIsGaUxEnabled;
        private final boolean mRegisterAdBeaconEnabled;
        private final boolean mFiltersEnabled;
        private final boolean mEnrollmentCheckDisabled;

        FledgeE2ETestFlags(
                boolean isGaUxEnabled,
                boolean registerAdBeaconEnabled,
                boolean filtersEnabled,
                boolean enrollmentCheckDisabled) {
            mIsGaUxEnabled = isGaUxEnabled;
            mRegisterAdBeaconEnabled = registerAdBeaconEnabled;
            mFiltersEnabled = filtersEnabled;
            mEnrollmentCheckDisabled = enrollmentCheckDisabled;
        }

        @Override
        public long getAdSelectionBiddingTimeoutPerCaMs() {
            return 10000;
        }

        @Override
        public long getAdSelectionScoringTimeoutMs() {
            return 10000;
        }

        @Override
        public long getAdSelectionOverallTimeoutMs() {
            return 300000;
        }

        @Override
        public boolean getEnforceIsolateMaxHeapSize() {
            return false;
        }

        @Override
        public boolean getGaUxFeatureEnabled() {
            return mIsGaUxEnabled;
        }

        @Override
        public float getSdkRequestPermitsPerSecond() {
            // Unlimited rate for unit tests to avoid flake in tests due to rate limiting
            return -1;
        }

        @Override
        public boolean getDisableFledgeEnrollmentCheck() {
            return mEnrollmentCheckDisabled;
        }

        @Override
        public boolean getFledgeRegisterAdBeaconEnabled() {
            return mRegisterAdBeaconEnabled;
        }

        @Override
        public boolean getFledgeAdSelectionFilteringEnabled() {
            return mFiltersEnabled;
        }

        @Override
        public boolean getFledgeAdSelectionContextualAdsEnabled() {
            return true;
        }

        @Override
        public long getFledgeAdSelectionBiddingLogicJsVersion() {
            return JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3;
        }
    }
}
