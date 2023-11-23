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
import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.Manifest.permission.READ_CLIPBOARD_IN_BACKGROUND;
import static android.Manifest.permission.READ_DEVICE_CONFIG;
import static android.Manifest.permission.WAKE_LOCK;
import static android.Manifest.permission.WRITE_ALLOWLISTED_DEVICE_CONFIG;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT;
import static android.virtualdevice.cts.common.ClipboardTestConstants.ACTION_GET_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.ACTION_SET_AND_GET_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.ACTION_SET_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.ACTION_WAIT_FOR_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_FINISH_AFTER_SENDING_RESULT;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_GET_CLIP_DATA;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_HAS_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_NOTIFY_WHEN_ATTACHED_TO_WINDOW;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_NOT_FOCUSABLE;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_RESULT_RECEIVER;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_SET_CLIP_DATA;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_WAIT_FOR_FOCUS;
import static android.virtualdevice.cts.common.ClipboardTestConstants.RESULT_CODE_ATTACHED_TO_WINDOW;
import static android.virtualdevice.cts.common.ClipboardTestConstants.RESULT_CODE_CLIP_LISTENER_READY;
import static android.virtualdevice.cts.util.VirtualDeviceTestUtils.createActivityOptions;
import static android.virtualdevice.cts.util.VirtualDeviceTestUtils.createResultReceiver;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.app.UiAutomation;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager.VirtualDevice;
import android.companion.virtual.VirtualDeviceParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.platform.test.annotations.AppModeFull;
import android.server.wm.WindowManagerStateHelper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.virtualdevice.cts.common.FakeAssociationRule;
import android.virtualdevice.cts.util.VirtualDeviceTestUtils;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;
import com.android.compatibility.common.util.Timeout;

import com.google.common.util.concurrent.SettableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "VirtualDeviceManager cannot be accessed by instant apps")
public class StreamedAppClipboardTest {
    private static final String TAG = "StreamedAppClipboardTest";

    private static final ComponentName CLIPBOARD_TEST_ACTIVITY =
            new ComponentName("android.virtualdevice.streamedtestapp",
                    "android.virtualdevice.streamedtestapp.ClipboardTestActivity");
    private static final ComponentName CLIPBOARD_TEST_ACTIVITY_2 =
            new ComponentName("android.virtualdevice.streamedtestapp2",
                    "android.virtualdevice.streamedtestapp2.ClipboardTestActivity2");
    private static final int EVENT_TIMEOUT_MS = 6000;

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            ACTIVITY_EMBEDDING,
            ADD_TRUSTED_DISPLAY,
            CREATE_VIRTUAL_DEVICE,
            READ_CLIPBOARD_IN_BACKGROUND,
            READ_DEVICE_CONFIG,
            WRITE_ALLOWLISTED_DEVICE_CONFIG,
            WRITE_SECURE_SETTINGS,
            WAKE_LOCK);

    @Rule
    public FakeAssociationRule mFakeAssociationRule = new FakeAssociationRule();

    private VirtualDeviceManager mVirtualDeviceManager;
    private VirtualDevice mVirtualDevice;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReaderForVirtualDisplay;
    private Context mContext;
    @Mock
    private VirtualDisplay.Callback mVirtualDisplayCallback;

    @Mock
    private VirtualDeviceTestUtils.OnReceiveResultListener mOnReceiveResultListener;
    private ResultReceiver mResultReceiver;

    private final WindowManagerStateHelper mWmState = new WindowManagerStateHelper();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = getApplicationContext();
        final PackageManager packageManager = mContext.getPackageManager();
        assumeTrue(packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP));
        assumeTrue(packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS));
        // TODO(b/261155110): Re-enable tests once freeform mode is supported in Virtual Display.
        assumeFalse("Skipping test: VirtualDisplay window policy doesn't support freeform.",
                packageManager.hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT));

        mVirtualDeviceManager = mContext.getSystemService(VirtualDeviceManager.class);
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        new VirtualDeviceParams.Builder().build());

        mImageReaderForVirtualDisplay = ImageReader.newInstance(/* width= */ 100, /* height= */ 100,
                PixelFormat.RGBA_8888, /* maxImages= */ 1);
        mVirtualDisplay = mVirtualDevice.createVirtualDisplay(
                /* width= */ 100,
                /* height= */ 100,
                /* densityDpi= */ 240,
                /* surface= */ mImageReaderForVirtualDisplay.getSurface(),
                /* flags= */ 0,
                /* executor= */ Runnable::run,
                mVirtualDisplayCallback);
        mResultReceiver = createResultReceiver(mOnReceiveResultListener);
    }

    @After
    public void tearDown() {
        // Avoid crash in sysui showing toasts on closed VirtualDisplay (b/265325338).
        waitForNoToasts();
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
        }
        if (mVirtualDevice != null) {
            mVirtualDevice.close();
        }
        if (mImageReaderForVirtualDisplay != null) {
            mImageReaderForVirtualDisplay.close();
        }
    }

    @Test
    public void oneAppOnVirtualDevice_canWriteAndReadClipboard() {
        ClipboardManager clipboard = mContext.createDeviceContext(
                DEVICE_ID_DEFAULT).getSystemService(ClipboardManager.class);
        clipboard.clearPrimaryClip();
        assertThat(clipboard.hasPrimaryClip()).isFalse();

        ClipData clipToSet = ClipData.newPlainText("some label", "Hello World");
        final Intent intent = new Intent(ACTION_SET_AND_GET_CLIP)
                .setComponent(CLIPBOARD_TEST_ACTIVITY)
                .putExtra(EXTRA_SET_CLIP_DATA, clipToSet)
                .putExtra(EXTRA_RESULT_RECEIVER, mResultReceiver);
        launchAndAwaitActivityOnVirtualDisplay(intent);
        tapOnDisplay(mVirtualDisplay.getDisplay());

        ArgumentCaptor<Bundle> bundle = ArgumentCaptor.forClass(Bundle.class);
        verify(mOnReceiveResultListener, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(anyInt(),
                bundle.capture());

        // Make sure the activity was able to read what it wrote itself.
        ClipData clipData = bundle.getValue().getParcelable(EXTRA_GET_CLIP_DATA, ClipData.class);
        assertThat(clipData).isNotNull();
        assertThat(clipData.getItemCount()).isEqualTo(1);
        assertThat(clipData.getDescription().getLabel().toString()).isEqualTo(
                clipToSet.getDescription().getLabel().toString());
        assertThat(clipData.getItemAt(0).getText()).isEqualTo(clipToSet.getItemAt(0).getText());

        // We shouldn't observe what was written to the VirtualDevice siloed clipboard
        assertThat(clipboard.hasPrimaryClip()).isFalse();
    }


    @Test
    public void oneAppOnVirtualDevice_appWritesClipboard_deviceOwnerGetsEventAndCanRead() {
        ClipboardManager deviceClipboard = mVirtualDevice.createContext().getSystemService(
                ClipboardManager.class);

        ClipboardManager.OnPrimaryClipChangedListener deviceClipboardListener =
                mock(ClipboardManager.OnPrimaryClipChangedListener.class);
        deviceClipboard.addPrimaryClipChangedListener(deviceClipboardListener);

        ClipData clipToSet = ClipData.newPlainText("some label", "Hello World");
        final Intent intent = new Intent(ACTION_SET_CLIP)
                .setComponent(CLIPBOARD_TEST_ACTIVITY)
                .putExtra(EXTRA_SET_CLIP_DATA, clipToSet);
        launchAndAwaitActivityOnVirtualDisplay(intent);
        tapOnDisplay(mVirtualDisplay.getDisplay());

        // We use atLeastOnce for onPrimaryClipChanged because it can fire more than once as
        // text classification results on the clipboard content become available.
        verify(deviceClipboardListener,
                timeout(EVENT_TIMEOUT_MS).atLeastOnce()).onPrimaryClipChanged();
        assertThat(deviceClipboard.hasPrimaryClip()).isTrue();
        ClipData clipData = deviceClipboard.getPrimaryClip();
        assertThat(clipData.getItemCount()).isEqualTo(1);
        assertThat(clipData.getDescription().getLabel().toString()).isEqualTo(
                clipToSet.getDescription().getLabel().toString());
        assertThat(clipData.getItemAt(0).getText()).isEqualTo(clipToSet.getItemAt(0).getText());
    }

    @Test
    public void oneAppOnVirtualDevice_deviceOwnerWritesClipboard_appGetsEventAndCanRead() {
        ClipboardManager deviceClipboard = mVirtualDevice.createContext().getSystemService(
                ClipboardManager.class);

        launchAndAwaitActivityOnVirtualDisplay(new Intent(ACTION_WAIT_FOR_CLIP)
                .setComponent(CLIPBOARD_TEST_ACTIVITY)
                .putExtra(EXTRA_RESULT_RECEIVER, mResultReceiver));

        // Give the activity focus so that it is allowed to read the clipboard.
        tapOnDisplay(mVirtualDisplay.getDisplay());

        // Make sure the clip listener is ready
        verify(mOnReceiveResultListener, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(
                eq(RESULT_CODE_CLIP_LISTENER_READY), any());

        ClipData clipToSet = ClipData.newPlainText("some label", "Hello World");
        waitForNoToasts();
        deviceClipboard.setPrimaryClip(clipToSet);
        waitForToastToAppearAndDisappear();

        ArgumentCaptor<Bundle> bundle = ArgumentCaptor.forClass(Bundle.class);
        verify(mOnReceiveResultListener, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(
                eq(Activity.RESULT_OK), bundle.capture());

        ClipData clipData = bundle.getValue().getParcelable(EXTRA_GET_CLIP_DATA, ClipData.class);
        assertThat(clipData).isNotNull();
        assertThat(clipData.getItemCount()).isEqualTo(1);
        assertThat(clipData.getDescription().getLabel().toString()).isEqualTo(
                clipToSet.getDescription().getLabel().toString());
        assertThat(clipData.getItemAt(0).getText()).isEqualTo(clipToSet.getItemAt(0).getText());
    }

    @Test
    public void unfocusedAppOnVirtualDevice_deviceOwnerWritesClipboard_appCannotRead() {
        ClipboardManager deviceClipboard = mVirtualDevice.createContext().getSystemService(
                ClipboardManager.class);

        ClipData clipToSet = ClipData.newPlainText("some label", "Hello World");
        deviceClipboard.setPrimaryClip(clipToSet);

        launchAndAwaitActivityOnVirtualDisplay(new Intent(ACTION_GET_CLIP)
                .setComponent(CLIPBOARD_TEST_ACTIVITY)
                .putExtra(EXTRA_NOT_FOCUSABLE, true)
                .putExtra(EXTRA_WAIT_FOR_FOCUS, false)
                .putExtra(EXTRA_RESULT_RECEIVER, mResultReceiver));

        // Note that we do not tap on the display here - the app should remain unfocused and should
        // not be able to read the contents of the clipboard.

        ArgumentCaptor<Bundle> bundle = ArgumentCaptor.forClass(Bundle.class);
        verify(mOnReceiveResultListener, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(anyInt(),
                bundle.capture());
        assertThat(bundle.getValue().getBoolean(EXTRA_HAS_CLIP, true)).isFalse();
        ClipData clipData = bundle.getValue().getParcelable(EXTRA_GET_CLIP_DATA, ClipData.class);
        assertThat(clipData).isNull();
    }

    @Test
    public void oneAppOnVirtualDeviceOneOnDefault_firstAppWrites_secondCannotRead() {
        ClipboardManager defaultClipboard = mContext.createDeviceContext(
                DEVICE_ID_DEFAULT).getSystemService(ClipboardManager.class);
        defaultClipboard.clearPrimaryClip();

        final Intent firstAppIntent =
                new Intent(ACTION_SET_CLIP).setComponent(CLIPBOARD_TEST_ACTIVITY);
        ClipData clipToSet = ClipData.newPlainText("some label", "Hello World");
        firstAppIntent.putExtra(EXTRA_SET_CLIP_DATA, clipToSet);
        firstAppIntent.putExtra(EXTRA_RESULT_RECEIVER, mResultReceiver);
        launchAndAwaitActivityOnVirtualDisplay(firstAppIntent);
        tapOnDisplay(mVirtualDisplay.getDisplay());
        verify(mOnReceiveResultListener, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(anyInt(),
                any());

        VirtualDeviceTestUtils.OnReceiveResultListener secondResultReceiver = mock(
                VirtualDeviceTestUtils.OnReceiveResultListener.class);
        final Intent secondAppIntent = new Intent(ACTION_GET_CLIP)
                .setComponent(CLIPBOARD_TEST_ACTIVITY_2)
                .putExtra(EXTRA_NOTIFY_WHEN_ATTACHED_TO_WINDOW, true)
                .putExtra(EXTRA_RESULT_RECEIVER,
                        createResultReceiver(secondResultReceiver))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        InstrumentationRegistry.getInstrumentation().getTargetContext()
                .startActivity(secondAppIntent, null);
        verify(secondResultReceiver, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(
                eq(RESULT_CODE_ATTACHED_TO_WINDOW), any());
        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        tapOnDisplay(displayManager.getDisplay(Display.DEFAULT_DISPLAY));

        ArgumentCaptor<Bundle> bundle = ArgumentCaptor.forClass(Bundle.class);
        verify(secondResultReceiver, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(
                eq(Activity.RESULT_OK), bundle.capture());
        assertThat(bundle.getValue().getBoolean(EXTRA_HAS_CLIP, true)).isFalse();
        ClipData clipData = bundle.getValue().getParcelable(EXTRA_GET_CLIP_DATA, ClipData.class);
        assertThat(clipData).isNull();
    }

    @Test
    public void twoAppsOnVirtualDevice_firstAppWrites_secondAppCanRead() {
        final Intent firstAppIntent =
                new Intent(ACTION_SET_CLIP).setComponent(CLIPBOARD_TEST_ACTIVITY);

        ClipData clipToSet = ClipData.newPlainText("some label", "Hello World");
        firstAppIntent.putExtra(EXTRA_SET_CLIP_DATA, clipToSet);
        firstAppIntent.putExtra(EXTRA_RESULT_RECEIVER, mResultReceiver);
        launchAndAwaitActivityOnVirtualDisplay(firstAppIntent);
        tapOnDisplay(mVirtualDisplay.getDisplay());
        verify(mOnReceiveResultListener, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(anyInt(),
                any());

        VirtualDeviceTestUtils.OnReceiveResultListener secondResultReceiver = mock(
                VirtualDeviceTestUtils.OnReceiveResultListener.class);
        final Intent secondAppIntent = new Intent(ACTION_GET_CLIP).setComponent(
                        CLIPBOARD_TEST_ACTIVITY_2)
                .putExtra(EXTRA_RESULT_RECEIVER, createResultReceiver(secondResultReceiver));

        waitForNoToasts();
        launchAndAwaitActivityOnVirtualDisplay(secondAppIntent);
        tapOnDisplay(mVirtualDisplay.getDisplay());
        waitForToastToAppearAndDisappear();

        // Make sure that the second activity now running on top of the VirtualDisplay reads the
        // value which was set by the first activity.
        ArgumentCaptor<Bundle> bundle = ArgumentCaptor.forClass(Bundle.class);
        verify(secondResultReceiver, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(anyInt(),
                bundle.capture());
        ClipData clipData = bundle.getValue().getParcelable(EXTRA_GET_CLIP_DATA, ClipData.class);
        assertThat(clipData).isNotNull();
        assertThat(clipData.getItemCount()).isEqualTo(1);
        assertThat(clipData.getDescription().getLabel().toString()).isEqualTo(
                clipToSet.getDescription().getLabel().toString());
        assertThat(clipData.getItemAt(0).getText()).isEqualTo(clipToSet.getItemAt(0).getText());
    }

    @Test
    public void twoAppsOnVirtualDevice_bothAppsWriteInSequence_deviceOwnerCanObserveBoth() {
        ClipboardManager deviceClipboard = mVirtualDevice.createContext().getSystemService(
                ClipboardManager.class);
        ClipboardManager.OnPrimaryClipChangedListener deviceClipboardListener =
                mock(ClipboardManager.OnPrimaryClipChangedListener.class);
        deviceClipboard.addPrimaryClipChangedListener(deviceClipboardListener);

        ClipData app1Clip = ClipData.newPlainText("app one", "Hello World 1");
        final Intent firstAppIntent =
                new Intent(ACTION_SET_CLIP).setComponent(CLIPBOARD_TEST_ACTIVITY)
                        .putExtra(EXTRA_SET_CLIP_DATA, app1Clip)
                        .putExtra(EXTRA_RESULT_RECEIVER, mResultReceiver);
        launchAndAwaitActivityOnVirtualDisplay(firstAppIntent);
        tapOnDisplay(mVirtualDisplay.getDisplay());
        verify(mOnReceiveResultListener, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(anyInt(),
                any());

        verify(deviceClipboardListener,
                timeout(EVENT_TIMEOUT_MS).atLeastOnce()).onPrimaryClipChanged();
        assertThat(deviceClipboard.hasPrimaryClip()).isTrue();
        ClipData readClip1 = deviceClipboard.getPrimaryClip();
        assertThat(readClip1).isNotNull();
        assertThat(readClip1.getItemCount()).isEqualTo(1);
        assertThat(readClip1.getDescription().getLabel().toString()).isEqualTo(
                app1Clip.getDescription().getLabel().toString());
        assertThat(readClip1.getItemAt(0).getText()).isEqualTo(app1Clip.getItemAt(0).getText());

        ClipData app2Clip = ClipData.newPlainText("app two", "Hello World 2");
        VirtualDeviceTestUtils.OnReceiveResultListener secondResultReceiver = mock(
                VirtualDeviceTestUtils.OnReceiveResultListener.class);
        final Intent secondAppIntent = new Intent(ACTION_SET_CLIP).setComponent(
                        CLIPBOARD_TEST_ACTIVITY_2)
                .putExtra(EXTRA_SET_CLIP_DATA, app2Clip)
                .putExtra(EXTRA_RESULT_RECEIVER, createResultReceiver(secondResultReceiver));
        launchAndAwaitActivityOnVirtualDisplay(secondAppIntent);

        reset(deviceClipboardListener);
        tapOnDisplay(mVirtualDisplay.getDisplay());

        verify(secondResultReceiver, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(anyInt(), any());
        verify(deviceClipboardListener,
                timeout(EVENT_TIMEOUT_MS).atLeastOnce()).onPrimaryClipChanged();

        assertThat(deviceClipboard.hasPrimaryClip()).isTrue();
        ClipData readClip2 = deviceClipboard.getPrimaryClip();
        assertThat(readClip2).isNotNull();
        assertThat(readClip2.getItemCount()).isEqualTo(1);
        assertThat(readClip2.getDescription().getLabel().toString()).isEqualTo(
                app2Clip.getDescription().getLabel().toString());
        assertThat(readClip2.getItemAt(0).getText()).isEqualTo(app2Clip.getItemAt(0).getText());
    }

    @Test
    public void twoAppsOnVirtualDevice_focusedAppWrites_unfocusedAppDoesNotGetEvent() {
        // The first app does not wait for focus, and immediately starts waiting for a clipboard
        // change event.
        launchAndAwaitActivityOnVirtualDisplay(new Intent(ACTION_WAIT_FOR_CLIP)
                .setComponent(CLIPBOARD_TEST_ACTIVITY)
                .putExtra(EXTRA_NOT_FOCUSABLE, true)
                .putExtra(EXTRA_WAIT_FOR_FOCUS, false)
                .putExtra(EXTRA_RESULT_RECEIVER, mResultReceiver));

        // Make sure the clip listener is ready
        verify(mOnReceiveResultListener, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(
                eq(RESULT_CODE_CLIP_LISTENER_READY), any());

        // The second app is launched on top of the first app, and once given focus it writes into
        // the clipboard.
        ClipData app2Clip = ClipData.newPlainText("app two", "Hello World 2");
        VirtualDeviceTestUtils.OnReceiveResultListener secondResultReceiver = mock(
                VirtualDeviceTestUtils.OnReceiveResultListener.class);
        final Intent secondAppIntent = new Intent(ACTION_SET_CLIP)
                .setComponent(CLIPBOARD_TEST_ACTIVITY_2)
                .putExtra(EXTRA_SET_CLIP_DATA, app2Clip)
                .putExtra(EXTRA_RESULT_RECEIVER, createResultReceiver(secondResultReceiver));
        launchAndAwaitActivityOnVirtualDisplay(secondAppIntent);
        tapOnDisplay(mVirtualDisplay.getDisplay());

        // The second app should have been successful at writing the clipboard, and the first app
        // should not have gotten any change events that caused it to send a result back.
        verify(secondResultReceiver, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(anyInt(), any());
        verify(mOnReceiveResultListener, never()).onReceiveResult(eq(Activity.RESULT_OK), any());
    }

    @Test
    public void appRunsOnTwoVirtualDevices_clipboardWritesAreIsolated() {
        VirtualDevice secondDevice = mVirtualDeviceManager.createVirtualDevice(
                mFakeAssociationRule.getAssociationInfo().getId(),
                new VirtualDeviceParams.Builder().build());

        ImageReader reader = ImageReader.newInstance(/* width= */ 100, /* height= */ 100,
                PixelFormat.RGBA_8888, /* maxImages= */ 1);
        VirtualDisplay secondVirtualDisplay = secondDevice.createVirtualDisplay(
                /* width= */ 100,
                /* height= */ 100,
                /* densityDpi= */ 240,
                /* surface= */ reader.getSurface(),
                /* flags= */ 0,
                Runnable::run,
                /* callback= */ null);
        try {
            ClipboardManager deviceClipboard = mVirtualDevice.createContext().getSystemService(
                    ClipboardManager.class);
            ClipboardManager secondDeviceClipboard = secondDevice.createContext().getSystemService(
                    ClipboardManager.class);

            ClipboardManager.OnPrimaryClipChangedListener deviceClipboardListener =
                    mock(ClipboardManager.OnPrimaryClipChangedListener.class);
            ClipboardManager.OnPrimaryClipChangedListener secondDeviceClipboardListener =
                    mock(ClipboardManager.OnPrimaryClipChangedListener.class);

            deviceClipboard.addPrimaryClipChangedListener(deviceClipboardListener);
            secondDeviceClipboard.addPrimaryClipChangedListener(secondDeviceClipboardListener);

            ClipboardManager defaultClipboard = mContext.createDeviceContext(
                    DEVICE_ID_DEFAULT).getSystemService(ClipboardManager.class);
            defaultClipboard.clearPrimaryClip();
            ClipboardManager.OnPrimaryClipChangedListener defaultClipboardListener =
                    mock(ClipboardManager.OnPrimaryClipChangedListener.class);
            defaultClipboard.addPrimaryClipChangedListener(defaultClipboardListener);

            assertThat(deviceClipboard.hasPrimaryClip()).isFalse();
            assertThat(secondDeviceClipboard.hasPrimaryClip()).isFalse();
            assertThat(defaultClipboard.hasPrimaryClip()).isFalse();

            ClipData clipData1 = ClipData.newPlainText("device one", "Hello World 1");
            final Intent virtualDeviceOneIntent = new Intent(ACTION_SET_AND_GET_CLIP)
                    .setComponent(CLIPBOARD_TEST_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra(EXTRA_SET_CLIP_DATA,
                            clipData1)
                    .putExtra(EXTRA_FINISH_AFTER_SENDING_RESULT, false)
                    .putExtra(EXTRA_RESULT_RECEIVER, mResultReceiver);

            VirtualDeviceTestUtils.OnReceiveResultListener secondResultReceiver = mock(
                    VirtualDeviceTestUtils.OnReceiveResultListener.class);
            ClipData clipData2 = ClipData.newPlainText("device two", "Hello World 2");
            final Intent virtualDeviceTwoIntent = new Intent(ACTION_SET_AND_GET_CLIP)
                    .setComponent(CLIPBOARD_TEST_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra(EXTRA_SET_CLIP_DATA,
                            clipData2)
                    .putExtra(EXTRA_RESULT_RECEIVER,
                            createResultReceiver(secondResultReceiver));

            launchAndAwaitActivityOnVirtualDisplay(virtualDeviceOneIntent);
            launchAndAwaitActivity(virtualDeviceTwoIntent, secondDevice, secondVirtualDisplay);

            // Focus the display on the first VirtualDevice
            tapOnDisplay(mVirtualDisplay.getDisplay());

            // Make sure app on first VirtualDevice could write and read its own clip
            ArgumentCaptor<Bundle> bundle = ArgumentCaptor.forClass(Bundle.class);
            verify(mOnReceiveResultListener, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(anyInt(),
                    bundle.capture());
            assertThat(bundle.getValue().getBoolean(EXTRA_HAS_CLIP, false)).isTrue();
            ClipData readClip =
                    bundle.getValue().getParcelable(EXTRA_GET_CLIP_DATA, ClipData.class);
            assertThat(readClip.getItemCount()).isEqualTo(1);
            assertThat(readClip.getDescription().getLabel().toString()).isEqualTo(
                    clipData1.getDescription().getLabel().toString());
            assertThat(readClip.getItemAt(0).getText()).isEqualTo(
                    clipData1.getItemAt(0).getText());

            // The second app should not have sent any result yet
            verify(secondResultReceiver, never()).onReceiveResult(anyInt(), any());

            // Make sure the device owner (us) read the correct clip from the first VirtualDevice,
            // but hasn't gotten a clipboard change event for the second VirtualDevice's clipboard
            verify(deviceClipboardListener,
                    timeout(EVENT_TIMEOUT_MS).atLeastOnce()).onPrimaryClipChanged();
            verify(secondDeviceClipboardListener, never()).onPrimaryClipChanged();
            assertThat(deviceClipboard.hasPrimaryClip()).isTrue();
            readClip = deviceClipboard.getPrimaryClip();
            assertThat(readClip.getItemCount()).isEqualTo(1);
            assertThat(readClip.getDescription().getLabel().toString()).isEqualTo(
                    clipData1.getDescription().getLabel().toString());
            assertThat(readClip.getItemAt(0).getText()).isEqualTo(
                    clipData1.getItemAt(0).getText());

            // Focus the other VirtualDevice
            tapOnDisplay(secondVirtualDisplay.getDisplay());

            // Now make sure app on second VirtualDevice could write and read its own clip
            bundle = ArgumentCaptor.forClass(Bundle.class);
            verify(secondResultReceiver, timeout(EVENT_TIMEOUT_MS)).onReceiveResult(anyInt(),
                    bundle.capture());
            assertThat(bundle.getValue().getBoolean(EXTRA_HAS_CLIP, false)).isTrue();
            readClip = bundle.getValue().getParcelable(EXTRA_GET_CLIP_DATA, ClipData.class);
            assertThat(readClip.getItemCount()).isEqualTo(1);
            assertThat(readClip.getDescription().getLabel().toString()).isEqualTo(
                    clipData2.getDescription().getLabel().toString());
            assertThat(readClip.getItemAt(0).getText()).isEqualTo(
                    clipData2.getItemAt(0).getText());

            // Make sure the device owner (us) read the same clip from the second VirtualDevice
            verify(secondDeviceClipboardListener,
                    timeout(EVENT_TIMEOUT_MS).atLeastOnce()).onPrimaryClipChanged();
            assertThat(secondDeviceClipboard.hasPrimaryClip()).isTrue();
            waitForNoToasts();
            readClip = secondDeviceClipboard.getPrimaryClip();
            assertThat(readClip.getItemCount()).isEqualTo(1);
            assertThat(readClip.getDescription().getLabel().toString()).isEqualTo(
                    clipData2.getDescription().getLabel().toString());
            assertThat(readClip.getItemAt(0).getText()).isEqualTo(
                    clipData2.getItemAt(0).getText());

            assertThat(defaultClipboard.hasPrimaryClip()).isFalse();
            verify(defaultClipboardListener, never()).onPrimaryClipChanged();

        } finally {
            secondVirtualDisplay.release();
            secondDevice.close();
            reader.close();
        }
    }

    @Test
    public void virtualDeviceOwner_noAccessToasts() {
        ClipboardManager deviceClipboard = mVirtualDevice.createContext().getSystemService(
                ClipboardManager.class);

        ClipboardManager.OnPrimaryClipChangedListener deviceClipboardListener =
                mock(ClipboardManager.OnPrimaryClipChangedListener.class);
        deviceClipboard.addPrimaryClipChangedListener(deviceClipboardListener);

        ClipData clipToSet = ClipData.newPlainText("some label", "Hello World");
        final Intent intent = new Intent(ACTION_SET_CLIP)
                .setComponent(CLIPBOARD_TEST_ACTIVITY)
                .putExtra(EXTRA_SET_CLIP_DATA, clipToSet);
        launchAndAwaitActivityOnVirtualDisplay(intent);
        tapOnDisplay(mVirtualDisplay.getDisplay());

        // We use atLeastOnce for onPrimaryClipChanged because it can fire more than once as
        // text classification results on the clipboard content become available.
        verify(deviceClipboardListener,
                timeout(EVENT_TIMEOUT_MS).atLeastOnce()).onPrimaryClipChanged();
        assertThat(deviceClipboard.hasPrimaryClip()).isTrue();
        waitForNoToasts();
        ClipData clipData = deviceClipboard.getPrimaryClip();

        // Since we set the DEVICE_CONFIG_SHOW_ACCESS_NOTIFICATIONS_FOR_VD_OWNER flag to false
        // above, there should be no Toast windows.
        mWmState.computeState();
        assertThat(mWmState.getMatchingWindowType(WindowManager.LayoutParams.TYPE_TOAST)).isEmpty();
    }

    /**
     * Launches an activity on mVirtualDisplay and waits for it to be running.
     */
    private void launchAndAwaitActivityOnVirtualDisplay(Intent intent) {
        launchAndAwaitActivity(intent, mVirtualDevice, mVirtualDisplay);
    }

    /**
     * Launches an activity and waits for it to be running on top of the given VirtualDisplay
     * owned by the given VirtualDevice.
     */
    private void launchAndAwaitActivity(Intent intent, VirtualDevice virtualDevice,
            VirtualDisplay targetDisplay) {
        SettableFuture<Void> activityRunning = SettableFuture.create();

        VirtualDeviceManager.ActivityListener activityListener =
                new VirtualDeviceManager.ActivityListener() {
                    @Override
                    public void onTopActivityChanged(int displayId,
                            @NonNull ComponentName topActivity) {
                        if (topActivity.equals(intent.getComponent())) {
                            activityRunning.set(null);
                        }
                    }

                    @Override
                    public void onDisplayEmpty(int displayId) {
                    }
                };

        virtualDevice.addActivityListener(Runnable::run, activityListener);

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().getTargetContext()
                .startActivity(intent, createActivityOptions(targetDisplay));
        try {
            activityRunning.get(EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        virtualDevice.removeActivityListener(activityListener);
    }

    private void waitForNoToasts() {
        boolean observedToast = true;
        // The default timeout for mWmState.waitFor is a little low so we try a few times.
        for (int i = 0; i < 5; i++) {
            if (mWmState.waitFor(state -> state.getMatchingWindowType(
                    WindowManager.LayoutParams.TYPE_TOAST).isEmpty(), "No Toast appears")) {
                observedToast = false;
                break;
            }
        }
        assertThat(observedToast).isFalse();
    }

    private void waitForToastToAppearAndDisappear() {
        boolean observedToast = false;
        // The default timeout for mWmState.waitFor is a little low so we try a few times.
        for (int i = 0; i < 5; i++) {
            if (mWmState.waitFor(state -> !state.getMatchingWindowType(
                    WindowManager.LayoutParams.TYPE_TOAST).isEmpty(), "Toast appears")) {
                observedToast = true;
                break;
            }
        }
        assertThat(observedToast).isTrue();
        waitForNoToasts();
    }

    /**
     * Tap in the center of the given Display, to give focus to the top activity there.
     */
    private void tapOnDisplay(Display display) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        display.getRealMetrics(displayMetrics);
        final Point p = new Point(displayMetrics.widthPixels / 2, displayMetrics.heightPixels / 2);
        UiAutomation uiAutomation = getInstrumentation().getUiAutomation();

        // Sometimes the top activity on the display isn't quite ready to receive inputs and the
        // injected input event gets rejected, so we retry a few times before giving up.
        long maxTimeoutMs = 2 * EVENT_TIMEOUT_MS;
        Timeout timeout = new Timeout("tapOnDisplay", EVENT_TIMEOUT_MS, 2f, maxTimeoutMs);
        try {
            timeout.run("tap on display " + display.getDisplayId(), ()-> {
                final long downTime = SystemClock.elapsedRealtime();
                final MotionEvent downEvent = MotionEvent.obtain(downTime, downTime,
                        MotionEvent.ACTION_DOWN, p.x, p.y, 0 /* metaState */);
                downEvent.setDisplayId(display.getDisplayId());
                boolean downEventSuccess =
                        uiAutomation.injectInputEvent(downEvent, /* sync */ true);
                boolean upEventSuccess = false;
                if (downEventSuccess) {
                    final long upTime = SystemClock.elapsedRealtime();
                    final MotionEvent upEvent = MotionEvent.obtain(downTime, upTime,
                            MotionEvent.ACTION_UP, p.x, p.y, 0 /* metaState */);
                    upEvent.setDisplayId(display.getDisplayId());
                    upEventSuccess = uiAutomation.injectInputEvent(upEvent, /* sync */ true);
                }
                return (downEventSuccess && upEventSuccess) ? Boolean.TRUE : null;
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
