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
package com.google.android.car.networking.railway;

import android.app.AlertDialog;
import android.app.Application;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.EthernetNetworkManagementException;
import android.net.EthernetNetworkSpecifier;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.google.common.base.Strings;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MainActivity extends FragmentActivity {
    private static final String TAG = MainActivity.class.getName();
    private static final Duration REQUEST_NETWORK_TIMEOUT =
            Duration.of(200, ChronoUnit.MILLIS);

    private ConfigurationUpdater mConfigurationUpdater;
    private InterfaceEnabler mInterfaceEnabler;
    private CurrentEthernetNetworksViewModel mCurrentEthernetNetworksViewModel;
    private ConnectivityManager mConnectivityManager;

    private final List<Button> mButtons = new ArrayList<>();
    private final List<EditText> mEditTexts = new ArrayList<>();

    private EditText mAllowedPackageNames;
    private EditText mIpConfiguration;
    private EditText mInterfaceName; // used in updateConfiguration
    private EditText mNetworkCapabilities;
    private EditText mInterfaceName2; // used in connect/enable/disable

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAllowedPackageNames = findViewById(R.id.allowedPackageNamesInput);
        mIpConfiguration = findViewById(R.id.ipConfigurationInput);
        mInterfaceName = findViewById(R.id.interfaceNameInput);
        mNetworkCapabilities = findViewById(R.id.networkCapabilitiesInput);
        Button updateButton = findViewById(R.id.updateButton);
        updateButton.setOnClickListener(v -> onUpdateButtonClick());

        mInterfaceName2 = findViewById(R.id.interfaceNameInput2);
        Button connectButton = findViewById(R.id.connectButton);
        connectButton.setOnClickListener(v -> onConnectButtonClick());
        Button enableButton = findViewById(R.id.enableButton);
        enableButton.setOnClickListener(v -> onEnableButtonClick());
        Button disableButton = findViewById(R.id.disableButton);
        disableButton.setOnClickListener(v -> onDisableButtonClick());

        mButtons.add(updateButton);
        mButtons.add(connectButton);
        mButtons.add(enableButton);
        mButtons.add(disableButton);

        mEditTexts.add(mAllowedPackageNames);
        mEditTexts.add(mIpConfiguration);
        mEditTexts.add(mInterfaceName);
        mEditTexts.add(mNetworkCapabilities);
        mEditTexts.add(mInterfaceName2);

        TextView currentEthernetNetworksOutput = findViewById(R.id.currentEthernetNetworksOutput);

        mCurrentEthernetNetworksViewModel =
                new ViewModelProvider(this,
                        new CurrentEthernetNetworksViewModelFactory(
                                (Application) getApplicationContext(),
                                new CurrentEthernetNetworksCallback()))
                        .get(CurrentEthernetNetworksViewModel.class);
        mCurrentEthernetNetworksViewModel.getNetworksLiveData().observe(this,
                networkNetworkInfoMap -> currentEthernetNetworksOutput
                        .setText(mCurrentEthernetNetworksViewModel
                                .getCurrentEthernetNetworksText(networkNetworkInfoMap)));

        OutcomeReceiver<String, EthernetNetworkManagementException> outcomeReceiver =
                new OutcomeReceiver<String, EthernetNetworkManagementException>() {
                    @Override
                    public void onResult(String result) {
                        showOperationResultDialog(result, /* isSuccess= */ true);
                    }

                    @Override
                    public void onError(EthernetNetworkManagementException error) {
                        OutcomeReceiver.super.onError(error);
                        showOperationResultDialog(error.getLocalizedMessage(),
                                /* isSuccess= */ false);
                    }
                };

        mConfigurationUpdater = new ConfigurationUpdater(getApplicationContext(), outcomeReceiver);
        mInterfaceEnabler = new InterfaceEnabler(getApplicationContext(), outcomeReceiver);
        mConnectivityManager = getSystemService(ConnectivityManager.class);
    }

    private void setButtonsAndEditTextsEnabled(boolean isEnabled) {
        for (Button button : mButtons) {
            button.setEnabled(isEnabled);
        }

        for (EditText editText : mEditTexts) {
            editText.setEnabled(isEnabled);
        }
    }

    private void showOperationResultDialog(String message, boolean isSuccess) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isSuccess ? R.string.success : R.string.error)
                .setMessage(message)
                .setOnDismissListener(d -> setButtonsAndEditTextsEnabled(true))
                .create();
        dialog.show();
    }

    private String getUpdateConfigurationIface() {
        return mInterfaceName.getText().toString();
    }

    private String getEnableDisableConnectIface() {
        return mInterfaceName2.getText().toString();
    }

    private void onUpdateButtonClick() {
        validateInterfaceName(getUpdateConfigurationIface());
        setButtonsAndEditTextsEnabled(false);
        Log.d(TAG, "configuration update started");
        try {
            mConfigurationUpdater.updateNetworkConfiguration(
                    mAllowedPackageNames.getText().toString(),
                    mIpConfiguration.getText().toString(),
                    mNetworkCapabilities.getText().toString(),
                    getUpdateConfigurationIface());
        } catch (IllegalStateException | IllegalArgumentException
                | PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Error updating: " + e.getMessage());
            showOperationResultDialog(e.getLocalizedMessage(), /* isSuccess= */ false);
        }
    }

    private void onConnectButtonClick() {
        setButtonsAndEditTextsEnabled(false);
        Log.d(TAG, "connect interface started");

        try {
            validateInterfaceName(getEnableDisableConnectIface());
            NetworkRequest request =
                    new NetworkRequest.Builder()
                            .clearCapabilities()
                            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                            .setNetworkSpecifier(
                                    new EthernetNetworkSpecifier(getEnableDisableConnectIface()))
                            .build();
            mConnectivityManager.requestNetwork(request,
                    new InterfaceConnectorCallback(),
                    new Handler(Looper.getMainLooper()),
                    Math.toIntExact(REQUEST_NETWORK_TIMEOUT.toMillis()));
        } catch (SecurityException | IllegalArgumentException e) {
            showOperationResultDialog(e.getLocalizedMessage(), /* isSuccess= */ false);
        }
    }

    private void onEnableButtonClick() {
        validateInterfaceName(getEnableDisableConnectIface());
        setButtonsAndEditTextsEnabled(false);
        Log.d(TAG, "enable interface started");
        mInterfaceEnabler.enableInterface(getEnableDisableConnectIface());
    }

    private void onDisableButtonClick() {
        validateInterfaceName(getEnableDisableConnectIface());
        setButtonsAndEditTextsEnabled(false);
        Log.d(TAG, "disable interface started");
        mInterfaceEnabler.disableInterface(getEnableDisableConnectIface());
    }

    private void validateInterfaceName(String iface) {
        if (Strings.isNullOrEmpty(iface)) {
            throw new IllegalArgumentException("Interface Name can't be empty.");
        }
    }

    private class InterfaceConnectorCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            super.onAvailable(network);

            try (Socket socket = new Socket()) {
                network.bindSocket(socket);
            } catch (IOException e) {
                showOperationResultDialog(e.getLocalizedMessage(), /* isSuccess= */ false);
                return;
            } finally {
                mConnectivityManager.unregisterNetworkCallback(this);
            }

            showOperationResultDialog(
                    mConnectivityManager.getLinkProperties(network).getInterfaceName(),
                    /* isSuccess= */ true);
        }

        @Override
        public void onUnavailable() {
            super.onUnavailable();
            mConnectivityManager.unregisterNetworkCallback(this);

            showOperationResultDialog(mInterfaceName2.getText().toString() + " is not available",
                    /* isSuccess= */ false);
        }
    }

    private class CurrentEthernetNetworksCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            super.onAvailable(network);
            Log.d(TAG, "Network " + network + " available");

            runOnUiThread(() -> mCurrentEthernetNetworksViewModel.addNetwork(network));
        }

        @Override
        public void onLost(Network network) {
            super.onLost(network);
            Log.d(TAG, "Network " + network + " lost");

            runOnUiThread(() -> mCurrentEthernetNetworksViewModel.removeNetwork(network));
        }

        @Override
        public void onCapabilitiesChanged(Network network,
                NetworkCapabilities networkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);
            Log.d(TAG, "Network " + network + " capabilities changed to "
                    + Arrays.toString(networkCapabilities.getCapabilities()));

            runOnUiThread(() -> mCurrentEthernetNetworksViewModel.onNetworkChanged());
        }

        @Override
        public void onLinkPropertiesChanged(Network network,
                LinkProperties linkProperties) {
            super.onLinkPropertiesChanged(network, linkProperties);
            Log.d(TAG, "Network " + network + " link properties changed");

            runOnUiThread(
                    () -> mCurrentEthernetNetworksViewModel.onNetworkChanged());
        }
    }

    private static class CurrentEthernetNetworksViewModelFactory extends
            ViewModelProvider.AndroidViewModelFactory {
        private final Application mApplication;
        private final ConnectivityManager.NetworkCallback mNetworkCallback;

        private CurrentEthernetNetworksViewModelFactory(
                Application application, ConnectivityManager.NetworkCallback networkCallback) {
            super(application);
            mApplication = application;
            mNetworkCallback = networkCallback;
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(Class<T> modelClass) {
            return modelClass.cast(
                    new CurrentEthernetNetworksViewModel(mApplication, mNetworkCallback));
        }
    }

}
