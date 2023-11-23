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

package com.android.adservices.service.appsearch;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

import android.app.adservices.AdServicesManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;

import java.util.List;

@SmallTest
public class AppSearchMeasurementRollbackManagerTest {
    private static final int UID = 100;
    private static final long CURRENT_APEX_VERSION = 1000;
    private static final String EXTSERVICES_PACKAGE_NAME = "com.google.android.extservices";

    private final Context mContext = spy(ApplicationProvider.getApplicationContext());

    @Mock private AppSearchMeasurementRollbackWorker mWorker;

    private MockitoSession mMockitoSession;

    private AppSearchMeasurementRollbackManager mManager;

    @Before
    public void setup() {
        mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(UserHandle.class)
                        .mockStatic(AppSearchMeasurementRollbackWorker.class)
                        .initMocks(this)
                        .startMocking();

        // Mock the user handle to return the fake user id
        UserHandle mockUserHandle = mock(UserHandle.class);
        doReturn(mockUserHandle).when(() -> UserHandle.getUserHandleForUid(anyInt()));
        doReturn(UID).when(mockUserHandle).getIdentifier();

        // Mock the worker instance
        doReturn(mWorker)
                .when(
                        () ->
                                AppSearchMeasurementRollbackWorker.getInstance(
                                        mContext, Integer.toString(UID)));

        // Mock the current apex version computation
        mockApexVersion();

        // Instantiate the manager class after all the dependencies are mocked.
        mManager =
                AppSearchMeasurementRollbackManager.getInstance(
                        mContext, AdServicesManager.MEASUREMENT_DELETION);
    }

    @After
    public void teardown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testRecordAdServicesDeletionOccurred() {
        mManager.recordAdServicesDeletionOccurred();
        verify(mWorker)
                .recordAdServicesDeletionOccurred(
                        AdServicesManager.MEASUREMENT_DELETION, CURRENT_APEX_VERSION);
        verifyNoMoreInteractions(mWorker);
    }

    @Test
    public void testRecordAdServicesDeletionOccurred_noApex() {
        // Mock so that no apex matches the suffix.
        mockApexVersion(List.of());
        mManager =
                AppSearchMeasurementRollbackManager.getInstance(
                        mContext, AdServicesManager.MEASUREMENT_DELETION);

        mManager.recordAdServicesDeletionOccurred();
        verify(mWorker, never()).recordAdServicesDeletionOccurred(anyInt(), anyLong());
        verifyNoMoreInteractions(mWorker);
    }

    @Test
    public void testRecordAdServicesDeletionOccurred_exception() {
        doThrow(RuntimeException.class)
                .when(mWorker)
                .recordAdServicesDeletionOccurred(anyInt(), anyLong());

        // Manager should not crash when the worker throws an exception.
        mManager.recordAdServicesDeletionOccurred();
    }

    @Test
    public void testNeedsToHandleRollbackReconciliation_StoredDataNull() {
        doReturn(null).when(mWorker).getAdServicesDeletionRollbackMetadata(anyInt());
        assertThat(mManager.needsToHandleRollbackReconciliation()).isFalse();
        verify(mWorker, never()).clearAdServicesDeletionOccurred(anyString());
        verifyNoMoreInteractions(mWorker);
    }

    @Test
    public void testNeedsToHandleRollbackReconciliation_StoredVersionLower() {
        AppSearchMeasurementRollbackDao mockDao = mock(AppSearchMeasurementRollbackDao.class);
        doReturn(CURRENT_APEX_VERSION - 1).when(mockDao).getApexVersion();
        doReturn(mockDao).when(mWorker).getAdServicesDeletionRollbackMetadata(anyInt());

        assertThat(mManager.needsToHandleRollbackReconciliation()).isFalse();
        verify(mWorker, never()).clearAdServicesDeletionOccurred(anyString());
        verifyNoMoreInteractions(mWorker);
    }

    @Test
    public void testNeedsToHandleRollbackReconciliation_StoredVersionEqual() {
        AppSearchMeasurementRollbackDao mockDao = mock(AppSearchMeasurementRollbackDao.class);
        doReturn(CURRENT_APEX_VERSION).when(mockDao).getApexVersion();
        doReturn(mockDao).when(mWorker).getAdServicesDeletionRollbackMetadata(anyInt());

        assertThat(mManager.needsToHandleRollbackReconciliation()).isFalse();
        verify(mWorker, never()).clearAdServicesDeletionOccurred(anyString());
        verifyNoMoreInteractions(mWorker);
    }

    @Test
    public void testNeedsToHandleRollbackReconciliation_StoredVersionHigher() {
        String mockRowId = "mock_row_id";

        AppSearchMeasurementRollbackDao mockDao = mock(AppSearchMeasurementRollbackDao.class);
        doReturn(CURRENT_APEX_VERSION + 1).when(mockDao).getApexVersion();
        doReturn(mockRowId).when(mockDao).getId();
        doReturn(mockDao).when(mWorker).getAdServicesDeletionRollbackMetadata(anyInt());

        assertThat(mManager.needsToHandleRollbackReconciliation()).isTrue();
        verify(mWorker).clearAdServicesDeletionOccurred(eq(mockRowId));
        verifyNoMoreInteractions(mWorker);
    }

    @Test
    public void testNeedsToHandleRollbackReconciliation_noApex() {
        // Mock so that no apex matches the suffix.
        mockApexVersion(List.of());
        mManager =
                AppSearchMeasurementRollbackManager.getInstance(
                        mContext, AdServicesManager.MEASUREMENT_DELETION);

        assertThat(mManager.needsToHandleRollbackReconciliation()).isFalse();
        verify(mWorker, never()).clearAdServicesDeletionOccurred(any());
        verify(mWorker, never()).getAdServicesDeletionRollbackMetadata(anyInt());
        verifyNoMoreInteractions(mWorker);
    }

    @Test
    public void testNeedsToHandleRollbackReconciliation_workerException() {
        doThrow(RuntimeException.class)
                .when(mWorker)
                .getAdServicesDeletionRollbackMetadata(anyInt());
        // Manager should not crash if the worker throws an exception
        assertThat(mManager.needsToHandleRollbackReconciliation()).isFalse();
    }

    private void mockApexVersion() {
        PackageInfo info1 = new PackageInfo();
        info1.packageName = EXTSERVICES_PACKAGE_NAME;
        info1.setLongVersionCode(CURRENT_APEX_VERSION);
        info1.isApex = true;
        mockApexVersion(List.of(info1));
    }

    private void mockApexVersion(List<PackageInfo> packagesToReturn) {
        PackageManager packageManager = mock(PackageManager.class);
        doReturn(packageManager).when(mContext).getPackageManager();
        doReturn(packagesToReturn)
                .when(packageManager)
                .getInstalledPackages(PackageManager.MATCH_APEX);
    }
}
