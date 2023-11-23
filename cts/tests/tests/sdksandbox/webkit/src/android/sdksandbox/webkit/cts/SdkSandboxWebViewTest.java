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

package android.sdksandbox.webkit.cts;

import android.app.sdksandbox.testutils.testscenario.KeepSdkSandboxAliveRule;
import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@MediumTest
@AppModeFull
@RunWith(AndroidJUnit4.class)
public class SdkSandboxWebViewTest {
    @ClassRule
    public static final KeepSdkSandboxAliveRule sSdkTestSuiteSetup =
            new KeepSdkSandboxAliveRule("com.android.emptysdkprovider");

    @Rule
    public final WebViewSandboxTestRule sdkTester =
            new WebViewSandboxTestRule("android.webkit.cts.WebViewTest");

    @Test
    public void testConstructor() throws Exception {
        sdkTester.assertSdkTestRunPasses("testConstructor");
    }

    @Test
    public void testCreatingWebViewWithDeviceEncrpytionFails() throws Exception {
        sdkTester.assertSdkTestRunPasses("testCreatingWebViewWithDeviceEncrpytionFails");
    }

    @Test
    public void testCreatingWebViewWithMultipleEncryptionContext() throws Exception {
        sdkTester.assertSdkTestRunPasses("testCreatingWebViewWithMultipleEncryptionContext");
    }

    @Test
    public void testCreatingWebViewCreatesCookieSyncManager() throws Exception {
        sdkTester.assertSdkTestRunPasses("testCreatingWebViewCreatesCookieSyncManager");
    }

    @Test
    public void testFindAddress() throws Exception {
        sdkTester.assertSdkTestRunPasses("testFindAddress");
    }

    @Test
    public void testAccessHttpAuthUsernamePassword() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessHttpAuthUsernamePassword");
    }

    @Test
    public void testWebViewDatabaseAccessHttpAuthUsernamePassword() throws Exception {
        sdkTester.assertSdkTestRunPasses("testWebViewDatabaseAccessHttpAuthUsernamePassword");
    }

    @Test
    public void testScrollBarOverlay() throws Exception {
        sdkTester.assertSdkTestRunPasses("testScrollBarOverlay");
    }

    @Test
    public void testFlingScroll() throws Exception {
        sdkTester.assertSdkTestRunPasses("testFlingScroll");
    }

    @Test
    @Presubmit
    public void testLoadUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadUrl");
    }

    @Test
    public void testPostUrlWithNetworkUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testPostUrlWithNetworkUrl");
    }

    @Test
    public void testAppInjectedXRequestedWithHeaderIsNotOverwritten() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAppInjectedXRequestedWithHeaderIsNotOverwritten");
    }

    @Test
    public void testAppCanInjectHeadersViaImmutableMap() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAppCanInjectHeadersViaImmutableMap");
    }

    @Test
    public void testCanInjectHeaders() throws Exception {
        sdkTester.assertSdkTestRunPasses("testCanInjectHeaders");
    }

    @Test
    public void testGetVisibleTitleHeight() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGetVisibleTitleHeight");
    }

    @Test
    public void testGetOriginalUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGetOriginalUrl");
    }

    @Test
    public void testStopLoading() throws Exception {
        sdkTester.assertSdkTestRunPasses("testStopLoading");
    }

    @Test
    public void testGoBackAndForward() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGoBackAndForward");
    }

    @Test
    public void testAddJavascriptInterface() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAddJavascriptInterface");
    }

    @Test
    public void testAddJavascriptInterfaceNullObject() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAddJavascriptInterfaceNullObject");
    }

    @Test
    public void testRemoveJavascriptInterface() throws Exception {
        sdkTester.assertSdkTestRunPasses("testRemoveJavascriptInterface");
    }

    @Test
    public void testUseRemovedJavascriptInterface() throws Exception {
        sdkTester.assertSdkTestRunPasses("testUseRemovedJavascriptInterface");
    }

    @Test
    public void testAddJavascriptInterfaceExceptions() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAddJavascriptInterfaceExceptions");
    }

    @Test
    public void testJavascriptInterfaceCustomPropertiesClearedOnReload() throws Exception {
        sdkTester.assertSdkTestRunPasses("testJavascriptInterfaceCustomPropertiesClearedOnReload");
    }

    @Test
    @MediumTest
    public void testJavascriptInterfaceForClientPopup() throws Exception {
        sdkTester.assertSdkTestRunPasses("testJavascriptInterfaceForClientPopup");
    }

    @Test
    @MediumTest
    public void testLoadDataWithBaseUrl_historyUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadDataWithBaseUrl_historyUrl");
    }

    @Test
    public void testLoadDataWithBaseUrl_nullHistoryUrlShowsAsAboutBlank() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadDataWithBaseUrl_nullHistoryUrlShowsAsAboutBlank");
    }

    @Test
    public void testLoadDataWithBaseUrl_dataBaseUrlIgnoresHistoryUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadDataWithBaseUrl_dataBaseUrlIgnoresHistoryUrl");
    }

    @Test
    public void testLoadDataWithBaseUrl_unencodedContentHttpBaseUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadDataWithBaseUrl_unencodedContentHttpBaseUrl");
    }

    @Test
    public void testLoadDataWithBaseUrl_urlEncodedContentDataBaseUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadDataWithBaseUrl_urlEncodedContentDataBaseUrl");
    }

    @Test
    public void testLoadDataWithBaseUrl_nullSafe() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadDataWithBaseUrl_nullSafe");
    }

    @Test
    public void testSaveWebArchive() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSaveWebArchive");
    }

    @Test
    public void testFindAll() throws Exception {
        sdkTester.assertSdkTestRunPasses("testFindAll");
    }

    @Test
    public void testFindNext() throws Exception {
        sdkTester.assertSdkTestRunPasses("testFindNext");
    }

    @Test
    public void testPageScroll() throws Exception {
        sdkTester.assertSdkTestRunPasses("testPageScroll");
    }

    @Test
    public void testGetContentHeight() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGetContentHeight");
    }

    @Test
    public void testPlatformNotifications() throws Exception {
        sdkTester.assertSdkTestRunPasses("testPlatformNotifications");
    }

    @Test
    public void testAccessPluginList() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAccessPluginList");
    }

    @Test
    public void testDestroy() throws Exception {
        sdkTester.assertSdkTestRunPasses("testDestroy");
    }

    @Test
    public void testDebugDump() throws Exception {
        sdkTester.assertSdkTestRunPasses("testDebugDump");
    }

    @Test
    public void testSetInitialScale() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetInitialScale");
    }

    @Test
    public void testRequestChildRectangleOnScreen() throws Exception {
        sdkTester.assertSdkTestRunPasses("testRequestChildRectangleOnScreen");
    }

    @Test
    public void testSetLayoutParams() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetLayoutParams");
    }

    @Test
    public void testSetMapTrackballToArrowKeys() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetMapTrackballToArrowKeys");
    }

    @Test
    public void testPauseResumeTimers() throws Exception {
        sdkTester.assertSdkTestRunPasses("testPauseResumeTimers");
    }

    @Test
    public void testEvaluateJavascript() throws Exception {
        sdkTester.assertSdkTestRunPasses("testEvaluateJavascript");
    }

    @Test
    public void testPrinting() throws Exception {
        sdkTester.assertSdkTestRunPasses("testPrinting");
    }

    @Test
    public void testPrintingPagesCount() throws Exception {
        sdkTester.assertSdkTestRunPasses("testPrintingPagesCount");
    }

    @Test
    public void testVisualStateCallbackCalled() throws Exception {
        sdkTester.assertSdkTestRunPasses("testVisualStateCallbackCalled");
    }

    @Test
    public void testSetSafeBrowsingAllowlistWithMalformedList() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetSafeBrowsingAllowlistWithMalformedList");
    }

    @Test
    public void testSetSafeBrowsingAllowlistWithValidList() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetSafeBrowsingAllowlistWithValidList");
    }

    @Test
    public void testGetWebViewClient() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGetWebViewClient");
    }

    @Test
    public void testGetWebChromeClient() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGetWebChromeClient");
    }

    @Test
    public void testSetCustomTextClassifier() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetCustomTextClassifier");
    }

    @Test
    public void testGetSafeBrowsingPrivacyPolicyUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGetSafeBrowsingPrivacyPolicyUrl");
    }

    @Test
    public void testWebViewClassLoaderReturnsNonNull() throws Exception {
        sdkTester.assertSdkTestRunPasses("testWebViewClassLoaderReturnsNonNull");
    }

    @Test
    public void testCapturePicture() throws Exception {
        sdkTester.assertSdkTestRunPasses("testCapturePicture");
    }

    @Test
    public void testSetPictureListener() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetPictureListener");
    }

    @Test
    public void testLoadData() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadData");
    }

    @Test
    public void testLoadDataWithBaseUrl_resolvesRelativeToBaseUrl() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadDataWithBaseUrl_resolvesRelativeToBaseUrl");
    }

    @Test
    public void testLoadDataWithBaseUrl_javascriptCanAccessOrigin() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadDataWithBaseUrl_javascriptCanAccessOrigin");
    }

    @Test
    public void testDocumentHasImages() throws Exception {
        sdkTester.assertSdkTestRunPasses("testDocumentHasImages");
    }

    @Test
    public void testClearHistory() throws Exception {
        sdkTester.assertSdkTestRunPasses("testClearHistory");
    }

    @Test
    public void testSaveAndRestoreState() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSaveAndRestoreState");
    }

    @Test
    public void testSetDownloadListener() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetDownloadListener");
    }

    @Test
    public void testSetNetworkAvailable() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetNetworkAvailable");
    }

    @Test
    public void testSetWebChromeClient() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetWebChromeClient");
    }

    @Test
    public void testRequestFocusNodeHref() throws Exception {
        sdkTester.assertSdkTestRunPasses("testRequestFocusNodeHref");
    }

    @Test
    public void testRequestImageRef() throws Exception {
        sdkTester.assertSdkTestRunPasses("testRequestImageRef");
    }

    @Test
    public void testGetHitTestResult() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGetHitTestResult");
    }
}
