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

package android.wifi.mockwifi;

import android.content.Context;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import java.util.concurrent.TimeUnit;

public class MockWifiModemManager {
    private static final String TAG = "MockWifiModemManager";

    private static Context sContext;
    private static MockWifiModemServiceConnector sServiceConnector;
    private MockWifiModemService mMockWifiModemService;

    public MockWifiModemManager() {
        sContext = InstrumentationRegistry.getInstrumentation().getContext();
    }

    private void waitForWifiFrameworkDone(int delayInSec) throws Exception {
        TimeUnit.SECONDS.sleep(delayInSec);
    }

    /* Public APIs */

    /**
     * Bring up Mock Modem Service and connect to it.
     *
     * @return boolean true if the operation is successful, otherwise false.
     */
    public boolean connectMockWifiModemService() throws Exception {
        if (sServiceConnector == null) {
            sServiceConnector =
                    new MockWifiModemServiceConnector(InstrumentationRegistry.getInstrumentation());
        }

        if (sServiceConnector == null) {
            Log.e(TAG, "Create MockWifiModemServiceConnector failed!");
            return false;
        }
        boolean result = sServiceConnector.connectMockWifiModemService();
        Log.i(TAG, "sServiceConnector.connectMockWifiModemService return " + result);
        if (result) {
            mMockWifiModemService = sServiceConnector.getMockWifiModemService();

            if (mMockWifiModemService != null) {
                /*
                  It may need to have a delay to wait for Wifi Framework to bind with
                  MockWifiModemService and setup for mocked HAL.
                  Currently, there is no setup which is required. So 1 sec is enough for now.
                */
                waitForWifiFrameworkDone(1);
            } else {
                Log.e(TAG, "MockWifiModemService get failed!");
            }
        }

        return result;
    }

    /**
     * Disconnect from Mock Modem Service.
     *
     * @return boolean true if the operation is successful, otherwise false.
     */
    public boolean disconnectMockWifiModemService() throws Exception {
        if (sServiceConnector == null) {
            Log.e(TAG, "No MockWifiModemServiceConnector exist!");
            return false;
        }
        boolean result = sServiceConnector.disconnectMockWifiModemService();

        if (result) {
            mMockWifiModemService = null;
        } else {
            Log.e(TAG, "MockWifiModemService disconnected failed!");
        }

        return result;
    }

    /**
     * Update list of mocked methods in the framework.
     */
    public boolean updateConfiguredMockedMethods() throws Exception {
        if (sServiceConnector == null) {
            Log.e(TAG, "No MockWifiModemServiceConnector exists!");
            return false;
        }
        return sServiceConnector.updateConfiguredMockedMethods();
    }

    /**
     * Configures the return result for signalPoll API for the target iface name.
     *
     */
    public boolean configureSignalPoll(String ifaceName, int currentRssiDbm, int txBitrateMbps,
            int rxBitrateMbps, int associationFrequencyMHz) {
        if (mMockWifiModemService == null) {
            return false;
        }
        return mMockWifiModemService.configureSignalPoll(ifaceName, currentRssiDbm, txBitrateMbps,
                rxBitrateMbps, associationFrequencyMHz);
    }
}
