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
package com.android.helper.aoa;

import static com.android.helper.aoa.AoaDevice.ACCESSORY_REGISTER_HID;
import static com.android.helper.aoa.AoaDevice.ACCESSORY_SEND_HID_EVENT;
import static com.android.helper.aoa.AoaDevice.ACCESSORY_SET_HID_REPORT_DESC;
import static com.android.helper.aoa.AoaDevice.ACCESSORY_START;
import static com.android.helper.aoa.AoaDevice.ACCESSORY_UNREGISTER_HID;
import static com.android.helper.aoa.AoaDevice.DEVICE_NOT_FOUND;
import static com.android.helper.aoa.AoaDevice.FLING_STEPS;
import static com.android.helper.aoa.AoaDevice.GOOGLE_VID;
import static com.android.helper.aoa.AoaDevice.LONG_CLICK;
import static com.android.helper.aoa.AoaDevice.SCROLL_STEPS;
import static com.android.helper.aoa.AoaDevice.SYSTEM_BACK;
import static com.android.helper.aoa.AoaDevice.SYSTEM_HOME;
import static com.android.helper.aoa.AoaDevice.SYSTEM_WAKE;
import static com.android.helper.aoa.AoaDevice.TOUCH_DOWN;
import static com.android.helper.aoa.AoaDevice.TOUCH_UP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.primitives.Shorts;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.verification.VerificationMode;

import java.awt.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

/** Unit tests for {@link AoaDevice} */
@RunWith(JUnit4.class)
public class AoaDeviceTest {

    private static final int HID_COUNT = AoaDevice.HID.values().length;
    private static final String SERIAL_NUMBER = "serial-number";
    private static final int INVALID_VID = 0x0000;
    private static final int ADB_DISABLED_PID = 0x2D00;
    private static final int ADB_ENABLED_PID = 0x2D01;

    private AoaDevice mDevice;

    private UsbHelper mHelper;
    private UsbDevice mDelegate;

    @Before
    public void setUp() {
        // valid accessory mode device by default
        mDelegate = mock(UsbDevice.class);
        when(mDelegate.isValid()).thenReturn(true);
        when(mDelegate.getSerialNumber()).thenReturn(SERIAL_NUMBER);
        when(mDelegate.getVendorId()).thenReturn(GOOGLE_VID);
        when(mDelegate.getProductId()).thenReturn(ADB_DISABLED_PID);

        mHelper = mock(UsbHelper.class);
        when(mHelper.getDevice(anyString(), any())).thenReturn(mDelegate);
    }

    // Initialization

    @Test
    public void testRegistersHIDsAutomatically() {
        mDevice = createDevice();

        // already in accessory mode
        verifyRequest(never(), ACCESSORY_START);

        // registers HIDs
        verifyRequest(never(), ACCESSORY_UNREGISTER_HID);
        verifyRequest(times(HID_COUNT), ACCESSORY_REGISTER_HID);
        verifyRequest(times(HID_COUNT), ACCESSORY_SET_HID_REPORT_DESC);
    }

    @Test
    public void testDetectsAccessoryMode() {
        // not in accessory mode initially
        when(mDelegate.getVendorId())
                .thenReturn(INVALID_VID)
                .thenReturn(INVALID_VID)
                .thenReturn(GOOGLE_VID);

        mDevice = createDevice();

        // restarts in accessory mode
        verifyRequest(times(1), ACCESSORY_START);

        // registers HIDs afterwards
        verifyRequest(never(), ACCESSORY_UNREGISTER_HID);
        verifyRequest(times(HID_COUNT), ACCESSORY_REGISTER_HID);
        verifyRequest(times(HID_COUNT), ACCESSORY_SET_HID_REPORT_DESC);
    }

    @Test
    public void testResetConnection() {
        mDevice = createDevice();
        clearInvocations(mDelegate);
        mDevice.resetConnection();

        verify(mHelper, times(1)).getDevice(eq(SERIAL_NUMBER), any());

        // re-registers HIDs
        verifyRequest(times(HID_COUNT), ACCESSORY_UNREGISTER_HID);
        verifyRequest(times(HID_COUNT), ACCESSORY_REGISTER_HID);
        verifyRequest(times(HID_COUNT), ACCESSORY_SET_HID_REPORT_DESC);
    }

    @Test(expected = UsbException.class)
    public void testThrowsIfConnectionInvalid() {
        when(mDelegate.isValid()).thenReturn(false);
        mDevice = createDevice();
    }

    @Test(expected = UsbException.class)
    public void testThrowsIfSerialNumberMissing() {
        when(mDelegate.getSerialNumber()).thenReturn(null);
        mDevice = createDevice();
    }

    @Test
    public void testGetSerialNumber() {
        mDevice = createDevice();
        assertEquals(SERIAL_NUMBER, mDevice.getSerialNumber());
    }

    @Test
    public void testIsAdbEnabled() {
        mDevice = createDevice();

        // invalid VID and valid PID
        when(mDelegate.getVendorId()).thenReturn(INVALID_VID);
        when(mDelegate.getProductId()).thenReturn(ADB_ENABLED_PID);
        assertFalse(mDevice.isAdbEnabled());

        // valid VID and invalid PID
        when(mDelegate.getVendorId()).thenReturn(GOOGLE_VID);
        when(mDelegate.getProductId()).thenReturn(ADB_DISABLED_PID);
        assertFalse(mDevice.isAdbEnabled());

        // both valid
        when(mDelegate.getVendorId()).thenReturn(GOOGLE_VID);
        when(mDelegate.getProductId()).thenReturn(ADB_ENABLED_PID);
        assertTrue(mDevice.isAdbEnabled());
    }

    @Test
    public void testClose() {
        mDevice = createDevice();
        mDevice.close();

        // unregisters descriptors and closes connection
        verifyRequest(times(HID_COUNT), ACCESSORY_UNREGISTER_HID);
        verify(mDelegate, times(1)).close();
    }

    // Actions

    @Test
    public void testClick() {
        mDevice = createDevice();
        clearInvocations(mDevice);
        mDevice.click(new Point(12, 34));

        verifyTouches(new Touch(TOUCH_DOWN, 12, 34), new Touch(TOUCH_UP, 12, 34));
    }

    @Test
    public void testLongClick() {
        mDevice = createDevice();
        clearInvocations(mDevice);
        mDevice.longClick(new Point(23, 45));

        verifyTouches(new Touch(TOUCH_DOWN, 23, 45), new Touch(TOUCH_UP, 23, 45));
        verify(mDevice, atLeastOnce()).sleep(LONG_CLICK);
    }

    @Test
    public void testScroll() {
        mDevice = createDevice();
        mDevice.scroll(new Point(0, 0), new Point(0, SCROLL_STEPS));

        // generate an event for each step, spaced one pixel apart
        List<Touch> events =
                Stream.iterate(0, i -> i + 1)
                        .limit(SCROLL_STEPS + 1)
                        .map(i -> new Touch(TOUCH_DOWN, 0, i))
                        .collect(Collectors.toList());
        events.add(new Touch(TOUCH_UP, 0, SCROLL_STEPS));

        verifyTouches(events);
    }

    @Test
    public void testFling() {
        mDevice = createDevice();
        mDevice.fling(new Point(0, 0), new Point(FLING_STEPS, 0));

        // generate an event for each step, spaced one pixel apart
        List<Touch> events =
                Stream.iterate(0, i -> i + 1)
                        .limit(FLING_STEPS + 1)
                        .map(i -> new Touch(TOUCH_DOWN, i, 0))
                        .collect(Collectors.toList());
        events.add(new Touch(TOUCH_UP, FLING_STEPS, 0));

        verifyTouches(events);
    }

    @Test
    public void testDrag() {
        mDevice = createDevice();
        mDevice.drag(new Point(0, 0), new Point(SCROLL_STEPS, 0));

        // generate an event for each step, spaced one pixel apart
        List<Touch> events =
                Stream.iterate(0, i -> i + 1)
                        .limit(SCROLL_STEPS + 1)
                        .map(i -> new Touch(TOUCH_DOWN, i, 0))
                        .collect(Collectors.toList());
        // drag is a long click followed by a scroll
        events.add(0, new Touch(TOUCH_DOWN, 0, 0));
        events.add(new Touch(TOUCH_UP, SCROLL_STEPS, 0));

        verifyTouches(events);
    }

    @Test
    public void testWrite() {
        mDevice = spy(createDevice());
        mDevice.write("Test #0123!");

        verify(mDevice).key(0x17, 0x08, 0x16, 0x17, 0x2C, null, 0x27, 0x1E, 0x1F, 0x20, null);
    }

    @Test
    public void testKey() {
        mDevice = createDevice();
        mDevice.key(1, null, 2);

        InOrder order = inOrder(mDelegate);
        // press and release 1
        verifyHidRequest(order, times(1), AoaDevice.HID.KEYBOARD, (byte) 1);
        verifyHidRequest(order, times(1), AoaDevice.HID.KEYBOARD, (byte) 0);
        // skip null, and press and release 2
        verifyHidRequest(order, times(1), AoaDevice.HID.KEYBOARD, (byte) 2);
        verifyHidRequest(order, times(1), AoaDevice.HID.KEYBOARD, (byte) 0);
    }

    @Test
    public void testWakeUp() {
        mDevice = createDevice();
        mDevice.wakeUp();

        verifyHidRequest(times(1), AoaDevice.HID.SYSTEM, SYSTEM_WAKE);
    }

    @Test
    public void testGoHome() {
        mDevice = createDevice();
        mDevice.goHome();

        verifyHidRequest(times(1), AoaDevice.HID.SYSTEM, SYSTEM_HOME);
    }

    @Test
    public void testGoBack() {
        mDevice = createDevice();
        mDevice.goBack();

        verifyHidRequest(times(1), AoaDevice.HID.SYSTEM, SYSTEM_BACK);
    }

    @Test
    public void testRetryAction() {
        mDevice = createDevice();

        // first attempt will fail to find device
        when(mDelegate.controlTransfer(anyByte(), anyByte(), anyInt(), anyInt(), any()))
                .thenReturn(DEVICE_NOT_FOUND)
                .thenReturn(0);

        mDevice.click(new Point(34, 56));

        // reset the connection
        verify(mHelper, times(1)).getDevice(eq(SERIAL_NUMBER), any());

        verifyTouches(
                new Touch(TOUCH_DOWN, 34, 56), // failed
                new Touch(TOUCH_DOWN, 34, 56), // retry after resetting connection
                new Touch(TOUCH_UP, 34, 56));
    }

    // Helpers

    private AoaDevice createDevice() {
        AoaDevice device = new AoaDevice(mHelper, mDelegate) {
            @Override
            public void sleep(@Nonnull Duration duration) {}
        };
        return spy(device);
    }

    private void verifyRequest(VerificationMode mode, byte request) {
        verify(mDelegate, mode)
                .controlTransfer(anyByte(), eq(request), anyInt(), anyInt(), any());
    }

    private void verifyHidRequest(VerificationMode mode, AoaDevice.HID hid, byte... data) {
        verifyHidRequest(null, mode, hid, data);
    }

    private void verifyHidRequest(
            InOrder order, VerificationMode mode, AoaDevice.HID hid, byte... data) {
        UsbDevice verifier =
                order == null ? verify(mDelegate, mode) : order.verify(mDelegate, mode);
        verifier.controlTransfer(
                anyByte(), eq(ACCESSORY_SEND_HID_EVENT), eq(hid.getId()), anyInt(), eq(data));
    }

    private void verifyTouches(Touch... expected) {
        verifyTouches(Arrays.asList(expected));
    }

    private void verifyTouches(List<Touch> expected) {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);

        verify(mDelegate, times(expected.size()))
                .controlTransfer(
                        anyByte(),
                        eq(ACCESSORY_SEND_HID_EVENT),
                        eq(AoaDevice.HID.TOUCH_SCREEN.getId()),
                        anyInt(),
                        captor.capture());

        List<Touch> events =
                captor.getAllValues().stream().map(Touch::new).collect(Collectors.toList());
        assertEquals(expected, events);
    }

    /** Touch HID event. */
    private static class Touch {

        private final byte mType;
        private final int mX;
        private final int mY;

        private Touch(byte type, int x, int y) {
            mType = type;
            mX = x;
            mY = y;
        }

        private Touch(byte[] data) {
            mType = data[0];
            mX = Shorts.fromBytes(data[2], data[1]);
            mY = Shorts.fromBytes(data[4], data[3]);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Touch)) {
                return false;
            }
            Touch event = (Touch) object;
            return mType == event.mType && mX == event.mX && mY == event.mY;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mType, mX, mY);
        }

        @Override
        public String toString() {
            return String.format("Touch{%d, %d, %d}", mType, mX, mY);
        }
    }
}
