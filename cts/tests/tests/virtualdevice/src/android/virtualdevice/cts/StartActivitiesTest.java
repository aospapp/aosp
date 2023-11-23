/*
 * Copyright (C) 2021 The Android Open Source Project
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
import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.Manifest.permission.READ_CLIPBOARD_IN_BACKGROUND;
import static android.Manifest.permission.WAKE_LOCK;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.virtualdevice.cts.util.VirtualDeviceTestUtils.createResultReceiver;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.PendingIntent;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager.VirtualDevice;
import android.companion.virtual.VirtualDeviceParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.platform.test.annotations.AppModeFull;
import android.virtualdevice.cts.common.FakeAssociationRule;
import android.virtualdevice.cts.util.TestAppHelper;
import android.virtualdevice.cts.util.VirtualDeviceTestUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;
import com.android.compatibility.common.util.ApiTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.function.IntConsumer;

@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "VirtualDeviceManager cannot be accessed by instant apps")
public class StartActivitiesTest {

    private static final VirtualDeviceParams DEFAULT_VIRTUAL_DEVICE_PARAMS =
            new VirtualDeviceParams.Builder().build();

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            ACTIVITY_EMBEDDING,
            ADD_TRUSTED_DISPLAY,
            CREATE_VIRTUAL_DEVICE,
            READ_CLIPBOARD_IN_BACKGROUND,
            WAKE_LOCK);

    @Rule
    public FakeAssociationRule mFakeAssociationRule = new FakeAssociationRule();

    private VirtualDeviceManager mVirtualDeviceManager;
    @Nullable
    private VirtualDevice mVirtualDevice;
    @Nullable
    private VirtualDisplay mVirtualDisplay;
    private Context mContext;
    @Mock
    private VirtualDisplay.Callback mVirtualDisplayCallback;
    @Mock
    private IntConsumer mLaunchCompleteListener;
    @Mock
    private VirtualDeviceTestUtils.OnReceiveResultListener mOnReceiveResultListener;
    private ResultReceiver mResultReceiver;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = getApplicationContext();
        final PackageManager packageManager = mContext.getPackageManager();
        assumeTrue(packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP));
        assumeTrue(packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS));
        mVirtualDeviceManager = mContext.getSystemService(VirtualDeviceManager.class);
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        mVirtualDisplay = mVirtualDevice.createVirtualDisplay(
                /* width= */ 100,
                /* height= */ 100,
                /* densityDpi= */ 240,
                /* surface= */ null,
                /* flags= */ DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED,
                Runnable::run,
                mVirtualDisplayCallback);
        mResultReceiver = createResultReceiver(mOnReceiveResultListener);
    }

    @After
    public void tearDown() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
        }
        if (mVirtualDevice != null) {
            mVirtualDevice.close();
        }
    }

    @Test
    @ApiTest(apis = {"android.companion.virtual.VirtualDeviceManager#launchPendingIntent"})
    public void testStartActivities_shouldLaunchOnSameDisplay() {
        final int displayId = mVirtualDisplay.getDisplay().getDisplayId();
        final int requestCode = 1;

        final Intent[] intents = TestAppHelper.createStartActivitiesIntents(mResultReceiver);
        mVirtualDevice.launchPendingIntent(displayId,
                PendingIntent.getActivities(mContext, requestCode, intents,
                        FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT),
                Runnable::run, mLaunchCompleteListener);

        ArgumentCaptor<Bundle> bundleArgumentCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mOnReceiveResultListener, timeout(3000).times(intents.length))
                .onReceiveResult(eq(Activity.RESULT_OK), bundleArgumentCaptor.capture());
        verify(mLaunchCompleteListener).accept(eq(VirtualDeviceManager.LAUNCH_SUCCESS));

        List<Bundle> bundleList = bundleArgumentCaptor.getAllValues();
        for (int i = bundleList.size() - 1; i >= 0; i--) {
            Bundle bundle = bundleList.get(i);
            assertThat(bundle.size()).isEqualTo(1);
            for (String key : bundle.keySet()) {
                assertThat(bundle.getInt(key)).isEqualTo(displayId);
            }
        }
    }

    @Test
    public void launchPendingIntent_nullArguments_shouldThrow() {
        final int displayId = mVirtualDisplay.getDisplay().getDisplayId();
        final int requestCode = 1;
        final Intent[] intents = TestAppHelper.createStartActivitiesIntents(mResultReceiver);

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.launchPendingIntent(displayId,
                        null,
                        Runnable::run,
                        mLaunchCompleteListener));

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.launchPendingIntent(displayId,
                        PendingIntent.getActivities(mContext, requestCode, intents,
                                FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT),
                        null,
                        mLaunchCompleteListener));

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.launchPendingIntent(displayId,
                        PendingIntent.getActivities(mContext, requestCode, intents,
                                FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT),
                        Runnable::run,
                        null));
    }

}
