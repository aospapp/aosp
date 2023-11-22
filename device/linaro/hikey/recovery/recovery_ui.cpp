/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <recovery_ui/device.h>
#include <recovery_ui/screen_ui.h>

namespace android {
namespace device {
namespace linaro {
namespace hikey {

class HikeyUI : public ::ScreenRecoveryUI
{
    RecoveryUI::KeyAction CheckKey(int key, bool is_long_press) {
        // Recovery core can't tolerate using KEY_POWER as an alias for
        // KEY_DOWN, and a reboot is always triggered. Remap any power
        // key press to KEY_DOWN to allow us to use the power key as
        // a regular key.
        if (key == KEY_POWER && !is_long_press) {
            RecoveryUI::EnqueueKey(KEY_DOWN);
            return RecoveryUI::IGNORE;
        }

        return RecoveryUI::CheckKey(key, is_long_press);
    }
};

} // namespace hikey
} // namespace linaro
} // namespace device
} // namespace android

Device *make_device()
{
    return new Device(new ::android::device::linaro::hikey::HikeyUI());
}
