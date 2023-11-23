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

@AppModeFull
@RunWith(AndroidJUnit4.class)
public class SdkSandboxWebViewSslTest {
    @ClassRule
    public static final KeepSdkSandboxAliveRule sSdkTestSuiteSetup =
            new KeepSdkSandboxAliveRule("com.android.emptysdkprovider");

    @Rule
    public final WebViewSandboxTestRule sdkTester =
            new WebViewSandboxTestRule("android.webkit.cts.WebViewSslTest");

    @Test
    @MediumTest
    public void testInsecureSiteClearsCertificate() throws Exception {
        sdkTester.assertSdkTestRunPasses("testInsecureSiteClearsCertificate");
    }

    @Test
    @MediumTest
    public void testSecureSiteSetsCertificate() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSecureSiteSetsCertificate");
    }

    @Test
    @MediumTest
    public void testClearSslPreferences() throws Exception {
        sdkTester.assertSdkTestRunPasses("testClearSslPreferences");
    }

    @Test
    @MediumTest
    public void testOnReceivedSslError() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnReceivedSslError");
    }

    @Test
    @MediumTest
    public void testOnReceivedSslErrorProceed() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnReceivedSslErrorProceed");
    }

    @Test
    @MediumTest
    public void testOnReceivedSslErrorCancel() throws Exception {
        sdkTester.assertSdkTestRunPasses("testOnReceivedSslErrorCancel");
    }

    @Test
    @MediumTest
    public void testSslErrorProceedResponseReusedForSameHost() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSslErrorProceedResponseReusedForSameHost");
    }

    @Test
    @MediumTest
    public void testSslErrorProceedResponseNotReusedForDifferentHost() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSslErrorProceedResponseNotReusedForDifferentHost");
    }

    @Test
    @MediumTest
    public void testSecureServerRequestingClientCertDoesNotCancelRequest() throws Exception {
        sdkTester.assertSdkTestRunPasses(
                "testSecureServerRequestingClientCertDoesNotCancelRequest");
    }

    @Test
    @MediumTest
    public void testSecureServerRequiringClientCertDoesCancelRequest() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSecureServerRequiringClientCertDoesCancelRequest");
    }

    @Test
    @MediumTest
    public void testProceedClientCertRequest() throws Exception {
        sdkTester.assertSdkTestRunPasses("testProceedClientCertRequest");
    }

    @Test
    @MediumTest
    public void testIgnoreClientCertRequest() throws Exception {
        sdkTester.assertSdkTestRunPasses("testIgnoreClientCertRequest");
    }

    @Test
    @MediumTest
    public void testCancelClientCertRequest() throws Exception {
        sdkTester.assertSdkTestRunPasses("testCancelClientCertRequest");
    }

    @Test
    @MediumTest
    public void testClientCertIssuersReceivedCorrectly() throws Exception {
        sdkTester.assertSdkTestRunPasses("testClientCertIssuersReceivedCorrectly");
    }
}
