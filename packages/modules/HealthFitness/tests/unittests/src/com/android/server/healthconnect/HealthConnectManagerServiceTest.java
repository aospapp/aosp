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

package com.android.server.healthconnect;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.SystemService;
import com.android.server.appop.AppOpsManagerLocal;
import com.android.server.healthconnect.backuprestore.BackupRestore;
import com.android.server.healthconnect.migration.MigrationStateChangeJob;
import com.android.server.healthconnect.storage.datatypehelpers.PreferenceHelper;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

public class HealthConnectManagerServiceTest {
    private static final String HEALTH_CONNECT_DAILY_JOB_NAMESPACE = "HEALTH_CONNECT_DAILY_JOB";
    private static final String ANDROID_SERVER_PACKAGE_NAME = "com.android.server";
    @Mock Context mContext;
    @Mock private SystemService.TargetUser mMockTargetUser;
    @Mock private JobScheduler mJobScheduler;
    @Mock private UserManager mUserManager;
    @Mock private PackageManager mPackageManager;
    @Mock private PermissionManager mPermissionManager;
    @Mock private AppOpsManagerLocal mAppOpsManagerLocal;
    @Mock private PreferenceHelper mPreferenceHelper;
    private MockitoSession mStaticMockSession;
    private HealthConnectManagerService mHealthConnectManagerService;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(PreferenceHelper.class)
                        .mockStatic(BackupRestore.BackupRestoreJobService.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        MockitoAnnotations.initMocks(this);

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.READ_DEVICE_CONFIG);

        when(mJobScheduler.forNamespace(HEALTH_CONNECT_DAILY_JOB_NAMESPACE))
                .thenReturn(mJobScheduler);
        when(mJobScheduler.forNamespace(MigrationStateChangeJob.class.toString()))
                .thenReturn(mJobScheduler);
        PermissionGroupInfo permissionGroupInfo = new PermissionGroupInfo();
        permissionGroupInfo.packageName = "test";
        PackageInfo mockPackageInfo = new PackageInfo();
        mockPackageInfo.permissions = new PermissionInfo[1];
        mockPackageInfo.permissions[0] = new PermissionInfo();

        when(mPackageManager.getPermissionGroupInfo(
                        eq(android.health.connect.HealthPermissions.HEALTH_PERMISSION_GROUP),
                        eq(0)))
                .thenThrow(new PackageManager.NameNotFoundException());
        when(mPackageManager.getPackageInfo(
                        anyString(),
                        eq(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS))))
                .thenThrow(new PackageManager.NameNotFoundException());
        when(mContext.getSystemService(JobScheduler.class)).thenReturn(mJobScheduler);
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mContext.getSystemService(PackageManager.class)).thenReturn(mPackageManager);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getSystemService(PermissionManager.class)).thenReturn(mPermissionManager);
        when(mContext.getSystemService(AppOpsManagerLocal.class)).thenReturn(mAppOpsManagerLocal);
        when(mContext.getUser()).thenReturn(UserHandle.CURRENT);
        when(mContext.getPackageName()).thenReturn(ANDROID_SERVER_PACKAGE_NAME);
        when(mContext.getDatabasePath(anyString()))
                .thenReturn(
                        InstrumentationRegistry.getInstrumentation()
                                .getContext()
                                .getDatabasePath("mock"));
        when(mContext.createContextAsUser(any(), anyInt())).thenReturn(mContext);
        when(mMockTargetUser.getUserHandle()).thenReturn(UserHandle.CURRENT);
        when(mContext.getApplicationContext()).thenReturn(mContext);
        when(PreferenceHelper.getInstance()).thenReturn(mPreferenceHelper);

        mHealthConnectManagerService = new HealthConnectManagerService(mContext);
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testCreateService() {
        Truth.assertThat(mHealthConnectManagerService).isNotNull();
    }

    @Test
    public void testUserSupport() {
        when(mUserManager.isProfile()).thenReturn(true);
        Truth.assertThat(mHealthConnectManagerService.isUserSupported(mMockTargetUser)).isFalse();
        when(mUserManager.isProfile()).thenReturn(false);
        Truth.assertThat(mHealthConnectManagerService.isUserSupported(mMockTargetUser)).isTrue();
    }

    @Test
    public void testUserSwitch() {
        when(mUserManager.isUserUnlocked(any())).thenReturn(false);
        mHealthConnectManagerService.onUserSwitching(mMockTargetUser, mMockTargetUser);
        verify(mJobScheduler, times(0)).cancelAll();
        when(mUserManager.isUserUnlocked(any())).thenReturn(true);
        mHealthConnectManagerService.onUserSwitching(mMockTargetUser, mMockTargetUser);
        verify(mJobScheduler, times(1)).cancelAll();
        verify(mJobScheduler, timeout(5000).times(1)).schedule(any());
        ExtendedMockito.verify(
                () ->
                        BackupRestore.BackupRestoreJobService.cancelAllJobs(eq(mContext)));
    }
}
