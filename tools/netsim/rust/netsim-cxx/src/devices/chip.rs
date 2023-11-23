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

/// A Chip is a generic emulated radio that connects to Chip Facade
/// library.
///
/// The chip facade is a library that implements the controller protocol.
///
use crate::devices::facades::FacadeIdentifier;
use crate::devices::facades::*;
use crate::devices::id_factory::IdFactory;
use frontend_proto::common::ChipKind as ProtoChipKind;
use frontend_proto::model::Chip as ProtoChip;
use frontend_proto::model::State as ProtoState;
use lazy_static::lazy_static;
use protobuf::EnumOrUnknown;

use std::sync::RwLock;

use super::facades;
use crate::devices::device::DeviceIdentifier;

pub type ChipIdentifier = i32;

// Allocator for chip identifiers.
lazy_static! {
    static ref IDS: RwLock<IdFactory<ChipIdentifier>> = RwLock::new(IdFactory::new(2000, 1));
}

pub struct Chip {
    pub id: ChipIdentifier,
    pub facade_id: FacadeIdentifier,
    pub kind: ProtoChipKind,
    pub name: String,
    // TODO: may not be necessary
    pub device_name: String,
    // These are patchable
    manufacturer: String,
    product_name: String,
    capture: ProtoState,
}

impl Chip {
    fn new(
        id: ChipIdentifier,
        facade_id: FacadeIdentifier,
        kind: ProtoChipKind,
        name: &str,
        device_name: &str,
        manufacturer: &str,
        product_name: &str,
    ) -> Self {
        Self {
            id,
            facade_id,
            kind,
            name: name.to_string(),
            device_name: device_name.to_string(),
            manufacturer: manufacturer.to_string(),
            product_name: product_name.to_string(),
            capture: ProtoState::OFF,
        }
    }

    /// Create the model protobuf
    pub fn get(&self) -> ProtoChip {
        let mut chip = ProtoChip::new();
        chip.kind = EnumOrUnknown::new(self.kind);
        chip.id = self.id;
        chip.name = self.name.clone();
        chip.manufacturer = self.manufacturer.clone();
        chip.product_name = self.product_name.clone();
        chip.capture = EnumOrUnknown::new(self.capture);
        match chip.kind.enum_value() {
            Ok(ProtoChipKind::BLUETOOTH) => {
                chip.set_bt(hci_get(self.facade_id));
            }
            Ok(ProtoChipKind::WIFI) => {
                chip.set_wifi(wifi_get(self.facade_id));
            }
            _ => {
                eprint!("Unknown chip kind: {:?}", chip.kind);
            }
        }
        chip
    }

    /// Patch processing for the chip. Validate and move state from the patch
    /// into the chip changing the ChipFacade as needed.
    pub fn patch(&mut self, patch: &ProtoChip) {
        if let Ok(patch_capture) = patch.capture.enum_value() {
            if patch_capture != ProtoState::UNKNOWN && patch_capture != self.capture {
                self.capture = patch_capture;
            }
        }
        if !patch.manufacturer.is_empty() {
            self.manufacturer = patch.manufacturer.clone();
        }
        if !patch.product_name.is_empty() {
            self.product_name = patch.product_name.clone();
        }
        // Check both ChipKind and RadioKind fields, they should be consistent
        if self.kind == ProtoChipKind::BLUETOOTH && patch.has_bt() {
            facades::hci_patch(self.facade_id, patch.bt());
        } else if self.kind == ProtoChipKind::WIFI && patch.has_wifi() {
            wifi_patch(self.facade_id, patch.wifi());
        } else {
            eprint!("Unknown chip kind or missing radio: {:?}", self.kind);
        }
    }

    pub fn remove(&mut self) {
        match self.kind {
            ProtoChipKind::BLUETOOTH => {
                hci_remove(self.facade_id);
            }
            ProtoChipKind::WIFI => {
                wifi_remove(self.facade_id);
            }
            _ => {
                eprint!("Unknown chip kind: {:?}", self.kind);
            }
        }
    }

    pub fn reset(&mut self) {
        match self.kind {
            ProtoChipKind::BLUETOOTH => {
                hci_reset(self.facade_id);
            }
            ProtoChipKind::WIFI => {
                wifi_reset(self.facade_id);
            }
            _ => {
                eprint!("Unknown chip kind: {:?}", self.kind);
            }
        }
    }
}

/// Allocates a new chip with a facade_id.
pub fn chip_new(
    device_id: DeviceIdentifier,
    chip_kind: ProtoChipKind,
    chip_name: &str,
    device_name: &str,
    chip_manufacturer: &str,
    chip_product_name: &str,
) -> Chip {
    let id = IDS.write().unwrap().next_id();
    let facade_id = match chip_kind {
        ProtoChipKind::BLUETOOTH => facades::hci_add(device_id),
        ProtoChipKind::WIFI => facades::wifi_add(device_id),
        _ => {
            panic!("Unknown chip kind: {:?}", chip_kind);
        }
    };
    Chip::new(
        id,
        facade_id,
        chip_kind,
        chip_name,
        device_name,
        chip_manufacturer,
        chip_product_name,
    )
}
