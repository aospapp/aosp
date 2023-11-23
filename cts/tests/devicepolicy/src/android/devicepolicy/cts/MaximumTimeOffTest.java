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

package android.devicepolicy.cts;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import static com.android.queryable.queries.ActivityQuery.activity;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.content.Intent;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.NotificationsTest;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.CannotSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.PolicyAppliesTest;
import com.android.bedstead.harrier.policies.MaximumTimeOff;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.notifications.NotificationListener;
import com.android.bedstead.nene.notifications.NotificationListenerQuerySubject;
import com.android.bedstead.nene.utils.Poll;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppActivityReference;
import com.android.bedstead.testapp.TestAppInstance;
import com.android.eventlib.EventLogs;

import com.google.common.truth.Truth;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.runner.RunWith;

import java.time.Duration;

@RunWith(BedsteadJUnit4.class)
public final class MaximumTimeOffTest {

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final TestApp sTestApp = sDeviceState.testApps().query()
            .whereActivities().contains(
                    activity().where().exported().isTrue()
            ).get();

    @PolicyAppliesTest(policy = MaximumTimeOff.class)
    public void setManagedProfileMaximumTimeOff_timesOut_personalAppsAreSuspended()
            throws Exception {
        long originalMaximumTimeOff =
                sDeviceState.dpc().devicePolicyManager()
                        .getManagedProfileMaximumTimeOff(
                                sDeviceState.dpc().componentName());
        try (TestAppInstance personalInstance = sTestApp.install()) {
            TestAppActivityReference activity = personalInstance.activities().any();
            sDeviceState.dpc().devicePolicyManager().setManagedProfileMaximumTimeOff(
                    sDeviceState.dpc().componentName(), /* timeoutMs= */ 1);

            sDeviceState.workProfile().setQuietMode(true);

            // TODO(264248238): We should create a more direct way of figuring out the launch
            //  was refused
            Poll.forValue("Successfully launched", () -> {
                // Ensure that we only see events from the current iteration
                EventLogs.resetLogs();
                startActivityWithoutBlocking(activity);

                try {
                    activity.events().activityStarted().waitForEvent(Duration.ofSeconds(2));
                    return true;
                } catch (AssertionError e) {
                    // No event
                    return false;
                }
            }).toBeEqualTo(false)
                    .errorOnFail()
                    .await();
        } finally {
            sDeviceState.workProfile().setQuietMode(false);
            sDeviceState.dpc().devicePolicyManager().setManagedProfileMaximumTimeOff(
                    sDeviceState.dpc().componentName(), /* timeoutMs= */ originalMaximumTimeOff);
        }
    }

    private void startActivityWithoutBlocking(TestAppActivityReference activity) {
        Intent intent = new Intent();
        intent.setComponent(activity.component().componentName());
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);

        TestApis.context().instrumentedContext().startActivity(intent);
    }

    @PolicyAppliesTest(policy = MaximumTimeOff.class)
    @NotificationsTest
    public void setManagedProfileMaximumTimeOff_timesOut_notificationIsShown() {
        long originalMaximumTimeOff =
                sDeviceState.dpc().devicePolicyManager()
                        .getManagedProfileMaximumTimeOff(
                                sDeviceState.dpc().componentName());
        try (NotificationListener notifications = TestApis.notifications().createListener()) {
            sDeviceState.dpc().devicePolicyManager().setManagedProfileMaximumTimeOff(
                    sDeviceState.dpc().componentName(), /* timeoutMs= */ 1);

            sDeviceState.workProfile().setQuietMode(true);

            NotificationListenerQuerySubject.assertThat(
                    notifications.query()
                            .wherePackageName().isEqualTo("android")
                            .whereNotification().channelId().isEqualTo("DEVICE_ADMIN_ALERTS")
            ).wasPosted();
        } finally {
            sDeviceState.workProfile().setQuietMode(false);
            sDeviceState.dpc().devicePolicyManager().setManagedProfileMaximumTimeOff(
                    sDeviceState.dpc().componentName(), /* timeoutMs= */ originalMaximumTimeOff);
        }
    }

    @CannotSetPolicyTest(policy = MaximumTimeOff.class, includeNonDeviceAdminStates = false)
    public void setManagedProfileMaximumTimeOff_notAllowed_throwsException() {
        assertThrows(SecurityException.class, () -> {
            sDeviceState.dpc().devicePolicyManager().setManagedProfileMaximumTimeOff(
                    sDeviceState.dpc().componentName(), /* timeoutMs= */ 1);
        });
    }

    @CanSetPolicyTest(policy = MaximumTimeOff.class)
    public void getManagedProfileMaximumTimeOff_returnsSetValue() {
        long originalMaximumTimeOff =
                sDeviceState.dpc().devicePolicyManager()
                        .getManagedProfileMaximumTimeOff(
                                sDeviceState.dpc().componentName());
        try {
            sDeviceState.dpc().devicePolicyManager().setManagedProfileMaximumTimeOff(
                    sDeviceState.dpc().componentName(), /* timeoutMs= */ 12345);

            assertThat(sDeviceState.dpc().devicePolicyManager().getManagedProfileMaximumTimeOff(
                    sDeviceState.dpc().componentName())).isEqualTo(12345);
        } finally {
            sDeviceState.dpc().devicePolicyManager().setManagedProfileMaximumTimeOff(
                    sDeviceState.dpc().componentName(), /* timeoutMs= */ originalMaximumTimeOff);
        }
    }

    // TODO(264249662): Add missing coverage
}
