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

package com.android.sdksandbox.cts.app;

import static com.google.common.truth.Truth.assertThat;

import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SdkSandboxManager;
import android.app.sdksandbox.testutils.FakeLoadSdkCallback;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.sdksandbox.cts.provider.mediationtest.IMediationTestSdkApi;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RunWith(JUnit4.class)
public class SdkSandboxMediationTestApp {

    private static final String SDK_NAME = "com.android.sdksandbox.cts.provider.mediationtest";
    private static final String SDK_NAME_2 = "com.android.sdksandbox.cts.provider.storagetest";

    @Rule public final ActivityScenarioRule mRule = new ActivityScenarioRule<>(TestActivity.class);

    private Context mContext;
    private SdkSandboxManager mSdkSandboxManager;
    private IMediationTestSdkApi mSdk;

    @Before
    public void setup() {
        mContext = ApplicationProvider.getApplicationContext();
        mSdkSandboxManager = mContext.getSystemService(SdkSandboxManager.class);
        assertThat(mSdkSandboxManager).isNotNull();
        mRule.getScenario();
        // unload SDK to fix flakiness
        mSdkSandboxManager.unloadSdk(SDK_NAME);
        mSdkSandboxManager.unloadSdk(SDK_NAME_2);
    }

    @After
    public void tearDown() {
        // unload SDK to fix flakiness
        if (mSdkSandboxManager != null) {
            mSdkSandboxManager.unloadSdk(SDK_NAME);
            mSdkSandboxManager.unloadSdk(SDK_NAME_2);
        }
    }

    @Test
    public void testGetSandboxedSdk_GetsAllSdksLoadedInTheSandbox() throws Exception {
        loadMediatorSdkAndPopulateInterface();

        final List<SandboxedSdk> sandboxedSdks = mSdk.getSandboxedSdks();
        assertThat(sandboxedSdks).hasSize(1);
        assertThat(sandboxedSdks.get(0).getInterface().getInterfaceDescriptor())
                .isEqualTo(
                        "com.android.sdksandbox.cts.provider.mediationtest"
                                + ".IMediationTestSdkApi");
    }

    @Test
    public void testGetSandboxedSdk_MultipleSdks() throws Exception {
        loadMediatorSdkAndPopulateInterface();
        loadSdk2();

        final List<SandboxedSdk> sandboxedSdks = mSdk.getSandboxedSdks();
        assertThat(sandboxedSdks).hasSize(2);
        Set<String> interfaceDescriptors =
                sandboxedSdks.stream()
                        .map(
                                s -> {
                                    try {
                                        return s.getInterface().getInterfaceDescriptor();
                                    } catch (RemoteException e) {
                                        // Pass through exception
                                    }
                                    return null;
                                })
                        .collect(Collectors.toSet());

        assertThat(interfaceDescriptors)
                .containsExactly(
                        "com.android.sdksandbox.cts.provider.storagetest" + ".IStorageTestSdkApi",
                        "com.android.sdksandbox.cts.provider.mediationtest"
                                + ".IMediationTestSdkApi");
    }

    private void loadMediatorSdkAndPopulateInterface() {
        FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();

        // Store the returned SDK interface so that we can interact with it later.
        mSdk = IMediationTestSdkApi.Stub.asInterface(callback.getSandboxedSdk().getInterface());
    }

    private void loadSdk2() {
        FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_2, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();
    }
}
