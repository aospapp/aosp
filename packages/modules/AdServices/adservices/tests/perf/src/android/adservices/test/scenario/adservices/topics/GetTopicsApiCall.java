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

import android.adservices.clients.topics.AdvertisingTopicsClient;
import android.adservices.topics.GetTopicsResponse;
import android.content.Context;
import android.platform.test.scenario.annotation.Scenario;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.compatibility.common.util.ShellUtils;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Crystalball test for Topics API to collect System Heath metrics. */
@Scenario
@RunWith(JUnit4.class)
public class GetTopicsApiCall {
    private static final String TAG = "GetTopicsApiCall";

    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final Executor CALLBACK_EXECUTOR = Executors.newCachedThreadPool();
    private static final String SDKNAME = "sdk1";

    @Before
    public void setup() {
        disableGlobalKillSwitch();
        disableTopicsKillSwitch();
        enableUserConsent(true);
        overrideDisableTopicsEnrollmentCheck("1");
        // Extra flags need to be set when test is executed on S- for service to run (e.g.
        // to avoid invoking system-server related code).
        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.setFlags();
        }
    }

    @After
    public void teardown() {
        enableUserConsent(false);
        overrideDisableTopicsEnrollmentCheck("0");
        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.resetFlagsToDefault();
        }
    }

    private void measureGetTopics(String label) throws Exception {
        Log.i(TAG, "Calling getTopics()");
        final long start = System.currentTimeMillis();
        AdvertisingTopicsClient advertisingTopicsClient =
                new android.adservices.clients.topics.AdvertisingTopicsClient.Builder()
                        .setContext(sContext)
                        .setSdkName(SDKNAME)
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        GetTopicsResponse unUsedTopicsResponse = advertisingTopicsClient.getTopics().get();

        final long duration = System.currentTimeMillis() - start;
        // TODO(b/234452723): In the future, we will want to use either statsd or perfetto instead
        // Two major benefits are that statsd or perfetto will allow you to collect field data and
        // the metrics are going to be less reliable if you collect them from logcat because they
        // are impacted by latency in writing the messages to logcat and making the API calls.
        Log.i(TAG, "(" + label + ": " + duration + ")");
    }

    @Test
    public void testTopicsManager() throws Exception {
        measureGetTopics("TOPICS_COLD_START_LATENCY_METRIC");
        // We need to sleep here to prevent going above the Rate Limit.
        Thread.sleep(1000);
        measureGetTopics("TOPICS_HOT_START_LATENCY_METRIC");
    }

    // Override global_kill_switch to ignore the effect of actual PH values.
    protected void disableGlobalKillSwitch() {
        ShellUtils.runShellCommand("device_config put adservices global_kill_switch false");
    }

    // Override topics_kill_switch to ignore the effect of actual PH values.
    protected void disableTopicsKillSwitch() {
        ShellUtils.runShellCommand("device_config put adservices topics_kill_switch false");
    }

    // Override User Consent
    protected void enableUserConsent(boolean isEnabled) {
        ShellUtils.runShellCommand(
                "setprop debug.adservices.consent_manager_debug_mode " + isEnabled);
    }

    // Override the flag to disable Topics enrollment check.
    private void overrideDisableTopicsEnrollmentCheck(String val) {
        // Setting it to 1 here disables the Topics' enrollment check.
        ShellUtils.runShellCommand(
                "setprop debug.adservices.disable_topics_enrollment_check " + val);
    }
}
