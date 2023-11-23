/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.support.test.launcherhelper;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.system.helpers.CommandsHelper;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Implementation of {@link ILauncherStrategy} to support Volvo launcher */
public class VolvoLauncherStrategy extends AutoLauncherStrategy {
    private static final String VOLVO_LAUNCHER_PACKAGE = "com.volvocars.launcher";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private static final Map<String, BySelector> FACET_MAP =
            Stream.of(
                            new Object[][] {
                                {
                                    "App Grid",
                                    By.res(SYSTEM_UI_PACKAGE, "nav_bar_apps").clickable(true)
                                },
                            })
                    .collect(
                            Collectors.toMap(
                                    data -> (String) data[0], data -> (BySelector) data[1]));

    private static final Map<String, BySelector> APP_OPEN_VERIFIERS =
            Stream.of(
                            new Object[][] {
                                {"App Grid", By.res(VOLVO_LAUNCHER_PACKAGE, "apps_pane")},
                            })
                    .collect(
                            Collectors.toMap(
                                    data -> (String) data[0], data -> (BySelector) data[1]));

    private static final long APP_LAUNCH_TIMEOUT_MS = 10000;
    private static final long UI_WAIT_TIMEOUT_MS = 5000;
    private static final long POLL_INTERVAL = 100;

    protected UiDevice mDevice;
    private Instrumentation mInstrumentation;
    private CommandsHelper mCommandsHelper;

    @Override
    public String getSupportedLauncherPackage() {
        return VOLVO_LAUNCHER_PACKAGE;
    }

    @Override
    public void setUiDevice(UiDevice uiDevice) {
        mDevice = uiDevice;
    }

    @Override
    public void setInstrumentation(Instrumentation instrumentation) {
        super.setInstrumentation(instrumentation);
        mInstrumentation = instrumentation;
        mCommandsHelper = CommandsHelper.getInstance(mInstrumentation);
    }

    @Override
    public void openApp(String appName) {
        if (checkApplicationExists(appName)) {
            UiObject2 app = mDevice.findObject(By.clickable(true).hasDescendant(By.text(appName)));
            app.clickAndWait(Until.newWindow(), APP_LAUNCH_TIMEOUT_MS);
            mDevice.waitForIdle();
        } else {
            throw new RuntimeException(String.format("Application %s not found", appName));
        }
    }

    @Override
    public void openBluetoothAudioApp() {
        String appName = "Bluetooth Media Player";
        if (checkApplicationExists(appName)) {
            UiObject2 app = mDevice.findObject(By.clickable(true).hasDescendant(By.text(appName)));
            app.clickAndWait(Until.newWindow(), APP_LAUNCH_TIMEOUT_MS);
            mDevice.waitForIdle();
        } else {
            throw new RuntimeException(String.format("Application %s not found", appName));
        }
    }

    @Override
    public void openGooglePlayStore() {
        mDevice.pressHome();
        mDevice.waitForIdle();
        mCommandsHelper.executeShellCommand(
                "am start -a android.intent.action.MAIN "
                        + "-c android.intent.category.LAUNCHER "
                        + "-n com.android.vending/"
                        + "com.google.android.finsky.carmainactivity.MainActivity");
    }

    @Override
    public boolean checkApplicationExists(String appName) {
        openAppGridFacet();
        UiObject2 app = findApplication(appName);
        return app != null;
    }

    @Override
    public void openAppGridFacet() {
        openFacet("App Grid");
    }

    @Override
    public void openMapsFacet() {
        // Volvo does not have Facet for Maps, so open Maps from App Grid
        openApp("Maps");
    }

    private void openFacet(String facetName) {
        BySelector facetSelector = FACET_MAP.get(facetName);
        UiObject2 facet = mDevice.findObject(facetSelector);
        if (facet != null) {
            facet.click();
            waitUntilAppOpen(facetName, APP_LAUNCH_TIMEOUT_MS);
        } else {
            throw new RuntimeException(String.format("Failed to find %s facet.", facetName));
        }
    }

    private void waitUntilAppOpen(String appName, long timeout) {
        SystemClock.sleep(timeout);
        long startTime = SystemClock.uptimeMillis();
        boolean isOpen = false;
        do {
            isOpen = mDevice.hasObject(APP_OPEN_VERIFIERS.get(appName));
            if (isOpen) {
                break;
            }
            SystemClock.sleep(POLL_INTERVAL);
        } while ((SystemClock.uptimeMillis() - startTime) < timeout);
        if (!isOpen) {
            throw new IllegalStateException(
                    String.format(
                            "Did not find any app of %s in foreground after %d ms.",
                            appName, timeout));
        }
    }

    private UiObject2 findApplication(String appName) {
        BySelector appSelector = By.clickable(true).hasDescendant(By.text(appName));
        return mDevice.findObject(appSelector);
    }

    /** {@inheritDoc} */
    @Override
    public void openNotifications() {
        String cmd = "cmd statusbar expand-notifications";
        mCommandsHelper.executeShellCommand(cmd);
    }

    /** {@inheritDoc} */
    @Override
    public void pressHome() {
        String cmd = "input keyevent KEYCODE_HOME";
        mCommandsHelper.executeShellCommand(cmd);
    }
}
