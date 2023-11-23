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

/**
 * Class to represent a stylus as a [UInputDevice]. This can then be used to register a stylus using
 * [android.platform.test.rule.InputDeviceRule].
 *
 * @param supportedKeys should be keycodes obtained from the linux event code set. See
 *   https://source.corp.google.com/kernel-upstream/include/uapi/linux/input-event-codes.h
 */
class UInputStylus(
    override val vendorId: Int = ARBITRARY_VENDOR_ID,
    override val productId: Int = ARBITRARY_PRODUCT_ID,
    override val name: String = "Test Stylus With Buttons (BT)",
    override val supportedKeys: Array<Int> = SUPPORTED_KEYS,
) : UInputDevice() {
    // Based on cts/tests/input/res/raw/test_bluetooth_stylus_register.json
    override val bus = "bluetooth"

    companion object {
        // UI_SET_KEYBIT : BTN_STYLUS, BTN_STYLUS2, BTN_STYLUS3
        val SUPPORTED_KEYS = arrayOf(331, 332, 329)

        const val ARBITRARY_VENDOR_ID = 0x18d1
        const val ARBITRARY_PRODUCT_ID = 0xabcd
    }
}
