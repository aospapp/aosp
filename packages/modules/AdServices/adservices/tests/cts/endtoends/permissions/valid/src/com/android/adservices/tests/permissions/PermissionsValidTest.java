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

package com.android.adservices.tests.permissions;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.adservices.adselection.AdSelectionConfig;
import android.adservices.adselection.AdSelectionConfigFixture;
import android.adservices.adselection.ReportImpressionRequest;
import android.adservices.clients.adselection.AdSelectionClient;
import android.adservices.clients.customaudience.AdvertisingCustomAudienceClient;
import android.adservices.clients.topics.AdvertisingTopicsClient;
import android.adservices.common.AdTechIdentifier;
import android.adservices.customaudience.CustomAudience;
import android.adservices.topics.GetTopicsResponse;
import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.adservices.common.AdservicesTestHelper;
import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.adservices.service.js.JSScriptEngine;
import com.android.compatibility.common.util.ShellUtils;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RunWith(AndroidJUnit4.class)
// TODO: Add tests for measurement (b/238194122).
public class PermissionsValidTest {
    private static final Executor CALLBACK_EXECUTOR = Executors.newCachedThreadPool();
    private static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final String PERMISSION_NOT_REQUESTED =
            "Caller is not authorized to call this API. Permission was not requested.";

    private String mPreviousAppAllowList;

    @Before
    public void setup() {
        // Skip the test if it runs on unsupported platforms
        Assume.assumeTrue(AdservicesTestHelper.isDeviceSupported());

        if (!SdkLevel.isAtLeastT()) {
            mPreviousAppAllowList =
                    CompatAdServicesTestUtils.getAndOverridePpapiAppAllowList(
                            sContext.getPackageName());
            CompatAdServicesTestUtils.setFlags();
            // TODO: Remove after EngProd figures out why setprop commands from AndroidTest
            //  .ExtServices.xml are not executing in post-submit (b/276909363)
            setAdditionalFlags();
        }
    }

    @After
    public void tearDown() throws Exception {
        if (!AdservicesTestHelper.isDeviceSupported()) {
            return;
        }

        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.setPpapiAppAllowList(mPreviousAppAllowList);
            CompatAdServicesTestUtils.resetFlagsToDefault();
            // TODO: Remove after EngProd figures out why setprop commands from AndroidTest
            //  .ExtServices.xml are not executing in post-submit (b/276909363)
            resetAdditionalFlags();
        }
    }

    private void setAdditionalFlags() {
        ShellUtils.runShellCommand("setprop debug.adservices.consent_manager_debug_mode true");
        ShellUtils.runShellCommand("setprop debug.adservices.disable_fledge_enrollment_check true");
        ShellUtils.runShellCommand("setprop debug.adservices.disable_topics_enrollment_check true");
        // TODO: Investigate why this is needed (b/276916172)
        ShellUtils.runShellCommand("device_config put adservices ppapi_app_signature_allow_list *");
    }

    private void resetAdditionalFlags() {
        ShellUtils.runShellCommand("setprop debug.adservices.consent_manager_debug_mode null");
        ShellUtils.runShellCommand("setprop debug.adservices.disable_fledge_enrollment_check null");
        ShellUtils.runShellCommand("setprop debug.adservices.disable_topics_enrollment_check null");
        ShellUtils.runShellCommand(
                "device_config put adservices ppapi_app_signature_allow_list null");
    }

    @Test
    public void testValidPermissions_topics() throws Exception {
        AdvertisingTopicsClient advertisingTopicsClient1 =
                new AdvertisingTopicsClient.Builder()
                        .setContext(sContext)
                        .setSdkName("sdk1")
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        GetTopicsResponse sdk1Result = advertisingTopicsClient1.getTopics().get();
        // Not getting an error here indicates that permissions are valid. The valid case is also
        // tested in TopicsManagerTest.
        assertThat(sdk1Result.getTopics()).isEmpty();
    }

    @Test
    public void testValidPermissions_fledgeJoinCustomAudience()
            throws ExecutionException, InterruptedException {
        AdvertisingCustomAudienceClient customAudienceClient =
                new AdvertisingCustomAudienceClient.Builder()
                        .setContext(sContext)
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        CustomAudience customAudience =
                new CustomAudience.Builder()
                        .setBuyer(AdTechIdentifier.fromString("test.com"))
                        .setName("exampleCustomAudience")
                        .setDailyUpdateUri(Uri.parse("https://test.com/daily-update"))
                        .setBiddingLogicUri(Uri.parse("https://test.com/bidding-logic"))
                        .build();

        customAudienceClient.joinCustomAudience(customAudience).get();
    }

    @Test
    public void testValidPermissions_selectAds_adSelectionConfig() {
        Assume.assumeTrue(JSScriptEngine.AvailabilityChecker.isJSSandboxAvailable());
        AdSelectionConfig adSelectionConfig = AdSelectionConfigFixture.anAdSelectionConfig();

        AdSelectionClient mAdSelectionClient =
                new AdSelectionClient.Builder()
                        .setContext(sContext)
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        ExecutionException exception =
                assertThrows(
                        ExecutionException.class,
                        () -> mAdSelectionClient.selectAds(adSelectionConfig).get());
        // We only need to get past the permissions check for this test to be valid
        assertThat(exception.getMessage()).isNotEqualTo(PERMISSION_NOT_REQUESTED);
    }

    @Test
    public void testValidPermissions_reportImpression() {
        Assume.assumeTrue(JSScriptEngine.AvailabilityChecker.isJSSandboxAvailable());
        AdSelectionConfig adSelectionConfig = AdSelectionConfigFixture.anAdSelectionConfig();

        long adSelectionId = 1;

        AdSelectionClient mAdSelectionClient =
                new AdSelectionClient.Builder()
                        .setContext(sContext)
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        ReportImpressionRequest request =
                new ReportImpressionRequest(adSelectionId, adSelectionConfig);

        ExecutionException exception =
                assertThrows(
                        ExecutionException.class,
                        () -> mAdSelectionClient.reportImpression(request).get());
        // We only need to get past the permissions check for this test to be valid
        assertThat(exception.getMessage()).isNotEqualTo(PERMISSION_NOT_REQUESTED);
    }
    // TODO(b/274723533): Uncomment after un-hiding the API
    /*
    @Test
    public void testValidPermissions_reportInteraction() {
        long adSelectionId = 1;
        String interactionKey = "click";
        String interactionData = "{\"key\":\"value\"}";

        AdSelectionClient mAdSelectionClient =
                new AdSelectionClient.Builder()
                        .setContext(sContext)
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        ReportInteractionRequest request =
                new ReportInteractionRequest(
                        adSelectionId,
                        interactionKey,
                        interactionData,
                        ReportInteractionRequest.FLAG_REPORTING_DESTINATION_BUYER
                                | ReportInteractionRequest.FLAG_REPORTING_DESTINATION_SELLER);

        ExecutionException exception =
                assertThrows(
                        ExecutionException.class,
                        () -> mAdSelectionClient.reportInteraction(request).get());
        // We only need to get past the permissions check for this test to be valid
        assertThat(exception.getMessage()).isNotEqualTo(PERMISSION_NOT_REQUESTED);
    }
    */

    // TODO(b/221876775): Unhide for frequency cap mainline promotion
    /*
    @Test
    public void testValidPermissions_updateAdCounterHistogram() {
        long adSelectionId = 1;

        AdSelectionClient mAdSelectionClient =
                new AdSelectionClient.Builder()
                        .setContext(sContext)
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        UpdateAdCounterHistogramRequest request =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(adSelectionId)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION)
                        .setCallerAdTech(AdTechIdentifier.fromString("test.com"))
                        .build();
        ExecutionException exception =
                assertThrows(
                        ExecutionException.class,
                        () -> mAdSelectionClient.updateAdCounterHistogram(request).get());

        // We only need to get past the permissions check for this test to be valid
        assertThat(exception.getMessage()).isNotEqualTo(PERMISSION_NOT_REQUESTED);
    }
    */

    @Test
    public void testValidPermissions_fledgeLeaveCustomAudience()
            throws ExecutionException, InterruptedException {
        AdvertisingCustomAudienceClient customAudienceClient =
                new AdvertisingCustomAudienceClient.Builder()
                        .setContext(sContext)
                        .setExecutor(CALLBACK_EXECUTOR)
                        .build();

        customAudienceClient
                .leaveCustomAudience(
                        AdTechIdentifier.fromString("test.com"), "exampleCustomAudience")
                .get();
    }
}
