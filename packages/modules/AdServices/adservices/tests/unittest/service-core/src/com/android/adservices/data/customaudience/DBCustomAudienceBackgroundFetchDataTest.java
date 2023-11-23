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

package com.android.adservices.data.customaudience;

import static com.android.adservices.customaudience.DBCustomAudienceBackgroundFetchDataFixture.NUM_TIMEOUT_FAILURES_POSITIVE;
import static com.android.adservices.customaudience.DBCustomAudienceBackgroundFetchDataFixture.NUM_VALIDATION_FAILURES_POSITIVE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import android.adservices.common.CommonFixture;
import android.adservices.customaudience.CustomAudienceFixture;

import com.android.adservices.customaudience.DBCustomAudienceBackgroundFetchDataFixture;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.PhFlagsFixture;
import com.android.adservices.service.customaudience.BackgroundFetchRunner;
import com.android.adservices.service.customaudience.CustomAudienceUpdatableData;
import com.android.adservices.service.customaudience.CustomAudienceUpdatableDataFixture;
import com.android.modules.utils.testing.TestableDeviceConfig;

import org.json.JSONException;
import org.junit.Rule;
import org.junit.Test;

import java.time.Instant;

public class DBCustomAudienceBackgroundFetchDataTest {
    // This rule is used for configuring P/H flags
    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule mDeviceConfigRule =
            new TestableDeviceConfig.TestableDeviceConfigRule();

    @Test
    public void testBuildFetchDataSuccess() {
        DBCustomAudienceBackgroundFetchData fetchData =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI)
                        .setNumValidationFailures(NUM_VALIDATION_FAILURES_POSITIVE)
                        .setNumTimeoutFailures(NUM_TIMEOUT_FAILURES_POSITIVE)
                        .build();

        assertEquals(CustomAudienceFixture.VALID_OWNER, fetchData.getOwner());
        assertEquals(CommonFixture.VALID_BUYER_1, fetchData.getBuyer());
        assertEquals(CustomAudienceFixture.VALID_NAME, fetchData.getName());
        assertEquals(
                CustomAudienceFixture.getValidDailyUpdateUriByBuyer(CommonFixture.VALID_BUYER_1),
                fetchData.getDailyUpdateUri());
        assertEquals(CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI, fetchData.getEligibleUpdateTime());
        assertEquals(NUM_VALIDATION_FAILURES_POSITIVE, fetchData.getNumValidationFailures());
        assertEquals(NUM_TIMEOUT_FAILURES_POSITIVE, fetchData.getNumTimeoutFailures());
    }

    @Test
    public void testBuildFetchDataDefaultFailuresSuccess() {
        DBCustomAudienceBackgroundFetchData fetchData =
                DBCustomAudienceBackgroundFetchData.builder()
                        .setOwner(CustomAudienceFixture.VALID_OWNER)
                        .setBuyer(CommonFixture.VALID_BUYER_1)
                        .setName(CustomAudienceFixture.VALID_NAME)
                        .setDailyUpdateUri(
                                CustomAudienceFixture.getValidDailyUpdateUriByBuyer(
                                        CommonFixture.VALID_BUYER_1))
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI)
                        .build();

        assertEquals(CustomAudienceFixture.VALID_OWNER, fetchData.getOwner());
        assertEquals(CommonFixture.VALID_BUYER_1, fetchData.getBuyer());
        assertEquals(CustomAudienceFixture.VALID_NAME, fetchData.getName());
        assertEquals(
                CustomAudienceFixture.getValidDailyUpdateUriByBuyer(CommonFixture.VALID_BUYER_1),
                fetchData.getDailyUpdateUri());
        assertEquals(CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI, fetchData.getEligibleUpdateTime());
        assertEquals(0, fetchData.getNumValidationFailures());
        assertEquals(0, fetchData.getNumTimeoutFailures());
    }

    @Test
    public void testBuildFetchDataNegativeFailuresFail() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                        CommonFixture.VALID_BUYER_1)
                                .setNumValidationFailures(-10)
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                        CommonFixture.VALID_BUYER_1)
                                .setNumTimeoutFailures(-10)
                                .build());
    }

    @Test
    public void testCreateFetchDataSuccess() {
        DBCustomAudienceBackgroundFetchData fetchData =
                DBCustomAudienceBackgroundFetchData.create(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME,
                        CustomAudienceFixture.getValidDailyUpdateUriByBuyer(
                                CommonFixture.VALID_BUYER_1),
                        CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI,
                        NUM_VALIDATION_FAILURES_POSITIVE,
                        NUM_TIMEOUT_FAILURES_POSITIVE);

        assertEquals(CustomAudienceFixture.VALID_OWNER, fetchData.getOwner());
        assertEquals(CommonFixture.VALID_BUYER_1, fetchData.getBuyer());
        assertEquals(CustomAudienceFixture.VALID_NAME, fetchData.getName());
        assertEquals(
                CustomAudienceFixture.getValidDailyUpdateUriByBuyer(CommonFixture.VALID_BUYER_1),
                fetchData.getDailyUpdateUri());
        assertEquals(CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI, fetchData.getEligibleUpdateTime());
        assertEquals(NUM_VALIDATION_FAILURES_POSITIVE, fetchData.getNumValidationFailures());
        assertEquals(NUM_TIMEOUT_FAILURES_POSITIVE, fetchData.getNumTimeoutFailures());
    }

    @Test
    public void testComputeNextEligibleUpdateTimeWithInputFlags() {
        Flags testFlags = FlagsFactory.getFlagsForTest();
        Instant expectedEligibleUpdateTime =
                CommonFixture.FIXED_NOW.plusSeconds(
                        testFlags.getFledgeBackgroundFetchEligibleUpdateBaseIntervalS());

        Instant actualEligibleUpdateTime =
                DBCustomAudienceBackgroundFetchData
                        .computeNextEligibleUpdateTimeAfterSuccessfulUpdate(
                                CommonFixture.FIXED_NOW, testFlags);

        assertEquals(expectedEligibleUpdateTime, actualEligibleUpdateTime);
    }

    @Test
    public void testComputeNextEligibleUpdateTimeWithPhFlags() {
        long configuredBaseIntervalS = 100L;
        PhFlagsFixture.configureFledgeBackgroundFetchEligibleUpdateBaseIntervalS(
                configuredBaseIntervalS);
        Instant expectedEligibleUpdateTime =
                CommonFixture.FIXED_NOW.plusSeconds(configuredBaseIntervalS);

        Instant actualEligibleUpdateTime =
                DBCustomAudienceBackgroundFetchData
                        .computeNextEligibleUpdateTimeAfterSuccessfulUpdate(
                                CommonFixture.FIXED_NOW);

        assertEquals(expectedEligibleUpdateTime, actualEligibleUpdateTime);
    }

    @Test
    public void testCopyWithFullSuccessfulUpdatableDataResetsFailureCounts() throws JSONException {
        DBCustomAudienceBackgroundFetchData originalFetchData =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW)
                        .setNumValidationFailures(1)
                        .setNumTimeoutFailures(2)
                        .build();

        Instant attemptedUpdateTime = CommonFixture.FIXED_NOW.plusSeconds(10);
        Instant expectedEligibleUpdateTime =
                DBCustomAudienceBackgroundFetchData
                        .computeNextEligibleUpdateTimeAfterSuccessfulUpdate(
                                attemptedUpdateTime, FlagsFactory.getFlagsForTest());

        CustomAudienceUpdatableData updatableData =
                CustomAudienceUpdatableDataFixture.getValidBuilderFullSuccessfulResponse()
                        .setAttemptedUpdateTime(attemptedUpdateTime)
                        .build();

        DBCustomAudienceBackgroundFetchData updatedFetchData =
                originalFetchData.copyWithUpdatableData(updatableData);

        assertNotEquals(
                "Background fetch data was not updated", updatedFetchData, originalFetchData);
        assertEquals(expectedEligibleUpdateTime, updatedFetchData.getEligibleUpdateTime());
        assertEquals(0, updatedFetchData.getNumValidationFailures());
        assertEquals(0, updatedFetchData.getNumTimeoutFailures());
    }

    @Test
    public void testCopyWithFailedUpdatableDataUpdatesValidationFailureCount() {
        DBCustomAudienceBackgroundFetchData originalFetchData =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW)
                        .setNumValidationFailures(NUM_VALIDATION_FAILURES_POSITIVE)
                        .setNumTimeoutFailures(NUM_TIMEOUT_FAILURES_POSITIVE)
                        .build();

        Instant attemptedUpdateTime = CommonFixture.FIXED_NOW.plusSeconds(10);
        Instant expectedEligibleUpdateTime = originalFetchData.getEligibleUpdateTime();

        CustomAudienceUpdatableData updatableData =
                CustomAudienceUpdatableDataFixture.getValidBuilderEmptyFailedResponse()
                        .setAttemptedUpdateTime(attemptedUpdateTime)
                        .setInitialUpdateResult(BackgroundFetchRunner.UpdateResultType.SUCCESS)
                        .build();

        DBCustomAudienceBackgroundFetchData updatedFetchData =
                originalFetchData.copyWithUpdatableData(updatableData);

        assertNotEquals(
                "Background fetch data was not updated", updatedFetchData, originalFetchData);
        assertEquals(expectedEligibleUpdateTime, updatedFetchData.getEligibleUpdateTime());
        assertEquals(
                NUM_VALIDATION_FAILURES_POSITIVE + 1, updatedFetchData.getNumValidationFailures());
        assertEquals(NUM_TIMEOUT_FAILURES_POSITIVE, updatedFetchData.getNumTimeoutFailures());
    }

    @Test
    public void testCopyWithResponseValidationFailureUpdatesValidationFailureCount() {
        DBCustomAudienceBackgroundFetchData originalFetchData =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW)
                        .setNumValidationFailures(NUM_VALIDATION_FAILURES_POSITIVE)
                        .setNumTimeoutFailures(NUM_TIMEOUT_FAILURES_POSITIVE)
                        .build();

        Instant attemptedUpdateTime = CommonFixture.FIXED_NOW.plusSeconds(10);
        Instant expectedEligibleUpdateTime = originalFetchData.getEligibleUpdateTime();

        CustomAudienceUpdatableData updatableData =
                CustomAudienceUpdatableDataFixture.getValidBuilderEmptyFailedResponse()
                        .setAttemptedUpdateTime(attemptedUpdateTime)
                        .setInitialUpdateResult(
                                BackgroundFetchRunner.UpdateResultType.RESPONSE_VALIDATION_FAILURE)
                        .build();

        DBCustomAudienceBackgroundFetchData updatedFetchData =
                originalFetchData.copyWithUpdatableData(updatableData);

        assertNotEquals(
                "Background fetch data was not updated", updatedFetchData, originalFetchData);
        assertEquals(expectedEligibleUpdateTime, updatedFetchData.getEligibleUpdateTime());
        assertEquals(
                NUM_VALIDATION_FAILURES_POSITIVE + 1, updatedFetchData.getNumValidationFailures());
        assertEquals(NUM_TIMEOUT_FAILURES_POSITIVE, updatedFetchData.getNumTimeoutFailures());
    }

    @Test
    public void testCopyWithNetworkConnectTimeoutFailureUpdatesTimeoutFailureCount() {
        DBCustomAudienceBackgroundFetchData originalFetchData =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW)
                        .setNumValidationFailures(NUM_VALIDATION_FAILURES_POSITIVE)
                        .setNumTimeoutFailures(NUM_TIMEOUT_FAILURES_POSITIVE)
                        .build();

        Instant attemptedUpdateTime = CommonFixture.FIXED_NOW.plusSeconds(10);
        Instant expectedEligibleUpdateTime = originalFetchData.getEligibleUpdateTime();

        CustomAudienceUpdatableData updatableData =
                CustomAudienceUpdatableDataFixture.getValidBuilderEmptyFailedResponse()
                        .setAttemptedUpdateTime(attemptedUpdateTime)
                        .setInitialUpdateResult(
                                BackgroundFetchRunner.UpdateResultType.NETWORK_FAILURE)
                        .build();

        DBCustomAudienceBackgroundFetchData updatedFetchData =
                originalFetchData.copyWithUpdatableData(updatableData);

        assertNotEquals(
                "Background fetch data was not updated", updatedFetchData, originalFetchData);
        assertEquals(expectedEligibleUpdateTime, updatedFetchData.getEligibleUpdateTime());
        assertEquals(NUM_VALIDATION_FAILURES_POSITIVE, updatedFetchData.getNumValidationFailures());
        assertEquals(NUM_TIMEOUT_FAILURES_POSITIVE + 1, updatedFetchData.getNumTimeoutFailures());
    }

    @Test
    public void testCopyWithNetworkReadTimeoutFailureUpdatesTimeoutFailureCount() {
        DBCustomAudienceBackgroundFetchData originalFetchData =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW)
                        .setNumValidationFailures(NUM_VALIDATION_FAILURES_POSITIVE)
                        .setNumTimeoutFailures(NUM_TIMEOUT_FAILURES_POSITIVE)
                        .build();

        Instant attemptedUpdateTime = CommonFixture.FIXED_NOW.plusSeconds(10);
        Instant expectedEligibleUpdateTime = originalFetchData.getEligibleUpdateTime();

        CustomAudienceUpdatableData updatableData =
                CustomAudienceUpdatableDataFixture.getValidBuilderEmptyFailedResponse()
                        .setAttemptedUpdateTime(attemptedUpdateTime)
                        .setInitialUpdateResult(
                                BackgroundFetchRunner.UpdateResultType.NETWORK_READ_TIMEOUT_FAILURE)
                        .build();

        DBCustomAudienceBackgroundFetchData updatedFetchData =
                originalFetchData.copyWithUpdatableData(updatableData);

        assertNotEquals(
                "Background fetch data was not updated", updatedFetchData, originalFetchData);
        assertEquals(expectedEligibleUpdateTime, updatedFetchData.getEligibleUpdateTime());
        assertEquals(NUM_VALIDATION_FAILURES_POSITIVE, updatedFetchData.getNumValidationFailures());
        assertEquals(NUM_TIMEOUT_FAILURES_POSITIVE + 1, updatedFetchData.getNumTimeoutFailures());
    }

    @Test
    public void testCopyWithKAnonFailureDesNotUpdate() {
        DBCustomAudienceBackgroundFetchData originalFetchData =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW)
                        .setNumValidationFailures(NUM_VALIDATION_FAILURES_POSITIVE)
                        .setNumTimeoutFailures(NUM_TIMEOUT_FAILURES_POSITIVE)
                        .build();

        Instant attemptedUpdateTime = CommonFixture.FIXED_NOW.plusSeconds(10);

        CustomAudienceUpdatableData updatableData =
                CustomAudienceUpdatableDataFixture.getValidBuilderEmptyFailedResponse()
                        .setAttemptedUpdateTime(attemptedUpdateTime)
                        .setInitialUpdateResult(
                                BackgroundFetchRunner.UpdateResultType.K_ANON_FAILURE)
                        .build();

        DBCustomAudienceBackgroundFetchData updatedFetchData =
                originalFetchData.copyWithUpdatableData(updatableData);

        assertEquals(
                "Background fetch data was updated from a benign failure",
                updatedFetchData,
                originalFetchData);
    }

    @Test
    public void testCopyWithUnknownFailureDesNotUpdate() {
        DBCustomAudienceBackgroundFetchData originalFetchData =
                DBCustomAudienceBackgroundFetchDataFixture.getValidBuilderByBuyer(
                                CommonFixture.VALID_BUYER_1)
                        .setEligibleUpdateTime(CommonFixture.FIXED_NOW)
                        .setNumValidationFailures(NUM_VALIDATION_FAILURES_POSITIVE)
                        .setNumTimeoutFailures(NUM_TIMEOUT_FAILURES_POSITIVE)
                        .build();

        Instant attemptedUpdateTime = CommonFixture.FIXED_NOW.plusSeconds(10);

        CustomAudienceUpdatableData updatableData =
                CustomAudienceUpdatableDataFixture.getValidBuilderEmptyFailedResponse()
                        .setAttemptedUpdateTime(attemptedUpdateTime)
                        .setInitialUpdateResult(BackgroundFetchRunner.UpdateResultType.UNKNOWN)
                        .build();

        DBCustomAudienceBackgroundFetchData updatedFetchData =
                originalFetchData.copyWithUpdatableData(updatableData);

        assertEquals(
                "Background fetch data was updated from a benign failure",
                updatedFetchData,
                originalFetchData);
    }
}
