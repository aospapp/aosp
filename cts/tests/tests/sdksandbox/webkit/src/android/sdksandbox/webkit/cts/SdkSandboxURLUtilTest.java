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

package android.sdksandbox.webkit.cts;

import android.app.sdksandbox.testutils.testscenario.KeepSdkSandboxAliveRule;
import android.platform.test.annotations.AppModeFull;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@MediumTest
@AppModeFull
@RunWith(AndroidJUnit4.class)
public class SdkSandboxURLUtilTest {
    @ClassRule
    public static final KeepSdkSandboxAliveRule sSdkTestSuiteSetup =
            new KeepSdkSandboxAliveRule("com.android.emptysdkprovider");

    @Rule
    public final WebViewSandboxTestRule sdkTester =
            new WebViewSandboxTestRule("android.webkit.cts.URLUtilTest");

    @Test
    public void testIsAssetUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsAssetUrl");
    }

    @Test
    public void testIsAboutUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsAboutUrl");
    }

    @Test
    public void testIsContentUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsContentUrl");
    }

    @Test
    public void testIsCookielessProxyUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsCookielessProxyUrl");
    }

    @Test
    public void testIsDataUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsDataUrl");
    }

    @Test
    public void testIsFileUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsFileUrl");
    }

    @Test
    public void testIsHttpsUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsHttpsUrl");
    }

    @Test
    public void testIsHttpUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsHttpUrl");
    }

    @Test
    public void testIsJavaScriptUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsJavaScriptUrl");
    }

    @Test
    public void testIsNetworkUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsNetworkUrl");
    }

    @Test
    public void testIsValidUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIsValidUrl");
    }

    @Test
    public void testComposeSearchUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testComposeSearchUrl");
    }

    @Test
    public void testDecode() throws Exception {
        sdkTester.assertSdkTestRunPasses("testDecode");
    }

    @Test
    public void testGuessFileName() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGuessFileName");
    }

    @Test
    public void testGuessUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGuessUrl");
    }

    @Test
    public void testStripAnchor() throws Exception {
        sdkTester.assertSdkTestRunPasses("testStripAnchor");
    }
}
