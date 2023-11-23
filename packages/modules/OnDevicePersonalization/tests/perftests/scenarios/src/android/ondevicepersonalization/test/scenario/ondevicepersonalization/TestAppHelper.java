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
package android.ondevicepersonalization.test.scenario.ondevicepersonalization;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertNotNull;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import java.io.IOException;

/** Helper class for interacting with OdpClient test app in perf tests. */
public class TestAppHelper {
    private static final UiDevice sUiDevice = UiDevice.getInstance(getInstrumentation());
    private static final long UI_FIND_RESOURCE_TIMEOUT = 5000;
    private static final String ODP_CLIENT_TEST_APP_PACKAGE_NAME = "com.android.odpclient";
    private static final String GET_AD_BUTTON_RESOURCE_ID = "get_ad_button";
    private static final String RENDERED_VIEW_RESOURCE_ID = "rendered_view";
    private static final String SURFACE_VIEW_TEXT = "Nest";

    /** Open ODP client test app. */
    public static void openApp() throws IOException {
        sUiDevice.executeShellCommand(
                "am start " + ODP_CLIENT_TEST_APP_PACKAGE_NAME + "/.MainActivity");
    }

    /** Go back to home screen. */
    public static void goToHomeScreen() throws IOException {
        sUiDevice.pressHome();
    }

    /** Click Get Ad button. */
    public void clickGetAd() {
        UiObject2 getAdButton = getGetAdButton();
        assertNotNull("Get Ad button not found", getAdButton);
        getAdButton.click();
    }

    /** Verify view is correctly displayed after clicking Get Ad. */
    public void verifyRenderedView() {
        UiObject2 renderedView = getRenderedView();
        assertNotNull("Rendered view not found", renderedView);

        UiObject2 childSurfaceView = getChildSurfaceViewByText(SURFACE_VIEW_TEXT);
        assertNotNull("Child surface view not found", childSurfaceView);
    }

    private UiObject2 getGetAdButton() {
        return sUiDevice.wait(
            Until.findObject(By.res(ODP_CLIENT_TEST_APP_PACKAGE_NAME, GET_AD_BUTTON_RESOURCE_ID)),
            UI_FIND_RESOURCE_TIMEOUT);
    }

    private UiObject2 getRenderedView() {
        return sUiDevice.wait(
            Until.findObject(By.res(ODP_CLIENT_TEST_APP_PACKAGE_NAME, RENDERED_VIEW_RESOURCE_ID)),
            UI_FIND_RESOURCE_TIMEOUT);
    }

    private UiObject2 getChildSurfaceViewByText(final String text) {
        return sUiDevice.wait(
            Until.findObject(By.desc(text)),
            UI_FIND_RESOURCE_TIMEOUT);
    }
}
