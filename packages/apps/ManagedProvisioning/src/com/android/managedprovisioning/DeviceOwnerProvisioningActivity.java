/*
 * Copyright 2014, The Android Open Source Project
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

package com.android.managedprovisioning;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.SystemProperties;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.android.managedprovisioning.task.AddWifiNetworkTask;

import java.util.Locale;

/**
 * This activity starts device owner provisioning:
 * It downloads a mobile device management application(mdm) from a given url and installs it,
 * or a given mdm is already present on the device. The mdm is set as the owner of the device so
 * that it has full control over the device:
 * TODO: put link here with documentation on how a device owner has control over the device
 * The mdm can then execute further setup steps.
 *
 * <p>
 * An example use case might be when a company wants to set up a device for a single use case
 * (such as giving instructions).
 * </p>
 *
 * <p>
 * Provisioning is triggered by a programmer device that sends required provisioning parameters via
 * nfc. For an example of a programmer app see:
 * com.example.android.apis.app.DeviceProvisioningProgrammerSample.
 * </p>
 *
 * <p>
 * In the unlikely case that this activity is killed the whole provisioning process so far is
 * repeated. We made sure that all tasks can be done twice without causing any problems.
 * </p>
 */
public class DeviceOwnerProvisioningActivity extends Activity {
    private static final String KEY_USER_CONSENTED = "user_consented";

    private static final int ENCRYPT_DEVICE_REQUEST_CODE = 1;
    private static final int WIFI_REQUEST_CODE = 2;

    private BroadcastReceiver mServiceMessageReceiver;
    private TextView mProgressTextView;
    private Dialog mDialog; // The cancel or error dialog that is currently shown.
    private boolean mDone; // Indicates whether the service has sent ACTION_PROVISIONING_SUCCESS.

    // Run when wifi picker activity reports success.
    private Runnable mOnWifiConnectedRunnable;

    // Indicates whether user consented by clicking on positive button of interstitial.
    private boolean mUserConsented = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mUserConsented = savedInstanceState.getBoolean(KEY_USER_CONSENTED, false);
        }

        ProvisionLogger.logd("Device owner provisioning activity ONCREATE");

        // Check whether we can provision.
        if (Global.getInt(getContentResolver(), Global.DEVICE_PROVISIONED, 0 /* default */) != 0) {
            ProvisionLogger.loge("Device already provisioned.");
            error(R.string.device_owner_error_already_provisioned, false /* no factory reset */);
            return;
        }

        if (UserHandle.myUserId() != UserHandle.USER_OWNER) {
            ProvisionLogger.loge("Device owner can only be set up for USER_OWNER.");
            error(R.string.device_owner_error_general, false /* no factory reset */);
            return;
        }

        // Setup the UI.
        final LayoutInflater inflater = getLayoutInflater();
        final View contentView = inflater.inflate(R.layout.progress, null);
        setContentView(contentView);
        mProgressTextView = (TextView) findViewById(R.id.prog_text);
        TextView titleText = (TextView) findViewById(R.id.title);
        if (titleText != null) titleText.setText(getString(R.string.setup_device));

        // Setup broadcast receiver for feedback from service.
        mServiceMessageReceiver = new ServiceMessageReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(DeviceOwnerProvisioningService.ACTION_PROVISIONING_SUCCESS);
        filter.addAction(DeviceOwnerProvisioningService.ACTION_PROVISIONING_ERROR);
        filter.addAction(DeviceOwnerProvisioningService.ACTION_PROGRESS_UPDATE);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceMessageReceiver, filter);

        // Parse the incoming intent.
        final ProvisioningParams params;
        MessageParser parser = new MessageParser();
        try {
            params = parser.parseIntent(getIntent());
            mOnWifiConnectedRunnable = new Runnable() {
                public void run() {
                    showInterstitialAndProvision(params);
                }
            };
        } catch (MessageParser.ParseException e) {
            ProvisionLogger.loge("Could not read data from intent", e);
            error(e.getErrorMessageId(), false /* no factory reset */);
            return;
        }

        // Ask to encrypt the device before proceeding
        if (!(EncryptDeviceActivity.isDeviceEncrypted()
                        || SystemProperties.getBoolean("persist.sys.no_req_encrypt", false))) {
            requestEncryption(parser, params);
            finish();
            return;
            // System will reboot. Bootreminder will restart this activity.
        }

        // Have the user pick a wifi network if necessary.
        if (!AddWifiNetworkTask.isConnectedToWifi(this) && TextUtils.isEmpty(params.mWifiSsid)) {
            requestWifiPick();
            return;
            // Wait for onActivityResult.
        }

        showInterstitialAndProvision(params);
    }

    private void showInterstitialAndProvision(final ProvisioningParams params) {
        if (mUserConsented || params.mStartedByNfc) {
            startDeviceOwnerProvisioningService(params);
        } else {
            // Notify the user that the admin will have full control over the device,
            // then start provisioning.
            new UserConsentDialog(this, UserConsentDialog.DEVICE_OWNER, new Runnable() {
                    @Override
                    public void run() {
                        mUserConsented = true;
                        startDeviceOwnerProvisioningService(params);
                    }
                } /* onUserConsented */ , new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                } /* onCancel */).show(getFragmentManager(),
                        "UserConsentDialogFragment");
        }
    }

    private void startDeviceOwnerProvisioningService(ProvisioningParams params) {
        Intent intent = new Intent(this, DeviceOwnerProvisioningService.class);
        intent.putExtra(DeviceOwnerProvisioningService.EXTRA_PROVISIONING_PARAMS, params);
        intent.putExtras(getIntent());
        startService(intent);
    }

    class ServiceMessageReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if (action.equals(DeviceOwnerProvisioningService.ACTION_PROVISIONING_SUCCESS)) {
                ProvisionLogger.logd("Successfully provisioned");

                synchronized(this) {
                    if (mDialog == null) {
                        onProvisioningSuccess();
                    } else {
                        // Postpone finishing this activity till the user has decided whether
                        // he/she wants to reset or not.
                        mDone = true;
                    }
                }
                return;
            } else if (action.equals(DeviceOwnerProvisioningService.ACTION_PROVISIONING_ERROR)) {
                int errorMessageId = intent.getIntExtra(
                        DeviceOwnerProvisioningService.EXTRA_USER_VISIBLE_ERROR_ID_KEY,
                        R.string.device_owner_error_general);

                ProvisionLogger.logd("Error reported with code "
                        + getResources().getString(errorMessageId));
                error(errorMessageId, true /* always factory reset */);
            } else if (action.equals(DeviceOwnerProvisioningService.ACTION_PROGRESS_UPDATE)) {
                int progressMessage = intent.getIntExtra(
                        DeviceOwnerProvisioningService.EXTRA_PROGRESS_MESSAGE_ID_KEY, -1);
                ProvisionLogger.logd("Progress update reported with code "
                        + getResources().getString(progressMessage));
                if (progressMessage >= 0) {
                    progressUpdate(progressMessage);
                }
            }
        }
    }

    private void onProvisioningSuccess() {
        // The Setup wizards listens to this flag and finishes itself when it is set.
        // It then fires a home intent, which we catch in the HomeReceiverActivity before sending
        // the intent to notify the mdm that provisioning is complete.
        Global.putInt(getContentResolver(), Global.DEVICE_PROVISIONED, 1);
        Secure.putInt(getContentResolver(), Secure.USER_SETUP_COMPLETE, 1);

        // Note: the DeviceOwnerProvisioningService will stop itself.
        setResult(Activity.RESULT_OK);
        finish();
    }

    private void requestEncryption(MessageParser messageParser, ProvisioningParams params) {
        Intent encryptIntent = new Intent(DeviceOwnerProvisioningActivity.this,
                EncryptDeviceActivity.class);

        Bundle resumeExtras = new Bundle();
        resumeExtras.putString(EncryptDeviceActivity.EXTRA_RESUME_TARGET,
                EncryptDeviceActivity.TARGET_DEVICE_OWNER);
        messageParser.addProvisioningParamsToBundle(resumeExtras, params);

        encryptIntent.putExtra(EncryptDeviceActivity.EXTRA_RESUME, resumeExtras);

        startActivityForResult(encryptIntent, ENCRYPT_DEVICE_REQUEST_CODE);
    }

    private void requestWifiPick() {
        startActivityForResult(AddWifiNetworkTask.getWifiPickIntent(), WIFI_REQUEST_CODE);
    }

    @Override
    public void onBackPressed() {
        showCancelResetDialog();
    }

    private void showCancelResetDialog() {
        AlertDialog.Builder alertBuilder =
                new AlertDialog.Builder(DeviceOwnerProvisioningActivity.this)
                        .setCancelable(false)
                        .setTitle(R.string.device_owner_cancel_title)
                        .setMessage(R.string.device_owner_cancel_message)
                        .setNegativeButton(R.string.device_owner_cancel_cancel,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog,int id) {
                                        dialog.dismiss();
                                        synchronized(this) {
                                            mDialog = null;
                                            if (mDone) {
                                                onProvisioningSuccess();
                                            }
                                        }
                                    }
                        })
                        .setPositiveButton(R.string.device_owner_error_reset,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog,int id) {
                                        // Factory reset the device.
                                        Intent intent = new Intent(Intent.ACTION_MASTER_CLEAR);
                                        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                                        intent.putExtra(Intent.EXTRA_REASON,
                                                "DeviceOwnerProvisioningActivity.showCancelResetDialog()");
                                        sendBroadcast(intent);
                                        stopService(new Intent(DeviceOwnerProvisioningActivity.this,
                                                DeviceOwnerProvisioningService.class));
                                        finish();
                                    }
                        });

        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = alertBuilder.create();
        mDialog.show();
    }

    private void progressUpdate(int progressMessage) {
        mProgressTextView.setText(progressMessage);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ENCRYPT_DEVICE_REQUEST_CODE) {
            if (resultCode == RESULT_CANCELED) {
                ProvisionLogger.loge("User canceled device encryption.");
                finish();
            }
        } else if (requestCode == WIFI_REQUEST_CODE) {
            if (resultCode == RESULT_CANCELED) {
                ProvisionLogger.loge("User canceled wifi picking.");
                stopService(new Intent(DeviceOwnerProvisioningActivity.this,
                                DeviceOwnerProvisioningService.class));
                finish();
            } else if (resultCode == RESULT_OK) {
                ProvisionLogger.logd("Wifi request result is OK");
                if (AddWifiNetworkTask.isConnectedToWifi(this)) {
                    mOnWifiConnectedRunnable.run();
                } else {
                    requestWifiPick();
                }
            }
        }
    }

    private void error(int dialogMessage, boolean resetRequired) {
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this)
                .setTitle(R.string.provisioning_error_title)
                .setMessage(dialogMessage)
                .setCancelable(false);
        if (resetRequired) {
            alertBuilder.setPositiveButton(R.string.device_owner_error_reset,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog,int id) {
                            // Factory reset the device.
                            Intent intent = new Intent(Intent.ACTION_MASTER_CLEAR);
                            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                            intent.putExtra(Intent.EXTRA_REASON,
                                    "DeviceOwnerProvisioningActivity.error()");
                            sendBroadcast(intent);
                            stopService(new Intent(DeviceOwnerProvisioningActivity.this,
                                            DeviceOwnerProvisioningService.class));
                            finish();
                        }
                    });
        } else {
            alertBuilder.setPositiveButton(R.string.device_owner_error_ok,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog,int id) {
                            // Close activity.
                            stopService(new Intent(DeviceOwnerProvisioningActivity.this,
                                            DeviceOwnerProvisioningService.class));
                            finish();
                        }
                    });
        }
        mDialog = alertBuilder.create();
        mDialog.show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(KEY_USER_CONSENTED, mUserConsented);
    }

    @Override
    public void onDestroy() {
        ProvisionLogger.logd("Device owner provisioning activity ONDESTROY");
        if (mServiceMessageReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceMessageReceiver);
            mServiceMessageReceiver = null;
        }
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onRestart() {
        ProvisionLogger.logd("Device owner provisioning activity ONRESTART");
        super.onRestart();
    }

    @Override
    protected void onResume() {
        ProvisionLogger.logd("Device owner provisioning activity ONRESUME");
        super.onResume();
    }

    @Override
    protected void onPause() {
        ProvisionLogger.logd("Device owner provisioning activity ONPAUSE");
        super.onPause();
    }

    @Override
    protected void onStop() {
        ProvisionLogger.logd("Device owner provisioning activity ONSTOP");
        super.onStop();
    }
}

