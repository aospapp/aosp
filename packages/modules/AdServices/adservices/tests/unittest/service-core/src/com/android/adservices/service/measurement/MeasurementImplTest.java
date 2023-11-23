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

import static android.adservices.common.AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
import static android.adservices.common.AdServicesStatusUtils.STATUS_INVALID_ARGUMENT;
import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;
import static android.view.MotionEvent.ACTION_BUTTON_PRESS;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.adservices.measurement.DeletionParam;
import android.adservices.measurement.DeletionRequest;
import android.adservices.measurement.WebSourceParams;
import android.adservices.measurement.WebSourceRegistrationRequest;
import android.adservices.measurement.WebSourceRegistrationRequestInternal;
import android.adservices.measurement.WebTriggerParams;
import android.adservices.measurement.WebTriggerRegistrationRequest;
import android.adservices.measurement.WebTriggerRegistrationRequestInternal;
import android.app.adservices.AdServicesManager;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.RemoteException;
import android.view.InputEvent;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.adservices.data.DbTestUtil;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.data.measurement.DatastoreManager;
import com.android.adservices.data.measurement.SQLDatastoreManager;
import com.android.adservices.data.measurement.deletion.MeasurementDataDeleter;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.appsearch.AppSearchMeasurementRollbackManager;
import com.android.adservices.service.enrollment.EnrollmentData;
import com.android.adservices.service.measurement.attribution.TriggerContentProvider;
import com.android.adservices.service.measurement.inputverification.ClickVerifier;
import com.android.adservices.service.measurement.registration.AsyncRegistrationContentProvider;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.modules.utils.build.SdkLevel;
import com.android.modules.utils.testing.TestableDeviceConfig;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.Spy;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

/** Unit tests for {@link MeasurementImpl} */
@SmallTest
public final class MeasurementImplTest {
    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule mDeviceConfigRule =
            new TestableDeviceConfig.TestableDeviceConfigRule();

    private static final Context DEFAULT_CONTEXT = ApplicationProvider.getApplicationContext();
    private static final Uri DEFAULT_URI = Uri.parse("android-app://com.example.abc");
    private static final Uri REGISTRATION_URI_1 = WebUtil.validUri("https://foo.test/bar?ad=134");
    private static final Uri REGISTRATION_URI_2 = WebUtil.validUri("https://foo.test/bar?ad=256");
    private static final String SDK_PACKAGE_NAME = "sdk.package.name";
    private static final String DEFAULT_ENROLLMENT = "enrollment-id";
    private static final Uri INVALID_WEB_DESTINATION = Uri.parse("https://example.not_a_tld");
    private static final Uri WEB_DESTINATION = WebUtil.validUri("https://web-destination.test");
    private static final Uri OTHER_WEB_DESTINATION =
            WebUtil.validUri("https://other-web-destination.test");
    private static final Uri APP_DESTINATION = Uri.parse("android-app://com.app_destination");
    private static final Uri OTHER_APP_DESTINATION =
            Uri.parse("android-app://com.other_app_destination");
    private static final boolean DEFAULT_AD_ID_PERMISSION = false;

    private static final WebSourceParams INPUT_SOURCE_REGISTRATION_1 =
            new WebSourceParams.Builder(REGISTRATION_URI_1).setDebugKeyAllowed(true).build();
    private static final WebSourceParams INPUT_SOURCE_REGISTRATION_2 =
            new WebSourceParams.Builder(REGISTRATION_URI_2).setDebugKeyAllowed(false).build();
    private static final WebTriggerParams INPUT_TRIGGER_REGISTRATION_1 =
            new WebTriggerParams.Builder(REGISTRATION_URI_1).setDebugKeyAllowed(true).build();
    private static final WebTriggerParams INPUT_TRIGGER_REGISTRATION_2 =
            new WebTriggerParams.Builder(REGISTRATION_URI_2).setDebugKeyAllowed(false).build();
    private static final long REQUEST_TIME = 10000L;

    @Spy
    private DatastoreManager mDatastoreManager =
            new SQLDatastoreManager(DbTestUtil.getMeasurementDbHelperForTest());

    @Mock private ContentProviderClient mMockContentProviderClient;
    @Mock private ContentResolver mContentResolver;
    @Mock private ClickVerifier mClickVerifier;
    private MeasurementImpl mMeasurementImpl;
    @Mock EnrollmentDao mEnrollmentDao;
    @Mock MeasurementDataDeleter mMeasurementDataDeleter;

    private static EnrollmentData getEnrollment(String enrollmentId) {
        return new EnrollmentData.Builder().setEnrollmentId(enrollmentId).build();
    }

    public static InputEvent getInputEvent() {
        return MotionEvent.obtain(0, 0, ACTION_BUTTON_PRESS, 0, 0, 0);
    }

    private static WebTriggerRegistrationRequestInternal createWebTriggerRegistrationRequest(
            Uri destination) {
        WebTriggerRegistrationRequest webTriggerRegistrationRequest =
                new WebTriggerRegistrationRequest.Builder(
                                Arrays.asList(
                                        INPUT_TRIGGER_REGISTRATION_1, INPUT_TRIGGER_REGISTRATION_2),
                                destination)
                        .build();
        return new WebTriggerRegistrationRequestInternal.Builder(
                        webTriggerRegistrationRequest,
                        DEFAULT_CONTEXT.getAttributionSource().getPackageName(),
                        SDK_PACKAGE_NAME)
                .build();
    }

    private static WebSourceRegistrationRequestInternal createWebSourceRegistrationRequest(
            Uri appDestination, Uri webDestination, Uri verifiedDestination) {
        return createWebSourceRegistrationRequest(
                appDestination, webDestination, verifiedDestination, DEFAULT_URI);
    }

    private static WebSourceRegistrationRequestInternal createWebSourceRegistrationRequest(
            Uri appDestination, Uri webDestination, Uri verifiedDestination, Uri topOriginUri) {
        WebSourceRegistrationRequest sourceRegistrationRequest =
                new WebSourceRegistrationRequest.Builder(
                                Arrays.asList(
                                        INPUT_SOURCE_REGISTRATION_1, INPUT_SOURCE_REGISTRATION_2),
                                topOriginUri)
                        .setAppDestination(appDestination)
                        .setWebDestination(webDestination)
                        .setVerifiedDestination(verifiedDestination)
                        .build();
        return new WebSourceRegistrationRequestInternal.Builder(
                        sourceRegistrationRequest,
                        DEFAULT_CONTEXT.getAttributionSource().getPackageName(),
                        SDK_PACKAGE_NAME,
                        REQUEST_TIME)
                .build();
    }

    @Before
    public void before() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mContentResolver.acquireContentProviderClient(TriggerContentProvider.TRIGGER_URI))
                .thenReturn(mMockContentProviderClient);
        when(mContentResolver.acquireContentProviderClient(
                        AsyncRegistrationContentProvider.TRIGGER_URI))
                .thenReturn(mMockContentProviderClient);
        when(mMockContentProviderClient.insert(eq(TriggerContentProvider.TRIGGER_URI), any()))
                .thenReturn(TriggerContentProvider.TRIGGER_URI);
        when(mMockContentProviderClient.insert(
                        eq(AsyncRegistrationContentProvider.TRIGGER_URI), any()))
                .thenReturn(AsyncRegistrationContentProvider.TRIGGER_URI);
        mMeasurementImpl =
                spy(
                        new MeasurementImpl(
                                DEFAULT_CONTEXT,
                                mDatastoreManager,
                                mClickVerifier,
                                mMeasurementDataDeleter,
                                mContentResolver));
        doReturn(true).when(mClickVerifier).isInputEventVerifiable(any(), anyLong());
        when(mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(any()))
                .thenReturn(getEnrollment(DEFAULT_ENROLLMENT));
    }

    @Test
    public void testDeleteRegistrations_successfulNoOptionalParameters() {
        MeasurementImpl measurement =
                new MeasurementImpl(
                        DEFAULT_CONTEXT,
                        new SQLDatastoreManager(DbTestUtil.getMeasurementDbHelperForTest()),
                        mClickVerifier,
                        mMeasurementDataDeleter,
                        mContentResolver);
        doReturn(true).when(mMeasurementDataDeleter).delete(any());
        final int result =
                measurement.deleteRegistrations(
                        new DeletionParam.Builder(
                                        Collections.emptyList(),
                                        Collections.emptyList(),
                                        Instant.ofEpochMilli(Long.MIN_VALUE),
                                        Instant.ofEpochMilli(Long.MAX_VALUE),
                                        DEFAULT_CONTEXT.getAttributionSource().getPackageName(),
                                        SDK_PACKAGE_NAME)
                                .build());
        assertEquals(STATUS_SUCCESS, result);
    }

    @Test
    public void testRegisterWebSource_verifiedDestination_webDestinationMismatch() {
        final int result =
                mMeasurementImpl.registerWebSource(
                        createWebSourceRegistrationRequest(
                                APP_DESTINATION, WEB_DESTINATION, OTHER_WEB_DESTINATION),
                        DEFAULT_AD_ID_PERMISSION,
                        System.currentTimeMillis());
        assertEquals(STATUS_INVALID_ARGUMENT, result);
    }

    @Test
    public void testDeleteRegistrations_successfulWithRange() {
        doReturn(true).when(mMeasurementDataDeleter).delete(any());
        final int result =
                mMeasurementImpl.deleteRegistrations(
                        new DeletionParam.Builder(
                                        Collections.emptyList(),
                                        Collections.emptyList(),
                                        Instant.now().minusSeconds(1),
                                        Instant.now(),
                                        DEFAULT_CONTEXT.getAttributionSource().getPackageName(),
                                        SDK_PACKAGE_NAME)
                                .setMatchBehavior(DeletionRequest.MATCH_BEHAVIOR_DELETE)
                                .setDeletionMode(DeletionRequest.DELETION_MODE_ALL)
                                .build());
        assertEquals(STATUS_SUCCESS, result);
    }

    @Test
    public void testDeleteRegistrations_successfulWithOrigin() {
        DeletionParam deletionParam =
                new DeletionParam.Builder(
                                Collections.singletonList(DEFAULT_URI),
                                Collections.emptyList(),
                                Instant.ofEpochMilli(Long.MIN_VALUE),
                                Instant.ofEpochMilli(Long.MAX_VALUE),
                                DEFAULT_CONTEXT.getAttributionSource().getPackageName(),
                                SDK_PACKAGE_NAME)
                        .setMatchBehavior(DeletionRequest.MATCH_BEHAVIOR_DELETE)
                        .setDeletionMode(DeletionRequest.DELETION_MODE_ALL)
                        .build();
        when(mMeasurementDataDeleter.delete(deletionParam)).thenReturn(true);
        final int result = mMeasurementImpl.deleteRegistrations(deletionParam);
        assertEquals(STATUS_SUCCESS, result);
    }

    @Test
    public void testDeleteRegistrations_internalError() {
        doReturn(false).when(mDatastoreManager).runInTransaction(any());
        doReturn(false).when(mMeasurementDataDeleter).delete(any());
        final int result =
                mMeasurementImpl.deleteRegistrations(
                        new DeletionParam.Builder(
                                        Collections.emptyList(),
                                        Collections.emptyList(),
                                        Instant.MIN,
                                        Instant.MAX,
                                        DEFAULT_CONTEXT.getAttributionSource().getPackageName(),
                                        SDK_PACKAGE_NAME)
                                .setMatchBehavior(DeletionRequest.MATCH_BEHAVIOR_DELETE)
                                .setDeletionMode(DeletionRequest.DELETION_MODE_ALL)
                                .build());
        assertEquals(STATUS_INTERNAL_ERROR, result);
    }

    @Test
    public void testRegisterWebSource_invalidWebDestination() {
        final int result =
                mMeasurementImpl.registerWebSource(
                        createWebSourceRegistrationRequest(null, INVALID_WEB_DESTINATION, null),
                        DEFAULT_AD_ID_PERMISSION,
                        System.currentTimeMillis());
        assertEquals(STATUS_INVALID_ARGUMENT, result);
    }

    @Test
    public void testRegisterWebTrigger_invalidDestination() throws RemoteException {
        final int result =
                mMeasurementImpl.registerWebTrigger(
                        createWebTriggerRegistrationRequest(INVALID_WEB_DESTINATION),
                        DEFAULT_AD_ID_PERMISSION,
                        System.currentTimeMillis());
        assertEquals(STATUS_INVALID_ARGUMENT, result);
    }

    @Test
    public void testRegisterWebSource_verifiedDestination_appDestinationMismatch() {
        final int result =
                mMeasurementImpl.registerWebSource(
                        createWebSourceRegistrationRequest(
                                APP_DESTINATION, WEB_DESTINATION, OTHER_APP_DESTINATION),
                        DEFAULT_AD_ID_PERMISSION,
                        System.currentTimeMillis());
        assertEquals(STATUS_INVALID_ARGUMENT, result);
    }

    @Test
    public void testGetSourceType_verifiedInputEvent_returnsNavigationSourceType() {
        doReturn(true).when(mClickVerifier).isInputEventVerifiable(any(), anyLong());
        assertEquals(
                Source.SourceType.NAVIGATION,
                mMeasurementImpl.getSourceType(getInputEvent(), 1000L));
    }

    @Test
    public void testGetSourceType_noInputEventGiven() {
        assertEquals(Source.SourceType.EVENT, mMeasurementImpl.getSourceType(null, 1000L));
    }

    @Test
    public void testGetSourceType_inputEventNotVerifiable_returnsEventSourceType() {
        doReturn(false).when(mClickVerifier).isInputEventVerifiable(any(), anyLong());
        assertEquals(
                Source.SourceType.EVENT, mMeasurementImpl.getSourceType(getInputEvent(), 1000L));
    }

    @Test
    public void testGetSourceType_clickVerificationDisabled_returnsNavigationSourceType() {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .initMocks(this)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            Flags mockFlags = Mockito.mock(Flags.class);
            ClickVerifier mockClickVerifier = Mockito.mock(ClickVerifier.class);
            doReturn(false).when(mockClickVerifier).isInputEventVerifiable(any(), anyLong());
            doReturn(false).when(mockFlags).getMeasurementIsClickVerificationEnabled();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlagsForTest());
            MeasurementImpl measurementImpl =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mockClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);

            // Because click verification is disabled, the SourceType is NAVIGATION even if the
            // input event is not verifiable.
            assertEquals(
                    Source.SourceType.NAVIGATION,
                    measurementImpl.getSourceType(getInputEvent(), 1000L));
        } catch (Exception e) {
            Assert.fail();
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeleteRegistrations_success_recordsDeletionInSystemServer() {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AdServicesManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AdServicesManager mockAdServicesManager = Mockito.mock(AdServicesManager.class);
            ExtendedMockito.doReturn(mockAdServicesManager)
                    .when(() -> AdServicesManager.getInstance(any()));

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            doReturn(true).when(mMeasurementDataDeleter).delete(any());
            measurement.deleteRegistrations(
                    new DeletionParam.Builder(
                                    Collections.emptyList(),
                                    Collections.emptyList(),
                                    Instant.ofEpochMilli(Long.MIN_VALUE),
                                    Instant.ofEpochMilli(Long.MAX_VALUE),
                                    DEFAULT_CONTEXT.getAttributionSource().getPackageName(),
                                    SDK_PACKAGE_NAME)
                            .build());

            Mockito.verify(mockAdServicesManager)
                    .recordAdServicesDeletionOccurred(AdServicesManager.MEASUREMENT_DELETION);
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeleteRegistrations_success_recordsDeletionInAppSearch() {
        Assume.assumeTrue(!SdkLevel.isAtLeastT());
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AppSearchMeasurementRollbackManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AppSearchMeasurementRollbackManager mockRollbackManager =
                    Mockito.mock(AppSearchMeasurementRollbackManager.class);
            ExtendedMockito.doReturn(mockRollbackManager)
                    .when(
                            () ->
                                    AppSearchMeasurementRollbackManager.getInstance(
                                            any(), eq(AdServicesManager.MEASUREMENT_DELETION)));

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            doReturn(true).when(mMeasurementDataDeleter).delete(any());
            measurement.deleteRegistrations(
                    new DeletionParam.Builder(
                                    Collections.emptyList(),
                                    Collections.emptyList(),
                                    Instant.ofEpochMilli(Long.MIN_VALUE),
                                    Instant.ofEpochMilli(Long.MAX_VALUE),
                                    DEFAULT_CONTEXT.getAttributionSource().getPackageName(),
                                    SDK_PACKAGE_NAME)
                            .build());

            Mockito.verify(mockRollbackManager).recordAdServicesDeletionOccurred();

        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeleteRegistrations_success_recordsDeletionInSystemServer_flagOff() {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AdServicesManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(true).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AdServicesManager mockAdServicesManager = Mockito.mock(AdServicesManager.class);
            ExtendedMockito.doReturn(mockAdServicesManager)
                    .when(() -> AdServicesManager.getInstance(any()));

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            doReturn(true).when(mMeasurementDataDeleter).delete(any());
            measurement.deleteRegistrations(
                    new DeletionParam.Builder(
                                    Collections.emptyList(),
                                    Collections.emptyList(),
                                    Instant.ofEpochMilli(Long.MIN_VALUE),
                                    Instant.ofEpochMilli(Long.MAX_VALUE),
                                    DEFAULT_CONTEXT.getAttributionSource().getPackageName(),
                                    SDK_PACKAGE_NAME)
                            .build());

            Mockito.verify(mockAdServicesManager, Mockito.never())
                    .recordAdServicesDeletionOccurred(AdServicesManager.MEASUREMENT_DELETION);
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeleteRegistrations_success_recordsDeletionInAppSearch_flagOff() {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .mockStatic(AppSearchMeasurementRollbackManager.class)
                        .mockStatic(SdkLevel.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            ExtendedMockito.doReturn(false).when(SdkLevel::isAtLeastT);
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(true).when(mockFlags).getMeasurementRollbackDeletionAppSearchKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(FlagsFactory::getFlags);

            AppSearchMeasurementRollbackManager mockRollbackManager =
                    Mockito.mock(AppSearchMeasurementRollbackManager.class);
            ExtendedMockito.doReturn(mockRollbackManager)
                    .when(
                            () ->
                                    AppSearchMeasurementRollbackManager.getInstance(
                                            any(), eq(AdServicesManager.MEASUREMENT_DELETION)));

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            doReturn(true).when(mMeasurementDataDeleter).delete(any());
            measurement.deleteRegistrations(
                    new DeletionParam.Builder(
                                    Collections.emptyList(),
                                    Collections.emptyList(),
                                    Instant.ofEpochMilli(Long.MIN_VALUE),
                                    Instant.ofEpochMilli(Long.MAX_VALUE),
                                    DEFAULT_CONTEXT.getAttributionSource().getPackageName(),
                                    SDK_PACKAGE_NAME)
                            .build());

            Mockito.verify(mockRollbackManager, Mockito.never()).recordAdServicesDeletionOccurred();
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeletePackageRecords_success_recordsDeletionInSystemServer() {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AdServicesManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AdServicesManager mockAdServicesManager = Mockito.mock(AdServicesManager.class);
            ExtendedMockito.doReturn(mockAdServicesManager)
                    .when(() -> AdServicesManager.getInstance(any()));

            doReturn(Optional.of(true)).when(mDatastoreManager).runInTransactionWithResult(any());
            doReturn(true).when(mDatastoreManager).runInTransaction(any());

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            measurement.deletePackageRecords(DEFAULT_URI);

            Mockito.verify(mockAdServicesManager)
                    .recordAdServicesDeletionOccurred(AdServicesManager.MEASUREMENT_DELETION);
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeletePackageRecords_success_recordsDeletionInAppSearch() {
        Assume.assumeTrue(!SdkLevel.isAtLeastT());
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AppSearchMeasurementRollbackManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AppSearchMeasurementRollbackManager mockRollbackManager =
                    Mockito.mock(AppSearchMeasurementRollbackManager.class);
            ExtendedMockito.doReturn(mockRollbackManager)
                    .when(
                            () ->
                                    AppSearchMeasurementRollbackManager.getInstance(
                                            any(), eq(AdServicesManager.MEASUREMENT_DELETION)));

            doReturn(Optional.of(true)).when(mDatastoreManager).runInTransactionWithResult(any());
            doReturn(true).when(mDatastoreManager).runInTransaction(any());

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            measurement.deletePackageRecords(DEFAULT_URI);

            Mockito.verify(mockRollbackManager).recordAdServicesDeletionOccurred();
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeletePackageRecords_noDeletion_doesNotRecordDeletion() {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AdServicesManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AdServicesManager mockAdServicesManager = Mockito.mock(AdServicesManager.class);
            ExtendedMockito.doReturn(mockAdServicesManager)
                    .when(() -> AdServicesManager.getInstance(any()));

            doReturn(Optional.of(false)).when(mDatastoreManager).runInTransactionWithResult(any());
            doReturn(true).when(mDatastoreManager).runInTransaction(any());

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            measurement.deletePackageRecords(DEFAULT_URI);

            Mockito.verify(mockAdServicesManager, Mockito.never())
                    .recordAdServicesDeletionOccurred(AdServicesManager.MEASUREMENT_DELETION);
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeleteAllMeasurementData_success_recordsDeletionInSystemServer() {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AdServicesManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AdServicesManager mockAdServicesManager = Mockito.mock(AdServicesManager.class);
            ExtendedMockito.doReturn(mockAdServicesManager)
                    .when(() -> AdServicesManager.getInstance(any()));

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            measurement.deleteAllMeasurementData(Collections.EMPTY_LIST);

            Mockito.verify(mockAdServicesManager)
                    .recordAdServicesDeletionOccurred(AdServicesManager.MEASUREMENT_DELETION);
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeleteAllMeasurementData_success_recordsDeletionInAppSearch() {
        Assume.assumeTrue(!SdkLevel.isAtLeastT());

        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AppSearchMeasurementRollbackManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AppSearchMeasurementRollbackManager mockRollbackManager =
                    Mockito.mock(AppSearchMeasurementRollbackManager.class);
            ExtendedMockito.doReturn(mockRollbackManager)
                    .when(
                            () ->
                                    AppSearchMeasurementRollbackManager.getInstance(
                                            any(), eq(AdServicesManager.MEASUREMENT_DELETION)));

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            measurement.deleteAllMeasurementData(Collections.EMPTY_LIST);

            Mockito.verify(mockRollbackManager).recordAdServicesDeletionOccurred();

        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeleteAllUninstalledMeasurementData_success_recordsDeletionInSystemServer() {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AdServicesManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AdServicesManager mockAdServicesManager = Mockito.mock(AdServicesManager.class);
            ExtendedMockito.doReturn(mockAdServicesManager)
                    .when(() -> AdServicesManager.getInstance(any()));

            doReturn(Optional.of(true)).when(mDatastoreManager).runInTransactionWithResult(any());
            doReturn(true).when(mDatastoreManager).runInTransaction(any());

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            measurement.deleteAllUninstalledMeasurementData();

            Mockito.verify(mockAdServicesManager)
                    .recordAdServicesDeletionOccurred(AdServicesManager.MEASUREMENT_DELETION);
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeleteAllUninstalledMeasurementData_success_recordsDeletionInAppSearch() {
        Assume.assumeTrue(!SdkLevel.isAtLeastT());
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AppSearchMeasurementRollbackManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AppSearchMeasurementRollbackManager mockRollbackManager =
                    Mockito.mock(AppSearchMeasurementRollbackManager.class);
            ExtendedMockito.doReturn(mockRollbackManager)
                    .when(
                            () ->
                                    AppSearchMeasurementRollbackManager.getInstance(
                                            any(), eq(AdServicesManager.MEASUREMENT_DELETION)));

            doReturn(Optional.of(true)).when(mDatastoreManager).runInTransactionWithResult(any());
            doReturn(true).when(mDatastoreManager).runInTransaction(any());

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            measurement.deleteAllUninstalledMeasurementData();

            Mockito.verify(mockRollbackManager).recordAdServicesDeletionOccurred();
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testDeleteAllUninstalledMeasurementData_noDeletion_doesNotRecordDeletion() {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AdServicesManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            Flags mockFlags = Mockito.mock(Flags.class);

            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(() -> FlagsFactory.getFlags());

            AdServicesManager mockAdServicesManager = Mockito.mock(AdServicesManager.class);
            ExtendedMockito.doReturn(mockAdServicesManager)
                    .when(() -> AdServicesManager.getInstance(any()));

            doReturn(Optional.of(false)).when(mDatastoreManager).runInTransactionWithResult(any());
            doReturn(true).when(mDatastoreManager).runInTransaction(any());

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            measurement.deleteAllUninstalledMeasurementData();

            Mockito.verify(mockAdServicesManager, Mockito.never())
                    .recordAdServicesDeletionOccurred(AdServicesManager.MEASUREMENT_DELETION);
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testCheckIfNeedsToHandleReconciliation() {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(SdkLevel.class)
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AppSearchMeasurementRollbackManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            ExtendedMockito.doReturn(false).when(SdkLevel::isAtLeastT);

            Flags mockFlags = Mockito.mock(Flags.class);
            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(FlagsFactory::getFlags);

            AppSearchMeasurementRollbackManager mockManager =
                    Mockito.mock(AppSearchMeasurementRollbackManager.class);
            ExtendedMockito.doReturn(mockManager)
                    .when(() -> AppSearchMeasurementRollbackManager.getInstance(any(), anyInt()));

            doReturn(false).when(mockManager).needsToHandleRollbackReconciliation();
            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            assertThat(measurement.checkIfNeedsToHandleReconciliation()).isFalse();
            Mockito.verify(mockManager).needsToHandleRollbackReconciliation();
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testCheckIfNeedsToHandleReconciliation_clearsData() {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(SdkLevel.class)
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AppSearchMeasurementRollbackManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            ExtendedMockito.doReturn(false).when(SdkLevel::isAtLeastT);

            Flags mockFlags = Mockito.mock(Flags.class);
            doReturn(false).when(mockFlags).getMeasurementRollbackDeletionKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(FlagsFactory::getFlags);

            AppSearchMeasurementRollbackManager mockManager =
                    Mockito.mock(AppSearchMeasurementRollbackManager.class);
            ExtendedMockito.doReturn(mockManager)
                    .when(() -> AppSearchMeasurementRollbackManager.getInstance(any(), anyInt()));

            doReturn(true).when(mockManager).needsToHandleRollbackReconciliation();
            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            assertThat(measurement.checkIfNeedsToHandleReconciliation()).isTrue();
            Mockito.verify(mockManager).needsToHandleRollbackReconciliation();
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testCheckIfNeedsToHandleReconciliation_flagOff() {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(SdkLevel.class)
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AppSearchMeasurementRollbackManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            ExtendedMockito.doReturn(false).when(SdkLevel::isAtLeastT);

            Flags mockFlags = Mockito.mock(Flags.class);
            doReturn(true).when(mockFlags).getMeasurementRollbackDeletionAppSearchKillSwitch();
            ExtendedMockito.doReturn(mockFlags).when(FlagsFactory::getFlags);

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);
            assertThat(measurement.checkIfNeedsToHandleReconciliation()).isFalse();
            ExtendedMockito.verify(
                    () -> AppSearchMeasurementRollbackManager.getInstance(any(), anyInt()),
                    never());
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testCheckIfNeedsToHandleReconciliation_TPlus() {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .mockStatic(SdkLevel.class)
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AdServicesManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        try {
            ExtendedMockito.doReturn(true).when(SdkLevel::isAtLeastT);

            AdServicesManager mockManager = Mockito.mock(AdServicesManager.class);
            ExtendedMockito.doReturn(mockManager).when(() -> AdServicesManager.getInstance(any()));

            doReturn(true).when(mockManager).needsToHandleRollbackReconciliation(anyInt());

            MeasurementImpl measurement =
                    new MeasurementImpl(
                            DEFAULT_CONTEXT,
                            mDatastoreManager,
                            mClickVerifier,
                            mMeasurementDataDeleter,
                            mContentResolver);

            assertThat(measurement.checkIfNeedsToHandleReconciliation()).isTrue();
            Mockito.verify(mockManager)
                    .needsToHandleRollbackReconciliation(
                            eq(AdServicesManager.MEASUREMENT_DELETION));

            // Verify that the code doesn't accidentally fall through into the Android S part.
            ExtendedMockito.verify(FlagsFactory::getFlags, never());
        } finally {
            session.finishMocking();
        }
    }
}
