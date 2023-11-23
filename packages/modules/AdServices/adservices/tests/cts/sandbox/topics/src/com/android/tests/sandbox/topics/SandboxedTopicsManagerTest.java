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

package com.android.tests.sandbox.topics;

import static com.google.common.truth.Truth.assertThat;

import android.adservices.clients.topics.AdvertisingTopicsClient;
import android.adservices.topics.GetTopicsResponse;
import android.app.sdksandbox.SdkSandboxManager;
import android.app.sdksandbox.testutils.FakeLoadSdkCallback;
import android.content.Context;
import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.adservices.common.AdservicesTestHelper;
import com.android.compatibility.common.util.ShellUtils;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/*
 * Test Topics API running within the Sandbox.
 */
@RunWith(JUnit4.class)
public class SandboxedTopicsManagerTest {
    private static final String TAG = "SandboxedTopicsManagerTest";
    private static final Executor CALLBACK_EXECUTOR = Executors.newCachedThreadPool();
    private static final String SDK_NAME = "com.android.tests.providers.sdk1";

    // The JobId of the Epoch Computation.
    private static final int EPOCH_JOB_ID = 2;

    // Override the Epoch Job Period to this value to speed up the epoch computation.
    private static final long TEST_EPOCH_JOB_PERIOD_MS = 3000;

    // Default Epoch Period.
    private static final long TOPICS_EPOCH_JOB_PERIOD_MS = 7 * 86_400_000; // 7 days.

    // Use 0 percent for random topic in the test so that we can verify the returned topic.
    private static final int TEST_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC = 0;
    private static final int TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC = 5;

    private static final Context sContext =
            InstrumentationRegistry.getInstrumentation().getContext();
    private static final String ADSERVICES_PACKAGE_NAME =
            AdservicesTestHelper.getAdServicesPackageName(sContext, TAG);

    @Before
    public void setup() throws TimeoutException, InterruptedException {
        // Skip the test if it runs on unsupported platforms.
        Assume.assumeTrue(AdservicesTestHelper.isDeviceSupported());
        // Kill adservices process to avoid interfering from other tests.
        AdservicesTestHelper.killAdservicesProcess(ADSERVICES_PACKAGE_NAME);

        // We need to skip 3 epochs so that if there is any usage from other test runs, it will
        // not be used for epoch retrieval.
        Thread.sleep(3 * TEST_EPOCH_JOB_PERIOD_MS);

        // Start a foreground activity
        SimpleActivity.startAndWaitForSimpleActivity(sContext, Duration.ofMillis(1000));

        // The setup for this test:
        // SandboxedTopicsManagerTest is the test app. It will load the Sdk1 into the Sandbox.
        // The Sdk1 (running within the Sandbox) will query Topics API and verify that the correct
        // Topics are returned.
        // After Sdk1 verifies the result, it will communicate back to the
        // SandboxedTopicsManagerTest via the loadSdk's callback.
        // In this test, we use the loadSdk's callback as a 2-way communications between the Test
        // app (this class) and the Sdk running within the Sandbox process.

        overrideEpochPeriod(TEST_EPOCH_JOB_PERIOD_MS);

        // We need to turn off random topic so that we can verify the returned topic.
        overridePercentageForRandomTopic(TEST_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC);
    }

    @After
    public void shutDown() {
        overrideEpochPeriod(TOPICS_EPOCH_JOB_PERIOD_MS);
        overridePercentageForRandomTopic(TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC);

        SimpleActivity.stopSimpleActivity(sContext);
    }

    @Test
    public void loadSdkAndRunTopicsApi() throws Exception {
        final SdkSandboxManager sdkSandboxManager =
                sContext.getSystemService(SdkSandboxManager.class);

        final FakeLoadSdkCallback callback = new FakeLoadSdkCallback();

        // Let EpochJobService finish onStart() when first getting scheduled.
        Thread.sleep(TEST_EPOCH_JOB_PERIOD_MS);

        // Call Topics API once to record usage for epoch computation, so that SDK can get topics
        // when calling Topics API.
        // Note this invocation mocks SDK calling Topics API by setting SdkName. This way avoids
        // the async problem between epoch computation and Topics API invocation from SDK.
        AdvertisingTopicsClient advertisingTopicsClient =
                new AdvertisingTopicsClient.Builder()
                        .setContext(sContext)
                        .setSdkName(SDK_NAME)
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();
        GetTopicsResponse response = advertisingTopicsClient.getTopics().get();
        assertThat(response.getTopics()).isEmpty();

        // Now force the Epoch Computation Job. This should be done in the same epoch for
        // callersCanLearnMap to have the entry for processing.
        forceEpochComputationJob();

        // Wait to the next epoch.
        Thread.sleep(TEST_EPOCH_JOB_PERIOD_MS);

        sdkSandboxManager.loadSdk(SDK_NAME, new Bundle(), CALLBACK_EXECUTOR, callback);

        // This verifies that the Sdk1 in the Sandbox gets back the correct topic.
        // If the Sdk1 did not get correct topic, it will trigger the callback.onLoadSdkError
        callback.assertLoadSdkIsSuccessful();
    }

    // Override the Epoch Period to shorten the Epoch Length in the test.
    private void overrideEpochPeriod(long overrideEpochPeriod) {
        ShellUtils.runShellCommand(
                "setprop debug.adservices.topics_epoch_job_period_ms " + overrideEpochPeriod);
    }

    // Override the Percentage For Random Topic in the test.
    private void overridePercentageForRandomTopic(long overridePercentage) {
        ShellUtils.runShellCommand(
                "setprop debug.adservices.topics_percentage_for_random_topics "
                        + overridePercentage);
    }

    /** Forces JobScheduler to run the Epoch Computation job */
    private void forceEpochComputationJob() {
        ShellUtils.runShellCommand(
                "cmd jobscheduler run -f" + " " + ADSERVICES_PACKAGE_NAME + " " + EPOCH_JOB_ID);
    }
}
