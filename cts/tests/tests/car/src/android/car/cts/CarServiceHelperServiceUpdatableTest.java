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

package android.car.cts;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.hamcrest.CoreMatchers.containsStringIgnoringCase;
import static org.junit.Assume.assumeThat;
import static org.testng.Assert.fail;

import android.app.UiAutomation;
import android.car.Car;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleEvent;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.os.NewUserRequest;
import android.os.NewUserResponse;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import androidx.test.filters.FlakyTest;
import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public final class CarServiceHelperServiceUpdatableTest extends CarApiTestBase {

    private static final String TAG = CarServiceHelperServiceUpdatableTest.class.getSimpleName();
    private static final int TIMEOUT_MS = 60_000;
    private static final int WAIT_TIME_MS = 1_000;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        SystemUtil.runShellCommand("logcat -b all -c");
    }

    @Test
    public void testCarServiceHelperServiceDump() throws Exception {
        assumeThat("System_server_dumper not implemented.",
                executeShellCommand("service check system_server_dumper"),
                containsStringIgnoringCase("system_server_dumper: found"));

        assertWithMessage("System server dumper")
                .that(executeShellCommand("dumpsys system_server_dumper --list"))
                .contains("CarServiceHelper");

        assertWithMessage("CarServiceHelperService dump")
                .that(executeShellCommand("dumpsys system_server_dumper --name CarServiceHelper"))
                .contains("CarServiceProxy");

        // Test setSafeMode
        try {
            executeShellCommand("cmd car_service emulate-driving-state drive");

            assertWithMessage("CarServiceHelperService dump")
                    .that(executeShellCommand(
                            "dumpsys system_server_dumper --name CarServiceHelper"))
                    .contains("Safe to run device policy operations: false");
        } finally {
            executeShellCommand("cmd car_service emulate-driving-state park");
        }

        assertWithMessage("CarServiceHelperService dump")
                .that(executeShellCommand("dumpsys system_server_dumper --name CarServiceHelper"))
                .contains("Safe to run device policy operations: true");

        // Test dumpServiceStacks
        assertWithMessage("CarServiceHelperService dump")
                .that(executeShellCommand("dumpsys system_server_dumper --name CarServiceHelper"
                        + " --dump-service-stacks"))
                .contains("dumpServiceStacks ANR file path=/data/anr/anr_");
    }

    @FlakyTest(bugId = 222167696)
    @Test
    public void testSendUserLifecycleEventAndOnUserRemoved() throws Exception {
        // Add listener to check if user started
        CarUserManager carUserManager = (CarUserManager) getCar()
                .getCarManager(Car.CAR_USER_SERVICE);
        LifecycleListener listener = new LifecycleListener();
        carUserManager.addListener(Runnable::run, listener);

        NewUserResponse response = null;
        UserManager userManager = null;
        boolean userRemoved = false;
        try {
            // get create User permissions
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .adoptShellPermissionIdentity(android.Manifest.permission.CREATE_USERS);

            // CreateUser
            userManager = mContext.getSystemService(UserManager.class);
            response = userManager.createUser(new NewUserRequest.Builder().build());
            assertThat(response.isSuccessful()).isTrue();

            int userId = response.getUser().getIdentifier();
            startUser(userId);
            listener.assertEventReceived(userId, CarUserManager.USER_LIFECYCLE_EVENT_TYPE_STARTING);

            // TestOnUserRemoved call
            userRemoved = userManager.removeUser(response.getUser());
            // check the dump stack
            assertLastUserRemoved(userId);
        } finally {
            if (!userRemoved && response != null && response.isSuccessful()) {
                userManager.removeUser(response.getUser());
            }
            carUserManager.removeListener(listener);
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    private void assertLastUserRemoved(int userId) throws Exception {
        // check for the logcat
        // TODO(b/210874444): Use logcat helper from
        // cts/tests/tests/car_builtin/src/android/car/cts/builtin/util/LogcatHelper.java
        String match = "car_service_on_user_removed: " + userId;
        long timeout = 60_000;
        long startTime = SystemClock.elapsedRealtime();
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        String command = "logcat -b events";
        ParcelFileDescriptor output = automation.executeShellCommand(command);
        FileDescriptor fd = output.getFileDescriptor();
        FileInputStream fileInputStream = new FileInputStream(fd);
        try (BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(fileInputStream))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if (line.contains(match)) {
                    return;
                }
                if ((SystemClock.elapsedRealtime() - startTime) > timeout) {
                    fail("match '" + match + "' was not found, Timeout: " + timeout + " ms");
                }
            }
        } catch (IOException e) {
            fail("match '" + match + "' was not found, IO exception: " + e);
        }

    }

    // TODO(214100537): Improve listener by removing sleep.
    private final class LifecycleListener implements UserLifecycleListener {

        private final List<UserLifecycleEvent> mEvents =
                new ArrayList<CarUserManager.UserLifecycleEvent>();

        private final Object mLock = new Object();

        @Override
        public void onEvent(UserLifecycleEvent event) {
            Log.d(TAG, "Event received: " + event);
            synchronized (mLock) {
                mEvents.add(event);
            }
        }

        public void assertEventReceived(int userId, int eventType)
                throws InterruptedException {
            long startTime = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - startTime < TIMEOUT_MS) {
                boolean result = checkEvent(userId, eventType);
                if (result) return;
                Thread.sleep(WAIT_TIME_MS);
            }

            fail("Event" + eventType + " was not received within timeoutMs: " + TIMEOUT_MS);
        }

        private boolean checkEvent(int userId, int eventType) {
            synchronized (mLock) {
                for (int i = 0; i < mEvents.size(); i++) {
                    if (mEvents.get(i).getUserHandle().getIdentifier() == userId
                            && mEvents.get(i).getEventType() == eventType) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
