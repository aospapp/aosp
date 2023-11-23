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
namespace ti {
namespace beagle_x15 {

class BeagleX15UI : public ::ScreenRecoveryUI
{
    RecoveryUI::KeyAction CheckKey(int key, bool is_long_press) {
        // Use "Home" key (called USER5 on AM57x EVM) to select the item,
        // because it's impossible to reproduce long press, and we can't use
        // "Power" button as a chord for toggle
        if (key == KEY_HOME) {
            return RecoveryUI::TOGGLE;
        }

        return RecoveryUI::CheckKey(key, is_long_press);
    }
};

} // namespace beagle_x15
} // namespace ti
} // namespace device
} // namespace android

Device *make_device()
{
    return new Device(new ::android::device::ti::beagle_x15::BeagleX15UI());
}
