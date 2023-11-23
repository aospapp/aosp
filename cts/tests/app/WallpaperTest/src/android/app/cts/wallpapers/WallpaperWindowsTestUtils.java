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

package android.app.cts.wallpapers;

import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import android.app.UiAutomation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.ParcelFileDescriptor;
import android.server.wm.UiDeviceUtils;
import android.server.wm.WindowManagerState;
import android.server.wm.WindowManagerStateHelper;
import android.view.Display;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.TestUtils;

import org.junit.Assume;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to check the status of the wallpaper windows.
 * Includes tool to handle the keyguard state to check both home and lock screen wallpapers.
 */
public class WallpaperWindowsTestUtils {
    private static Context sContext;
    private static DisplayManager sDisplayManager;
    private static UiAutomation sUiAutomation;

    public static void setContext(Context context) {
        sContext = context;
        sDisplayManager = context.getSystemService(DisplayManager.class);
        sUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    public static class WallpaperWindowsHelper {
        private final WindowManagerStateHelper mWmState;
        private List<WindowManagerState.WindowState> mWallpaperWindows = new ArrayList<>();
        private List<String> mPackageNames = new ArrayList<>();

        public WallpaperWindowsHelper(WindowManagerStateHelper windowManagerStateHelper) {
            mWmState = windowManagerStateHelper;
        }

        public void showHomeScreenAndUpdate() throws Exception {
            showHomeScreen(mWmState);
            updateWindows();
        }

        public void showLockScreenAndUpdate() throws Exception {
            showLockScreen();
            updateWindows();
        }

        public String dumpWindows() {
            StringBuilder msgWindows = new StringBuilder();
            mWallpaperWindows.forEach(w -> msgWindows.append(w.toLongString()).append(", "));
            return "[" + msgWindows + "]";
        }

        public String dumpPackages() {
            StringBuilder msgWindows = new StringBuilder();
            mPackageNames.forEach(p -> msgWindows.append(p).append(", "));
            return "[" + msgWindows + "]";
        }

        public List<String> getAllWallpaperPackages() {
            return mPackageNames;
        }

        /**
         * Wait until the visibility of the window is the one expected,
         * and return false if it does not happen within N iterations
         */
        public boolean waitForMatchingWindowVisibility(String name,
                boolean expectedVisibility) {
            return mWmState.waitFor(
                    (wmState) -> checkMatchingWindowVisibility(name, expectedVisibility),
                    "Visibility of " + name + " is not " + expectedVisibility);
        }

        /**
         * Wait until the packages of the wallpapers match exactly the expected ones,
         * and return false if it does not happen within N iterations
         */
        public boolean waitForMatchingPackages(List<String> expected) {
            return mWmState.waitFor(
                    (wmState) -> checkMatchingPackages(expected),
                    "Provided packages and observed ones do not match");
        }

        private boolean checkMatchingWindowVisibility(String name, boolean expectedVisibility) {
            updateWindows();
            return mWallpaperWindows.stream().anyMatch(
                    w -> w.getName().equals(name) && w.isSurfaceShown() == expectedVisibility);
        }

        private boolean checkMatchingPackages(List<String> expected) {
            updateWindows();
            if (expected.size() != mPackageNames.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!expected.get(i).equals(mPackageNames.get(i))) {
                    return false;
                }
            }
            return true;
        }

        private void updateWindows() {
            mWmState.waitForAppTransitionIdleOnDisplay(sContext.getDisplayId());
            mWmState.computeState();
            mWallpaperWindows = mWmState.getMatchingWindowType(TYPE_WALLPAPER);
            mPackageNames = new ArrayList<>();
            mWallpaperWindows.forEach(w -> mPackageNames.add(w.getPackageName()));
        }
    }

    public static void runWithKeyguardEnabled(WindowManagerStateHelper wmState,
            Runnable runnable) throws Exception {
        boolean isDisabled;
        try (ParcelFileDescriptor parcelFileDescriptor = sUiAutomation.executeShellCommand(
                "locksettings get-disabled")) {
            FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
            BufferedReader reader = new BufferedReader(new FileReader(fileDescriptor));
            isDisabled = "true".equals(reader.readLine());
        }
        try {
            if (isDisabled) setKeyguardUnlockMethod(wmState, false);
            runnable.run();
        } finally {
            if (isDisabled) setKeyguardUnlockMethod(wmState, true);
        }
    }

    private static void setKeyguardUnlockMethod(
            WindowManagerStateHelper wmState, boolean setDisabled) throws Exception {
        try {
            sUiAutomation.executeShellCommand("locksettings clear");
            sUiAutomation.executeShellCommand("locksettings set-disabled " + setDisabled);
        } catch (IllegalArgumentException e) {
            Assume.assumeNoException(e);
        }
        // Unlock device to make the changes effective from the next unlock
        showHomeScreen(wmState);
    }

    private static void showLockScreen() throws Exception {
        if (sDisplayManager == null) throw new Exception("No display");
        Display display = sDisplayManager.getDisplay(sContext.getDisplayId());

        UiDeviceUtils.pressSleepButton();
        TestUtils.waitUntil("display does not turn off", 5, () -> !isDisplayOn(display));
        UiDeviceUtils.pressWakeupButton();
        TestUtils.waitUntil("display does not turn on", 5, () -> isDisplayOn(display));
    }

    private static void showHomeScreen(WindowManagerStateHelper wmSH) throws Exception {
        showLockScreen();
        UiDeviceUtils.pressUnlockButton();
        wmSH.waitForKeyguardGone();
    }

    private static boolean isDisplayOn(Display display) {
        return display != null && display.getState() == Display.STATE_ON;
    }
}
