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

package com.android.managedprovisioning.provisioning;

/**
 * Callback intended to be used with {@link ResetDeviceActivity}.
 *
 * <p>It is the responsibility of the {@link ResetDeviceActivityBridge} implementation
 * to call through the callback.
 */
public interface ResetDeviceActivityBridgeCallback {
    /**
     * Callback invoked when the reset button has been clicked.
     */
    void onResetButtonClicked();
}
