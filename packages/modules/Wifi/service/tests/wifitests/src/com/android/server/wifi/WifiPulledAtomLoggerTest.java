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

package com.android.server.wifi;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.StatsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.test.TestLooper;
import android.util.StatsEvent;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.wifi.proto.WifiStatsLog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

@SmallTest
public class WifiPulledAtomLoggerTest extends WifiBaseTest {
    public static final int TEST_INVALID_ATOM_ID = -1;
    private WifiPulledAtomLogger mWifiPulledAtomLogger;
    private MockitoSession mSession;
    private TestLooper mLooper;
    @Mock private StatsManager mStatsManager;
    @Mock private Context mContext;
    @Mock private PackageManager mPackageManager;
    @Captor ArgumentCaptor<StatsManager.StatsPullAtomCallback> mPullAtomCallbackArgumentCaptor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        mWifiPulledAtomLogger = new WifiPulledAtomLogger(mStatsManager,
                new Handler(mLooper.getLooper()), mContext);

        mSession = ExtendedMockito.mockitoSession()
                .strictness(Strictness.LENIENT)
                .mockStatic(WifiStatsLog.class)
                .startMocking();
    }

    @After
    public void tearDown() {
        mSession.finishMocking();
    }

    @Test
    public void testNullStatsManagerNoCrash() {
        mWifiPulledAtomLogger = new WifiPulledAtomLogger(null,
                new Handler(mLooper.getLooper()), mContext);
        mWifiPulledAtomLogger.setPullAtomCallback(WifiStatsLog.WIFI_MODULE_INFO);
    }

    @Test
    public void testSetCallbackWithInvalidPull() {
        mWifiPulledAtomLogger.setPullAtomCallback(WifiStatsLog.WIFI_MODULE_INFO);
        verify(mStatsManager).setPullAtomCallback(eq(WifiStatsLog.WIFI_MODULE_INFO), any(), any(),
                mPullAtomCallbackArgumentCaptor.capture());

        assertNotNull(mPullAtomCallbackArgumentCaptor.getValue());
        // Invalid ATOM ID should get skipped
        assertEquals(StatsManager.PULL_SKIP, mPullAtomCallbackArgumentCaptor.getValue()
                .onPullAtom(TEST_INVALID_ATOM_ID, new ArrayList<>()));
    }

    @Test
    public void testWifiMainlineVersionPull_builtFromSource() {
        mWifiPulledAtomLogger.setPullAtomCallback(WifiStatsLog.WIFI_MODULE_INFO);
        verify(mStatsManager).setPullAtomCallback(eq(WifiStatsLog.WIFI_MODULE_INFO), any(), any(),
                mPullAtomCallbackArgumentCaptor.capture());

        assertNotNull(mPullAtomCallbackArgumentCaptor.getValue());

        List<StatsEvent> data = new ArrayList<>();
        // Test with null PackageManager - the atom pull should be skipped
        when(mContext.getPackageManager()).thenReturn(null);
        assertEquals(StatsManager.PULL_SKIP, mPullAtomCallbackArgumentCaptor.getValue()
                .onPullAtom(WifiStatsLog.WIFI_MODULE_INFO, data));
        assertEquals(0, data.size());

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        List<PackageInfo> packageInfos = new ArrayList<>();
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = WifiPulledAtomLogger.WIFI_BUILD_FROM_SOURCE_PACKAGE_NAME;
        packageInfos.add(packageInfo);
        when(mPackageManager.getInstalledPackages(anyInt())).thenReturn(packageInfos);
        assertEquals(StatsManager.PULL_SUCCESS, mPullAtomCallbackArgumentCaptor.getValue()
                .onPullAtom(WifiStatsLog.WIFI_MODULE_INFO, data));
        assertEquals(1, data.size());
        ExtendedMockito.verify(() -> WifiStatsLog.buildStatsEvent(
                WifiStatsLog.WIFI_MODULE_INFO,
                WifiPulledAtomLogger.WIFI_VERSION_NUMBER,
                WifiStatsLog.WIFI_MODULE_INFO__BUILD_TYPE__TYPE_BUILT_FROM_SOURCE));
    }

    @Test
    public void testWifiMainlineVersionPull_preBuilt() {
        mWifiPulledAtomLogger.setPullAtomCallback(WifiStatsLog.WIFI_MODULE_INFO);
        verify(mStatsManager).setPullAtomCallback(eq(WifiStatsLog.WIFI_MODULE_INFO), any(), any(),
                mPullAtomCallbackArgumentCaptor.capture());

        assertNotNull(mPullAtomCallbackArgumentCaptor.getValue());

        List<StatsEvent> data = new ArrayList<>();
        List<PackageInfo> packageInfos = new ArrayList<>();
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "com.google.android.wifi";
        packageInfos.add(packageInfo);
        when(mPackageManager.getInstalledPackages(anyInt())).thenReturn(packageInfos);
        assertEquals(StatsManager.PULL_SUCCESS, mPullAtomCallbackArgumentCaptor.getValue()
                .onPullAtom(WifiStatsLog.WIFI_MODULE_INFO, data));
        assertEquals(1, data.size());
        ExtendedMockito.verify(() -> WifiStatsLog.buildStatsEvent(
                WifiStatsLog.WIFI_MODULE_INFO,
                WifiPulledAtomLogger.WIFI_VERSION_NUMBER,
                WifiStatsLog.WIFI_MODULE_INFO__BUILD_TYPE__TYPE_PREBUILT));
    }
}
