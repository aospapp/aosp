/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telephony.cts;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OPSTR_READ_PHONE_STATE;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_NAME_STRING;
import static android.telephony.CarrierConfigManager.KEY_FORCE_HOME_NETWORK_BOOL;
import static android.telephony.ServiceState.STATE_IN_SERVICE;

import static androidx.test.InstrumentationRegistry.getContext;
import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static com.android.compatibility.common.util.AppOpsUtils.setOpMode;
import static com.android.internal.telephony.TelephonyIntents.EXTRA_SPN;
import static com.android.internal.telephony.TelephonyIntents.SPN_STRINGS_UPDATED_ACTION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Looper;
import android.os.PersistableBundle;
import android.platform.test.annotations.SecurityTest;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.compatibility.common.util.TestThread;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

public class CarrierConfigManagerTest {

    private static final String CARRIER_NAME_OVERRIDE = "carrier_a";
    private CarrierConfigManager mConfigManager;
    private TelephonyManager mTelephonyManager;
    private static final int TOLERANCE = 2000;
    private final Object mLock = new Object();

    @Before
    public void setUp() throws Exception {
        mTelephonyManager = (TelephonyManager)
                getContext().getSystemService(Context.TELEPHONY_SERVICE);
        mConfigManager = (CarrierConfigManager)
                getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
    }

    @After
    public void tearDown() throws Exception {
        try {
            setOpMode("android.telephony.cts", OPSTR_READ_PHONE_STATE, MODE_ALLOWED);
        } catch (IOException e) {
            fail();
        }
    }

    /**
     * Checks whether the telephony stack should be running on this device.
     *
     * Note: "Telephony" means only SMS/MMS and voice calls in some contexts, but we also care if
     * the device supports cellular data.
     */
    private boolean hasTelephony() {
        ConnectivityManager mgr =
                (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        return mgr.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
    }

    private boolean isSimCardPresent() {
        return mTelephonyManager.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE &&
                mTelephonyManager.getSimState() != TelephonyManager.SIM_STATE_UNKNOWN &&
                mTelephonyManager.getSimState() != TelephonyManager.SIM_STATE_ABSENT;
    }

    private boolean isSimCardAbsent() {
        return mTelephonyManager.getSimState() == TelephonyManager.SIM_STATE_ABSENT;
    }

    private void checkConfig(PersistableBundle config) {
        if (config == null) {
            assertFalse("Config should only be null when telephony is not running.", hasTelephony());
            return;
        }
        assertNotNull("CarrierConfigManager should not return null config", config);
        if (isSimCardAbsent()) {
            // Static default in CarrierConfigManager will be returned when no sim card present.
            assertEquals("Config doesn't match static default.",
                    config.getBoolean(CarrierConfigManager.KEY_ADDITIONAL_CALL_SETTING_BOOL), true);

            assertEquals("KEY_VVM_DESTINATION_NUMBER_STRING doesn't match static default.",
                config.getString(CarrierConfigManager.KEY_VVM_DESTINATION_NUMBER_STRING), "");
            assertEquals("KEY_VVM_PORT_NUMBER_INT doesn't match static default.",
                config.getInt(CarrierConfigManager.KEY_VVM_PORT_NUMBER_INT), 0);
            assertEquals("KEY_VVM_TYPE_STRING doesn't match static default.",
                config.getString(CarrierConfigManager.KEY_VVM_TYPE_STRING), "");
            assertEquals("KEY_VVM_CELLULAR_DATA_REQUIRED_BOOLEAN doesn't match static default.",
                config.getBoolean(CarrierConfigManager.KEY_VVM_CELLULAR_DATA_REQUIRED_BOOL),
                false);
            assertEquals("KEY_VVM_PREFETCH_BOOLEAN doesn't match static default.",
                config.getBoolean(CarrierConfigManager.KEY_VVM_PREFETCH_BOOL), true);
            assertEquals("KEY_CARRIER_VVM_PACKAGE_NAME_STRING doesn't match static default.",
                config.getString(CarrierConfigManager.KEY_CARRIER_VVM_PACKAGE_NAME_STRING), "");
            assertFalse(CarrierConfigManager.isConfigForIdentifiedCarrier(config));
        }
    }

    @Test
    public void testGetConfig() {
        PersistableBundle config = mConfigManager.getConfig();
        checkConfig(config);
    }

    @SecurityTest
    @Test
    public void testRevokePermission() {
        PersistableBundle config;

        try {
            setOpMode("android.telephony.cts", OPSTR_READ_PHONE_STATE, MODE_IGNORED);
        } catch (IOException e) {
            fail();
        }

        config = mConfigManager.getConfig();
        assertTrue(config.isEmptyParcel());

        try {
            setOpMode("android.telephony.cts", OPSTR_READ_PHONE_STATE, MODE_ALLOWED);
        } catch (IOException e) {
            fail();
        }

        config = mConfigManager.getConfig();
        checkConfig(config);
    }

    @Test
    public void testGetConfigForSubId() {
        PersistableBundle config =
                mConfigManager.getConfigForSubId(SubscriptionManager.getDefaultSubscriptionId());
        checkConfig(config);
    }

    /**
     * Tests the CarrierConfigManager.notifyConfigChangedForSubId() API. This makes a call to
     * notifyConfigChangedForSubId() API and expects a SecurityException since the test apk is not signed
     * by certificate on the SIM.
     */
    @Test
    public void testNotifyConfigChangedForSubId() {
        try {
            if (isSimCardPresent()) {
                mConfigManager.notifyConfigChangedForSubId(
                        SubscriptionManager.getDefaultSubscriptionId());
                fail("Expected SecurityException. App doesn't have carrier privileges.");
            }
        } catch (SecurityException expected) {
        }
    }

    /**
     * This checks that {@link CarrierConfigManager#overrideConfig(int, PersistableBundle)}
     * correctly overrides the Carrier Name (SPN) string.
     */
    @Test
    public void testCarrierConfigNameOverride() throws Exception {
        if (!isSimCardPresent()
                || mTelephonyManager.getServiceState().getState() != STATE_IN_SERVICE) {
            return;
        }

        // Adopt shell permission so the required permission (android.permission.MODIFY_PHONE_STATE)
        // is granted.
        UiAutomation ui = getInstrumentation().getUiAutomation();
        ui.adoptShellPermissionIdentity();

        int subId = SubscriptionManager.getDefaultSubscriptionId();
        TestThread t = new TestThread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();

                PersistableBundle carrierNameOverride = new PersistableBundle(3);
                carrierNameOverride.putBoolean(KEY_CARRIER_NAME_OVERRIDE_BOOL, true);
                carrierNameOverride.putBoolean(KEY_FORCE_HOME_NETWORK_BOOL, true);
                carrierNameOverride.putString(KEY_CARRIER_NAME_STRING, CARRIER_NAME_OVERRIDE);
                mConfigManager.overrideConfig(subId, carrierNameOverride);

                Looper.loop();
            }
        });

        try {
            BroadcastReceiver spnBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (CARRIER_NAME_OVERRIDE.equals(intent.getStringExtra(EXTRA_SPN))) {
                        synchronized (mLock) {
                            mLock.notify();
                        }
                    }
                }
            };

            getContext().registerReceiver(
                    spnBroadcastReceiver,
                    new IntentFilter(SPN_STRINGS_UPDATED_ACTION));

            synchronized (mLock) {
                t.start();
                mLock.wait(TOLERANCE); // wait for SPN broadcast
            }

            assertEquals(CARRIER_NAME_OVERRIDE, mTelephonyManager.getSimOperatorName());
        } finally {
            mConfigManager.overrideConfig(subId, null);
            ui.dropShellPermissionIdentity();
        }
    }
}
