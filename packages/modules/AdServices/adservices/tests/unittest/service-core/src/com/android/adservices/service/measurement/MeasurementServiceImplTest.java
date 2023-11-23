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

package com.android.adservices.service.measurement;

import static android.adservices.common.AdServicesStatusUtils.STATUS_BACKGROUND_CALLER;
import static android.adservices.common.AdServicesStatusUtils.STATUS_CALLER_NOT_ALLOWED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_KILLSWITCH_ENABLED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_PERMISSION_NOT_REQUESTED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_RATE_LIMIT_REACHED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;
import static android.adservices.common.AdServicesStatusUtils.STATUS_USER_CONSENT_REVOKED;
import static android.adservices.measurement.MeasurementManager.MEASUREMENT_API_STATE_DISABLED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.CallerMetadata;
import android.adservices.measurement.DeletionParam;
import android.adservices.measurement.DeletionRequest;
import android.adservices.measurement.IMeasurementApiStatusCallback;
import android.adservices.measurement.IMeasurementCallback;
import android.adservices.measurement.MeasurementErrorResponse;
import android.adservices.measurement.MeasurementManager;
import android.adservices.measurement.RegistrationRequest;
import android.adservices.measurement.StatusParam;
import android.adservices.measurement.WebSourceParams;
import android.adservices.measurement.WebSourceRegistrationRequest;
import android.adservices.measurement.WebSourceRegistrationRequestInternal;
import android.adservices.measurement.WebTriggerParams;
import android.adservices.measurement.WebTriggerRegistrationRequest;
import android.adservices.measurement.WebTriggerRegistrationRequestInternal;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.SystemClock;
import android.test.mock.MockContext;

import androidx.test.filters.SmallTest;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.AppImportanceFilter;
import com.android.adservices.service.common.PermissionHelper;
import com.android.adservices.service.common.Throttler;
import com.android.adservices.service.consent.AdServicesApiConsent;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.ApiCallStats;
import com.android.adservices.service.stats.Clock;
import com.android.compatibility.common.util.TestUtils;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Unit tests for {@link MeasurementServiceImpl} */
@SmallTest
public final class MeasurementServiceImplTest {

    private static final String ALLOW_ALL_PACKAGES = "*";
    private static final Uri APP_DESTINATION = Uri.parse("android-app://test.app-destination");
    private static final String APP_PACKAGE_NAME = "app.package.name";
    private static final Uri REGISTRATION_URI = Uri.parse("https://registration-uri.test");
    private static final String SDK_PACKAGE_NAME = "sdk.package.name";
    private static final int TIMEOUT = 5_000;
    private static final Uri WEB_DESTINATION = Uri.parse("https://web-destination-uri.test");

    @Mock private AdServicesLogger mMockAdServicesLogger;
    @Mock private AppImportanceFilter mMockAppImportanceFilter;
    @Mock private ConsentManager mMockConsentManager;
    @Mock private Flags mMockFlags;
    @Mock private MeasurementImpl mMockMeasurementImpl;
    @Mock private Throttler mMockThrottler;
    @Mock private MockContext mMockContext;

    private MeasurementServiceImpl mMeasurementServiceImpl;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mMeasurementServiceImpl = createServiceWithMocks();
    }

    @Test
    public void testRegisterSource_success() throws Exception {
        runRunMocks(
                Api.REGISTER_SOURCE,
                new AccessDenier(),
                () -> {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    final List<Integer> list = new ArrayList<>();

                    createServiceWithMocks()
                            .register(
                                    createRegistrationSourceRequest(),
                                    createCallerMetadata(),
                                    new IMeasurementCallback.Stub() {
                                        @Override
                                        public void onResult() {
                                            list.add(STATUS_SUCCESS);
                                            countDownLatch.countDown();
                                        }

                                        @Override
                                        public void onFailure(
                                                MeasurementErrorResponse responseParcel) {}
                                    });

                    assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
                    assertThat(list.size()).isEqualTo(1);
                    assertThat(list.get(0)).isEqualTo(STATUS_SUCCESS);
                    assertPackageNameLogged();
                });
    }

    private void registerSourceAndAssertFailure(@AdServicesStatusUtils.StatusCode int status)
            throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final List<MeasurementErrorResponse> errorContainer = new ArrayList<>();
        createServiceWithMocks()
                .register(
                        createRegistrationSourceRequest(),
                        createCallerMetadata(),
                        new IMeasurementCallback.Stub() {
                            @Override
                            public void onResult() {}

                            @Override
                            public void onFailure(MeasurementErrorResponse responseParcel) {
                                errorContainer.add(responseParcel);
                                countDownLatch.countDown();
                            }
                        });

        assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
        verify(mMockMeasurementImpl, never()).register(any(), anyBoolean(), anyLong());
        Assert.assertEquals(1, errorContainer.size());
        Assert.assertEquals(status, errorContainer.get(0).getStatusCode());
    }

    @Test
    public void testRegisterSource_failureByAppPackagePpApiAccessResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_SOURCE,
                new AccessDenier().deniedByAppPackagePpApiApp(),
                () -> registerSourceAndAssertFailure(STATUS_CALLER_NOT_ALLOWED));
    }

    @Test
    public void testRegisterSource_failureByAttributionPermissionResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_SOURCE,
                new AccessDenier().deniedByAttributionPermission(),
                () -> registerSourceAndAssertFailure(STATUS_PERMISSION_NOT_REQUESTED));
    }

    @Test
    public void testRegisterSource_failureByConsentResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_SOURCE,
                new AccessDenier().deniedByConsent(),
                () -> registerSourceAndAssertFailure(STATUS_USER_CONSENT_REVOKED));
    }

    @Test
    public void testRegisterSource_failureByForegroundEnforcementAccessResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_SOURCE,
                new AccessDenier().deniedByForegroundEnforcement(),
                () -> registerSourceAndAssertFailure(STATUS_BACKGROUND_CALLER));
    }

    @Test
    public void testRegisterSource_failureByKillSwitchAccessResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_SOURCE,
                new AccessDenier().deniedByKillSwitch(),
                () -> registerSourceAndAssertFailure(STATUS_KILLSWITCH_ENABLED));
    }

    @Test
    public void testRegisterSource_failureByThrottler() throws Exception {
        runRunMocks(
                Api.REGISTER_SOURCE,
                new AccessDenier().deniedByThrottler(),
                () -> registerSourceAndAssertFailure(STATUS_RATE_LIMIT_REACHED));
    }

    @Test
    public void testRegisterTrigger_success() throws Exception {
        runRunMocks(
                Api.REGISTER_TRIGGER,
                new AccessDenier(),
                () -> {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    final List<Integer> list = new ArrayList<>();

                    createServiceWithMocks()
                            .register(
                                    createRegistrationTriggerRequest(),
                                    createCallerMetadata(),
                                    new IMeasurementCallback.Stub() {
                                        @Override
                                        public void onResult() {
                                            list.add(STATUS_SUCCESS);
                                            countDownLatch.countDown();
                                        }

                                        @Override
                                        public void onFailure(
                                                MeasurementErrorResponse responseParcel) {}
                                    });

                    assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
                    assertThat(list.size()).isEqualTo(1);
                    assertThat(list.get(0)).isEqualTo(STATUS_SUCCESS);
                    assertPackageNameLogged();
                });
    }

    private void registerTriggerAndAssertFailure(@AdServicesStatusUtils.StatusCode int status)
            throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final List<MeasurementErrorResponse> errorContainer = new ArrayList<>();
        createServiceWithMocks()
                .register(
                        createRegistrationTriggerRequest(),
                        createCallerMetadata(),
                        new IMeasurementCallback.Stub() {
                            @Override
                            public void onResult() {}

                            @Override
                            public void onFailure(MeasurementErrorResponse responseParcel) {
                                errorContainer.add(responseParcel);
                                countDownLatch.countDown();
                            }
                        });

        assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
        verify(mMockMeasurementImpl, never()).register(any(), anyBoolean(), anyLong());
        Assert.assertEquals(1, errorContainer.size());
        Assert.assertEquals(status, errorContainer.get(0).getStatusCode());
    }

    @Test
    public void testRegisterTrigger_failureByAppPackagePpApiAccessResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_TRIGGER,
                new AccessDenier().deniedByAppPackagePpApiApp(),
                () -> registerTriggerAndAssertFailure(STATUS_CALLER_NOT_ALLOWED));
    }

    @Test
    public void testRegisterTrigger_failureByAttributionPermissionResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_TRIGGER,
                new AccessDenier().deniedByAttributionPermission(),
                () -> registerTriggerAndAssertFailure(STATUS_PERMISSION_NOT_REQUESTED));
    }

    @Test
    public void testRegisterTrigger_failureByConsentResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_TRIGGER,
                new AccessDenier().deniedByConsent(),
                () -> registerTriggerAndAssertFailure(STATUS_USER_CONSENT_REVOKED));
    }

    @Test
    public void testRegisterTrigger_failureByForegroundEnforcementAccessResolver()
            throws Exception {
        runRunMocks(
                Api.REGISTER_TRIGGER,
                new AccessDenier().deniedByForegroundEnforcement(),
                () -> registerTriggerAndAssertFailure(STATUS_BACKGROUND_CALLER));
    }

    @Test
    public void testRegisterTrigger_failureByKillSwitchAccessResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_TRIGGER,
                new AccessDenier().deniedByKillSwitch(),
                () -> registerTriggerAndAssertFailure(STATUS_KILLSWITCH_ENABLED));
    }

    @Test
    public void testRegisterTrigger_failureByThrottler() throws Exception {
        runRunMocks(
                Api.REGISTER_TRIGGER,
                new AccessDenier().deniedByThrottler(),
                () -> registerTriggerAndAssertFailure(STATUS_RATE_LIMIT_REACHED));
    }

    @Test
    public void testRegister_invalidRequest_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        createServiceWithMocks()
                                .register(
                                        /* request = */ null,
                                        createCallerMetadata(),
                                        new IMeasurementCallback.Default()));
    }

    @Test
    public void testRegister_invalidCallerMetadata_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        createServiceWithMocks()
                                .register(
                                        createRegistrationSourceRequest(),
                                        /* callerMetadata = */ null,
                                        new IMeasurementCallback.Default()));
    }

    @Test
    public void testRegister_invalidCallback_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        createServiceWithMocks()
                                .register(
                                        createRegistrationSourceRequest(),
                                        createCallerMetadata(),
                                        /* callback = */ null));
    }

    @Test
    public void testDeleteRegistrations_success() throws Exception {
        runRunMocks(
                Api.DELETE_REGISTRATIONS,
                new AccessDenier(),
                () -> {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    final List<Integer> list = new ArrayList<>();

                    mMeasurementServiceImpl.deleteRegistrations(
                            createDeletionRequest(),
                            createCallerMetadata(),
                            new IMeasurementCallback.Stub() {
                                @Override
                                public void onResult() {
                                    list.add(STATUS_SUCCESS);
                                    countDownLatch.countDown();
                                }

                                @Override
                                public void onFailure(MeasurementErrorResponse responseParcel) {}
                            });

                    assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
                    assertThat(list.get(0)).isEqualTo(STATUS_SUCCESS);
                    assertThat(list.size()).isEqualTo(1);
                    assertPackageNameLogged();
                });
    }

    private void deleteRegistrationsAndAssertFailure(@AdServicesStatusUtils.StatusCode int status)
            throws InterruptedException {
        final List<MeasurementErrorResponse> errorContainer = new ArrayList<>();
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        createServiceWithMocks()
                .deleteRegistrations(
                        createDeletionRequest(),
                        createCallerMetadata(),
                        new IMeasurementCallback.Stub() {
                            @Override
                            public void onResult() {}

                            @Override
                            public void onFailure(MeasurementErrorResponse errorResponse) {
                                errorContainer.add(errorResponse);
                                countDownLatch.countDown();
                            }
                        });

        assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
        verify(mMockMeasurementImpl, never()).deleteRegistrations(any());
        Assert.assertEquals(1, errorContainer.size());
        Assert.assertEquals(status, errorContainer.get(0).getStatusCode());
    }

    @Test
    public void testDeleteRegistrations_failureByAppPackagePpApiAccessResolver() throws Exception {
        runRunMocks(
                Api.DELETE_REGISTRATIONS,
                new AccessDenier().deniedByAppPackagePpApiApp(),
                () -> deleteRegistrationsAndAssertFailure(STATUS_CALLER_NOT_ALLOWED));
    }

    @Test
    public void testDeleteRegistrations_failureByAppPackageWebContextClientAccessResolver()
            throws Exception {
        runRunMocks(
                Api.DELETE_REGISTRATIONS,
                new AccessDenier().deniedByAppPackageWebContextClientApp(),
                () -> deleteRegistrationsAndAssertFailure(STATUS_CALLER_NOT_ALLOWED));
    }

    @Test
    public void testDeleteRegistrations_failureByForegroundEnforcementAccessResolver()
            throws Exception {
        runRunMocks(
                Api.DELETE_REGISTRATIONS,
                new AccessDenier().deniedByForegroundEnforcement(),
                () -> deleteRegistrationsAndAssertFailure(STATUS_BACKGROUND_CALLER));
    }

    @Test
    public void testDeleteRegistrations_failureByKillSwitchAccessResolver() throws Exception {
        runRunMocks(
                Api.DELETE_REGISTRATIONS,
                new AccessDenier().deniedByKillSwitch(),
                () -> deleteRegistrationsAndAssertFailure(STATUS_KILLSWITCH_ENABLED));
    }

    @Test
    public void testDeleteRegistrations_failureByThrottler() throws Exception {
        runRunMocks(
                Api.DELETE_REGISTRATIONS,
                new AccessDenier().deniedByThrottler(),
                () -> deleteRegistrationsAndAssertFailure(STATUS_RATE_LIMIT_REACHED));
    }

    @Test
    public void testDeleteRegistrations_invalidRequest_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        createServiceWithMocks()
                                .deleteRegistrations(
                                        /* request = */ null,
                                        createCallerMetadata(),
                                        new IMeasurementCallback.Default()));
    }

    @Test
    public void testDeleteRegistrations_invalidCallerMetadata_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        createServiceWithMocks()
                                .deleteRegistrations(
                                        createDeletionRequest(),
                                        /* callerMetadata = */ null,
                                        new IMeasurementCallback.Default()));
    }

    @Test
    public void testDeleteRegistrations_invalidCallback_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        createServiceWithMocks()
                                .deleteRegistrations(
                                        createDeletionRequest(),
                                        createCallerMetadata(),
                                        /* callback = */ null));
    }

    @Test
    public void testGetMeasurementApiStatus_success() throws Exception {
        runRunMocks(
                Api.STATUS,
                new AccessDenier(),
                () -> {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    final AtomicInteger resultWrapper = new AtomicInteger();

                    createServiceWithMocks()
                            .getMeasurementApiStatus(
                                    createStatusParam(),
                                    createCallerMetadata(),
                                    new IMeasurementApiStatusCallback.Stub() {
                                        @Override
                                        public void onResult(int result) {
                                            resultWrapper.set(result);
                                            countDownLatch.countDown();
                                        }
                                    });

                    assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
                    assertThat(resultWrapper.get())
                            .isEqualTo(MeasurementManager.MEASUREMENT_API_STATE_ENABLED);
                    assertPackageNameLogged();
                });
    }

    private void getMeasurementApiStatusAndAssertFailure() throws InterruptedException {
        final CountDownLatch countDownLatchAny = new CountDownLatch(1);
        final AtomicInteger resultWrapper = new AtomicInteger();

        createServiceWithMocks()
                .getMeasurementApiStatus(
                        createStatusParam(),
                        createCallerMetadata(),
                        new IMeasurementApiStatusCallback.Stub() {
                            @Override
                            public void onResult(int result) {
                                resultWrapper.set(result);
                                countDownLatchAny.countDown();
                            }
                        });

        assertThat(countDownLatchAny.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(resultWrapper.get()).isEqualTo(MEASUREMENT_API_STATE_DISABLED);
    }

    @Test
    public void testGetMeasurementApiStatus_failureByAppPackagePpApiAccessResolver()
            throws Exception {
        runRunMocks(
                Api.STATUS,
                new AccessDenier().deniedByAppPackagePpApiApp(),
                this::getMeasurementApiStatusAndAssertFailure);
    }

    @Test
    public void testGetMeasurementApiStatus_failureByForegroundEnforcementAccessResolver()
            throws Exception {
        runRunMocks(
                Api.STATUS,
                new AccessDenier().deniedByForegroundEnforcement(),
                this::getMeasurementApiStatusAndAssertFailure);
    }

    @Test
    public void testGetMeasurementApiStatus_failureByKillSwitchAccessResolver() throws Exception {
        runRunMocks(
                Api.STATUS,
                new AccessDenier().deniedByKillSwitch(),
                this::getMeasurementApiStatusAndAssertFailure);
    }

    @Test
    public void testGetMeasurementApiStatus_failureByConsentAccessResolver() throws Exception {
        runRunMocks(
                Api.STATUS,
                new AccessDenier().deniedByConsent(),
                this::getMeasurementApiStatusAndAssertFailure);
    }

    @Test
    public void testGetMeasurementApiStatus_invalidRequest_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mMeasurementServiceImpl.getMeasurementApiStatus(
                                /* request = */ null,
                                createCallerMetadata(),
                                new IMeasurementApiStatusCallback.Default()));
    }

    @Test
    public void testGetMeasurementApiStatus_invalidCallerMetadata_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mMeasurementServiceImpl.getMeasurementApiStatus(
                                createStatusParam(),
                                /* callerMetadata = */ null,
                                new IMeasurementApiStatusCallback.Default()));
    }

    @Test
    public void testGetMeasurementApiStatus_invalidCallback_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mMeasurementServiceImpl.getMeasurementApiStatus(
                                createStatusParam(),
                                createCallerMetadata(),
                                /* callback = */ null));
    }

    @Test
    public void registerWebSource_success() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_SOURCE,
                new AccessDenier(),
                () -> {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    final List<Integer> list = new ArrayList<>();

                    createServiceWithMocks()
                            .registerWebSource(
                                    createWebSourceRegistrationRequest(),
                                    createCallerMetadata(),
                                    new IMeasurementCallback.Stub() {
                                        @Override
                                        public void onResult() {
                                            list.add(STATUS_SUCCESS);
                                            countDownLatch.countDown();
                                        }

                                        @Override
                                        public void onFailure(
                                                MeasurementErrorResponse
                                                        measurementErrorResponse) {}
                                    });

                    assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
                    assertThat(list.get(0)).isEqualTo(STATUS_SUCCESS);
                    assertThat(list.size()).isEqualTo(1);
                    assertPackageNameLogged();
                });
    }

    private void registerWebSourceAndAssertFailure(@AdServicesStatusUtils.StatusCode int status)
            throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final List<MeasurementErrorResponse> errorContainer = new ArrayList<>();
        createServiceWithMocks()
                .registerWebSource(
                        createWebSourceRegistrationRequest(),
                        createCallerMetadata(),
                        new IMeasurementCallback.Stub() {
                            @Override
                            public void onResult() {}

                            @Override
                            public void onFailure(MeasurementErrorResponse responseParcel) {
                                errorContainer.add(responseParcel);
                                countDownLatch.countDown();
                            }
                        });

        assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
        verify(mMockMeasurementImpl, never()).registerWebSource(any(), anyBoolean(), anyLong());
        Assert.assertEquals(1, errorContainer.size());
        Assert.assertEquals(status, errorContainer.get(0).getStatusCode());
    }

    @Test
    public void testRegisterWebSource_failureByAppPackagePpApiAccessResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_SOURCE,
                new AccessDenier().deniedByAppPackagePpApiApp(),
                () -> registerWebSourceAndAssertFailure(STATUS_CALLER_NOT_ALLOWED));
    }

    @Test
    public void testRegisterWebSource_failureByAppPackageWebContextClientAccessResolver()
            throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_SOURCE,
                new AccessDenier().deniedByAppPackageWebContextClientApp(),
                () -> registerWebSourceAndAssertFailure(STATUS_CALLER_NOT_ALLOWED));
    }

    @Test
    public void testRegisterWebSource_failureByAttributionPermissionResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_SOURCE,
                new AccessDenier().deniedByAttributionPermission(),
                () -> registerWebSourceAndAssertFailure(STATUS_PERMISSION_NOT_REQUESTED));
    }

    @Test
    public void testRegisterWebSource_failureByConsentResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_SOURCE,
                new AccessDenier().deniedByConsent(),
                () -> registerWebSourceAndAssertFailure(STATUS_USER_CONSENT_REVOKED));
    }

    @Test
    public void testRegisterWebSource_failureByForegroundEnforcementAccessResolver()
            throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_SOURCE,
                new AccessDenier().deniedByForegroundEnforcement(),
                () -> registerWebSourceAndAssertFailure(STATUS_BACKGROUND_CALLER));
    }

    @Test
    public void testRegisterWebSource_failureByKillSwitchAccessResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_SOURCE,
                new AccessDenier().deniedByKillSwitch(),
                () -> registerWebSourceAndAssertFailure(STATUS_KILLSWITCH_ENABLED));
    }

    @Test
    public void testRegisterWebSource_failureByThrottler() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_SOURCE,
                new AccessDenier().deniedByThrottler(),
                () -> registerWebSourceAndAssertFailure(STATUS_RATE_LIMIT_REACHED));
    }

    @Test
    public void registerWebSource_invalidRequest_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mMeasurementServiceImpl.registerWebSource(
                                /* request = */ null,
                                createCallerMetadata(),
                                new IMeasurementCallback.Default()));
    }

    @Test
    public void registerWebSource_invalidCallerMetadata_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mMeasurementServiceImpl.registerWebSource(
                                createWebSourceRegistrationRequest(),
                                /* callerMetadata */ null,
                                new IMeasurementCallback.Default()));
    }

    @Test
    public void registerWebSource_invalidCallback_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mMeasurementServiceImpl.registerWebSource(
                                createWebSourceRegistrationRequest(),
                                createCallerMetadata(),
                                /* callback = */ null));
    }

    @Test
    public void registerWebTrigger_success() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_TRIGGER,
                new AccessDenier(),
                () -> {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    final List<Integer> list = new ArrayList<>();

                    createServiceWithMocks()
                            .registerWebTrigger(
                                    createWebTriggerRegistrationRequest(),
                                    createCallerMetadata(),
                                    new IMeasurementCallback.Stub() {
                                        @Override
                                        public void onResult() {
                                            list.add(STATUS_SUCCESS);
                                            countDownLatch.countDown();
                                        }

                                        @Override
                                        public void onFailure(
                                                MeasurementErrorResponse
                                                        measurementErrorResponse) {}
                                    });

                    assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
                    assertThat(list.get(0)).isEqualTo(STATUS_SUCCESS);
                    assertThat(list.size()).isEqualTo(1);
                    assertPackageNameLogged();
                });
    }

    private void registerWebTriggerAndAssertFailure(@AdServicesStatusUtils.StatusCode int status)
            throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final List<MeasurementErrorResponse> errorContainer = new ArrayList<>();
        createServiceWithMocks()
                .registerWebTrigger(
                        createWebTriggerRegistrationRequest(),
                        createCallerMetadata(),
                        new IMeasurementCallback.Stub() {
                            @Override
                            public void onResult() {}

                            @Override
                            public void onFailure(MeasurementErrorResponse responseParcel) {
                                errorContainer.add(responseParcel);
                                countDownLatch.countDown();
                            }
                        });

        assertThat(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue();
        verify(mMockMeasurementImpl, never()).registerWebTrigger(any(), anyBoolean(), anyLong());
        Assert.assertEquals(1, errorContainer.size());
        Assert.assertEquals(status, errorContainer.get(0).getStatusCode());
    }

    @Test
    public void testRegisterWebTrigger_failureByAppPackagePpApiAccessResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_TRIGGER,
                new AccessDenier().deniedByAppPackagePpApiApp(),
                () -> registerWebTriggerAndAssertFailure(STATUS_CALLER_NOT_ALLOWED));
    }

    @Test
    public void testRegisterWebTrigger_failureByAttributionPermissionResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_TRIGGER,
                new AccessDenier().deniedByAttributionPermission(),
                () -> registerWebTriggerAndAssertFailure(STATUS_PERMISSION_NOT_REQUESTED));
    }

    @Test
    public void testRegisterWebTrigger_failureByConsentResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_TRIGGER,
                new AccessDenier().deniedByConsent(),
                () -> registerWebTriggerAndAssertFailure(STATUS_USER_CONSENT_REVOKED));
    }

    @Test
    public void testRegisterWebTrigger_failureByForegroundEnforcementAccessResolver()
            throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_TRIGGER,
                new AccessDenier().deniedByForegroundEnforcement(),
                () -> registerWebTriggerAndAssertFailure(STATUS_BACKGROUND_CALLER));
    }

    @Test
    public void testRegisterWebTrigger_failureByKillSwitchAccessResolver() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_TRIGGER,
                new AccessDenier().deniedByKillSwitch(),
                () -> registerWebTriggerAndAssertFailure(STATUS_KILLSWITCH_ENABLED));
    }

    @Test
    public void testRegisterWebTrigger_failureByThrottler() throws Exception {
        runRunMocks(
                Api.REGISTER_WEB_TRIGGER,
                new AccessDenier().deniedByThrottler(),
                () -> registerWebTriggerAndAssertFailure(STATUS_RATE_LIMIT_REACHED));
    }

    @Test
    public void registerWebTrigger_invalidRequest_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mMeasurementServiceImpl.registerWebTrigger(
                                /* request = */ null,
                                createCallerMetadata(),
                                new IMeasurementCallback.Default()));
    }

    @Test
    public void registerWebTrigger_invalidCallerMetadata_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mMeasurementServiceImpl.registerWebTrigger(
                                createWebTriggerRegistrationRequest(),
                                /* callerMetadata */ null,
                                new IMeasurementCallback.Default()));
    }

    @Test
    public void registerWebTrigger_invalidCallback_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mMeasurementServiceImpl.registerWebTrigger(
                                createWebTriggerRegistrationRequest(),
                                createCallerMetadata(),
                                /* callback = */ null));
    }

    private RegistrationRequest createRegistrationSourceRequest() {
        return new RegistrationRequest.Builder(
                        RegistrationRequest.REGISTER_SOURCE,
                        Uri.parse("https://registration-uri.com"),
                        APP_PACKAGE_NAME,
                        SDK_PACKAGE_NAME)
                .build();
    }

    private RegistrationRequest createRegistrationTriggerRequest() {
        return new RegistrationRequest.Builder(
                        RegistrationRequest.REGISTER_TRIGGER,
                        Uri.parse("https://registration-uri.com"),
                        APP_PACKAGE_NAME,
                        SDK_PACKAGE_NAME)
                .build();
    }

    private WebSourceRegistrationRequestInternal createWebSourceRegistrationRequest() {
        WebSourceRegistrationRequest sourceRegistrationRequest =
                new WebSourceRegistrationRequest.Builder(
                                Collections.singletonList(
                                        new WebSourceParams.Builder(REGISTRATION_URI)
                                                .setDebugKeyAllowed(true)
                                                .build()),
                                Uri.parse("android-app//com.example"))
                        .setWebDestination(WEB_DESTINATION)
                        .setAppDestination(APP_DESTINATION)
                        .build();
        return new WebSourceRegistrationRequestInternal.Builder(
                        sourceRegistrationRequest, APP_PACKAGE_NAME, SDK_PACKAGE_NAME, 10000L)
                .build();
    }

    private WebTriggerRegistrationRequestInternal createWebTriggerRegistrationRequest() {
        WebTriggerRegistrationRequest webTriggerRegistrationRequest =
                new WebTriggerRegistrationRequest.Builder(
                                Collections.singletonList(
                                        new WebTriggerParams.Builder(REGISTRATION_URI)
                                                .setDebugKeyAllowed(true)
                                                .build()),
                                Uri.parse("android-app://com.example"))
                        .build();
        return new WebTriggerRegistrationRequestInternal.Builder(
                        webTriggerRegistrationRequest, APP_PACKAGE_NAME, SDK_PACKAGE_NAME)
                .build();
    }

    private DeletionParam createDeletionRequest() {
        return new DeletionParam.Builder(
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Instant.MIN,
                        Instant.MAX,
                        APP_PACKAGE_NAME,
                        SDK_PACKAGE_NAME)
                .setMatchBehavior(DeletionRequest.MATCH_BEHAVIOR_DELETE)
                .setDeletionMode(DeletionRequest.DELETION_MODE_ALL)
                .build();
    }

    private CallerMetadata createCallerMetadata() {
        return new CallerMetadata.Builder()
                .setBinderElapsedTimestamp(SystemClock.elapsedRealtime())
                .build();
    }

    private StatusParam createStatusParam() {
        return new StatusParam.Builder(APP_PACKAGE_NAME, SDK_PACKAGE_NAME).build();
    }

    private void assertPackageNameLogged() throws InterruptedException {
        // Sleep just a tiny fraction
        // Logging happens in a separate thread and happens after the result code is returned
        TimeUnit.MILLISECONDS.sleep(20);
        ArgumentCaptor<ApiCallStats> captorStatus = ArgumentCaptor.forClass(ApiCallStats.class);
        verify(mMockAdServicesLogger).logApiCallStats(captorStatus.capture());
        assertEquals(APP_PACKAGE_NAME, captorStatus.getValue().getAppPackageName());
        assertEquals(SDK_PACKAGE_NAME, captorStatus.getValue().getSdkPackageName());
    }

    private MeasurementServiceImpl createServiceWithMocks() {
        return new MeasurementServiceImpl(
                mMockMeasurementImpl,
                mMockContext,
                Clock.SYSTEM_CLOCK,
                mMockConsentManager,
                mMockThrottler,
                mMockFlags,
                mMockAdServicesLogger,
                mMockAppImportanceFilter);
    }

    private enum Api {
        DELETE_REGISTRATIONS,
        REGISTER_SOURCE,
        REGISTER_TRIGGER,
        REGISTER_WEB_SOURCE,
        REGISTER_WEB_TRIGGER,
        STATUS
    }

    private void runRunMocks(
            final Api api,
            final AccessDenier accessDenier,
            final TestUtils.RunnableWithThrow execute)
            throws Exception {

        final MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(Binder.class)
                        .mockStatic(FlagsFactory.class)
                        .mockStatic(PermissionHelper.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            // Flags
            ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

            // Binder
            ExtendedMockito.doReturn(1).when(Binder::getCallingUidOrThrow);

            switch (api) {
                case DELETE_REGISTRATIONS:
                    mockDeleteRegistrationsApi(accessDenier);
                    break;
                case REGISTER_SOURCE:
                    mockRegisterSourceApi(accessDenier);
                    break;
                case REGISTER_TRIGGER:
                    mockRegisterTriggerApi(accessDenier);
                    break;
                case REGISTER_WEB_SOURCE:
                    mockRegisterWebSourceApi(accessDenier);
                    break;
                case REGISTER_WEB_TRIGGER:
                    mockRegisterWebTriggerApi(accessDenier);
                    break;
                case STATUS:
                    mockStatusApi(accessDenier);
                    break;
                default:
                    break;
            }

            execute.run();

        } finally {
            mockitoSession.finishMocking();
        }
    }

    /**
     * Mock related objects for Delete Registrations API only. Same mock objects could be shared
     * among other APIs, but implementation could change. Therefore, each mock{API} should describe
     * the mock objects the API uses for readability and separation of concerns.
     *
     * @param accessDenier describes if API is allowed or denied by any barrier
     */
    private void mockDeleteRegistrationsApi(AccessDenier accessDenier) {
        // Throttler
        updateThrottlerDenied(accessDenier.mByThrottler);

        // Access Resolvers
        // Kill Switch Resolver
        final boolean killSwitchEnabled = accessDenier.mByKillSwitch;
        when(mMockFlags.getMeasurementApiDeleteRegistrationsKillSwitch())
                .thenReturn(killSwitchEnabled);

        // Foreground Resolver
        if (accessDenier.mByForegroundEnforcement) {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementDeleteRegistrations())
                    .thenReturn(true);
            doThrowExceptionCallerNotInForeground();
        } else {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementDeleteRegistrations())
                    .thenReturn(false);
        }

        // App Package Resolver Pp Api
        updateAppPackagePpApiResolverDenied(accessDenier.mByAppPackagePpApiApp);

        // App Package Resolver Web Context Client App
        updateAppPackageResolverWebAppDenied(accessDenier.mByAppPackageWebContextClientApp);

        // Results
        when(mMockMeasurementImpl.deleteRegistrations(any(DeletionParam.class)))
                .thenReturn(STATUS_SUCCESS);
    }

    /**
     * Mock related objects for Register Source API only. Same mock objects could be shared among
     * other APIs, but implementation could change. Therefore, each mock{API} should describe the
     * mock objects the API uses for readability and separation of concerns.
     *
     * @param accessDenier describes if API is allowed or denied by any barrier
     */
    private void mockRegisterSourceApi(AccessDenier accessDenier) {
        // Throttler
        updateThrottlerDenied(accessDenier.mByThrottler);

        // Access Resolvers
        // Kill Switch Resolver
        final boolean killSwitchEnabled = accessDenier.mByKillSwitch;
        when(mMockFlags.getMeasurementApiRegisterSourceKillSwitch()).thenReturn(killSwitchEnabled);

        // Foreground Resolver
        if (accessDenier.mByForegroundEnforcement) {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementRegisterSource())
                    .thenReturn(true);
            doThrowExceptionCallerNotInForeground();
        } else {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementRegisterSource())
                    .thenReturn(false);
        }

        // App Package Resolver Pp Api
        updateAppPackagePpApiResolverDenied(accessDenier.mByAppPackagePpApiApp);

        // Consent Resolver
        updateConsentDenied(accessDenier.mByConsent);

        // PermissionHelper
        updateAttributionPermissionDenied(accessDenier.mByAttributionPermission);

        // Results
        when(mMockMeasurementImpl.register(any(RegistrationRequest.class), anyBoolean(), anyLong()))
                .thenReturn(STATUS_SUCCESS);
    }

    /**
     * Mock related objects for Register Trigger API only. Same mock objects could be shared among
     * other APIs, but implementation could change. Therefore, each mock{API} should describe the
     * mock objects the API uses for readability and separation of concerns.
     *
     * @param accessDenier describes if API is allowed or denied by any barrier
     */
    private void mockRegisterTriggerApi(AccessDenier accessDenier) {
        // Throttler
        updateThrottlerDenied(accessDenier.mByThrottler);

        // Access Resolvers
        // Kill Switch Resolver
        final boolean killSwitchEnabled = accessDenier.mByKillSwitch;
        when(mMockFlags.getMeasurementApiRegisterTriggerKillSwitch()).thenReturn(killSwitchEnabled);

        // Foreground Resolver
        if (accessDenier.mByForegroundEnforcement) {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementRegisterTrigger())
                    .thenReturn(true);
            doThrowExceptionCallerNotInForeground();
        } else {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementRegisterTrigger())
                    .thenReturn(false);
        }

        // App Package Resolver Pp Api
        updateAppPackagePpApiResolverDenied(accessDenier.mByAppPackagePpApiApp);

        // Consent Resolver
        updateConsentDenied(accessDenier.mByConsent);

        // PermissionHelper
        updateAttributionPermissionDenied(accessDenier.mByAttributionPermission);

        // Results
        when(mMockMeasurementImpl.register(any(RegistrationRequest.class), anyBoolean(), anyLong()))
                .thenReturn(STATUS_SUCCESS);
    }

    /**
     * Mock related objects for Register Web Source API only. Same mock objects could be shared
     * among other APIs, but implementation could change. Therefore, each mock{API} should describe
     * the mock objects the API uses for readability and separation of concerns.
     *
     * @param accessDenier describes if API is allowed or denied by any barrier
     */
    private void mockRegisterWebSourceApi(AccessDenier accessDenier) {
        // Throttler
        updateThrottlerDenied(accessDenier.mByThrottler);

        // Access Resolvers
        // Kill Switch Resolver
        final boolean killSwitchEnabled = accessDenier.mByKillSwitch;
        when(mMockFlags.getMeasurementApiRegisterWebSourceKillSwitch())
                .thenReturn(killSwitchEnabled);

        // Foreground Resolver
        if (accessDenier.mByForegroundEnforcement) {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementRegisterWebSource())
                    .thenReturn(true);
            doThrowExceptionCallerNotInForeground();
        } else {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementRegisterWebSource())
                    .thenReturn(false);
        }

        // App Package Resolver Pp Api
        updateAppPackagePpApiResolverDenied(accessDenier.mByAppPackagePpApiApp);

        // App Package Resolver Web Context Client App
        updateAppPackageResolverWebAppDenied(accessDenier.mByAppPackageWebContextClientApp);

        // Consent Resolver
        updateConsentDenied(accessDenier.mByConsent);

        // PermissionHelper
        updateAttributionPermissionDenied(accessDenier.mByAttributionPermission);

        // Results
        when(mMockMeasurementImpl.registerWebSource(
                        any(WebSourceRegistrationRequestInternal.class), anyBoolean(), anyLong()))
                .thenReturn(STATUS_SUCCESS);
    }

    /**
     * Mock related objects for Register Web Trigger API only. Same mock objects could be shared
     * among other APIs, but implementation could change. Therefore, each mock{API} should describe
     * the mock objects the API uses for readability and separation of concerns.
     *
     * @param accessDenier describes if API is allowed or denied by any barrier
     */
    private void mockRegisterWebTriggerApi(AccessDenier accessDenier) {
        // Throttler
        updateThrottlerDenied(accessDenier.mByThrottler);

        // Access Resolvers
        // Kill Switch Resolver
        final boolean killSwitchEnabled = accessDenier.mByKillSwitch;
        when(mMockFlags.getMeasurementApiRegisterWebTriggerKillSwitch())
                .thenReturn(killSwitchEnabled);

        // Foreground Resolver
        if (accessDenier.mByForegroundEnforcement) {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementRegisterWebTrigger())
                    .thenReturn(true);
            doThrowExceptionCallerNotInForeground();
        } else {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementRegisterWebTrigger())
                    .thenReturn(false);
        }

        // App Package Resolver Pp Api
        updateAppPackagePpApiResolverDenied(accessDenier.mByAppPackagePpApiApp);

        // Consent Resolver
        updateConsentDenied(accessDenier.mByConsent);

        // PermissionHelper
        updateAttributionPermissionDenied(accessDenier.mByAttributionPermission);

        // Results
        when(mMockMeasurementImpl.registerWebTrigger(
                        any(WebTriggerRegistrationRequestInternal.class), anyBoolean(), anyLong()))
                .thenReturn(STATUS_SUCCESS);
    }

    /**
     * Mock related objects for Status API only. Same mock objects could be shared among other APIs,
     * but implementation could change. Therefore, each mock{API} should describe the mock objects
     * the API uses for readability and separation of concerns.
     *
     * @param accessDenier describes if API is allowed or denied by any barrier
     */
    private void mockStatusApi(AccessDenier accessDenier) {
        // Access Resolvers
        // Kill Switch Resolver
        final boolean killSwitchEnabled = accessDenier.mByKillSwitch;
        when(mMockFlags.getMeasurementApiStatusKillSwitch()).thenReturn(killSwitchEnabled);

        // Foreground Resolver
        if (accessDenier.mByForegroundEnforcement) {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementStatus()).thenReturn(true);
            doThrowExceptionCallerNotInForeground();
        } else {
            when(mMockFlags.getEnforceForegroundStatusForMeasurementStatus()).thenReturn(false);
        }

        // App Package Resolver Pp Api
        updateAppPackagePpApiResolverDenied(accessDenier.mByAppPackagePpApiApp);

        // Consent Resolver
        updateConsentDenied(accessDenier.mByConsent);
    }

    private void doThrowExceptionCallerNotInForeground() {
        doThrow(new AppImportanceFilter.WrongCallingApplicationStateException())
                .when(mMockAppImportanceFilter)
                .assertCallerIsInForeground(anyInt(), anyInt(), any());
    }

    private void updateAppPackagePpApiResolverDenied(boolean denied) {
        String allowList = denied ? "" : ALLOW_ALL_PACKAGES;
        when(mMockFlags.getPpapiAppAllowList()).thenReturn(allowList);
    }

    private void updateAppPackageResolverWebAppDenied(boolean denied) {
        String allowList = denied ? "" : ALLOW_ALL_PACKAGES;
        when(mMockFlags.getWebContextClientAppAllowList()).thenReturn(allowList);
    }

    private void updateAttributionPermissionDenied(boolean denied) {
        final boolean allowed = !denied;
        ExtendedMockito.doReturn(allowed)
                .when(() -> PermissionHelper.hasAttributionPermission(any(Context.class)));
    }

    private void updateConsentDenied(boolean denied) {
        final AdServicesApiConsent apiConsent =
                denied ? AdServicesApiConsent.REVOKED : AdServicesApiConsent.GIVEN;
        when(mMockConsentManager.getConsent()).thenReturn(apiConsent);
    }

    private void updateThrottlerDenied(boolean denied) {
        final boolean canAcquire = !denied;
        when(mMockThrottler.tryAcquire(any(), any())).thenReturn(canAcquire);
    }

    private static class AccessDenier {
        private boolean mByAppPackagePpApiApp;
        private boolean mByAppPackageWebContextClientApp;
        private boolean mByAttributionPermission;
        private boolean mByConsent;
        private boolean mByForegroundEnforcement;
        private boolean mByKillSwitch;
        private boolean mByThrottler;

        private AccessDenier deniedByAppPackagePpApiApp() {
            mByAppPackagePpApiApp = true;
            return this;
        }

        private AccessDenier deniedByAppPackageWebContextClientApp() {
            mByAppPackageWebContextClientApp = true;
            return this;
        }

        private AccessDenier deniedByAttributionPermission() {
            mByAttributionPermission = true;
            return this;
        }

        private AccessDenier deniedByConsent() {
            mByConsent = true;
            return this;
        }

        private AccessDenier deniedByForegroundEnforcement() {
            mByForegroundEnforcement = true;
            return this;
        }

        private AccessDenier deniedByKillSwitch() {
            mByKillSwitch = true;
            return this;
        }

        private AccessDenier deniedByThrottler() {
            mByThrottler = true;
            return this;
        }
    }
}
