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

use std::io::{Error, Read};

#[derive(Debug)]
pub struct Packet {
    pub h4_type: u8,
    pub payload: Vec<u8>,
}

#[derive(Debug)]
pub enum PacketError {
    IoError(Error),
    InvalidPacketType,
    InvalidPacket,
}

/* H4 message type */
const H4_CMD_TYPE: u8 = 1;
const H4_ACL_TYPE: u8 = 2;
const H4_SCO_TYPE: u8 = 3;
const H4_EVT_TYPE: u8 = 4;
const H4_ISO_TYPE: u8 = 5;

/* HCI message preamble size */
const HCI_CMD_PREAMBLE_SIZE: usize = 3;
const HCI_ACL_PREAMBLE_SIZE: usize = 4;
const HCI_SCO_PREAMBLE_SIZE: usize = 3;
const HCI_EVT_PREAMBLE_SIZE: usize = 2;
const HCI_ISO_PREAMBLE_SIZE: usize = 4;

// TODO: recovery mode to get back on sync
pub fn read_h4_packet<R: Read>(reader: &mut R) -> Result<Packet, PacketError> {
    // Read the h4 type and obtain the preamble length
    let mut buffer = [0u8; 1];
    reader.read_exact(&mut buffer).map_err(PacketError::IoError)?;
    let h4_type = buffer[0];
    let preamble_size = match h4_type {
        H4_CMD_TYPE => HCI_CMD_PREAMBLE_SIZE,
        H4_ACL_TYPE => HCI_ACL_PREAMBLE_SIZE,
        H4_SCO_TYPE => HCI_SCO_PREAMBLE_SIZE,
        H4_EVT_TYPE => HCI_EVT_PREAMBLE_SIZE,
        H4_ISO_TYPE => HCI_ISO_PREAMBLE_SIZE,
        _ => return Err(PacketError::InvalidPacketType),
    };

    // Read the preamble and obtain the payload length
    let mut packet = vec![0u8; preamble_size];
    reader.read_exact(&mut packet).map_err(PacketError::IoError)?;
    let payload_length: usize = match h4_type {
        H4_CMD_TYPE => {
            // Command: 2 bytes for opcode, 1 byte for parameter length (Volume 2, Part E, 5.4.1)
            packet[2] as usize
        }
        H4_ACL_TYPE => {
            // ACL: 2 bytes for handle, 2 bytes for data length (Volume 2, Part E, 5.4.2)
            u16::from_le_bytes([packet[2], packet[3]]) as usize
        }
        H4_SCO_TYPE => {
            // SCO: 2 bytes for handle, 1 byte for data length (Volume 2, Part E, 5.4.3)
            packet[2] as usize
        }
        H4_EVT_TYPE => {
            // Event: 1 byte for event code, 1 byte for parameter length (Volume 2, Part E, 5.4.4)
            packet[1] as usize
        }
        H4_ISO_TYPE => {
            // 2 bytes for handle and flags, 12 bits for length (Volume 2, Part E, 5.4.5)
            usize::from(packet[3] & 0x0f) << 8 | usize::from(packet[2])
        }
        _ => panic!("Unknown H4 packet type."),
    };
    // Read and append the payload and return
    packet.resize(preamble_size + payload_length, 0u8);
    reader.read_exact(&mut packet[preamble_size..]).map_err(PacketError::IoError)?;
    Ok(Packet { h4_type, payload: packet })
}

#[cfg(test)]
mod tests {
    use super::read_h4_packet;
    use super::H4_ACL_TYPE;
    use std::io::Cursor;

    #[test]
    fn test_acl() {
        let hci_acl: Vec<u8> = vec![
            0x02, 0x2a, 0x20, 0x11, 0x00, 0x15, 0x00, 0x40, 0x00, 0x06, 0x00, 0x01, 0x00, 0x10,
            0x36, 0x00, 0x03, 0x19, 0x11, 0x08, 0x02, 0xa0,
        ];
        let mut file = Cursor::new(hci_acl);
        let response = read_h4_packet(&mut file);
        assert!(&response.is_ok());
        assert_eq!(response.as_ref().unwrap().h4_type, H4_ACL_TYPE);
        assert_eq!(response.as_ref().unwrap().payload.len(), 21);
    }
}
