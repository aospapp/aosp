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

package android.virtualdevice.cts;

import static android.Manifest.permission.ACTIVITY_EMBEDDING;
import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.Manifest.permission.WAKE_LOCK;
import static android.virtualdevice.cts.util.VirtualDeviceTestUtils.createActivityOptions;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.annotation.Nullable;
import android.app.Activity;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager.IntentInterceptorCallback;
import android.companion.virtual.VirtualDeviceManager.VirtualDevice;
import android.companion.virtual.VirtualDeviceParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.display.VirtualDisplay;
import android.platform.test.annotations.AppModeFull;
import android.virtualdevice.cts.common.FakeAssociationRule;
import android.virtualdevice.cts.util.EmptyActivity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executors;


@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "VirtualDeviceManager cannot be accessed by instant apps")
public class ActivityInterceptionTest {

    private static final String PACKAGE_NAME = "android.virtualdevice.cts";
    private static final String INTERCEPTED_ACTIVITY =
            "android.virtualdevice.cts.util.InterceptedActivity";
    private static final String ACTION_INTERCEPTED_RECEIVER =
            "android.virtualdevice.util.INTERCEPTED_RECEIVER";

    private static final VirtualDeviceParams DEFAULT_VIRTUAL_DEVICE_PARAMS =
            new VirtualDeviceParams.Builder().build();

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            ACTIVITY_EMBEDDING,
            CREATE_VIRTUAL_DEVICE,
            WAKE_LOCK);

    @Rule
    public FakeAssociationRule mFakeAssociationRule = new FakeAssociationRule();

    @Nullable
    private VirtualDevice mVirtualDevice;
    @Nullable
    private VirtualDisplay mVirtualDisplay;
    private VirtualDeviceManager mVirtualDeviceManager;
    private Context mContext;
    @Mock
    private VirtualDisplay.Callback mVirtualDisplayCallback;
    private IntentInterceptorCallback mInterceptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = getApplicationContext();
        final PackageManager packageManager = mContext.getPackageManager();
        assumeTrue(packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP));
        assumeTrue(packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS));
        mVirtualDeviceManager = mContext.getSystemService(VirtualDeviceManager.class);
        mVirtualDevice =
                mVirtualDeviceManager.createVirtualDevice(
                        mFakeAssociationRule.getAssociationInfo().getId(),
                        DEFAULT_VIRTUAL_DEVICE_PARAMS);
        mVirtualDisplay = mVirtualDevice.createVirtualDisplay(
                /* width= */ 100,
                /* height= */ 100,
                /* densityDpi= */ 240,
                /* surface= */ null,
                /* flags= */ 0,
                Runnable::run,
                mVirtualDisplayCallback);
    }

    @After
    public void tearDown() {
        if (mVirtualDevice != null) {
            mVirtualDevice.close();
        }
    }

    @Test
    public void onIntentIntercepted_noInterceptorRegistered_activityShouldLaunch() {
        Intent startIntent = new Intent(mContext, EmptyActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        EmptyActivity emptyActivity = (EmptyActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(startIntent, createActivityOptions(mVirtualDisplay));
        EmptyActivity.Callback callback = mock(EmptyActivity.Callback.class);
        emptyActivity.setCallback(callback);

        Intent allowedIntent = createInterceptedIntent();

        int requestCode = 1;
        emptyActivity.startActivityForResult(
                allowedIntent,
                requestCode,
                createActivityOptions(mVirtualDisplay));

        verify(callback, timeout(10000)).onActivityResult(
                eq(requestCode), eq(Activity.RESULT_OK), any());
        emptyActivity.finish();
    }

    @Test
    public void onIntentIntercepted_interceptorRegistered_intentIsIntercepted() {
        mInterceptor = mock(IntentInterceptorCallback.class);

        IntentFilter intentFilter = new IntentFilter(ACTION_INTERCEPTED_RECEIVER);

        mVirtualDevice.registerIntentInterceptor(intentFilter, Executors.newSingleThreadExecutor(),
                mInterceptor);

        // Starting test on EmptyActivity
        Intent startIntent = new Intent(mContext, EmptyActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        EmptyActivity emptyActivity = (EmptyActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(startIntent, createActivityOptions(mVirtualDisplay));

        // Send intent that is intercepted
        Intent interceptedIntent = createInterceptedIntent();
        interceptedIntent.putExtra("TEST", "extra");
        emptyActivity.startActivity(interceptedIntent,
                createActivityOptions(mVirtualDisplay));


        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mInterceptor, timeout(3000).times(1)).onIntentIntercepted(captor.capture());
        Intent capturedIntent = captor.getValue();

        assertThat(capturedIntent).isNotNull();
        assertThat(capturedIntent.getAction()).isEqualTo(interceptedIntent.getAction());
        assertThat(capturedIntent.getData()).isEqualTo(interceptedIntent.getData());
        assertThat(capturedIntent.getExtras()).isNull();

        // Unregister interceptor and verify intent is not intercepted
        reset(mInterceptor);
        mVirtualDevice.unregisterIntentInterceptor(mInterceptor);

        emptyActivity.startActivity(interceptedIntent,
                createActivityOptions(mVirtualDisplay));
        emptyActivity.finish();

        verify(mInterceptor, never()).onIntentIntercepted(any());
    }

    @Test
    public void onIntentIntercepted_multipleInterceptorsRegistered_intentIsIntercepted() {
        mInterceptor = mock(IntentInterceptorCallback.class);
        IntentInterceptorCallback interceptorOther = mock(
                IntentInterceptorCallback.class);

        IntentFilter intentFilter = new IntentFilter(ACTION_INTERCEPTED_RECEIVER);

        mVirtualDevice.registerIntentInterceptor(intentFilter, Executors.newSingleThreadExecutor(),
                mInterceptor);
        mVirtualDevice.registerIntentInterceptor(intentFilter, Executors.newSingleThreadExecutor(),
                interceptorOther);

        // Starting test on EmptyActivity
        Intent startIntent = new Intent(mContext, EmptyActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        EmptyActivity emptyActivity = (EmptyActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(startIntent, createActivityOptions(mVirtualDisplay));

        // Send intent that is intercepted
        Intent interceptedIntent = createInterceptedIntent();
        interceptedIntent.putExtra("TEST", "extra");
        emptyActivity.startActivity(interceptedIntent,
                createActivityOptions(mVirtualDisplay));

        verify(mInterceptor, timeout(3000).times(1)).onIntentIntercepted(any());
        verify(interceptorOther, timeout(3000).times(1)).onIntentIntercepted(any());

        // Unregister first interceptor and verify intent is intercepted by other
        reset(mInterceptor);
        reset(interceptorOther);
        mVirtualDevice.unregisterIntentInterceptor(mInterceptor);

        emptyActivity.startActivity(interceptedIntent,
                createActivityOptions(mVirtualDisplay));
        emptyActivity.finish();

        verify(mInterceptor, never()).onIntentIntercepted(any());

        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(interceptorOther, timeout(3000).times(1)).onIntentIntercepted(captor.capture());
        Intent capturedIntent = captor.getValue();

        assertThat(capturedIntent).isNotNull();
        assertThat(capturedIntent.getAction()).isEqualTo(interceptedIntent.getAction());
        assertThat(capturedIntent.getData()).isEqualTo(interceptedIntent.getData());
        assertThat(capturedIntent.getExtras()).isNull();

        mVirtualDevice.unregisterIntentInterceptor(interceptorOther);
    }

    @Test
    public void onIntentIntercepted_differentInterceptorRegistered_activityShouldLaunch() {
        mInterceptor = mock(IntentInterceptorCallback.class);

        IntentFilter intentFilter = new IntentFilter("ACTION_OTHER");
        mVirtualDevice.registerIntentInterceptor(intentFilter, Executors.newSingleThreadExecutor(),
                mInterceptor);

        // Starting test on EmptyActivity
        Intent startIntent = new Intent(mContext, EmptyActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        EmptyActivity emptyActivity = (EmptyActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(startIntent, createActivityOptions(mVirtualDisplay));
        EmptyActivity.Callback callback = mock(EmptyActivity.Callback.class);
        emptyActivity.setCallback(callback);

        // Send intent that is intercepted
        Intent allowedIntent = createInterceptedIntent();
        int requestCode = 1;
        emptyActivity.startActivityForResult(
                allowedIntent,
                requestCode,
                createActivityOptions(mVirtualDisplay));

        verify(callback, timeout(10000)).onActivityResult(
                eq(requestCode), eq(Activity.RESULT_OK), any());
        emptyActivity.finish();

        verify(mInterceptor, never()).onIntentIntercepted(any());
        mVirtualDevice.unregisterIntentInterceptor(mInterceptor);
    }

    @Test
    public void onIntentIntercepted_differentInterceptorsRegistered_oneIntercepted() {
        // setup expected intent interceptor
        mInterceptor = mock(IntentInterceptorCallback.class);
        IntentFilter intentFilter = new IntentFilter(ACTION_INTERCEPTED_RECEIVER);
        mVirtualDevice.registerIntentInterceptor(intentFilter, Executors.newSingleThreadExecutor(),
                mInterceptor);

        // setup other intent interceptor
        IntentInterceptorCallback interceptorOther = mock(
                IntentInterceptorCallback.class);
        IntentFilter intentFilterOther = new IntentFilter("ACTION_OTHER");
        mVirtualDevice.registerIntentInterceptor(intentFilterOther,
                Executors.newSingleThreadExecutor(), interceptorOther);

        // Starting test on EmptyActivity
        Intent startIntent = new Intent(mContext, EmptyActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        EmptyActivity emptyActivity = (EmptyActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(startIntent, createActivityOptions(mVirtualDisplay));

        // Send intent that is intercepted
        Intent interceptedIntent = createInterceptedIntent();
        interceptedIntent.putExtra("TEST", "extra");
        emptyActivity.startActivity(interceptedIntent,
                createActivityOptions(mVirtualDisplay));
        emptyActivity.finish();

        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mInterceptor, timeout(3000).times(1)).onIntentIntercepted(captor.capture());
        Intent capturedIntent = captor.getValue();

        assertThat(capturedIntent).isNotNull();
        assertThat(capturedIntent.getAction()).isEqualTo(interceptedIntent.getAction());
        assertThat(capturedIntent.getData()).isEqualTo(interceptedIntent.getData());
        assertThat(capturedIntent.getExtras()).isNull();

        verify(interceptorOther, never()).onIntentIntercepted(any());

        mVirtualDevice.unregisterIntentInterceptor(mInterceptor);
        mVirtualDevice.unregisterIntentInterceptor(interceptorOther);
    }

    @Test
    public void registerIntentInterceptor_nullArguments_shouldThrow() {
        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.registerIntentInterceptor(null,
                        Executors.newSingleThreadExecutor(), mInterceptor));

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.registerIntentInterceptor(new IntentFilter(),
                        null, mInterceptor));

        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.registerIntentInterceptor(new IntentFilter(),
                        Executors.newSingleThreadExecutor(), null));
    }

    @Test
    public void unregisterIntentInterceptor_nullArguments_shouldThrow() {
        assertThrows(NullPointerException.class,
                () -> mVirtualDevice.unregisterIntentInterceptor(null));
    }

    private static Intent createInterceptedIntent() {
        return new Intent(ACTION_INTERCEPTED_RECEIVER)
                .setComponent(new ComponentName(PACKAGE_NAME, INTERCEPTED_ACTIVITY));
    }
}
