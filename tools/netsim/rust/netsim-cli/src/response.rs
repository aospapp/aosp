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

use std::cmp::max;

use crate::args::{self, Command, OnOffState, Pcap};
use frontend_proto::{
    common::ChipKind,
    frontend::{GetDevicesResponse, ListCaptureResponse, VersionResponse},
    model::{self, chip::Chip as Chip_oneof_chip, State},
};
use protobuf::Message;

impl args::Command {
    /// Format and print the response received from the frontend server for the command
    pub fn print_response(&self, response: &[u8], verbose: bool) {
        match self {
            Command::Version => {
                Self::print_version_response(VersionResponse::parse_from_bytes(response).unwrap());
            }
            Command::Radio(cmd) => {
                if verbose {
                    println!(
                        "Radio {} is {} for {}",
                        cmd.radio_type,
                        cmd.status,
                        cmd.name.to_owned()
                    );
                }
            }
            Command::Move(cmd) => {
                if verbose {
                    println!(
                        "Moved device:{} to x: {:.2}, y: {:.2}, z: {:.2}",
                        cmd.name,
                        cmd.x,
                        cmd.y,
                        cmd.z.unwrap_or_default()
                    )
                }
            }
            Command::Devices(_) => {
                Self::print_device_response(
                    GetDevicesResponse::parse_from_bytes(response).unwrap(),
                    verbose,
                );
            }
            Command::Reset => {
                if verbose {
                    println!("All devices have been reset.");
                }
            }
            Command::Pcap(Pcap::List(cmd)) => Self::print_list_capture_response(
                ListCaptureResponse::parse_from_bytes(response).unwrap(),
                verbose,
                cmd.patterns.to_owned(),
            ),
            Command::Pcap(Pcap::Patch(cmd)) => {
                if verbose {
                    println!(
                        "Patched Capture state to {}",
                        Self::on_off_state_to_string(cmd.state),
                    );
                }
            }
            Command::Pcap(Pcap::Get(_)) => {
                if verbose {
                    println!("Successfully downloaded Pcap.");
                }
            }
            Command::Gui => {
                unimplemented!("No Grpc Response for Gui Command.");
            }
        }
    }

    /// Helper function to format and print GetDevicesResponse
    fn print_device_response(response: GetDevicesResponse, verbose: bool) {
        let pos_prec = 2;
        let name_width = 16;
        let state_width = 5;
        let cnt_width = 9;
        let chip_indent = 2;
        let radio_width = 9;
        if verbose {
            if response.devices.is_empty() {
                println!("No attached devices found.");
            } else {
                println!("List of attached devices:");
            }
            for device in response.devices {
                let pos = device.position;
                println!(
                    "{:name_width$}  position: {:.pos_prec$}, {:.pos_prec$}, {:.pos_prec$}",
                    device.name, pos.x, pos.y, pos.z
                );
                for chip in &device.chips {
                    match &chip.chip {
                        Some(Chip_oneof_chip::Bt(bt)) => {
                            if bt.low_energy.is_some() {
                                let ble_chip = &bt.low_energy;
                                println!(
                                    "{:chip_indent$}{:radio_width$}{:state_width$}| rx_count: {:cnt_width$?} | tx_count: {:cnt_width$?} | capture: {}",
                                    "",
                                    "ble:",
                                    Self::chip_state_to_string(ble_chip.state.enum_value_or_default()),
                                    ble_chip.rx_count,
                                    ble_chip.tx_count,
                                    Self::capture_state_to_string(chip.capture.enum_value_or_default())
                                );
                            }
                            if bt.classic.is_some() {
                                let classic_chip = &bt.classic;
                                println!(
                                    "{:chip_indent$}{:radio_width$}{:state_width$}| rx_count: {:cnt_width$?} | tx_count: {:cnt_width$?} | capture: {}",
                                    "",
                                    "classic:",
                                    Self::chip_state_to_string(classic_chip.state.enum_value_or_default()),
                                    classic_chip.rx_count,
                                    classic_chip.tx_count,
                                    Self::capture_state_to_string(chip.capture.enum_value_or_default())
                                );
                            }
                        }
                        Some(Chip_oneof_chip::Wifi(wifi_chip)) => {
                            println!(
                                "{:chip_indent$}{:radio_width$}{:state_width$}| rx_count: {:cnt_width$?} | tx_count: {:cnt_width$?} | capture: {}",
                                "",
                                "wifi:",
                                Self::chip_state_to_string(wifi_chip.state.enum_value_or_default()),
                                wifi_chip.rx_count,
                                wifi_chip.tx_count,
                                Self::capture_state_to_string(chip.capture.enum_value_or_default())
                            );
                        }
                        Some(Chip_oneof_chip::Uwb(uwb_chip)) => {
                            println!(
                                "{:chip_indent$}{:radio_width$}{:state_width$}| rx_count: {:cnt_width$?} | tx_count: {:cnt_width$?} | capture: {}",
                                "",
                                "uwb:",
                                Self::chip_state_to_string(uwb_chip.state.enum_value_or_default()),
                                uwb_chip.rx_count,
                                uwb_chip.tx_count,
                                Self::capture_state_to_string(chip.capture.enum_value_or_default())
                            );
                        }
                        _ => println!("{:chip_indent$}Unknown chip: down  ", ""),
                    }
                }
            }
        } else {
            for device in response.devices {
                let pos = device.position;
                print!("{:name_width$}  ", device.name,);
                if pos.x != 0.0 || pos.y != 0.0 || pos.z != 0.0 {
                    print!(
                        "position: {:.pos_prec$}, {:.pos_prec$}, {:.pos_prec$}",
                        pos.x, pos.y, pos.z
                    );
                }
                for chip in &device.chips {
                    match &chip.chip {
                        Some(Chip_oneof_chip::Bt(bt)) => {
                            if bt.low_energy.is_some() {
                                let ble_chip = &bt.low_energy;
                                if ble_chip.state.enum_value_or_default() == State::OFF {
                                    print!(
                                        "{:chip_indent$}{:radio_width$}{:state_width$}",
                                        "",
                                        "ble:",
                                        Self::chip_state_to_string(
                                            ble_chip.state.enum_value_or_default()
                                        ),
                                    );
                                }
                            }
                            if bt.classic.is_some() {
                                let classic_chip = &bt.classic;
                                if classic_chip.state.enum_value_or_default() == State::OFF {
                                    print!(
                                        "{:chip_indent$}{:radio_width$}{:state_width$}",
                                        "",
                                        "classic:",
                                        Self::chip_state_to_string(
                                            classic_chip.state.enum_value_or_default()
                                        )
                                    );
                                }
                            }
                        }
                        Some(Chip_oneof_chip::Wifi(wifi_chip)) => {
                            if wifi_chip.state.enum_value_or_default() == State::OFF {
                                print!(
                                    "{:chip_indent$}{:radio_width$}{:state_width$}",
                                    "",
                                    "wifi:",
                                    Self::chip_state_to_string(
                                        wifi_chip.state.enum_value_or_default()
                                    )
                                );
                            }
                        }
                        Some(Chip_oneof_chip::Uwb(uwb_chip)) => {
                            if uwb_chip.state.enum_value_or_default() == State::OFF {
                                print!(
                                    "{:chip_indent$}{:radio_width$}{:state_width$}",
                                    "",
                                    "uwb:",
                                    Self::chip_state_to_string(
                                        uwb_chip.state.enum_value_or_default()
                                    )
                                );
                            }
                        }
                        _ => {}
                    }
                    if chip.capture.enum_value_or_default() == State::ON {
                        print!("{:chip_indent$}capture: on", "");
                    }
                }
                println!();
            }
        }
    }

    /// Helper function to convert frontend_proto::model::State to string for output
    fn chip_state_to_string(state: State) -> String {
        match state {
            State::ON => "up".to_string(),
            State::OFF => "down".to_string(),
            _ => "unknown".to_string(),
        }
    }

    fn capture_state_to_string(state: State) -> String {
        match state {
            State::ON => "on".to_string(),
            State::OFF => "off".to_string(),
            _ => "unknown".to_string(),
        }
    }

    fn on_off_state_to_string(state: OnOffState) -> String {
        match state {
            OnOffState::On => "on".to_string(),
            OnOffState::Off => "off".to_string(),
        }
    }

    /// Helper function to format and print VersionResponse
    fn print_version_response(response: VersionResponse) {
        println!("Netsim version: {}", response.version);
    }

    /// Helper function to format and print ListCaptureResponse
    fn print_list_capture_response(
        mut response: ListCaptureResponse,
        verbose: bool,
        patterns: Vec<String>,
    ) {
        if response.captures.is_empty() {
            if verbose {
                println!("No available Capture found.");
            }
            return;
        }
        if patterns.is_empty() {
            println!("List of Captures:");
        } else {
            // Filter out list of captures with matching patterns
            Self::filter_captures(&mut response.captures, &patterns);
            if response.captures.is_empty() {
                if verbose {
                    println!("No available Capture found matching pattern(s) `{:?}`:", patterns);
                }
                return;
            }
            println!("List of Captures matching pattern(s) `{:?}`:", patterns);
        }
        // Create the header row and determine column widths
        let id_hdr = "ID";
        let name_hdr = "Device Name";
        let chipkind_hdr = "Chip Kind";
        let state_hdr = "State";
        let size_hdr = "Size";
        let id_width = 4; // ID width of 4 since capture id (=chip_id) starts at 1000
        let state_width = 7; // State width of 7 for 'unknown'
        let chipkind_width = 11; // ChipKind width 11 for 'UNSPECIFIED'
        let name_width = max(
            (response.captures.iter().max_by_key(|x| x.device_name.len()))
                .unwrap_or_default()
                .device_name
                .len(),
            name_hdr.len(),
        );
        let size_width = max(
            (response.captures.iter().max_by_key(|x| x.size))
                .unwrap_or_default()
                .size
                .to_string()
                .len(),
            size_hdr.len(),
        );
        // Print header for capture list
        println!(
            "{}",
            if verbose {
                format!("{:id_width$} | {:name_width$} | {:chipkind_width$} | {:state_width$} | {:size_width$} |",
                    id_hdr,
                    name_hdr,
                    chipkind_hdr,
                    state_hdr,
                    size_hdr,
                )
            } else {
                format!(
                    "{:name_width$} | {:chipkind_width$} | {:state_width$} | {:size_width$} |",
                    name_hdr, chipkind_hdr, state_hdr, size_hdr,
                )
            }
        );
        // Print information of each Capture
        for capture in &response.captures {
            println!(
                "{}",
                if verbose {
                    format!("{:id_width$} | {:name_width$} | {:chipkind_width$} | {:state_width$} | {:size_width$} |",
                        capture.id.to_string(),
                        capture.device_name,
                        Self::chip_kind_to_string(capture.chip_kind.enum_value_or_default()),
                        Self::capture_state_to_string(capture.state.enum_value_or_default()),
                        capture.size,
                    )
                } else {
                    format!(
                        "{:name_width$} | {:chipkind_width$} | {:state_width$} | {:size_width$} |",
                        capture.device_name,
                        Self::chip_kind_to_string(capture.chip_kind.enum_value_or_default()),
                        Self::capture_state_to_string(capture.state.enum_value_or_default()),
                        capture.size,
                    )
                }
            );
        }
    }

    pub fn chip_kind_to_string(chip_kind: ChipKind) -> String {
        match chip_kind {
            ChipKind::UNSPECIFIED => "UNSPECIFIED".to_string(),
            ChipKind::BLUETOOTH => "BLUETOOTH".to_string(),
            ChipKind::WIFI => "WIFI".to_string(),
            ChipKind::UWB => "UWB".to_string(),
        }
    }

    pub fn filter_captures(captures: &mut Vec<model::Capture>, keys: &[String]) {
        // Filter out list of captures with matching pattern
        captures.retain(|capture| {
            keys.iter().map(|key| key.to_uppercase()).all(|key| {
                capture.id.to_string().contains(&key)
                    || capture.device_name.to_uppercase().contains(&key)
                    || Self::chip_kind_to_string(capture.chip_kind.enum_value_or_default())
                        .contains(&key)
            })
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn test_filter_captures_helper(patterns: Vec<String>, expected_captures: Vec<model::Capture>) {
        let mut captures = all_test_captures();
        Command::filter_captures(&mut captures, &patterns);
        assert_eq!(captures, expected_captures);
    }

    fn capture_1() -> model::Capture {
        model::Capture {
            id: 4001,
            chip_kind: ChipKind::BLUETOOTH.into(),
            device_name: "device 1".to_string(),
            ..Default::default()
        }
    }
    fn capture_1_wifi() -> model::Capture {
        model::Capture {
            id: 4002,
            chip_kind: ChipKind::WIFI.into(),
            device_name: "device 1".to_string(),
            ..Default::default()
        }
    }
    fn capture_2() -> model::Capture {
        model::Capture {
            id: 4003,
            chip_kind: ChipKind::BLUETOOTH.into(),
            device_name: "device 2".to_string(),
            ..Default::default()
        }
    }
    fn capture_3() -> model::Capture {
        model::Capture {
            id: 4004,
            chip_kind: ChipKind::WIFI.into(),
            device_name: "device 3".to_string(),
            ..Default::default()
        }
    }
    fn capture_4_uwb() -> model::Capture {
        model::Capture {
            id: 4005,
            chip_kind: ChipKind::UWB.into(),
            device_name: "device 4".to_string(),
            ..Default::default()
        }
    }
    fn all_test_captures() -> Vec<model::Capture> {
        vec![capture_1(), capture_1_wifi(), capture_2(), capture_3(), capture_4_uwb()]
    }

    #[test]
    fn test_no_match() {
        test_filter_captures_helper(vec!["test".to_string()], vec![]);
    }

    #[test]
    fn test_all_match() {
        test_filter_captures_helper(vec!["device".to_string()], all_test_captures());
    }

    #[test]
    fn test_match_capture_id() {
        test_filter_captures_helper(vec!["4001".to_string()], vec![capture_1()]);
        test_filter_captures_helper(vec!["03".to_string()], vec![capture_2()]);
        test_filter_captures_helper(vec!["40".to_string()], all_test_captures());
    }

    #[test]
    fn test_match_device_name() {
        test_filter_captures_helper(
            vec!["device 1".to_string()],
            vec![capture_1(), capture_1_wifi()],
        );
        test_filter_captures_helper(vec![" 2".to_string()], vec![capture_2()]);
    }

    #[test]
    fn test_match_device_name_case_insensitive() {
        test_filter_captures_helper(
            vec!["DEVICE 1".to_string()],
            vec![capture_1(), capture_1_wifi()],
        );
    }

    #[test]
    fn test_match_wifi() {
        test_filter_captures_helper(vec!["wifi".to_string()], vec![capture_1_wifi(), capture_3()]);
        test_filter_captures_helper(vec!["WIFI".to_string()], vec![capture_1_wifi(), capture_3()]);
    }

    #[test]
    fn test_match_uwb() {
        test_filter_captures_helper(vec!["uwb".to_string()], vec![capture_4_uwb()]);
        test_filter_captures_helper(vec!["UWB".to_string()], vec![capture_4_uwb()]);
    }

    #[test]
    fn test_match_bt() {
        test_filter_captures_helper(vec!["BLUETOOTH".to_string()], vec![capture_1(), capture_2()]);
        test_filter_captures_helper(vec!["blue".to_string()], vec![capture_1(), capture_2()]);
    }

    #[test]
    fn test_match_name_and_chip() {
        test_filter_captures_helper(
            vec!["device 1".to_string(), "wifi".to_string()],
            vec![capture_1_wifi()],
        );
    }
}
