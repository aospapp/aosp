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

package android.virtualdevice.cts;

import static android.Manifest.permission.ACTIVITY_EMBEDDING;
import static android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY;
import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.Manifest.permission.WAKE_LOCK;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.content.Context.DEVICE_ID_INVALID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.virtualdevice.cts.util.VirtualDeviceTestUtils.createActivityOptions;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityMonitor;
import android.app.Service;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager.VirtualDevice;
import android.companion.virtual.VirtualDeviceParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.IBinder;
import android.platform.test.annotations.AppModeFull;
import android.view.Display;
import android.virtualdevice.cts.common.FakeAssociationRule;
import android.virtualdevice.cts.util.EmptyActivity;
import android.virtualdevice.cts.util.SecondActivity;
import android.virtualdevice.cts.util.TestService;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;
import com.android.compatibility.common.util.ApiTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;

@RunWith(AndroidJUnit4.class)
@ApiTest(apis = {"android.content.Context#updateDeviceId",
        "android.content.Context#unregisterDeviceIdChangeListener",
        "android.content.Context#registerDeviceIdChangeListener"}
)
@AppModeFull(reason = "VirtualDeviceManager cannot be accessed by instant apps")
public class DeviceAssociationTest {

    private static final VirtualDeviceParams DEFAULT_VIRTUAL_DEVICE_PARAMS =
            new VirtualDeviceParams.Builder().build();
    private static final int TIMEOUT_MILLIS = 3000;

    private Executor mTestExecutor;
    @Mock
    private IntConsumer mDeviceChangeListener;
    @Mock
    private IntConsumer mDeviceChangeListener2;
    private Display mDefaultDisplay;
    private VirtualDisplay mVirtualDisplay;
    @Mock
    private VirtualDisplay.Callback mVirtualDisplayCallback;
    @Mock
    private VirtualDeviceManager.ActivityListener mActivityListener;

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            ACTIVITY_EMBEDDING,
            ADD_ALWAYS_UNLOCKED_DISPLAY,
            CREATE_VIRTUAL_DEVICE,
            WAKE_LOCK);

    @Rule
    public FakeAssociationRule mFakeAssociationRule = new FakeAssociationRule();

    private VirtualDeviceManager mVirtualDeviceManager;
    @Nullable
    private VirtualDevice mVirtualDevice;
    private List<Activity> mActivities = new ArrayList();
    private List<VirtualDevice> mVirtualDevices = new ArrayList();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        Context context = getApplicationContext();
        final PackageManager packageManager = context.getPackageManager();
        assumeTrue(packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP));
        assumeTrue(packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS));
        mVirtualDeviceManager = context.getSystemService(VirtualDeviceManager.class);
        mTestExecutor = context.getMainExecutor();
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        mDefaultDisplay = dm.getDisplay(DEFAULT_DISPLAY);
        mVirtualDevice = createVirtualDevice();
        mVirtualDevice.addActivityListener(context.getMainExecutor(), mActivityListener);
        mVirtualDisplay = createVirtualDisplay(mVirtualDevice);
    }

    @After
    public void tearDown() {
        for (VirtualDevice virtualDevice : mVirtualDevices) {
            virtualDevice.close();
        }
        for (Activity activity : mActivities) {
            activity.finish();
            activity.releaseInstance();
        }
    }

    @Test
    @ApiTest(apis = {"android.content.Context#registerDeviceIdChangeListener"})
    public void registerDeviceIdChangeListener_nullListener_throwsException() {
        Context context = getApplicationContext();

        assertThrows(
                NullPointerException.class,
                () -> context.registerDeviceIdChangeListener(mTestExecutor, null));
    }

    @Test
    @ApiTest(apis = {"android.content.Context#registerDeviceIdChangeListener"})
    public void registerDeviceIdChangeListener_nullExecutor_throwsException() {
        Context context = getApplicationContext();

        assertThrows(
                NullPointerException.class,
                () -> context.registerDeviceIdChangeListener(null, mDeviceChangeListener));
    }

    @Test
    @ApiTest(apis = {"android.content.Context#unregisterDeviceIdChangeListener"})
    public void registerDeviceIdChangeListener_registerTwice_throwsException() {
        Context context = getApplicationContext();

        context.registerDeviceIdChangeListener(mTestExecutor, mDeviceChangeListener);

        assertThrows(
                IllegalArgumentException.class,
                () -> context.registerDeviceIdChangeListener(mTestExecutor, mDeviceChangeListener));
    }

    @Test
    @ApiTest(apis = {"android.content.Context#unregisterDeviceIdChangeListener"})
    public void unregisterDeviceChangedListener_nullListener_throwsException() {
        Context context = getApplicationContext();

        assertThrows(
                NullPointerException.class,
                () -> context.unregisterDeviceIdChangeListener(null));
    }

    @Test
    @ApiTest(apis = {"android.content.Context#unregisterDeviceIdChangeListener"})
    public void unregisterDeviceChangedListener_succeeds() {
        Context context = getApplicationContext();
        context.registerDeviceIdChangeListener(mTestExecutor, mDeviceChangeListener);

        context.updateDeviceId(mVirtualDevice.getDeviceId());

        assertThat(context.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
        verify(mDeviceChangeListener, timeout(TIMEOUT_MILLIS))
                .accept(mVirtualDevice.getDeviceId());


        // DeviceId of listener is not updated after unregistering.
        context.unregisterDeviceIdChangeListener(mDeviceChangeListener);
        context.updateDeviceId(DEVICE_ID_DEFAULT);

        assertThat(context.getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
        verifyNoMoreInteractions(mDeviceChangeListener);
    }

    @Test
    @ApiTest(apis = {"android.content.Context#updateDeviceId"})
    public void updateDeviceId_invalidDeviceId_shouldThrowIllegalArgumentException() {
        Context context = getApplicationContext();

        assertThrows(
                IllegalArgumentException.class,
                () -> context.updateDeviceId(DEVICE_ID_INVALID));
    }

    @Test
    @ApiTest(apis = {"android.content.Context#updateDeviceId"})
    public void updateDeviceId_missingDeviceId_shouldThrowIllegalArgumentException() {
        Context context = getApplicationContext();

        assertThrows(
                IllegalArgumentException.class,
                () -> context.updateDeviceId(mVirtualDevice.getDeviceId() + 1));
    }

    @Test
    @ApiTest(apis = {"android.content.Context#updateDeviceId"})
    public void updateDeviceId_defaultDeviceId() {
        Context context = getApplicationContext();

        context.updateDeviceId(DEVICE_ID_DEFAULT);

        assertThat(context.getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    @ApiTest(apis = {"android.content.Context#updateDeviceId"})
    public void updateDeviceId_validVirtualDeviceId() {
        Context context = getApplicationContext();

        context.updateDeviceId(mVirtualDevice.getDeviceId());

        assertThat(context.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
    }

    @Test
    @ApiTest(apis = {"android.content.Context#updateDeviceId"})
    public void updateDeviceId_convertToDeviceContext_overridesValue() {
        Context context = getApplicationContext();

        context.updateDeviceId(mVirtualDevice.getDeviceId());
        assertThat(context.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());

        Context defaultDeviceContext = context.createDeviceContext(DEVICE_ID_DEFAULT);

        assertThat(defaultDeviceContext.getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    @ApiTest(apis = {"android.content.Context#updateDeviceId"})
    public void updateDeviceId_onDeviceContext_throwsException() {
        Context context = getApplicationContext();
        Context deviceContext = context.createDeviceContext(mVirtualDevice.getDeviceId());
        deviceContext.registerDeviceIdChangeListener(mTestExecutor, mDeviceChangeListener);

        assertThrows(
                UnsupportedOperationException.class,
                () -> deviceContext.updateDeviceId(DEVICE_ID_DEFAULT));
    }

    @Test
    @ApiTest(apis = {"android.content.Context#updateDeviceId"})
    public void updateDeviceId_notifiesListeners() {
        Context context = getApplicationContext();
        context.registerDeviceIdChangeListener(mTestExecutor, mDeviceChangeListener);
        context.registerDeviceIdChangeListener(mTestExecutor, mDeviceChangeListener2);

        context.updateDeviceId(mVirtualDevice.getDeviceId());

        assertThat(context.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
        verify(mDeviceChangeListener, timeout(TIMEOUT_MILLIS)).accept(mVirtualDevice.getDeviceId());
        verify(mDeviceChangeListener2, timeout(TIMEOUT_MILLIS))
                .accept(mVirtualDevice.getDeviceId());
    }

    @Test
    @ApiTest(apis = {"android.content.Context#updateDeviceId"})
    public void updateDeviceId_sameId_DoesNotNotifyListeners() {
        Context context = getApplicationContext();
        context.registerDeviceIdChangeListener(mTestExecutor, mDeviceChangeListener);

        context.updateDeviceId(mVirtualDevice.getDeviceId());
        verify(mDeviceChangeListener, timeout(TIMEOUT_MILLIS)).accept(mVirtualDevice.getDeviceId());

        context.updateDeviceId(mVirtualDevice.getDeviceId());
        verifyNoMoreInteractions(mDeviceChangeListener);
    }

    @Test
    public void activityContext_startActivityOnVirtualDevice_returnsVirtualDeviceId() {
        Activity activityContext = startActivity(EmptyActivity.class, mVirtualDisplay);

        assertThat(activityContext.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
    }

    @Test
    public void activityContext_startActivityOnDefaultDevice_returnsDefaultDeviceId() {
        Activity activityContext = startActivity(EmptyActivity.class, DEFAULT_DISPLAY);

        assertThat(activityContext.getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void activityContext_activityMovesDisplay_returnsDeviceIdAssociatedWithDisplay() {
        Activity activity = startActivity(EmptyActivity.class, mVirtualDisplay);
        try (VirtualDevice virtualDevice2 = createVirtualDevice()) {
            VirtualDisplay virtualDisplay2 = createVirtualDisplay(virtualDevice2);

            assertThat(activity.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());

            activity.updateDisplay(virtualDisplay2.getDisplay().getDisplayId());

            assertThat(activity.getDeviceId()).isEqualTo(virtualDevice2.getDeviceId());
        }
    }

    @Test
    public void applicationContext_lastActivityOnVirtualDevice_returnsVirtualDeviceId() {
        startActivity(EmptyActivity.class, DEFAULT_DISPLAY);
        startActivity(SecondActivity.class, mVirtualDisplay);

        assertThat(getApplicationContext().getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
    }

    @Test
    public void applicationContext_recreateActivity_returnsDeviceIdOfActivity() {
        Activity virtualDeviceActivity = startActivity(EmptyActivity.class, mVirtualDisplay);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ActivityMonitor monitor = createMonitor(EmptyActivity.class.getName());
        instrumentation.addMonitor(monitor);

        instrumentation.runOnMainSync(() -> virtualDeviceActivity.recreate());
        monitor.waitForActivity();
        instrumentation.removeMonitor(monitor);

        assertThat(getApplicationContext().getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
    }

    @Test
    public void applicationContext_recreateFirstActivity_returnsDeviceOfLastCreatedActivity() {
        Activity defaultActivity = startActivity(EmptyActivity.class, DEFAULT_DISPLAY);
        startActivity(SecondActivity.class, mVirtualDisplay);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ActivityMonitor monitor = createMonitor(EmptyActivity.class.getName());
        instrumentation.addMonitor(monitor);

        instrumentation.runOnMainSync(() -> defaultActivity.recreate());
        monitor.waitForActivity();
        instrumentation.removeMonitor(monitor);

        assertThat(getApplicationContext().getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
    }

    @Test
    public void applicationContext_activityOnVirtualDeviceDestroyed_returnsDefault() {
        Activity activity = startActivity(EmptyActivity.class, mVirtualDisplay);
        activity.finish();
        activity.releaseInstance();

        verify(mActivityListener, timeout(TIMEOUT_MILLIS))
                .onDisplayEmpty(eq(mVirtualDisplay.getDisplay().getDisplayId()));

        assertThat(getApplicationContext().getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void applicationContext_lastActivityOnDefaultDevice_returnsDefault() {
        startActivity(EmptyActivity.class, mVirtualDisplay);
        startActivity(SecondActivity.class, DEFAULT_DISPLAY);

        assertThat(getApplicationContext().getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void application_context_noActivities_returnsDefault() {
        assertThat(getApplicationContext().getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void applicationContext_twoVirtualDevices_returnsIdOfLatest() {
        startActivity(EmptyActivity.class, mVirtualDisplay);
        Context context = getApplicationContext();
        try (VirtualDevice virtualDevice2 = createVirtualDevice()) {
            VirtualDisplay virtualDisplay2 = createVirtualDisplay(virtualDevice2);

            assertThat(context.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());

            startActivity(SecondActivity.class, virtualDisplay2);

            assertThat(context.getDeviceId()).isEqualTo(virtualDevice2.getDeviceId());

            startActivity(SecondActivity.class, DEFAULT_DISPLAY);

            assertThat(context.getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
        }
    }

    @Test
    public void serviceContext_lastActivityOnVirtualDevice_returnsVirtualDeviceId()
            throws TimeoutException {
        Service service = createTestService();
        startActivity(EmptyActivity.class, DEFAULT_DISPLAY);
        startActivity(SecondActivity.class, mVirtualDisplay);

        assertThat(service.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
    }

    @Test
    public void serviceContext_lastActivityOnDefaultDevice_returnsDefault()
            throws TimeoutException {
        Service service = createTestService();
        startActivity(EmptyActivity.class, mVirtualDisplay);
        startActivity(SecondActivity.class, DEFAULT_DISPLAY);

        assertThat(service.getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void serviceContext_startServiceAfterActivity_hasDeviceIdOfTopActivity()
            throws TimeoutException {
        startActivity(EmptyActivity.class, DEFAULT_DISPLAY);
        startActivity(SecondActivity.class, mVirtualDisplay);
        Service service = createTestService();

        assertThat(service.getDeviceId()).isEqualTo(mVirtualDevice.getDeviceId());
    }

    @Test
    public void serviceContext_startServiceAfterActivityDeviceIsClosed_returnsDefault()
            throws TimeoutException {
        startActivity(EmptyActivity.class, DEFAULT_DISPLAY);
        startActivity(SecondActivity.class, mVirtualDisplay);
        mVirtualDevice.close();
        Service service = createTestService();

        assertThat(service.getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void serviceContext_noActivities_hasDefaultId()
            throws TimeoutException {
        Service service = createTestService();

        assertThat(service.getDeviceId()).isEqualTo(DEVICE_ID_DEFAULT);
    }

    private VirtualDevice createVirtualDevice() {
        VirtualDevice virtualDevice = mVirtualDeviceManager.createVirtualDevice(
                mFakeAssociationRule.getAssociationInfo().getId(),
                DEFAULT_VIRTUAL_DEVICE_PARAMS);
        mVirtualDevices.add(virtualDevice);
        return virtualDevice;
    }

    private VirtualDisplay createVirtualDisplay(VirtualDevice virtualDevice) {
        return virtualDevice.createVirtualDisplay(
                /* width= */ 100,
                /* height= */ 100,
                /* densityDpi= */ 240,
                /* surface= */ null,
                /* flags= */ 0,
                Runnable::run,
                mVirtualDisplayCallback);
    }

    private ActivityMonitor createMonitor(String activityName) {
        return new ActivityMonitor(activityName,
                new Instrumentation.ActivityResult(0, new Intent()), false);
    }

    private Activity startActivity(Class<?> activityClass, VirtualDisplay virtualDisplay) {
        return startActivityWithOptions(createActivityOptions(virtualDisplay), activityClass);
    }

    private Activity startActivity(Class<?> activityClass, int displayId) {
        return startActivityWithOptions(createActivityOptions(displayId), activityClass);
    }

    private Activity startActivityWithOptions(Bundle activityOptions, Class<?> activityClass) {
        Activity activity = InstrumentationRegistry.getInstrumentation()
                .startActivitySync(
                        new Intent(getApplicationContext(), activityClass)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        activityOptions);
        mActivities.add(activity);
        return activity;
    }

    private Service createTestService() throws TimeoutException {
        final Intent intent = new Intent(getApplicationContext(), TestService.class);
        final ServiceTestRule serviceRule = new ServiceTestRule();
        IBinder serviceToken = serviceRule.bindService(intent);
        return ((TestService.TestBinder) serviceToken).getService();
    }
}
