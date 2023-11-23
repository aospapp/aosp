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

package com.android.server.sdksandbox;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.Manifest;
import android.app.sdksandbox.SdkSandboxManager;
import android.app.sdksandbox.testutils.FakeLoadSdkCallbackBinder;
import android.app.sdksandbox.testutils.FakeRequestSurfacePackageCallbackBinder;
import android.app.sdksandbox.testutils.FakeSdkSandboxService;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.sdksandbox.SandboxLatencyInfo;

import org.junit.Before;
import org.junit.Test;

public class LoadSdkSessionUnitTest {

    private static final String TEST_PACKAGE = "com.android.server.sdksandbox.tests";
    private static final String SDK_NAME = "com.android.codeprovider";

    private Context mContext;
    private SdkSandboxManagerService.Injector mInjector;
    private FakeSdkSandboxService mSdkSandboxService;
    private CallingInfo mTestCallingInfo;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mInjector = new SdkSandboxManagerService.Injector(mContext);
        mSdkSandboxService = new FakeSdkSandboxService();
        mTestCallingInfo = new CallingInfo(Process.myUid(), TEST_PACKAGE);

        // Required for using MATCH_ANY_USER when fetching installed SDK.
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    @Test
    public void testLoadSdkSessionIsInitiallyPendingLoad() {
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext, null, mInjector, "random", mTestCallingInfo, null, null);
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOAD_PENDING);
        assertThat(sdkSession.getSandboxedSdk()).isNull();
    }

    @Test
    public void testGetSdkProviderInfo_SdkDoesNotExist() {
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext, null, mInjector, "random", mTestCallingInfo, null, null);
        assertThat(sdkSession.mSdkProviderInfo).isNull();
        assertThat(sdkSession.getSdkProviderErrorIfExists()).contains("not found for loading");
    }

    @Test
    public void testGetSdkProviderInfo_SdkIsValid() {
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext, null, mInjector, SDK_NAME, mTestCallingInfo, null, null);

        assertThat(sdkSession.mSdkProviderInfo).isNotNull();
        assertThat(sdkSession.mSdkProviderInfo.getSdkProviderClassName())
                .isEqualTo("test.class.name");
        assertThat(sdkSession.mSdkProviderInfo.getSdkInfo().getPackageName())
                .isEqualTo("com.android.codeprovider_1");
        assertThat(sdkSession.getSdkProviderErrorIfExists()).isEmpty();
    }

    @Test
    public void testLoadSdkIsSuccessful() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeSuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOADED);
        callback.assertLoadSdkIsSuccessful();
        assertThat(sdkSession.getSandboxedSdk()).isNotNull();
    }

    @Test
    public void testLoadSdkError() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session and fail it.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeError();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOAD_FAILED);
        callback.assertLoadSdkIsUnsuccessful();
    }

    // Verifies that the same load SDK session cannot be used multiple times to load an SDK even if
    // the first load failed.
    @Test
    public void testSecondLoadFails_ErrorOnFirstLoad_SameSession() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session and fail it.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeError();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOAD_FAILED);
        callback.assertLoadSdkIsUnsuccessful();

        // Trying to load the SDK again as part of the same session should fail.
        assertThrows(
                IllegalArgumentException.class,
                () -> sdkSession.load(mSdkSandboxService, "", "", -1, -1));
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOAD_FAILED);
    }

    @Test
    public void testLoadingSdkTwiceInSameSessionShouldFail() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeSuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOADED);
        callback.assertLoadSdkIsSuccessful();

        // Trying to load the SDK again as part of the same session should fail.
        assertThrows(
                IllegalArgumentException.class,
                () -> sdkSession.load(mSdkSandboxService, "", "", -1, -1));
        // Since the SDK had already been loaded, its status should still show as loaded regardless
        // of any invalid requests.
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOADED);
    }

    @Test
    public void testSandboxDiedOnLoadSdkRequest() {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Throw a DeadObjectException when loading the SDK.
        mSdkSandboxService.dieOnLoad = true;
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        callback.assertLoadSdkIsUnsuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOAD_FAILED);
    }

    @Test
    public void testLoadedSdkStatusAfterSandboxDeath() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeSuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOADED);
        callback.assertLoadSdkIsSuccessful();

        // Verify that the SDK status is unloaded.
        sdkSession.onSandboxDeath();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.UNLOADED);
    }

    @Test
    public void testLoadSdk_SandboxDiesBeforeCompletion() {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);

        // Before invoking the callback, kill the sandbox.
        sdkSession.onSandboxDeath();

        callback.assertLoadSdkIsUnsuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOAD_FAILED);
    }

    @Test
    public void testUnloadSdk() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeSuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOADED);
        callback.assertLoadSdkIsSuccessful();

        // Unloading SDK should go through as a successful request.
        sdkSession.unload(-1);
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.UNLOADED);
    }

    @Test
    public void testUnloadSdkThatIsBeingLoaded() throws Exception {
        // Create a new load session. That would be set to pending load by default.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOAD_PENDING);

        // Request to load the SDK in this session, but don't complete the request.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);

        // Unloading SDK should throw exception.
        assertThrows(IllegalArgumentException.class, () -> sdkSession.unload(-1));
        // The status after trying to unload should still be a pending load.
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOAD_PENDING);

        // Complete the loading.
        mSdkSandboxService.sendLoadCodeSuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOADED);
    }

    @Test
    public void testUnloadSdkAfterFailedLoading() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session and fail it.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeError();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOAD_FAILED);
        callback.assertLoadSdkIsUnsuccessful();

        sdkSession.unload(-1);
        // The status should still be failed load, instead of unload.
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOAD_FAILED);
    }

    @Test
    public void testSandboxDeathBeforeUnload() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeSuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOADED);
        callback.assertLoadSdkIsSuccessful();

        // Simulate a sandbox kill.
        sdkSession.onSandboxDeath();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.UNLOADED);

        sdkSession.unload(-1);
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.UNLOADED);
        // Verify that unload wss not called in the sandbox service.
        assertThat(mSdkSandboxService.isSdkUnloaded()).isFalse();
    }

    @Test
    public void testLoadingAfterUnloading_ShouldFail_SameSession() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeSuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOADED);
        callback.assertLoadSdkIsSuccessful();

        // Unloading SDK should go through as a successful request.
        sdkSession.unload(-1);
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.UNLOADED);

        // Trying to load the SDK again as part of the same session should fail.
        assertThrows(
                IllegalArgumentException.class,
                () -> sdkSession.load(mSdkSandboxService, "", "", -1, -1));
    }

    @Test
    public void testRequestSurfacePackage() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeSuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOADED);
        callback.assertLoadSdkIsSuccessful();

        FakeRequestSurfacePackageCallbackBinder surfacePackageCallback =
                new FakeRequestSurfacePackageCallbackBinder();
        sdkSession.requestSurfacePackage(
                new Binder(), 0, 500, 500, -1, new Bundle(), surfacePackageCallback);
        mSdkSandboxService.sendSurfacePackageReady(new SandboxLatencyInfo(-1));
        assertThat(surfacePackageCallback.isRequestSurfacePackageSuccessful()).isTrue();
    }

    @Test
    public void testRequestPackage_SdkNotLoaded() {
        // Create a new load session, but don't load the SDK
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        FakeRequestSurfacePackageCallbackBinder surfacePackageCallback =
                new FakeRequestSurfacePackageCallbackBinder();
        sdkSession.requestSurfacePackage(
                new Binder(), 0, 500, 500, -1, new Bundle(), surfacePackageCallback);

        assertThat(surfacePackageCallback.isRequestSurfacePackageSuccessful()).isFalse();
        assertThat(surfacePackageCallback.getSurfacePackageErrorCode())
                .isEqualTo(SdkSandboxManager.REQUEST_SURFACE_PACKAGE_SDK_NOT_LOADED);
    }

    @Test
    public void testRequestSurfacePackage_SandboxDiesInBetween() throws Exception {
        // Create a new load session.
        FakeLoadSdkCallbackBinder callback = new FakeLoadSdkCallbackBinder();
        LoadSdkSession sdkSession =
                new LoadSdkSession(
                        mContext,
                        null,
                        mInjector,
                        SDK_NAME,
                        mTestCallingInfo,
                        new Bundle(),
                        callback);

        // Load the SDK in this session.
        sdkSession.load(mSdkSandboxService, "", "", -1, -1);
        mSdkSandboxService.sendLoadCodeSuccessful();
        assertThat(sdkSession.getStatus()).isEqualTo(LoadSdkSession.LOADED);
        callback.assertLoadSdkIsSuccessful();

        // Request surface package but don't complete the request.
        FakeRequestSurfacePackageCallbackBinder surfacePackageCallback =
                new FakeRequestSurfacePackageCallbackBinder();
        sdkSession.requestSurfacePackage(
                new Binder(), 0, 500, 500, -1, new Bundle(), surfacePackageCallback);

        // Kill the sandbox in between.
        sdkSession.onSandboxDeath();
        assertThat(surfacePackageCallback.isRequestSurfacePackageSuccessful()).isFalse();
    }
}
