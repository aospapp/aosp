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

package com.android.adservices.service.common;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;

import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.modules.utils.build.SdkLevel;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@SmallTest
public class AdExtBootCompletedReceiverTest {
    private static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final Intent sIntent = new Intent();
    private static final String TEST_PACKAGE_NAME = "test";
    private static final String AD_SERVICES_APK_PKG_SUFFIX = "android.adservices";
    private static final int NUM_ACTIVITIES_TO_DISABLE = 7;
    private static final int NUM_SERVICE_CLASSES_TO_DISABLE = 7;

    @Mock Flags mMockFlags;
    @Mock Context mContext;
    @Mock PackageManager mPackageManager;
    MockitoSession mSession;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);

        // Start a mockitoSession to mock static method
        mSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(PackageChangedReceiver.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        when(FlagsFactory.getFlags()).thenReturn(mMockFlags);
    }

    @After
    public void teardown() {
        mSession.finishMocking();
    }

    @Test
    public void testOnReceive_tPlus_flagOff() {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        doReturn(true).when(mMockFlags).getGlobalKillSwitch();

        bootCompletedReceiver.onReceive(sContext, sIntent);

        verify(bootCompletedReceiver, never()).registerPackagedChangedBroadcastReceivers(any());
        verify(bootCompletedReceiver, never()).updateAdExtServicesServices(any(), eq(true));
        verify(bootCompletedReceiver, atLeastOnce())
                .updateAdExtServicesActivities(any(), eq(false));
        verify(bootCompletedReceiver, atLeastOnce()).updateAdExtServicesServices(any(), eq(false));
        verify(bootCompletedReceiver, atLeastOnce())
                .unregisterPackageChangedBroadcastReceivers(any());
        verify(bootCompletedReceiver, atLeastOnce()).disableScheduledBackgroundJobs(any());
    }

    @Test
    public void testOnReceive_tPlus_flagOn() {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        doReturn(true).when(mMockFlags).getEnableBackCompat();
        doReturn(true).when(mMockFlags).getAdServicesEnabled();
        doReturn(false).when(mMockFlags).getGlobalKillSwitch();

        bootCompletedReceiver.onReceive(sContext, sIntent);
        verify(bootCompletedReceiver, never()).registerPackagedChangedBroadcastReceivers(any());
        verify(bootCompletedReceiver, never()).updateAdExtServicesServices(any(), eq(true));
        verify(bootCompletedReceiver, atLeastOnce())
                .updateAdExtServicesActivities(any(), eq(false));
        verify(bootCompletedReceiver, atLeastOnce()).updateAdExtServicesServices(any(), eq(false));
        verify(bootCompletedReceiver, atLeastOnce())
                .unregisterPackageChangedBroadcastReceivers(any());
        verify(bootCompletedReceiver, atLeastOnce()).disableScheduledBackgroundJobs(any());
    }

    @Test
    public void testOnReceive_s_flagsOff() {
        Assume.assumeTrue(
                Build.VERSION.SDK_INT == Build.VERSION_CODES.S
                        || Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2);
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        doReturn(false).when(mMockFlags).getEnableBackCompat();

        bootCompletedReceiver.onReceive(sContext, sIntent);
        verify(bootCompletedReceiver, never()).registerPackagedChangedBroadcastReceivers(any());
        verify(bootCompletedReceiver, never()).updateAdExtServicesActivities(any(), eq(true));
        verify(bootCompletedReceiver, never()).updateAdExtServicesServices(any(), eq(true));

        doReturn(true).when(mMockFlags).getEnableBackCompat();
        doReturn(false).when(mMockFlags).getAdServicesEnabled();

        bootCompletedReceiver.onReceive(sContext, sIntent);
        verify(bootCompletedReceiver, never()).registerPackagedChangedBroadcastReceivers(any());
        verify(bootCompletedReceiver, never()).updateAdExtServicesActivities(any(), eq(true));
        verify(bootCompletedReceiver, never()).updateAdExtServicesServices(any(), eq(true));

        doReturn(true).when(mMockFlags).getEnableBackCompat();
        doReturn(true).when(mMockFlags).getAdServicesEnabled();
        doReturn(true).when(mMockFlags).getGlobalKillSwitch();

        bootCompletedReceiver.onReceive(sContext, sIntent);
        verify(bootCompletedReceiver, never()).registerPackagedChangedBroadcastReceivers(any());
        verify(bootCompletedReceiver, never()).updateAdExtServicesActivities(any(), eq(true));
        verify(bootCompletedReceiver, never()).updateAdExtServicesServices(any(), eq(true));
        verify(bootCompletedReceiver, never()).disableScheduledBackgroundJobs(any());
    }

    @Test
    public void testOnReceive_SFlagsOn() {
        Assume.assumeTrue(
                Build.VERSION.SDK_INT == Build.VERSION_CODES.S
                        || Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2);
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        doReturn(true).when(mMockFlags).getEnableBackCompat();
        doReturn(true).when(mMockFlags).getAdServicesEnabled();
        doReturn(false).when(mMockFlags).getGlobalKillSwitch();

        bootCompletedReceiver.onReceive(sContext, sIntent);
        verify(bootCompletedReceiver).registerPackagedChangedBroadcastReceivers(any());
        verify(bootCompletedReceiver).updateAdExtServicesActivities(any(), eq(true));
        verify(bootCompletedReceiver).updateAdExtServicesServices(any(), eq(true));
        verify(bootCompletedReceiver, never()).disableScheduledBackgroundJobs(any());
    }

    @Test
    public void testRegisterReceivers() {
        Assume.assumeTrue(
                Build.VERSION.SDK_INT == Build.VERSION_CODES.S
                        || Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2);
        AdExtBootCompletedReceiver bootCompletedReceiver = new AdExtBootCompletedReceiver();
        bootCompletedReceiver.registerPackagedChangedBroadcastReceivers(sContext);
        verify(() -> PackageChangedReceiver.enableReceiver(any(Context.class), any(Flags.class)));
    }

    @Test
    public void testUnregisterReceivers() {
        doReturn(true).when(() -> PackageChangedReceiver.disableReceiver(any(), any()));

        AdExtBootCompletedReceiver bootCompletedReceiver = new AdExtBootCompletedReceiver();
        bootCompletedReceiver.unregisterPackageChangedBroadcastReceivers(sContext);

        verify(() -> PackageChangedReceiver.disableReceiver(any(Context.class), any(Flags.class)));
    }

    @Test
    public void testEnableActivities_s() throws Exception {
        Assume.assumeTrue(
                Build.VERSION.SDK_INT == Build.VERSION_CODES.S
                        || Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2);
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());

        setCommonMocks(TEST_PACKAGE_NAME);

        // Call the method we're testing.
        bootCompletedReceiver.updateAdExtServicesActivities(mContext, true);

        verify(mPackageManager, times(7))
                .setComponentEnabledSetting(
                        any(ComponentName.class),
                        eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                        eq(PackageManager.DONT_KILL_APP));
    }

    @Test
    public void testDisableActivities_tPlus() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT == 33);
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        setCommonMocks(TEST_PACKAGE_NAME);

        // Call the method we're testing.
        bootCompletedReceiver.updateAdExtServicesActivities(mContext, false);

        verify(mPackageManager, times(NUM_ACTIVITIES_TO_DISABLE))
                .setComponentEnabledSetting(
                        any(ComponentName.class),
                        eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                        eq(PackageManager.DONT_KILL_APP));
    }

    @Test
    public void testDisableAdExtServicesServices_tPlus() throws Exception {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        setCommonMocks(TEST_PACKAGE_NAME);

        // Call the method we're testing.
        bootCompletedReceiver.updateAdExtServicesServices(mContext, /* shouldEnable= */ false);

        verify(mPackageManager, times(NUM_SERVICE_CLASSES_TO_DISABLE))
                .setComponentEnabledSetting(
                        any(ComponentName.class),
                        eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                        eq(PackageManager.DONT_KILL_APP));
    }

    @Test
    public void testUpdateAdExtServicesServices_s() throws Exception {
        Assume.assumeTrue(
                Build.VERSION.SDK_INT == Build.VERSION_CODES.S
                        || Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2);
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        setCommonMocks(TEST_PACKAGE_NAME);

        // Call the method we're testing
        bootCompletedReceiver.updateAdExtServicesServices(mContext, /* shouldEnable= */ true);

        verify(mPackageManager, times(NUM_SERVICE_CLASSES_TO_DISABLE))
                .setComponentEnabledSetting(
                        any(ComponentName.class),
                        eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                        eq(PackageManager.DONT_KILL_APP));
    }

    @Test
    public void testUpdateAdExtServicesActivities_withAdServicesPackageSuffix_doesNotUpdate()
            throws Exception {
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        setCommonMocks(AD_SERVICES_APK_PKG_SUFFIX);

        // Call the method we're testing.
        bootCompletedReceiver.updateAdExtServicesActivities(mContext, false);

        verify(mPackageManager, never())
                .setComponentEnabledSetting(
                        any(ComponentName.class),
                        eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                        eq(PackageManager.DONT_KILL_APP));
    }

    @Test
    public void testDisableAdExtServicesServices_withAdServicesPackageSuffix_doesNotUpdate()
            throws Exception {
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        setCommonMocks(AD_SERVICES_APK_PKG_SUFFIX);

        // Call the method we're testing.
        bootCompletedReceiver.updateAdExtServicesServices(mContext, /* shouldEnable= */ false);

        verify(mPackageManager, never())
                .setComponentEnabledSetting(
                        any(ComponentName.class),
                        eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                        eq(PackageManager.DONT_KILL_APP));
    }

    @Test
    public void testUpdateComponents_withAdServicesPackagePrefix_throwsException() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        AdExtBootCompletedReceiver.updateComponents(
                                mContext, ImmutableList.of(), AD_SERVICES_APK_PKG_SUFFIX, false));
    }

    @Test
    public void testDisableScheduledBackgroundJobs_contextNull() {
        assertThrows(
                NullPointerException.class,
                () -> new AdExtBootCompletedReceiver().disableScheduledBackgroundJobs(null));
    }

    @Test
    public void testDisableScheduledBackgroundJobs_withAdServicesPackageSuffix_doesNotUpdate()
            throws Exception {
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        setCommonMocks(AD_SERVICES_APK_PKG_SUFFIX);

        bootCompletedReceiver.disableScheduledBackgroundJobs(mContext);
        verify(mContext, never()).getSystemService(eq(JobScheduler.class));
    }

    @Test
    public void testDisableScheduledBackgroundJobs_cancelsAllJobs() throws Exception {
        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());

        JobScheduler mockScheduler = Mockito.mock(JobScheduler.class);
        when(mContext.getSystemService(JobScheduler.class)).thenReturn(mockScheduler);

        setCommonMocks(TEST_PACKAGE_NAME);

        bootCompletedReceiver.disableScheduledBackgroundJobs(mContext);
        verify(mockScheduler).cancelAll();
    }

    @Test
    public void testDisableScheduledBackgroundJobs_handlesException() throws Exception {
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getPackageInfo(anyString(), anyInt()))
                .thenThrow(PackageManager.NameNotFoundException.class);

        AdExtBootCompletedReceiver bootCompletedReceiver =
                Mockito.spy(new AdExtBootCompletedReceiver());
        bootCompletedReceiver.disableScheduledBackgroundJobs(mContext);
        verify(mContext, never()).getSystemService(eq(JobScheduler.class));
    }

    private void setCommonMocks(String packageName) throws Exception {
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getPackageName()).thenReturn(TEST_PACKAGE_NAME);

        PackageInfo packageInfo = Mockito.spy(PackageInfo.class);
        packageInfo.packageName = packageName;
        when(mPackageManager.getPackageInfo(eq(packageInfo.packageName), eq(0)))
                .thenReturn(packageInfo);
    }
}
