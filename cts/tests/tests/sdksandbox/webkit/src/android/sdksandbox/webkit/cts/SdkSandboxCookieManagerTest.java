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
public class SdkSandboxCookieManagerTest {
    @ClassRule
    public static final KeepSdkSandboxAliveRule sSdkTestSuiteSetup =
            new KeepSdkSandboxAliveRule("com.android.emptysdkprovider");

    @Rule
    public final WebViewSandboxTestRule sdkTester =
            new WebViewSandboxTestRule("android.webkit.cts.CookieManagerTest");

    @Test
    public void testGetInstance() throws Exception {
        sdkTester.assertSdkTestRunPasses("testGetInstance");
    }

    @Test
    public void testFlush() throws Exception {
        sdkTester.assertSdkTestRunPasses("testFlush");
    }

    @Test
    public void testAcceptCookie() throws Exception {
        sdkTester.assertSdkTestRunPasses("testAcceptCookie");
    }

    @Test
    public void testSetCookie() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetCookie");
    }

    @Test
    public void testSetCookieNullCallback() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetCookieNullCallback");
    }

    @Test
    public void testSetCookieCallback() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSetCookieCallback");
    }

    @Test
    public void testRemoveCookies() throws Exception {
        sdkTester.assertSdkTestRunPasses("testRemoveCookies");
    }

    @Test
    public void testRemoveCookiesNullCallback() throws Exception {
        sdkTester.assertSdkTestRunPasses("testRemoveCookiesNullCallback");
    }

    @Test
    public void testRemoveCookiesCallback() throws Exception {
        sdkTester.assertSdkTestRunPasses("testRemoveCookiesCallback");
    }

    @Test
    public void testThirdPartyCookie() throws Exception {
        sdkTester.assertSdkTestRunPasses("testThirdPartyCookie");
    }

    @Test
    public void testSameSiteLaxByDefault() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSameSiteLaxByDefault");
    }

    @Test
    public void testSameSiteNoneRequiresSecure() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSameSiteNoneRequiresSecure");
    }

    @Test
    public void testSchemefulSameSite() throws Exception {
        sdkTester.assertSdkTestRunPasses("testSchemefulSameSite");
    }

    @Test
    public void testb3167208() throws Exception {
        sdkTester.assertSdkTestRunPasses("testb3167208");
    }
}
