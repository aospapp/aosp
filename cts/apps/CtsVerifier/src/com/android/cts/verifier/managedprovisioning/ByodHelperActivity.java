/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.cts.verifier.managedprovisioning;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.android.cts.verifier.R;
import com.android.cts.verifier.managedprovisioning.ByodFlowTestActivity.TestResult;

/**
 * A helper activity from the managed profile side that responds to requests from CTS verifier in
 * primary user. Profile owner APIs are accessible inside this activity (given this activity is
 * started within the work profile). Its current functionalities include making sure the profile
 * owner is setup correctly, and removing the work profile upon request.
 *
 * Note: We have to use a dummy activity because cross-profile intents only work for activities.
 */
public class ByodHelperActivity extends Activity {
    static final String TAG = "ByodHelperActivity";

    // Primary -> managed intent: query if the profile owner has been set up.
    public static final String ACTION_QUERY_PROFILE_OWNER = "com.android.cts.verifier.managedprovisioning.BYOD_QUERY";
    // Managed -> primary intent: update profile owner test status in primary's CtsVerifer
    public static final String ACTION_PROFILE_OWNER_STATUS = "com.android.cts.verifier.managedprovisioning.BYOD_STATUS";
    // Primary -> managed intent: request to delete the current profile
    public static final String ACTION_REMOVE_PROFILE_OWNER = "com.android.cts.verifier.managedprovisioning.BYOD_REMOVE";
    // Managed -> managed intent: provisioning completed successfully
    public static final String ACTION_PROFILE_PROVISIONED = "com.android.cts.verifier.managedprovisioning.BYOD_PROVISIONED";

    public static final String EXTRA_PROVISIONED = "extra_provisioned";

    private ComponentName mAdminReceiverComponent;

    private DevicePolicyManager mDevicePolicyManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAdminReceiverComponent = new ComponentName(this, DeviceAdminTestReceiver.class.getName());
        mDevicePolicyManager = (DevicePolicyManager) getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        String action = getIntent().getAction();
        Log.d(TAG, "ByodHelperActivity.onCreate: " + action);

        // we are explicitly started by {@link DeviceAdminTestReceiver} after a successful provisioning.
        if (action.equals(ACTION_PROFILE_PROVISIONED)) {
            // Jump back to CTS verifier with result.
            Intent response = new Intent(ACTION_PROFILE_OWNER_STATUS);
            response.putExtra(EXTRA_PROVISIONED, isProfileOwner());
            startActivityInPrimary(response);
            // Queried by CtsVerifier in the primary side using startActivityForResult.
        } else if (action.equals(ACTION_QUERY_PROFILE_OWNER)) {
            Intent response = new Intent();
            response.putExtra(EXTRA_PROVISIONED, isProfileOwner());
            setResult(RESULT_OK, response);
            // Request to delete work profile.
        } else if (action.equals(ACTION_REMOVE_PROFILE_OWNER)) {
            if (isProfileOwner()) {
                mDevicePolicyManager.wipeData(0);
                showToast(R.string.provisioning_byod_profile_deleted);
            }
        }
        // This activity has no UI and is only used to respond to CtsVerifier in the primary side.
        finish();
    }

    private boolean isProfileOwner() {
        return mDevicePolicyManager.isAdminActive(mAdminReceiverComponent) &&
                mDevicePolicyManager.isProfileOwnerApp(mAdminReceiverComponent.getPackageName());
    }

    private void startActivityInPrimary(Intent intent) {
        // Disable app components in the current profile, so only the counterpart in the other
        // profile can respond (via cross-profile intent filter)
        getPackageManager().setComponentEnabledSetting(new ComponentName(
                this, ByodFlowTestActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        startActivity(intent);
    }

    private void showToast(int messageId) {
        String message = getString(messageId);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
