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

package com.android.tv.settings;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.graphics.drawable.Drawable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import com.android.tv.settings.connectivity.ConnectivityListener;

import java.util.Locale;

/**
 * Main settings which loads up the top level headers.
 */
public class MainSettings extends MenuActivity implements OnAccountsUpdateListener,
        ConnectivityListener.Listener {

    private BrowseInfo mBrowseInfo;
    private AccountManager mAccountManager;
    private Locale mCurrentLocale;
    private IntentFilter mAdapterIntentFilter;
    private ConnectivityListener mConnectivityListener;

    // Broadcast Receiver for Bluetooth related events
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                mBrowseInfo.bluetoothDeviceConnected(device);
            } else if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                mBrowseInfo.bluetoothDeviceDisconnected(device);
            }
            mBrowseInfo.updateAccessories();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mBrowseInfo = new BrowseInfo(this);
        mBrowseInfo.init();
        mCurrentLocale = Locale.getDefault();
        mAccountManager = AccountManager.get(this);
        mAdapterIntentFilter = new IntentFilter();

        mAdapterIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        mAdapterIntentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mAdapterIntentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        mAdapterIntentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);

        registerReceiver(mBroadcastReceiver, mAdapterIntentFilter);
        super.onCreate(savedInstanceState);

        mConnectivityListener = new ConnectivityListener(this, this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mAccountManager.addOnAccountsUpdatedListener(this, null, true);
        // Update here, just in case.
        onAccountsUpdated(null);
        mConnectivityListener.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mBrowseInfo.checkForDeveloperOptionUpdate();
    }

    @Override
    protected void onStop() {
        mAccountManager.removeOnAccountsUpdatedListener(this);
        super.onStop();
        mConnectivityListener.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mBroadcastReceiver);
        mAccountManager.removeOnAccountsUpdatedListener(this);
    }

    @Override
    public void onConnectivityChange(Intent intent) {
        mBrowseInfo.updateWifi();
    }

    @Override
    protected String getBrowseTitle() {
        return getString(R.string.settings_app_name);
    }

    @Override
    protected Drawable getBadgeImage() {
        return getResources().getDrawable(R.drawable.ic_settings_app_icon);
    }

    @Override
    protected BrowseInfoFactory getBrowseInfoFactory() {
        if (!mCurrentLocale.equals(Locale.getDefault())) {
            // the System Locale information has changed
            mCurrentLocale = Locale.getDefault();
            mBrowseInfo.rebuildInfo();
        }

        return mBrowseInfo;
    }

    @Override
    public void onAccountsUpdated(Account[] accounts) {
        mBrowseInfo.updateAccounts();
    }
}
