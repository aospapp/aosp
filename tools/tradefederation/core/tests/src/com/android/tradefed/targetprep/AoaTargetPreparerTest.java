/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.targetprep;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.android.helper.aoa.AoaDevice;
import com.android.helper.aoa.UsbHelper;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.TestDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.awt.*;
import java.time.Duration;

/** Unit tests for {@link AoaTargetPreparer} */
@RunWith(MockitoJUnitRunner.class)
public class AoaTargetPreparerTest {

    @Spy private AoaTargetPreparer mPreparer;
    private OptionSetter mOptionSetter;

    @Mock private TestDevice mTestDevice;
    @Mock private IBuildInfo mBuildInfo;
    @Mock private UsbHelper mUsb;
    @Mock private AoaDevice mDevice;

    @Before
    public void setUp() throws ConfigurationException {
        when(mUsb.getAoaDevice(any(), any())).thenReturn(mDevice);
        doReturn(mUsb).when(mPreparer).getUsbHelper();
        mOptionSetter = new OptionSetter(mPreparer);
        when(mDevice.getSerialNumber()).thenReturn("SERIAL");
    }

    @Test
    public void testSetUp()
            throws TargetSetupError, DeviceNotAvailableException, ConfigurationException {
        mOptionSetter.setOptionValue("action", "wake");
        mOptionSetter.setOptionValue("device-timeout", "1s");
        mOptionSetter.setOptionValue("wait-for-device-online", "true");

        mPreparer.setUp(mTestDevice, mBuildInfo);
        // fetched device, executed actions, and verified status
        verify(mUsb, times(1)).getAoaDevice(any(), eq(Duration.ofSeconds(1L)));
        verify(mPreparer, times(1)).execute(eq(mDevice), eq("wake"));
        verify(mTestDevice, times(1)).waitForDeviceOnline();
    }

    @Test
    public void testSetUp_noActions() throws TargetSetupError, DeviceNotAvailableException {
        mPreparer.setUp(mTestDevice, mBuildInfo);
        // no-op if no actions provided
        verifyZeroInteractions(mUsb);
        verify(mPreparer, never()).execute(eq(mDevice), any());
        verifyZeroInteractions(mTestDevice);
    }

    @Test(expected = DeviceNotAvailableException.class)
    public void testSetUp_noDevice()
            throws TargetSetupError, DeviceNotAvailableException, ConfigurationException {
        mOptionSetter.setOptionValue("action", "wake");
        when(mUsb.getAoaDevice(any(), any())).thenReturn(null); // device not found or incompatible
        mPreparer.setUp(mTestDevice, mBuildInfo);
    }

    @Test
    public void testSetUp_skipDeviceCheck()
            throws TargetSetupError, DeviceNotAvailableException, ConfigurationException {
        mOptionSetter.setOptionValue("action", "wake");
        mOptionSetter.setOptionValue("wait-for-device-online", "false"); // device check disabled

        mPreparer.setUp(mTestDevice, mBuildInfo);
        // actions executed, but status check skipped
        verify(mPreparer, times(1)).execute(eq(mDevice), eq("wake"));
        verify(mTestDevice, never()).waitForDeviceOnline();
    }

    @Test
    public void testClick() {
        mPreparer.execute(mDevice, "click 1 23");

        verify(mDevice, times(1)).click(eq(new Point(1, 23)));
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testLongClick() {
        mPreparer.execute(mDevice, "longClick 23 4");

        verify(mDevice, times(1)).longClick(eq(new Point(23, 4)));
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testScroll() {
        mPreparer.execute(mDevice, "scroll 3 45 6 78");

        verify(mDevice, times(1)).scroll(eq(new Point(3, 45)), eq(new Point(6, 78)));
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testFling() {
        mPreparer.execute(mDevice, "fling 45 6 78 9");

        verify(mDevice, times(1)).fling(eq(new Point(45, 6)), eq(new Point(78, 9)));
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testDrag() {
        mPreparer.execute(mDevice, "drag 5 67 8 90");

        verify(mDevice, times(1)).drag(eq(new Point(5, 67)), eq(new Point(8, 90)));
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testWrite() {
        mPreparer.execute(mDevice, "write lorem ipsum");

        verify(mDevice, times(1)).write(eq("lorem ipsum"));
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testKey() {
        mPreparer.execute(mDevice, "key 44");
        // accepts hexadecimal values
        mPreparer.execute(mDevice, "key 0x2C");

        verify(mDevice, times(2)).key(eq(44));
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testKey_multiple() {
        mPreparer.execute(mDevice, "key 1 2 3 4 5");

        verify(mDevice, times(1)).key(eq(1), eq(2), eq(3), eq(4), eq(5));
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testKey_repeated() {
        mPreparer.execute(mDevice, "key 3* 0x2C");

        verify(mDevice, times(1)).key(eq(44), eq(44), eq(44));
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testWake() {
        mPreparer.execute(mDevice, "wake");

        verify(mDevice, times(1)).wakeUp();
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testHome() {
        mPreparer.execute(mDevice, "home");

        verify(mDevice, times(1)).goHome();
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testBack() {
        mPreparer.execute(mDevice, "back");

        verify(mDevice, times(1)).goBack();
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test
    public void testSleep() {
        mPreparer.execute(mDevice, "sleep PT10M");

        verify(mDevice, times(1)).sleep(eq(Duration.ofMinutes(10L)));
        verifyNoMoreInteractions(ignoreStubs(mDevice));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalid_unknownKeyword() {
        mPreparer.execute(mDevice, "jump 12 3");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalid_missingCoordinates() {
        mPreparer.execute(mDevice, "click");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalid_tooFewCoordinates() {
        mPreparer.execute(mDevice, "longClick 1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalid_tooManyCoordinates() {
        mPreparer.execute(mDevice, "scroll 1 2 3 4 5");
    }
}
