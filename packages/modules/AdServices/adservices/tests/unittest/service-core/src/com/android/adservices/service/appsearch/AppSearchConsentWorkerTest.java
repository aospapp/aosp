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

package com.android.adservices.service.appsearch;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.UserHandle;

import androidx.appsearch.app.AppSearchBatchResult;
import androidx.appsearch.app.AppSearchResult;
import androidx.appsearch.app.AppSearchSession;
import androidx.appsearch.app.SetSchemaRequest;
import androidx.appsearch.app.SetSchemaResponse;
import androidx.appsearch.platformstorage.PlatformStorage;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.adservices.AdServicesCommon;
import com.android.adservices.data.topics.Topic;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.feature.PrivacySandboxFeatureType;
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentConstants;
import com.android.adservices.service.consent.ConsentManager;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

@SmallTest
public class AppSearchConsentWorkerTest {
    private Context mContext = ApplicationProvider.getApplicationContext();
    private static final String ADSERVICES_PACKAGE_NAME = "com.android.adservices.api";
    private static final String ADEXTSERVICES_PACKAGE_NAME = "com.android.ext.adservices.api";
    private static final String API_TYPE = AdServicesApiType.TOPICS.toPpApiDatastoreKey();
    private static final Boolean CONSENTED = true;
    private static final String TEST = "test";
    private static final int UID = 55;
    private static final Topic TOPIC1 = Topic.create(0, 1, 11);
    private static final Topic TOPIC2 = Topic.create(12, 2, 22);
    private static final Topic TOPIC3 = Topic.create(123, 3, 33);
    private List<Topic> mTopics = new ArrayList<>();
    private MockitoSession mMockitoSession;
    @Mock Flags mMockFlags;

    @Before
    public void setup() {
        mTopics.addAll(List.of(TOPIC1, TOPIC2, TOPIC3));
        mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(FlagsFactory.class)
                        .strictness(Strictness.WARN)
                        .initMocks(this)
                        .startMocking();
        ExtendedMockito.doReturn(mMockFlags).when(() -> FlagsFactory.getFlags());
        when(mMockFlags.getAdservicesApkShaCertificate())
                .thenReturn(Flags.ADSERVICES_APK_SHA_CERTIFICATE);
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testGetConsent() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .mockStatic(AppSearchConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(false)
                    .when(
                            () ->
                                    AppSearchConsentDao.readConsentData(
                                            /* globalSearchSession= */ any(ListenableFuture.class),
                                            /* executor= */ any(),
                                            /* userId= */ any(),
                                            eq(API_TYPE)));
            boolean result = AppSearchConsentWorker.getInstance(mContext).getConsent(API_TYPE);
            assertThat(result).isFalse();

            // Confirm that the right value is returned even when it is true.
            ExtendedMockito.doReturn(true)
                    .when(
                            () ->
                                    AppSearchConsentDao.readConsentData(
                                            /* globalSearchSession= */ any(ListenableFuture.class),
                                            /* executor= */ any(),
                                            /* userId= */ any(),
                                            eq(API_TYPE)));
            boolean result2 = AppSearchConsentWorker.getInstance(mContext).getConsent(API_TYPE);
            assertThat(result2).isTrue();
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testSetConsent_failure() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    AppSearchConsentWorker.getInstance(mContext)
                                            .setConsent(API_TYPE, CONSENTED));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testSetConsent() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .mockStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initSuccessResponse();
            // Verify that no exception is thrown.
            AppSearchConsentWorker.getInstance(mContext).setConsent(API_TYPE, CONSENTED);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testGetUserIdentifierFromBinderCallingUid() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            UserHandle mockUserHandle = Mockito.mock(UserHandle.class);
            Mockito.when(UserHandle.getUserHandleForUid(Binder.getCallingUid()))
                    .thenReturn(mockUserHandle);
            Mockito.when(mockUserHandle.getIdentifier()).thenReturn(UID);
            String result =
                    AppSearchConsentWorker.getInstance(mContext)
                            .getUserIdentifierFromBinderCallingUid();
            assertThat(result).isEqualTo("" + UID);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testGetAdServicesPackageName_null() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AdServicesCommon.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            Context context = Mockito.mock(Context.class);
            PackageManager mockPackageManager = Mockito.mock(PackageManager.class);
            Mockito.when(context.getPackageManager()).thenReturn(mockPackageManager);
            Mockito.when(AdServicesCommon.resolveAdServicesService(any(), any())).thenReturn(null);
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () -> AppSearchConsentWorker.getAdServicesPackageName(context));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testGetAdServicesPackageName() {
        Context context = Mockito.mock(Context.class);
        PackageManager mockPackageManager = Mockito.mock(PackageManager.class);
        // When the resolveInfo returns AdServices package name, that is returned.
        Mockito.when(context.getPackageManager()).thenReturn(mockPackageManager);

        ServiceInfo serviceInfo1 = new ServiceInfo();
        serviceInfo1.packageName = ADSERVICES_PACKAGE_NAME;
        ResolveInfo resolveInfo1 = new ResolveInfo();
        resolveInfo1.serviceInfo = serviceInfo1;

        ServiceInfo serviceInfo2 = new ServiceInfo();
        serviceInfo2.packageName = ADEXTSERVICES_PACKAGE_NAME;
        ResolveInfo resolveInfo2 = new ResolveInfo();
        resolveInfo2.serviceInfo = serviceInfo2;
        Mockito.when(mockPackageManager.queryIntentServices(any(), anyInt()))
                .thenReturn(List.of(resolveInfo1, resolveInfo2));
        assertThat(AppSearchConsentWorker.getAdServicesPackageName(context))
                .isEqualTo(ADSERVICES_PACKAGE_NAME);

        // When the resolveInfo returns AdExtServices package name, the AdServices package name
        // is returned.
        Mockito.when(mockPackageManager.queryIntentServices(any(), anyInt()))
                .thenReturn(List.of(resolveInfo2));
        assertThat(AppSearchConsentWorker.getAdServicesPackageName(context))
                .isEqualTo(ADSERVICES_PACKAGE_NAME);
    }

    @Test
    public void testGetAppsWithConsent_nullOrEmpty() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchAppConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            // Null dao is returned.
            ExtendedMockito.doReturn(null)
                    .when(() -> AppSearchAppConsentDao.readConsentData(any(), any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.getAppsWithConsent(TEST)).isNotNull();
            assertThat(appSearchConsentWorker.getAppsWithConsent(TEST)).isEmpty();

            // Dao is returned, but list is null.
            AppSearchAppConsentDao mockDao = Mockito.mock(AppSearchAppConsentDao.class);
            ExtendedMockito.doReturn(mockDao)
                    .when(() -> AppSearchAppConsentDao.readConsentData(any(), any(), any(), any()));
            assertThat(appSearchConsentWorker.getAppsWithConsent(TEST)).isNotNull();
            assertThat(appSearchConsentWorker.getAppsWithConsent(TEST)).isEmpty();
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testGetAppsWithConsent() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchAppConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            // Null dao is returned.
            ExtendedMockito.doReturn(null)
                    .when(() -> AppSearchAppConsentDao.readConsentData(any(), any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.getAppsWithConsent(TEST)).isNotNull();
            assertThat(appSearchConsentWorker.getAppsWithConsent(TEST)).isEmpty();

            // Dao is returned, but list is null.
            AppSearchAppConsentDao mockDao = Mockito.mock(AppSearchAppConsentDao.class);
            List<String> apps = ImmutableList.of(TEST);
            when(mockDao.getApps()).thenReturn(apps);
            ExtendedMockito.doReturn(mockDao)
                    .when(() -> AppSearchAppConsentDao.readConsentData(any(), any(), any(), any()));
            assertThat(appSearchConsentWorker.getAppsWithConsent(TEST)).isNotNull();
            assertThat(appSearchConsentWorker.getAppsWithConsent(TEST)).isEqualTo(apps);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testClearAppsWithConsent_failure() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            FluentFuture future =
                    FluentFuture.from(
                            Futures.immediateFailedFuture(new ExecutionException("test", null)));
            ExtendedMockito.doReturn(future)
                    .when(() -> AppSearchDao.deleteData(any(), any(), any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () -> appSearchConsentWorker.clearAppsWithConsent(TEST));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testClearAppsWithConsent() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            AppSearchBatchResult<String, Void> result = Mockito.mock(AppSearchBatchResult.class);
            FluentFuture future = FluentFuture.from(Futures.immediateFuture(result));
            ExtendedMockito.doReturn(future)
                    .when(() -> AppSearchDao.deleteData(any(), any(), any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            // No exceptions are thrown.
            appSearchConsentWorker.clearAppsWithConsent(TEST);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testAddAppWithConsent_null() {
        MockitoSession staticMockSessionLocal = null;
        try {
            String consentType = AppSearchAppConsentDao.APPS_WITH_CONSENT;
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchAppConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(null)
                    .when(() -> AppSearchAppConsentDao.readConsentData(any(), any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            // No exceptions are thrown.
            assertThat(appSearchConsentWorker.addAppWithConsent(consentType, TEST)).isTrue();
            ExtendedMockito.verify(
                    () -> AppSearchAppConsentDao.getRowId(any(), eq(consentType)), atLeastOnce());
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testAddAppWithConsent_failure() {
        MockitoSession staticMockSessionLocal = null;
        try {
            String consentType = AppSearchAppConsentDao.APPS_WITH_CONSENT;
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchAppConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            AppSearchAppConsentDao dao = Mockito.mock(AppSearchAppConsentDao.class);
            ExtendedMockito.doReturn(dao)
                    .when(
                            () ->
                                    AppSearchAppConsentDao.readConsentData(
                                            any(), any(), any(), eq(consentType)));
            when(dao.getApps()).thenReturn(List.of());
            FluentFuture future =
                    FluentFuture.from(
                            Futures.immediateFailedFuture(new ExecutionException("test", null)));
            when(dao.writeData(any(), any(), any())).thenReturn(future);

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.addAppWithConsent(consentType, TEST)).isFalse();
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testAddAppWithConsent() {
        MockitoSession staticMockSessionLocal = null;
        try {
            String consentType = AppSearchAppConsentDao.APPS_WITH_CONSENT;
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchAppConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            AppSearchAppConsentDao dao = Mockito.mock(AppSearchAppConsentDao.class);
            ExtendedMockito.doReturn(dao)
                    .when(
                            () ->
                                    AppSearchAppConsentDao.readConsentData(
                                            any(), any(), any(), eq(consentType)));
            when(dao.getApps()).thenReturn(List.of());
            AppSearchBatchResult<String, Void> result = Mockito.mock(AppSearchBatchResult.class);
            FluentFuture future = FluentFuture.from(Futures.immediateFuture(result));
            when(dao.writeData(any(), any(), any())).thenReturn(future);

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            // No exceptions are thrown.
            assertThat(appSearchConsentWorker.addAppWithConsent(consentType, TEST)).isTrue();
            verify(dao, atLeastOnce()).getApps();
            verify(dao, atLeastOnce()).writeData(any(), any(), any());
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRemoveAppWithConsent_null() {
        MockitoSession staticMockSessionLocal = null;
        try {
            String consentType = AppSearchAppConsentDao.APPS_WITH_CONSENT;
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchAppConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(null)
                    .when(() -> AppSearchAppConsentDao.readConsentData(any(), any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            // No exceptions are thrown.
            appSearchConsentWorker.removeAppWithConsent(consentType, TEST);
            ExtendedMockito.verify(
                    () -> AppSearchAppConsentDao.getRowId(any(), eq(consentType)), never());
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRemoveAppWithConsent_failure() {
        MockitoSession staticMockSessionLocal = null;
        try {
            String consentType = AppSearchAppConsentDao.APPS_WITH_CONSENT;
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchAppConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            AppSearchAppConsentDao dao = Mockito.mock(AppSearchAppConsentDao.class);
            ExtendedMockito.doReturn(dao)
                    .when(
                            () ->
                                    AppSearchAppConsentDao.readConsentData(
                                            any(), any(), any(), eq(consentType)));
            when(dao.getApps()).thenReturn(List.of(TEST));
            FluentFuture future =
                    FluentFuture.from(
                            Futures.immediateFailedFuture(new ExecutionException("test", null)));
            when(dao.writeData(any(), any(), any())).thenReturn(future);

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () -> appSearchConsentWorker.removeAppWithConsent(consentType, TEST));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRemoveAppWithConsent() {
        MockitoSession staticMockSessionLocal = null;
        try {
            String consentType = AppSearchAppConsentDao.APPS_WITH_CONSENT;
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchAppConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            AppSearchAppConsentDao dao = Mockito.mock(AppSearchAppConsentDao.class);
            ExtendedMockito.doReturn(dao)
                    .when(
                            () ->
                                    AppSearchAppConsentDao.readConsentData(
                                            any(), any(), any(), eq(consentType)));

            when(dao.getApps()).thenReturn(List.of(TEST));
            AppSearchBatchResult<String, Void> result = Mockito.mock(AppSearchBatchResult.class);
            FluentFuture future = FluentFuture.from(Futures.immediateFuture(result));
            when(dao.writeData(any(), any(), any())).thenReturn(future);

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            // No exceptions are thrown.
            appSearchConsentWorker.removeAppWithConsent(consentType, TEST);
            verify(dao, atLeastOnce()).getApps();
            verify(dao, atLeastOnce()).writeData(any(), any(), any());
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testWasNotificationDisplayed() {
        runNotificationDisplayedTest(true);
    }

    @Test
    public void testNotificationNotDisplayed() {
        runNotificationDisplayedTest(false);
    }

    private void runNotificationDisplayedTest(boolean displayed) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchNotificationDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(displayed)
                    .when(
                            () ->
                                    AppSearchNotificationDao.wasNotificationDisplayed(
                                            any(), any(), any()));

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.wasNotificationDisplayed()).isEqualTo(displayed);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testWasGaUxNotificationDisplayed() {
        runGaUxNotificationDisplayedTest(true);
    }

    @Test
    public void testGaUxNotificationNotDisplayed() {
        runGaUxNotificationDisplayedTest(false);
    }

    private void runGaUxNotificationDisplayedTest(boolean displayed) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchNotificationDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(displayed)
                    .when(
                            () ->
                                    AppSearchNotificationDao.wasGaUxNotificationDisplayed(
                                            any(), any(), any()));

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.wasGaUxNotificationDisplayed()).isEqualTo(displayed);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRecordNotificationDisplayed_failure() {
        runRecordNotificationDisplayedTestFailure(/* isBetaUx= */ true);
    }

    @Test
    public void testRecordGaUxNotificationDisplayed_faiure() {
        runRecordNotificationDisplayedTestFailure(/* isBetaUx= */ false);
    }

    private void runRecordNotificationDisplayedTestFailure(boolean isBetaUx) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            if (isBetaUx) {
                RuntimeException e =
                        assertThrows(
                                RuntimeException.class, () -> worker.recordNotificationDisplayed());
                assertThat(e.getMessage())
                        .isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
            } else {
                RuntimeException e =
                        assertThrows(
                                RuntimeException.class,
                                () -> worker.recordGaUxNotificationDisplayed());
                assertThat(e.getMessage())
                        .isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
            }
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRecordNotificationDisplayed() {
        runRecordNotificationDisplayed(/* isBetaUx= */ true);
    }

    @Test
    public void testRecordGaUxNotificationDisplayed() {
        runRecordNotificationDisplayed(/* isBetaUx= */ false);
    }

    private void runRecordNotificationDisplayed(boolean isBetaUx) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .mockStatic(AppSearchNotificationDao.class)
                            .spyStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initSuccessResponse();
            when(AppSearchNotificationDao.getRowId(eq("" + UID))).thenReturn("" + UID);
            // Verify that no exception is thrown.
            if (isBetaUx) {
                when(AppSearchNotificationDao.wasGaUxNotificationDisplayed(any(), any(), any()))
                        .thenReturn(false);
                AppSearchConsentWorker.getInstance(mContext).recordNotificationDisplayed();
            } else {
                when(AppSearchNotificationDao.wasNotificationDisplayed(any(), any(), any()))
                        .thenReturn(false);
                AppSearchConsentWorker.getInstance(mContext).recordGaUxNotificationDisplayed();
            }
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testGetPrivacySandboxFeature() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchInteractionsDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            PrivacySandboxFeatureType feature = PrivacySandboxFeatureType.PRIVACY_SANDBOX_RECONSENT;
            ExtendedMockito.doReturn(feature)
                    .when(
                            () ->
                                    AppSearchInteractionsDao.getPrivacySandboxFeatureType(
                                            any(), any(), any()));

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.getPrivacySandboxFeature()).isEqualTo(feature);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testSetCurrentPrivacySandboxFeature_failure() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    worker.setCurrentPrivacySandboxFeature(
                                            PrivacySandboxFeatureType.PRIVACY_SANDBOX_RECONSENT));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testSetCurrentPrivacySandboxFeature() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .spyStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initSuccessResponse();
            // Verify that no exception is thrown.
            PrivacySandboxFeatureType feature =
                    PrivacySandboxFeatureType.PRIVACY_SANDBOX_FIRST_CONSENT;
            AppSearchConsentWorker.getInstance(mContext).setCurrentPrivacySandboxFeature(feature);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testGetInteractions() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchInteractionsDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            int umi = ConsentManager.NO_MANUAL_INTERACTIONS_RECORDED;
            ExtendedMockito.doReturn(umi)
                    .when(
                            () ->
                                    AppSearchInteractionsDao.getManualInteractions(
                                            any(), any(), any()));

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.getUserManualInteractionWithConsent()).isEqualTo(umi);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRecordUserManualInteractionWithConsent_failure() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            int interactions = ConsentManager.MANUAL_INTERACTIONS_RECORDED;
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () -> worker.recordUserManualInteractionWithConsent(interactions));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRecordUserManualInteractionWithConsent() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .mockStatic(AppSearchInteractionsDao.class)
                            .spyStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initSuccessResponse();
            when(AppSearchInteractionsDao.getRowId(any(), any())).thenReturn("" + UID);
            // Verify that no exception is thrown.
            int interactions = ConsentManager.MANUAL_INTERACTIONS_RECORDED;
            AppSearchConsentWorker.getInstance(mContext)
                    .recordUserManualInteractionWithConsent(interactions);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testGetBlockedTopics() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchTopicsConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(mTopics)
                    .when(() -> AppSearchTopicsConsentDao.getBlockedTopics(any(), any(), any()));

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.getBlockedTopics()).isEqualTo(mTopics);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRecordBlockedTopics_failure() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            RuntimeException e =
                    assertThrows(RuntimeException.class, () -> worker.recordBlockedTopic(TOPIC1));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRecordBlockedTopics_new() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .mockStatic(AppSearchTopicsConsentDao.class)
                            .spyStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initSuccessResponse();
            String query = "" + UID;
            ExtendedMockito.doReturn(query).when(() -> AppSearchTopicsConsentDao.getQuery(any()));
            ExtendedMockito.doReturn(null)
                    .when(() -> AppSearchTopicsConsentDao.readConsentData(any(), any(), any()));

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            appSearchConsentWorker.recordBlockedTopic(TOPIC1);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRecordBlockedTopics() throws Exception {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .mockStatic(AppSearchTopicsConsentDao.class)
                            .spyStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            String query = "" + UID;
            ExtendedMockito.doReturn(query).when(() -> AppSearchTopicsConsentDao.getQuery(any()));
            AppSearchTopicsConsentDao dao = Mockito.mock(AppSearchTopicsConsentDao.class);
            ExtendedMockito.doReturn(dao)
                    .when(() -> AppSearchTopicsConsentDao.readConsentData(any(), any(), any()));
            AppSearchBatchResult<String, Void> result = Mockito.mock(AppSearchBatchResult.class);
            when(dao.writeData(any(), any(), any()))
                    .thenReturn(FluentFuture.from(Futures.immediateFuture(result)));

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            appSearchConsentWorker.recordBlockedTopic(TOPIC1);
            verify(dao).addBlockedTopic(TOPIC1);
            verify(dao).writeData(any(), any(), any());
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRecordUnblockedTopics_failure() throws Exception {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .mockStatic(AppSearchTopicsConsentDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            AppSearchTopicsConsentDao dao = Mockito.mock(AppSearchTopicsConsentDao.class);
            ExtendedMockito.doReturn(dao)
                    .when(() -> AppSearchTopicsConsentDao.readConsentData(any(), any(), any()));
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            when(dao.writeData(any(), any(), any()))
                    .thenReturn(
                            FluentFuture.from(
                                    Futures.immediateFailedFuture(new InterruptedException())));

            RuntimeException e =
                    assertThrows(RuntimeException.class, () -> worker.recordUnblockedTopic(TOPIC1));
            verify(dao).removeBlockedTopic(TOPIC1);
            verify(dao).writeData(any(), any(), any());
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRecordUnblockedTopics_new() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .mockStatic(AppSearchTopicsConsentDao.class)
                            .spyStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            String query = "" + UID;
            ExtendedMockito.doReturn(query).when(() -> AppSearchTopicsConsentDao.getQuery(any()));
            ExtendedMockito.doReturn(null)
                    .when(() -> AppSearchTopicsConsentDao.readConsentData(any(), any(), any()));

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            appSearchConsentWorker.recordUnblockedTopic(TOPIC1);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testRecordUnblockedTopics() throws Exception {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .mockStatic(AppSearchTopicsConsentDao.class)
                            .spyStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            String query = "" + UID;
            ExtendedMockito.doReturn(query).when(() -> AppSearchTopicsConsentDao.getQuery(any()));
            AppSearchTopicsConsentDao dao = Mockito.mock(AppSearchTopicsConsentDao.class);
            ExtendedMockito.doReturn(dao)
                    .when(() -> AppSearchTopicsConsentDao.readConsentData(any(), any(), any()));
            AppSearchBatchResult<String, Void> result = Mockito.mock(AppSearchBatchResult.class);
            when(dao.writeData(any(), any(), any()))
                    .thenReturn(FluentFuture.from(Futures.immediateFuture(result)));

            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            appSearchConsentWorker.recordUnblockedTopic(TOPIC1);
            verify(dao).removeBlockedTopic(TOPIC1);
            verify(dao).writeData(any(), any(), any());
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testClearBlockedTopics_failure() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .spyStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            RuntimeException e =
                    assertThrows(RuntimeException.class, () -> worker.clearBlockedTopics());
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testClearBlockedTopics() {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .spyStatic(UserHandle.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initSuccessResponse();
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            // Verify no exceptions are thrown.
            appSearchConsentWorker.clearBlockedTopics();
        } finally {
            staticMockSessionLocal.finishMocking();
        }
    }

    private void initSuccessResponse() {
        AppSearchSession mockSession = Mockito.mock(AppSearchSession.class);
        UserHandle mockUserHandle = Mockito.mock(UserHandle.class);
        Mockito.when(UserHandle.getUserHandleForUid(Binder.getCallingUid()))
                .thenReturn(mockUserHandle);
        Mockito.when(mockUserHandle.getIdentifier()).thenReturn(UID);
        ExtendedMockito.doReturn(Futures.immediateFuture(mockSession))
                .when(() -> PlatformStorage.createSearchSessionAsync(any()));
        verify(mockSession, atMost(1)).setSchemaAsync(any(SetSchemaRequest.class));

        SetSchemaResponse mockResponse = Mockito.mock(SetSchemaResponse.class);
        when(mockSession.setSchemaAsync(any(SetSchemaRequest.class)))
                .thenReturn(Futures.immediateFuture(mockResponse));
        AppSearchBatchResult<String, Void> result = Mockito.mock(AppSearchBatchResult.class);
        when(mockSession.putAsync(any())).thenReturn(Futures.immediateFuture(result));

        verify(mockResponse, atMost(1)).getMigrationFailures();
        when(mockResponse.getMigrationFailures()).thenReturn(List.of());
    }

    private void initFailureResponse() {
        AppSearchSession mockSession = Mockito.mock(AppSearchSession.class);
        ExtendedMockito.doReturn(Futures.immediateFuture(mockSession))
                .when(() -> PlatformStorage.createSearchSessionAsync(any()));
        verify(mockSession, atMost(1)).setSchemaAsync(any(SetSchemaRequest.class));

        SetSchemaResponse mockResponse = Mockito.mock(SetSchemaResponse.class);
        when(mockSession.setSchemaAsync(any(SetSchemaRequest.class)))
                .thenReturn(Futures.immediateFuture(mockResponse));

        AppSearchResult mockResult = Mockito.mock(AppSearchResult.class);
        SetSchemaResponse.MigrationFailure failure =
                new SetSchemaResponse.MigrationFailure(
                        /* namespace= */ TEST,
                        /* id= */ TEST,
                        /* schemaType= */ TEST,
                        /* appSearchResult= */ mockResult);
        when(mockResponse.getMigrationFailures()).thenReturn(List.of(failure));
    }

    @Test
    public void isAdIdEnabledTest_trueBit() {
        isAdIdEnabledTest(true);
    }

    @Test
    public void isAdIdEnabledTest_falseBit() {
        isAdIdEnabledTest(false);
    }

    private void isAdIdEnabledTest(boolean isAdIdEnabled) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchUxStatesDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(isAdIdEnabled)
                    .when(() -> AppSearchUxStatesDao.readIsAdIdEnabled(any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.isAdIdEnabled()).isEqualTo(isAdIdEnabled);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void setAdIdEnabledTest_trueBit() {
        setAdIdEnabledTest(true);
    }

    @Test
    public void setAdIdEnabledTest_falseBit() {
        setAdIdEnabledTest(false);
    }

    private void setAdIdEnabledTest(boolean isAdIdEnabled) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class, () -> worker.setAdIdEnabled(isAdIdEnabled));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void isU18AccountTest_trueBit() {
        isU18AccountTest(true);
    }

    @Test
    public void isU18AccountTest_falseBit() {
        isU18AccountTest(false);
    }

    private void isU18AccountTest(boolean isU18Account) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchUxStatesDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(isU18Account)
                    .when(() -> AppSearchUxStatesDao.readIsU18Account(any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.isU18Account()).isEqualTo(isU18Account);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void setU18AccountTest_trueBit() {
        setU18AccountTest(true);
    }

    @Test
    public void setU18AccountTest_falseBit() {
        setU18AccountTest(false);
    }

    private void setU18AccountTest(boolean isU18Account) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            RuntimeException e =
                    assertThrows(RuntimeException.class, () -> worker.setU18Account(isU18Account));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void isEntryPointEnabledTest_trueBit() {
        isEntryPointEnabledTest(true);
    }

    @Test
    public void isEntryPointEnabledTest_falseBit() {
        isEntryPointEnabledTest(false);
    }

    private void isEntryPointEnabledTest(boolean isEntryPointEnabled) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchUxStatesDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(isEntryPointEnabled)
                    .when(() -> AppSearchUxStatesDao.readIsEntryPointEnabled(any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.isEntryPointEnabled()).isEqualTo(isEntryPointEnabled);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void setEntryPointEnabledTest_trueBit() {
        setEntryPointEnabledTest(true);
    }

    @Test
    public void setEntryPointEnabledTest_falseBit() {
        setEntryPointEnabledTest(false);
    }

    private void setEntryPointEnabledTest(boolean isEntryPointEnabled) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () -> worker.setEntryPointEnabled(isEntryPointEnabled));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void isAdultAccountTest_trueBit() {
        isAdultAccountTest(true);
    }

    @Test
    public void isAdultAccountTest_falseBit() {
        isAdultAccountTest(false);
    }

    private void isAdultAccountTest(boolean isAdultAccount) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchUxStatesDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(isAdultAccount)
                    .when(() -> AppSearchUxStatesDao.readIsAdultAccount(any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.isAdultAccount()).isEqualTo(isAdultAccount);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void setAdultAccountTest_trueBit() {
        setAdultAccountTest(true);
    }

    @Test
    public void setAdultAccountTest_falseBit() {
        setAdultAccountTest(false);
    }

    private void setAdultAccountTest(boolean isAdultAccount) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class, () -> worker.setAdultAccount(isAdultAccount));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void wasU18NotificationDisplayedTest_trueBit() {
        wasU18NotificationDisplayedTest(true);
    }

    @Test
    public void wasU18NotificationDisplayedTest_falseBit() {
        wasU18NotificationDisplayedTest(false);
    }

    private void wasU18NotificationDisplayedTest(boolean wasU18NotificationDisplayed) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(AppSearchUxStatesDao.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(wasU18NotificationDisplayed)
                    .when(
                            () ->
                                    AppSearchUxStatesDao.readIsU18NotificationDisplayed(
                                            any(), any(), any()));
            AppSearchConsentWorker appSearchConsentWorker =
                    AppSearchConsentWorker.getInstance(mContext);
            assertThat(appSearchConsentWorker.wasU18NotificationDisplayed())
                    .isEqualTo(wasU18NotificationDisplayed);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void setU18NotificationDisplayedTest_trueBit() {
        setU18NotificationDisplayedTest(true);
    }

    @Test
    public void setU18NotificationDisplayedTest_falseBit() {
        setU18NotificationDisplayedTest(false);
    }

    private void setU18NotificationDisplayedTest(boolean wasU18NotificationDisplayed) {
        MockitoSession staticMockSessionLocal = null;
        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(PlatformStorage.class)
                            .strictness(Strictness.WARN)
                            .initMocks(this)
                            .startMocking();
            initFailureResponse();
            AppSearchConsentWorker worker = AppSearchConsentWorker.getInstance(mContext);
            RuntimeException e =
                    assertThrows(
                            RuntimeException.class,
                            () -> worker.setU18NotificationDisplayed(wasU18NotificationDisplayed));
            assertThat(e.getMessage()).isEqualTo(ConsentConstants.ERROR_MESSAGE_APPSEARCH_FAILURE);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }
}
