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
package android.platform.helpers.uinput

import android.view.InputDevice

/**
 * Interface representing a Uinput device to be used in
 * [android.platform.test.rule.InputDeviceRule].
 */
abstract class UInputDevice {
    abstract val vendorId: Int
    abstract val productId: Int
    abstract val name: String
    abstract val bus: String
    abstract val supportedKeys: Array<Int>

    // Based on cts/tests/input/res/raw/test_bluetooth_stylus_register.json
    fun getRegisterCommand(): String {
        return """{
            "id": 1,
            "type": "uinput",
            "command": "register",
            "name": "$name",
            "vid": $vendorId,
            "pid": $productId,
            "bus": "$bus",
            "configuration":[
                {"type": 100, "data": [1]},
                {"type": 101, "data": ${supportedKeys.contentToString()}}
            ]
        }"""
            .trimIndent()
            .replace("\n", "")
    }

    fun isInputDevice(inputDevice: InputDevice): Boolean =
        this.name == inputDevice.name &&
            this.vendorId == inputDevice.vendorId &&
            this.productId == inputDevice.productId
}
