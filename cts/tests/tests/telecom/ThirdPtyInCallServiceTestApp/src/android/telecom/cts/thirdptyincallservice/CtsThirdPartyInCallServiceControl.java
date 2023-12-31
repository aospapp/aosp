/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telecom.cts.thirdptyincallservice;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class CtsThirdPartyInCallServiceControl extends Service {

    private static final String TAG = CtsThirdPartyInCallServiceControl.class.getSimpleName();
    public static final String CONTROL_INTERFACE_ACTION =
            "android.telecom.cts.thirdptyincallservice.ACTION_THIRDPTY_CTRL";

    private final IBinder mCtsCompanionAppControl = new ICtsThirdPartyInCallServiceControl.Stub() {

        @Override
        public boolean checkBindStatus(boolean bind) {
            return CtsThirdPartyInCallService.checkBindStatus(bind);
        }

        @Override
        public int getLocalCallCount() {
            return CtsThirdPartyInCallService.getLocalCallCount();
        }

        @Override
        public void resetLatchForServiceBound(boolean bind) {
            CtsThirdPartyInCallService.resetLatchForServiceBound(bind);
        }

        @Override
        public void resetCalls() {
            CtsThirdPartyInCallService.resetCalls();
        }

        @Override
        public boolean checkPermissionGrant(String permission) {
            return getPackageManager().checkPermission(permission
                    , getApplication().getPackageName()) == PERMISSION_GRANTED;
        }

        @Override
        public void setExpectedExtra(String newKey, String newValue) {
            CtsThirdPartyInCallService.getInstance().setExpectedExtra(newKey, newValue);
        }

        @Override
        public boolean waitUntilExpectedExtrasReceived() {
            return CtsThirdPartyInCallService.getInstance().waitForExtrasChanged();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        if (CONTROL_INTERFACE_ACTION.equals(intent.getAction())) {
            Log.d(TAG, "onBind: return control interface.");
            return mCtsCompanionAppControl;
        }
        Log.d(TAG, "onBind: invalid intent.");
        return null;
    }

}
