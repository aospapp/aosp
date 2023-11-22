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
package com.android.tradefed.invoker.shard.token;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.helper.TelephonyHelper;
import com.android.tradefed.device.helper.TelephonyHelper.SimCardInformation;
import com.android.tradefed.log.LogUtil.CLog;

/** Token provider for telephony related tokens. */
public class TelephonyTokenProvider implements ITokenProvider {

    @Override
    public boolean hasToken(ITestDevice device, TokenProperty token) {
        if (device.getIDevice() instanceof StubDevice) {
            return false;
        }
        try {
            SimCardInformation info = TelephonyHelper.getSimInfo(device);
            if (info == null || !info.mHasTelephonySupport) {
                CLog.e("SimcardInfo: %s", info);
                return false;
            }
            switch (token) {
                case SIM_CARD:
                    // 5 is sim state ready
                    if ("5".equals(info.mSimState)) {
                        return true;
                    }
                    CLog.w(
                            "%s cannot run with token '%s' - Sim info: %s",
                            device.getSerialNumber(), token, info);
                    return false;
                case UICC_SIM_CARD:
                    if (info.mCarrierPrivileges) {
                        return true;
                    }
                    CLog.w(
                            "%s cannot run with token '%s' - Sim info: %s",
                            device.getSerialNumber(), token, info);
                    return false;
                case SECURE_ELEMENT_SIM_CARD:
                    if (info.mHasSecuredElement && info.mHasSeService) {
                        return true;
                    }
                    CLog.w(
                            "%s cannot run with token '%s' - Sim info: %s",
                            device.getSerialNumber(), token, info);
                    return false;
                default:
                    CLog.w("Token '%s' doesn't match any TelephonyTokenProvider tokens.", token);
                    return false;
            }
        } catch (DeviceNotAvailableException e) {
            CLog.e("Ignoring DNAE: %s", e);
        }
        return false;
    }
}
