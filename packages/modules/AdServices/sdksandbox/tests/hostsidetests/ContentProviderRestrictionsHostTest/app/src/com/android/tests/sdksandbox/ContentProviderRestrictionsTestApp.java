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

package com.android.tests.sdksandbox;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.Manifest;
import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SdkSandboxManager;
import android.app.sdksandbox.testutils.FakeLoadSdkCallback;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DeviceConfig;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.tests.sdkprovider.restrictions.contentproviders.IContentProvidersSdkApi;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ContentProviderRestrictionsTestApp {
    private SdkSandboxManager mSdkSandboxManager;
    private static final String ENFORCE_CONTENT_PROVIDER_RESTRICTIONS =
            "enforce_content_provider_restrictions";

    private static final String SDK_PACKAGE =
            "com.android.tests.sdkprovider.restrictions.contentproviders";

    /** This rule is defined to start an activity in the foreground to call the sandbox APIs */
    @Rule
    public final ActivityScenarioRule mRule =
            new ActivityScenarioRule<>(SdkSandboxEmptyActivity.class);

    @Before
    public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        mSdkSandboxManager = context.getSystemService(SdkSandboxManager.class);
        assertThat(mSdkSandboxManager).isNotNull();
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.WRITE_DEVICE_CONFIG);
    }

    @Test
    public void testGetContentProvider_restrictionsApplied() throws Exception {
        mRule.getScenario();

        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                ENFORCE_CONTENT_PROVIDER_RESTRICTIONS,
                "true",
                false);

        FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_PACKAGE, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();
        SandboxedSdk sandboxedSdk = callback.getSandboxedSdk();

        IBinder binder = sandboxedSdk.getInterface();
        IContentProvidersSdkApi contentProvidersSdkApi =
                IContentProvidersSdkApi.Stub.asInterface(binder);

        assertThrows(SecurityException.class, () -> contentProvidersSdkApi.getContentProvider());
    }

    @Test
    public void testRegisterContentObserver_restrictionsApplied() throws Exception {
        mRule.getScenario();

        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                ENFORCE_CONTENT_PROVIDER_RESTRICTIONS,
                "true",
                false);

        FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_PACKAGE, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();
        SandboxedSdk sandboxedSdk = callback.getSandboxedSdk();

        IBinder binder = sandboxedSdk.getInterface();
        IContentProvidersSdkApi contentProvidersSdkApi =
                IContentProvidersSdkApi.Stub.asInterface(binder);

        assertThrows(
                SecurityException.class, () -> contentProvidersSdkApi.registerContentObserver());
    }

    @Test(expected = Test.None.class /* no exception expected */)
    public void testGetContentProvider_restrictionsNotApplied() throws Exception {
        mRule.getScenario();

        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                ENFORCE_CONTENT_PROVIDER_RESTRICTIONS,
                "false",
                false);

        FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_PACKAGE, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();
        SandboxedSdk sandboxedSdk = callback.getSandboxedSdk();

        IBinder binder = sandboxedSdk.getInterface();
        IContentProvidersSdkApi contentProvidersSdkApi =
                IContentProvidersSdkApi.Stub.asInterface(binder);

        contentProvidersSdkApi.getContentProvider();
    }

    @Test(expected = Test.None.class /* no exception expected */)
    public void testRegisterContentObserver_restrictionsNotApplied() throws Exception {
        mRule.getScenario();

        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                ENFORCE_CONTENT_PROVIDER_RESTRICTIONS,
                "false",
                false);

        FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_PACKAGE, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();
        SandboxedSdk sandboxedSdk = callback.getSandboxedSdk();

        IBinder binder = sandboxedSdk.getInterface();
        IContentProvidersSdkApi contentProvidersSdkApi =
                IContentProvidersSdkApi.Stub.asInterface(binder);

        contentProvidersSdkApi.registerContentObserver();
    }

    @Test(expected = Test.None.class /* no exception expected */)
    public void testGetContentProvider_defaultValueRestrictionsNotApplied() throws Exception {
        /** Ensuring that the property is not present in DeviceConfig */
        DeviceConfig.deleteProperty(
                DeviceConfig.NAMESPACE_ADSERVICES, ENFORCE_CONTENT_PROVIDER_RESTRICTIONS);

        FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_PACKAGE, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();
        SandboxedSdk sandboxedSdk = callback.getSandboxedSdk();

        IBinder binder = sandboxedSdk.getInterface();
        IContentProvidersSdkApi contentProvidersSdkApi =
                IContentProvidersSdkApi.Stub.asInterface(binder);

        contentProvidersSdkApi.getContentProvider();
    }

    @Test(expected = Test.None.class /* no exception expected */)
    public void testRegisterContentObserver_defaultValueRestrictionsNotApplied() throws Exception {
        /** Ensuring that the property is not present in DeviceConfig */
        DeviceConfig.deleteProperty(
                DeviceConfig.NAMESPACE_ADSERVICES, ENFORCE_CONTENT_PROVIDER_RESTRICTIONS);

        FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_PACKAGE, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();
        SandboxedSdk sandboxedSdk = callback.getSandboxedSdk();

        IBinder binder = sandboxedSdk.getInterface();
        IContentProvidersSdkApi contentProvidersSdkApi =
                IContentProvidersSdkApi.Stub.asInterface(binder);

        contentProvidersSdkApi.registerContentObserver();
    }
}
