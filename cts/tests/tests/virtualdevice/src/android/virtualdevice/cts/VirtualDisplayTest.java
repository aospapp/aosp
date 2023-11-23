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
import static android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY;
import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.Manifest.permission.WAKE_LOCK;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.util.concurrent.Uninterruptibles.tryAcquireUninterruptibly;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.annotation.Nullable;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager.VirtualDevice;
import android.companion.virtual.VirtualDeviceParams;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.platform.test.annotations.AppModeFull;
import android.view.Display;
import android.virtualdevice.cts.common.FakeAssociationRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;
import com.android.internal.annotations.GuardedBy;

import com.google.common.base.Objects;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = " cannot be accessed by instant apps")
public class VirtualDisplayTest {
    private static final int DISPLAY_WIDTH = 640;
    private static final int DISPLAY_HEIGHT = 480;
    private static final int DISPLAY_DPI = 420;
    private static final int TIMEOUT_MILLIS = 1000;

    private static final VirtualDeviceParams DEFAULT_VIRTUAL_DEVICE_PARAMS =
            new VirtualDeviceParams.Builder().build();

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            ACTIVITY_EMBEDDING,
            ADD_ALWAYS_UNLOCKED_DISPLAY,
            ADD_TRUSTED_DISPLAY,
            CREATE_VIRTUAL_DEVICE,
            WAKE_LOCK);

    @Rule
    public FakeAssociationRule mFakeAssociationRule = new FakeAssociationRule();

    private VirtualDeviceManager mVirtualDeviceManager;
    private DisplayManager mDisplayManager;
    private DisplayListenerForTest mDisplayListener;
    @Nullable
    private VirtualDevice mVirtualDevice;
    @Mock
    private VirtualDisplay.Callback mVirtualDisplayCallback;

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
        mDisplayListener = new DisplayListenerForTest();
        mDisplayManager.registerDisplayListener(mDisplayListener, /*handler=*/null);
    }

    @After
    public void tearDown() {
        if (mVirtualDevice != null) {
            mVirtualDevice.close();
        }
        if (mDisplayManager != null && mDisplayListener != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
    }

    @Test
    public void createVirtualDisplay_shouldSucceed() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        VirtualDisplay virtualDisplay = mVirtualDevice.createVirtualDisplay(
                /* width= */ DISPLAY_WIDTH,
                /* height= */ DISPLAY_HEIGHT,
                /* densityDpi= */ DISPLAY_DPI,
                /* surface= */ null,
                /* flags= */ 0,
                Runnable::run,
                mVirtualDisplayCallback);
        mDisplayListener.waitForOnDisplayAddedCallback();

        assertThat(virtualDisplay).isNotNull();
        Display display = virtualDisplay.getDisplay();
        assertThat(display.isValid()).isTrue();
        assertThat(display.getWidth()).isEqualTo(DISPLAY_WIDTH);
        assertThat(display.getHeight()).isEqualTo(DISPLAY_HEIGHT);
        assertThat(mDisplayListener.getObservedAddedDisplays()).containsExactly(
                Integer.valueOf(display.getDisplayId()));
    }

    @Test
    public void createVirtualDisplay_alwaysUnlocked_shouldSpecifyFlagInVirtualDisplays() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        new VirtualDeviceParams.Builder()
                                .setLockState(VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED)
                                .build());

        VirtualDisplay virtualDisplay = mVirtualDevice.createVirtualDisplay(
                /* width= */ DISPLAY_WIDTH,
                /* height= */ DISPLAY_HEIGHT,
                /* densityDpi= */ DISPLAY_DPI,
                /* surface= */ null,
                /* flags= */ 0,
                Runnable::run,
                mVirtualDisplayCallback);
        mDisplayListener.waitForOnDisplayAddedCallback();

        assertThat(virtualDisplay).isNotNull();
        Display display = virtualDisplay.getDisplay();
        assertThat(display.isValid()).isTrue();
        assertThat(display.getWidth()).isEqualTo(DISPLAY_WIDTH);
        assertThat(display.getHeight()).isEqualTo(DISPLAY_HEIGHT);
        int displayFlags = display.getFlags();
        assertWithMessage(
                String.format(
                        "Virtual display flags (0x%x) should contain FLAG_ALWAYS_UNLOCKED",
                        displayFlags))
                .that(displayFlags & Display.FLAG_ALWAYS_UNLOCKED)
                .isNotEqualTo(0);
        assertThat(mDisplayListener.getObservedAddedDisplays()).containsExactly(
                display.getDisplayId());
    }

    @Test
    public void createVirtualDisplay_nullExecutorAndCallback_shouldSucceed() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        VirtualDisplay virtualDisplay = mVirtualDevice.createVirtualDisplay(
                /* width= */ DISPLAY_WIDTH,
                /* height= */ DISPLAY_HEIGHT,
                /* densityDpi= */ DISPLAY_DPI,
                /* surface= */ null,
                /* flags= */ 0,
                /* executor= */ null,
                /* callback= */ null);
        mDisplayListener.waitForOnDisplayAddedCallback();

        assertThat(virtualDisplay).isNotNull();
        Display display = virtualDisplay.getDisplay();
        assertThat(display.isValid()).isTrue();
        assertThat(display.getWidth()).isEqualTo(DISPLAY_WIDTH);
        assertThat(display.getHeight()).isEqualTo(DISPLAY_HEIGHT);
        assertThat(mDisplayListener.getObservedAddedDisplays()).containsExactly(
                Integer.valueOf(display.getDisplayId()));
    }

    @Test
    public void createVirtualDisplay_nullExecutorButNonNullCallback_shouldThrow() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);

        assertThrows(NullPointerException.class, () ->
                mVirtualDevice.createVirtualDisplay(
                        /* width= */ DISPLAY_WIDTH,
                        /* height= */ DISPLAY_HEIGHT,
                        /* densityDpi= */ DISPLAY_DPI,
                        /* surface= */ null,
                        /* flags= */ 0,
                        /* executor= */ null,
                        mVirtualDisplayCallback));
    }

    @Test
    public void virtualDisplay_createAndRemoveSeveralDisplays() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        ArrayList<VirtualDisplay> displays = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            displays.add(mVirtualDevice.createVirtualDisplay(
                    /* width= */ DISPLAY_WIDTH,
                    /* height= */ DISPLAY_HEIGHT,
                    /* densityDpi= */ DISPLAY_DPI,
                    /* surface= */ null,
                    /* flags= */ 0,
                    Runnable::run,
                    mVirtualDisplayCallback));
        }

        // Releasing several displays in quick succession should not cause deadlock
        displays.forEach(VirtualDisplay::release);

        mDisplayListener.waitForOnDisplayRemovedCallback(/*numDisplays=*/5);
        assertThat(mDisplayListener.getObservedRemovedDisplays()).containsExactlyElementsIn(
                getDisplayIds(displays));
    }

    @Test
    public void virtualDisplay_releasedWhenDeviceIsClosed() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        ArrayList<VirtualDisplay> displays = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            displays.add(mVirtualDevice.createVirtualDisplay(
                    /* width= */ DISPLAY_WIDTH,
                    /* height= */ DISPLAY_HEIGHT,
                    /* densityDpi= */ DISPLAY_DPI,
                    /* surface= */ null,
                    /* flags= */ 0,
                    Runnable::run,
                    mVirtualDisplayCallback));
        }

        // Closing the virtual device should automatically release displays.
        mVirtualDevice.close();

        mDisplayListener.waitForOnDisplayRemovedCallback(/*numDisplays=*/5);
        assertThat(mDisplayListener.getObservedRemovedDisplays()).containsExactlyElementsIn(
                getDisplayIds(displays));
        displays.forEach(display -> assertThat(display.getDisplay().isValid()).isFalse());
    }

    @Test
    public void virtualDisplay_releaseDisplaysAndCloseDevice() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        ArrayList<VirtualDisplay> displays = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            displays.add(mVirtualDevice.createVirtualDisplay(
                    /* width= */ DISPLAY_WIDTH,
                    /* height= */ DISPLAY_HEIGHT,
                    /* densityDpi= */ DISPLAY_DPI,
                    /* surface= */ null,
                    /* flags= */ 0,
                    Runnable::run,
                    mVirtualDisplayCallback));
        }

        // Releasing and closing the device should result in each display being released only once.
        displays.forEach(VirtualDisplay::release);
        mVirtualDevice.close();

        mDisplayListener.waitForOnDisplayRemovedCallback(/*numDisplays=*/5);
        assertThat(mDisplayListener.getObservedRemovedDisplays()).containsExactlyElementsIn(
                getDisplayIds(displays));
    }

    @Test
    public void getDeviceIdForDisplayId_returnsCorrectId() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        VirtualDisplay virtualDisplay = mVirtualDevice.createVirtualDisplay(
                /* width= */ DISPLAY_WIDTH,
                /* height= */ DISPLAY_HEIGHT,
                /* densityDpi= */ DISPLAY_DPI,
                /* surface= */ null,
                /* flags= */ 0,
                Runnable::run,
                mVirtualDisplayCallback);

        assertThat(mVirtualDeviceManager.getDeviceIdForDisplayId(
                virtualDisplay.getDisplay().getDisplayId())).isEqualTo(
                mVirtualDevice.getDeviceId());
    }

    @Test
    public void getDeviceIdForDisplayId_returnsDefaultIdForReleasedDisplay() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        VirtualDisplay virtualDisplay = mVirtualDevice.createVirtualDisplay(
                /* width= */ DISPLAY_WIDTH,
                /* height= */ DISPLAY_HEIGHT,
                /* densityDpi= */ DISPLAY_DPI,
                /* surface= */ null,
                /* flags= */ 0,
                Runnable::run,
                mVirtualDisplayCallback);
        virtualDisplay.release();
        mDisplayListener.waitForOnDisplayRemovedCallback();

        // When virtual display is released, the callback from display manager service to VDM
        // is dispatched asynchronously and after display listener callbacks are dispatched.
        // Therefore, even receiving onDisplayRemoved callback from display manager doesn't
        // guarantee that the display removal was already processed by VDM.
        // To test the proper removal but avoid flakiness, the assertion below polls for result
        // until it returns DEVICE_ID_DEFAULT (meaning the  display was properly disposed of).
        // In case the display is not released within the polling timeout, the pollForResult returns
        // last value from getDeviceIdForDisplayId call and the test will fail.
        assertThat(pollForResult(() -> mVirtualDeviceManager.getDeviceIdForDisplayId(
                        virtualDisplay.getDisplay().getDisplayId()),
                Context.DEVICE_ID_DEFAULT)).isEqualTo(
                Context.DEVICE_ID_DEFAULT);
    }

    @Test
    public void createAndRelease_isInvalidForReleasedDisplay() {
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        VirtualDisplay virtualDisplay = mVirtualDevice.createVirtualDisplay(
                /* width= */ DISPLAY_WIDTH,
                /* height= */ DISPLAY_HEIGHT,
                /* densityDpi= */ DISPLAY_DPI,
                /* surface= */ null,
                /* flags= */ 0,
                Runnable::run,
                mVirtualDisplayCallback);

        mVirtualDevice.close();
        mDisplayListener.waitForOnDisplayRemovedCallback();

        // Check whether display associated with virtual device is valid.
        Display display = virtualDisplay.getDisplay();
        assertThat(display.isValid()).isFalse();
    }

    private static List<Integer> getDisplayIds(Collection<VirtualDisplay> displays) {
        return displays.stream().map((display) -> display.getDisplay().getDisplayId()).collect(
                Collectors.toList());
    }

    /**
     * Polls for result of supplier until the returned value equals expected
     * value, or the timeout expires.
     *
     * @param supplier       Supplier to poll from
     * @param expectedResult expected result, when this result is returned, polling stops.
     * @param <T>            return type of supplier
     * @return last return value from supplier.
     */
    private static <T> T pollForResult(Supplier<T> supplier, T expectedResult) {
        final long pollingStartedTime = System.currentTimeMillis();
        T lastResult = supplier.get();
        while (!Objects.equal(lastResult, expectedResult)
                && (System.currentTimeMillis() - pollingStartedTime < TIMEOUT_MILLIS)) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException ie) {
                continue;
            }
            lastResult = supplier.get();
        }
        return lastResult;
    }


    private static class DisplayListenerForTest implements DisplayManager.DisplayListener {
        private final Semaphore mDisplayAddedSemaphore = new Semaphore(0);
        private final Semaphore mDisplayRemovedSemaphore = new Semaphore(0);
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private final ArrayList<Integer> mAddedDisplays = new ArrayList<>();
        @GuardedBy("mLock")
        private final ArrayList<Integer> mRemovedDisplays = new ArrayList<>();

        /**
         * Wait until {@code OnDisplayAddedCallback} is invoked at least numDisplays or timeout
         * expires.
         *
         * @param numDisplays - how many invocations of {@code OnDisplayAddedCallback} to wait for.
         * @return true iff the {@code OnDisplayAddedCallback} was invoked at least numDisplays
         * before
         * timeout expiration.
         */
        public boolean waitForOnDisplayAddedCallback(int numDisplays) {
            return tryAcquireUninterruptibly(mDisplayAddedSemaphore, numDisplays, TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
        }

        /**
         * Wait until {@code OnDisplayAddedCallback} is invoked once or timeout expires.
         *
         * @return true iff the {@code OnDisplayAddedCallback} was invoked at least once before
         * timeout expiration.
         */
        public boolean waitForOnDisplayAddedCallback() {
            return waitForOnDisplayAddedCallback(/*numDisplays=*/1);
        }


        /**
         * Wait until {@code OnDisplayRemovedCallback} is invoked at least numDisplays or timeout
         * expires.
         *
         * @param numDisplays - how many invocations of {@code OnDisplayRemovedCallback} to wait
         *                    for.
         * @return true iff the {@code OnDisplayRemovedCallback} was invoked at least numDisplays
         * before
         * timeout expiration.
         */
        public boolean waitForOnDisplayRemovedCallback(int numDisplays) {
            return tryAcquireUninterruptibly(mDisplayRemovedSemaphore, numDisplays, TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
        }

        /**
         * Wait until {@code OnDisplayRemovedCallback} is invoked once or timeout expires.
         *
         * @return true iff the {@code OnDisplayRemovedCallback} was invoked at least once before
         * timeout expiration.
         */
        public boolean waitForOnDisplayRemovedCallback() {
            return waitForOnDisplayRemovedCallback(/*numDisplays=*/1);
        }

        @Override
        public void onDisplayAdded(int displayId) {
            synchronized (mLock) {
                mAddedDisplays.add(displayId);
                mDisplayAddedSemaphore.release();
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mLock) {
                mRemovedDisplays.add(displayId);
                mDisplayRemovedSemaphore.release();
            }
        }


        @Override
        public void onDisplayChanged(int displayId) {
            //Do nothing.
        }

        /**
         * Get list of recently added display ids.
         *
         * @return List of recently added display ids.
         */
        ArrayList<Integer> getObservedAddedDisplays() {
            synchronized (mDisplayAddedSemaphore) {
                return new ArrayList<>(mAddedDisplays);
            }
        }

        /**
         * Get list of recently removed display ids.
         *
         * @return List of recently removed display ids.
         */
        ArrayList<Integer> getObservedRemovedDisplays() {
            synchronized (mDisplayRemovedSemaphore) {
                return new ArrayList<>(mRemovedDisplays);
            }
        }
    }
}
