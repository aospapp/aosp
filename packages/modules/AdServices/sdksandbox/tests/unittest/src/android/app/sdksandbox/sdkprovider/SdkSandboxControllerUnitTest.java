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

package android.app.sdksandbox.sdkprovider;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.app.sdksandbox.ISdkToServiceCallback;
import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SandboxedSdkContext;
import android.app.sdksandbox.SdkSandboxLocalSingleton;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.DeviceConfig;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.DeviceConfigStateManager;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class SdkSandboxControllerUnitTest {
    private static final String RESOURCES_PACKAGE = "com.android.codeproviderresources_1";

    private static boolean sCustomizedSdkContextEnabled;

    private Context mContext;
    private SandboxedSdkContext mSandboxedSdkContext;
    private SdkSandboxLocalSingleton mSdkSandboxLocalSingleton;
    private StaticMockitoSession mStaticMockSession;

    @BeforeClass
    public static void setUpClass() throws Exception {
        DeviceConfigStateManager stateManager =
                new DeviceConfigStateManager(
                        InstrumentationRegistry.getInstrumentation().getContext(),
                        DeviceConfig.NAMESPACE_ADSERVICES,
                        "sdksandbox_customized_sdk_context_enabled");
        sCustomizedSdkContextEnabled = Boolean.parseBoolean(stateManager.get());
    }

    @Before
    public void setup() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mSandboxedSdkContext =
                new SandboxedSdkContext(
                        mContext,
                        getClass().getClassLoader(),
                        /*clientPackageName=*/ "",
                        new ApplicationInfo(),
                        /*sdkName=*/ "",
                        /*sdkCeDataDir=*/ null,
                        /*sdkDeDataDir=*/ null,
                        sCustomizedSdkContextEnabled);

        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(SdkSandboxLocalSingleton.class)
                        .startMocking();
        mSdkSandboxLocalSingleton = Mockito.mock(SdkSandboxLocalSingleton.class);
        // Populate mSdkSandboxLocalSingleton
        ExtendedMockito.doReturn(mSdkSandboxLocalSingleton)
                .when(() -> SdkSandboxLocalSingleton.getExistingInstance());
    }

    @After
    public void tearDown() {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    @Test
    public void testCreateInstance() throws Exception {
        final SdkSandboxController controller = new SdkSandboxController(mContext);
        assertThat(controller).isNotNull();
    }

    @Test
    public void testInitWithAnyContext() throws Exception {
        SdkSandboxController controller = mContext.getSystemService(SdkSandboxController.class);
        assertThat(controller).isNotNull();
        // Does not fail on initialising with same context
        controller.initialize(mContext);
        assertThat(controller).isNotNull();
    }

    @Test
    public void testGetSandboxedSdks() throws RemoteException {
        SdkSandboxController controller = mContext.getSystemService(SdkSandboxController.class);
        controller.initialize(mSandboxedSdkContext);

        // Mock singleton methods
        ISdkToServiceCallback serviceCallback = Mockito.mock(ISdkToServiceCallback.class);
        ArrayList<SandboxedSdk> sandboxedSdksMock = new ArrayList<>();
        sandboxedSdksMock.add(new SandboxedSdk(new Binder()));
        Mockito.when(serviceCallback.getSandboxedSdks(Mockito.anyString()))
                .thenReturn(sandboxedSdksMock);
        Mockito.when(mSdkSandboxLocalSingleton.getSdkToServiceCallback())
                .thenReturn(serviceCallback);

        List<SandboxedSdk> sandboxedSdks = controller.getSandboxedSdks();
        assertThat(sandboxedSdks).isEqualTo(sandboxedSdksMock);
    }

    @Test
    public void testGetSandboxedSdksFailsWithIncorrectContext() {
        SdkSandboxController controller = mContext.getSystemService(SdkSandboxController.class);

        assertThrows(
                "Only available from the context obtained by calling android.app.sdksandbox"
                        + ".SandboxedSdkProvider#getContext()",
                UnsupportedOperationException.class,
                () -> controller.getSandboxedSdks());
    }

    @Test
    public void testGetClientSharedPreferences_onlyFromSandboxedContext() throws Exception {
        final SdkSandboxController controller = new SdkSandboxController(mContext);
        assertThrows(
                "Only available from SandboxedSdkContext",
                UnsupportedOperationException.class,
                () -> controller.getClientSharedPreferences());
    }

    @Test
    public void testGetClientSharedPreferences() throws Exception {
        final SdkSandboxController controller = new SdkSandboxController(mContext);
        controller.initialize(mSandboxedSdkContext);

        final SharedPreferences sp = controller.getClientSharedPreferences();
        // Assert same instance as a name SharedPreference on sandboxed context
        final SharedPreferences spFromSandboxedContext =
                mSandboxedSdkContext.getSharedPreferences(
                        SdkSandboxController.CLIENT_SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        assertThat(sp).isSameInstanceAs(spFromSandboxedContext);
        // Assert same instance as a name SharedPreference on original context
        final SharedPreferences spFromOriginalContext =
                mContext.getSharedPreferences(
                        SdkSandboxController.CLIENT_SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        assertThat(sp).isSameInstanceAs(spFromOriginalContext);
    }

    @Test
    public void testRegisterSdkSandboxActivityHandler() {
        SdkSandboxController controller = new SdkSandboxController(mContext);
        controller.initialize(mSandboxedSdkContext);

        assumeTrue(SdkLevel.isAtLeastU());

        SdkSandboxActivityHandler handler = activity -> {};

        IBinder token1 = controller.registerSdkSandboxActivityHandler(handler);
        IBinder token2 = controller.registerSdkSandboxActivityHandler(handler);
        assertThat(token2).isEqualTo(token1);

        // cleaning
        controller.unregisterSdkSandboxActivityHandler(handler);
    }

    @Test
    public void testUnregisterSdkSandboxActivityHandler() {
        SdkSandboxController controller = new SdkSandboxController(mContext);
        controller.initialize(mSandboxedSdkContext);

        assumeTrue(SdkLevel.isAtLeastU());

        SdkSandboxActivityHandler handler = activity -> {};

        IBinder token1 = controller.registerSdkSandboxActivityHandler(handler);
        assertThat(token1).isNotNull();

        controller.unregisterSdkSandboxActivityHandler(handler);

        IBinder token2 = controller.registerSdkSandboxActivityHandler(handler);
        assertThat(token2).isNotEqualTo(token1);
    }
}
