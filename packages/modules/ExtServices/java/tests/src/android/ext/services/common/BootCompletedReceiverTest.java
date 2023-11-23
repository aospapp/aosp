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

package android.ext.services.common;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.Spy;
import org.mockito.quality.Strictness;

import java.util.List;

public class BootCompletedReceiverTest {
    private static final String ADSERVICES_EXT_PACKAGE_NAME = "com.android.ext.adservices.api";

    private MockitoSession mMockitoSession;

    @Spy
    private final Context mContext = ApplicationProvider.getApplicationContext();

    @Spy
    private BootCompletedReceiver mReceiver;

    @Mock
    private PackageManager mPackageManager;

    @Before
    public void setup() {
        mMockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();

        doReturn(mPackageManager).when(mContext).getPackageManager();
    }

    @After
    public void tearDown() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testReceiverSkipsBroadcastIfDisabled() {
        mockReceiverEnabled(false);

        mReceiver.onReceive(mContext, null);

        verify(mContext, never()).getPackageManager();
        verify(mContext, never()).sendBroadcast(any());
    }

    @Test
    public void testReceiverSkipsBroadcastIfNoPackages() {
        mockReceiverEnabled(true);
        doReturn(List.of()).when(mPackageManager).getInstalledPackages(anyInt());

        mReceiver.onReceive(mContext, null);

        verify(mContext, never()).sendBroadcast(any());
        verify(mPackageManager, never()).setComponentEnabledSetting(any(), anyInt(), anyInt());
    }

    @Test
    public void testReceiverSkipsBroadcastIfNoPackagesMatchingAdServices() {
        mockReceiverEnabled(true);

        PackageInfo one = new PackageInfo();
        one.packageName = "one";
        PackageInfo two = new PackageInfo();
        two.packageName = "external.adservices.invalid.api";
        doReturn(List.of(one, two)).when(mPackageManager).getInstalledPackages(anyInt());

        mReceiver.onReceive(mContext, null);

        verify(mContext, never()).sendBroadcast(any());
        verify(mPackageManager, never()).setComponentEnabledSetting(any(), anyInt(), anyInt());
    }

    @Test
    public void testReceiverResendsBroadcast() {
        mockReceiverEnabled(true);
        mockAdServicesPackageName();
        doReturn(false).when(mReceiver).isAtLeastT();
        mockExcludedDevices("");

        mReceiver.onReceive(mContext, null);

        verifyBroadcastSent();
    }

    @Test
    public void testReceiverDisablesItselfOnTPlusIfAdServicesDisabled() {
        mockReceiverEnabled(true);
        mockAdServicesPackageName();
        doReturn(true).when(mReceiver).isAtLeastT();
        doReturn(List.of()).when(mPackageManager).queryIntentActivities(any(), anyInt());

        mReceiver.onReceive(mContext, null);

        verify(mPackageManager).setComponentEnabledSetting(any(),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED), eq(0));
        verify(mContext, never()).sendBroadcast(any());
    }

    @Test
    public void testReceiverDoesNotDisableItselfOnTPlusIfAdServicesEnabled() {
        mockReceiverEnabled(true);
        mockAdServicesPackageName();
        doReturn(true).when(mReceiver).isAtLeastT();
        mockExcludedDevices("");

        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = "test";
        info.activityInfo.name = "test2";
        doReturn(List.of(info)).when(mPackageManager).queryIntentActivities(any(), anyInt());

        mReceiver.onReceive(mContext, null);

        verify(mPackageManager, never()).setComponentEnabledSetting(any(), anyInt(), anyInt());
        verifyBroadcastSent();
    }

    @Test
    public void testReceiverShouldNotDisableItselfOnSMinus() {
        mockReceiverEnabled(true);
        mockAdServicesPackageName();
        doReturn(false).when(mReceiver).isAtLeastT();
        mockExcludedDevices("");

        mReceiver.onReceive(mContext, null);

        verify(mPackageManager, never()).queryIntentActivities(any(), anyInt());
        verify(mPackageManager, never()).setComponentEnabledSetting(any(), anyInt(), anyInt());
        verifyBroadcastSent();
    }

    @Test
    public void testReceiverSkipsBroadcastIfFingerprintExcludedExactly() {
        mockReceiverEnabled(true);
        mockAdServicesPackageName();
        doReturn(false).when(mReceiver).isAtLeastT();
        mockExcludedDevices(Build.FINGERPRINT);

        mReceiver.onReceive(mContext, null);

        verify(mContext, never()).sendBroadcast(any());
    }

    @Test
    public void testReceiverSkipsBroadcastIfFingerprintExcludedPrefix() {
        mockReceiverEnabled(true);
        mockAdServicesPackageName();
        doReturn(false).when(mReceiver).isAtLeastT();

        String currentBuild = Build.FINGERPRINT;
        if (currentBuild.length() > 1) {
            currentBuild = currentBuild.substring(0, currentBuild.length() - 2);
        }
        mockExcludedDevices(currentBuild);

        mReceiver.onReceive(mContext, null);

        verify(mContext, never()).sendBroadcast(any());
    }

    @Test
    public void testReceiverSkipsBroadcastIfFingerprintExcludedTrim() {
        mockReceiverEnabled(true);
        mockAdServicesPackageName();
        doReturn(false).when(mReceiver).isAtLeastT();
        mockExcludedDevices("  " + Build.FINGERPRINT + "  ");

        mReceiver.onReceive(mContext, null);

        verify(mContext, never()).sendBroadcast(any());
    }

    @Test
    public void testReceiverSkipsBroadcastIfFingerprintExcludedInList() {
        mockReceiverEnabled(true);
        mockAdServicesPackageName();
        doReturn(false).when(mReceiver).isAtLeastT();
        mockExcludedDevices("one, " + Build.FINGERPRINT + ", two");

        mReceiver.onReceive(mContext, null);

        verify(mContext, never()).sendBroadcast(any());
    }

    private void mockAdServicesPackageName() {
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = ADSERVICES_EXT_PACKAGE_NAME;
        doReturn(List.of(pkg)).when(mPackageManager).getInstalledPackages(anyInt());
    }

    private void mockReceiverEnabled(boolean value) {
        doReturn(value).when(mReceiver).isReceiverEnabled();
    }

    private void mockExcludedDevices(String value) {
        doReturn(value).when(mReceiver).getExcludedFingerprints();
    }

    private void verifyBroadcastSent() {
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendBroadcast(captor.capture());
        verify(mPackageManager, never()).setComponentEnabledSetting(any(), anyInt(), anyInt());

        ComponentName componentName = captor.getValue().getComponent();
        assertThat(componentName.getPackageName()).isEqualTo(ADSERVICES_EXT_PACKAGE_NAME);
        assertThat(componentName.getShortClassName()).isEqualTo(
                "com.android.adservices.service.common.AdExtBootCompletedReceiver");
    }
}
