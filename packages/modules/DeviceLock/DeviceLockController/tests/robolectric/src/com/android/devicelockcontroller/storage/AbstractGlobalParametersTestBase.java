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

import com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState;

abstract class AbstractGlobalParametersTestBase {
    static final String ALLOWLIST_PACKAGE_0 = "allowlist.package.0";
    static final String ALLOWLIST_PACKAGE_1 = "allowlist.package.1";

    static final String KIOSK_SIGNING_CERT = "signing_certificate";
    static final boolean NEED_CHECK_IN = false;
    static final String REGISTERED_DEVICE_ID = "test_id";
    static final boolean FORCED_PROVISION = true;
    static final String ENROLLMENT_TOKEN = "test_enrollment_token";

    @DeviceProvisionState
    static final int LAST_RECEIVED_PROVISION_STATE = DeviceProvisionState.PROVISION_STATE_RETRY;
}
