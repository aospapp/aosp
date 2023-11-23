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

package com.android.adservices.tests.cts.measurement;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.adservices.clients.measurement.MeasurementClient;
import android.adservices.measurement.DeletionRequest;
import android.adservices.measurement.MeasurementManager;
import android.adservices.measurement.WebSourceParams;
import android.adservices.measurement.WebSourceRegistrationRequest;
import android.adservices.measurement.WebTriggerParams;
import android.adservices.measurement.WebTriggerRegistrationRequest;
import android.content.Context;
import android.net.Uri;
import android.os.LimitExceededException;
import android.os.OutcomeReceiver;
import android.os.SystemProperties;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.adservices.common.AdservicesTestHelper;
import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.compatibility.common.util.ShellUtils;
import com.android.modules.utils.build.SdkLevel;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MeasurementManagerCtsTest {
    private MeasurementClient mMeasurementClient;
    private static final Executor CALLBACK_EXECUTOR = Executors.newCachedThreadPool();

    /* Note: The source and trigger registration used here must match one of those in
       {@link PreEnrolledAdTechForTest}.
    */
    private static final Uri SOURCE_REGISTRATION_URI = Uri.parse("https://test.com/source");
    private static final Uri TRIGGER_REGISTRATION_URI = Uri.parse("https://test.com/trigger");
    private static final Uri DESTINATION = Uri.parse("http://trigger-origin.com");
    private static final Uri OS_DESTINATION = Uri.parse("android-app://com.os.destination");
    private static final Uri WEB_DESTINATION = Uri.parse("http://web-destination.com");
    private static final Uri ORIGIN_URI = Uri.parse("https://sample.example1.com");
    private static final Uri DOMAIN_URI = Uri.parse("https://example2.com");
    private static final int DEFAULT_REQUEST_PER_SECOND = 5;
    private static final String FLAG_REGISTER_SOURCE =
            "measurement_register_source_request_permits_per_second";
    private static final String FLAG_REGISTER_WEB_SOURCE =
            "measurement_register_web_source_request_permits_per_second";
    private final ExecutorService mExecutorService = Executors.newCachedThreadPool();

    protected static final Context sContext = ApplicationProvider.getApplicationContext();

    private String mPreviousAppAllowList;

    @Before
    public void setup() throws Exception {
        if (!SdkLevel.isAtLeastT()) {
            mPreviousAppAllowList =
                    CompatAdServicesTestUtils.getAndOverridePpapiAppAllowList(
                            sContext.getPackageName());
            CompatAdServicesTestUtils.setFlags();
        }
        // Skip the test if it runs on unsupported platforms.
        Assume.assumeTrue(AdservicesTestHelper.isDeviceSupported());

        // To grant access to all pp api app
        allowAllPackageNamesAccessToMeasurementApis();

        // We need to turn the Consent Manager into debug mode
        overrideConsentManagerDebugMode();

        overrideMeasurementKillSwitches(true);

        mMeasurementClient =
                new MeasurementClient.Builder()
                        .setContext(sContext)
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();
        // Cool-off rate limiter in case it was initialized by another test
        TimeUnit.SECONDS.sleep(1);
    }

    @After
    public void tearDown() {
        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.setPpapiAppAllowList(mPreviousAppAllowList);
            CompatAdServicesTestUtils.resetFlagsToDefault();
        }
        resetAllowSandboxPackageNameAccessMeasurementApis();
        resetOverrideConsentManagerDebugMode();
        resetOverrideDisableMeasurementEnrollmentCheck();
        overrideMeasurementKillSwitches(false);
    }

    @Test
    public void testRegisterSource_withCallbackButNoServerSetup_NoErrors() throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        ListenableFuture<Void> result =
                mMeasurementClient.registerSource(SOURCE_REGISTRATION_URI, /* inputEvent = */ null);
        assertThat(result.get()).isNull();
        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void testRegisterSource_verifyRateLimitReached() throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        final MeasurementManager manager = MeasurementManager.get(sContext);

        // Rate limit hasn't reached yet
        final long nowInMillis = System.currentTimeMillis();
        final int requestPerSecond = getRequestPerSecond(FLAG_REGISTER_SOURCE);
        for (int i = 0; i < requestPerSecond; i++) {
            assertFalse(registerSourceAndVerifyRateLimitReached(manager));
        }

        // Due to bursting, we could reach the limit at the exact limit or limit + 1. Therefore,
        // triggering one more call without checking the outcome.
        registerSourceAndVerifyRateLimitReached(manager);

        // Verify limit reached
        // If the test takes less than 1 second / permits per second, this test is reliable due to
        // the rate limiter limits queries per second. If duration is longer than a second, skip it.
        final boolean reachedLimit = registerSourceAndVerifyRateLimitReached(manager);
        final boolean executedInLessThanOneSec =
                (System.currentTimeMillis() - nowInMillis) < (1_000 / requestPerSecond);
        if (executedInLessThanOneSec) {
            assertTrue(reachedLimit);
        }

        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void testRegisterTrigger_withCallbackButNoServerSetup_NoErrors() throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        ListenableFuture<Void> result =
                mMeasurementClient.registerTrigger(TRIGGER_REGISTRATION_URI);
        assertThat(result.get()).isNull();
        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void registerWebSource_withCallback_NoErrors() throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        WebSourceParams webSourceParams =
                new WebSourceParams.Builder(SOURCE_REGISTRATION_URI)
                        .setDebugKeyAllowed(false)
                        .build();

        WebSourceRegistrationRequest webSourceRegistrationRequest =
                new WebSourceRegistrationRequest.Builder(
                                Collections.singletonList(webSourceParams), SOURCE_REGISTRATION_URI)
                        .setInputEvent(null)
                        .setAppDestination(OS_DESTINATION)
                        .setWebDestination(WEB_DESTINATION)
                        .setVerifiedDestination(null)
                        .build();

        ListenableFuture<Void> result =
                mMeasurementClient.registerWebSource(webSourceRegistrationRequest);
        assertThat(result.get()).isNull();
        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void testRegisterWebSource_verifyRateLimitReached() throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        final MeasurementManager manager = MeasurementManager.get(sContext);

        // Rate limit hasn't reached yet
        final long nowInMillis = System.currentTimeMillis();
        final int requestPerSecond = getRequestPerSecond(FLAG_REGISTER_WEB_SOURCE);
        for (int i = 0; i < requestPerSecond; i++) {
            assertFalse(registerWebSourceAndVerifyRateLimitReached(manager));
        }

        // Due to bursting, we could reach the limit at the exact limit or limit + 1. Therefore,
        // triggering one more call without checking the outcome.
        registerWebSourceAndVerifyRateLimitReached(manager);

        // Verify limit reached
        // If the test takes less than 1 second / permits per second, this test is reliable due to
        // the rate limiter limits queries per second. If duration is longer than a second, skip it.
        final boolean reachedLimit = registerWebSourceAndVerifyRateLimitReached(manager);
        final boolean executedInLessThanOneSec =
                (System.currentTimeMillis() - nowInMillis) < (1_000 / requestPerSecond);
        if (executedInLessThanOneSec) {
            assertTrue(reachedLimit);
        }

        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void registerWebTrigger_withCallback_NoErrors() throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        WebTriggerParams webTriggerParams =
                new WebTriggerParams.Builder(TRIGGER_REGISTRATION_URI).build();
        WebTriggerRegistrationRequest webTriggerRegistrationRequest =
                new WebTriggerRegistrationRequest.Builder(
                                Collections.singletonList(webTriggerParams), DESTINATION)
                        .build();

        ListenableFuture<Void> result =
                mMeasurementClient.registerWebTrigger(webTriggerRegistrationRequest);
        assertThat(result.get()).isNull();
        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void testDeleteRegistrations_withRequest_withNoOrigin_withNoRange_withCallback_NoErrors()
            throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        DeletionRequest deletionRequest = new DeletionRequest.Builder().build();
        ListenableFuture<Void> result = mMeasurementClient.deleteRegistrations(deletionRequest);
        assertNull(result.get());
        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void
            testDeleteRegistrations_multiple_withRequest_noOrigin_noRange_withCallback_NoErrors()
                    throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        DeletionRequest deletionRequest = new DeletionRequest.Builder().build();
        ListenableFuture<Void> result = mMeasurementClient.deleteRegistrations(deletionRequest);
        assertWithMessage("first deleteRegistrations result").that(result.get()).isNull();
        // Call it once more to ensure that there is no error when recording deletions back-to-back
        TimeUnit.SECONDS.sleep(1); // Sleep to ensure rate-limiter doesn't get tripped.
        result = mMeasurementClient.deleteRegistrations(deletionRequest);
        assertWithMessage("second deleteRegistrations result").that(result.get()).isNull();
        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void testDeleteRegistrations_withRequest_withNoRange_withCallback_NoErrors()
            throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        DeletionRequest deletionRequest =
                new DeletionRequest.Builder()
                        .setOriginUris(Collections.singletonList(ORIGIN_URI))
                        .setDomainUris(Collections.singletonList(DOMAIN_URI))
                        .build();
        ListenableFuture<Void> result = mMeasurementClient.deleteRegistrations(deletionRequest);
        assertNull(result.get());
        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void testDeleteRegistrations_withRequest_withEmptyLists_withRange_withCallback_NoErrors()
            throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        DeletionRequest deletionRequest =
                new DeletionRequest.Builder()
                        .setOriginUris(Collections.emptyList())
                        .setDomainUris(Collections.emptyList())
                        .setStart(Instant.ofEpochMilli(0))
                        .setEnd(Instant.now())
                        .build();
        ListenableFuture<Void> result = mMeasurementClient.deleteRegistrations(deletionRequest);
        assertNull(result.get());
        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void testDeleteRegistrations_withRequest_withUris_withRange_withCallback_NoErrors()
            throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        DeletionRequest deletionRequest =
                new DeletionRequest.Builder()
                        .setOriginUris(Collections.singletonList(ORIGIN_URI))
                        .setDomainUris(Collections.singletonList(DOMAIN_URI))
                        .setStart(Instant.ofEpochMilli(0))
                        .setEnd(Instant.now())
                        .build();
        ListenableFuture<Void> result = mMeasurementClient.deleteRegistrations(deletionRequest);
        assertNull(result.get());
        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void testDeleteRegistrations_withRequest_withInvalidArguments_withCallback_hasError()
            throws Exception {
        overrideDisableMeasurementEnrollmentCheck("1");
        final MeasurementManager manager = MeasurementManager.get(sContext);
        Objects.requireNonNull(manager);

        CompletableFuture<Void> future = new CompletableFuture<>();
        OutcomeReceiver<Object, Exception> callback =
                new OutcomeReceiver<Object, Exception>() {
                    @Override
                    public void onResult(@NonNull Object ignoredResult) {
                        fail();
                    }

                    @Override
                    public void onError(Exception error) {
                        future.complete(null);
                        assertTrue(error instanceof IllegalArgumentException);
                    }
                };
        DeletionRequest request =
                new DeletionRequest.Builder()
                        .setOriginUris(Collections.singletonList(ORIGIN_URI))
                        .setDomainUris(Collections.singletonList(DOMAIN_URI))
                        .setStart(Instant.now().plusMillis(1000))
                        .setEnd(Instant.now())
                        .build();

        manager.deleteRegistrations(request, mExecutorService, callback);

        Assert.assertNull(future.get());
        overrideDisableMeasurementEnrollmentCheck("0");
    }

    @Test
    public void testMeasurementApiStatus_returnEnabled() throws Exception {
        enableGlobalKillSwitch(/* enabled= */ false);
        enableMeasurementKillSwitch(/* enabled= */ false);
        allowAllPackageNamesAccessToMeasurementApis();
        boolean result = callMeasurementApiStatus();
        Assert.assertTrue(result);
    }

    @Test
    public void testMeasurementApiStatus_killSwitchGlobalOn_returnDisabled() throws Exception {
        enableGlobalKillSwitch(/* enabled= */ true);
        boolean result = callMeasurementApiStatus();
        Assert.assertFalse(result);
    }

    @Test
    public void testMeasurementApiStatus_killSwitchMeasurementOn_returnDisabled() throws Exception {
        enableMeasurementKillSwitch(/* enabled= */ true);
        boolean result = callMeasurementApiStatus();
        Assert.assertFalse(result);
    }

    @Test
    public void testMeasurementApiStatus_notInAllowList_returnDisabled() throws Exception {
        enableGlobalKillSwitch(/* enabled= */ true);
        blockAllPackageNamesAccessToMeasurementApis();
        boolean result = callMeasurementApiStatus();
        Assert.assertFalse(result);
    }

    /**
     * Performs calls to measurement status API and returns a boolean representing if the API was
     * enabled {@code true} or disabled {@code false}.
     *
     * @return api status
     */
    private boolean callMeasurementApiStatus() throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        final MeasurementManager manager = MeasurementManager.get(sContext);
        List<Integer> resultCodes = new ArrayList<>();

        manager.getMeasurementApiStatus(
                mExecutorService,
                result -> {
                    resultCodes.add(result);
                    countDownLatch.countDown();
                });

        assertThat(countDownLatch.await(500, TimeUnit.MILLISECONDS)).isTrue();
        Assert.assertNotNull(resultCodes);
        Assert.assertEquals(1, resultCodes.size());
        return resultCodes.get(0) == MeasurementManager.MEASUREMENT_API_STATE_ENABLED;
    }

    private void allowAllPackageNamesAccessToMeasurementApis() {
        final String packageName = "*";
        ShellUtils.runShellCommand(
                "device_config put adservices ppapi_app_allow_list " + packageName);
        ShellUtils.runShellCommand(
                "device_config put adservices web_context_client_allow_list " + packageName);
    }

    private void blockAllPackageNamesAccessToMeasurementApis() {
        final String packageName = "";
        ShellUtils.runShellCommand(
                "device_config put adservices ppapi_app_allow_list " + packageName);
        ShellUtils.runShellCommand(
                "device_config put adservices web_context_client_allow_list " + packageName);
    }

    // Override the Consent Manager behaviour - Consent Given
    private void overrideConsentManagerDebugMode() {
        ShellUtils.runShellCommand("setprop debug.adservices.consent_manager_debug_mode true");
    }

    // Override the flag to disable Measurement enrollment check. Setting to 1 disables enforcement.
    private void overrideDisableMeasurementEnrollmentCheck(String val) {
        ShellUtils.runShellCommand(
                "setprop debug.adservices.disable_measurement_enrollment_check " + val);
    }

    private void resetAllowSandboxPackageNameAccessMeasurementApis() {
        ShellUtils.runShellCommand("device_config put adservices ppapi_app_allow_list null");
        ShellUtils.runShellCommand(
                "device_config put adservices web_context_client_allow_list null");
    }

    private void resetOverrideConsentManagerDebugMode() {
        ShellUtils.runShellCommand("setprop debug.adservices.consent_manager_debug_mode null");
    }

    private void resetOverrideDisableMeasurementEnrollmentCheck() {
        ShellUtils.runShellCommand(
                "setprop debug.adservices.disable_measurement_enrollment_check null");
    }

    // Override measurement related kill switch to ignore the effect of actual PH values.
    // If isOverride = true, override measurement related kill switch to OFF to allow adservices
    // If isOverride = false, override measurement related kill switch to meaningless value so that
    // PhFlags will use the default value.
    private void overrideMeasurementKillSwitches(boolean isOverride) {
        String overrideString = isOverride ? "false" : "null";
        ShellUtils.runShellCommand("setprop debug.adservices.global_kill_switch " + overrideString);
        ShellUtils.runShellCommand(
                "setprop debug.adservices.measurement_kill_switch " + overrideString);
        ShellUtils.runShellCommand(
                "setprop debug.adservices.measurement_api_register_source_kill_switch "
                        + overrideString);
        ShellUtils.runShellCommand(
                "setprop debug.adservices.measurement_api_register_trigger_kill_switch "
                        + overrideString);
        ShellUtils.runShellCommand(
                "setprop debug.adservices.measurement_api_register_web_source_kill_switch "
                        + overrideString);
        ShellUtils.runShellCommand(
                "setprop debug.adservices.measurement_api_register_web_trigger_kill_switch "
                        + overrideString);
        ShellUtils.runShellCommand(
                "setprop debug.adservices.measurement_api_delete_registrations_kill_switch "
                        + overrideString);
        ShellUtils.runShellCommand(
                "setprop debug.adservices.measurement_api_status_kill_switch " + overrideString);
        ShellUtils.runShellCommand("setprop debug.adservices.adid_kill_switch " + overrideString);
    }

    private void enableGlobalKillSwitch(boolean enabled) {
        ShellUtils.runShellCommand("setprop debug.adservices.global_kill_switch " + enabled);
    }

    private void enableMeasurementKillSwitch(boolean enabled) {
        ShellUtils.runShellCommand("setprop debug.adservices.measurement_kill_switch " + enabled);
    }

    private boolean registerSourceAndVerifyRateLimitReached(MeasurementManager manager)
            throws InterruptedException {
        final AtomicBoolean reachedLimit = new AtomicBoolean(false);
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        manager.registerSource(
                SOURCE_REGISTRATION_URI,
                null,
                CALLBACK_EXECUTOR,
                createCallbackWithCountdownOnLimitExceeded(countDownLatch, reachedLimit));

        countDownLatch.await();
        return reachedLimit.get();
    }

    private boolean registerWebSourceAndVerifyRateLimitReached(MeasurementManager manager)
            throws InterruptedException {
        final AtomicBoolean reachedLimit = new AtomicBoolean(false);
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        WebSourceParams webSourceParams =
                new WebSourceParams.Builder(SOURCE_REGISTRATION_URI)
                        .setDebugKeyAllowed(false)
                        .build();

        WebSourceRegistrationRequest webSourceRegistrationRequest =
                new WebSourceRegistrationRequest.Builder(
                                Collections.singletonList(webSourceParams), SOURCE_REGISTRATION_URI)
                        .setInputEvent(null)
                        .setAppDestination(OS_DESTINATION)
                        .setWebDestination(WEB_DESTINATION)
                        .setVerifiedDestination(null)
                        .build();

        manager.registerWebSource(
                webSourceRegistrationRequest,
                CALLBACK_EXECUTOR,
                createCallbackWithCountdownOnLimitExceeded(countDownLatch, reachedLimit));

        countDownLatch.await();
        return reachedLimit.get();
    }

    private OutcomeReceiver<Object, Exception> createCallbackWithCountdownOnLimitExceeded(
            CountDownLatch countDownLatch, AtomicBoolean reachedLimit) {
        return new OutcomeReceiver<Object, Exception>() {
            @Override
            public void onResult(@NonNull Object result) {
                countDownLatch.countDown();
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (error instanceof LimitExceededException) {
                    reachedLimit.set(true);
                }
                countDownLatch.countDown();
            }
        };
    }

    private int getRequestPerSecond(String flagName) {
        try {
            String permitString = SystemProperties.get("debug.adservices." + flagName);
            if (!TextUtils.isEmpty(permitString) && !"null".equalsIgnoreCase(permitString)) {
                return Integer.parseInt(permitString);
            }

            permitString = ShellUtils.runShellCommand("device_config get adservices " + flagName);
            if (!TextUtils.isEmpty(permitString) && !"null".equalsIgnoreCase(permitString)) {
                return Integer.parseInt(permitString);
            }
            return DEFAULT_REQUEST_PER_SECOND;
        } catch (Exception e) {
            return DEFAULT_REQUEST_PER_SECOND;
        }
    }
}
