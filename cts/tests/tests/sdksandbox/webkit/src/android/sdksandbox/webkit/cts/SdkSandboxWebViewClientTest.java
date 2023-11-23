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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class SdkSandboxWebViewClientTest {
    // TODO(b/266051278): Uncomment this when we work out why preserving
    // the SDK sandbox manager between tests cases {@link testOnRenderProcessGone}
    // to fail.
    //
    // @ClassRule
    // public static final KeepSdkSandboxAliveRule sSdkTestSuiteSetup =
    //         new KeepSdkSandboxAliveRule("com.android.emptysdkprovider");

    @Rule
    public final WebViewSandboxTestRule sdkTester =
            new WebViewSandboxTestRule("android.webkit.cts.WebViewClientTest");

    @Test
    public void testShouldOverrideUrlLoadingDefault() throws Exception {
        sdkTester.assertSdkTestRunPasses("testShouldOverrideUrlLoadingDefault");
    }

    @Test
    public void testShouldOverrideUrlLoading() throws Exception {
        sdkTester.assertSdkTestRunPasses("testShouldOverrideUrlLoading");
    }

    @Test
    public void testShouldOverrideUrlLoadingOnCreateWindow() throws Exception {
        sdkTester.assertSdkTestRunPasses("testShouldOverrideUrlLoadingOnCreateWindow");
    }

    @Test
    public void testLoadPage() throws Exception {
        sdkTester.assertSdkTestRunPasses("testLoadPage");
    }

    @Test
    public void testOnReceivedLoginRequest() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnReceivedLoginRequest");
    }

    @Test
    public void testOnReceivedError() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnReceivedError");
    }

    @Test
    public void testOnReceivedErrorForSubresource() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnReceivedErrorForSubresource");
    }

    @Test
    public void testOnReceivedHttpError() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnReceivedHttpError");
    }

    @Test
    public void testOnFormResubmission() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnFormResubmission");
    }

    @Test
    public void testDoUpdateVisitedHistory() throws Exception {
        sdkTester.assertSdkTestRunPasses("testDoUpdateVisitedHistory");
    }

    @Test
    public void testOnReceivedHttpAuthRequest() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnReceivedHttpAuthRequest");
    }

    @Test
    public void testShouldOverrideKeyEvent() throws Exception {
        sdkTester.assertSdkTestRunPasses("testShouldOverrideKeyEvent");
    }

    @Test
    public void testOnUnhandledKeyEvent() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnUnhandledKeyEvent");
    }

    @Test
    public void testOnScaleChanged() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnScaleChanged");
    }

    @Test
    public void testShouldInterceptRequestParams() throws Exception {
        sdkTester.assertSdkTestRunPasses("testShouldInterceptRequestParams");
    }

    @Test
    public void testShouldInterceptRequestResponse() throws Exception {
        sdkTester.assertSdkTestRunPasses("testShouldInterceptRequestResponse");
    }

    @Test
    public void testOnRenderProcessGoneDefault() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnRenderProcessGoneDefault");
    }

    @Test
    public void testOnRenderProcessGone() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnRenderProcessGone");
    }

    // TODO(crbug/1245351): Remove @FlakyTest once bug fixed
    @FlakyTest
    @Test
    public void testOnSafeBrowsingHitBackToSafety() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnSafeBrowsingHitBackToSafety");
    }

    // TODO(crbug/1245351): Remove @FlakyTest once bug fixed
    @FlakyTest
    @Test
    public void testOnSafeBrowsingHitProceed() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnSafeBrowsingHitProceed");
    }

    // TODO(crbug/1245351): Remove @FlakyTest once bug fixed
    @FlakyTest
    @Test
    public void testOnSafeBrowsingMalwareCode() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnSafeBrowsingMalwareCode");
    }

    // TODO(crbug/1245351): Remove @FlakyTest once bug fixed
    @FlakyTest
    @Test
    public void testOnSafeBrowsingPhishingCode() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnSafeBrowsingPhishingCode");
    }

    // TODO(crbug/1245351): Remove @FlakyTest once bug fixed
    @FlakyTest
    @Test
    public void testOnSafeBrowsingUnwantedSoftwareCode() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnSafeBrowsingUnwantedSoftwareCode");
    }

    // TODO(crbug/1245351): Remove @FlakyTest once bug fixed
    @FlakyTest
    @Test
    public void testOnSafeBrowsingBillingCode() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnSafeBrowsingBillingCode");
    }

    @Test
    public void testOnPageCommitVisibleCalled() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnPageCommitVisibleCalled");
    }
}
