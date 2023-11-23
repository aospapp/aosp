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

/// request packets flow into netsim
/// response packets flow out of netsim
/// packet transports read requests and write response packets over gRPC or Fds.
use super::h4;
use super::uci;
use crate::ffi::{add_chip_cxx, handle_request_cxx};
use cxx::let_cxx_string;
use lazy_static::lazy_static;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs::File;
use std::io::{IoSlice, Write};
use std::os::fd::FromRawFd;
use std::sync::RwLock;
use std::thread::JoinHandle;
use std::{fmt, thread};

#[derive(Serialize, Deserialize, Debug)]
struct StartupInfo {
    devices: Vec<Device>,
}

#[derive(Serialize, Deserialize, Debug)]
struct Device {
    name: String,
    chips: Vec<Chip>,
}

#[derive(Serialize, Deserialize, Debug, Copy, Clone)]
enum ChipKindEnum {
    UNKNOWN = 0,
    BLUETOOTH = 1,
    WIFI = 2,
    UWB = 3,
}

impl fmt::Display for ChipKindEnum {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

#[derive(Serialize, Deserialize, Debug)]
struct Chip {
    kind: ChipKindEnum,
    id: Option<String>,
    manufacturer: Option<String>,
    product_name: Option<String>,
    #[serde(rename = "fdIn")]
    fd_in: u32,
    #[serde(rename = "fdOut")]
    fd_out: u32,
    loopback: Option<bool>,
}

// TRANSPORTS is a singleton that contains the output FiFo
lazy_static! {
    static ref TRANSPORTS: RwLock<HashMap<String, File>> = RwLock::new(HashMap::new());
}

fn key(kind: u32, facade_id: u32) -> String {
    format!("{}/{}", kind, facade_id)
}

pub fn handle_response(kind: u32, facade_id: u32, packet: &cxx::CxxVector<u8>, packet_type: u8) {
    let binding = TRANSPORTS.read().unwrap();
    let key = key(kind, facade_id);
    match binding.get(&key) {
        Some(mut fd_out) => {
            // todo add error checking
            let temp = [packet_type];
            let bufs = [IoSlice::new(&temp), IoSlice::new(packet.as_slice())];
            if let Err(e) = fd_out.write_vectored(&bufs) {
                println!("netsimd: error writing {}", e);
            };
        }
        None => {
            println!("netsimd: Error unknown key {}/{}", kind, facade_id);
        }
    };
}

/// read from the raw fd and pass to the packet hub.
///
fn fd_reader(fd_rx: i32, kind: ChipKindEnum, facade_id: u32) -> JoinHandle<()> {
    thread::Builder::new()
        .name(format!("fd_reader_{}", fd_rx))
        .spawn(move || {
            let mut rx = unsafe { File::from_raw_fd(fd_rx) };

            println!(
                "netsimd: thread handling kind:{:?} facade_id:{:?} fd:{}",
                kind, facade_id, fd_rx
            );

            loop {
                match kind {
                    ChipKindEnum::UWB => match uci::read_uci_packet(&mut rx) {
                        Err(e) => {
                            println!(
                                "netsimd: error reading uci control packet fd {} {:?}",
                                fd_rx, e
                            );
                            return;
                        }
                        Ok(uci::Packet { payload }) => {
                            handle_request_cxx(kind as u32, facade_id, &payload, 0);
                        }
                    },
                    ChipKindEnum::BLUETOOTH => match h4::read_h4_packet(&mut rx) {
                        Ok(h4::Packet { h4_type, payload }) => {
                            handle_request_cxx(kind as u32, facade_id, &payload, h4_type);
                        }
                        Err(e) => {
                            println!(
                                "netsimd: error reading hci control packet fd {} {:?}",
                                fd_rx, e
                            );
                            return;
                        }
                    },
                    _ => {
                        println!("netsimd: unknown control packet kind: {:?}", kind);
                        return;
                    }
                };
            }
        })
        .unwrap()
}

/// start_fd_transport
///
/// Create threads to read and write to file descriptors
//
pub fn run_fd_transport(startup_json: &String) {
    println!("netsimd: fd_transport starting with {startup_json}");
    let startup_info = match serde_json::from_str::<StartupInfo>(startup_json.as_str()) {
        Err(e) => {
            println!("Error parsing startup info: {:?}", e);
            return;
        }
        Ok(startup_info) => startup_info,
    };
    // See https://tokio.rs/tokio/topics/bridging
    // This code is synchronous hosting asynchronous until main is converted to rust.
    thread::Builder::new()
        .name("fd_transport".to_string())
        .spawn(move || {
            let chip_count = startup_info.devices.iter().map(|d| d.chips.len()).sum();
            let mut handles = Vec::with_capacity(chip_count);
            for device in startup_info.devices {
                for chip in device.chips {
                    let_cxx_string!(guid = chip.fd_in.to_string());
                    let_cxx_string!(device_name = device.name.clone());
                    let_cxx_string!(name = chip.id.unwrap_or_default());
                    let_cxx_string!(manufacturer = chip.manufacturer.unwrap_or_default());
                    let_cxx_string!(product_name = chip.product_name.unwrap_or_default());
                    let result = add_chip_cxx(
                        &guid,
                        &device_name,
                        chip.kind as u32,
                        &name,
                        &manufacturer,
                        &product_name,
                    );
                    let key = key(chip.kind as u32, result.get_facade_id());

                    // Cf writes to fd_out and reads from fd_in
                    let file_in = unsafe { File::from_raw_fd(chip.fd_in as i32) };

                    TRANSPORTS.write().unwrap().insert(key, file_in);
                    // TODO: switch to runtime.spawn once FIFOs are available in Tokio
                    handles.push(fd_reader(chip.fd_out as i32, chip.kind, result.get_facade_id()));
                }
            }
            // Wait for all of them to complete.
            for handle in handles {
                // The `spawn` method returns a `JoinHandle`. A `JoinHandle` is
                // a future, so we can wait for it using `block_on`.
                // runtime.block_on(handle).unwrap();
                // TODO: use runtime.block_on once FIFOs are available in Tokio
                handle.join().unwrap();
            }
            println!("netsimd:: done with all fd handlers");
        })
        .unwrap();
}

mod tests {
    #[allow(unused)]
    use super::StartupInfo;

    #[test]
    fn test_serde() {
        let s = r#"
    {"devices": [
       {"name": "emulator-5554",
        "chips": [{"kind": "WIFI", "fdIn": 1, "fdOut": 2},
                  {"kind": "BLUETOOTH", "fdIn": 20, "fdOut":21}]
       },
       {"name": "emulator-5555",
        "chips": [{"kind": "BLUETOOTH", "fdIn": 3, "fdOut": 4},
                {"kind": "UWB", "fdIn": 5, "fdOut": 6, "model": "DW300"}]
       }
     ]
    }"#;
        let startup_info = serde_json::from_str::<StartupInfo>(s).unwrap();
        for device in startup_info.devices {
            println!("device {:?}", device);
        }
    }
}
