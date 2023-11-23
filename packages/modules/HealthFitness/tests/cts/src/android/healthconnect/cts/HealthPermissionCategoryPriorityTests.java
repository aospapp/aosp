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

package android.healthconnect.cts;

import static android.health.connect.HealthDataCategory.ACTIVITY;
import static android.health.connect.HealthDataCategory.BODY_MEASUREMENTS;
import static android.health.connect.HealthDataCategory.CYCLE_TRACKING;
import static android.health.connect.HealthDataCategory.NUTRITION;
import static android.health.connect.HealthDataCategory.SLEEP;
import static android.health.connect.HealthDataCategory.VITALS;
import static android.healthconnect.cts.TestUtils.MANAGE_HEALTH_DATA;

import static com.google.common.truth.Truth.assertThat;

import android.app.UiAutomation;
import android.content.Context;
import android.health.connect.FetchDataOriginsPriorityOrderResponse;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.UpdateDataOriginPriorityOrderRequest;
import android.health.connect.datatypes.DataOrigin;
import android.os.OutcomeReceiver;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class HealthPermissionCategoryPriorityTests {
    private static final Set<Integer> sAllDataCategories =
            Set.of(ACTIVITY, BODY_MEASUREMENTS, CYCLE_TRACKING, NUTRITION, SLEEP, VITALS);
    private static final String TAG = "PermissionCategoryPriorityTests";
    private static final UiAutomation sUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();

    @Before
    public void setUp() {
        TestUtils.deleteAllStagedRemoteData();
    }

    @After
    public void tearDown() {
        TestUtils.deleteAllStagedRemoteData();
    }

    @Test
    public void testGetPriority() throws InterruptedException {
        sUiAutomation.adoptShellPermissionIdentity(MANAGE_HEALTH_DATA);

        try {
            for (Integer permissionCategory : sAllDataCategories) {
                assertThat(getPriority(permissionCategory)).isNotNull();
            }
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Test
    public void testGetPriority_no_perm() throws InterruptedException {
        for (Integer permissionCategory : sAllDataCategories) {
            try {
                getPriority(permissionCategory);
                Assert.fail("Get Priority must not be allowed without right HC permission");
            } catch (HealthConnectException healthConnectException) {
                assertThat(healthConnectException.getErrorCode())
                        .isEqualTo(HealthConnectException.ERROR_SECURITY);
            }
        }
    }

    @Test
    public void testUpdatePriority_incorrectValues() throws InterruptedException {
        sUiAutomation.adoptShellPermissionIdentity(MANAGE_HEALTH_DATA);

        try {
            for (Integer permissionCategory : sAllDataCategories) {
                FetchDataOriginsPriorityOrderResponse currentPriority =
                        getPriority(permissionCategory);
                assertThat(currentPriority).isNotNull();
                updatePriority(permissionCategory, Arrays.asList("a", "b", "c"));
                FetchDataOriginsPriorityOrderResponse newPriority = getPriority(permissionCategory);
                assertThat(currentPriority.getDataOriginsPriorityOrder().size())
                        .isEqualTo(newPriority.getDataOriginsPriorityOrder().size());

                List<String> currentPriorityString =
                        currentPriority.getDataOriginsPriorityOrder().stream()
                                .map(DataOrigin::getPackageName)
                                .toList();
                List<String> newPriorityString =
                        newPriority.getDataOriginsPriorityOrder().stream()
                                .map(DataOrigin::getPackageName)
                                .toList();
                assertThat(currentPriorityString.equals(newPriorityString)).isTrue();
            }
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Test
    public void testUpdatePriority_no_perm() throws InterruptedException {
        for (Integer permissionCategory : sAllDataCategories) {
            try {
                updatePriority(permissionCategory, Arrays.asList("a", "b", "c"));
                Assert.fail("Update priority must not be allowed without right HC permission");
            } catch (HealthConnectException healthConnectException) {
                assertThat(healthConnectException.getErrorCode())
                        .isEqualTo(HealthConnectException.ERROR_SECURITY);
            }
        }
    }

    // TODO(b/257638480): Add more tests with some actual priorities, after grant permission tests
    // are in place

    private FetchDataOriginsPriorityOrderResponse getPriority(int permissionCategory)
            throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<FetchDataOriginsPriorityOrderResponse> response = new AtomicReference<>();
        AtomicReference<HealthConnectException> healthConnectExceptionAtomicReference =
                new AtomicReference<>();
        service.fetchDataOriginsPriorityOrder(
                permissionCategory,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(FetchDataOriginsPriorityOrderResponse result) {
                        response.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException exception) {
                        healthConnectExceptionAtomicReference.set(exception);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (healthConnectExceptionAtomicReference.get() != null) {
            throw healthConnectExceptionAtomicReference.get();
        }

        return response.get();
    }

    private void updatePriority(int permissionCategory, List<String> packageNames)
            throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();

        List<DataOrigin> dataOrigins =
                packageNames.stream()
                        .map(
                                (packageName) ->
                                        new DataOrigin.Builder()
                                                .setPackageName(packageName)
                                                .build())
                        .collect(Collectors.toList());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HealthConnectException> healthConnectExceptionAtomicReference =
                new AtomicReference<>();
        UpdateDataOriginPriorityOrderRequest updateDataOriginPriorityOrderRequest =
                new UpdateDataOriginPriorityOrderRequest(dataOrigins, permissionCategory);
        service.updateDataOriginPriorityOrder(
                updateDataOriginPriorityOrderRequest,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Void result) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException exception) {
                        healthConnectExceptionAtomicReference.set(exception);
                        latch.countDown();
                    }
                });
        assertThat(updateDataOriginPriorityOrderRequest.getDataCategory())
                .isEqualTo(permissionCategory);
        assertThat(updateDataOriginPriorityOrderRequest.getDataOriginInOrder()).isNotNull();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (healthConnectExceptionAtomicReference.get() != null) {
            throw healthConnectExceptionAtomicReference.get();
        }
    }

    // TODO(b/261618513): Test actual priority order by using other test apps
}
