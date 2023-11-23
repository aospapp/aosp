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

package android.adservices.test.scenario.adservices.topics;

import static com.google.common.truth.Truth.assertThat;

import android.adservices.clients.topics.AdvertisingTopicsClient;
import android.adservices.topics.GetTopicsResponse;
import android.adservices.topics.Topic;
import android.content.Context;
import android.platform.test.scenario.annotation.Scenario;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.common.AdservicesTestHelper;
import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.compatibility.common.util.ShellUtils;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Crystalball test for Topics API to test epoch computation using the On Device classifier. */
@Scenario
@RunWith(JUnit4.class)
public class TopicsEpochComputationOnDeviceClassifier {
    private static final String TAG = "TopicsEpochComputation";

    // Metric name for Crystalball test
    private static final String EPOCH_COMPUTATION_DURATION = "EPOCH_COMPUTATION_DURATION";

    // The JobId of the Epoch Computation.
    private static final int EPOCH_JOB_ID = 2;

    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final Executor CALLBACK_EXECUTOR = Executors.newCachedThreadPool();

    private static final String ADSERVICES_PACKAGE_NAME =
            AdservicesTestHelper.getAdServicesPackageName(sContext, TAG);

    private static final DateTimeFormatter LOG_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final String EPOCH_COMPUTATION_START_LOG = "Start of Epoch Computation";

    private static final String EPOCH_COMPUTATION_END_LOG = "End of Epoch Computation";

    private static final String EPOCH_START_TIMESTAMP_KEY = "start";

    private static final String EPOCH_STOP_TIMESTAMP_KEY = "end";

    // Classifier test constants.
    private static final int TEST_CLASSIFIER_NUMBER_OF_TOP_LABELS = 5;
    // Each app is given topics with a confidence score between 0.0 to 1.0 float value. This
    // denotes how confident are you that a particular topic t1 is related to the app x that is
    // classified.
    // Threshold value for classifier confidence set to 0 to allow all topics and avoid filtering.
    private static final float TEST_CLASSIFIER_THRESHOLD = 0.0f;
    // ON_DEVICE_CLASSIFIER
    private static final int TEST_CLASSIFIER_TYPE = 1;
    // Use 0 percent for random topic in the test so that we can verify the returned topic.
    private static final int TEST_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC = 0;
    // Override the Epoch Job Period to this value to speed up the epoch computation.
    private static final long TEST_EPOCH_JOB_PERIOD_MS = 4000;

    // Classifier default constants.
    private static final int DEFAULT_CLASSIFIER_NUMBER_OF_TOP_LABELS = 3;
    // Threshold value for classifier confidence set back to the default.
    private static final float DEFAULT_CLASSIFIER_THRESHOLD = 0.1f;
    // PRECOMPUTED_THEN_ON_DEVICE_CLASSIFIER
    private static final int DEFAULT_CLASSIFIER_TYPE = 3;
    private static final int DEFAULT_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC = 5;
    // Default Epoch Period.
    private static final long DEFAULT_TOPICS_EPOCH_JOB_PERIOD_MS = 7 * 86_400_000; // 7 days.

    @Before
    public void setup() throws Exception {
        // We need to skip 3 epochs so that if there is any usage from other test runs, it will
        // not be used for epoch retrieval.
        Thread.sleep(3 * TEST_EPOCH_JOB_PERIOD_MS);

        overridingBeforeTest();
    }

    @After
    public void teardown() {
        overridingAfterTest();
    }

    @Test
    public void testEpochComputation() throws Exception {
        // The Test App has 1 SDK: sdk3
        // sdk3 calls the Topics API.
        AdvertisingTopicsClient advertisingTopicsClient =
                new AdvertisingTopicsClient.Builder()
                        .setContext(sContext)
                        .setSdkName("sdk3")
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        // At beginning, Sdk3 receives no topic.
        GetTopicsResponse sdk3Result = advertisingTopicsClient.getTopics().get();
        assertThat(sdk3Result.getTopics()).isEmpty();

        Instant startTime = Clock.systemUTC().instant();
        // Now force the Epoch Computation Job. This should be done in the same epoch for
        // callersCanLearnMap to have the entry for processing.
        forceEpochComputationJob();

        // Wait to the next epoch. We will not need to do this after we implement the fix in
        // go/rb-topics-epoch-scheduling
        Thread.sleep(TEST_EPOCH_JOB_PERIOD_MS);

        // calculate and log epoch computation duration after some delay so that epoch
        // computation job is finished.
        logEpochComputationDuration(startTime);

        // Since the sdk3 called the Topics API in the previous Epoch, it should receive some topic.
        sdk3Result = advertisingTopicsClient.getTopics().get();
        assertThat(sdk3Result.getTopics()).isNotEmpty();

        // We only have 1 test app which has 5 classification topics: 10147,10253,10175,10254,10333
        // in the precomputed list.
        // These 5 classification topics will become top 5 topics of the epoch since there is
        // no other apps calling Topics API.
        // The app will be assigned one random topic from one of these 5 topics.
        assertThat(sdk3Result.getTopics()).hasSize(1);
        Topic topic = sdk3Result.getTopics().get(0);

        // Top 5 classifications for empty string with v3 model are [10230, 10228, 10253, 10232,
        // 10140]. This is computed by running the model on the device for empty string.
        // topic is one of the 5 classification topics of the Test App.
        assertThat(topic.getTopicId()).isIn(Arrays.asList(10230, 10228, 10253, 10232, 10140));

        assertThat(topic.getModelVersion()).isAtLeast(1L);
        assertThat(topic.getTaxonomyVersion()).isAtLeast(1L);
    }

    private void overridingBeforeTest() {
        disableGlobalKillSwitch();
        disableTopicsKillSwitch();
        overridingAdservicesLoggingLevel("DEBUG");

        overrideDisableTopicsEnrollmentCheck("1");
        overrideEpochPeriod(TEST_EPOCH_JOB_PERIOD_MS);

        // We need to turn off random topic so that we can verify the returned topic.
        overridePercentageForRandomTopic(TEST_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC);

        // We need to turn the Consent Manager into debug mode
        overrideConsentManagerDebugMode();

        // Set classifier flag to use on-device classifier.
        overrideClassifierType(TEST_CLASSIFIER_TYPE);

        // Set number of top labels returned by the on-device classifier to 5.
        overrideClassifierNumberOfTopLabels(TEST_CLASSIFIER_NUMBER_OF_TOP_LABELS);
        // Remove classifier threshold by setting it to 0.
        overrideClassifierThreshold(TEST_CLASSIFIER_THRESHOLD);

        // Extra flags need to be set when test is executed on S- for service to run (e.g.
        // to avoid invoking system-server related code).
        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.setFlags();
        }
    }

    private void overridingAfterTest() {
        overrideDisableTopicsEnrollmentCheck("0");
        overrideEpochPeriod(DEFAULT_TOPICS_EPOCH_JOB_PERIOD_MS);
        overridePercentageForRandomTopic(DEFAULT_TOPICS_PERCENTAGE_FOR_RANDOM_TOPIC);
        overridingAdservicesLoggingLevel("INFO");

        // Set classifier flag back to default.
        overrideClassifierType(DEFAULT_CLASSIFIER_TYPE);
        // Set number of top labels returned by the on-device classifier back to default.
        overrideClassifierNumberOfTopLabels(DEFAULT_CLASSIFIER_NUMBER_OF_TOP_LABELS);
        // Set classifier threshold back to default.
        overrideClassifierThreshold(DEFAULT_CLASSIFIER_THRESHOLD);

        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.resetFlagsToDefault();
        }
    }

    // Override the flag to disable Topics enrollment check.
    private void overrideDisableTopicsEnrollmentCheck(String val) {
        // Setting it to 1 here disables the Topics' enrollment check.
        ShellUtils.runShellCommand(
                "setprop debug.adservices.disable_topics_enrollment_check " + val);
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

    // Override the Consent Manager behaviour - Consent Given
    private void overrideConsentManagerDebugMode() {
        ShellUtils.runShellCommand("setprop debug.adservices.consent_manager_debug_mode true");
    }

    // Override the flag to select classifier type.
    private void overrideClassifierType(int val) {
        ShellUtils.runShellCommand("device_config put adservices classifier_type " + val);
    }

    // Override the flag to change the number of top labels returned by on-device classifier type.
    private void overrideClassifierNumberOfTopLabels(int val) {
        ShellUtils.runShellCommand(
                "device_config put adservices classifier_number_of_top_labels " + val);
    }

    // Override the flag to change the threshold for the classifier.
    private void overrideClassifierThreshold(float val) {
        ShellUtils.runShellCommand("device_config put adservices classifier_threshold " + val);
    }

    /** Forces JobScheduler to run the Epoch Computation job */
    private void forceEpochComputationJob() {
        ShellUtils.runShellCommand(
                "cmd jobscheduler run -f" + " " + ADSERVICES_PACKAGE_NAME + " " + EPOCH_JOB_ID);
    }

    private void logEpochComputationDuration(Instant startTime) throws Exception {
        long epoch_computation_duration =
                processLogCatStreamToGetMetricMap(getMetricsEvents(startTime));
        Log.i(TAG, "(" + EPOCH_COMPUTATION_DURATION + ": " + epoch_computation_duration + ")");
    }

    /** Return AdServices(EpochManager) logs that will be used to build the test metrics. */
    public InputStream getMetricsEvents(Instant startTime) throws IOException {
        ProcessBuilder pb =
                new ProcessBuilder(
                        Arrays.asList(
                                "logcat",
                                "-s",
                                "adservices.topics:V",
                                "-t",
                                LOG_TIME_FORMATTER.format(startTime),
                                "|",
                                "grep",
                                "Epoch"));
        return pb.start().getInputStream();
    }

    /**
     * Filters the start and end log for the epoch computation and based on that calculates the
     * duration of epoch computation. If we fail to parse the start or end log for epoch
     * computation, we catch ParseException and in the end throw an exception.
     *
     * @param inputStream the logcat stream which contains start and end time info for the epoch
     *     computation
     * @return the value of epoch computation latency
     * @throws Exception if the test failed to get the time point for epoch computation's start and
     *     end.
     */
    private Long processLogCatStreamToGetMetricMap(InputStream inputStream) throws Exception {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        Map<String, Long> output = new HashMap<String, Long>();
        bufferedReader
                .lines()
                .filter(
                        line ->
                                line.contains(EPOCH_COMPUTATION_START_LOG)
                                        || line.contains(EPOCH_COMPUTATION_END_LOG))
                .forEach(
                        line -> {
                            if (line.contains(EPOCH_COMPUTATION_START_LOG)) {
                                try {
                                    output.put(
                                            EPOCH_START_TIMESTAMP_KEY, getTimestampFromLog(line));
                                } catch (ParseException e) {
                                    Log.e(
                                            TAG,
                                            String.format(
                                                    "Caught ParseException when fetching start"
                                                            + " time for epoch computation: %s",
                                                    e.toString()));
                                }
                            } else {
                                try {
                                    output.put(EPOCH_STOP_TIMESTAMP_KEY, getTimestampFromLog(line));
                                } catch (ParseException e) {
                                    Log.e(
                                            TAG,
                                            String.format(
                                                    "Caught ParseException when fetching end time"
                                                            + " for epoch computation: %s",
                                                    e.toString()));
                                }
                            }
                        });

        if (output.containsKey(EPOCH_START_TIMESTAMP_KEY)
                && output.containsKey(EPOCH_STOP_TIMESTAMP_KEY)) {
            return output.get(EPOCH_STOP_TIMESTAMP_KEY) - output.get(EPOCH_START_TIMESTAMP_KEY);
        }
        throw new Exception("Cannot get the time of Epoch Computation's start and end");
    }

    /**
     * Parses the timestamp from the log. Example log: 10-06 17:58:20.173 14950 14966 D adservices:
     * Start of Epoch Computation
     */
    private static Long getTimestampFromLog(String log) throws ParseException {
        String[] words = log.split(" ");
        SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd hh:mm:ss.SSS");
        Date parsedDate = dateFormat.parse(words[0] + " " + words[1]);
        return parsedDate.getTime();
    }

    private void overridingAdservicesLoggingLevel(String loggingLevel) {
        ShellUtils.runShellCommand("setprop log.tag.adservices %s", loggingLevel);
        ShellUtils.runShellCommand("setprop log.tag.adservices.topics %s", loggingLevel);
    }

    // Override global_kill_switch to ignore the effect of actual PH values.
    private void disableGlobalKillSwitch() {
        ShellUtils.runShellCommand("device_config put adservices global_kill_switch false");
    }

    // Override topics_kill_switch to ignore the effect of actual PH values.
    private void disableTopicsKillSwitch() {
        ShellUtils.runShellCommand("device_config put adservices topics_kill_switch false");
    }
}
