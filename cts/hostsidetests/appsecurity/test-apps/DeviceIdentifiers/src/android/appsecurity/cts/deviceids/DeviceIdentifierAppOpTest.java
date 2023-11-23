/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.appsecurity.cts.deviceids;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.fail;

import android.content.Context;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.ShellIdentityUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Verify apps can access device identifiers with the app op granted.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceIdentifierAppOpTest  {
    private static final String DEVICE_ID_WITH_APPOP_ERROR_MESSAGE =
            "An unexpected value was received by an app with the READ_DEVICE_IDENTIFIERS app op "
                    + "granted when invoking %s.";

    @Test
    public void testAccessToDeviceIdentifiersWithAppOp() throws Exception {
        TelephonyManager telephonyManager =
                (TelephonyManager) InstrumentationRegistry.getContext().getSystemService(
                        Context.TELEPHONY_SERVICE);
        try {
            assertEquals(String.format(DEVICE_ID_WITH_APPOP_ERROR_MESSAGE, "getDeviceId"),
                    ShellIdentityUtils.invokeMethodWithShellPermissions(telephonyManager,
                            (tm) -> tm.getDeviceId()), telephonyManager.getDeviceId());
            assertEquals(String.format(DEVICE_ID_WITH_APPOP_ERROR_MESSAGE, "getImei"),
                    ShellIdentityUtils.invokeMethodWithShellPermissions(telephonyManager,
                            (tm) -> tm.getImei()), telephonyManager.getImei());
            assertEquals(String.format(DEVICE_ID_WITH_APPOP_ERROR_MESSAGE, "getMeid"),
                    ShellIdentityUtils.invokeMethodWithShellPermissions(telephonyManager,
                            (tm) -> tm.getMeid()), telephonyManager.getMeid());
            assertEquals(String.format(DEVICE_ID_WITH_APPOP_ERROR_MESSAGE, "getSubscriberId"),
                    ShellIdentityUtils.invokeMethodWithShellPermissions(telephonyManager,
                            (tm) -> tm.getSubscriberId()), telephonyManager.getSubscriberId());
            assertEquals(
                    String.format(DEVICE_ID_WITH_APPOP_ERROR_MESSAGE, "getSimSerialNumber"),
                    ShellIdentityUtils.invokeMethodWithShellPermissions(telephonyManager,
                            (tm) -> tm.getSimSerialNumber()),
                    telephonyManager.getSimSerialNumber());
            assertEquals(String.format(DEVICE_ID_WITH_APPOP_ERROR_MESSAGE, "Build#getSerial"),
                    ShellIdentityUtils.invokeStaticMethodWithShellPermissions(Build::getSerial),
                    Build.getSerial());
        } catch (SecurityException e) {
            fail("An app with the READ_DEVICE_IDENTIFIERS app op set to allowed must be able to "
                    + "access the device identifiers; caught SecurityException instead: "
                    + e);
        }
    }
}
