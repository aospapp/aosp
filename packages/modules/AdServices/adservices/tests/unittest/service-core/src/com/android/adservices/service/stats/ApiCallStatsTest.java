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

package com.android.adservices.service.stats;

import static com.android.adservices.ResultCode.RESULT_OK;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_CLASS__TARGETING;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS;

import org.junit.Assert;
import org.junit.Test;

/**
 * Unit test for {@link ApiCallStats}.
 */
public class ApiCallStatsTest {

    private final String mPackageName = "com.android.test";
    private final String mSdkName = "com.android.container";
    private static final int LATENCY = 100;
    @Test
    public void testBuilderCreateSuccess() {
        ApiCallStats stats = new ApiCallStats.Builder()
                .setCode(AD_SERVICES_API_CALLED)
                .setApiClass(AD_SERVICES_API_CALLED__API_CLASS__TARGETING)
                .setApiName(AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS)
                .setAppPackageName(mPackageName)
                .setSdkPackageName(mSdkName)
                .setLatencyMillisecond(LATENCY)
                .setResultCode(RESULT_OK)
                .build();
        Assert.assertEquals(AD_SERVICES_API_CALLED, stats.getCode());
        Assert.assertEquals(AD_SERVICES_API_CALLED__API_CLASS__TARGETING, stats.getApiClass());
        Assert.assertEquals(AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS, stats.getApiName());
        Assert.assertEquals(mPackageName, stats.getAppPackageName());
        Assert.assertEquals(mSdkName, stats.getSdkPackageName());
        Assert.assertEquals(LATENCY, stats.getLatencyMillisecond());
        Assert.assertEquals(RESULT_OK, stats.getResultCode());
    }

    @Test
    public void testNullSdkPackageName_throwsNPE() {
        Assert.assertThrows("expect to throw null exception",
                NullPointerException.class,
                () -> {
                    new ApiCallStats.Builder()
                            .setSdkPackageName(null)
                            .build();
                }
        );
    }

    @Test
    public void testNullAppPackageName_throwsNPE() {
        Assert.assertThrows("expect to throw null exception",
                NullPointerException.class,
                () -> {
                    new ApiCallStats.Builder()
                            .setAppPackageName(null)
                            .build();
                }
        );
    }

    @Test
    public void testIncompleteInputBuilder_throwsIAE() {
        Assert.assertThrows("expect to throw Illegal Argument exception",
                IllegalArgumentException.class,
                () -> {
                    new ApiCallStats.Builder()
                            .build();
                }
        );
    }
}
