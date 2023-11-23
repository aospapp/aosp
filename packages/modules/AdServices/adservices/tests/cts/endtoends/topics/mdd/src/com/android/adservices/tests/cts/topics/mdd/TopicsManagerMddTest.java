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

package com.android.adservices.tests.cts.topics;

import static com.google.common.truth.Truth.assertThat;

import android.adservices.clients.topics.AdvertisingTopicsClient;
import android.adservices.topics.GetTopicsResponse;
import android.adservices.topics.Topic;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.common.AdservicesTestHelper;
import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.compatibility.common.util.ShellUtils;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Run GetTopics API with following steps.
 * <li>Bind AdvertisingTopicsClient to allow background MDD jobs.
 * <li>Override MDD URI and trigger download.
 * <li>Unbind and re-bind AdvertisingTopicsClient to pick new downloaded assets.
 * <li>Verify topics are from the downloaded assets.
 */
@RunWith(JUnit4.class)
public class TopicsManagerMddTest {
    private static final String TAG = "TopicsManagerMddTest";
    // The JobId of the Epoch Computation.
    private static final int EPOCH_JOB_ID = 2;
    // Job ID for Mdd Wifi Charging Task.
    public static final int MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID = 14;

    // Override the Epoch Job Period to this value to speed up the epoch computation.
    private static final long TEST_EPOCH_JOB_PERIOD_MS = 3000;
    // Waiting time for assets to be downloaded after triggering MDD job.
    private static final long TEST_MDD_DOWNLOAD_WAIT_TIME_MS = 45000;
    // Waiting time for the AdvertisingTopicsClient to unbind.
    private static final long TEST_UNBIND_WAIT_TIME = 3000;

    // Default Epoch Period.
    private static final long TOPICS_EPOCH_JOB_PERIOD_MS = 7 * 86_400_000; // 7 days.

    // Manifest file that points to CTS test assets:
    // http://google3/wireless/android/adservices/mdd/topics_classifier/cts_test_1/
    // These assets are have asset version set to 0 for verification in tests.
    private static final String TEST_MDD_MANIFEST_FILE_URL =
            "https://www.gstatic.com/mdi-serving/rubidium-adservices-topics-classifier/1802/6cad713dedb6ab31af6dfaac0f07aa9f3733bcf2";

    // Use 0 percent for random topic in the test so that we can verify the returned topic.
    private static final int TEST_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC = 0;
    private static final int TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC = 5;

    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final Executor CALLBACK_EXECUTOR = Executors.newCachedThreadPool();

    private static final String ADSERVICES_PACKAGE_NAME =
            AdservicesTestHelper.getAdServicesPackageName(sContext, TAG);

    private String mDefaultMddManifestFileUrl;

    @Before
    public void setup() throws Exception {
        // Skip the test if it runs on unsupported platforms.
        Assume.assumeTrue(AdservicesTestHelper.isDeviceSupported());

        // Extra flags need to be set when test is executed on S- for service to run (e.g.
        // to avoid invoking system-server related code).
        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.setFlags();
        }

        // Kill AdServices process.
        AdservicesTestHelper.killAdservicesProcess(ADSERVICES_PACKAGE_NAME);

        // We need to skip 3 epochs so that if there is any usage from other test runs, it will
        // not be used for epoch retrieval.
        Thread.sleep(3 * TEST_EPOCH_JOB_PERIOD_MS);

        overrideEpochPeriod(TEST_EPOCH_JOB_PERIOD_MS);
        // We need to turn off random topic so that we can verify the returned topic.
        overridePercentageForRandomTopic(TEST_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC);

        // Store current default value before override.
        mDefaultMddManifestFileUrl = getDefaultMddManifestFileUrl();
        // Override manifest URL for Mdd.
        overrideMddManifestFileUrl(TEST_MDD_MANIFEST_FILE_URL);
    }

    @After
    public void teardown() {
        overrideEpochPeriod(TOPICS_EPOCH_JOB_PERIOD_MS);
        overridePercentageForRandomTopic(TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC);
        // Reset Mdd manifest file url to the default value.
        overrideMddManifestFileUrl(mDefaultMddManifestFileUrl);
        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.resetFlagsToDefault();
        }
    }

    @Test
    public void testTopicsManager_downloadModelViaMdd_runPrecomputedClassifier() throws Exception {
        // The Test App has 1 SDK: sdk1
        // sdk1 calls the Topics API.
        AdvertisingTopicsClient advertisingTopicsClient1 =
                new AdvertisingTopicsClient.Builder()
                        .setContext(sContext)
                        .setSdkName("sdk1")
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        // Call Topics API to bind TOPICS_SERVICE.
        GetTopicsResponse sdk1Result = advertisingTopicsClient1.getTopics().get();
        assertThat(sdk1Result.getTopics()).isEmpty();

        // Force epoch computation to pick up the model if download is complete. If not, trigger
        // pending download.
        forceEpochComputationJob();

        // Force to trigger the pending downloads in background.
        triggerAndWaitForMddToFinishDownload();

        // Kill AdServices API to unbind TOPICS_SERVICE.
        AdservicesTestHelper.killAdservicesProcess(ADSERVICES_PACKAGE_NAME);

        // Wait for AdvertisingTopicsClient to unbind.
        Thread.sleep(TEST_UNBIND_WAIT_TIME);

        // Create a new AdvertisingTopicsClient to bind TOPICS_SERVICE again.
        advertisingTopicsClient1 =
                new AdvertisingTopicsClient.Builder()
                        .setContext(sContext)
                        .setSdkName("sdk1")
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        // At beginning, Sdk1 receives no topic.
        sdk1Result = advertisingTopicsClient1.getTopics().get();
        assertThat(sdk1Result.getTopics()).isEmpty();

        // Now force the Epoch Computation Job. This should be done in the same epoch for
        // callersCanLearnMap to have the entry for processing.
        forceEpochComputationJob();

        // Wait to the next epoch. We will not need to do this after we implement the fix in
        // go/rb-topics-epoch-scheduling
        Thread.sleep(TEST_EPOCH_JOB_PERIOD_MS);

        // Since the sdk1 called the Topics API in the previous Epoch, it should receive some topic.
        sdk1Result = advertisingTopicsClient1.getTopics().get();
        assertThat(sdk1Result.getTopics()).isNotEmpty();

        // We only have 5 topics classified by the on-device classifier.
        // The app will be assigned one random topic from one of these 5 topics.
        assertThat(sdk1Result.getTopics()).hasSize(1);
        Topic topic = sdk1Result.getTopics().get(0);

        // Top 5 classifications for  with v1 model are [10301, 10302, 10303, 10304, 10305].
        List<Integer> expectedTopTopicIds = Arrays.asList(10301, 10302, 10303, 10304, 10305);
        assertThat(topic.getTopicId()).isIn(expectedTopTopicIds);

        // Verify assets are from the downloaded assets. These assets have asset version set to 0
        // for verification.
        // Test assets downloaded for CTS test:
        // http://google3/wireless/android/adservices/mdd/topics_classifier/cts_test_1/
        assertThat(topic.getModelVersion()).isEqualTo(0L);
        assertThat(topic.getTaxonomyVersion()).isEqualTo(0L);
    }

    private String getDefaultMddManifestFileUrl() {
        return ShellUtils.runShellCommand(
                "device_config get adservices mdd_topics_classifier_manifest_file_url");
    }

    // Override the flag to set manifest url for Mdd.
    private void overrideMddManifestFileUrl(String val) {
        ShellUtils.runShellCommand(
                "device_config put adservices mdd_topics_classifier_manifest_file_url " + val);
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

    private void triggerAndWaitForMddToFinishDownload() throws InterruptedException {
        // Forces JobScheduler to run Mdd.
        ShellUtils.runShellCommand(
                "cmd jobscheduler run -f"
                        + " "
                        + ADSERVICES_PACKAGE_NAME
                        + " "
                        + MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID);

        // Wait for TEST_MDD_DOWNLOAD_WAIT_TIME_MS seconds for the assets to be downloaded.
        Thread.sleep(TEST_MDD_DOWNLOAD_WAIT_TIME_MS);
    }

    /** Forces JobScheduler to run the Epoch Computation job */
    private void forceEpochComputationJob() {
        ShellUtils.runShellCommand(
                "cmd jobscheduler run -f" + " " + ADSERVICES_PACKAGE_NAME + " " + EPOCH_JOB_ID);
    }
}
