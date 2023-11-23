/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.app.cts;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.compatibility.common.util.BatteryUtils;
import com.android.compatibility.common.util.SettingsUtils;

public class UiModeManagerTest extends AndroidTestCase {
    private static final String TAG = "UiModeManagerTest";

    private UiModeManager mUiModeManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mUiModeManager = (UiModeManager) getContext().getSystemService(Context.UI_MODE_SERVICE);
        assertNotNull(mUiModeManager);
    }

    public void testUiMode() throws Exception {
        if (isAutomotive()) {
            Log.i(TAG, "testUiMode automotive");
            doTestUiModeForAutomotive();
        } else if (isTelevision()) {
            assertEquals(Configuration.UI_MODE_TYPE_TELEVISION,
                    mUiModeManager.getCurrentModeType());
            doTestLockedUiMode();
        } else if (isWatch()) {
            assertEquals(Configuration.UI_MODE_TYPE_WATCH,
                    mUiModeManager.getCurrentModeType());
            doTestLockedUiMode();
        } else {
            Log.i(TAG, "testUiMode generic");
            doTestUiModeGeneric();
        }
    }

    public void testNightMode() throws Exception {
        if (isAutomotive()) {
            assertTrue(mUiModeManager.isNightModeLocked());
            doTestLockedNightMode();
        } else {
            if (mUiModeManager.isNightModeLocked()) {
                doTestLockedNightMode();
            } else {
                doTestUnlockedNightMode();
            }
        }
    }

    public void testNightModeInCarModeIsTransient() {
        if (mUiModeManager.isNightModeLocked()) {
            return;
        }

        assertNightModeChange(UiModeManager.MODE_NIGHT_NO);

        mUiModeManager.enableCarMode(0);
        assertEquals(Configuration.UI_MODE_TYPE_CAR, mUiModeManager.getCurrentModeType());

        assertNightModeChange(UiModeManager.MODE_NIGHT_YES);

        mUiModeManager.disableCarMode(0);
        assertNotSame(Configuration.UI_MODE_TYPE_CAR, mUiModeManager.getCurrentModeType());
        assertEquals(UiModeManager.MODE_NIGHT_NO, mUiModeManager.getNightMode());
    }

    public void testNightModeToggleInCarModeDoesNotChangeSetting() {
        if (mUiModeManager.isNightModeLocked()) {
            return;
        }

        assertNightModeChange(UiModeManager.MODE_NIGHT_NO);
        assertStoredNightModeSetting(UiModeManager.MODE_NIGHT_NO);

        mUiModeManager.enableCarMode(0);
        assertStoredNightModeSetting(UiModeManager.MODE_NIGHT_NO);

        assertNightModeChange(UiModeManager.MODE_NIGHT_YES);
        assertStoredNightModeSetting(UiModeManager.MODE_NIGHT_NO);

        mUiModeManager.disableCarMode(0);
        assertStoredNightModeSetting(UiModeManager.MODE_NIGHT_NO);
    }

    public void testNightModeInCarModeOnPowerSaveIsTransient() throws Throwable {
        if (mUiModeManager.isNightModeLocked() || !BatteryUtils.isBatterySaverSupported()) {
            return;
        }

        BatteryUtils.runDumpsysBatteryUnplug();

        // Turn off battery saver, disable night mode
        BatteryUtils.enableBatterySaver(false);
        mUiModeManager.setNightMode(UiModeManager.MODE_NIGHT_NO);
        assertEquals(UiModeManager.MODE_NIGHT_NO, mUiModeManager.getNightMode());
        assertVisibleNightModeInConfiguration(Configuration.UI_MODE_NIGHT_NO);

        // Then enable battery saver to check night mode is made visible
        BatteryUtils.enableBatterySaver(true);
        assertEquals(UiModeManager.MODE_NIGHT_NO, mUiModeManager.getNightMode());
        assertVisibleNightModeInConfiguration(Configuration.UI_MODE_NIGHT_YES);

        // Then disable it, enable car mode, and check night mode is not visible
        BatteryUtils.enableBatterySaver(false);
        mUiModeManager.enableCarMode(0);
        assertEquals(Configuration.UI_MODE_TYPE_CAR, mUiModeManager.getCurrentModeType());
        assertVisibleNightModeInConfiguration(Configuration.UI_MODE_NIGHT_NO);

        // Enable battery saver, check that night mode is still not visible, overridden by car mode
        BatteryUtils.enableBatterySaver(true);
        assertEquals(UiModeManager.MODE_NIGHT_NO, mUiModeManager.getNightMode());
        assertVisibleNightModeInConfiguration(Configuration.UI_MODE_NIGHT_NO);

        // Disable car mode
        mUiModeManager.disableCarMode(0);

        // Toggle night mode to force propagation of uiMode update, since disabling car mode
        // is deferred to a broadcast.
        mUiModeManager.setNightMode(UiModeManager.MODE_NIGHT_YES);
        mUiModeManager.setNightMode(UiModeManager.MODE_NIGHT_NO);

        // Check battery saver mode now shows night mode
        assertNotSame(Configuration.UI_MODE_TYPE_CAR, mUiModeManager.getCurrentModeType());
        assertVisibleNightModeInConfiguration(Configuration.UI_MODE_NIGHT_YES);

        // Disable battery saver and check night mode back to not visible
        BatteryUtils.enableBatterySaver(false);
        assertEquals(UiModeManager.MODE_NIGHT_NO, mUiModeManager.getNightMode());
        assertVisibleNightModeInConfiguration(Configuration.UI_MODE_NIGHT_NO);

        BatteryUtils.runDumpsysBatteryReset();
    }

    private boolean isAutomotive() {
        return getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE);
    }

    private boolean isTelevision() {
        PackageManager pm = getContext().getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private boolean isWatch() {
        return getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WATCH);
    }

    private void doTestUiModeForAutomotive() throws Exception {
        assertEquals(Configuration.UI_MODE_TYPE_CAR, mUiModeManager.getCurrentModeType());
        assertTrue(mUiModeManager.isUiModeLocked());
        doTestLockedUiMode();
    }

    private void doTestUiModeGeneric() throws Exception {
        if (mUiModeManager.isUiModeLocked()) {
            doTestLockedUiMode();
        } else {
            doTestUnlockedUiMode();
        }
    }

    private void doTestLockedUiMode() throws Exception {
        int originalMode = mUiModeManager.getCurrentModeType();
        mUiModeManager.enableCarMode(0);
        assertEquals(originalMode, mUiModeManager.getCurrentModeType());
        mUiModeManager.disableCarMode(0);
        assertEquals(originalMode, mUiModeManager.getCurrentModeType());
    }

    private void doTestUnlockedUiMode() throws Exception {
        mUiModeManager.enableCarMode(0);
        assertEquals(Configuration.UI_MODE_TYPE_CAR, mUiModeManager.getCurrentModeType());
        mUiModeManager.disableCarMode(0);
        assertNotSame(Configuration.UI_MODE_TYPE_CAR, mUiModeManager.getCurrentModeType());
    }

    private void doTestLockedNightMode() throws Exception {
        int currentMode = mUiModeManager.getNightMode();
        if (currentMode == UiModeManager.MODE_NIGHT_YES) {
            mUiModeManager.setNightMode(UiModeManager.MODE_NIGHT_NO);
            assertEquals(currentMode, mUiModeManager.getNightMode());
        } else {
            mUiModeManager.setNightMode(UiModeManager.MODE_NIGHT_YES);
            assertEquals(currentMode, mUiModeManager.getNightMode());
        }
    }

    private void doTestUnlockedNightMode() throws Exception {
        // day night mode should be settable regardless of car mode.
        mUiModeManager.enableCarMode(0);
        doTestAllNightModes();
        mUiModeManager.disableCarMode(0);
        doTestAllNightModes();
    }

    private void doTestAllNightModes() {
        assertNightModeChange(UiModeManager.MODE_NIGHT_AUTO);
        assertNightModeChange(UiModeManager.MODE_NIGHT_YES);
        assertNightModeChange(UiModeManager.MODE_NIGHT_NO);
    }

    private void assertNightModeChange(int mode) {
        mUiModeManager.setNightMode(mode);
        assertEquals(mode, mUiModeManager.getNightMode());
    }

    private void assertVisibleNightModeInConfiguration(int mode) {
        int uiMode = getContext().getResources().getConfiguration().uiMode;
        int flags = uiMode & Configuration.UI_MODE_NIGHT_MASK;
        assertEquals(mode, flags);
    }

    private void assertStoredNightModeSetting(int mode) {
        // Settings.Secure.UI_NIGHT_MODE
        String storedMode = SettingsUtils.getSecureSetting("ui_night_mode");
        int storedModeInt = Integer.parseInt(storedMode);
        assertEquals(mode, storedModeInt);
    }
}
