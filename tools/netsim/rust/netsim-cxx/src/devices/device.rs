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

// Device.rs

use protobuf::Message;

use crate::devices::chip;
use crate::devices::chip::Chip;
use crate::devices::chip::ChipIdentifier;
use crate::devices::facades::FacadeIdentifier;
use frontend_proto::common::ChipKind as ProtoChipKind;
use frontend_proto::model::Device as ProtoDevice;
use frontend_proto::model::Orientation as ProtoOrientation;
use frontend_proto::model::Position as ProtoPosition;
use std::collections::HashMap;

pub type DeviceIdentifier = i32;

pub struct Device {
    pub id: DeviceIdentifier,
    pub guid: String,
    name: String,
    visible: bool,
    pub position: ProtoPosition,
    orientation: ProtoOrientation,
    pub chips: HashMap<ChipIdentifier, Chip>,
}
impl Device {
    pub fn new(id: DeviceIdentifier, guid: String, name: String) -> Self {
        Device {
            id,
            guid,
            name,
            visible: true,
            position: ProtoPosition::new(),
            orientation: ProtoOrientation::new(),
            chips: HashMap::new(),
        }
    }
}

pub struct AddChipResult {
    pub device_id: DeviceIdentifier,
    pub chip_id: ChipIdentifier,
    pub facade_id: FacadeIdentifier,
}

impl Device {
    pub fn get(&self) -> ProtoDevice {
        let mut device = ProtoDevice::new();
        device.id = self.id;
        device.name = self.name.clone();
        device.visible = self.visible;
        device.position = protobuf::MessageField::from(Some(self.position.clone()));
        device.orientation = protobuf::MessageField::from(Some(self.orientation.clone()));
        for chip in self.chips.values() {
            device.chips.push(chip.get());
        }
        device
    }

    /// Patch a device and its chips.
    pub fn patch(&mut self, patch: &ProtoDevice) {
        // TODO visible should be State
        self.visible = patch.visible;
        if patch.position.is_some() {
            self.position.clone_from(&patch.position);
        }
        if patch.orientation.is_some() {
            self.orientation.clone_from(&patch.orientation);
        }
        // iterate over patched ProtoChip entries and patch matching chip
        for patch_chip in patch.chips.iter() {
            // Allow default chip kind of BLUETOOTH
            let patch_chip_kind = patch_chip.kind.enum_value_or(ProtoChipKind::BLUETOOTH);
            let patch_chip_name = &patch_chip.name;
            // Find the matching chip and patch the proto chip
            for chip in self.chips.values_mut() {
                if chip.name.eq(patch_chip_name) && chip.kind == patch_chip_kind {
                    chip.patch(patch_chip);
                    break; // next proto chip
                }
            }
        }
    }

    /// Remove a chip from a device.
    pub fn remove_chip(&mut self, chip_id: ChipIdentifier) {
        if let Some(chip) = self.chips.get_mut(&chip_id) {
            chip.remove();
        } else {
            eprintln!("RemoveChip id {chip_id} not found");
        }
        self.chips.remove(&chip_id);
    }

    pub fn add_chip(
        &mut self,
        device_name: &str,
        chip_kind: ProtoChipKind,
        chip_name: &str,
        chip_manufacturer: &str,
        chip_product_name: &str,
    ) -> Option<AddChipResult> {
        for chip in self.chips.values() {
            if chip.kind == chip_kind && chip.name == chip_name {
                eprintln!("Device::AddChip - duplicate at id {}, skipping.", chip.id);
                return None;
            }
        }
        let chip = chip::chip_new(
            self.id,
            chip_kind,
            chip_name,
            device_name,
            chip_manufacturer,
            chip_product_name,
        );
        let chip_id = chip.id;
        let facade_id = chip.facade_id;
        self.chips.insert(chip.id, chip);
        Some(AddChipResult { device_id: self.id, chip_id, facade_id })
    }

    /// Reset a device to its default state.
    pub fn reset(&mut self) {
        self.visible = true;
        self.position.clear();
        self.orientation.clear();
        for chp in self.chips.values_mut() {
            chp.reset();
        }
    }

    /// Remove all chips from a device.
    /// Called at shutdown.
    #[allow(dead_code)]
    pub fn remove(&mut self) {
        for (_, chip) in self.chips.iter_mut() {
            chip.remove();
        }
    }
}
