/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.bluetooth.cts;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanRecord;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Utility class for Bluetooth CTS test.
 */
public class TestUtils {
    /**
     * Checks whether this device has Bluetooth feature
     * @return true if this device has Bluetooth feature
     */
    public static boolean hasBluetooth() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        return context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH);
    }

    /**
     * Get the current enabled status of a given profile
     */
    public static boolean isProfileEnabled(int profile) {
        switch (profile) {
            case BluetoothProfile.A2DP:
                return BluetoothProperties.isProfileA2dpSourceEnabled().orElse(false);
            case BluetoothProfile.A2DP_SINK:
                return BluetoothProperties.isProfileA2dpSinkEnabled().orElse(false);
            // Hidden profile
            // case BluetoothProfile.AVRCP:
            //     return BluetoothProperties.isProfileAvrcpTargetEnabled().orElse(false);
            case BluetoothProfile.AVRCP_CONTROLLER:
                return BluetoothProperties.isProfileAvrcpControllerEnabled().orElse(false);
            case BluetoothProfile.CSIP_SET_COORDINATOR:
                return BluetoothProperties.isProfileCsipSetCoordinatorEnabled().orElse(false);
            case BluetoothProfile.GATT:
                return BluetoothProperties.isProfileGattEnabled().orElse(true);
            case BluetoothProfile.HAP_CLIENT:
                return BluetoothProperties.isProfileHapClientEnabled().orElse(false);
            case BluetoothProfile.HEADSET:
                return BluetoothProperties.isProfileHfpAgEnabled().orElse(false);
            case BluetoothProfile.HEADSET_CLIENT:
                return BluetoothProperties.isProfileHfpHfEnabled().orElse(false);
            case BluetoothProfile.HEARING_AID:
                Context context = InstrumentationRegistry.getInstrumentation().getContext();
                if (!isBleSupported(context)) {
                    return false;
                }
                boolean default_value = true;
                if (isAutomotive(context) || isWatch(context) || isTv(context)) {
                    default_value = false;
                }
                return BluetoothProperties.isProfileAshaCentralEnabled().orElse(default_value);
            case BluetoothProfile.HID_DEVICE:
                return BluetoothProperties.isProfileHidDeviceEnabled().orElse(false);
            case BluetoothProfile.HID_HOST:
                return BluetoothProperties.isProfileHidHostEnabled().orElse(false);
            case BluetoothProfile.LE_AUDIO:
                return BluetoothProperties.isProfileBapUnicastClientEnabled().orElse(false);
            case BluetoothProfile.LE_AUDIO_BROADCAST:
                return BluetoothProperties.isProfileBapBroadcastSourceEnabled().orElse(false);
            case BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT:
                return BluetoothProperties.isProfileBapBroadcastAssistEnabled().orElse(false);
            // Hidden profile
            // case BluetoothProfile.LE_CALL_CONTROL:
            //     return BluetoothProperties.isProfileCcpServerEnabled().orElse(false);
            case BluetoothProfile.MAP:
                return BluetoothProperties.isProfileMapServerEnabled().orElse(false);
            case BluetoothProfile.MAP_CLIENT:
                return BluetoothProperties.isProfileMapClientEnabled().orElse(false);
            // Hidden profile
            // case BluetoothProfile.MCP_SERVER:
            //     return BluetoothProperties.isProfileMcpServerEnabled().orElse(false);
            case BluetoothProfile.OPP:
                return BluetoothProperties.isProfileOppEnabled().orElse(false);
            case BluetoothProfile.PAN:
                return BluetoothProperties.isProfilePanNapEnabled().orElse(false)
                        || BluetoothProperties.isProfilePanPanuEnabled().orElse(false);
            case BluetoothProfile.PBAP:
                return BluetoothProperties.isProfilePbapServerEnabled().orElse(false);
            case BluetoothProfile.PBAP_CLIENT:
                return BluetoothProperties.isProfilePbapClientEnabled().orElse(false);
            case BluetoothProfile.SAP:
                return BluetoothProperties.isProfileSapServerEnabled().orElse(false);
            case BluetoothProfile.VOLUME_CONTROL:
                return BluetoothProperties.isProfileVcpControllerEnabled().orElse(false);
            default:
                return false;
        }
    }

    /**
     * Adopt shell UID's permission via {@link android.app.UiAutomation}
     * @param permission permission to adopt
     */
    public static void adoptPermissionAsShellUid(@Nullable String... permission) {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(permission);
    }

    /**
     * Drop all permissions adopted as shell UID
     */
    public static void dropPermissionAsShellUid() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    /**
     * @return permissions adopted from Shell
     */
    public static Set<String> getAdoptedShellPermissions() {
        return InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .getAdoptedShellPermissions();
    }

    /**
     * Get {@link BluetoothAdapter} via {@link android.bluetooth.BluetoothManager}
     * Fail the test if {@link BluetoothAdapter} is null
     * @return instance of {@link BluetoothAdapter}
     */
    @NonNull public static BluetoothAdapter getBluetoothAdapterOrDie() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        assertNotNull(manager);
        BluetoothAdapter adapter = manager.getAdapter();
        assertNotNull(adapter);
        return adapter;
    }

    /**
     * Utility method to call hidden ScanRecord.parseFromBytes method.
     */
    public static ScanRecord parseScanRecord(byte[] bytes) {
        Class<?> scanRecordClass = ScanRecord.class;
        try {
            Method method = scanRecordClass.getDeclaredMethod("parseFromBytes", byte[].class);
            return (ScanRecord) method.invoke(null, bytes);
        } catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException e) {
            return null;
        }
    }

    /**
     * Assert two byte arrays are equal.
     */
    public static void assertArrayEquals(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail("expected:<" + Arrays.toString(expected)
                    + "> but was:<" + Arrays.toString(actual) + ">");
        }
    }

    /**
     * Get current location mode settings.
     */
    public static int getLocationMode(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
    }

    /**
     * Set location settings mode.
     */
    public static void setLocationMode(Context context, int mode) {
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE,
                mode);
    }

    /**
     * Return true if location is on.
     */
    public static boolean isLocationOn(Context context) {
        return getLocationMode(context) != Settings.Secure.LOCATION_MODE_OFF;
    }

    /**
     * Enable location and set the mode to GPS only.
     */
    public static void enableLocation(Context context) {
        setLocationMode(context, Settings.Secure.LOCATION_MODE_SENSORS_ONLY);
    }

    /**
     * Disable location.
     */
    public static void disableLocation(Context context) {
        setLocationMode(context, Settings.Secure.LOCATION_MODE_OFF);
    }

    /**
     * Check if BLE is supported by this platform
     * @param context current device context
     * @return true if BLE is supported, false otherwise
     */
    public static boolean isBleSupported(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    /**
     * Check if this is an automotive device
     * @param context current device context
     * @return true if this Android device is an automotive device, false otherwise
     */
    public static boolean isAutomotive(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    /**
     * Check if this is a watch device
     * @param context current device context
     * @return true if this Android device is a watch device, false otherwise
     */
    public static boolean isWatch(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    /**
     * Check if this is a TV device
     * @param context current device context
     * @return true if this Android device is a TV device, false otherwise
     */
    public static boolean isTv(Context context) {
        PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    /**
     * Put the current thread to sleep.
     * @param sleepMillis number of milliseconds to sleep for
     */
    public static void sleep(int sleepMillis) {
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            Log.e(TestUtils.class.getSimpleName(), "interrupted", e);
        }
    }

    /**
     * Boilerplate class for profile listener
     */
    public static class BluetoothCtsServiceConnector {
        private static final int PROXY_CONNECTION_TIMEOUT_MS = 500;  // ms timeout for Proxy Connect
        private BluetoothProfile mProfileProxy = null;
        private boolean mIsProfileReady = false;
        private boolean mIsProfileConnecting = false;
        private final Condition mConditionProfileConnection;
        private final ReentrantLock mProfileConnectionLock;
        private final String mLogTag;
        private final int mProfileId;
        private final BluetoothAdapter mAdapter;
        private final Context mContext;
        public BluetoothCtsServiceConnector(String logTag, int profileId, BluetoothAdapter adapter,
                Context context) {
            mLogTag = logTag;
            mProfileId = profileId;
            mAdapter = adapter;
            mContext = context;
            mProfileConnectionLock = new ReentrantLock();
            mConditionProfileConnection = mProfileConnectionLock.newCondition();
            assertNotNull(mLogTag);
            assertNotNull(mAdapter);
            assertNotNull(mContext);
        }

        public BluetoothProfile getProfileProxy() {
            return mProfileProxy;
        }

        public void closeProfileProxy() {
            if (mProfileProxy != null) {
                mAdapter.closeProfileProxy(mProfileId, mProfileProxy);
                mProfileProxy = null;
                mIsProfileReady = false;
            }
        }

        public boolean openProfileProxyAsync() {
            mIsProfileConnecting = mAdapter.getProfileProxy(mContext, mServiceListener, mProfileId);
            return mIsProfileConnecting;
        }

        public boolean waitForProfileConnect() {
            return waitForProfileConnect(PROXY_CONNECTION_TIMEOUT_MS);
        }

        public boolean waitForProfileConnect(int timeoutMs) {
            if (!mIsProfileConnecting) {
                mIsProfileConnecting =
                        mAdapter.getProfileProxy(mContext, mServiceListener, mProfileId);
            }
            if (!mIsProfileConnecting) {
                return false;
            }
            mProfileConnectionLock.lock();
            try {
                // Wait for the Adapter to be disabled
                while (!mIsProfileReady) {
                    if (!mConditionProfileConnection.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                        // Timeout
                        Log.e(mLogTag, "Timeout while waiting for Profile Connect");
                        break;
                    } // else spurious wake-ups
                }
            } catch (InterruptedException e) {
                Log.e(mLogTag, "waitForProfileConnect: interrupted");
            } finally {
                mProfileConnectionLock.unlock();
            }
            mIsProfileConnecting = false;
            return mIsProfileReady;
        }

        private final BluetoothProfile.ServiceListener mServiceListener =
                new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                mProfileConnectionLock.lock();
                mProfileProxy = proxy;
                mIsProfileReady = true;
                try {
                    mConditionProfileConnection.signal();
                } finally {
                    mProfileConnectionLock.unlock();
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                mProfileConnectionLock.lock();
                mIsProfileReady = false;
                try {
                    mConditionProfileConnection.signal();
                } finally {
                    mProfileConnectionLock.unlock();
                }
            }
        };
    }
}
