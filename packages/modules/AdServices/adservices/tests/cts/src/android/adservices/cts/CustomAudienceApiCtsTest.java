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

package android.adservices.cts;

import static android.adservices.common.AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.adservices.clients.customaudience.AdvertisingCustomAudienceClient;
import android.adservices.clients.customaudience.TestAdvertisingCustomAudienceClient;
import android.adservices.common.AdDataFixture;
import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.CommonFixture;
import android.adservices.customaudience.AddCustomAudienceOverrideRequest;
import android.adservices.customaudience.CustomAudience;
import android.adservices.customaudience.CustomAudienceFixture;
import android.adservices.customaudience.RemoveCustomAudienceOverrideRequest;
import android.os.Process;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.adservices.common.AdservicesTestHelper;
import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.adservices.service.PhFlagsFixture;
import com.android.adservices.service.devapi.DevContext;
import com.android.adservices.service.devapi.DevContextFilter;
import com.android.modules.utils.build.SdkLevel;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CustomAudienceApiCtsTest extends ForegroundCtsTest {
    private AdvertisingCustomAudienceClient mClient;
    private TestAdvertisingCustomAudienceClient mTestClient;

    private static final AdTechIdentifier BUYER = AdTechIdentifier.fromString("buyer");
    private static final String NAME = "name";
    private static final String BIDDING_LOGIC_JS = "function test() { return \"hello world\"; }";
    private static final AdSelectionSignals TRUSTED_BIDDING_DATA =
            AdSelectionSignals.fromString("{\"trusted_bidding_data\":1}");

    private boolean mIsDebugMode;
    private String mPreviousAppAllowList;

    private final ArrayList<CustomAudience> mCustomAudiencesToCleanUp = new ArrayList<>();

    @Before
    public void setup() throws InterruptedException {
        // Skip the test if it runs on unsupported platforms
        Assume.assumeTrue(AdservicesTestHelper.isDeviceSupported());

        if (SdkLevel.isAtLeastT()) {
            assertForegroundActivityStarted();
        } else {
            mPreviousAppAllowList =
                    CompatAdServicesTestUtils.getAndOverridePpapiAppAllowList(
                            sContext.getPackageName());
            CompatAdServicesTestUtils.setFlags();
        }

        mClient =
                new AdvertisingCustomAudienceClient.Builder()
                        .setContext(sContext)
                        .setExecutor(MoreExecutors.directExecutor())
                        .build();
        mTestClient =
                new TestAdvertisingCustomAudienceClient.Builder()
                        .setContext(sContext)
                        .setExecutor(MoreExecutors.directExecutor())
                        .build();
        DevContext devContext = DevContextFilter.create(sContext).createDevContext(Process.myUid());
        mIsDebugMode = devContext.getDevOptionsEnabled();

        // Needed to test different custom audience limits
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.WRITE_DEVICE_CONFIG);
        PhFlagsFixture.overrideEnableEnrollmentSeed(true);
        // TODO(b/266725238): Remove/modify once the API rate limit has been adjusted for FLEDGE
        CommonFixture.doSleep(PhFlagsFixture.DEFAULT_API_RATE_LIMIT_SLEEP_MS);
    }

    @After
    public void tearDown() throws ExecutionException, InterruptedException, TimeoutException {
        if (!AdservicesTestHelper.isDeviceSupported()) {
            return;
        }

        leaveJoinedCustomAudiences();
        PhFlagsFixture.overrideEnableEnrollmentSeed(false);

        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.setPpapiAppAllowList(mPreviousAppAllowList);
            CompatAdServicesTestUtils.resetFlagsToDefault();
        }
    }

    @Test
    public void testJoinCustomAudience_validCustomAudience_success()
            throws ExecutionException, InterruptedException, TimeoutException {
        joinCustomAudience(
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1).build());
    }

    @Test
    public void testJoinCustomAudience_validCustomAudience_success_usingGetMethodToCreateManager()
            throws ExecutionException, InterruptedException, TimeoutException {
        // Override mClient with a new value that explicitly uses the Get method to create manager
        createClientUsingGetMethod();
        testJoinCustomAudience_validCustomAudience_success();
    }

    @Test
    public void testJoinCustomAudience_withMissingEnrollment_fail() {
        Exception exception =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                joinCustomAudience(
                                        CustomAudienceFixture.getValidBuilderForBuyer(
                                                        CommonFixture.NOT_ENROLLED_BUYER)
                                                .build()));
        assertThat(exception).hasCauseThat().isInstanceOf(SecurityException.class);
        assertThat(exception)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo(SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE);
    }

    @Test
    public void testJoinCustomAudience_withMissingEnrollment_fail_usingGetMethodToCreateManager() {
        // Override mClient with a new value that explicitly uses the Get method to create manager
        createClientUsingGetMethod();
        testJoinCustomAudience_withMissingEnrollment_fail();
    }

    @Test
    public void testJoinCustomAudience_invalidAdsMetadata_fail() {
        CustomAudience customAudienceWithInvalidAdDataMetadata =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                        .setAds(AdDataFixture.getInvalidAdsByBuyer(CommonFixture.VALID_BUYER_1))
                        .build();

        Exception exception =
                assertThrows(
                        ExecutionException.class,
                        () -> joinCustomAudience(customAudienceWithInvalidAdDataMetadata));
        assertThat(exception).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
        assertThat(exception).hasCauseThat().hasMessageThat().isEqualTo(null);
    }

    @Test
    public void testJoinCustomAudience_invalidAdsMetadata_fail_usingGetMethodToCreateManager() {
        // Override mClient with a new value that explicitly uses the Get method to create manager
        createClientUsingGetMethod();
        testJoinCustomAudience_invalidAdsMetadata_fail();
    }

    @Test
    public void testJoinCustomAudience_invalidAdsRenderUris_fail() {
        CustomAudience customAudienceWithInvalidAdDataRenderUris =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                        .setAds(
                                AdDataFixture.getInvalidAdsByBuyer(
                                        AdTechIdentifier.fromString(
                                                "!\\@#\"$#@NOTAREALURI$%487\\")))
                        .build();

        Exception exception =
                assertThrows(
                        ExecutionException.class,
                        () -> joinCustomAudience(customAudienceWithInvalidAdDataRenderUris));
        assertThat(exception).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
        assertThat(exception).hasCauseThat().hasMessageThat().isEqualTo(null);
    }

    @Test
    public void testJoinCustomAudience_invalidNumberOfAds_fail() {
        PhFlagsFixture.overrideFledgeCustomAudienceMaxNumAds(2);
        try {
            CustomAudience customAudienceWithInvalidNumberOfAds =
                    CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                            .setAds(
                                    ImmutableList.of(
                                            AdDataFixture.getValidAdDataByBuyer(
                                                    CommonFixture.VALID_BUYER_1, 1),
                                            AdDataFixture.getValidAdDataByBuyer(
                                                    CommonFixture.VALID_BUYER_1, 2),
                                            AdDataFixture.getValidAdDataByBuyer(
                                                    CommonFixture.VALID_BUYER_1, 3)))
                            .build();

            Exception exception =
                    assertThrows(
                            ExecutionException.class,
                            () -> joinCustomAudience(customAudienceWithInvalidNumberOfAds));
            assertThat(exception).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
            assertThat(exception).hasCauseThat().hasMessageThat().isEqualTo(null);
        } finally {
            PhFlagsFixture.overrideFledgeCustomAudienceMaxNumAds(100);
        }
    }

    @Test
    public void testJoinCustomAudience_mismatchDailyFetchUriDomain_fail() {
        CustomAudience customAudienceWithMismatchedDailyFetchUriDomain =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                        .setDailyUpdateUri(
                                CustomAudienceFixture.getValidDailyUpdateUriByBuyer(
                                        CommonFixture.VALID_BUYER_2))
                        .build();

        Exception exception =
                assertThrows(
                        ExecutionException.class,
                        () -> joinCustomAudience(customAudienceWithMismatchedDailyFetchUriDomain));
        assertThat(exception).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
        assertThat(exception).hasCauseThat().hasMessageThat().isEqualTo(null);
    }

    @Test
    public void testJoinCustomAudience_illegalExpirationTime_fail() {
        CustomAudience customAudience =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                        .setExpirationTime(CustomAudienceFixture.INVALID_BEYOND_MAX_EXPIRATION_TIME)
                        .build();
        Exception exception =
                assertThrows(ExecutionException.class, () -> joinCustomAudience(customAudience));
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertThat(exception).hasCauseThat().hasMessageThat().isEqualTo(null);
    }

    @Test
    public void testJoinCustomAudience_maxTotalCustomAudiences_fail() {
        PhFlagsFixture.overrideFledgeCustomAudienceMaxCount(2);
        PhFlagsFixture.overrideFledgeCustomAudiencePerAppMaxCount(1000);
        PhFlagsFixture.overrideFledgeCustomAudienceMaxOwnerCount(1000);
        try {
            CustomAudience customAudience1 =
                    CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                            .setName("CA1")
                            .build();
            CustomAudience customAudience2 =
                    CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                            .setName("CA2")
                            .build();
            CustomAudience customAudience3 =
                    CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                            .setName("CA3")
                            .build();

            Exception exception =
                    assertThrows(
                            ExecutionException.class,
                            () -> {
                                joinCustomAudience(customAudience1);

                                // TODO(b/266725238): Remove/modify once the API rate limit has been
                                //  adjusted for FLEDGE
                                CommonFixture.doSleep(
                                        PhFlagsFixture.DEFAULT_API_RATE_LIMIT_SLEEP_MS);

                                joinCustomAudience(customAudience2);

                                // TODO(b/266725238): Remove/modify once the API rate limit has been
                                //  adjusted for FLEDGE
                                CommonFixture.doSleep(
                                        PhFlagsFixture.DEFAULT_API_RATE_LIMIT_SLEEP_MS);

                                joinCustomAudience(customAudience3);
                            });
            assertThat(exception).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
            assertThat(exception).hasCauseThat().hasMessageThat().isEqualTo(null);
        } finally {
            PhFlagsFixture.overrideFledgeCustomAudienceMaxCount(4000);
        }
    }

    @Test
    public void testJoinCustomAudience_maxCustomAudiencesPerApp_fail() {
        PhFlagsFixture.overrideFledgeCustomAudienceMaxCount(4000);
        PhFlagsFixture.overrideFledgeCustomAudiencePerAppMaxCount(2);
        PhFlagsFixture.overrideFledgeCustomAudienceMaxOwnerCount(1000);
        try {
            CustomAudience customAudience1 =
                    CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                            .setName("CA1")
                            .build();
            CustomAudience customAudience2 =
                    CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                            .setName("CA2")
                            .build();
            CustomAudience customAudience3 =
                    CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                            .setName("CA3")
                            .build();

            Exception exception =
                    assertThrows(
                            ExecutionException.class,
                            () -> {
                                joinCustomAudience(customAudience1);

                                // TODO(b/266725238): Remove/modify once the API rate limit has been
                                //  adjusted for FLEDGE
                                CommonFixture.doSleep(
                                        PhFlagsFixture.DEFAULT_API_RATE_LIMIT_SLEEP_MS);

                                joinCustomAudience(customAudience2);

                                // TODO(b/266725238): Remove/modify once the API rate limit has been
                                //  adjusted for FLEDGE
                                CommonFixture.doSleep(
                                        PhFlagsFixture.DEFAULT_API_RATE_LIMIT_SLEEP_MS);

                                joinCustomAudience(customAudience3);
                            });
            assertThat(exception).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
            assertThat(exception).hasCauseThat().hasMessageThat().isEqualTo(null);
        } finally {
            PhFlagsFixture.overrideFledgeCustomAudiencePerAppMaxCount(1000);
        }
    }

    @Test
    public void testLeaveCustomAudience_joinedCustomAudience_success()
            throws ExecutionException, InterruptedException, TimeoutException {
        joinCustomAudience(
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1).build());
        mClient.leaveCustomAudience(
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME)
                .get();
    }

    @Test
    public void testLeaveCustomAudience_notJoinedCustomAudience_doesNotFail()
            throws ExecutionException, InterruptedException {
        mClient.leaveCustomAudience(
                        CommonFixture.VALID_BUYER_1,
                        "not_exist_name")
                .get();
    }

    @Test
    public void testLeaveCustomAudience_withMissingEnrollment_fail() {
        Exception exception =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                mClient.leaveCustomAudience(
                                                CommonFixture.NOT_ENROLLED_BUYER,
                                                CustomAudienceFixture.VALID_NAME)
                                        .get());
        assertThat(exception).hasCauseThat().isInstanceOf(SecurityException.class);
        assertThat(exception)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo(SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE);
    }

    @Test
    public void testAddOverrideFailsWithDebugModeDisabled() {
        Assume.assumeFalse(mIsDebugMode);

        AddCustomAudienceOverrideRequest request =
                new AddCustomAudienceOverrideRequest.Builder()
                        .setBuyer(BUYER)
                        .setName(NAME)
                        .setBiddingLogicJs(BIDDING_LOGIC_JS)
                        .setTrustedBiddingSignals(TRUSTED_BIDDING_DATA)
                        .build();

        ListenableFuture<Void> result = mTestClient.overrideCustomAudienceRemoteInfo(request);

        Exception exception =
                assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
        assertThat(exception.getCause()).isInstanceOf(SecurityException.class);
    }

    @Test
    public void testRemoveOverrideFailsWithDebugModeDisabled() {
        Assume.assumeFalse(mIsDebugMode);

        RemoveCustomAudienceOverrideRequest request =
                new RemoveCustomAudienceOverrideRequest.Builder()
                        .setBuyer(BUYER)
                        .setName(NAME)
                        .build();

        ListenableFuture<Void> result = mTestClient.removeCustomAudienceRemoteInfoOverride(request);

        Exception exception =
                assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
        assertThat(exception.getCause()).isInstanceOf(SecurityException.class);
    }

    @Test
    public void testResetAllOverridesFailsWithDebugModeDisabled() {
        Assume.assumeFalse(mIsDebugMode);

        ListenableFuture<Void> result = mTestClient.resetAllCustomAudienceOverrides();

        Exception exception =
                assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
        assertThat(exception.getCause()).isInstanceOf(SecurityException.class);
    }

    private void joinCustomAudience(CustomAudience customAudience)
            throws ExecutionException, InterruptedException, TimeoutException {
        mClient.joinCustomAudience(customAudience).get(10, TimeUnit.SECONDS);
        mCustomAudiencesToCleanUp.add(customAudience);
    }

    private void leaveJoinedCustomAudiences()
            throws ExecutionException, InterruptedException, TimeoutException {
        try {
            for (CustomAudience customAudience : mCustomAudiencesToCleanUp) {
                // TODO(b/266725238): Remove/modify once the API rate limit has been adjusted
                //  for FLEDGE
                CommonFixture.doSleep(PhFlagsFixture.DEFAULT_API_RATE_LIMIT_SLEEP_MS);

                mClient.leaveCustomAudience(customAudience.getBuyer(), customAudience.getName())
                        .get(10, TimeUnit.SECONDS);
            }
        } finally {
            mCustomAudiencesToCleanUp.clear();
        }
    }

    private void createClientUsingGetMethod() {
        mClient =
                new AdvertisingCustomAudienceClient.Builder()
                        .setContext(sContext)
                        .setExecutor(MoreExecutors.directExecutor())
                        .setUseGetMethodToCreateManagerInstance(true)
                        .build();
    }
}
