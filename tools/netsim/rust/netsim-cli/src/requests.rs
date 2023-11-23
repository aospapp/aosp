// Copyright 2022 Google LLC
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

use crate::args::{self, Command};
use frontend_client_cxx::ffi::GrpcMethod;

impl args::Command {
    /// Return the respective GrpcMethod for the command
    pub fn grpc_method(&self) -> GrpcMethod {
        match self {
            Command::Version => GrpcMethod::GetVersion,
            Command::Radio(_) => GrpcMethod::PatchDevice,
            Command::Move(_) => GrpcMethod::PatchDevice,
            Command::Devices(_) => GrpcMethod::GetDevices,
            Command::Reset => GrpcMethod::Reset,
            Command::Pcap(cmd) => match cmd {
                args::Pcap::List(_) => GrpcMethod::ListCapture,
                args::Pcap::Get(_) => GrpcMethod::GetCapture,
                args::Pcap::Patch(_) => GrpcMethod::PatchCapture,
            },
            Command::Gui => {
                panic!("No GrpcMethod for Ui Command.");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use args::{BinaryProtobuf, NetsimArgs};
    use clap::Parser;
    use frontend_proto::{
        common::ChipKind,
        frontend,
        model::{
            self,
            chip::{Bluetooth as Chip_Bluetooth, Radio as Chip_Radio},
            Device, Position, State,
        },
    };
    use protobuf::Message;

    fn test_command(
        command: &str,
        expected_grpc_method: GrpcMethod,
        expected_request_byte_str: BinaryProtobuf,
    ) {
        let command = NetsimArgs::parse_from(command.split_whitespace()).command;
        assert_eq!(expected_grpc_method, command.grpc_method());
        let request = command.get_request_bytes();
        assert_eq!(request, expected_request_byte_str);
    }

    #[test]
    fn test_version_request() {
        test_command("netsim-cli version", GrpcMethod::GetVersion, Vec::new())
    }

    fn get_expected_radio(name: &str, radio_type: &str, state: &str) -> BinaryProtobuf {
        let mut chip = model::Chip { ..Default::default() };
        let chip_state = match state {
            "up" => State::ON,
            _ => State::OFF,
        };
        if radio_type == "wifi" {
            let mut wifi_chip = Chip_Radio::new();
            wifi_chip.state = chip_state.into();
            chip.set_wifi(wifi_chip);
            chip.kind = ChipKind::WIFI.into();
        } else if radio_type == "uwb" {
            let mut uwb_chip = Chip_Radio::new();
            uwb_chip.state = chip_state.into();
            chip.set_uwb(uwb_chip);
            chip.kind = ChipKind::UWB.into();
        } else {
            let mut bt_chip = Chip_Bluetooth::new();
            let mut bt_chip_radio = Chip_Radio::new();
            bt_chip_radio.state = chip_state.into();
            if radio_type == "ble" {
                bt_chip.low_energy = Some(bt_chip_radio).into();
            } else {
                bt_chip.classic = Some(bt_chip_radio).into();
            }
            chip.kind = ChipKind::BLUETOOTH.into();
            chip.set_bt(bt_chip);
        }
        let mut result = frontend::PatchDeviceRequest::new();
        let mut device = Device::new();
        device.name = name.to_owned();
        device.chips.push(chip);
        result.device = Some(device).into();
        result.write_to_bytes().unwrap()
    }

    #[test]
    fn test_radio_ble() {
        test_command(
            "netsim-cli radio ble down 1000",
            GrpcMethod::PatchDevice,
            get_expected_radio("1000", "ble", "down"),
        );
        test_command(
            "netsim-cli radio ble up 1000",
            GrpcMethod::PatchDevice,
            get_expected_radio("1000", "ble", "up"),
        );
    }

    #[test]
    fn test_radio_ble_aliases() {
        test_command(
            "netsim-cli radio ble Down 1000",
            GrpcMethod::PatchDevice,
            get_expected_radio("1000", "ble", "down"),
        );
        test_command(
            "netsim-cli radio ble Up 1000",
            GrpcMethod::PatchDevice,
            get_expected_radio("1000", "ble", "up"),
        );
        test_command(
            "netsim-cli radio ble DOWN 1000",
            GrpcMethod::PatchDevice,
            get_expected_radio("1000", "ble", "down"),
        );
        test_command(
            "netsim-cli radio ble UP 1000",
            GrpcMethod::PatchDevice,
            get_expected_radio("1000", "ble", "up"),
        );
    }

    #[test]
    fn test_radio_classic() {
        test_command(
            "netsim-cli radio classic down 100",
            GrpcMethod::PatchDevice,
            get_expected_radio("100", "classic", "down"),
        );
        test_command(
            "netsim-cli radio classic up 100",
            GrpcMethod::PatchDevice,
            get_expected_radio("100", "classic", "up"),
        );
    }

    #[test]
    fn test_radio_wifi() {
        test_command(
            "netsim-cli radio wifi down a",
            GrpcMethod::PatchDevice,
            get_expected_radio("a", "wifi", "down"),
        );
        test_command(
            "netsim-cli radio wifi up b",
            GrpcMethod::PatchDevice,
            get_expected_radio("b", "wifi", "up"),
        );
    }

    #[test]
    fn test_radio_uwb() {
        test_command(
            "netsim-cli radio uwb down a",
            GrpcMethod::PatchDevice,
            get_expected_radio("a", "uwb", "down"),
        );
        test_command(
            "netsim-cli radio uwb up b",
            GrpcMethod::PatchDevice,
            get_expected_radio("b", "uwb", "up"),
        );
    }

    fn get_expected_move(name: &str, x: f32, y: f32, z: Option<f32>) -> BinaryProtobuf {
        let mut result = frontend::PatchDeviceRequest::new();
        let mut device = Device::new();
        let position = Position { x, y, z: z.unwrap_or_default(), ..Default::default() };
        device.name = name.to_owned();
        device.position = Some(position).into();
        result.device = Some(device).into();
        result.write_to_bytes().unwrap()
    }

    #[test]
    fn test_move_int() {
        test_command(
            "netsim-cli move 1 1 2 3",
            GrpcMethod::PatchDevice,
            get_expected_move("1", 1.0, 2.0, Some(3.0)),
        )
    }

    #[test]
    fn test_move_float() {
        test_command(
            "netsim-cli move 1000 1.2 3.4 5.6",
            GrpcMethod::PatchDevice,
            get_expected_move("1000", 1.2, 3.4, Some(5.6)),
        )
    }

    #[test]
    fn test_move_mixed() {
        test_command(
            "netsim-cli move 1000 1.1 2 3.4",
            GrpcMethod::PatchDevice,
            get_expected_move("1000", 1.1, 2.0, Some(3.4)),
        )
    }

    #[test]
    fn test_move_no_z() {
        test_command(
            "netsim-cli move 1000 1.2 3.4",
            GrpcMethod::PatchDevice,
            get_expected_move("1000", 1.2, 3.4, None),
        )
    }

    #[test]
    fn test_devices() {
        test_command("netsim-cli devices", GrpcMethod::GetDevices, Vec::new())
    }

    #[test]
    fn test_reset() {
        test_command("netsim-cli reset", GrpcMethod::Reset, Vec::new())
    }

    #[test]
    fn test_pcap_list() {
        test_command("netsim-cli pcap list", GrpcMethod::ListCapture, Vec::new())
    }

    //TODO: Add pcap patch and get tests once able to run tests with cxx definitions
}
