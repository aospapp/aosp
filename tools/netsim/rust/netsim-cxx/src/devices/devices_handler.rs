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

// devices_handler.rs
//
// Provides the API for the frontend and backend to interact with devices.
//
// The Devices struct is a singleton for the devices collection.
//
// Additional functions are
// -- inactivity instant
// -- vending device identifiers

use super::chip::ChipIdentifier;
use super::device::DeviceIdentifier;
use super::id_factory::IdFactory;
use crate::devices::device::AddChipResult;
use crate::devices::device::Device;
use frontend_proto::common::ChipKind as ProtoChipKind;
use frontend_proto::model::Device as ProtoDevice;
use frontend_proto::model::Position as ProtoPosition;
use frontend_proto::model::Scene as ProtoScene;
use lazy_static::lazy_static;
use protobuf_json_mapping::merge_from_str;
use protobuf_json_mapping::print_to_string;
use std::collections::HashMap;
use std::sync::RwLock;
use std::sync::RwLockWriteGuard;
use std::time::Duration;
use std::time::Instant;

lazy_static! {
    static ref DEVICES: RwLock<Devices> = RwLock::new(Devices::new());
}
static IDLE_SECS_FOR_SHUTDOWN: u64 = 120;

/// The Device resource is a singleton that manages all devices.
struct Devices {
    devices: HashMap<DeviceIdentifier, Device>,
    id_factory: IdFactory<DeviceIdentifier>,
    pub idle_since: Option<Instant>,
}

impl Devices {
    fn new() -> Self {
        Devices { devices: HashMap::new(), id_factory: IdFactory::new(1000, 1), idle_since: None }
    }
}

#[allow(dead_code)]
fn notify_all() {
    // TODO
}

/// add_chip is called by the transport layer when a new chip is attached.
///
/// The guid is a transport layer identifier for the device (host:port)
/// that is adding the chip.
#[allow(dead_code)]
pub fn add_chip(
    device_guid: &str,
    device_name: &str,
    chip_kind: ProtoChipKind,
    chip_name: &str,
    chip_manufacturer: &str,
    chip_product_name: &str,
) -> AddChipResult {
    let mut resource = DEVICES.write().unwrap();
    resource.idle_since = None;
    let device_id = get_or_create_device(&mut resource, device_guid, device_name);
    // This is infrequent, so we can afford to do another lookup for the device.
    resource
        .devices
        .get_mut(&device_id)
        .unwrap()
        .add_chip(device_name, chip_kind, chip_name, chip_manufacturer, chip_product_name)
        .unwrap_or_else(|| {
            eprintln!("Error adding chip to device {}", device_id);
            AddChipResult { device_id: 0, chip_id: 0, facade_id: 0 }
        })
}

/// Get or create a device.
fn get_or_create_device(
    resource: &mut RwLockWriteGuard<Devices>,
    guid: &str,
    name: &str,
) -> DeviceIdentifier {
    // Check if a device with the given guid already exists
    if let Some(existing_device) = resource.devices.values().find(|d| d.guid == guid) {
        // A device with the same guid already exists, return it
        existing_device.id
    } else {
        // No device with the same guid exists, insert the new device
        let new_id = resource.id_factory.next_id();
        resource.devices.insert(new_id, Device::new(new_id, guid.to_string(), name.to_string()));
        new_id
    }
}

/// Remove a device from the simulation.
///
/// Called when the last chip for the device is removed.
fn remove_device(resource: &mut RwLockWriteGuard<Devices>, id: DeviceIdentifier) {
    resource.devices.remove(&id).or_else(|| {
        eprintln!("Error removing device id {id}");
        None
    });
    if resource.devices.is_empty() {
        resource.idle_since = Some(Instant::now());
    }
}

/// Remove a chip from a device.
///
/// Called when the packet transport for the chip shuts down.
#[allow(dead_code)]
pub fn remove_chip(device_id: DeviceIdentifier, chip_id: ChipIdentifier) {
    let mut resource = DEVICES.write().unwrap();
    let mut is_empty = false;
    resource.devices.entry(device_id).and_modify(|device| {
        device.remove_chip(chip_id);
        is_empty = device.chips.is_empty();
    });
    if is_empty {
        remove_device(&mut resource, device_id);
    }
}
/// MATCH
///
///

// lock the devices, find the id and call the patch function
#[allow(dead_code)]
fn patch_device(id: DeviceIdentifier, patch_json: &str) {
    let mut proto_device = ProtoDevice::new();
    if merge_from_str(&mut proto_device, patch_json).is_ok() {
        DEVICES
            .write()
            .unwrap()
            .devices
            .get_mut(&id)
            .map(|device_ref| device_ref.patch(&proto_device))
            .or_else(|| {
                eprintln!("No such device with id {id}");
                None
            });
    } else {
        eprintln!("Error parsing device {id} patch json {}", patch_json);
    }
}

fn distance(a: &ProtoPosition, b: &ProtoPosition) -> f32 {
    ((b.x - a.x).powf(2.0) + (b.y - a.y).powf(2.0) + (b.z - a.z).powf(2.0)).sqrt()
}

#[allow(dead_code)]
pub fn get_distance(id: DeviceIdentifier, other_id: DeviceIdentifier) -> f32 {
    print!("get_distance({:?}, {:?}) = ", id, other_id);
    let devices = &DEVICES.read().unwrap().devices;
    let a = devices.get(&id).map(|device_ref| device_ref.position.clone()).or_else(|| {
        eprintln!("No such device with id {id}");
        None
    });
    let b = devices.get(&other_id).map(|device_ref| device_ref.position.clone()).or_else(|| {
        eprintln!("No such device with id {id}");
        None
    });
    match (a, b) {
        (Some(a), Some(b)) => distance(&a, &b),
        _ => 0.0,
    }
}

#[allow(dead_code)]
pub fn get_devices() -> String {
    let mut scene = ProtoScene::new();
    // iterate over the devices and add each to the scene
    DEVICES.read().unwrap().devices.values().for_each(|device| {
        scene.devices.push(device.get());
    });
    print_to_string(&scene).unwrap_or_else(|e| -> String {
        eprintln!("Error converting scene {:?}", e);
        String::new()
    })
}

#[allow(dead_code)]
pub fn reset(id: DeviceIdentifier) {
    DEVICES.write().unwrap().devices.get_mut(&id).map(|device_ref| device_ref.reset()).or_else(
        || {
            eprintln!("No such device with id {id}");
            None
        },
    );
}

#[allow(dead_code)]
fn get_secs_until_idle_shutdown() -> Option<u32> {
    if let Some(idle_since) = DEVICES.read().unwrap().idle_since {
        let remaining_secs = idle_since
            .elapsed()
            .saturating_sub(Duration::from_secs(IDLE_SECS_FOR_SHUTDOWN))
            .as_secs();
        Some(remaining_secs.try_into().unwrap())
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn new_with_xyz(x: f32, y: f32, z: f32) -> ProtoPosition {
        ProtoPosition { x, y, z, ..Default::default() }
    }

    #[test]
    fn test_distance() {
        // Pythagorean quadruples
        let a = new_with_xyz(0.0, 0.0, 0.0);
        let mut b = new_with_xyz(1.0, 2.0, 2.0);
        assert_eq!(distance(&a, &b), 3.0);
        b = new_with_xyz(2.0, 3.0, 6.0);
        assert_eq!(distance(&a, &b), 7.0);
    }
}
