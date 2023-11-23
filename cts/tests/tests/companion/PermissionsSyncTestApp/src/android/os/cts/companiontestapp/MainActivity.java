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

package android.os.cts.companiontestapp;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.CompanionException;
import android.companion.cts.permissionssynctestapp.R;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.OutcomeReceiver;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.UUID;

public class MainActivity extends Activity {
    static final String TAG = "MainActivity";

    // Name for the SDP record when creating server socket
    private static final String SERVICE_NAME = "CDMPermissionsSyncBluetoothSecure";

    // Unique UUID for this application
    private static final UUID SERVICE_UUID =
            UUID.fromString("7606c653-6dc3-4a61-9870-07652896cc1c");

    private BluetoothAdapter mAdapter;
    private BluetoothServerThread mServerThread;
    private BluetoothDevice mClientDevice;

    private CompanionDeviceManager mCompanionDeviceManager;
    private int mAssociationId;

    private static final int REQUEST_CODE_DISCOVERABLE = 100;
    private static final int REQUEST_CODE_ASSOCIATE = 101;
    private static final int REQUEST_CODE_SYNC = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        mAdapter = getSystemService(BluetoothManager.class).getAdapter();

        mCompanionDeviceManager = getSystemService(CompanionDeviceManager.class);

        requireViewById(R.id.associateButton).setOnClickListener(v -> {
            mCompanionDeviceManager.associate(
                    new AssociationRequest.Builder()
                            .setDisplayName("Test Device")
                            .build(),
                    getMainExecutor(), mCallback);
        });
        requireViewById(R.id.disassociateButton).setOnClickListener(v -> {
            mCompanionDeviceManager.disassociate(mAssociationId);
        });

        requireViewById(R.id.beginPermissionsSyncButton).setOnClickListener(v -> {
            try {
                final IntentSender intentSender = mCompanionDeviceManager
                        .buildPermissionTransferUserConsentIntent(mAssociationId);
                startIntentSenderForResult(intentSender, REQUEST_CODE_SYNC, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                throw new RuntimeException(e);
            }
        });

        requireViewById(R.id.startAdvertisingButton).setOnClickListener(v -> {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE),
                    REQUEST_CODE_DISCOVERABLE);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        bindService(new Intent(this, PermissionsTransferCompanionService.class),
                mServiceConnection, Context.BIND_AUTO_CREATE);

        mServerThread = new BluetoothServerThread();
        mServerThread.start();
    }

    @Override
    protected void onStop() {
        super.onStop();

        mServerThread.shutdown();
        mServerThread = null;

        unbindService(mServiceConnection);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.v(TAG, "onActivityResult() request=" + requestCode + " result=" + resultCode);

        switch (requestCode) {
            case REQUEST_CODE_SYNC:
                if (resultCode == Activity.RESULT_OK) {
                    try {
                        final BluetoothSocket socket = mClientDevice
                                .createRfcommSocketToServiceRecord(SERVICE_UUID);
                        socket.connect();
                        Log.v(TAG, "Attaching client socket " + socket);
                        PermissionsTransferCompanionService.sInstance.attachSystemDataTransport(
                                mAssociationId, socket.getInputStream(), socket.getOutputStream());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    mCompanionDeviceManager.startSystemDataTransfer(mAssociationId,
                            getMainExecutor(), new OutcomeReceiver<Void, CompanionException>() {
                                @Override
                                public void onResult(Void result) {
                                    Log.v(TAG, "Success!");
                                    PermissionsTransferCompanionService.sInstance
                                            .detachSystemDataTransport(mAssociationId);
                                }

                                @Override
                                public void onError(CompanionException error) {
                                    Log.v(TAG, "Failure!", error);
                                    PermissionsTransferCompanionService.sInstance
                                            .detachSystemDataTransport(mAssociationId);
                                }
                            });
                }
                break;
        }
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // ignored
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // ignored
        }
    };

    private CompanionDeviceManager.Callback mCallback = new CompanionDeviceManager.Callback() {
        @Override
        public void onAssociationPending(@NonNull IntentSender intentSender) {
            Log.v(TAG, "onAssociationPending " + intentSender);

            try {
                startIntentSenderForResult(intentSender, REQUEST_CODE_ASSOCIATE, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onAssociationCreated(@NonNull AssociationInfo associationInfo) {
            Log.v(TAG, "onAssociationCreated " + associationInfo);

            mAssociationId = associationInfo.getId();
            mClientDevice = mAdapter.getRemoteDevice(
                    associationInfo.getDeviceMacAddress().toString().toUpperCase());
        }

        @Override
        public void onFailure(@Nullable CharSequence error) {
            throw new RuntimeException(error.toString());
        }
    };

    private class BluetoothServerThread extends Thread {
        private BluetoothServerSocket mServerSocket;

        @Override
        public void run() {
            try {
                Log.v(TAG, "Listening for remote connections...");
                mServerSocket = mAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME,
                        SERVICE_UUID);
                while (true) {
                    final BluetoothSocket socket = mServerSocket.accept();
                    Log.v(TAG, "Attaching server socket " + socket);
                    PermissionsTransferCompanionService.sInstance.attachSystemDataTransport(
                            mAssociationId, socket.getInputStream(), socket.getOutputStream());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public void shutdown() {
            if (mServerSocket != null) {
                try {
                    mServerSocket.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
