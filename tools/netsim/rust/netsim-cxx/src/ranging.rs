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

//! Ranging library for RSSI.
///
/// The Free Space Path Loss (FSPL) model is considered as the standard
/// under the ideal scenario.

/// (dBm) PATH_LOSS at 1m for isotropic antenna transmitting BLE.
const PATH_LOSS_AT_1M: f32 = 40.20;

/// Convert distance to RSSI using the free space path loss equation.
/// See [Free-space_path_loss][1].
///
/// [1]: http://en.wikipedia.org/wiki/Free-space_path_loss
///
/// # Parameters
///
/// * `distance`: distance in meters (m).
/// * `tx_power1`: transmitted power (dBm) calibrated to 1 meter.
///
/// # Returns
///
/// The rssi (dBm) that would be measured at that distance.
pub fn distance_to_rssi(tx_power: i8, distance: f32) -> i8 {
    assert!(distance >= 0.0);
    if distance == 0.0 {
        tx_power
    } else {
        (tx_power as f32 - 20.0 * distance.log10() - PATH_LOSS_AT_1M) as i8
    }
}

mod tests {
    #[test]
    fn zero_distance() {
        let rssi_at_0 = super::distance_to_rssi(-120, 0.0);
        assert_eq!(rssi_at_0, -120);
    }
    #[test]
    fn rssi_at_far() {
        // With transmit power at 0 dBm verify a reasonable rssi at 1m
        let rssi_at_1 = super::distance_to_rssi(0, 1.0);
        assert!(rssi_at_1 < -35 && rssi_at_1 > -55);
    }
}
