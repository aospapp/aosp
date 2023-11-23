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

package com.android.adservices.service.topics;

import static android.adservices.common.AdServicesPermissions.ACCESS_ADSERVICES_TOPICS;
import static android.adservices.common.AdServicesStatusUtils.STATUS_BACKGROUND_CALLER;
import static android.adservices.common.AdServicesStatusUtils.STATUS_CALLER_NOT_ALLOWED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_PERMISSION_NOT_REQUESTED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_RATE_LIMIT_REACHED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;
import static android.adservices.common.AdServicesStatusUtils.STATUS_UNAUTHORIZED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_USER_CONSENT_REVOKED;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_CLASS__TARGETING;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS_PREVIEW_API;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__PACKAGE_NAME_NOT_FOUND_EXCEPTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.adservices.common.CallerMetadata;
import android.adservices.topics.GetTopicsParam;
import android.adservices.topics.GetTopicsResult;
import android.adservices.topics.IGetTopicsCallback;
import android.app.adservices.AdServicesManager;
import android.app.adservices.topics.TopicParcel;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.test.mock.MockContext;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.DbHelper;
import com.android.adservices.data.DbTestUtil;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.data.topics.Topic;
import com.android.adservices.data.topics.TopicsDao;
import com.android.adservices.data.topics.TopicsTables;
import com.android.adservices.errorlogging.ErrorLogUtil;
import com.android.adservices.service.Flags;
import com.android.adservices.service.appsearch.AppSearchConsentManager;
import com.android.adservices.service.common.AllowLists;
import com.android.adservices.service.common.AppImportanceFilter;
import com.android.adservices.service.common.AppImportanceFilter.WrongCallingApplicationStateException;
import com.android.adservices.service.common.AppManifestConfigHelper;
import com.android.adservices.service.common.Throttler;
import com.android.adservices.service.consent.AdServicesApiConsent;
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.enrollment.EnrollmentData;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.adservices.service.stats.ApiCallStats;
import com.android.adservices.service.stats.Clock;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Unit test for {@link com.android.adservices.service.topics.TopicsServiceImpl}. */
public class TopicsServiceImplTest {
    private static final String TEST_APP_PACKAGE_NAME = "com.android.adservices.servicecoretest";
    private static final String INVALID_PACKAGE_NAME = "com.do_not_exists";
    private static final String SOME_SDK_NAME = "SomeSdkName";
    private static final int BINDER_CONNECTION_TIMEOUT_MS = 10_000;
    private static final String SDK_PACKAGE_NAME = "test_package_name";
    private static final String ALLOWED_SDK_ID = "1234567";
    // This is not allowed per the ad_services_config.xml manifest config.
    private static final String DISALLOWED_SDK_ID = "123";
    private static final int SANDBOX_UID = 25000;
    private static final String HEX_STRING =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final byte[] BYTE_ARRAY = new byte[32];

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final AdServicesLogger mAdServicesLogger =
            Mockito.spy(AdServicesLoggerImpl.getInstance());

    private CountDownLatch mGetTopicsCallbackLatch;
    private CallerMetadata mCallerMetadata;
    private TopicsWorker mTopicsWorker;
    private TopicsWorker mSpyTopicsWorker;
    private BlockedTopicsManager mBlockedTopicsManager;
    private TopicsDao mTopicsDao;
    private GetTopicsParam mRequest;
    private MockitoSession mStaticMockitoSession;
    private TopicsServiceImpl mTopicsServiceImpl;

    @Mock private EpochManager mMockEpochManager;
    @Mock private ConsentManager mConsentManager;
    @Mock private PackageManager mPackageManager;
    @Mock private Flags mMockFlags;
    @Mock private Clock mClock;
    @Mock private Context mMockSdkContext;
    @Mock private Context mMockAppContext;
    @Mock private Throttler mMockThrottler;
    @Mock private EnrollmentDao mEnrollmentDao;
    @Mock private AppImportanceFilter mMockAppImportanceFilter;
    @Mock AdServicesLogger mLogger;
    @Mock AdServicesManager mMockAdServicesManager;
    @Mock AppSearchConsentManager mAppSearchConsentManager;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Clean DB before each test
        DbTestUtil.deleteTable(TopicsTables.ReturnedTopicContract.TABLE);

        DbHelper dbHelper = DbTestUtil.getDbHelperForTest();
        mTopicsDao = new TopicsDao(dbHelper);
        mBlockedTopicsManager =
                new BlockedTopicsManager(
                        mTopicsDao,
                        mMockAdServicesManager,
                        mAppSearchConsentManager,
                        Flags.PPAPI_AND_SYSTEM_SERVER,
                        /* enableAppSearchConsent= */ false);
        CacheManager cacheManager =
                new CacheManager(
                        mTopicsDao,
                        mMockFlags,
                        mLogger,
                        mBlockedTopicsManager,
                        new GlobalBlockedTopicsManager(
                                /* globalBlockedTopicsManager = */ new HashSet<>()));

        AppUpdateManager appUpdateManager =
                new AppUpdateManager(dbHelper, mTopicsDao, new Random(), mMockFlags);
        mTopicsWorker =
                new TopicsWorker(
                        mMockEpochManager,
                        cacheManager,
                        mBlockedTopicsManager,
                        appUpdateManager,
                        mMockFlags);
        // Used for verifying recordUsage method invocations.
        mSpyTopicsWorker =
                Mockito.spy(
                        new TopicsWorker(
                                mMockEpochManager,
                                cacheManager,
                                mBlockedTopicsManager,
                                appUpdateManager,
                                mMockFlags));

        when(mClock.elapsedRealtime()).thenReturn(150L, 200L);
        mCallerMetadata = new CallerMetadata.Builder().setBinderElapsedTimestamp(100L).build();
        mRequest =
                new GetTopicsParam.Builder()
                        .setAppPackageName(TEST_APP_PACKAGE_NAME)
                        .setSdkName(SOME_SDK_NAME)
                        .setSdkPackageName(SDK_PACKAGE_NAME)
                        .build();

        DbTestUtil.deleteTable(TopicsTables.BlockedTopicsContract.TABLE);
        when(mConsentManager.getConsent()).thenReturn(AdServicesApiConsent.GIVEN);
        when(mConsentManager.getConsent(AdServicesApiType.TOPICS))
                .thenReturn(AdServicesApiConsent.GIVEN);
        when(mMockSdkContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getPackageUid(TEST_APP_PACKAGE_NAME, 0)).thenReturn(Process.myUid());

        // Allow all for signature allow list check
        when(mMockFlags.getPpapiAppSignatureAllowList()).thenReturn(AllowLists.ALLOW_ALL);
        when(mMockFlags.getTopicsEpochJobPeriodMs()).thenReturn(Flags.TOPICS_EPOCH_JOB_PERIOD_MS);

        // Initialize enrollment data.
        EnrollmentData fakeEnrollmentData =
                new EnrollmentData.Builder().setEnrollmentId(ALLOWED_SDK_ID).build();
        when(mEnrollmentDao.getEnrollmentDataFromSdkName(SOME_SDK_NAME))
                .thenReturn(fakeEnrollmentData);

        // Rate Limit is not reached.
        when(mMockThrottler.tryAcquire(eq(Throttler.ApiKey.TOPICS_API_SDK_NAME), anyString()))
                .thenReturn(true);
        when(mMockThrottler.tryAcquire(
                        eq(Throttler.ApiKey.TOPICS_API_APP_PACKAGE_NAME), anyString()))
                .thenReturn(true);

        when(mMockFlags.isEnrollmentBlocklisted(Mockito.any())).thenReturn(false);

        // Initialize mock static.
        mStaticMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(Binder.class)
                        .spyStatic(AllowLists.class)
                        .spyStatic(ErrorLogUtil.class)
                        .startMocking();
    }

    @After
    public void tearDown() {
        mStaticMockitoSession.finishMocking();
    }

    @Test
    public void checkNoUserConsent() throws InterruptedException {
        when(mMockFlags.getGaUxFeatureEnabled()).thenReturn(false);
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        when(mConsentManager.getConsent()).thenReturn(AdServicesApiConsent.REVOKED);
        invokeGetTopicsAndVerifyError(
                mContext, STATUS_USER_CONSENT_REVOKED, /* checkLoggingStatus */ true);
    }

    @Test
    public void checkNoUserConsent_gaUxFeatureEnabled() throws InterruptedException {
        when(mMockFlags.getGaUxFeatureEnabled()).thenReturn(true);
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        when(mConsentManager.getConsent(AdServicesApiType.TOPICS))
                .thenReturn(AdServicesApiConsent.REVOKED);
        invokeGetTopicsAndVerifyError(
                mContext, STATUS_USER_CONSENT_REVOKED, /* checkLoggingStatus */ true);
    }

    @Test
    public void checkSignatureAllowList_successAllowList() throws Exception {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        mTopicsServiceImpl = createTestTopicsServiceImplInstance();

        // Add test app into allow list
        ExtendedMockito.doReturn(BYTE_ARRAY)
                .when(() -> AllowLists.getAppSignatureHash(mContext, TEST_APP_PACKAGE_NAME));
        when(mMockFlags.getPpapiAppSignatureAllowList()).thenReturn(HEX_STRING);

        runGetTopics(mTopicsServiceImpl);
    }

    @Test
    public void checkSignatureAllowList_emptyAllowList() throws InterruptedException {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        // Empty allow list and bypass list.
        when(mMockFlags.getPpapiAppSignatureAllowList()).thenReturn("");
        invokeGetTopicsAndVerifyError(
                mContext, STATUS_CALLER_NOT_ALLOWED, /* checkLoggingStatus */ true);
    }

    @Test
    public void checkThrottler_rateLimitReached_forSdkName() throws InterruptedException {
        // Rate Limit Reached.
        when(mMockThrottler.tryAcquire(eq(Throttler.ApiKey.TOPICS_API_SDK_NAME), anyString()))
                .thenReturn(false);
        // We don't log STATUS_RATE_LIMIT_REACHED for getTopics API.
        invokeGetTopicsAndVerifyError(
                mContext, STATUS_RATE_LIMIT_REACHED, /* checkLoggingStatus */ false);
    }

    @Test
    public void checkThrottler_rateLimitReached_forAppPackageName() throws InterruptedException {
        // App calls Topics API directly, not via an SDK.
        GetTopicsParam request =
                new GetTopicsParam.Builder()
                        .setAppPackageName(TEST_APP_PACKAGE_NAME)
                        .setSdkName("") // Empty SdkName implies the app calls Topic API directly.
                        .setSdkPackageName(SDK_PACKAGE_NAME)
                        .build();

        // Rate Limit Reached.
        when(mMockThrottler.tryAcquire(
                        eq(Throttler.ApiKey.TOPICS_API_APP_PACKAGE_NAME), anyString()))
                .thenReturn(false);
        // We don't log STATUS_RATE_LIMIT_REACHED for getTopics API.
        invokeGetTopicsAndVerifyError(
                mContext, STATUS_RATE_LIMIT_REACHED, request, /* checkLoggingStatus */ false);
    }

    @Test
    public void testEnforceForeground_backgroundCaller() throws InterruptedException {
        Assume.assumeTrue(SdkLevel.isAtLeastT()); // R/S can't enforce foreground checks.

        final int uid = Process.myUid();
        // Mock AppImportanceFilter to throw WrongCallingApplicationStateException
        doThrow(new WrongCallingApplicationStateException())
                .when(mMockAppImportanceFilter)
                .assertCallerIsInForeground(
                        uid, AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS, SOME_SDK_NAME);
        // Mock UID with Non-SDK UID
        when(Binder.getCallingUidOrThrow()).thenReturn(uid);

        // Mock Flags to true to enable enforcing foreground check.
        doReturn(true).when(mMockFlags).getEnforceForegroundStatusForTopics();

        invokeGetTopicsAndVerifyError(
                mContext, STATUS_BACKGROUND_CALLER, mRequest, /* checkLoggingStatus */ true);
    }

    @Test
    public void testEnforceForeground_sandboxCaller() throws Exception {
        Assume.assumeTrue(SdkLevel.isAtLeastT()); // Only applicable for T+

        // Mock AppImportanceFilter to throw Exception when invoked. This is to verify getTopics()
        // doesn't throw if caller is via Sandbox.
        doThrow(new WrongCallingApplicationStateException())
                .when(mMockAppImportanceFilter)
                .assertCallerIsInForeground(
                        SANDBOX_UID, AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS, SOME_SDK_NAME);

        // Mock UID with SDK UID
        when(Binder.getCallingUidOrThrow()).thenReturn(SANDBOX_UID);

        // Mock Flags with true to enable enforcing foreground check.
        doReturn(true).when(mMockFlags).getEnforceForegroundStatusForTopics();

        // Mock to grant required permissions
        // Copied UID calculation from Process.getAppUidForSdkSandboxUid().
        final int appCallingUid = SANDBOX_UID - 10000;
        when(mPackageManager.checkPermission(ACCESS_ADSERVICES_TOPICS, SDK_PACKAGE_NAME))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mPackageManager.getPackageUid(TEST_APP_PACKAGE_NAME, 0)).thenReturn(appCallingUid);
        doReturn(true).when(mMockFlags).isDisableTopicsEnrollmentCheck();

        // Verify getTopics() doesn't throw.
        mTopicsServiceImpl = createTopicsServiceImplInstance_SandboxContext();
        runGetTopics(mTopicsServiceImpl);

        verify(mMockAppImportanceFilter, never())
                .assertCallerIsInForeground(
                        SANDBOX_UID, AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS, SOME_SDK_NAME);
    }

    @Test
    public void testEnforceForeground_disableEnforcing() throws Exception {
        final int uid = Process.myUid();
        // Mock AppImportanceFilter to throw Exception when invoked. This is to verify getTopics()
        // doesn't throw if enforcing foreground is disabled
        doThrow(new WrongCallingApplicationStateException())
                .when(mMockAppImportanceFilter)
                .assertCallerIsInForeground(
                        uid, AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS, SOME_SDK_NAME);

        // Mock UID with Non-SDK UI
        when(Binder.getCallingUidOrThrow()).thenReturn(uid);

        // Mock Flags with false to disable enforcing foreground check.
        doReturn(false).when(mMockFlags).getEnforceForegroundStatusForTopics();

        // Mock to grant required permissions
        when(mPackageManager.getPackageUid(TEST_APP_PACKAGE_NAME, 0)).thenReturn(uid);

        // Verify getTopics() doesn't throw.
        mTopicsServiceImpl = createTestTopicsServiceImplInstance();
        runGetTopics(mTopicsServiceImpl);

        verify(mMockAppImportanceFilter, never())
                .assertCallerIsInForeground(
                        uid, AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS, SOME_SDK_NAME);
    }

    @Test
    public void checkNoPermission() throws InterruptedException {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        MockContext context =
                new MockContext() {
                    @Override
                    public int checkCallingOrSelfPermission(String permission) {
                        return PackageManager.PERMISSION_DENIED;
                    }

                    @Override
                    public PackageManager getPackageManager() {
                        return mPackageManager;
                    }
                };
        invokeGetTopicsAndVerifyError(
                context, STATUS_PERMISSION_NOT_REQUESTED, /* checkLoggingStatus */ true);
    }

    @Test
    public void checkSdkNoPermission() throws InterruptedException {
        Assume.assumeTrue(SdkLevel.isAtLeastT()); // Sdk Sandbox only exists in T+
        when(mPackageManager.checkPermission(ACCESS_ADSERVICES_TOPICS, SDK_PACKAGE_NAME))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(Binder.getCallingUidOrThrow()).thenReturn(SANDBOX_UID);
        invokeGetTopicsAndVerifyError(
                mMockSdkContext, STATUS_PERMISSION_NOT_REQUESTED, /* checkLoggingStatus */ true);
    }

    @Test
    public void checkSdkHasEnrollmentIdNull() throws Exception {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        EnrollmentData fakeEnrollmentData =
                new EnrollmentData.Builder().setEnrollmentId(null).build();
        when(mEnrollmentDao.getEnrollmentDataFromSdkName(SOME_SDK_NAME))
                .thenReturn(fakeEnrollmentData);
        invokeGetTopicsAndVerifyError(
                mMockSdkContext, STATUS_CALLER_NOT_ALLOWED, /* checkLoggingStatus */ true);
    }

    @Test
    public void checkSdkEnrollmentInBlocklist_blocked() throws Exception {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        EnrollmentData fakeEnrollmentData =
                new EnrollmentData.Builder().setEnrollmentId(ALLOWED_SDK_ID).build();
        when(mEnrollmentDao.getEnrollmentDataFromSdkName(SOME_SDK_NAME))
                .thenReturn(fakeEnrollmentData);

        when(mMockFlags.isEnrollmentBlocklisted(ALLOWED_SDK_ID)).thenReturn(true);

        invokeGetTopicsAndVerifyError(
                mMockSdkContext, STATUS_CALLER_NOT_ALLOWED, /* checkLoggingStatus */ true);
    }

    @Test
    public void checkSdkEnrollmentIdIsDisallowed() throws Exception {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        EnrollmentData fakeEnrollmentData =
                new EnrollmentData.Builder().setEnrollmentId(DISALLOWED_SDK_ID).build();
        when(mEnrollmentDao.getEnrollmentDataFromSdkName(SOME_SDK_NAME))
                .thenReturn(fakeEnrollmentData);
        invokeGetTopicsAndVerifyError(
                mMockSdkContext, STATUS_CALLER_NOT_ALLOWED, /* checkLoggingStatus */ true);
    }

    @Test
    public void getTopicsFromApp_SdkNotIncluded() throws Exception {
        Mockito.lenient().when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        PackageManager.Property property =
                mContext.getPackageManager()
                        .getProperty(
                                "android.adservices.AD_SERVICES_CONFIG.sdkMissing",
                                TEST_APP_PACKAGE_NAME);
        when(mPackageManager.getProperty(
                        AppManifestConfigHelper.AD_SERVICES_CONFIG_PROPERTY, TEST_APP_PACKAGE_NAME))
                .thenReturn(property);

        Resources resources =
                mContext.getPackageManager().getResourcesForApplication(TEST_APP_PACKAGE_NAME);
        when(mPackageManager.getResourcesForApplication(TEST_APP_PACKAGE_NAME))
                .thenReturn(resources);
        when(mMockAppContext.getPackageManager()).thenReturn(mPackageManager);
        invokeGetTopicsAndVerifyError(
                mMockAppContext, STATUS_CALLER_NOT_ALLOWED, /* checkLoggingStatus */ true);
    }

    @Test
    public void getTopicsFromApp_SdkTagMissing() throws Exception {
        Mockito.lenient().when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        PackageManager.Property property =
                mContext.getPackageManager()
                        .getProperty(
                                "android.adservices.AD_SERVICES_CONFIG.sdkTagMissing",
                                TEST_APP_PACKAGE_NAME);
        when(mPackageManager.getProperty(
                        AppManifestConfigHelper.AD_SERVICES_CONFIG_PROPERTY, TEST_APP_PACKAGE_NAME))
                .thenReturn(property);

        Resources resources =
                mContext.getPackageManager().getResourcesForApplication(TEST_APP_PACKAGE_NAME);
        when(mPackageManager.getResourcesForApplication(TEST_APP_PACKAGE_NAME))
                .thenReturn(resources);
        when(mMockAppContext.getPackageManager()).thenReturn(mPackageManager);
        invokeGetTopicsAndVerifyError(
                mMockAppContext, STATUS_CALLER_NOT_ALLOWED, /* checkLoggingStatus */ true);
    }

    @Test
    public void getTopics() throws Exception {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        runGetTopics(createTestTopicsServiceImplInstance());
    }

    @Test
    public void getTopicsGaUx() throws Exception {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        when(mMockFlags.getGaUxFeatureEnabled()).thenReturn(true);
        runGetTopics(createTestTopicsServiceImplInstance());
    }

    @Test
    public void getTopicsSdk() throws Exception {
        Mockito.lenient().when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        PackageManager.Property property =
                mContext.getPackageManager()
                        .getProperty(
                                AppManifestConfigHelper.AD_SERVICES_CONFIG_PROPERTY,
                                TEST_APP_PACKAGE_NAME);
        when(mPackageManager.getProperty(
                        AppManifestConfigHelper.AD_SERVICES_CONFIG_PROPERTY, TEST_APP_PACKAGE_NAME))
                .thenReturn(property);
        Resources resources =
                mContext.getPackageManager().getResourcesForApplication(TEST_APP_PACKAGE_NAME);
        when(mPackageManager.getResourcesForApplication(TEST_APP_PACKAGE_NAME))
                .thenReturn(resources);
        runGetTopics(createTopicsServiceImplInstance_SandboxContext());
    }

    @Test
    public void getTopics_oneTopicBlocked() throws InterruptedException {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        final long currentEpochId = 4L;
        final int numberOfLookBackEpochs = 3;
        List<Topic> topics = prepareAndPersistTopics(numberOfLookBackEpochs);
        List<TopicParcel> topicParcels =
                topics.stream().map(Topic::convertTopicToTopicParcel).collect(Collectors.toList());

        // Mock IPC calls
        doNothing().when(mMockAdServicesManager).recordBlockedTopic(List.of(topicParcels.get(0)));
        doReturn(List.of(topicParcels.get(0)))
                .when(mMockAdServicesManager)
                .retrieveAllBlockedTopics();
        // block topic1
        mBlockedTopicsManager.blockTopic(topics.get(0));

        when(mConsentManager.getConsent()).thenReturn(AdServicesApiConsent.GIVEN);
        when(mMockEpochManager.getCurrentEpochId()).thenReturn(currentEpochId);
        when(mMockFlags.getTopicsNumberOfLookBackEpochs()).thenReturn(numberOfLookBackEpochs);

        GetTopicsResult expectedGetTopicsResult =
                new GetTopicsResult.Builder()
                        .setTaxonomyVersions(Arrays.asList(2L, 3L))
                        .setModelVersions(Arrays.asList(5L, 6L))
                        .setTopics(Arrays.asList(2, 3))
                        .build();

        TopicsServiceImpl topicsServiceImpl = createTestTopicsServiceImplInstance();

        // Call init() to load the cache
        topicsServiceImpl.init();

        // To capture result in inner class, we have to declare final.
        final GetTopicsResult[] capturedResponseParcel = getTopicsResults(topicsServiceImpl);

        assertThat(
                        mGetTopicsCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();

        GetTopicsResult getTopicsResult = capturedResponseParcel[0];
        // Since the returned topic list is shuffled, elements have to be verified separately
        assertThat(getTopicsResult.getResultCode())
                .isEqualTo(expectedGetTopicsResult.getResultCode());
        assertThat(getTopicsResult.getTaxonomyVersions())
                .containsExactlyElementsIn(expectedGetTopicsResult.getTaxonomyVersions());
        assertThat(getTopicsResult.getModelVersions())
                .containsExactlyElementsIn(expectedGetTopicsResult.getModelVersions());
        assertThat(getTopicsResult.getTopics())
                .containsExactlyElementsIn(expectedGetTopicsResult.getTopics());

        // Invocations Summary
        // loadCache() : 1, getTopics(): 1 * 2
        verify(mMockEpochManager, Mockito.times(3)).getCurrentEpochId();
        verify(mMockFlags, Mockito.times(3)).getTopicsNumberOfLookBackEpochs();

        // Verify IPC calls
        verify(mMockAdServicesManager).recordBlockedTopic(List.of(topicParcels.get(0)));
        verify(mMockAdServicesManager).retrieveAllBlockedTopics();
    }

    @Test
    public void getTopics_allTopicsBlocked() throws InterruptedException {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        final long currentEpochId = 4L;
        final int numberOfLookBackEpochs = 3;
        List<Topic> topics = prepareAndPersistTopics(numberOfLookBackEpochs);
        List<TopicParcel> topicParcels =
                topics.stream().map(Topic::convertTopicToTopicParcel).collect(Collectors.toList());

        // Mock IPC calls
        doNothing().when(mMockAdServicesManager).recordBlockedTopic(anyList());
        doReturn(topicParcels).when(mMockAdServicesManager).retrieveAllBlockedTopics();
        // block all topics
        for (Topic topic : topics) {
            mBlockedTopicsManager.blockTopic(topic);
        }

        when(mConsentManager.getConsent()).thenReturn(AdServicesApiConsent.GIVEN);
        when(mMockEpochManager.getCurrentEpochId()).thenReturn(currentEpochId);
        when(mMockFlags.getTopicsNumberOfLookBackEpochs()).thenReturn(numberOfLookBackEpochs);

        // No topics (empty list) were returned because all topics are blocked
        GetTopicsResult expectedGetTopicsResult =
                new GetTopicsResult.Builder()
                        .setResultCode(STATUS_SUCCESS)
                        .setTaxonomyVersions(Collections.emptyList())
                        .setModelVersions(Collections.emptyList())
                        .setTopics(Collections.emptyList())
                        .build();

        TopicsServiceImpl topicsServiceImpl = createTestTopicsServiceImplInstance();

        // Call init() to load the cache
        topicsServiceImpl.init();

        // To capture result in inner class, we have to declare final.
        final GetTopicsResult[] capturedResponseParcel = getTopicsResults(topicsServiceImpl);

        assertThat(
                        mGetTopicsCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();

        GetTopicsResult getTopicsResult = capturedResponseParcel[0];

        assertThat(getTopicsResult).isEqualTo(expectedGetTopicsResult);

        // Invocation Summary:
        // loadCache(): 1, getTopics(): 2
        verify(mMockEpochManager, Mockito.times(3)).getCurrentEpochId();
        // getTopics in CacheManager and TopicsWorker calls this mock
        verify(mMockFlags, Mockito.times(3)).getTopicsNumberOfLookBackEpochs();
        // Verify IPC calls
        verify(mMockAdServicesManager, times(topics.size())).recordBlockedTopic(anyList());
        verify(mMockAdServicesManager).retrieveAllBlockedTopics();
    }

    @Test
    public void testGetTopics_emptyTopicsReturned() throws InterruptedException {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        final long currentEpochId = 4L;
        final int numberOfLookBackEpochs = 3;

        when(mConsentManager.getConsent()).thenReturn(AdServicesApiConsent.GIVEN);
        when(mMockEpochManager.getCurrentEpochId()).thenReturn(currentEpochId);
        when(mMockFlags.getTopicsNumberOfLookBackEpochs()).thenReturn(numberOfLookBackEpochs);

        // No topics (empty list) were returned.
        GetTopicsResult expectedGetTopicsResult =
                new GetTopicsResult.Builder()
                        .setResultCode(STATUS_SUCCESS)
                        .setTaxonomyVersions(Collections.emptyList())
                        .setModelVersions(Collections.emptyList())
                        .setTopics(Collections.emptyList())
                        .build();

        TopicsServiceImpl topicsServiceImpl = createTestTopicsServiceImplInstance();

        // Call init() to load the cache
        topicsServiceImpl.init();

        // To capture result in inner class, we have to declare final.
        final GetTopicsResult[] capturedResponseParcel = getTopicsResults(topicsServiceImpl);

        assertThat(
                        mGetTopicsCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();

        GetTopicsResult getTopicsResult = capturedResponseParcel[0];
        // Since the returned topic list is shuffled, elements have to be verified separately
        assertThat(getTopicsResult.getResultCode())
                .isEqualTo(expectedGetTopicsResult.getResultCode());
        assertThat(getTopicsResult.getTaxonomyVersions())
                .containsExactlyElementsIn(expectedGetTopicsResult.getTaxonomyVersions());
        assertThat(getTopicsResult.getModelVersions())
                .containsExactlyElementsIn(expectedGetTopicsResult.getModelVersions());
        assertThat(getTopicsResult.getTopics())
                .containsExactlyElementsIn(expectedGetTopicsResult.getTopics());

        // Invocations Summary
        // loadCache() : 1, getTopics(): 1 * 3
        verify(mMockEpochManager, Mockito.times(4)).getCurrentEpochId();
        verify(mMockFlags, Mockito.times(4)).getTopicsNumberOfLookBackEpochs();
    }

    @Test
    public void testGetTopics_LatencyCalculateVerify() throws InterruptedException {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        final long currentEpochId = 4L;
        final int numberOfLookBackEpochs = 3;

        when(mConsentManager.getConsent()).thenReturn(AdServicesApiConsent.GIVEN);
        when(mMockEpochManager.getCurrentEpochId()).thenReturn(currentEpochId);
        when(mMockFlags.getTopicsNumberOfLookBackEpochs()).thenReturn(numberOfLookBackEpochs);

        // No topics (empty list) were returned.
        GetTopicsResult expectedGetTopicsResult =
                new GetTopicsResult.Builder()
                        .setResultCode(STATUS_SUCCESS)
                        .setTaxonomyVersions(Collections.emptyList())
                        .setModelVersions(Collections.emptyList())
                        .setTopics(Collections.emptyList())
                        .build();

        TopicsServiceImpl topicsServiceImpl = createTestTopicsServiceImplInstance();

        // Call init() to load the cache
        topicsServiceImpl.init();

        // To capture result in inner class, we have to declare final.
        final GetTopicsResult[] capturedResponseParcel = new GetTopicsResult[1];
        mGetTopicsCallbackLatch = new CountDownLatch(1);

        // Topic impl service use a background executor to run the task,
        // use a countdownLatch and set the countdown in the logging call operation
        final CountDownLatch logOperationCalledLatch = new CountDownLatch(1);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock invocation) throws Throwable {
                                // The method logAPiCallStats is called.
                                invocation.callRealMethod();
                                logOperationCalledLatch.countDown();
                                return null;
                            }
                        })
                .when(mAdServicesLogger)
                .logApiCallStats(ArgumentMatchers.any(ApiCallStats.class));

        // Setting up the timestamp for latency calculation, we passing in a client side call
        // timestamp as a parameter to the call (100 in the below code), in topic service, it
        // calls for timestamp at the method start which will return 150, we get client side to
        // service latency as (start - client) * 2. The second time it calls for timestamp will
        // be at logging time which will return 200, we get service side latency as
        // (logging - start), thus the total latency is logging - start + (start - client) * 2,
        // which is 150 in these numbers
        when(mClock.elapsedRealtime()).thenReturn(150L, 200L);

        ArgumentCaptor<ApiCallStats> argument = ArgumentCaptor.forClass(ApiCallStats.class);

        // Send client side timestamp, working with the mock information in
        // service side to calculate the latency
        topicsServiceImpl.getTopics(
                mRequest,
                mCallerMetadata,
                new IGetTopicsCallback() {
                    @Override
                    public void onResult(GetTopicsResult responseParcel) {
                        capturedResponseParcel[0] = responseParcel;
                        mGetTopicsCallbackLatch.countDown();
                    }

                    @Override
                    public void onFailure(int resultCode) {
                        Assert.fail();
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });

        assertThat(
                        mGetTopicsCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();

        GetTopicsResult getTopicsResult = capturedResponseParcel[0];
        // Since the returned topic list is shuffled, elements have to be verified separately
        assertThat(getTopicsResult.getResultCode())
                .isEqualTo(expectedGetTopicsResult.getResultCode());
        assertThat(getTopicsResult.getTaxonomyVersions())
                .containsExactlyElementsIn(expectedGetTopicsResult.getTaxonomyVersions());
        assertThat(getTopicsResult.getModelVersions())
                .containsExactlyElementsIn(expectedGetTopicsResult.getModelVersions());
        assertThat(getTopicsResult.getTopics())
                .containsExactlyElementsIn(expectedGetTopicsResult.getTopics());

        assertThat(
                        logOperationCalledLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();

        verify(mAdServicesLogger).logApiCallStats(argument.capture());
        assertThat(argument.getValue().getResultCode()).isEqualTo(STATUS_SUCCESS);
        assertThat(argument.getValue().getAppPackageName()).isEqualTo(TEST_APP_PACKAGE_NAME);
        assertThat(argument.getValue().getSdkPackageName()).isEqualTo(SOME_SDK_NAME);
        // The latency calculate result (200 - 150) + (150 - 100) * 2 = 150
        assertThat(argument.getValue().getLatencyMillisecond()).isEqualTo(150);

        // Invocations Summary
        // loadCache() : 1, getTopics(): 1 * 3
        verify(mMockEpochManager, Mockito.times(4)).getCurrentEpochId();
        verify(mMockFlags, Mockito.times(4)).getTopicsNumberOfLookBackEpochs();
    }

    @Test
    public void testGetTopics_enforceCallingPackage_invalidPackage() throws InterruptedException {
        ExtendedMockito.doNothing().when(() -> ErrorLogUtil.e(any(), anyInt(), anyInt()));
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        final long currentEpochId = 4L;
        final int numberOfLookBackEpochs = 3;

        when(mConsentManager.getConsent()).thenReturn(AdServicesApiConsent.GIVEN);
        when(mMockEpochManager.getCurrentEpochId()).thenReturn(currentEpochId);
        when(mMockFlags.getTopicsNumberOfLookBackEpochs()).thenReturn(numberOfLookBackEpochs);

        TopicsServiceImpl topicsService = createTestTopicsServiceImplInstance();

        // A request with an invalid package name.
        mRequest =
                new GetTopicsParam.Builder()
                        .setAppPackageName(INVALID_PACKAGE_NAME)
                        .setSdkName(SOME_SDK_NAME)
                        .setSdkPackageName(SOME_SDK_NAME)
                        .build();

        mGetTopicsCallbackLatch = new CountDownLatch(1);

        topicsService.getTopics(
                mRequest,
                mCallerMetadata,
                new IGetTopicsCallback() {
                    @Override
                    public void onResult(GetTopicsResult responseParcel) {
                        Assert.fail();
                    }

                    @Override
                    public void onFailure(int resultCode) {
                        assertThat(resultCode).isEqualTo(STATUS_UNAUTHORIZED);
                        mGetTopicsCallbackLatch.countDown();
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });

        // This ensures that the callback was called.
        assertThat(
                        mGetTopicsCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();
        ExtendedMockito.verify(
                () ->
                        ErrorLogUtil.e(
                                any(Throwable.class),
                                eq(
                                        AD_SERVICES_ERROR_REPORTED__ERROR_CODE__PACKAGE_NAME_NOT_FOUND_EXCEPTION),
                                eq(AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS)));
    }

    @Test
    public void testGetTopics_recordObservation() throws InterruptedException {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        // To capture result in inner class, we have to declare final.
        final GetTopicsResult[] capturedResponseParcel = new GetTopicsResult[1];

        final long currentEpochId = 4L;
        final int numberOfLookBackEpochs = 3;

        when(mConsentManager.getConsent()).thenReturn(AdServicesApiConsent.GIVEN);
        when(mMockEpochManager.getCurrentEpochId()).thenReturn(currentEpochId);
        when(mMockFlags.getTopicsNumberOfLookBackEpochs()).thenReturn(numberOfLookBackEpochs);

        TopicsServiceImpl topicsService = createTestTopicsServiceImplInstance_spyTopicsWorker();

        // Not setting isRecordObservation explicitly will make isRecordObservation to have
        // default value which is true.
        mRequest =
                new GetTopicsParam.Builder()
                        .setAppPackageName(TEST_APP_PACKAGE_NAME)
                        .setSdkName(SOME_SDK_NAME)
                        .setSdkPackageName(SOME_SDK_NAME)
                        .build();

        // Topic impl service use a background executor to run the task,
        // use a countdownLatch and set the countdown in the logging call operation
        final CountDownLatch logOperationCalledLatch = new CountDownLatch(1);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock invocation) throws Throwable {
                                // The method logAPiCallStats is called.
                                invocation.callRealMethod();
                                logOperationCalledLatch.countDown();
                                return null;
                            }
                        })
                .when(mAdServicesLogger)
                .logApiCallStats(ArgumentMatchers.any(ApiCallStats.class));

        ArgumentCaptor<ApiCallStats> argument = ArgumentCaptor.forClass(ApiCallStats.class);

        topicsService.getTopics(
                mRequest,
                mCallerMetadata,
                new IGetTopicsCallback() {
                    @Override
                    public void onResult(GetTopicsResult responseParcel) {
                        capturedResponseParcel[0] = responseParcel;
                    }

                    @Override
                    public void onFailure(int resultCode) {
                        Assert.fail();
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });

        // getTopics method finished executing.
        assertThat(
                        logOperationCalledLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();

        // Record the call from App and Sdk to usage history only when isRecordObservation is true.
        verify(mSpyTopicsWorker, Mockito.times(1))
                .recordUsage(TEST_APP_PACKAGE_NAME, SOME_SDK_NAME);

        verify(mAdServicesLogger).logApiCallStats(argument.capture());
        assertThat(argument.getValue().getResultCode()).isEqualTo(STATUS_SUCCESS);
        assertThat(argument.getValue().getAppPackageName()).isEqualTo(TEST_APP_PACKAGE_NAME);
        assertThat(argument.getValue().getSdkPackageName()).isEqualTo(SOME_SDK_NAME);
        // Verify AdServicesLogger logs getTopics API.
        assertThat(argument.getValue().getApiName())
                .isEqualTo(AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS);
    }

    @Test
    public void testGetTopics_notRecordObservation() throws InterruptedException {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        // To capture result in inner class, we have to declare final.
        final GetTopicsResult[] capturedResponseParcel = new GetTopicsResult[1];

        final long currentEpochId = 4L;
        final int numberOfLookBackEpochs = 3;

        when(mConsentManager.getConsent()).thenReturn(AdServicesApiConsent.GIVEN);
        when(mMockEpochManager.getCurrentEpochId()).thenReturn(currentEpochId);
        when(mMockFlags.getTopicsNumberOfLookBackEpochs()).thenReturn(numberOfLookBackEpochs);

        TopicsServiceImpl topicsService = createTestTopicsServiceImplInstance_spyTopicsWorker();

        // Topic impl service use a background executor to run the task,
        // use a countdownLatch and set the countdown in the logging call operation
        final CountDownLatch logOperationCalledLatch = new CountDownLatch(1);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock invocation) throws Throwable {
                                // The method logAPiCallStats is called.
                                invocation.callRealMethod();
                                logOperationCalledLatch.countDown();
                                return null;
                            }
                        })
                .when(mAdServicesLogger)
                .logApiCallStats(ArgumentMatchers.any(ApiCallStats.class));

        ArgumentCaptor<ApiCallStats> argument = ArgumentCaptor.forClass(ApiCallStats.class);

        mRequest =
                new GetTopicsParam.Builder()
                        .setAppPackageName(TEST_APP_PACKAGE_NAME)
                        .setSdkName(SOME_SDK_NAME)
                        .setSdkPackageName(SOME_SDK_NAME)
                        .setShouldRecordObservation(false)
                        .build();

        topicsService.getTopics(
                mRequest,
                mCallerMetadata,
                new IGetTopicsCallback() {
                    @Override
                    public void onResult(GetTopicsResult responseParcel) {
                        capturedResponseParcel[0] = responseParcel;
                    }

                    @Override
                    public void onFailure(int resultCode) {
                        Assert.fail();
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });

        // getTopics method finished executing.
        assertThat(
                        logOperationCalledLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();

        // Not record the call from App and Sdk to usage history when isRecordObservation is false.
        verify(mSpyTopicsWorker, never()).recordUsage(anyString(), anyString());

        verify(mAdServicesLogger).logApiCallStats(argument.capture());
        assertThat(argument.getValue().getResultCode()).isEqualTo(STATUS_SUCCESS);
        assertThat(argument.getValue().getAppPackageName()).isEqualTo(TEST_APP_PACKAGE_NAME);
        assertThat(argument.getValue().getSdkPackageName()).isEqualTo(SOME_SDK_NAME);
        // Verify AdServicesLogger logs Preview API.
        assertThat(argument.getValue().getApiName())
                .isEqualTo(AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS_PREVIEW_API);
    }

    private void invokeGetTopicsAndVerifyError(
            Context context, int expectedResultCode, boolean checkLoggingStatus)
            throws InterruptedException {
        invokeGetTopicsAndVerifyError(context, expectedResultCode, mRequest, checkLoggingStatus);
    }

    private void invokeGetTopicsAndVerifyError(
            Context context,
            int expectedResultCode,
            GetTopicsParam request,
            boolean checkLoggingStatus)
            throws InterruptedException {
        CountDownLatch jobFinishedCountDown = new CountDownLatch(1);

        // Topic impl service use a background executor to run the task,
        // use a countdownLatch and set the countdown in the logging call operation
        CountDownLatch logOperationCalledLatch = new CountDownLatch(1);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock invocation) throws Throwable {
                                // The method logAPiCallStats is called.
                                invocation.callRealMethod();
                                logOperationCalledLatch.countDown();
                                return null;
                            }
                        })
                .when(mAdServicesLogger)
                .logApiCallStats(ArgumentMatchers.any(ApiCallStats.class));

        mTopicsServiceImpl =
                new TopicsServiceImpl(
                        context,
                        mTopicsWorker,
                        mConsentManager,
                        mAdServicesLogger,
                        mClock,
                        mMockFlags,
                        mMockThrottler,
                        mEnrollmentDao,
                        mMockAppImportanceFilter);
        mTopicsServiceImpl.getTopics(
                request,
                mCallerMetadata,
                new IGetTopicsCallback() {
                    @Override
                    public void onResult(GetTopicsResult responseParcel) {
                        Assert.fail();
                        jobFinishedCountDown.countDown();
                    }

                    @Override
                    public void onFailure(int resultCode) {
                        assertThat(resultCode).isEqualTo(expectedResultCode);
                        jobFinishedCountDown.countDown();
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });
        jobFinishedCountDown.await();

        if (checkLoggingStatus) {
            // getTopics method finished executing.
            logOperationCalledLatch.await();

            ArgumentCaptor<ApiCallStats> argument = ArgumentCaptor.forClass(ApiCallStats.class);

            verify(mAdServicesLogger).logApiCallStats(argument.capture());
            assertThat(argument.getValue().getCode()).isEqualTo(AD_SERVICES_API_CALLED);
            assertThat(argument.getValue().getApiClass())
                    .isEqualTo(AD_SERVICES_API_CALLED__API_CLASS__TARGETING);
            assertThat(argument.getValue().getApiName())
                    .isEqualTo(AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS);
            assertThat(argument.getValue().getResultCode()).isEqualTo(expectedResultCode);
            assertThat(argument.getValue().getAppPackageName())
                    .isEqualTo(request.getAppPackageName());
            assertThat(argument.getValue().getSdkPackageName()).isEqualTo(request.getSdkName());
        }
    }

    private void runGetTopics(TopicsServiceImpl topicsServiceImpl) throws Exception {
        final long currentEpochId = 4L;
        final int numberOfLookBackEpochs = 3;
        prepareAndPersistTopics(numberOfLookBackEpochs);

        when(mMockEpochManager.getCurrentEpochId()).thenReturn(currentEpochId);
        when(mMockFlags.getTopicsNumberOfLookBackEpochs()).thenReturn(numberOfLookBackEpochs);

        GetTopicsResult expectedGetTopicsResult =
                new GetTopicsResult.Builder()
                        .setTaxonomyVersions(Arrays.asList(1L, 2L, 3L))
                        .setModelVersions(Arrays.asList(4L, 5L, 6L))
                        .setTopics(Arrays.asList(1, 2, 3))
                        .build();

        // Call init() to load the cache
        topicsServiceImpl.init();
        final GetTopicsResult[] capturedResponseParcel = getTopicsResults(topicsServiceImpl);

        assertThat(
                        mGetTopicsCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();

        GetTopicsResult getTopicsResult = capturedResponseParcel[0];
        // Since the returned topic list is shuffled, elements have to be verified separately
        assertThat(getTopicsResult.getResultCode())
                .isEqualTo(expectedGetTopicsResult.getResultCode());
        assertThat(getTopicsResult.getTaxonomyVersions())
                .containsExactlyElementsIn(expectedGetTopicsResult.getTaxonomyVersions());
        assertThat(getTopicsResult.getModelVersions())
                .containsExactlyElementsIn(expectedGetTopicsResult.getModelVersions());
        assertThat(getTopicsResult.getTopics())
                .containsExactlyElementsIn(expectedGetTopicsResult.getTopics());

        // loadcache() and getTopics() in CacheManager calls this mock
        verify(mMockEpochManager, Mockito.times(3)).getCurrentEpochId();
        // getTopics in CacheManager and TopicsWorker calls this mock
        verify(mMockFlags, Mockito.times(3)).getTopicsNumberOfLookBackEpochs();
    }

    @NonNull
    private GetTopicsResult[] getTopicsResults(TopicsServiceImpl topicsServiceImpl) {
        // To capture result in inner class, we have to declare final.
        final GetTopicsResult[] capturedResponseParcel = new GetTopicsResult[1];
        mGetTopicsCallbackLatch = new CountDownLatch(1);

        topicsServiceImpl.getTopics(
                mRequest,
                mCallerMetadata,
                new IGetTopicsCallback() {
                    @Override
                    public void onResult(GetTopicsResult responseParcel) {
                        capturedResponseParcel[0] = responseParcel;
                        mGetTopicsCallbackLatch.countDown();
                    }

                    @Override
                    public void onFailure(int resultCode) {
                        Assert.fail();
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });
        return capturedResponseParcel;
    }

    @NonNull
    private List<Topic> prepareAndPersistTopics(int numberOfLookBackEpochs) {
        final Pair<String, String> appSdkKey = Pair.create(TEST_APP_PACKAGE_NAME, SOME_SDK_NAME);
        Topic topic1 =
                Topic.create(/* topic */ 1, /* taxonomyVersion= */ 1L, /* modelVersion= */ 4L);
        Topic topic2 =
                Topic.create(/* topic */ 2, /* taxonomyVersion= */ 2L, /* modelVersion= */ 5L);
        Topic topic3 =
                Topic.create(/* topic */ 3, /* taxonomyVersion= */ 3L, /* modelVersion= */ 6L);
        Topic[] topics = {topic1, topic2, topic3};
        // persist returned topics into DB
        for (int numEpoch = 1; numEpoch <= numberOfLookBackEpochs; numEpoch++) {
            Topic currentTopic = topics[numberOfLookBackEpochs - numEpoch];
            Map<Pair<String, String>, Topic> returnedAppSdkTopicsMap = new HashMap<>();
            returnedAppSdkTopicsMap.put(appSdkKey, currentTopic);
            mTopicsDao.persistReturnedAppTopicsMap(numEpoch, returnedAppSdkTopicsMap);
        }
        return Arrays.asList(topics);
    }

    @NonNull
    private TopicsServiceImpl createTestTopicsServiceImplInstance() {
        return new TopicsServiceImpl(
                mContext,
                mTopicsWorker,
                mConsentManager,
                mAdServicesLogger,
                mClock,
                mMockFlags,
                mMockThrottler,
                mEnrollmentDao,
                mMockAppImportanceFilter);
    }

    @NonNull
    private TopicsServiceImpl createTopicsServiceImplInstance_SandboxContext() {
        return new TopicsServiceImpl(
                mMockSdkContext,
                mTopicsWorker,
                mConsentManager,
                mAdServicesLogger,
                mClock,
                mMockFlags,
                mMockThrottler,
                mEnrollmentDao,
                mMockAppImportanceFilter);
    }

    @NonNull
    private TopicsServiceImpl createTestTopicsServiceImplInstance_spyTopicsWorker() {
        return new TopicsServiceImpl(
                mContext,
                mSpyTopicsWorker,
                mConsentManager,
                mAdServicesLogger,
                mClock,
                mMockFlags,
                mMockThrottler,
                mEnrollmentDao,
                mMockAppImportanceFilter);
    }
}
