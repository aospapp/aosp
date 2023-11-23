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

package com.android.server.devicelock;

import static android.devicelock.DeviceId.DEVICE_ID_TYPE_IMEI;
import static android.devicelock.DeviceId.DEVICE_ID_TYPE_MEID;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;

import android.content.Context;
import android.content.pm.PackageManager;
import android.devicelock.IGetDeviceIdCallback;
import android.telephony.TelephonyManager;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Tests for {@link com.android.server.devicelock.DeviceLockService}.
 */
public class DeviceLockUnitTest {

    private Context mMockContext;

    @Mock
    private PackageManager mPackageManager;

    @Mock
    private TelephonyManager mTelephonyManager;

    private MockitoSession mSession;

    private DeviceLockServiceImpl mService;

    private static final long ONE_SEC_MILLIS = 1000;

    @Before
    public void setup() {
        mSession = ExtendedMockito.mockitoSession().initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();
        mMockContext = spy(InstrumentationRegistry.getInstrumentation().getContext());

        doNothing().when(mMockContext).enforceCallingPermission(anyString(), anyString());

        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        when(mMockContext.getSystemService(TelephonyManager.class))
                .thenReturn(mTelephonyManager);
    }

    @After
    public void teardown() {
        mSession.finishMocking();
    }

    /**
     * Test IMEI for {@link com.android.server.devicelock.DeviceLockServiceImpl#getDeviceId}
     */
    @Test
    public void getDeviceIdShouldReturnIMEI() {
        final String testImei = "983402979622353";

        mService = new DeviceLockServiceImpl(mMockContext);

        when(mTelephonyManager.getImei()).thenReturn(testImei);

        IGetDeviceIdCallback mockCallback = mock(IGetDeviceIdCallback.class);

        mService.getDeviceId(mockCallback, 1 << DEVICE_ID_TYPE_IMEI);

        try {
            verify(mockCallback, timeout(ONE_SEC_MILLIS))
                    .onDeviceIdReceived(eq(DEVICE_ID_TYPE_IMEI), eq(testImei));
        } catch (Exception ex) {
            // Should not happen.
        }
    }

    /**
     * Test MEID for {@link com.android.server.devicelock.DeviceLockServiceImpl#getDeviceId}
     */
    @Test
    public void getDeviceIdShouldReturnMEID() {
        final String testMeid = "354403064522046";

        mService = new DeviceLockServiceImpl(mMockContext);

        when(mTelephonyManager.getMeid()).thenReturn(testMeid);

        IGetDeviceIdCallback mockCallback = mock(IGetDeviceIdCallback.class);

        mService.getDeviceId(mockCallback, 1 << DEVICE_ID_TYPE_MEID);

        try {
            verify(mockCallback, timeout(1000))
                    .onDeviceIdReceived(eq(DEVICE_ID_TYPE_MEID), eq(testMeid));
        } catch (Exception ex) {
            // Should not happen.
        }
    }
}
