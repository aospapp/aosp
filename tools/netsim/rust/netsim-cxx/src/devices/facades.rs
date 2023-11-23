// Copyright 2023 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use frontend_proto::model::chip::Bluetooth as ProtoBluetoothChip;
use frontend_proto::model::chip::Radio as ProtoRadioChip;

use super::device::DeviceIdentifier;

pub type FacadeIdentifier = i32;

pub fn hci_get(facade_id: FacadeIdentifier) -> ProtoBluetoothChip {
    println!("hci_get({})", facade_id);
    ProtoBluetoothChip::new()
}

pub fn wifi_get(facade_id: FacadeIdentifier) -> ProtoRadioChip {
    println!("wifi_get({})", facade_id);
    ProtoRadioChip::new()
}

pub fn hci_remove(facade_id: FacadeIdentifier) {
    println!("hci_remove({})", facade_id);
}

pub fn wifi_remove(facade_id: FacadeIdentifier) {
    println!("wifi_remove({})", facade_id);
}

pub fn hci_add(_device_id: DeviceIdentifier) -> FacadeIdentifier {
    println!("hci_add()");
    0
}

pub fn wifi_add(_device_id: DeviceIdentifier) -> FacadeIdentifier {
    println!("wifi_add()");
    0
}

pub fn hci_patch(_facade_id: FacadeIdentifier, _patch: &ProtoBluetoothChip) {
    println!("hci_patch()");
}

pub fn wifi_patch(_facade_id: FacadeIdentifier, _patch: &ProtoRadioChip) {
    println!("wifi_patch()");
}

pub fn hci_reset(_facade_id: FacadeIdentifier) {
    println!("bt_reset()");
}

pub fn wifi_reset(_facade_id: FacadeIdentifier) {
    println!("wifi_reset()");
}
