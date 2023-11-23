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

package android.app.cts.wallpapers;

import static android.Manifest.permission.READ_WALLPAPER_INTERNAL;
import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;
import static android.app.cts.wallpapers.WallpaperManagerTestUtils.TWO_DIFFERENT_LIVE_WALLPAPERS;
import static android.app.cts.wallpapers.WallpaperManagerTestUtils.TWO_SAME_LIVE_WALLPAPERS;
import static android.app.cts.wallpapers.WallpaperManagerTestUtils.WallpaperChange;
import static android.app.cts.wallpapers.WallpaperManagerTestUtils.WallpaperState;
import static android.app.cts.wallpapers.util.WallpaperTestUtils.isSimilar;
import static android.content.pm.PackageManager.FEATURE_LIVE_WALLPAPER;
import static android.content.pm.PackageManager.FEATURE_SECURE_LOCK_SCREEN;
import static android.opengl.cts.Egl14Utils.getMaxTextureSize;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.server.wm.WindowManagerStateHelper;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.CtsTouchUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for {@link WallpaperManager} and related classes.
 * <p>
 * Note: the wallpapers {@link TestLiveWallpaper}, {@link TestLiveWallpaperNoUnfoldTransition},
 * {@link TestLiveWallpaperSupportingAmbientMode} draw the screen in
 * cyan, magenta, yellow, respectively.
 * </p>
 */
@RunWith(AndroidJUnit4.class)
public class WallpaperManagerTest {

    private static final boolean DEBUG = false;
    private static final String TAG = "WallpaperManagerTest";
    private static final ComponentName DEFAULT_COMPONENT_NAME = new ComponentName(
            TestLiveWallpaper.class.getPackageName(), TestLiveWallpaper.class.getName());
    // Default wait time for async operations
    private static final int SLEEP_MS = 500;
    private static final int DIM_LISTENER_TIMEOUT_SECS = 30;

    private WallpaperManager mWallpaperManager;
    private Context mContext;
    private CtsTouchUtils mCtsTouchUtils;
    private Handler mHandler;
    private BroadcastReceiver mBroadcastReceiver;
    private CountDownLatch mCountDownLatch;
    private boolean mEnableWcg;
    private static final WindowManagerStateHelper sWindowManagerStateHelper =
            new WindowManagerStateHelper();

    @Rule
    public ActivityTestRule<WallpaperTestActivity> mActivityTestRule = new ActivityTestRule<>(
            WallpaperTestActivity.class,
            false /* initialTouchMode */,
            false /* launchActivity */);

    @Before
    public void setUp() throws Exception {

        // grant READ_WALLPAPER_INTERNAL for all tests
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(READ_WALLPAPER_INTERNAL);

        mContext = InstrumentationRegistry.getTargetContext();
        WallpaperWindowsTestUtils.setContext(mContext);
        mCtsTouchUtils = new CtsTouchUtils(mContext);
        mWallpaperManager = WallpaperManager.getInstance(mContext);
        assumeTrue("Device does not support wallpapers", mWallpaperManager.isWallpaperSupported());

        MockitoAnnotations.initMocks(this);
        final HandlerThread handlerThread = new HandlerThread("TestCallbacks");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mCountDownLatch = new CountDownLatch(1);
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCountDownLatch.countDown();
                if (DEBUG) {
                    Log.d(TAG, "broadcast state count down: " + mCountDownLatch.getCount());
                }
            }
        };
        mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
        mEnableWcg = mWallpaperManager.shouldEnableWideColorGamut();
        mWallpaperManager.clear(FLAG_SYSTEM | FLAG_LOCK);

        assertWithMessage("Home screen wallpaper must be set after setUp()").that(
                mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isAtLeast(0);
        assertWithMessage("Lock screen wallpaper must be unset after setUp()").that(
                mWallpaperManager.getWallpaperId(FLAG_LOCK)).isLessThan(0);

        TestWallpaperService.Companion.resetCounts();
    }

    @After
    public void tearDown() throws Exception {

        // drop READ_WALLPAPER_INTERNAL
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();

        if (mBroadcastReceiver != null) {
            mContext.unregisterReceiver(mBroadcastReceiver);
        }
        TestWallpaperService.Companion.checkAssertions();
        TestWallpaperService.Companion.resetCounts();
        mWallpaperManager.clear(FLAG_SYSTEM | FLAG_LOCK);
    }

    @Test
    public void setBitmap_homeScreen_homeStatic_lockScreenUnset_setsLockToHomeAndUpdatesHome()
            throws IOException {
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.RED);

        try {
            int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
            mWallpaperManager.setBitmap(tmpWallpaper, /* visibleCropHint= */
                    null, /* allowBackup= */true, FLAG_SYSTEM);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                    origHomeWallpaperId);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origHomeWallpaperId);
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    public void setBitmap_homeScreen_homeLive_lockScreenUnset_setsLockToHomeAndUpdatesHome()
            throws IOException {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_SYSTEM | FLAG_LOCK);
        });
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.RED);

        try {
            int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
            mWallpaperManager.setBitmap(tmpWallpaper, /* visibleCropHint= */
                    null, /* allowBackup= */true, FLAG_SYSTEM);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                    origHomeWallpaperId);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origHomeWallpaperId);
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    public void setBitmap_homeScreen_lockScreenSet_singleEngine_changesHomeOnly()
            throws IOException {
        assumeFalse(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.GREEN);

        try {
            mWallpaperManager.setBitmap(tmpWallpaper, null, true, FLAG_LOCK);
            int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
            int origLockWallpaperId = mWallpaperManager.getWallpaperId(FLAG_LOCK);
            canvas.drawColor(Color.RED);
            mWallpaperManager.setBitmap(tmpWallpaper, /* visibleCropHint= */
                    null, /* allowBackup= */true, FLAG_SYSTEM);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                    origHomeWallpaperId);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origLockWallpaperId);
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    public void setBitmap_lockScreen_lockScreenUnset_changesLockOnly() throws IOException {
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.RED);

        try {
            int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
            mWallpaperManager.setBitmap(tmpWallpaper, /* visibleCropHint= */
                    null, /* allowBackup= */true, FLAG_LOCK);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isEqualTo(
                    origHomeWallpaperId);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isAtLeast(0);
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    public void setBitmap_lockScreen_lockScreenSet_changesLockOnly() throws IOException {
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.GREEN);

        try {
            mWallpaperManager.setBitmap(tmpWallpaper, null, true, FLAG_LOCK);
            int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
            int origLockWallpaperId = mWallpaperManager.getWallpaperId(FLAG_LOCK);
            canvas.drawColor(Color.RED);
            mWallpaperManager.setBitmap(tmpWallpaper, /* visibleCropHint= */
                    null, /* allowBackup= */true, FLAG_LOCK);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isEqualTo(
                    origHomeWallpaperId);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isNotEqualTo(
                    origLockWallpaperId);
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    public void setBitmap_both_lockScreenUnset_changesHome() throws IOException {
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.RED);

        try {
            int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
            mWallpaperManager.setBitmap(tmpWallpaper, /* visibleCropHint= */
                    null, /* allowBackup= */true, FLAG_SYSTEM | FLAG_LOCK);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                    origHomeWallpaperId);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isLessThan(0);
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    public void setBitmap_both_lockScreenSet_changesHomeAndClearsLock() throws IOException {
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.GREEN);

        try {
            mWallpaperManager.setBitmap(tmpWallpaper, null, true, FLAG_LOCK);
            int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
            int origLockWallpaperId = mWallpaperManager.getWallpaperId(FLAG_LOCK);
            canvas.drawColor(Color.RED);
            mWallpaperManager.setBitmap(tmpWallpaper, /* visibleCropHint= */
                    null, /* allowBackup= */true, FLAG_SYSTEM | FLAG_LOCK);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                    origHomeWallpaperId);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isLessThan(0);
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    public void setBitmap_default_lockScreenUnset_sameAsBoth() throws IOException {
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.RED);

        try {
            int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
            mWallpaperManager.setBitmap(tmpWallpaper);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                    origHomeWallpaperId);
            assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isLessThan(0);
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    public void setResource_homeScreen_homeStatic_lockScreenUnset_setsLockToHomeAndUpdatesHome()
            throws IOException {
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origHomeWallpaperId);
    }

    @Test
    public void setResource_homeScreen_homeLive_lockScreenUnset_setsLockToHomeAndUpdatesHome()
            throws IOException {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_SYSTEM | FLAG_LOCK);
        });
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);

        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origHomeWallpaperId);
    }

    @Test
    public void setResource_homeScreen_lockScreenSet_changesHomeOnly() throws IOException {
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        int origLockWallpaperId = mWallpaperManager.getWallpaperId(FLAG_LOCK);
        mWallpaperManager.setResource(R.drawable.icon_green, FLAG_SYSTEM);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origLockWallpaperId);
    }

    @Test
    public void setResource_lockScreen_lockScreenUnset_changesLockOnly() throws IOException {
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isEqualTo(
                origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isAtLeast(0);
    }

    @Test
    public void setResource_lockScreen_lockScreenSet_changesLockOnly() throws IOException {
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        int origLockWallpaperId = mWallpaperManager.getWallpaperId(FLAG_LOCK);
        mWallpaperManager.setResource(R.drawable.icon_green, FLAG_LOCK);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isEqualTo(
                origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isNotEqualTo(
                origLockWallpaperId);
    }

    @Test
    public void setResource_both_lockScreenUnset_changesHome() throws IOException {
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM | FLAG_LOCK);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isLessThan(0);
    }

    @Test
    public void setResource_both_lockScreenSet_changesHomeAndClearsLock() throws IOException {
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        mWallpaperManager.setResource(R.drawable.icon_green, FLAG_SYSTEM | FLAG_LOCK);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isLessThan(0);
    }

    // This is just to be sure that setResource call the overload with `which`.
    @Test
    public void setResource_default_lockScreenUnset_sameAsBoth() throws IOException {
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        mWallpaperManager.setResource(R.drawable.icon_red);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(
                origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isLessThan(0);
    }

    @Test
    public void setWallpaperComponent_homeScreen_homeStatic_lockScreenUnset_migratesThenSetsHome() {
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_SYSTEM);
        });

        assertWithMessage("System wallpaper must change").that(
                mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertWithMessage("Lock wallpaper mush not change").that(
                mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origHomeWallpaperId);
    }

    @Test
    public void setWallpaperComponent_homeScreen_homeLive_lockScreenUnset_migratesThenSetsHome() {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_SYSTEM | FLAG_LOCK);
        });
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);

        runWithShellPermissionIdentity(() -> {
            ComponentName newComponentName = new ComponentName(
                    TestLiveWallpaperNoUnfoldTransition.class.getPackageName(),
                    TestLiveWallpaperNoUnfoldTransition.class.getName());
            setWallpaperComponentAndWait(newComponentName, FLAG_SYSTEM);
        });

        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origHomeWallpaperId);
    }

    @Test
    public void setWallpaperComponent_homeScreen_lockScreenSet_changesHomeOnly()
            throws IOException {
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        int origLockWallpaperId = mWallpaperManager.getWallpaperId(FLAG_LOCK);
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_SYSTEM);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origLockWallpaperId);
    }

    @Test
    public void setWallpaperComponent_lockScreen_singleEngine_lockScreenUnset_sameAsHomeScreen() {
        assumeFalse(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_LOCK);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origHomeWallpaperId);
    }

    @Test
    public void setWallpaperComponent_lockScreen_multiEngine_lockScreenUnset_changesLockOnly() {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_LOCK);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isAtLeast(0);
    }

    @Test
    public void setWallpaperComponent_lockScreen_singleEngine_lockScreenSet_behavesLikeHomeScreen()
            throws IOException {
        assumeFalse(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        int origLockWallpaperId = mWallpaperManager.getWallpaperId(FLAG_LOCK);
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_LOCK);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origLockWallpaperId);
    }

    @Test
    public void setWallpaperComponent_lockScreen_multiEngine_lockScreenSet_changeLockOnly()
            throws IOException {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        int origLockWallpaperId = mWallpaperManager.getWallpaperId(FLAG_LOCK);
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_LOCK);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isNotEqualTo(origLockWallpaperId);
    }

    @Test
    public void setWallpaperComponent_both_singleEngine_lockScreenUnset_behavesLikeHomeScreen() {
        assumeFalse(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_SYSTEM | FLAG_LOCK);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origHomeWallpaperId);
    }

    @Test
    public void setWallpaperComponent_both_multiEngine_lockScreenUnset_setsHomeToBoth() {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_SYSTEM | FLAG_LOCK);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isLessThan(0);
    }

    @Test
    public void setWallpaperComponent_both_singleEngine_lockScreenSet_behavesLikeHomeScreen()
            throws IOException {
        assumeFalse(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        int origLockWallpaperId = mWallpaperManager.getWallpaperId(FLAG_LOCK);
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_SYSTEM | FLAG_LOCK);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origLockWallpaperId);
    }

    @Test
    public void setWallpaperComponent_both_multiEngine_lockScreenSet_changesLockOnly()
            throws IOException {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_SYSTEM | FLAG_LOCK);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isLessThan(0);
    }

    @Test
    public void setWallpaperComponent_default_singleEngine_lockScreenUnset_behavesLikeHomeScreen() {
        assumeFalse(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        runWithShellPermissionIdentity(() -> {
            mWallpaperManager.setWallpaperComponent(DEFAULT_COMPONENT_NAME);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isEqualTo(origHomeWallpaperId);
    }

    @Test
    public void setWallpaperComponent_default_multiEngine_lockScreenUnset_behavesLikeBoth() {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        int origHomeWallpaperId = mWallpaperManager.getWallpaperId(FLAG_SYSTEM);
        runWithShellPermissionIdentity(() -> {
            mWallpaperManager.setWallpaperComponent(DEFAULT_COMPONENT_NAME);
        });
        assertThat(mWallpaperManager.getWallpaperId(FLAG_SYSTEM)).isNotEqualTo(origHomeWallpaperId);
        assertThat(mWallpaperManager.getWallpaperId(FLAG_LOCK)).isLessThan(0);
    }

    @Test
    public void setStaticWallpaper_doesNotSetWallpaperInfo() throws IOException {
        assertThat(mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM)).isNull();
        assertThat(mWallpaperManager.getWallpaperInfo(FLAG_LOCK)).isNull();

        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);
        mWallpaperManager.setResource(R.drawable.icon_green, FLAG_LOCK);

        assertThat(mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM)).isNull();
        assertThat(mWallpaperManager.getWallpaperInfo(FLAG_LOCK)).isNull();
    }

    @Test
    public void setLiveWallpaper_homeScreen_setsHomeWallpaperInfo() {
        assertThat(mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM)).isNull();
        assertThat(mWallpaperManager.getWallpaperInfo(FLAG_LOCK)).isNull();

        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_SYSTEM);
        });

        assertWithMessage("Home screen").that(
                mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM)).isNotNull();
        assertWithMessage("Lock screen").that(
                mWallpaperManager.getWallpaperInfo(FLAG_LOCK)).isNull();
    }

    @Test
    public void setLiveWallpaper_lockScreen_singleEngine_setsHomeWallpaperInfo() {
        assumeFalse(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        assertThat(mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM)).isNull();
        assertThat(mWallpaperManager.getWallpaperInfo(FLAG_LOCK)).isNull();

        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_LOCK);
        });

        assertWithMessage("Home screen").that(
                mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM)).isNotNull();
        assertWithMessage("Lock screen").that(
                mWallpaperManager.getWallpaperInfo(FLAG_LOCK)).isNull();
    }

    @Test
    public void setLiveWallpaper_lockScreen_multiEngine_setsLockWallpaperInfo() {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        assertThat(mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM)).isNull();
        assertThat(mWallpaperManager.getWallpaperInfo(FLAG_LOCK)).isNull();

        runWithShellPermissionIdentity(() -> {
            setWallpaperComponentAndWait(DEFAULT_COMPONENT_NAME, FLAG_LOCK);
        });

        assertWithMessage("Home screen").that(
                mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM)).isNull();
        assertWithMessage("Lock screen").that(
                mWallpaperManager.getWallpaperInfo(FLAG_LOCK)).isNotNull();
    }

    @Test
    public void getWallpaperInfo_badFlagsArgument_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM | FLAG_LOCK));
    }

    @Test
    public void wallpaperChangedBroadcastTest() {
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.BLACK);

        try {
            mWallpaperManager.setBitmap(tmpWallpaper);

            // Wait for up to 5 sec since this is an async call.
            // Should fail if Intent.ACTION_WALLPAPER_CHANGED isn't delivered.
            assertWithMessage("Timed out waiting for Intent").that(
                    mCountDownLatch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException | IOException e) {
            throw new AssertionError("Intent.ACTION_WALLPAPER_CHANGED not received.");
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    public void wallpaperClearBroadcastTest() {
        try {
            mWallpaperManager.clear(FLAG_LOCK | FLAG_SYSTEM);

            // Wait for 5 sec since this is an async call.
            // Should fail if Intent.ACTION_WALLPAPER_CHANGED isn't delivered.
            assertWithMessage("Timed out waiting for Intent").that(
                    mCountDownLatch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException | IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void invokeOnColorsChangedListenerTest_systemOnly() {
        int both = FLAG_LOCK | FLAG_SYSTEM;
        // Expect both since the first step is to migrate the current wallpaper
        // to the lock screen.
        verifyColorListenerInvoked(FLAG_SYSTEM, both);
    }

    @Test
    public void invokeOnColorsChangedListenerTest_lockOnly() {
        verifyColorListenerInvoked(FLAG_LOCK, FLAG_LOCK);
    }

    @Test
    public void invokeOnColorsChangedListenerTest_both() {
        int both = FLAG_LOCK | FLAG_SYSTEM;
        verifyColorListenerInvoked(both, both);
    }

    @Test
    public void invokeOnColorsChangedListenerTest_clearLock() throws IOException {
        verifyColorListenerInvokedClearing(FLAG_LOCK);
    }

    @Test
    public void invokeOnColorsChangedListenerTest_clearSystem() throws IOException {
        verifyColorListenerInvokedClearing(FLAG_SYSTEM);
    }

    /**
     * Removing a listener should not invoke it anymore
     */
    @Test
    public void addRemoveOnColorsChangedListenerTest_onlyInvokeAdded() throws IOException {
        ensureCleanState();

        final CountDownLatch latch = new CountDownLatch(1);
        WallpaperManager.OnColorsChangedListener counter = (colors, whichWp) -> latch.countDown();

        // Add and remove listener
        WallpaperManager.OnColorsChangedListener listener = getTestableListener();
        mWallpaperManager.addOnColorsChangedListener(listener, mHandler);
        mWallpaperManager.removeOnColorsChangedListener(listener);

        // Verify that the listener is not called
        mWallpaperManager.addOnColorsChangedListener(counter, mHandler);
        try {
            mWallpaperManager.setResource(R.drawable.icon_red);
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Registered listener not invoked");
            }
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
        verify(listener, never()).onColorsChanged(any(WallpaperColors.class), anyInt());
        mWallpaperManager.removeOnColorsChangedListener(counter);
    }

    /**
     * Suggesting desired dimensions is only a hint to the system that can be ignored.
     *
     * Test if the desired minimum width or height the WallpaperManager returns
     * is greater than 0. If so, then we check whether that the size is the dimension
     * that was suggested.
     */
    @Test
    public void suggestDesiredDimensionsTest() {
        final Point min = getScreenSize();
        int w = min.x * 3;
        int h = min.y * 2;

        // b/120847476: WallpaperManager limits at GL_MAX_TEXTURE_SIZE
        final int max = getMaxTextureSize();
        if (max > 0) {
            w = Math.min(w, max);
            h = Math.min(h, max);
        }

        assertDesiredDimension(new Point(min.x / 2, min.y / 2), new Point(min.x / 2, min.y / 2));

        assertDesiredDimension(new Point(w, h), new Point(w, h));

        assertDesiredDimension(new Point(min.x / 2, h), new Point(min.x / 2, h));

        assertDesiredDimension(new Point(w, min.y / 2), new Point(w, min.y / 2));
    }

    @Test
    @Ignore("b/265007420")
    public void wallpaperColors_primary() {
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.RED);

        try {
            mWallpaperManager.setBitmap(tmpWallpaper);
            WallpaperColors colors = mWallpaperManager.getWallpaperColors(
                    FLAG_SYSTEM);

            // Check that primary color is almost red
            Color primary = colors.getPrimaryColor();
            final float delta = 0.1f;
            assertWithMessage("red").that(primary.red()).isWithin(delta).of(1f);
            assertWithMessage("green").that(primary.green()).isWithin(delta).of(0f);
            assertWithMessage("blue").that(primary.blue()).isWithin(delta).of(0f);

            assertThat(colors.getSecondaryColor()).isNull();
            assertThat(colors.getTertiaryColor()).isNull();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    @Ignore("b/265007420")
    public void wallpaperColors_secondary() {
        Bitmap tmpWallpaper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tmpWallpaper);
        canvas.drawColor(Color.RED);
        // Make 20% of the wallpaper BLUE so that secondary color is BLUE
        canvas.clipRect(0, 0, 100, 20);
        canvas.drawColor(Color.BLUE);

        try {
            mWallpaperManager.setBitmap(tmpWallpaper);
            WallpaperColors colors = mWallpaperManager.getWallpaperColors(
                    FLAG_SYSTEM);

            // Check that the secondary color is almost blue
            Color secondary = colors.getSecondaryColor();
            final float delta = 0.15f;
            assertWithMessage("red").that(secondary.red()).isWithin(delta).of(0f);
            assertWithMessage("green").that(secondary.green()).isWithin(delta).of(0f);
            assertWithMessage("blue").that(secondary.blue()).isWithin(delta).of(1f);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            tmpWallpaper.recycle();
        }
    }

    @Test
    public void highRatioWallpaper_largeWidth() throws Exception {
        Bitmap highRatioWallpaper = Bitmap.createBitmap(8000, 800, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(highRatioWallpaper);
        canvas.drawColor(Color.RED);

        try {
            mWallpaperManager.setBitmap(highRatioWallpaper);
            assertBitmapDimensions(mWallpaperManager.getBitmap());
        } finally {
            highRatioWallpaper.recycle();
        }
    }

    @Test
    public void highRatioWallpaper_largeHeight() throws Exception {
        Bitmap highRatioWallpaper = Bitmap.createBitmap(800, 8000, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(highRatioWallpaper);
        canvas.drawColor(Color.RED);

        try {
            mWallpaperManager.setBitmap(highRatioWallpaper);
            assertBitmapDimensions(mWallpaperManager.getBitmap());
        } finally {
            highRatioWallpaper.recycle();
        }
    }

    @Test
    public void highResolutionWallpaper() throws Exception {
        Bitmap highResolutionWallpaper = Bitmap.createBitmap(10000, 10000, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(highResolutionWallpaper);
        canvas.drawColor(Color.BLUE);

        try {
            mWallpaperManager.setBitmap(highResolutionWallpaper);
            assertBitmapDimensions(mWallpaperManager.getBitmap());
        } finally {
            highResolutionWallpaper.recycle();
        }
    }

    @Test
    public void testWideGamutWallpaper() throws IOException {
        final ColorSpace srgb = ColorSpace.get(ColorSpace.Named.SRGB);
        final ColorSpace p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
        final Bitmap.Config config = Bitmap.Config.ARGB_8888;
        final Bitmap srgbBitmap = Bitmap.createBitmap(100, 100, config);
        final Bitmap p3Bitmap = Bitmap.createBitmap(100, 100, config, false, p3);

        try {
            // sRGB is the default color space
            mWallpaperManager.setBitmap(srgbBitmap);
            assertThat(mWallpaperManager.getBitmap().getColorSpace()).isEqualTo(srgb);

            // If wide gamut is enabled, Display-P3 should be supported.
            mWallpaperManager.setBitmap(p3Bitmap);

            final boolean isDisplayP3 = mWallpaperManager.getBitmap().getColorSpace().equals(p3);
            // Assert false only when device enabled WCG, but display does not support Display-P3
            assertThat(mEnableWcg && !isDisplayP3).isFalse();
        } finally {
            srgbBitmap.recycle();
            p3Bitmap.recycle();
        }
    }

    @Test
    public void testWallpaperSupportsWcg() throws IOException {
        final int sysWallpaper = FLAG_SYSTEM;

        final Bitmap srgbBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        final Bitmap p3Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888, false,
                ColorSpace.get(ColorSpace.Named.DISPLAY_P3));

        try {
            mWallpaperManager.setBitmap(srgbBitmap);
            assertThat(mWallpaperManager.wallpaperSupportsWcg(sysWallpaper)).isFalse();

            mWallpaperManager.setBitmap(p3Bitmap);
            assertThat(mWallpaperManager.wallpaperSupportsWcg(sysWallpaper)).isEqualTo(mEnableWcg);
        } finally {
            srgbBitmap.recycle();
            p3Bitmap.recycle();
        }
    }

    /**
     * Check that all the callback methods of the wallpaper are invoked by the same thread.
     * Also checks that the callback methods are called in a proper order.
     * See {@link TestWallpaperService} to see the checks that are performed.
     */
    @Test
    public void wallpaperCallbackMainThreadTest() {

        // use a wallpaper supporting ambient mode, to trigger Engine.onAmbientModeChanged
        ComponentName componentName = new ComponentName(
                TestLiveWallpaperSupportingAmbientMode.class.getPackageName(),
                TestLiveWallpaperSupportingAmbientMode.class.getName());
        runWithShellPermissionIdentity(() ->
                mWallpaperManager.setWallpaperComponent(componentName));

        // trigger Engine.onDesiredDimensionsChanged
        mWallpaperManager.suggestDesiredDimensions(1000, 1000);

        Activity activity = mActivityTestRule.launchActivity(null);

        Window window = activity.getWindow();
        IBinder windowToken = window.getDecorView().getWindowToken();

        // send some command to trigger Engine.onCommand
        mWallpaperManager.sendWallpaperCommand(
                windowToken, WallpaperManager.COMMAND_TAP, 50, 50, 0, null);

        // trigger Engine.onZoomChanged
        mWallpaperManager.setWallpaperZoomOut(windowToken, 0.5f);

        // trigger Engine.onTouchEvent
        mCtsTouchUtils.emulateTapOnViewCenter(
                InstrumentationRegistry.getInstrumentation(), null,
                activity.findViewById(android.R.id.content));

        mActivityTestRule.finishActivity();
        runWithShellPermissionIdentity(() -> mWallpaperManager.clearWallpaper());
    }

    @Test
    public void peekWallpaperCaching_cachesWallpaper() throws IOException {
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        // Get the current bitmap, and check that the second call returns the cached bitmap
        Bitmap bitmap1 = mWallpaperManager.getBitmapAsUser(mContext.getUserId(),
                false /* hardware */, FLAG_SYSTEM);
        assertThat(bitmap1).isNotNull();
        assertThat(mWallpaperManager.getBitmapAsUser(mContext.getUserId(), false /* hardware */,
                FLAG_SYSTEM)).isSameInstanceAs(bitmap1);

        // Change the wallpaper to invalidate the cached bitmap
        mWallpaperManager.setResource(R.drawable.icon_green, FLAG_SYSTEM);

        // Get the new bitmap, and check that the second call returns the newly cached bitmap
        Bitmap bitmap2 = mWallpaperManager.getBitmapAsUser(mContext.getUserId(),
                false /* hardware */, FLAG_SYSTEM);
        assertThat(bitmap2).isNotSameInstanceAs(bitmap1);
        assertThat(mWallpaperManager.getBitmapAsUser(mContext.getUserId(), false /* hardware */,
                FLAG_SYSTEM)).isSameInstanceAs(bitmap2);
    }

    @Test
    public void peekWallpaperCaching_differentWhich_doesNotReturnCached() throws IOException {
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);
        mWallpaperManager.setResource(R.drawable.icon_green, FLAG_LOCK);

        Bitmap bitmapSystem = mWallpaperManager.getBitmapAsUser(mContext.getUserId(),
                false /* hardware */, FLAG_SYSTEM);
        Bitmap bitmapLock = mWallpaperManager.getBitmapAsUser(mContext.getUserId(),
                false /* hardware */, FLAG_LOCK);
        assertThat(bitmapLock).isNotSameInstanceAs(bitmapSystem);

    }

    @Test
    public void peekWallpaperCaching_bitmapRecycled_doesNotReturnCached() throws IOException {
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        Bitmap bitmap = mWallpaperManager.getBitmapAsUser(mContext.getUserId(),
                false /* hardware */, FLAG_SYSTEM);
        assertThat(bitmap).isNotNull();
        bitmap.recycle();
        assertThat(mWallpaperManager.getBitmapAsUser(mContext.getUserId(), false /* hardware */,
                FLAG_SYSTEM)).isNotSameInstanceAs(bitmap);
    }

    @Test
    public void peekWallpaperCaching_differentUser_doesNotReturnCached() throws IOException {
        final int bogusUserId = -1;
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        Bitmap bitmap = mWallpaperManager.getBitmapAsUser(mContext.getUserId(),
                false /* hardware */, FLAG_SYSTEM);
        assertThat(bitmap).isNotNull();

        // If the cached bitmap was determined to be invalid, this leads to a call to
        // WallpaperManager.Globals#getCurrentWallpaperLocked() for a different user, which
        // generates a security exception: the exception indicates that the cached bitmap was
        // invalid, which is the desired result.
        assertThrows(SecurityException.class,
                () -> mWallpaperManager.getBitmapAsUser(bogusUserId, false /* hardware */,
                        FLAG_SYSTEM));
    }

    @Test
    public void peekWallpaperDimensions_homeScreen_succeeds() throws IOException {
        final int width = 100;
        final int height = 200;
        final Rect expectedSize = new Rect(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.RED);
        mWallpaperManager.setBitmap(bitmap);

        Rect actualSize = mWallpaperManager.peekBitmapDimensions();

        assertThat(actualSize).isEqualTo(expectedSize);
    }

    @Test
    public void peekWallpaperDimensions_lockScreenUnset_succeeds() {
        Rect actualSize = mWallpaperManager.peekBitmapDimensions(FLAG_LOCK);

        assertThat(actualSize).isNull();
    }

    @Test
    public void peekWallpaperDimensions_lockScreenSet_succeeds() throws IOException {
        Bitmap homeBitmap = Bitmap.createBitmap(150 /* width */, 150 /* width */,
                Bitmap.Config.ARGB_8888);
        Canvas homeCanvas = new Canvas(homeBitmap);
        homeCanvas.drawColor(Color.RED);
        mWallpaperManager.setBitmap(homeBitmap, /* visibleCropHint */ null, /* allowBackup */true,
                FLAG_SYSTEM);
        final int width = 100;
        final int height = 200;
        final Rect expectedSize = new Rect(0, 0, width, height);
        Bitmap lockBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas lockCanvas = new Canvas(lockBitmap);
        lockCanvas.drawColor(Color.RED);
        mWallpaperManager.setBitmap(lockBitmap, /* visibleCropHint */ null, /* allowBackup */true,
                FLAG_LOCK);

        Rect actualSize = mWallpaperManager.peekBitmapDimensions(FLAG_LOCK);

        assertThat(actualSize).isEqualTo(expectedSize);
    }

    @Test
    public void getDrawable_homeScreen_succeeds() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        Drawable actual = mWallpaperManager.getDrawable(FLAG_SYSTEM);

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void getDrawable_lockScreenUnset_returnsNull() {
        Drawable actual = mWallpaperManager.getDrawable(FLAG_LOCK);

        assertThat(actual).isNull();
    }

    @Test
    public void getDrawable_lockScreenSet_succeeds() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);

        Drawable actual = mWallpaperManager.getDrawable(FLAG_LOCK);

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void getDrawable_default_sameAsHome() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        Drawable actual = mWallpaperManager.getDrawable();

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void getFastDrawable_homeScreen_succeeds() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        Drawable actual = mWallpaperManager.getFastDrawable(FLAG_SYSTEM);

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void getFastDrawable_lockScreenUnset_returnsNull() {
        Drawable actual = mWallpaperManager.getFastDrawable(FLAG_LOCK);

        assertThat(actual).isNull();
    }

    @Test
    public void getFastDrawable_lockScreenSet_succeeds() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);

        Drawable actual = mWallpaperManager.getFastDrawable(FLAG_LOCK);

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void getFastDrawable_default_sameAsHome() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        Drawable actual = mWallpaperManager.getFastDrawable();

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void peekDrawable_homeScreen_succeeds() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        Drawable actual = mWallpaperManager.peekDrawable(FLAG_SYSTEM);

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void peekDrawable_lockScreenUnset_returnsNull() {
        Drawable actual = mWallpaperManager.peekDrawable(FLAG_LOCK);

        assertThat(actual).isNull();
    }

    @Test
    public void peekDrawable_lockScreenSet_succeeds() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);

        Drawable actual = mWallpaperManager.peekDrawable(FLAG_LOCK);

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void peekDrawable_default_sameAsHome() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        Drawable actual = mWallpaperManager.peekDrawable();

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void peekFastDrawable_homeScreen_succeeds() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        Drawable actual = mWallpaperManager.peekFastDrawable(FLAG_SYSTEM);

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void peekFastDrawable_lockScreenUnset_returnsNull() {
        Drawable actual = mWallpaperManager.peekFastDrawable(FLAG_LOCK);

        assertThat(actual).isNull();
    }

    @Test
    public void peekFastDrawable_lockScreenSet_succeeds() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_LOCK);

        Drawable actual = mWallpaperManager.peekFastDrawable(FLAG_LOCK);

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    @Test
    public void peekFastDrawable_default_sameAsHome() throws IOException {
        Drawable expected = mContext.getDrawable(R.drawable.icon_red);
        mWallpaperManager.setResource(R.drawable.icon_red, FLAG_SYSTEM);

        Drawable actual = mWallpaperManager.peekFastDrawable();

        assertWithMessage("Drawables must represent the same image").that(
                isSimilar(actual, expected, true)).isTrue();
    }

    /**
     * For every possible (state, change) couple, checks that the number of times
     * {@link TestWallpaperService.FakeEngine#onDestroy} and
     * {@link TestWallpaperService.FakeEngine#onCreate} are called is correct.
     */
    @Test
    public void testEngineCallbackCounts() throws IOException {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        ArrayList<String> errorMessages = new ArrayList<>();
        runWithShellPermissionIdentity(() -> {
            for (WallpaperState state : WallpaperManagerTestUtils.allPossibleStates()) {

                for (WallpaperChange change: state.allPossibleChanges()) {
                    WallpaperManagerTestUtils.goToState(mWallpaperManager, state);
                    TestWallpaperService.Companion.resetCounts();
                    WallpaperManagerTestUtils.performChange(mWallpaperManager, change);

                    int expectedCreateCount = state.expectedNumberOfLiveWallpaperCreate(change);
                    int actualCreateCount = TestWallpaperService.Companion.getCreateCount();
                    String createMessage = String.format(
                            "Expected %s calls to Engine#onCreate, got %s. ",
                            expectedCreateCount, actualCreateCount);
                    if (actualCreateCount != expectedCreateCount) {
                        errorMessages.add(
                                createMessage + "\n" + state.reproduceDescription(change));
                    }

                    int expectedDestroyCount = state.expectedNumberOfLiveWallpaperDestroy(change);
                    int actualDestroyCount = TestWallpaperService.Companion.getDestroyCount();
                    String destroyMessage = String.format(
                            "Expected %s calls to Engine#onDestroy, got %s. ",
                            expectedDestroyCount, actualDestroyCount);
                    if (actualDestroyCount != expectedDestroyCount) {
                        errorMessages.add(
                                destroyMessage + "\n" + state.reproduceDescription(change));
                    }
                }
            }
        });
        assertWithMessage(String.join("\n\n", errorMessages))
                .that(errorMessages.size()).isEqualTo(0);
    }

    /**
     * Check that the wallpaper windows that window manager is handling
     * are exactly the expected ones
     */
    @Test
    public void testExistingWallpaperWindows() {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        assumeTrue("Skipping testExistingWallpaperWindows: FEATURE_LIVE_WALLPAPER missing.",
                mContext.getPackageManager().hasSystemFeature(FEATURE_LIVE_WALLPAPER));
        runWithShellPermissionIdentity(() -> {
            WallpaperWindowsTestUtils.WallpaperWindowsHelper wallpaperWindowsHelper =
                    new WallpaperWindowsTestUtils.WallpaperWindowsHelper(sWindowManagerStateHelper);
            // Two independent wallpapers
            WallpaperManagerTestUtils.goToState(mWallpaperManager, TWO_DIFFERENT_LIVE_WALLPAPERS);
            assertWallpapersMatching(wallpaperWindowsHelper,
                    List.of(mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM).getServiceName(),
                            mWallpaperManager.getWallpaperInfo(FLAG_LOCK).getServiceName()));
            // One shared wallpaper
            WallpaperManagerTestUtils.goToState(mWallpaperManager, TWO_SAME_LIVE_WALLPAPERS);
            assertWallpapersMatching(wallpaperWindowsHelper, List.of(
                    mWallpaperManager.getWallpaperInfo(FLAG_SYSTEM).getServiceName()));
        });
    }

    /**
     * Check that the windows which have the role of home screen wallpapers
     * are actually visible on home screen
     */
    @Test
    public void testSystemAndLockWallpaperVisibility_onHomeScreen() {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        assumeTrue("Skipping testSystemAndLockWallpaperVisibility_onHomeScreen:"
                        + " FEATURE_LIVE_WALLPAPER missing.",
                mContext.getPackageManager().hasSystemFeature(FEATURE_LIVE_WALLPAPER));
        // Launch an activity that shows the wallpaper to make sure it is not behind opaque
        // activities
        mActivityTestRule.launchActivity(null);
        try {
            runWithShellPermissionIdentity(() -> {
                WallpaperWindowsTestUtils.WallpaperWindowsHelper wallpaperWindowsHelper =
                        new WallpaperWindowsTestUtils.WallpaperWindowsHelper(
                                sWindowManagerStateHelper);
                wallpaperWindowsHelper.showHomeScreenAndUpdate();

                // Two independent wallpapers
                WallpaperManagerTestUtils.goToState(mWallpaperManager,
                        TWO_DIFFERENT_LIVE_WALLPAPERS);
                assertWallpaperIsShown(wallpaperWindowsHelper, FLAG_SYSTEM,
                        true /* shouldBeShown */, "System wallpaper is hidden on home screen");

                // Shared wallpaper
                WallpaperManagerTestUtils.goToState(mWallpaperManager, TWO_SAME_LIVE_WALLPAPERS);
                assertWallpaperIsShown(wallpaperWindowsHelper, FLAG_SYSTEM | FLAG_LOCK,
                        true /* shouldBeShown */, "Shared wallpaper is hidden on home screen");
            });
        } finally {
            mActivityTestRule.finishActivity();
        }
    }

    /**
     * Check that the windows which have the role of lock screen wallpapers
     * are actually visible on lock screen
     */
    @Test
    public void testSystemAndLockWallpaperVisibility_onLockScreen() throws Exception {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        assumeTrue("Skipping assert_SystemWallpaperHidden_LockWallpaperShow_OnLockscreen:"
                        + " FEATURE_SECURE_LOCK_SCREEN missing.",
                mContext.getPackageManager().hasSystemFeature(FEATURE_SECURE_LOCK_SCREEN));
        assumeTrue("Skipping testSystemAndLockWallpaperVisibility_onLockScreen:"
                        + " FEATURE_LIVE_WALLPAPER missing.",
                mContext.getPackageManager().hasSystemFeature(FEATURE_LIVE_WALLPAPER));
        WallpaperWindowsTestUtils.runWithKeyguardEnabled(sWindowManagerStateHelper, () -> {
            runWithShellPermissionIdentity(() -> {
                WallpaperWindowsTestUtils.WallpaperWindowsHelper wallpaperWindowsHelper =
                        new WallpaperWindowsTestUtils.WallpaperWindowsHelper(
                                sWindowManagerStateHelper);

                // Two independent wallpapers
                WallpaperManagerTestUtils.goToState(mWallpaperManager,
                        TWO_DIFFERENT_LIVE_WALLPAPERS);
                wallpaperWindowsHelper.showLockScreenAndUpdate();
                assertWallpaperIsShown(wallpaperWindowsHelper, FLAG_SYSTEM,
                        false /* shouldBeShown */,
                        "System wallpaper is showing on lock screen");
                assertWallpaperIsShown(wallpaperWindowsHelper, FLAG_LOCK, true /* shouldBeShown */,
                        "Lock wallpaper is hidden on lock screen");

                // Shared wallpaper
                WallpaperManagerTestUtils.goToState(mWallpaperManager, TWO_SAME_LIVE_WALLPAPERS);
                assertWallpaperIsShown(wallpaperWindowsHelper, FLAG_SYSTEM | FLAG_LOCK,
                        true /* shouldBeShown */, "Shared wallpaper is hidden on lock screen");
            });
        });
    }

    @Test
    @Ignore("b/281082882")
    public void setDimAmount_lockScreenUnset_singleEngine_notifiesColorsChangedHomeOnly() {
        assumeFalse(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        ensureCleanState();

        final CountDownLatch latch = new CountDownLatch(1);
        WallpaperManager.OnColorsChangedListener counter = (colors, whichWp) -> latch.countDown();
        final LinkedList<Integer> receivedFlags = new LinkedList<>();
        WallpaperManager.OnColorsChangedListener listener = (colors, whichWp) -> receivedFlags.add(
                whichWp);
        mWallpaperManager.addOnColorsChangedListener(listener, mHandler);
        mWallpaperManager.addOnColorsChangedListener(counter, mHandler);
        final float initialDim = runWithShellPermissionIdentity(
                mWallpaperManager::getWallpaperDimAmount);
        final float newDim = initialDim > 0 ? 0.5f * initialDim : 0.5f;

        try {
            runWithShellPermissionIdentity(() -> {
                mWallpaperManager.setWallpaperDimAmount(newDim);
            });
            boolean latchSuccess = latch.await(DIM_LISTENER_TIMEOUT_SECS, TimeUnit.SECONDS);
            assertWithMessage("Registered listener not invoked").that(latchSuccess).isTrue();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            runWithShellPermissionIdentity(() ->
                    mWallpaperManager.setWallpaperDimAmount(initialDim));
        }

        assertThat(receivedFlags).containsExactly(FLAG_SYSTEM);
        mWallpaperManager.removeOnColorsChangedListener(listener);
        mWallpaperManager.removeOnColorsChangedListener(counter);
    }

    @Test
    @Ignore("b/281082882")
    public void setDimAmount_lockScreenUnset_multiEngine_notifiesColorsChangedBothTogether() {
        assumeTrue(mWallpaperManager.isLockscreenLiveWallpaperEnabled());
        ensureCleanState();

        final CountDownLatch latch = new CountDownLatch(1);
        WallpaperManager.OnColorsChangedListener counter = (colors, whichWp) -> latch.countDown();
        final LinkedList<Integer> receivedFlags = new LinkedList<>();
        WallpaperManager.OnColorsChangedListener listener = (colors, whichWp) -> receivedFlags.add(
                whichWp);
        mWallpaperManager.addOnColorsChangedListener(listener, mHandler);
        mWallpaperManager.addOnColorsChangedListener(counter, mHandler);
        final float initialDim = runWithShellPermissionIdentity(
                mWallpaperManager::getWallpaperDimAmount);
        final float newDim = initialDim > 0 ? 0.5f * initialDim : 0.5f;

        try {
            runWithShellPermissionIdentity(() -> {
                mWallpaperManager.setWallpaperDimAmount(newDim);
            });
            boolean latchSuccess = latch.await(DIM_LISTENER_TIMEOUT_SECS, TimeUnit.SECONDS);
            assertWithMessage("Registered listener not invoked").that(latchSuccess).isTrue();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            runWithShellPermissionIdentity(() ->
                    mWallpaperManager.setWallpaperDimAmount(initialDim));
        }

        assertThat(receivedFlags).containsExactly(FLAG_SYSTEM | FLAG_LOCK);
        mWallpaperManager.removeOnColorsChangedListener(listener);
        mWallpaperManager.removeOnColorsChangedListener(counter);
    }

    @Test
    @Ignore("b/281082882")
    public void setDimAmount_lockScreenSet_notifiesColorsChangedBothSeparately() {
        ensureCleanState(FLAG_LOCK);
        ensureCleanState(FLAG_SYSTEM);

        final CountDownLatch latch = new CountDownLatch(2);
        WallpaperManager.OnColorsChangedListener counter = (colors, whichWp) -> latch.countDown();
        final LinkedList<Integer> receivedFlags = new LinkedList<>();
        WallpaperManager.OnColorsChangedListener listener = (colors, whichWp) -> receivedFlags.add(
                whichWp);
        mWallpaperManager.addOnColorsChangedListener(listener, mHandler);
        final float initialDim = runWithShellPermissionIdentity(
                mWallpaperManager::getWallpaperDimAmount);
        final float newDim = initialDim > 0 ? 0.5f * initialDim : 0.5f;

        mWallpaperManager.addOnColorsChangedListener(counter, mHandler);
        try {
            runWithShellPermissionIdentity(() -> {
                mWallpaperManager.setWallpaperDimAmount(newDim);
            });
            boolean latchSuccess = latch.await(DIM_LISTENER_TIMEOUT_SECS, TimeUnit.SECONDS);
            assertWithMessage("Registered listener not invoked").that(latchSuccess).isTrue();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            runWithShellPermissionIdentity(() ->
                    mWallpaperManager.setWallpaperDimAmount(initialDim));
        }

        assertThat(receivedFlags).containsExactly(FLAG_SYSTEM, FLAG_LOCK);
        mWallpaperManager.removeOnColorsChangedListener(listener);
        mWallpaperManager.removeOnColorsChangedListener(counter);
    }

    private void assertWallpapersMatching(WallpaperWindowsTestUtils.WallpaperWindowsHelper windows,
            List<String> expectedWallpaperPackageNames) {

        boolean match = windows.waitForMatchingPackages(expectedWallpaperPackageNames);
        assertWithMessage("Lists do not match. Expected: "
                + expectedWallpaperPackageNames + " but received " + windows.dumpPackages())
                .that(match).isTrue();
    }

    /** Check if wallpaper corresponding to wallpaperFlag has visibility matching shouldBeShown */
    private void assertWallpaperIsShown(
            WallpaperWindowsTestUtils.WallpaperWindowsHelper wallpaperWindowsHelper,
            int wallpaperFlag,
            boolean shouldBeShown,
            String errorMsg) {
        String wpServiceName = mWallpaperManager.getWallpaperInfo(
                (wallpaperFlag & FLAG_SYSTEM) != 0 ? FLAG_SYSTEM : FLAG_LOCK).getServiceName();

        boolean matchingVisibility = wallpaperWindowsHelper
                .waitForMatchingWindowVisibility(wpServiceName, shouldBeShown);
        assertWithMessage(errorMsg + "\n" + wallpaperWindowsHelper.dumpWindows())
                .that(matchingVisibility).isTrue();
    }

    private void assertBitmapDimensions(Bitmap bitmap) {
        int maxSize = getMaxTextureSize();
        boolean safe = false;
        if (bitmap != null) {
            safe = bitmap.getWidth() <= maxSize && bitmap.getHeight() <= maxSize;
        }
        assertThat(safe).isTrue();
    }

    private void assertDesiredDimension(Point suggestedSize, Point expectedSize) {
        mWallpaperManager.suggestDesiredDimensions(suggestedSize.x, suggestedSize.y);
        Point actualSize = new Point(mWallpaperManager.getDesiredMinimumWidth(),
                mWallpaperManager.getDesiredMinimumHeight());
        if (actualSize.x > 0 || actualSize.y > 0) {
            if ((actualSize.x != expectedSize.x || actualSize.y != expectedSize.y)) {
                throw new AssertionError(
                        "Expected x: " + expectedSize.x + " y: " + expectedSize.y
                                + ", got x: " + actualSize.x + " y: " + actualSize.y);
            }
        }
    }

    private Point getScreenSize() {
        WindowManager wm = mContext.getSystemService(WindowManager.class);
        Display d = wm.getDefaultDisplay();
        Point p = new Point();
        d.getRealSize(p);
        return p;
    }

    /**
     * Helper to set a listener and verify if it was called with the same flags.
     * Executes operation synchronously. Params are FLAG_LOCK, FLAG_SYSTEM or a combination of both.
     *
     * @param which wallpaper destinations to set
     * @param whichExpected wallpaper destinations that should receive listener calls
     */
    private void verifyColorListenerInvoked(int which, int whichExpected) {
        ensureCleanState();
        int expected = 0;
        if ((whichExpected & FLAG_LOCK) != 0) expected++;
        if ((whichExpected & FLAG_SYSTEM) != 0) expected++;
        ArrayList<Integer> received = new ArrayList<>();

        final CountDownLatch latch = new CountDownLatch(expected);
        Handler handler = new Handler(Looper.getMainLooper());

        WallpaperManager.OnColorsChangedListener listener = getTestableListener();
        final AtomicBoolean allOk = new AtomicBoolean(true);
        WallpaperManager.OnColorsChangedListener counter = (colors, whichWp) -> {
            handler.post(() -> {
                final boolean wantSystem = (whichExpected & FLAG_SYSTEM) != 0;
                final boolean wantLock = (whichExpected & FLAG_LOCK) != 0;
                final boolean gotSystem = (whichWp & FLAG_SYSTEM) != 0;
                final boolean gotLock = (whichWp & FLAG_LOCK) != 0;
                received.add(whichWp);
                boolean ok = true;

                if (gotLock) {
                    if (wantLock) {
                        latch.countDown();
                    } else {
                        ok = false;
                    }
                }
                if (gotSystem) {
                    if (wantSystem) {
                        latch.countDown();
                    } else {
                        ok = false;
                    }
                }
                if (!ok) {
                    allOk.set(false);
                    Log.e(TAG,
                            "Unexpected which flag: " + whichWp + " should be: " + whichExpected);
                }
            });
        };

        mWallpaperManager.addOnColorsChangedListener(listener, mHandler);
        mWallpaperManager.addOnColorsChangedListener(counter, mHandler);

        try {
            mWallpaperManager.setResource(R.drawable.icon_red, which);
            boolean eventsReceived = latch.await(5, TimeUnit.SECONDS);
            assertWithMessage("Timed out waiting for color events. Expected: "
                    + whichExpected + " received: " + received)
                    .that(eventsReceived).isTrue();
            // Wait in case there are additional unwanted callbacks
            Thread.sleep(SLEEP_MS);
            assertWithMessage("Unexpected which flag, check logs for details")
                    .that(allOk.get()).isTrue();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        } finally {
            mWallpaperManager.removeOnColorsChangedListener(listener);
            mWallpaperManager.removeOnColorsChangedListener(counter);
        }
    }

    /**
     * Helper to clear a wallpaper synchronously.
     *
     * @param which FLAG_LOCK, FLAG_SYSTEM or a combination of both.
     */
    private void verifyColorListenerInvokedClearing(int which) {
        ensureCleanState();

        final CountDownLatch latch = new CountDownLatch(1);

        WallpaperManager.OnColorsChangedListener listener = getTestableListener();
        WallpaperManager.OnColorsChangedListener counter = (colors, whichWp) -> {
            latch.countDown();
        };

        mWallpaperManager.addOnColorsChangedListener(listener, mHandler);
        mWallpaperManager.addOnColorsChangedListener(counter, mHandler);

        try {
            mWallpaperManager.clear(which);
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }

        verify(listener, atLeast(1))
                .onColorsChanged(nullable(WallpaperColors.class), anyInt());

        mWallpaperManager.removeOnColorsChangedListener(listener);
        mWallpaperManager.removeOnColorsChangedListener(counter);
    }

    private void ensureCleanState() {
        ensureCleanState(FLAG_SYSTEM | FLAG_LOCK);
    }

    /**
     * Helper method to make sure that a) wallpaper is set for the destination(s) specified by
     * `flags`, and b) wallpaper callbacks related to setting the wallpaper have completed. This is
     * necessary before testing further callback interactions. Previously this was also necessary to
     * avoid race conditions between tests; not sure if that's still true as of April 2023.
     */
    private void ensureCleanState(int flags) {
        Bitmap bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        // We expect 3 events to happen when we change a wallpaper:
        // • Wallpaper changed
        // • System colors are known
        // • Lock colors are known
        int expectedEvents = 1;
        if ((flags & FLAG_SYSTEM) != 0) expectedEvents++;
        if ((flags & FLAG_LOCK) != 0) expectedEvents++;
        mCountDownLatch = new CountDownLatch(expectedEvents);
        if (DEBUG) {
            Log.d(TAG, "Started latch expecting: " + mCountDownLatch.getCount());
        }

        WallpaperManager.OnColorsChangedListener callback = (colors, which) -> {
            if ((which & FLAG_LOCK) != 0) {
                mCountDownLatch.countDown();
            }
            if ((which & FLAG_SYSTEM) != 0) {
                mCountDownLatch.countDown();
            }
            if (DEBUG) {
                Log.d(TAG, "color state count down: " + which + " - " + colors);
            }
        };
        mWallpaperManager.addOnColorsChangedListener(callback, mHandler);

        try {
            if (flags == (FLAG_SYSTEM | FLAG_LOCK)) {
                mWallpaperManager.setBitmap(bmp);
            } else {
                mWallpaperManager.setBitmap(bmp, /* visibleCropHint= */
                        null, /* allowBackup= */true, flags);
            }

            // Wait for up to 10 sec since this is an async call.
            // Will pass as soon as the expected callbacks are executed.
            assertWithMessage("Timed out waiting for events").that(
                    mCountDownLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(mCountDownLatch.getCount()).isEqualTo(0);
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException("Can't ensure a clean state.");
        } finally {
            mWallpaperManager.removeOnColorsChangedListener(callback);
            bmp.recycle();
        }
    }

    private void setWallpaperComponentAndWait(ComponentName component, int which) {
        mWallpaperManager.setWallpaperComponentWithFlags(component, which);
        try {
            // Allow time for callbacks since setting component is async
            Thread.sleep(SLEEP_MS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Live wallpaper wait interrupted", e);
        }
    }
    public WallpaperManager.OnColorsChangedListener getTestableListener() {
        // Unfortunately mockito cannot mock anonymous classes or lambdas.
        return spy(new TestableColorListener());
    }

    public static class TestableColorListener implements WallpaperManager.OnColorsChangedListener {
        @Override
        public void onColorsChanged(WallpaperColors colors, int which) {
        }
    }
}
