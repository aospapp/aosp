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

package android.app.sdksandbox.sdkprovider;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class SdkSandboxActivityRegistryTest {

    private static final String SDK_NAME = "SDK_NAME";
    private SdkSandboxActivityRegistry mRegistry;
    private SdkSandboxActivityHandler mHandler;

    @Before
    public void setUp() {
        mRegistry = SdkSandboxActivityRegistry.getInstance();
        mHandler = Mockito.spy(activity -> {});
    }

    @After
    public void tearDown() {
        try {
            mRegistry.unregister(mHandler);
        } catch (IllegalArgumentException e) {
            // safe to ignore, it is already unregistered
        }
    }

    @Test
    public void testRegisterSdkSandboxActivityHandler() {
        assumeTrue(SdkLevel.isAtLeastU());

        IBinder token1 = mRegistry.register(SDK_NAME, mHandler);
        IBinder token2 = mRegistry.register(SDK_NAME, mHandler);
        assertThat(token2).isEqualTo(token1);
    }

    @Test
    public void testUnregisterSdkSandboxActivityHandler() {
        assumeTrue(SdkLevel.isAtLeastU());

        IBinder token = mRegistry.register(SDK_NAME, mHandler);
        mRegistry.unregister(mHandler);
        new Handler(Looper.getMainLooper())
                .runWithScissors(
                        () -> {
                            Activity activity = new Activity();
                            IllegalArgumentException exception =
                                    assertThrows(
                                            IllegalArgumentException.class,
                                            () ->
                                                    mRegistry.notifyOnActivityCreation(
                                                            token, activity));
                            assertThat(exception.getMessage())
                                    .isEqualTo(
                                            "There is no registered "
                                                    + "SdkSandboxActivityHandler to notify");
                        },
                        1000);
    }

    @Test
    public void testNotifyOnActivityCreation() {
        assumeTrue(SdkLevel.isAtLeastU());

        IBinder token = mRegistry.register(SDK_NAME, mHandler);
        new Handler(Looper.getMainLooper())
                .runWithScissors(
                        () -> {
                            Activity activity = new Activity();
                            mRegistry.notifyOnActivityCreation(token, activity);

                            ArgumentCaptor<Activity> activityArgumentCaptor =
                                    ArgumentCaptor.forClass(Activity.class);
                            Mockito.verify(mHandler)
                                    .onActivityCreated(activityArgumentCaptor.capture());
                            assertThat(activityArgumentCaptor.getValue()).isEqualTo(activity);
                        },
                        1000);
    }

    @Test
    public void testNotifyOnActivityCreationMultipleTimeSucceed() {
        assumeTrue(SdkLevel.isAtLeastU());

        IBinder token = mRegistry.register(SDK_NAME, mHandler);
        new Handler(Looper.getMainLooper())
                .runWithScissors(
                        () -> {
                            Activity activity = new Activity();
                            mRegistry.notifyOnActivityCreation(token, activity);
                            mRegistry.notifyOnActivityCreation(token, activity);
                        },
                        1000);
    }
}
