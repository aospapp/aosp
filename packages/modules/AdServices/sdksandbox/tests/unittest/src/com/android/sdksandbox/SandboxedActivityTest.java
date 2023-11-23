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

package com.android.sdksandbox;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.app.sdksandbox.SdkSandboxManager;
import android.app.sdksandbox.sdkprovider.SdkSandboxActivityHandler;
import android.app.sdksandbox.sdkprovider.SdkSandboxActivityRegistry;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.android.modules.utils.build.SdkLevel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class SandboxedActivityTest {

    @Test
    public void testSandboxedActivityCreation() {
        assumeTrue(SdkLevel.isAtLeastU());

        SdkSandboxActivityHandler sdkSandboxActivityHandler = Mockito.spy(activity -> {});
        SdkSandboxActivityRegistry registry = SdkSandboxActivityRegistry.getInstance();
        IBinder token = registry.register("SDK_NAME", sdkSandboxActivityHandler);

        new Handler(Looper.getMainLooper())
                .runWithScissors(
                        () -> {
                            SandboxedActivity sandboxedActivity = new SandboxedActivity();

                            Intent intent = new Intent();
                            intent.setAction(SdkSandboxManager.ACTION_START_SANDBOXED_ACTIVITY);
                            Bundle extras = new Bundle();
                            extras.putBinder(
                                    sandboxedActivity.getSandboxedActivityHandlerKey(), token);
                            intent.putExtras(extras);
                            sandboxedActivity.setIntent(intent);

                            sandboxedActivity.notifySdkOnActivityCreation();

                            ArgumentCaptor<SandboxedActivity> sandboxedActivityArgumentCaptor =
                                    ArgumentCaptor.forClass(SandboxedActivity.class);
                            Mockito.verify(sdkSandboxActivityHandler)
                                    .onActivityCreated(sandboxedActivityArgumentCaptor.capture());
                            assertThat(sandboxedActivityArgumentCaptor.getValue())
                                    .isEqualTo(sandboxedActivity);
                        },
                        1000);
    }

    @Test
    public void testSandboxedActivityFinishIfNoIntentExtras() {
        assumeTrue(SdkLevel.isAtLeastU());

        new Handler(Looper.getMainLooper())
                .runWithScissors(
                        () -> {
                            SandboxedActivity sandboxedActivity =
                                    Mockito.spy(new SandboxedActivity());
                            sandboxedActivity.setIntent(new Intent());

                            sandboxedActivity.notifySdkOnActivityCreation();
                            assertThat(sandboxedActivity.isFinishing()).isTrue();
                        },
                        1000);
    }

    @Test
    public void testSandboxedActivityFinishIfNoIntentExtrasNotHavingTheHandlerToken() {
        assumeTrue(SdkLevel.isAtLeastU());

        new Handler(Looper.getMainLooper())
                .runWithScissors(
                        () -> {
                            SandboxedActivity sandboxedActivity =
                                    Mockito.spy(new SandboxedActivity());

                            Intent intent = new Intent();
                            Bundle extras = new Bundle();
                            intent.putExtras(extras);
                            sandboxedActivity.setIntent(intent);

                            sandboxedActivity.notifySdkOnActivityCreation();
                            assertThat(sandboxedActivity.isFinishing()).isTrue();
                        },
                        1000);
    }

    @Test
    public void testSandboxedActivityFinishIfHandlerTokenIsWrongType() {
        assumeTrue(SdkLevel.isAtLeastU());

        new Handler(Looper.getMainLooper())
                .runWithScissors(
                        () -> {
                            SandboxedActivity sandboxedActivity =
                                    Mockito.spy(new SandboxedActivity());

                            Intent intent = new Intent();
                            Bundle extras = new Bundle();
                            extras.putString(
                                    sandboxedActivity.getSandboxedActivityHandlerKey(), "");
                            intent.putExtras(extras);
                            sandboxedActivity.setIntent(intent);

                            sandboxedActivity.notifySdkOnActivityCreation();
                            assertThat(sandboxedActivity.isFinishing()).isTrue();
                        },
                        1000);
    }

    @Test
    public void testSandboxedActivityFinishIfHandlerNotRegistered() {
        new Handler(Looper.getMainLooper())
                .runWithScissors(
                        () -> {
                            SandboxedActivity sandboxedActivity =
                                    Mockito.spy(new SandboxedActivity());

                            Intent intent = new Intent();
                            Bundle extras = new Bundle();
                            extras.putBinder(
                                    sandboxedActivity.getSandboxedActivityHandlerKey(),
                                    new Binder());
                            intent.putExtras(extras);
                            sandboxedActivity.setIntent(intent);

                            sandboxedActivity.notifySdkOnActivityCreation();
                            assertThat(sandboxedActivity.isFinishing()).isTrue();
                        },
                        1000);
    }

    @Test
    public void testSandboxedActivityFinishIfHandlerNotifiedAlreadyAboutAnotherActivity() {
        SdkSandboxActivityHandler sdkSandboxActivityHandler = activity -> {};
        SdkSandboxActivityRegistry registry = SdkSandboxActivityRegistry.getInstance();
        IBinder token = registry.register("SDK_NAME", sdkSandboxActivityHandler);

        new Handler(Looper.getMainLooper())
                .runWithScissors(
                        () -> {
                            SandboxedActivity sandboxedActivity1 =
                                    Mockito.spy(new SandboxedActivity());

                            Intent intent = new Intent();
                            intent.setAction(SdkSandboxManager.ACTION_START_SANDBOXED_ACTIVITY);
                            Bundle extras = new Bundle();
                            extras.putBinder(
                                    sandboxedActivity1.getSandboxedActivityHandlerKey(), token);
                            intent.putExtras(extras);

                            sandboxedActivity1.setIntent(intent);
                            sandboxedActivity1.notifySdkOnActivityCreation();

                            SandboxedActivity sandboxedActivity2 =
                                    Mockito.spy(new SandboxedActivity());
                            sandboxedActivity1.setIntent(intent);
                            sandboxedActivity2.notifySdkOnActivityCreation();

                            Mockito.verify(sandboxedActivity1, Mockito.never()).finish();
                            Mockito.verify(sandboxedActivity2).finish();
                        },
                        1000);
    }
}
