/*
 * Copyright 2021 The Android Open Source Project
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

package android.service.timezone.cts;

import static android.app.time.cts.ParcelableTestSupport.assertRoundTripParcelable;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_NOT_APPLICABLE;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_OK;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_FAILED;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_OK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;

import android.service.timezone.TimeZoneProviderStatus;

import org.junit.Test;

/** SDK API tests. Non-SDK methods are tested elsewhere. */
public class TimeZoneProviderStatusTest {

    @Test
    public void testStatusValidation() {
        TimeZoneProviderStatus validStatus = validStatusBuilder().build();
        assertNotNull(validStatus);

        assertThrows(IllegalArgumentException.class,
                () -> validStatusBuilder()
                        .setLocationDetectionDependencyStatus(-1)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> validStatusBuilder()
                        .setConnectivityDependencyStatus(-1)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> validStatusBuilder()
                        .setTimeZoneResolutionOperationStatus(-1)
                        .build());
    }

    @Test
    public void testEqualsAndHashcode() {
        TimeZoneProviderStatus status1_1 = validStatusBuilder().build();
        assertEqualsAndHashcode(status1_1, status1_1);
        assertNotEquals(status1_1, null);

        {
            TimeZoneProviderStatus status1_2 = validStatusBuilder().build();
            assertEqualsAndHashcode(status1_1, status1_2);
            assertNotSame(status1_1, status1_2);
        }

        {
            TimeZoneProviderStatus status2 = validStatusBuilder()
                    .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT)
                    .build();
            assertNotEquals(status1_1, status2);
        }

        {
            TimeZoneProviderStatus status2 = validStatusBuilder()
                    .setConnectivityDependencyStatus(DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT)
                    .build();
            assertNotEquals(status1_1, status2);
        }

        {
            TimeZoneProviderStatus status2 = validStatusBuilder()
                    .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_FAILED)
                    .build();
            assertNotEquals(status1_1, status2);
        }
    }

    private static void assertEqualsAndHashcode(Object one, Object two) {
        assertEquals(one, two);
        assertEquals(two, one);
        assertEquals(one.hashCode(), two.hashCode());
    }

    @Test
    public void testParcelable() {
        TimeZoneProviderStatus status = validStatusBuilder().build();
        assertRoundTripParcelable(status);
    }

    @Test
    public void testAccessors() {
        TimeZoneProviderStatus status = new TimeZoneProviderStatus.Builder()
                .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS)
                .setConnectivityDependencyStatus(DEPENDENCY_STATUS_NOT_APPLICABLE)
                .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK)
                .build();

        assertEquals(DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS,
                status.getLocationDetectionDependencyStatus());
        assertEquals(DEPENDENCY_STATUS_NOT_APPLICABLE,
                status.getConnectivityDependencyStatus());
        assertEquals(OPERATION_STATUS_OK,
                status.getTimeZoneResolutionOperationStatus());
    }

    private static TimeZoneProviderStatus.Builder validStatusBuilder() {
        return new TimeZoneProviderStatus.Builder()
                .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_OK)
                .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK);
    }
}
