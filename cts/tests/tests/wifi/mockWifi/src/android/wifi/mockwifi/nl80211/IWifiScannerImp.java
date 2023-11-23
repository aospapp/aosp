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

package android.wifi.mockwifi.nl80211;

import android.net.wifi.nl80211.IPnoScanEvent;
import android.net.wifi.nl80211.IScanEvent;
import android.net.wifi.nl80211.IWifiScannerImpl;
import android.net.wifi.nl80211.NativeScanResult;
import android.net.wifi.nl80211.PnoSettings;
import android.net.wifi.nl80211.SingleScanSettings;
import android.util.Log;

public class IWifiScannerImp extends IWifiScannerImpl.Stub {
    private static final String TAG = "IWifiScannerImp";

    private String mIfaceName;

    public IWifiScannerImp(String ifaceName) {
        mIfaceName = ifaceName;
    }

    // Supported methods in IWifiScannerImpl.aidl
    @Override
    public NativeScanResult[] getScanResults() {
        Log.i(TAG, "getScanResults");
        // TODO: Mock it when we have a use (test) case.
        return null;
    }

    @Override
    public NativeScanResult[] getPnoScanResults() {
        Log.i(TAG, "getPnoScanResults");
        // TODO: Mock it when we have a use (test) case.
        return null;
    }

    @Override
    public boolean scan(SingleScanSettings scanSettings) {
        Log.i(TAG, "scan");
        // TODO: Mock it when we have a use (test) case.
        return true;
    }

    @Override
    public int scanRequest(SingleScanSettings scanSettings) {
        Log.i(TAG, "scanRequest");
        // TODO: Mock it when we have a use (test) case.
        return 0;
    }

    @Override
    public void subscribeScanEvents(IScanEvent handler) {
        Log.i(TAG, "subscribeScanEvents");
        // TODO: Mock it when we have a use (test) case.
    }

    @Override
    public void unsubscribeScanEvents() {
        Log.i(TAG, "unsubscribeScanEvents");
        // TODO: Mock it when we have a use (test) case.
    }

    @Override
    public void subscribePnoScanEvents(IPnoScanEvent handler) {
        Log.i(TAG, "subscribePnoScanEvents");
        // TODO: Mock it when we have a use (test) case.
    }

    @Override
    public void unsubscribePnoScanEvents() {
        Log.i(TAG, "unsubscribePnoScanEvents");
        // TODO: Mock it when we have a use (test) case.
    }

    @Override
    public boolean startPnoScan(PnoSettings pnoSettings) {
        Log.i(TAG, "startPnoScan");
        // TODO: Mock it when we have a use (test) case.
        return false;
    }

    @Override
    public boolean stopPnoScan() {
        Log.i(TAG, "stopPnoScan");
        // TODO: Mock it when we have a use (test) case.
        return false;
    }

    @Override
    public void abortScan() {
        Log.i(TAG, "abortScan");
        // TODO: Mock it when we have a use (test) case.
    }

    @Override
    public int getMaxSsidsPerScan() {
        Log.i(TAG, "getMaxSsidsPerScan");
        // TODO: Mock it when we have a use (test) case.
        return 0;
    }
}
