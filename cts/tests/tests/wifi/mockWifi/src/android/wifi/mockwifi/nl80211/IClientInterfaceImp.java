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

import android.net.wifi.nl80211.IClientInterface;
import android.net.wifi.nl80211.ISendMgmtFrameEvent;
import android.net.wifi.nl80211.IWifiScannerImpl;
import android.util.Log;

public class IClientInterfaceImp extends IClientInterface.Stub {
    private static final String TAG = "IClientInterfaceImp";

    private IWifiScannerImp mIWifiScannerImp;
    private String mIfaceName = null;

    private int mCurrentRssiDbm = 0;
    private int mTxBitrateMbps = 0;
    private int mRxBitrateMbps = 0;
    private int mAssociationFrequencyMHz = 0;

    public IClientInterfaceImp(String ifaceName) {
        mIfaceName = ifaceName;
        mIWifiScannerImp = new IWifiScannerImp(ifaceName);
    }

    public void setRxBitrateMbps(int rxBitrateMbps) {
        mRxBitrateMbps = rxBitrateMbps;
    }

    public void setTxBitrateMbps(int txBitrateMbps) {
        mTxBitrateMbps = txBitrateMbps;
    }

    public void setCurrentRssiDbm(int currentRssiDbm) {
        mCurrentRssiDbm = currentRssiDbm;
    }

    public void setAssociationFrequencyMHz(int associationFrequencyMHz) {
        mAssociationFrequencyMHz = associationFrequencyMHz;
    }

    // Supported methods in IClientInterface.aidl
    @Override
    public int[] signalPoll() {
        Log.d(TAG, "signalPoll");
        return new int[] {mCurrentRssiDbm, mTxBitrateMbps,
                mAssociationFrequencyMHz, mRxBitrateMbps};
    }

    @Override
    public int[] getPacketCounters() {
        Log.d(TAG, "getPacketCounters");
        // TODO: Mock it when we have a use (test) case.
        return null;
    }

    @Override
    public byte[] getMacAddress() {
        Log.d(TAG, "getMacAddress");
        // TODO: Mock it when we have a use (test) case.
        return null;
    }

    @Override
    public String getInterfaceName() {
        Log.d(TAG, "getInterfaceName");
        // TODO: Mock it when we have a use (test) case.
        return null;
    }

    @Override
    public IWifiScannerImpl getWifiScannerImpl() {
        Log.d(TAG, "getWifiScannerImpl");
        return mIWifiScannerImp;
    }

    // CHECKSTYLE:OFF Generated code
    @Override
    public void SendMgmtFrame(byte[] frame, ISendMgmtFrameEvent callback, int mcs) {
        Log.d(TAG, "SendMgmtFrame");
        // TODO: Mock it when we have a use (test) case.
    }
    // CHECKSTYLE:ON Generated code
}
