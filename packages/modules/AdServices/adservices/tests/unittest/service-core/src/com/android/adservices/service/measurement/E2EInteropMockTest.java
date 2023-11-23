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

import android.adservices.measurement.RegistrationRequest;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.DeviceConfig;

import com.android.adservices.service.measurement.actions.Action;
import com.android.adservices.service.measurement.actions.RegisterSource;
import com.android.adservices.service.measurement.actions.RegisterTrigger;
import com.android.adservices.service.measurement.actions.ReportObjects;
import com.android.adservices.service.measurement.registration.AsyncFetchStatus;
import com.android.adservices.service.measurement.registration.AsyncRegistration;
import com.android.adservices.service.measurement.util.Enrollment;

import org.json.JSONException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end test from source and trigger registration to attribution reporting, using mocked HTTP
 * requests.
 *
 * <p>Tests in assets/msmt_interop_tests/ directory were copied from Chromium
 * src/content/test/data/attribution_reporting/interop April 21, 2023
 */
@RunWith(Parameterized.class)
public class E2EInteropMockTest extends E2EMockTest {
    private static final String TEST_DIR_NAME = "msmt_interop_tests";
    private static final String ANDROID_APP_SCHEME = "android-app";

    private static String preprocessor(String json) {
        return json.replaceAll("\\.test(?=[\"\\/])", ".com")
                // Remove comments
                .replaceAll("^\\s*\\/\\/.+\\n", "")
                .replaceAll("\"destination\":", "\"web_destination\":");
    }

    @Parameterized.Parameters(name = "{3}")
    public static Collection<Object[]> getData() throws IOException, JSONException {
        return data(TEST_DIR_NAME, E2EInteropMockTest::preprocessor);
    }

    public E2EInteropMockTest(
            Collection<Action> actions,
            ReportObjects expectedOutput,
            ParamsProvider paramsProvider,
            String name,
            Map<String, String> phFlagsMap)
            throws RemoteException {
        super(actions, expectedOutput, paramsProvider, name, phFlagsMap);
        mAttributionHelper = TestObjectProvider.getAttributionJobHandler(sDatastoreManager, mFlags);
        mMeasurementImpl =
                TestObjectProvider.getMeasurementImpl(
                        sDatastoreManager,
                        mClickVerifier,
                        mMeasurementDataDeleter,
                        mMockContentResolver);
        mAsyncRegistrationQueueRunner =
                TestObjectProvider.getAsyncRegistrationQueueRunner(
                        TestObjectProvider.Type.DENOISED,
                        sDatastoreManager,
                        mAsyncSourceFetcher,
                        mAsyncTriggerFetcher,
                        mDebugReportApi);
    }

    @Before
    public void setup() {
        // Chromium does not have a flag at dynamic noising based on expiry but Android does, so it
        // needs to be enabled.
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                "measurement_enable_configurable_event_reporting_windows",
                "true",
                false);
    }

    @Override
    void processAction(RegisterSource sourceRegistration) throws JSONException, IOException {
        RegistrationRequest request = sourceRegistration.mRegistrationRequest;
        // For interop tests, we currently expect only one HTTPS response per registration with no
        // redirects, partly due to differences in redirect handling across attribution APIs.
        for (String uri : sourceRegistration.mUriToResponseHeadersMap.keySet()) {
            updateEnrollment(uri);
            insertSource(
                    sourceRegistration.getPublisher(),
                    sourceRegistration.mTimestamp,
                    uri,
                    sourceRegistration.mArDebugPermission,
                    request,
                    getNextResponse(sourceRegistration.mUriToResponseHeadersMap, uri));
        }
        mAsyncRegistrationQueueRunner.runAsyncRegistrationQueueWorker();
        if (sourceRegistration.mDebugReporting) {
            processActualDebugReportApiJob();
        }
    }

    @Override
    void processAction(RegisterTrigger triggerRegistration) throws IOException, JSONException {
        RegistrationRequest request = triggerRegistration.mRegistrationRequest;
        // For interop tests, we currently expect only one HTTPS response per registration with no
        // redirects, partly due to differences in redirect handling across attribution APIs.
        for (String uri : triggerRegistration.mUriToResponseHeadersMap.keySet()) {
            updateEnrollment(uri);
            insertTrigger(
                    triggerRegistration.getDestination(),
                    triggerRegistration.mTimestamp,
                    uri,
                    triggerRegistration.mArDebugPermission,
                    request,
                    getNextResponse(triggerRegistration.mUriToResponseHeadersMap, uri));
        }
        mAsyncRegistrationQueueRunner.runAsyncRegistrationQueueWorker();
        Assert.assertTrue(
                "AttributionJobHandler.performPendingAttributions returned false",
                mAttributionHelper.performPendingAttributions());
        // Attribution can happen up to an hour after registration call, due to AsyncRegistration
        processActualDebugReportJob(triggerRegistration.mTimestamp, TimeUnit.MINUTES.toMillis(30));
        if (triggerRegistration.mDebugReporting) {
            processActualDebugReportApiJob();
        }
    }

    private void insertSource(
            String publisher,
            long timestamp,
            String uri,
            boolean arDebugPermission,
            RegistrationRequest request,
            Map<String, List<String>> headers) {
        String enrollmentId =
                Enrollment.getValidEnrollmentId(
                                Uri.parse(uri),
                                request.getAppPackageName(),
                                mEnrollmentDao,
                                sContext,
                                mFlags)
                        .get();
        AsyncRegistration asyncRegistration =
                new AsyncRegistration.Builder()
                        .setRegistrationId(UUID.randomUUID().toString())
                        .setTopOrigin(Uri.parse(publisher))
                        .setOsDestination(null)
                        .setWebDestination(null)
                        .setRegistrant(getRegistrant(request.getAppPackageName()))
                        .setRequestTime(timestamp)
                        .setSourceType(getSourceType(request))
                        .setType(AsyncRegistration.RegistrationType.WEB_SOURCE)
                        .setAdIdPermission(true)
                        .setDebugKeyAllowed(arDebugPermission)
                        .setRegistrationUri(Uri.parse(uri))
                        .build();
        Source source = mAsyncSourceFetcher
                .parseSource(asyncRegistration, enrollmentId, headers, new AsyncFetchStatus())
                .orElseThrow();
        Assert.assertTrue(
                "mAsyncRegistrationQueueRunner.storeSource failed",
                sDatastoreManager.runInTransaction(
                        measurementDao ->
                                mAsyncRegistrationQueueRunner.storeSource(
                                        source,
                                        asyncRegistration,
                                        measurementDao)));
    }

    private void insertTrigger(
            String destination,
            long timestamp,
            String uri,
            boolean arDebugPermission,
            RegistrationRequest request,
            Map<String, List<String>> headers) {
        String enrollmentId =
                Enrollment.getValidEnrollmentId(
                                Uri.parse(uri),
                                request.getAppPackageName(),
                                mEnrollmentDao,
                                sContext,
                                mFlags)
                        .get();
        AsyncRegistration asyncRegistration =
                new AsyncRegistration.Builder()
                        .setRegistrationId(UUID.randomUUID().toString())
                        .setTopOrigin(Uri.parse(destination))
                        .setRegistrant(getRegistrant(request.getAppPackageName()))
                        .setRequestTime(timestamp)
                        .setType(AsyncRegistration.RegistrationType.WEB_TRIGGER)
                        .setAdIdPermission(true)
                        .setDebugKeyAllowed(arDebugPermission)
                        .setRegistrationUri(Uri.parse(uri))
                        .build();
        Trigger trigger = mAsyncTriggerFetcher
                .parseTrigger(asyncRegistration, enrollmentId, headers, new AsyncFetchStatus())
                .orElseThrow();
        Assert.assertTrue(
                "mAsyncRegistrationQueueRunner.storeTrigger failed",
                sDatastoreManager.runInTransaction(
                        measurementDao ->
                                mAsyncRegistrationQueueRunner.storeTrigger(
                                        trigger,
                                        measurementDao)));
    }

    private static Source.SourceType getSourceType(RegistrationRequest request) {
        return request.getInputEvent() == null
                ? Source.SourceType.EVENT
                : Source.SourceType.NAVIGATION;
    }

    private static Uri getRegistrant(String packageName) {
        return Uri.parse(ANDROID_APP_SCHEME + "://" + packageName);
    }
}
