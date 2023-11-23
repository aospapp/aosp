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

package com.android.tests.sdksandbox.endtoend;

import static android.app.sdksandbox.SdkSandboxManager.EXTRA_DISPLAY_ID;
import static android.app.sdksandbox.SdkSandboxManager.EXTRA_HEIGHT_IN_PIXELS;
import static android.app.sdksandbox.SdkSandboxManager.EXTRA_HOST_TOKEN;
import static android.app.sdksandbox.SdkSandboxManager.EXTRA_WIDTH_IN_PIXELS;
import static android.app.sdksandbox.SdkSandboxManager.LOAD_SDK_INTERNAL_ERROR;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.sdksandbox.LoadSdkException;
import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SdkSandboxManager;
import android.app.sdksandbox.testutils.FakeLoadSdkCallback;
import android.app.sdksandbox.testutils.FakeRequestSurfacePackageCallback;
import android.app.sdksandbox.testutils.FakeSdkSandboxProcessDeathCallback;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.ctssdkprovider.IActivityStarter;
import com.android.ctssdkprovider.ICtsSdkProviderApi;
import com.android.modules.utils.build.SdkLevel;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

/** End-to-end tests of {@link SdkSandboxManager} APIs. */
@RunWith(JUnit4.class)
public class SdkSandboxManagerTest {

    private static final String NON_EXISTENT_SDK = "com.android.not_exist";
    private static final String SDK_NAME_1 = "com.android.ctssdkprovider";
    private static final String SDK_NAME_2 = "com.android.emptysdkprovider";

    private static final String TEST_OPTION = "test-option";
    private static final String OPTION_THROW_INTERNAL_ERROR = "internal-error";
    private static final String OPTION_THROW_REQUEST_SURFACE_PACKAGE_ERROR = "rsp-error";

    @Rule
    public final ActivityScenarioRule<TestActivity> mRule =
            new ActivityScenarioRule<>(TestActivity.class);

    @Rule public final Expect mExpect = Expect.create();

    private ActivityScenario<TestActivity> mScenario;

    private SdkSandboxManager mSdkSandboxManager;

    @Before
    public void setup() {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mSdkSandboxManager = context.getSystemService(SdkSandboxManager.class);
        unloadAllSdks();
        mScenario = mRule.getScenario();
    }

    @After
    public void tearDown() {
        unloadAllSdks();
    }

    private void unloadAllSdks() {
        try {
            mSdkSandboxManager.unloadSdk(SDK_NAME_1);
            mSdkSandboxManager.unloadSdk(SDK_NAME_2);
        } catch (Exception ignored) {
        }
    }

    @Test
    public void testGetSdkSandboxState() {
        int state = SdkSandboxManager.getSdkSandboxState();
        assertThat(state).isEqualTo(SdkSandboxManager.SDK_SANDBOX_STATE_ENABLED_PROCESS_ISOLATION);
    }

    @Test
    public void testLoadSdkSuccessfully() {
        final FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();
        assertNotNull(callback.getSandboxedSdk());
        assertNotNull(callback.getSandboxedSdk().getInterface());
    }

    @Test
    public void testGetSandboxedSdkSuccessfully() {
        loadSdk();

        List<SandboxedSdk> sandboxedSdks = mSdkSandboxManager.getSandboxedSdks();

        assertThat(sandboxedSdks.size()).isEqualTo(1);
        assertThat(sandboxedSdks.get(0).getSharedLibraryInfo().getName()).isEqualTo(SDK_NAME_1);

        mSdkSandboxManager.unloadSdk(SDK_NAME_1);
        List<SandboxedSdk> sandboxedSdksAfterUnload = mSdkSandboxManager.getSandboxedSdks();
        assertThat(sandboxedSdksAfterUnload.size()).isEqualTo(0);
    }

    @Test
    public void testLoadSdkAndCheckClassloader() throws Exception {
        ICtsSdkProviderApi sdk = loadSdk();
        sdk.checkClassloaders();
    }

    @Test
    public void testGetOpPackageName() throws Exception {
        ICtsSdkProviderApi sdk = loadSdk();
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getContext().getPackageManager();
        assertThat(sdk.getOpPackageName()).isEqualTo(pm.getSdkSandboxPackageName());
    }

    @Test
    public void testRetryLoadSameSdkShouldFail() {
        FakeLoadSdkCallback callback = new FakeLoadSdkCallback();

        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();

        callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsUnsuccessful();
        assertThat(callback.getLoadSdkErrorCode())
                .isEqualTo(SdkSandboxManager.LOAD_SDK_ALREADY_LOADED);
    }

    @Test
    public void testLoadNonExistentSdk() {
        final FakeLoadSdkCallback callback = new FakeLoadSdkCallback();

        mSdkSandboxManager.loadSdk(NON_EXISTENT_SDK, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsUnsuccessful();
        assertThat(callback.getLoadSdkErrorCode())
                .isEqualTo(SdkSandboxManager.LOAD_SDK_NOT_FOUND);
        LoadSdkException loadSdkException = callback.getLoadSdkException();
        assertThat(loadSdkException.getExtraInformation()).isNotNull();
        assertThat(loadSdkException.getExtraInformation().isEmpty()).isTrue();
    }

    @Test
    public void testLoadSdkWithInternalErrorShouldFail() throws Exception {
        final FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        Bundle params = new Bundle();
        params.putString(TEST_OPTION, OPTION_THROW_INTERNAL_ERROR);
        mSdkSandboxManager.loadSdk(SDK_NAME_1, params, Runnable::run, callback);
        callback.assertLoadSdkIsUnsuccessful();
        assertThat(callback.getLoadSdkErrorCode())
                .isEqualTo(SdkSandboxManager.LOAD_SDK_SDK_DEFINED_ERROR);
    }

    @Test
    public void testUnloadAndReloadSdk() throws Exception {
        final FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();

        mSdkSandboxManager.unloadSdk(SDK_NAME_1);
        // Wait till SDK is unloaded.
        Thread.sleep(2000);

        // Calls to an unloaded SDK should fail.
        final FakeRequestSurfacePackageCallback requestSurfacePackageCallback =
                new FakeRequestSurfacePackageCallback();
        mSdkSandboxManager.requestSurfacePackage(
                SDK_NAME_1,
                getRequestSurfacePackageParams(),
                Runnable::run,
                requestSurfacePackageCallback);

        assertThat(requestSurfacePackageCallback.isRequestSurfacePackageSuccessful()).isFalse();
        assertThat(requestSurfacePackageCallback.getSurfacePackageErrorCode())
                .isEqualTo(SdkSandboxManager.REQUEST_SURFACE_PACKAGE_SDK_NOT_LOADED);

        // SDK can be reloaded after being unloaded.
        final FakeLoadSdkCallback callback2 = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback2);
        callback2.assertLoadSdkIsSuccessful();
    }

    @Test
    public void testUnloadNonexistentSdk() {
        final FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();

        final String nonexistentSdk = "com.android.nonexistent";
        // Unloading does nothing - call should go through without error.
        mSdkSandboxManager.unloadSdk(nonexistentSdk);
    }

    @Test
    public void testReloadingSdkDoesNotInvalidateIt() {

        final FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();
        SandboxedSdk sandboxedSdk = callback.getSandboxedSdk();
        assertNotNull(sandboxedSdk.getInterface());

        // Attempt to load the SDK again and see that it fails.
        final FakeLoadSdkCallback reloadCallback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, reloadCallback);
        reloadCallback.assertLoadSdkIsUnsuccessful();

        // SDK's interface should still be obtainable.
        assertNotNull(sandboxedSdk.getInterface());

        // Further calls to the SDK should still be valid.
        final FakeRequestSurfacePackageCallback surfacePackageCallback =
                new FakeRequestSurfacePackageCallback();
        mSdkSandboxManager.requestSurfacePackage(
                SDK_NAME_1,
                getRequestSurfacePackageParams(),
                Runnable::run,
                surfacePackageCallback);
        assertThat(surfacePackageCallback.isRequestSurfacePackageSuccessful()).isTrue();
    }

    @Test
    public void testReloadingSdkAfterKillingSandboxIsSuccessful() throws Exception {
        // Kill the sandbox if it already exists from previous tests
        killSandboxIfExists();

        FakeSdkSandboxProcessDeathCallback callback = new FakeSdkSandboxProcessDeathCallback();
        mSdkSandboxManager.addSdkSandboxProcessDeathCallback(Runnable::run, callback);
        assertThat(callback.getSdkSandboxDeathCount()).isEqualTo(0);

        // Killing the sandbox and loading the same SDKs again multiple times should work
        for (int i = 1; i <= 3; ++i) {
            // The same SDKs should be able to be loaded again after sandbox death
            loadMultipleSdks();
            killSandbox();
            assertThat(callback.getSdkSandboxDeathCount()).isEqualTo(i);
        }
    }

    @Test
    public void testAddSdkSandboxProcessDeathCallback_BeforeStartingSandbox() throws Exception {
        // Kill the sandbox if it already exists from previous tests
        killSandboxIfExists();

        // Add a sandbox lifecycle callback before starting the sandbox
        FakeSdkSandboxProcessDeathCallback lifecycleCallback =
                new FakeSdkSandboxProcessDeathCallback();
        mSdkSandboxManager.addSdkSandboxProcessDeathCallback(Runnable::run, lifecycleCallback);

        // Bring up the sandbox
        loadSdk();

        killSandbox();
        assertThat(lifecycleCallback.getSdkSandboxDeathCount()).isEqualTo(1);
    }

    @Test
    public void testAddSdkSandboxProcessDeathCallback_AfterStartingSandbox() throws Exception {
        // Bring up the sandbox
        loadSdk();

        // Add a sandbox lifecycle callback before starting the sandbox
        FakeSdkSandboxProcessDeathCallback lifecycleCallback =
                new FakeSdkSandboxProcessDeathCallback();
        mSdkSandboxManager.addSdkSandboxProcessDeathCallback(Runnable::run, lifecycleCallback);

        killSandbox();
        assertThat(lifecycleCallback.getSdkSandboxDeathCount()).isEqualTo(1);
    }

    @Test
    public void testRegisterMultipleSdkSandboxProcessDeathCallbacks() throws Exception {
        // Kill the sandbox if it already exists from previous tests
        killSandboxIfExists();

        // Add a sandbox lifecycle callback before starting the sandbox
        FakeSdkSandboxProcessDeathCallback lifecycleCallback1 =
                new FakeSdkSandboxProcessDeathCallback();
        mSdkSandboxManager.addSdkSandboxProcessDeathCallback(Runnable::run, lifecycleCallback1);

        // Bring up the sandbox
        loadSdk();

        // Add another sandbox lifecycle callback after starting it
        FakeSdkSandboxProcessDeathCallback lifecycleCallback2 =
                new FakeSdkSandboxProcessDeathCallback();
        mSdkSandboxManager.addSdkSandboxProcessDeathCallback(Runnable::run, lifecycleCallback2);

        killSandbox();
        assertThat(lifecycleCallback1.getSdkSandboxDeathCount()).isEqualTo(1);
        assertThat(lifecycleCallback2.getSdkSandboxDeathCount()).isEqualTo(1);
    }

    @Test
    public void testRemoveSdkSandboxProcessDeathCallback() throws Exception {
        // Bring up the sandbox
        loadSdk();

        // Add and remove a sandbox lifecycle callback
        FakeSdkSandboxProcessDeathCallback lifecycleCallback1 =
                new FakeSdkSandboxProcessDeathCallback();
        mSdkSandboxManager.addSdkSandboxProcessDeathCallback(Runnable::run, lifecycleCallback1);
        mSdkSandboxManager.removeSdkSandboxProcessDeathCallback(lifecycleCallback1);

        // Add a lifecycle callback but don't remove it
        FakeSdkSandboxProcessDeathCallback lifecycleCallback2 =
                new FakeSdkSandboxProcessDeathCallback();
        mSdkSandboxManager.addSdkSandboxProcessDeathCallback(Runnable::run, lifecycleCallback2);

        killSandbox();
        assertThat(lifecycleCallback1.getSdkSandboxDeathCount()).isEqualTo(0);
        assertThat(lifecycleCallback2.getSdkSandboxDeathCount()).isEqualTo(1);
    }

    @Test
    public void testRequestSurfacePackageSuccessfully() {
        loadSdk();

        final FakeRequestSurfacePackageCallback surfacePackageCallback =
                new FakeRequestSurfacePackageCallback();
        mSdkSandboxManager.requestSurfacePackage(
                SDK_NAME_1,
                getRequestSurfacePackageParams(),
                Runnable::run,
                surfacePackageCallback);
        assertThat(surfacePackageCallback.isRequestSurfacePackageSuccessful()).isTrue();
    }

    @Test
    public void testRequestSurfacePackageWithInternalErrorShouldFail() {
        loadSdk();

        final FakeRequestSurfacePackageCallback surfacePackageCallback =
                new FakeRequestSurfacePackageCallback();
        Bundle params = getRequestSurfacePackageParams();
        params.putString(TEST_OPTION, OPTION_THROW_REQUEST_SURFACE_PACKAGE_ERROR);
        mSdkSandboxManager.requestSurfacePackage(
                SDK_NAME_1, params, Runnable::run, surfacePackageCallback);
        assertThat(surfacePackageCallback.isRequestSurfacePackageSuccessful()).isFalse();
        assertThat(surfacePackageCallback.getSurfacePackageErrorCode())
                .isEqualTo(SdkSandboxManager.REQUEST_SURFACE_PACKAGE_INTERNAL_ERROR);
        assertThat(surfacePackageCallback.getExtraErrorInformation()).isNotNull();
        assertThat(surfacePackageCallback.getExtraErrorInformation().isEmpty()).isTrue();
    }

    @Test
    public void testRequestSurfacePackage_SandboxDiesAfterLoadingSdk() throws Exception {
        loadSdk();

        assertThat(killSandboxIfExists()).isTrue();

        final FakeRequestSurfacePackageCallback surfacePackageCallback =
                new FakeRequestSurfacePackageCallback();
        mSdkSandboxManager.requestSurfacePackage(
                SDK_NAME_1,
                getRequestSurfacePackageParams(),
                Runnable::run,
                surfacePackageCallback);
        assertThat(surfacePackageCallback.isRequestSurfacePackageSuccessful()).isFalse();
        assertThat(surfacePackageCallback.getSurfacePackageErrorCode())
                .isEqualTo(SdkSandboxManager.REQUEST_SURFACE_PACKAGE_SDK_NOT_LOADED);
    }

    @Test
    public void testResourcesAndAssets() throws Exception {
        ICtsSdkProviderApi sdk = loadSdk();
        sdk.checkResourcesAndAssets();
    }

    @Test
    public void testLoadSdkInBackgroundFails() throws Exception {
        mScenario.moveToState(Lifecycle.State.DESTROYED);

        // Wait for the activity to be destroyed
        Thread.sleep(1000);

        final FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback);

        LoadSdkException thrown = callback.getLoadSdkException();

        assertEquals(LOAD_SDK_INTERNAL_ERROR, thrown.getLoadSdkErrorCode());
        assertThat(thrown).hasMessageThat().contains("does not run in the foreground");
    }

    @Test
    public void testSandboxApisAreUsableAfterUnbindingSandbox() throws Exception {
        FakeLoadSdkCallback callback1 = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback1);
        callback1.assertLoadSdkIsSuccessful();

        // Move the app to the background and bring it back to the foreground again.
        mScenario.recreate();

        // Loading another sdk should work without issue
        FakeLoadSdkCallback callback2 = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_2, new Bundle(), Runnable::run, callback2);
        callback2.assertLoadSdkIsSuccessful();

        // Requesting surface package from the first loaded sdk should work.
        final FakeRequestSurfacePackageCallback surfacePackageCallback =
                new FakeRequestSurfacePackageCallback();
        mSdkSandboxManager.requestSurfacePackage(
                SDK_NAME_1,
                getRequestSurfacePackageParams(),
                Runnable::run,
                surfacePackageCallback);
        assertThat(surfacePackageCallback.isRequestSurfacePackageSuccessful()).isTrue();
    }

    /** Checks that {@code SdkSandbox.apk} only requests normal permissions in its manifest. */
    // TODO: This should probably be a separate test module
    @Test
    public void testSdkSandboxPermissions() throws Exception {
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getContext().getPackageManager();
        final PackageInfo sdkSandboxPackage =
                pm.getPackageInfo(
                        pm.getSdkSandboxPackageName(),
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS));
        for (int i = 0; i < sdkSandboxPackage.requestedPermissions.length; i++) {
            final String permissionName = sdkSandboxPackage.requestedPermissions[i];
            final PermissionInfo permissionInfo = pm.getPermissionInfo(permissionName, 0);
            mExpect.withMessage("SdkSandbox.apk requests non-normal permission " + permissionName)
                    .that(permissionInfo.getProtection())
                    .isEqualTo(PermissionInfo.PROTECTION_NORMAL);
        }
    }

    // TODO(b/244730098): The test below needs to be moved from e2e.
    // It is not and e2e test.
    @Test
    public void testLoadSdkExceptionWriteToParcel() {
        final Bundle bundle = new Bundle();
        bundle.putChar("testKey", /*testValue=*/ 'C');
        final String errorMessage = "Error Message";
        final Exception cause = new Exception(errorMessage);

        final LoadSdkException exception = new LoadSdkException(cause, bundle);

        final Parcel parcel = Parcel.obtain();
        exception.writeToParcel(parcel, /*flags=*/ 0);

        // Create LoadSdkException with the same parcel
        parcel.setDataPosition(0); // rewind
        final LoadSdkException exceptionCheck = LoadSdkException.CREATOR.createFromParcel(parcel);

        assertThat(exceptionCheck.getLoadSdkErrorCode()).isEqualTo(exception.getLoadSdkErrorCode());
        assertThat(exceptionCheck.getMessage()).isEqualTo(exception.getMessage());
        assertThat(exceptionCheck.getExtraInformation().getChar("testKey"))
                .isEqualTo(exception.getExtraInformation().getChar("testKey"));
        assertThat(exceptionCheck.getExtraInformation().keySet()).containsExactly("testKey");
    }

    // TODO(b/244730098): The test below needs to be moved from e2e.
    // It is not and e2e test.
    @Test
    public void testLoadSdkExceptionDescribeContents() throws Exception {
        final LoadSdkException exception = new LoadSdkException(new Exception(), new Bundle());
        assertThat(exception.describeContents()).isEqualTo(0);
    }

    // TODO(b/244730098): The test below needs to be moved from e2e.
    // It is not and e2e test.
    @Test
    public void testSandboxedSdkDescribeContents() throws Exception {
        final SandboxedSdk sandboxedSdk = new SandboxedSdk(new Binder());
        assertThat(sandboxedSdk.describeContents()).isEqualTo(0);
    }

    @Test
    public void testSdkAndAppProcessImportanceIsAligned_AppIsBackgrounded() throws Exception {
        // Sandbox and app priority is aligned only in U+.
        assumeTrue(SdkLevel.isAtLeastU());

        ICtsSdkProviderApi sdk = loadSdk();
        assertThat(sdk.getProcessImportance()).isEqualTo(getAppProcessImportance());

        // Move the app to the background.
        mScenario.moveToState(Lifecycle.State.DESTROYED);
        Thread.sleep(1000);

        assertThat(sdk.getProcessImportance()).isEqualTo(getAppProcessImportance());
    }

    @Test
    public void testSdkAndAppProcessImportanceIsAligned_AppIsBackgroundedAndForegrounded()
            throws Exception {
        // Sandbox and app priority is aligned only in U+.
        assumeTrue(SdkLevel.isAtLeastU());

        ICtsSdkProviderApi sdk = loadSdk();
        assertThat(sdk.getProcessImportance()).isEqualTo(getAppProcessImportance());

        // Move the app to the background and bring it back to the foreground again.
        mScenario.recreate();

        // The sandbox should have foreground importance again.
        assertThat(sdk.getProcessImportance()).isEqualTo(getAppProcessImportance());
    }

    @Test
    public void testStartSdkSandboxedActivities() {
        assumeTrue(SdkLevel.isAtLeastU());

        // Load SDK in sandbox
        ICtsSdkProviderApi sdk = loadSdk();

        mRule.getScenario()
                .onActivity(
                        clientActivity -> {
                            ActivityStarter activityStarter1 = new ActivityStarter(clientActivity);
                            try {
                                sdk.startActivity(activityStarter1);
                                // Wait for the activity to start and send confirmation back
                                Thread.sleep(1000);
                                assertThat(activityStarter1.isActivityStarted()).isTrue();
                            } catch (Exception e) {
                                fail(
                                        "Exception is thrown while starting activity: "
                                                + e.getMessage());
                            }

                            // Start another sandbox activity is important, to make sure that the
                            // system is not restarting the sandbox activities after test is done.
                            ActivityStarter activityStarter2 = new ActivityStarter(clientActivity);
                            try {
                                sdk.startActivity(activityStarter2);
                                // Wait for the activity to start and send confirmation back
                                Thread.sleep(1000);
                                assertThat(activityStarter2.isActivityStarted()).isTrue();
                            } catch (Exception e) {
                                fail(
                                        "Exception is thrown while starting activity: "
                                                + e.getMessage());
                            }
                        });
    }

    @Test
    public void testStartSdkSandboxedActivityFailIfTheHandlerUnregistered() {
        assumeTrue(SdkLevel.isAtLeastU());

        // Load SDK in sandbox
        ICtsSdkProviderApi sdk = loadSdk();

        mRule.getScenario()
                .onActivity(
                        activity -> {
                            ActivityStarter activityStarter = new ActivityStarter(activity);
                            try {
                                sdk.startActivityAfterUnregisterHandler(activityStarter);
                                // Wait for the activity to be initiated and destroyed.
                                Thread.sleep(1000);
                                assertThat(activityStarter.isActivityStarted()).isFalse();
                            } catch (Exception e) {
                                fail(
                                        "Exception is thrown while starting activity: "
                                                + e.getMessage());
                            }
                        });
    }

    // Helper method to load SDK_NAME_1
    private ICtsSdkProviderApi loadSdk() {
        final FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();

        final SandboxedSdk sandboxedSdk = callback.getSandboxedSdk();
        assertNotNull(sandboxedSdk);
        return ICtsSdkProviderApi.Stub.asInterface(callback.getSandboxedSdk().getInterface());
    }

    private int getAppProcessImportance() {
        ActivityManager.RunningAppProcessInfo processInfo =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(processInfo);
        return processInfo.importance;
    }

    private class ActivityStarter extends IActivityStarter.Stub {
        private Activity mActivity;
        private boolean mActivityStarted = false;

        ActivityStarter(Activity activity) {
            this.mActivity = activity;
        }

        @Override
        public void startActivity(IBinder token) throws RemoteException {
            mSdkSandboxManager.startSdkSandboxActivity(mActivity, token);
        }
        // SDK will call this function to notify that the activity is successfully created
        @Override
        public void activityStartedSuccessfully() {
            mActivityStarted = true;
        }

        public boolean isActivityStarted() {
            return mActivityStarted;
        }
    }

    private Bundle getRequestSurfacePackageParams() {
        Bundle params = new Bundle();
        params.putInt(EXTRA_WIDTH_IN_PIXELS, 500);
        params.putInt(EXTRA_HEIGHT_IN_PIXELS, 500);
        params.putInt(EXTRA_DISPLAY_ID, 0);
        params.putBinder(EXTRA_HOST_TOKEN, new Binder());

        return params;
    }

    private void loadMultipleSdks() {
        FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_1, new Bundle(), Runnable::run, callback);
        callback.assertLoadSdkIsSuccessful();

        FakeLoadSdkCallback callback2 = new FakeLoadSdkCallback();
        mSdkSandboxManager.loadSdk(SDK_NAME_2, new Bundle(), Runnable::run, callback2);
        callback2.assertLoadSdkIsSuccessful();
    }

    // Returns true if the sandbox was already likely existing, false otherwise.
    private boolean killSandboxIfExists() throws Exception {
        FakeSdkSandboxProcessDeathCallback callback = new FakeSdkSandboxProcessDeathCallback();
        mSdkSandboxManager.addSdkSandboxProcessDeathCallback(Runnable::run, callback);
        killSandbox();

        return callback.getSdkSandboxDeathCount() > 0;
    }

    private void killSandbox() throws Exception {
        // TODO(b/241542162): Avoid using reflection as a workaround once test apis can be run
        //  without issue.
        mSdkSandboxManager.getClass().getMethod("stopSdkSandbox").invoke(mSdkSandboxManager);
    }
}
