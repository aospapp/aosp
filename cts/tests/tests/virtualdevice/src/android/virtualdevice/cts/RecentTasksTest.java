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

package android.virtualdevice.cts;

import static android.Manifest.permission.ACTIVITY_EMBEDDING;
import static android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY;
import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.Manifest.permission.REAL_GET_TASKS;
import static android.Manifest.permission.WAKE_LOCK;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_RECENTS;
import static android.virtualdevice.cts.util.VirtualDeviceTestUtils.createActivityOptions;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import static java.lang.Integer.MAX_VALUE;

import android.app.Activity;
import android.app.ActivityManager;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager.VirtualDevice;
import android.companion.virtual.VirtualDeviceParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.platform.test.annotations.AppModeFull;
import android.virtualdevice.cts.common.FakeAssociationRule;
import android.virtualdevice.cts.util.EmptyActivity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.function.Predicate;

@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "VirtualDeviceManager cannot be accessed by instant apps")
public class RecentTasksTest {
    private static final VirtualDisplayConfig VIRTUAL_DISPLAY_CONFIG =
            new VirtualDisplayConfig.Builder("testDisplay", 100, 100, 100).setFlags(
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED).build();

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            ACTIVITY_EMBEDDING,
            ADD_ALWAYS_UNLOCKED_DISPLAY,
            ADD_TRUSTED_DISPLAY,
            CREATE_VIRTUAL_DEVICE,
            REAL_GET_TASKS,
            WAKE_LOCK);

    @Rule
    public FakeAssociationRule mFakeAssociationRule = new FakeAssociationRule();
    private VirtualDeviceManager mVirtualDeviceManager;
    private ActivityManager mActivityManager;

    @Before
    public void setUp() throws Exception {
        Context context = getApplicationContext();
        final PackageManager packageManager = context.getPackageManager();
        assumeTrue(packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP));
        assumeTrue(packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS));
        mVirtualDeviceManager = context.getSystemService(VirtualDeviceManager.class);
        assumeNotNull(mVirtualDeviceManager);
        mActivityManager = context.getSystemService(ActivityManager.class);
    }

    @Test
    public void activityLaunchedOnVdmWithDefaultRecentPolicy_includedInRecents() {
        try (VirtualDevice virtualDevice = createVirtualDeviceWithRecentsPolicy(
                DEVICE_POLICY_DEFAULT)) {
            VirtualDisplay virtualDisplay = virtualDevice.createVirtualDisplay(
                    VIRTUAL_DISPLAY_CONFIG, /*executor=*/null, /*callback=*/null);
            Activity activity = startActivityOnVirtualDisplay(virtualDisplay, EmptyActivity.class);

            List<ActivityManager.RecentTaskInfo> recentTasks = mActivityManager.getRecentTasks(
                    MAX_VALUE, /*flags=*/0);
            activity.finish();

            assertThat(recentTasks.stream().anyMatch(matchesActivity(activity))).isTrue();
        }
    }

    @Test
    public void activityLaunchedOnVdmWithCustomRecentPolicy_excludedFromRecents() {
        try (VirtualDevice virtualDevice = createVirtualDeviceWithRecentsPolicy(
                DEVICE_POLICY_CUSTOM)) {
            VirtualDisplay virtualDisplay = virtualDevice.createVirtualDisplay(
                    VIRTUAL_DISPLAY_CONFIG, /*executor=*/null, /*callback=*/null);
            Activity activity = startActivityOnVirtualDisplay(virtualDisplay, EmptyActivity.class);

            List<ActivityManager.RecentTaskInfo> recentTasks = mActivityManager.getRecentTasks(
                    MAX_VALUE, /*flags=*/0);
            activity.finish();

            assertThat(recentTasks.stream().anyMatch(matchesActivity(activity))).isFalse();
        }
    }

    Predicate<ActivityManager.RecentTaskInfo> matchesActivity(Activity activity) {
        return recentTaskInfo -> recentTaskInfo.baseIntent != null
                && activity.getComponentName().equals(
                recentTaskInfo.baseIntent.getComponent());
    }

    private VirtualDevice createVirtualDeviceWithRecentsPolicy(
            @VirtualDeviceParams.DevicePolicy int recentsPolicy) {
        return mVirtualDeviceManager.createVirtualDevice(
                mFakeAssociationRule.getAssociationInfo().getId(),
                new VirtualDeviceParams.Builder().setDevicePolicy(POLICY_TYPE_RECENTS,
                        recentsPolicy).build());
    }

    private static <T extends Activity> Activity startActivityOnVirtualDisplay(
            VirtualDisplay virtualDisplay, Class<T> activityClass) {
        return InstrumentationRegistry.getInstrumentation()
                .startActivitySync(
                        new Intent(getApplicationContext(), activityClass)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        createActivityOptions(virtualDisplay));
    }
}
