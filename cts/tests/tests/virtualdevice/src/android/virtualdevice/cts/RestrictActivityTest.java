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

package android.virtualdevice.cts;

import static android.Manifest.permission.ACTIVITY_EMBEDDING;
import static android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY;
import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.Manifest.permission.REAL_GET_TASKS;
import static android.Manifest.permission.WAKE_LOCK;
import static android.virtualdevice.cts.util.VirtualDeviceTestUtils.createActivityOptions;
import static android.virtualdevice.cts.util.VirtualDeviceTestUtils.createResultReceiver;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.annotation.Nullable;
import android.app.Activity;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager.VirtualDevice;
import android.companion.virtual.VirtualDeviceParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.ImageReader;
import android.os.ResultReceiver;
import android.platform.test.annotations.AppModeFull;
import android.virtualdevice.cts.common.FakeAssociationRule;
import android.virtualdevice.cts.util.TestAppHelper;
import android.virtualdevice.cts.util.VirtualDeviceTestUtils.OnReceiveResultListener;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;

/** Tests for blocking of activities that should not be shown on the virtual device. */
@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "VirtualDeviceManager cannot be accessed by instant apps")
public class RestrictActivityTest {

    private static final String DISPLAY_NAME = "RestrictActivityTest";

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule =
            new AdoptShellPermissionsRule(
                    InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                    ACTIVITY_EMBEDDING,
                    ADD_ALWAYS_UNLOCKED_DISPLAY,
                    ADD_TRUSTED_DISPLAY,
                    CREATE_VIRTUAL_DEVICE,
                    REAL_GET_TASKS,
                    WAKE_LOCK);

    @Rule public FakeAssociationRule mFakeAssociationRule = new FakeAssociationRule();

    private VirtualDeviceManager mVirtualDeviceManager;
    @Nullable private VirtualDevice mVirtualDevice;
    @Mock private VirtualDisplay.Callback mVirtualDisplayCallback;
    @Mock private OnReceiveResultListener mOnReceiveResultListener;
    private ResultReceiver mResultReceiver;
    private DisplayManager mDisplayManager;
    private ImageReader mReader;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        Context context = getApplicationContext();
        final PackageManager packageManager = context.getPackageManager();
        assumeTrue(packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP));
        assumeTrue(packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS));
        mVirtualDeviceManager = context.getSystemService(VirtualDeviceManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mResultReceiver = createResultReceiver(mOnReceiveResultListener);
        mReader = ImageReader.newInstance(
                        /* width= */ 100, /* height= */ 100,
                PixelFormat.RGBA_8888, /* maxImages= */ 1);
    }

    @After
    public void tearDown() {
        if (mVirtualDevice != null) {
            mVirtualDevice.close();
        }
    }

    @Test
    public void restrictedActivity_nonRestrictedActivityOnNonRestrictedDevice_shouldSucceed() {
        VirtualDisplay virtualDisplay = createVirtualDisplay(/* displayCategories= */ null);
        Intent intent =
                TestAppHelper.createActivityLaunchedReceiverIntent(mResultReceiver)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .startActivity(intent, createActivityOptions(virtualDisplay));

        verify(mOnReceiveResultListener, timeout(3000))
                .onReceiveResult(
                        eq(Activity.RESULT_OK),
                        argThat(
                                result ->
                                        result.getInt(TestAppHelper.EXTRA_DISPLAY)
                                                == virtualDisplay.getDisplay().getDisplayId()));
    }

    @Test
    public void restrictedActivity_restrictedActivityOnNonRestrictedDevice_shouldFail() {
        VirtualDisplay virtualDisplay = createVirtualDisplay(/* displayCategories= */ null);
        launchRestrictedAutomotiveActivity(virtualDisplay);

        verify(mOnReceiveResultListener, after(3000).never()).onReceiveResult(anyInt(), any());
    }

    @Test
    public void restrictedActivity_nonRestrictedActivityOnRestrictedDevice_shouldFail() {
        VirtualDisplay virtualDisplay =
                createVirtualDisplay(Set.of("automotive"));
        Intent intent =
                TestAppHelper.createActivityLaunchedReceiverIntent(mResultReceiver)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .startActivity(intent, createActivityOptions(virtualDisplay));

        verify(mOnReceiveResultListener, after(3000).never()).onReceiveResult(anyInt(), any());
    }

    @Test
    public void restrictedActivity_differentCategory_shouldFail() {
        VirtualDisplay virtualDisplay =
                createVirtualDisplay(Set.of("abc"));
        launchRestrictedAutomotiveActivity(virtualDisplay);

        verify(mOnReceiveResultListener, after(3000).never()).onReceiveResult(anyInt(), any());
    }

    @Test
    public void restrictedActivity_containCategories_shouldSucceed() {
        VirtualDisplay virtualDisplay =
                createVirtualDisplay(Set.of("automotive"));
        launchRestrictedAutomotiveActivity(virtualDisplay);

        verify(mOnReceiveResultListener, timeout(3000))
                .onReceiveResult(
                        eq(Activity.RESULT_OK),
                        argThat(
                                result ->
                                        result.getInt(TestAppHelper.EXTRA_DISPLAY)
                                                == virtualDisplay.getDisplay().getDisplayId()));
    }

    @Test
    public void restrictedActivity_noGwpc_shouldFail() {
        VirtualDisplay virtualDisplay =
                mDisplayManager.createVirtualDisplay(
                        /* name= */ "name",
                        /* width= */ 100,
                        /* height= */ 100,
                        /* densityDpi= */ 240,
                        mReader.getSurface(),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED);
        launchRestrictedAutomotiveActivity(virtualDisplay);

        verify(mOnReceiveResultListener, after(3000).never()).onReceiveResult(anyInt(), any());
    }

    private void launchRestrictedAutomotiveActivity(VirtualDisplay display) {
        Intent intent =
                TestAppHelper.createRestrictActivityIntent(mResultReceiver)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .startActivity(intent, createActivityOptions(display));
    }

    private VirtualDisplay createVirtualDisplay(@Nullable Set<String> displayCategories) {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        new VirtualDeviceParams.Builder().build());
        VirtualDisplayConfig.Builder builder =
                new VirtualDisplayConfig.Builder(DISPLAY_NAME, 100, 100, 240)
                        .setSurface(mReader.getSurface())
                        .setFlags(DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED);
        if (displayCategories != null) {
            builder = builder.setDisplayCategories(displayCategories);
        }
        return mVirtualDevice.createVirtualDisplay(
                builder.build(), Runnable::run, mVirtualDisplayCallback);
    }
}
