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

package android.sdksandbox.test.scenario.testsdk;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import android.os.SystemClock;
import android.platform.test.scenario.annotation.Scenario;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Scenario
@RunWith(JUnit4.class)
public class LoadAd {
    private static final UiDevice sUiDevice = UiDevice.getInstance(getInstrumentation());
    private static final long UI_NAVIGATION_WAIT_MS = 1000;

    private static final int NUMBER_OF_ADS = 3;
    private static final int TIME_BETWEEN_RENDERS_S = 2;
    private static final String LOAD_AD_BUTTON = "loadAdButton";

    private static final String CLIENT_APP = "com.google.android.libraries.internal.exampleclient";

    @AfterClass
    public static void tearDown() throws IOException {
        sUiDevice.executeShellCommand("am force-stop " + CLIENT_APP);
    }

    @Before
    public void setup() throws Exception {
        sUiDevice.executeShellCommand("am start " + CLIENT_APP + "/" + ".MainActivity");
    }

    @Test
    public void testLoadAd() throws Exception {
        // Loop to load ad multiple times sequentially
        for (int i = 0; i < NUMBER_OF_ADS; i++) {
            loadAd();
            SystemClock.sleep(TimeUnit.SECONDS.toMillis(TIME_BETWEEN_RENDERS_S));
            assertThat(getLoadAdButton().getText()).isEqualTo("Load Ad (Ad loaded)");
        }
        SystemClock.sleep(TimeUnit.SECONDS.toMillis(2));
    }

    private UiObject2 getLoadAdButton() {
        return sUiDevice.wait(
                Until.findObject(By.res(CLIENT_APP, LOAD_AD_BUTTON)), UI_NAVIGATION_WAIT_MS);
    }

    void loadAd() {
        if (getLoadAdButton() != null) {
            getLoadAdButton().click();
        } else {
            throw new RuntimeException("Did not find 'Load Ad' button.");
        }
    }
}
