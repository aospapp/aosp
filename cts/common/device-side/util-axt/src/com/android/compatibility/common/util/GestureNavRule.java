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

package com.android.compatibility.common.util;

import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.util.ArrayMap;

import androidx.test.InstrumentationRegistry;

import org.junit.ClassRule;
import org.junit.rules.ExternalResource;

import java.io.IOException;
import java.util.Map;

/**
 * Test rule to enable gesture navigation on the device. Designed to be a {@link ClassRule}.
 */
public class GestureNavRule extends ExternalResource {
    private static final String SETTINGS_PACKAGE_NAME = "com.android.settings";
    private static final String NAV_BAR_INTERACTION_MODE_RES_NAME = "config_navBarInteractionMode";
    private static final int NAV_BAR_INTERACTION_MODE_GESTURAL = 2;
    private static final String GESTURAL_OVERLAY_NAME =
            "com.android.internal.systemui.navbar.gestural";

    /** Most application's res id must be larger than 0x7f000000 */
    public static final int MIN_APPLICATION_RES_ID = 0x7f000000;
    public static final String SETTINGS_CLASS =
            SETTINGS_PACKAGE_NAME + ".Settings$SystemDashboardActivity";

    private final Map<String, Boolean> mSystemGestureOptionsMap = new ArrayMap<>();
    private final Context mTargetContext;
    private final UiDevice mDevice;

    // Bounds for actions like swipe and click.
    private String mEdgeToEdgeNavigationTitle;
    private String mSystemNavigationTitle;
    private String mGesturePreferenceTitle;
    private boolean mConfiguredInSettings;
    private boolean mRevertOverlay;

    @Override
    protected void before() throws Throwable {
        if (!isGestureMode()) {
            enableGestureNav();
        }
    }

    @Override
    protected void after() {
        disableGestureNav();
    }

    /**
     * Initialize all options in System Gesture.
     */
    public GestureNavRule() {
        @SuppressWarnings("deprecation")
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        mDevice = UiDevice.getInstance(instrumentation);
        mTargetContext = instrumentation.getTargetContext();
        PackageManager packageManager = mTargetContext.getPackageManager();
        Resources res;
        try {
            res = packageManager.getResourcesForApplication(SETTINGS_PACKAGE_NAME);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        if (res == null) {
            return;
        }

        mEdgeToEdgeNavigationTitle = getSettingsString(res, "edge_to_edge_navigation_title");
        mGesturePreferenceTitle = getSettingsString(res, "gesture_preference_title");
        mSystemNavigationTitle = getSettingsString(res, "system_navigation_title");

        String text = getSettingsString(res, "edge_to_edge_navigation_title");
        if (text != null) {
            mSystemGestureOptionsMap.put(text, false);
        }
        text = getSettingsString(res, "swipe_up_to_switch_apps_title");
        if (text != null) {
            mSystemGestureOptionsMap.put(text, false);
        }
        text = getSettingsString(res, "legacy_navigation_title");
        if (text != null) {
            mSystemGestureOptionsMap.put(text, false);
        }

        mConfiguredInSettings = false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean hasSystemGestureFeature() {
        final PackageManager pm = mTargetContext.getPackageManager();

        // No bars on embedded devices.
        // No bars on TVs and watches.
        return !(pm.hasSystemFeature(PackageManager.FEATURE_WATCH)
                || pm.hasSystemFeature(PackageManager.FEATURE_EMBEDDED)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE));
    }


    private UiObject2 findSystemNavigationObject(String text, boolean addCheckSelector) {
        BySelector widgetFrameSelector = By.res("android", "widget_frame");
        BySelector checkboxSelector = By.checkable(true);
        if (addCheckSelector) {
            checkboxSelector = checkboxSelector.checked(true);
        }
        BySelector textSelector = By.text(text);
        BySelector targetSelector = By.hasChild(widgetFrameSelector).hasDescendant(textSelector)
                .hasDescendant(checkboxSelector);

        return mDevice.findObject(targetSelector);
    }

    private boolean launchToSettingsSystemGesture() {

        // Open the Settings app as close as possible to the gesture Fragment
        Intent intent = new Intent(Intent.ACTION_MAIN);
        ComponentName settingComponent = new ComponentName(SETTINGS_PACKAGE_NAME, SETTINGS_CLASS);
        intent.setComponent(settingComponent);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mTargetContext.startActivity(intent);

        // Wait for the app to appear
        mDevice.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)),
                5000);
        mDevice.wait(Until.hasObject(By.text(mGesturePreferenceTitle)), 5000);
        if (mDevice.findObject(By.text(mGesturePreferenceTitle)) == null) {
            return false;
        }
        mDevice.findObject(By.text(mGesturePreferenceTitle)).click();
        mDevice.wait(Until.hasObject(By.text(mSystemNavigationTitle)), 5000);
        if (mDevice.findObject(By.text(mSystemNavigationTitle)) == null) {
            return false;
        }
        mDevice.findObject(By.text(mSystemNavigationTitle)).click();
        mDevice.wait(Until.hasObject(By.text(mEdgeToEdgeNavigationTitle)), 5000);

        return mDevice.hasObject(By.text(mEdgeToEdgeNavigationTitle));
    }

    private void leaveSettings() {
        mDevice.pressBack(); /* Back to Gesture */
        mDevice.waitForIdle();
        mDevice.pressBack(); /* Back to System */
        mDevice.waitForIdle();
        mDevice.pressBack(); /* back to Settings */
        mDevice.waitForIdle();
        mDevice.pressBack(); /* Back to Home */
        mDevice.waitForIdle();

        mDevice.pressHome(); /* double confirm back to home */
        mDevice.waitForIdle();
    }

    private void enableGestureNav() {
        if (!hasSystemGestureFeature()) {
            return;
        }
        try {
            if (mDevice.executeShellCommand("cmd overlay list").contains(GESTURAL_OVERLAY_NAME)) {
                mDevice.executeShellCommand("cmd overlay enable " + GESTURAL_OVERLAY_NAME);
                mDevice.waitForIdle();
            }
        } catch (IOException e) {
            // Do nothing
        }

        if (isGestureMode()) {
            mRevertOverlay = true;
            return;
        }

        // Set up the gesture navigation by enabling it via the Settings app
        boolean isOperatedSettingsToExpectedOption = launchToSettingsSystemGesture();
        if (isOperatedSettingsToExpectedOption) {
            for (Map.Entry<String, Boolean> entry : mSystemGestureOptionsMap.entrySet()) {
                UiObject2 uiObject2 = findSystemNavigationObject(entry.getKey(), true);
                entry.setValue(uiObject2 != null);
            }
            UiObject2 edgeToEdgeObj = mDevice.findObject(By.text(mEdgeToEdgeNavigationTitle));
            if (edgeToEdgeObj != null) {
                edgeToEdgeObj.click();
                mConfiguredInSettings = true;
            }
        }
        mDevice.waitForIdle();
        leaveSettings();

        mDevice.pressHome();
        mDevice.waitForIdle();

        mDevice.waitForIdle();
    }

    /**
     * Restore the original configured value for the system gesture by operating Settings.
     */
    private void disableGestureNav() {
        if (!hasSystemGestureFeature()) {
            return;
        }

        if (mRevertOverlay) {
            try {
                mDevice.executeShellCommand("cmd overlay disable " + GESTURAL_OVERLAY_NAME);
            } catch (IOException e) {
                // Do nothing
            }
            if (!isGestureMode()) {
                return;
            }
        }

        if (mConfiguredInSettings) {
            launchToSettingsSystemGesture();
            for (Map.Entry<String, Boolean> entry : mSystemGestureOptionsMap.entrySet()) {
                if (entry.getValue()) {
                    UiObject2 navigationObject = findSystemNavigationObject(entry.getKey(), false);
                    if (navigationObject != null) {
                        navigationObject.click();
                    }
                }
            }
            leaveSettings();
        }
    }

    /**
     * Assumes the device is in gesture navigation mode. Due to constraints of AndroidJUnitRunner we
     * can't make assumptions in static contexts like in a {@link ClassRule} so tests need to call
     * this method explicitly.
     */
    public void assumeGestureNavigationMode() {
        boolean isGestureMode = isGestureMode();
        assumeTrue("Gesture navigation required", isGestureMode);
    }

    private boolean isGestureMode() {
        // TODO: b/153032202 consider the CTS on GSI case.
        Resources res = mTargetContext.getResources();
        int naviModeId = res.getIdentifier(NAV_BAR_INTERACTION_MODE_RES_NAME, "integer", "android");
        int naviMode = res.getInteger(naviModeId);
        return naviMode == NAV_BAR_INTERACTION_MODE_GESTURAL;
    }

    private static String getSettingsString(Resources res, String strResName) {
        int resIdString = res.getIdentifier(strResName, "string", SETTINGS_PACKAGE_NAME);
        if (resIdString <= MIN_APPLICATION_RES_ID) {
            return null;
        }

        return res.getString(resIdString);
    }
}
