/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.devicelockcontroller.storage;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState;
import com.android.devicelockcontroller.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * A class exposing Global Parameters as a service.
 */
public final class GlobalParametersService extends Service {
    private static final String TAG = "GlobalParametersService";

    private Context mContext;

    private final IGlobalParametersService.Stub mBinder =
            new IGlobalParametersService.Stub() {
                @Override
                public void clear() {
                    GlobalParameters.clear(mContext);
                }

                @Override
                public List<String> getLockTaskAllowlist() {
                    return GlobalParameters.getLockTaskAllowlist(mContext);
                }

                @Override
                public void setLockTaskAllowlist(List<String> allowlist) {
                    GlobalParameters.setLockTaskAllowlist(mContext, new ArrayList<>(allowlist));
                }

                @Override
                public boolean needCheckIn() {
                    return GlobalParameters.needCheckIn(mContext);
                }

                @Override
                public void setNeedCheckIn(boolean needCheckIn) {
                    GlobalParameters.setNeedCheckIn(mContext, needCheckIn);
                }

                @Override
                public String getRegisteredDeviceId() {
                    return GlobalParameters.getRegisteredDeviceId(mContext);
                }

                @Override
                public void setRegisteredDeviceId(String registeredDeviceId) {
                    GlobalParameters.setRegisteredDeviceId(mContext, registeredDeviceId);
                }

                @Override
                public boolean isProvisionForced() {
                    return GlobalParameters.isProvisionForced(mContext);
                }

                @Override
                public void setProvisionForced(boolean isForced) {
                    GlobalParameters.setProvisionForced(mContext, isForced);
                }

                @Override
                public String getEnrollmentToken() {
                    return GlobalParameters.getEnrollmentToken(mContext);
                }

                @Override
                public void setEnrollmentToken(String token) {
                    GlobalParameters.setEnrollmentToken(mContext, token);
                }

                @Override
                public String getKioskSignature() {
                    return GlobalParameters.getKioskSignature(mContext);
                }

                @Override
                public void setKioskSignature(String kioskSignature) {
                    GlobalParameters.setKioskSignature(mContext, kioskSignature);
                }

                @Override
                @DeviceProvisionState
                public int getLastReceivedProvisionState() {
                    return GlobalParameters.getLastReceivedProvisionState(mContext);
                }

                @Override
                public void setLastReceivedProvisionState(
                        @DeviceProvisionState int provisionState) {
                    GlobalParameters.setLastReceivedProvisionState(mContext, provisionState);
                }


            };

    @Override
    public void onCreate() {
        LogUtil.d(TAG, "onCreate");
        mContext = this;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
