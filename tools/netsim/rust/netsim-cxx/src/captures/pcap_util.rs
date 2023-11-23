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

use std::{
    fs::File,
    io::{Result, Write},
    time::Duration,
};
macro_rules! be_vec {
    ( $( $x:expr ),* ) => {
         Vec::<u8>::new().iter().copied()
         $( .chain($x.to_be_bytes()) )*
         .collect()
       };
    }

pub enum PacketDirection {
    HostToController = 0,
    ControllerToHost = 1,
}

pub fn write_pcap_header(output: &mut File) -> Result<usize> {
    let linktype: u32 = 201; // LINKTYPE_BLUETOOTH_HCI_H4_WITH_PHDR

    // https://tools.ietf.org/id/draft-gharris-opsawg-pcap-00.html#name-file-header
    let header: Vec<u8> = be_vec![
        0xa1b2c3d4u32, // magic number
        2u16,          // major version
        4u16,          // minor version
        0u32,          // reserved 1
        0u32,          // reserved 2
        u32::MAX,      // snaplen
        linktype
    ];

    output.write_all(&header)?;
    Ok(header.len())
}

pub fn append_record(
    timestamp: Duration,
    output: &mut File,
    packet_direction: PacketDirection,
    packet_type: u32,
    packet: &[u8],
) -> Result<usize> {
    // Record (direciton, type, packet)
    let record: Vec<u8> = be_vec![packet_direction as u32, packet_type as u8];

    // https://tools.ietf.org/id/draft-gharris-opsawg-pcap-00.html#name-packet-record
    let length = record.len() + packet.len();
    let header: Vec<u8> = be_vec![
        timestamp.as_secs() as u32, // seconds
        timestamp.subsec_micros(),  // microseconds
        length as u32,              // Captured Packet Length
        length as u32               // Original Packet Length
    ];
    let mut bytes = Vec::<u8>::with_capacity(header.len() + length);
    bytes.extend(&header);
    bytes.extend(&record);
    bytes.extend(packet);
    output.write_all(&bytes)?;
    output.flush()?;
    Ok(header.len() + length)
}

#[cfg(test)]
mod tests {
    use std::{fs::File, io::Read, time::Duration};

    use crate::captures::pcap_util::{append_record, PacketDirection};

    use super::write_pcap_header;

    static EXPECTED: &[u8; 76] = include_bytes!("sample.pcap");

    #[test]
    /// The test is done with the golden file sample.pcap with following packets:
    /// Packet 1: HCI_EVT from Controller to Host (Sent Command Complete (LE Set Advertise Enable))
    /// Packet 2: HCI_CMD from Host to Controller (Rcvd LE Set Advertise Enable) [250 milisecs later]
    fn test_pcap_file() {
        let mut temp_dir = std::env::temp_dir();
        temp_dir.push("test.pcap");
        if let Ok(mut file) = File::create(temp_dir.clone()) {
            write_pcap_header(&mut file).unwrap();
            append_record(
                Duration::from_secs(0),
                &mut file,
                PacketDirection::HostToController,
                4u32,
                &[14, 4, 1, 10, 32, 0],
            )
            .unwrap();
            append_record(
                Duration::from_millis(250),
                &mut file,
                PacketDirection::ControllerToHost,
                1u32,
                &[10, 32, 1, 0],
            )
            .unwrap();
        } else {
            panic!("Cannot create temp file")
        }
        if let Ok(mut file) = File::open(temp_dir) {
            let mut buffer = [0u8; 76];
            #[allow(clippy::unused_io_amount)]
            {
                file.read(&mut buffer).unwrap();
            }
            assert_eq!(&buffer, EXPECTED);
        } else {
            panic!("Cannot create temp file")
        }
    }
}
