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
package com.android.adservices.tests.appsetid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.adservices.appsetid.AppSetId;
import android.adservices.appsetid.AppSetIdManager;
import android.content.Context;
import android.os.LimitExceededException;
import android.os.OutcomeReceiver;
import android.os.SystemProperties;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.compatibility.common.util.ConnectivityUtils;
import com.android.compatibility.common.util.ShellUtils;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class AppSetIdManagerTest {
    private static final Executor CALLBACK_EXECUTOR = Executors.newCachedThreadPool();
    private static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final int DEFAULT_APPSETID_REQUEST_PERMITS_PER_SECOND = 5;

    private static String sPreviousAppAllowList;

    @BeforeClass
    public static void setupClass() {
        if (!SdkLevel.isAtLeastT()) {
            sPreviousAppAllowList =
                    CompatAdServicesTestUtils.getAndOverridePpapiAppAllowList(
                            sContext.getPackageName());
        }
    }

    @Before
    public void setup() throws Exception {
        overrideAppSetIdKillSwitch(true);
        // Cool-off rate limiter in case it was initialized by another test
        TimeUnit.SECONDS.sleep(1);
    }

    @After
    public void tearDown() {
        overrideAppSetIdKillSwitch(false);
    }

    @AfterClass
    public static void tearDownAfterClass() {
        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.setPpapiAppAllowList(sPreviousAppAllowList);
        }
    }

    // Override appsetid related kill switch to ignore the effect of actual PH values.
    // If shouldOverride = true, override appsetid related kill switch to OFF to allow adservices
    // If shouldOverride = false, override appsetid related kill switch to meaningless value so that
    // PhFlags will use the default value.
    private void overrideAppSetIdKillSwitch(boolean shouldOverride) {
        String overrideString = shouldOverride ? "false" : "null";
        ShellUtils.runShellCommand(
                "setprop debug.adservices.appsetid_kill_switch " + overrideString);
    }

    @Test
    @FlakyTest(bugId = 271656209)
    public void testAppSetIdManager() throws Exception {
        Assume.assumeTrue(ConnectivityUtils.isNetworkConnected(sContext));

        AppSetIdManager appSetIdManager = AppSetIdManager.get(sContext);
        CompletableFuture<AppSetId> future = new CompletableFuture<>();
        OutcomeReceiver<AppSetId, Exception> callback =
                new OutcomeReceiver<AppSetId, Exception>() {
                    @Override
                    public void onResult(AppSetId result) {
                        future.complete(result);
                    }

                    @Override
                    public void onError(Exception error) {
                        Assert.fail();
                    }
                };
        appSetIdManager.getAppSetId(CALLBACK_EXECUTOR, callback);
        AppSetId resultAppSetId = future.get();
        Assert.assertNotNull(resultAppSetId.getId());
        Assert.assertNotNull(resultAppSetId.getScope());
    }

    @Test
    @FlakyTest(bugId = 271656209)
    public void testAppSetIdManager_verifyRateLimitReached() throws Exception {
        Assume.assumeTrue(ConnectivityUtils.isNetworkConnected(sContext));

        final AppSetIdManager appSetIdManager = AppSetIdManager.get(sContext);

        // Rate limit hasn't reached yet
        final long nowInMillis = System.currentTimeMillis();
        final int requestPerSecond = getAppSetIdRequestPerSecond();
        for (int i = 0; i < requestPerSecond; i++) {
            assertFalse(getAppSetIdAndVerifyRateLimitReached(appSetIdManager));
        }

        // Due to bursting, we could reach the limit at the exact limit or limit + 1. Therefore,
        // triggering one more call without checking the outcome.
        getAppSetIdAndVerifyRateLimitReached(appSetIdManager);

        // Verify limit reached
        // If the test takes less than 1 second / permits per second, this test is reliable due to
        // the rate limiter limits queries per second. If duration is longer than a second, skip it.
        final boolean reachedLimit = getAppSetIdAndVerifyRateLimitReached(appSetIdManager);
        final boolean executedInLessThanOneSec =
                (System.currentTimeMillis() - nowInMillis) < (1_000 / requestPerSecond);
        if (executedInLessThanOneSec) {
            assertTrue(reachedLimit);
        }
    }

    private boolean getAppSetIdAndVerifyRateLimitReached(AppSetIdManager manager)
            throws InterruptedException {
        final AtomicBoolean reachedLimit = new AtomicBoolean(false);
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        manager.getAppSetId(
                CALLBACK_EXECUTOR,
                createCallbackWithCountdownOnLimitExceeded(countDownLatch, reachedLimit));

        countDownLatch.await();
        return reachedLimit.get();
    }

    private OutcomeReceiver<AppSetId, Exception> createCallbackWithCountdownOnLimitExceeded(
            CountDownLatch countDownLatch, AtomicBoolean reachedLimit) {
        return new OutcomeReceiver<AppSetId, Exception>() {
            @Override
            public void onResult(@NonNull AppSetId result) {
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

    private int getAppSetIdRequestPerSecond() {
        try {
            String permitString =
                    SystemProperties.get("debug.adservices.appsetid_request_permits_per_second");
            if (!TextUtils.isEmpty(permitString) && !"null".equalsIgnoreCase(permitString)) {
                return Integer.parseInt(permitString);
            }

            permitString =
                    ShellUtils.runShellCommand(
                            "device_config get adservices appsetid_request_permits_per_second");
            if (!TextUtils.isEmpty(permitString) && !"null".equalsIgnoreCase(permitString)) {
                return Integer.parseInt(permitString);
            }
            return DEFAULT_APPSETID_REQUEST_PERMITS_PER_SECOND;
        } catch (Exception e) {
            return DEFAULT_APPSETID_REQUEST_PERMITS_PER_SECOND;
        }
    }
}
