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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class SdkSandboxWebSettingsTest {
    @ClassRule
    public static final KeepSdkSandboxAliveRule sSdkTestSuiteSetup =
            new KeepSdkSandboxAliveRule("com.android.emptysdkprovider");

    @Rule
    public final WebViewSandboxTestRule sdkTester =
            new WebViewSandboxTestRule("android.webkit.cts.WebSettingsTest");

    @Test
    public void testUserAgentString_default() throws Exception {
        sdkTester.assertSdkTestRunPasses("testUserAgentString_default");
    }

    @Test
    public void testUserAgentStringTest() throws Exception {
        sdkTester.assertSdkTestRunPasses("testUserAgentStringTest");
    }

    @Test
    public void testAccessUserAgentString() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessUserAgentString");
    }

    @Test
    public void testAccessCacheMode_defaultValue() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessCacheMode_defaultValue");
    }

    @Test
    public void testAccessCacheMode_cacheElseNetwork() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessCacheMode_cacheElseNetwork");
    }

    @Test
    public void testAccessCacheMode_noCache() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessCacheMode_noCache");
    }

    @Test
    public void testAccessCacheMode_cacheOnly() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessCacheMode_cacheOnly");
    }

    @Test
    public void testAccessCursiveFontFamily() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessCursiveFontFamily");
    }

    @Test
    public void testAccessFantasyFontFamily() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessFantasyFontFamily");
    }

    @Test
    public void testAccessFixedFontFamily() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessFixedFontFamily");
    }

    @Test
    public void testAccessSansSerifFontFamily() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessSansSerifFontFamily");
    }

    @Test
    public void testAccessSerifFontFamily() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessSerifFontFamily");
    }

    @Test
    public void testAccessStandardFontFamily() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessStandardFontFamily");
    }

    @Test
    public void testAccessDefaultFontSize() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessDefaultFontSize");
    }

    @Test
    public void testAccessDefaultFixedFontSize() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessDefaultFixedFontSize");
    }

    @Test
    public void testAccessDefaultTextEncodingName() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessDefaultTextEncodingName");
    }

    @Test
    public void testAccessJavaScriptCanOpenWindowsAutomatically() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessJavaScriptCanOpenWindowsAutomatically");
    }

    @Test
    public void testAccessJavaScriptEnabled() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessJavaScriptEnabled");
    }

    @Test
    public void testAccessLayoutAlgorithm() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessLayoutAlgorithm");
    }

    @Test
    public void testAccessMinimumFontSize() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessMinimumFontSize");
    }

    @Test
    public void testAccessMinimumLogicalFontSize() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessMinimumLogicalFontSize");
    }

    @Test
    public void testAccessPluginsEnabled() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessPluginsEnabled");
    }

    @Test
    public void testOffscreenPreRaster() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOffscreenPreRaster");
    }

    @Test
    public void testAccessPluginsPath() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessPluginsPath");
    }

    @Test
    public void testAccessTextSize() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessTextSize");
    }

    @Test
    public void testAccessUseDoubleTree() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessUseDoubleTree");
    }

    @Test
    public void testAccessUseWideViewPort() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessUseWideViewPort");
    }

    @Test
    public void testSetNeedInitialFocus() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetNeedInitialFocus");
    }

    @Test
    public void testSetRenderPriority() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetRenderPriority");
    }

    @Test
    public void testAccessSupportMultipleWindows() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessSupportMultipleWindows");
    }

    @Test
    public void testAccessSupportZoom() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessSupportZoom");
    }

    @Test
    public void testAccessBuiltInZoomControls() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessBuiltInZoomControls");
    }

    @Test
    public void testAppCacheDisabled() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAppCacheDisabled");
    }

    @Test
    public void testAppCacheEnabled() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAppCacheEnabled");
    }

    @Test
    public void testDatabaseDisabled() throws Exception {
        sdkTester.assertSdkTestRunPasses("testDatabaseDisabled");
    }

    @Test
    public void testDisabledActionModeMenuItems() throws Exception {
        sdkTester.assertSdkTestRunPasses("testDisabledActionModeMenuItems");
    }

    @Test
    public void testLoadsImagesAutomatically_default() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadsImagesAutomatically_default");
    }

    @Test
    public void testLoadsImagesAutomatically_httpImagesLoaded() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadsImagesAutomatically_httpImagesLoaded");
    }

    @Test
    public void testLoadsImagesAutomatically_dataUriImagesLoaded() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadsImagesAutomatically_dataUriImagesLoaded");
    }

    @Test
    public void testLoadsImagesAutomatically_blockLoadingImages() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadsImagesAutomatically_blockLoadingImages");
    }

    @Test
    public void testLoadsImagesAutomatically_loadImagesWithoutReload() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadsImagesAutomatically_loadImagesWithoutReload");
    }

    @Test
    public void testBlockNetworkImage() throws Exception {
        sdkTester.assertSdkTestRunPasses("testBlockNetworkImage");
    }

    @Test
    public void testBlockNetworkLoads() throws Exception {
        sdkTester.assertSdkTestRunPasses("testBlockNetworkLoads");
    }

    @Test
    public void testIframesWhenAccessFromFileURLsDisabled() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIframesWhenAccessFromFileURLsDisabled");
    }

    @Test
    public void testXHRWhenAccessFromFileURLsEnabled() throws Exception {
        sdkTester.assertSdkTestRunPasses("testXHRWhenAccessFromFileURLsEnabled");
    }

    @Test
    public void testXHRWhenAccessFromFileURLsDisabled() throws Exception {
        sdkTester.assertSdkTestRunPasses("testXHRWhenAccessFromFileURLsDisabled");
    }

    @Test
    public void testAllowMixedMode() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAllowMixedMode");
    }

    @Test
    public void testEnableSafeBrowsing() throws Exception {
        sdkTester.assertSdkTestRunPasses("testEnableSafeBrowsing");
    }
}
