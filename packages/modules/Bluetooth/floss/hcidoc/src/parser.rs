//! Parsing of various Bluetooth packets.
use chrono::NaiveDateTime;
use num_traits::cast::FromPrimitive;
use std::convert::TryFrom;
use std::fs::File;
use std::io::{Error, ErrorKind, Read, Seek};

use bt_packets::hci::{AclPacket, CommandPacket, EventPacket};

/// Linux snoop file header format. This format is used by `btmon` on Linux systems that have bluez
/// installed.
#[derive(Clone, Copy, Debug)]
pub struct LinuxSnoopHeader {
    id: [u8; 8],
    version: u32,
    data_type: u32,
}

/// Identifier for a Linux snoop file. In ASCII, this is 'btsnoop\0'.
const LINUX_SNOOP_MAGIC: [u8; 8] = [0x62, 0x74, 0x73, 0x6e, 0x6f, 0x6f, 0x70, 0x00];

/// Snoop files in monitor format will have this value in link type.
const LINUX_SNOOP_MONITOR_TYPE: u32 = 2001;

/// Size of snoop header. 8 bytes for magic and another 8 for additional info.
const LINUX_SNOOP_HEADER_SIZE: usize = 16;

impl TryFrom<&[u8]> for LinuxSnoopHeader {
    type Error = String;

    fn try_from(item: &[u8]) -> Result<Self, Self::Error> {
        if item.len() != LINUX_SNOOP_HEADER_SIZE {
            return Err(format!("Invalid size for snoop header: {}", item.len()));
        }

        let rest = item;
        let (id_bytes, rest) = rest.split_at(8);
        let (version_bytes, rest) = rest.split_at(std::mem::size_of::<u32>());
        let (data_type_bytes, _rest) = rest.split_at(std::mem::size_of::<u32>());

        let header = LinuxSnoopHeader {
            id: id_bytes.try_into().unwrap(),
            version: u32::from_be_bytes(version_bytes.try_into().unwrap()),
            data_type: u32::from_be_bytes(data_type_bytes.try_into().unwrap()),
        };

        if header.id != LINUX_SNOOP_MAGIC {
            return Err(format!("Id is not 'btsnoop'."));
        }

        if header.version != 1 {
            return Err(format!("Version is not supported. Got {}.", header.version));
        }

        if header.data_type != LINUX_SNOOP_MONITOR_TYPE {
            return Err(format!(
                "Invalid data type in snoop file. We want monitor type ({}) but got {}",
                LINUX_SNOOP_MONITOR_TYPE, header.data_type
            ));
        }

        Ok(header)
    }
}

/// Opcodes for Linux snoop packets.
#[derive(Debug, FromPrimitive, ToPrimitive)]
#[repr(u16)]
pub enum LinuxSnoopOpcodes {
    NewIndex = 0,
    DeleteIndex,
    CommandPacket,
    EventPacket,
    AclTxPacket,
    AclRxPacket,
    ScoTxPacket,
    ScoRxPacket,
    OpenIndex,
    CloseIndex,
    IndexInfo,
    VendorDiag,
    SystemNote,
    UserLogging,
    CtrlOpen,
    CtrlClose,
    CtrlCommand,
    CtrlEvent,
    IsoTx,
    IsoRx,

    Invalid = 0xffff,
}

/// Linux snoop file packet format.
#[derive(Debug, Clone)]
pub struct LinuxSnoopPacket {
    /// The original length of the captured packet as received via a network.
    pub original_length: u32,

    /// The length of the included data (can be smaller than original_length if
    /// the received packet was truncated).
    pub included_length: u32,
    pub flags: u32,
    pub drops: u32,
    pub timestamp_magic_us: u64,
    pub data: Vec<u8>,
}

impl LinuxSnoopPacket {
    pub fn adapter_index(&self) -> u16 {
        (self.flags >> 16).try_into().unwrap_or(0u16)
    }

    pub fn opcode(&self) -> LinuxSnoopOpcodes {
        LinuxSnoopOpcodes::from_u32(self.flags & 0xffff).unwrap_or(LinuxSnoopOpcodes::Invalid)
    }
}

/// Size of packet preamble (everything except the data).
const LINUX_SNOOP_PACKET_PREAMBLE_SIZE: usize = 24;

/// Maximum packet size for snoop is the max ACL size + 4 bytes.
const LINUX_SNOOP_MAX_PACKET_SIZE: usize = 1486 + 4;

/// Number of seconds from the year 1970 to the year 2000.
const LINUX_SNOOP_Y2K_OFFSET_IN_SECS: i64 = 946684800i64;

/// Snoop timestamps start at year 0 instead of 1970 like unix timestamps. This
/// offset is used to represent Jan 1, 2000 AD and can be used to convert back
/// to unixtime.
const LINUX_SNOOP_Y2K_EPOCH_USECS: i64 = 0x00E03AB44A676000i64;

/// Microseconds to seconds.
const USECS_TO_SECS: i64 = 1_000_000i64;

/// Offset from the snoop timestamp to unixtimestamp in seconds. This is a negative number.
const LINUX_SNOOP_OFFSET_TO_UNIXTIME_SECS: i64 =
    LINUX_SNOOP_Y2K_OFFSET_IN_SECS - (LINUX_SNOOP_Y2K_EPOCH_USECS / USECS_TO_SECS);

// Expect specifically the pre-amble to be read here (and no data).
impl TryFrom<&[u8]> for LinuxSnoopPacket {
    type Error = String;

    fn try_from(item: &[u8]) -> Result<Self, Self::Error> {
        if item.len() != LINUX_SNOOP_PACKET_PREAMBLE_SIZE {
            return Err(format!("Wrong size for snoop packet preamble: {}", item.len()));
        }

        let rest = item;
        let (orig_len_bytes, rest) = rest.split_at(std::mem::size_of::<u32>());
        let (included_len_bytes, rest) = rest.split_at(std::mem::size_of::<u32>());
        let (flags_bytes, rest) = rest.split_at(std::mem::size_of::<u32>());
        let (drops_bytes, rest) = rest.split_at(std::mem::size_of::<u32>());
        let (ts_bytes, _rest) = rest.split_at(std::mem::size_of::<u64>());

        // Note that all bytes are in big-endian because they're network order.
        let packet = LinuxSnoopPacket {
            original_length: u32::from_be_bytes(orig_len_bytes.try_into().unwrap()),
            included_length: u32::from_be_bytes(included_len_bytes.try_into().unwrap()),
            flags: u32::from_be_bytes(flags_bytes.try_into().unwrap()),
            drops: u32::from_be_bytes(drops_bytes.try_into().unwrap()),
            timestamp_magic_us: u64::from_be_bytes(ts_bytes.try_into().unwrap()),
            data: vec![],
        };

        Ok(packet)
    }
}

/// Reader for Linux snoop files.
pub struct LinuxSnoopReader<'a> {
    fd: &'a File,
}

impl<'a> LinuxSnoopReader<'a> {
    fn new(fd: &'a File) -> Self {
        LinuxSnoopReader { fd }
    }
}

impl<'a> Iterator for LinuxSnoopReader<'a> {
    type Item = LinuxSnoopPacket;

    fn next(&mut self) -> Option<Self::Item> {
        let mut data = [0u8; LINUX_SNOOP_PACKET_PREAMBLE_SIZE];
        let bytes = match self.fd.read(&mut data) {
            Ok(b) => b,
            Err(e) => {
                // |UnexpectedEof| could be seen since we're trying to read more
                // data than is available (i.e. end of file).
                if e.kind() != ErrorKind::UnexpectedEof {
                    eprintln!("Error reading snoop file: {:?}", e);
                }
                return None;
            }
        };

        match LinuxSnoopPacket::try_from(&data[0..bytes]) {
            Ok(mut p) => {
                if p.included_length > 0 {
                    let size: usize = p.included_length.try_into().unwrap();
                    let mut rem_data = [0u8; LINUX_SNOOP_MAX_PACKET_SIZE];
                    match self.fd.read(&mut rem_data[0..size]) {
                        Ok(b) => {
                            if b != size {
                                eprintln!(
                                    "Size({}) doesn't match bytes read({}). Aborting...",
                                    size, b
                                );
                                return None;
                            }

                            p.data = rem_data[0..b].to_vec();
                            Some(p)
                        }
                        Err(e) => {
                            eprintln!("Couldn't read any packet data: {}", e);
                            None
                        }
                    }
                } else {
                    Some(p)
                }
            }
            Err(_) => None,
        }
    }
}

/// What kind of log file is this?
#[derive(Clone, Debug)]
pub enum LogType {
    /// Linux snoop file generated by something like `btmon`.
    LinuxSnoop(LinuxSnoopHeader),
}

/// Parses different Bluetooth log types.
pub struct LogParser {
    fd: File,
    log_type: Option<LogType>,
}

impl<'a> LogParser {
    pub fn new(filepath: &str) -> std::io::Result<Self> {
        Ok(Self { fd: File::open(filepath)?, log_type: None })
    }

    /// Check the log file type for the current log file. This rewinds the position of the file.
    /// For a non-intrusive query, use |get_log_type|.
    pub fn read_log_type(&mut self) -> std::io::Result<LogType> {
        let mut buf = [0; LINUX_SNOOP_HEADER_SIZE];

        // First rewind to start of the file.
        self.fd.rewind()?;
        let bytes = self.fd.read(&mut buf)?;

        if let Ok(header) = LinuxSnoopHeader::try_from(&buf[0..bytes]) {
            let log_type = LogType::LinuxSnoop(header);
            self.log_type = Some(log_type.clone());
            Ok(log_type)
        } else {
            Err(Error::new(ErrorKind::Other, "Unsupported log file type"))
        }
    }

    /// Get cached log type. To initially read the log type, use |read_log_type|.
    pub fn get_log_type(&self) -> Option<LogType> {
        self.log_type.clone()
    }

    pub fn get_snoop_iterator(&mut self) -> Option<LinuxSnoopReader> {
        // Limit to LinuxSnoop files.
        if !matches!(self.get_log_type()?, LogType::LinuxSnoop(_)) {
            return None;
        }

        Some(LinuxSnoopReader::new(&mut self.fd))
    }
}

/// Data owned by a packet.
#[derive(Debug, Clone)]
pub enum PacketChild {
    HciCommand(CommandPacket),
    HciEvent(EventPacket),
    AclTx(AclPacket),
    AclRx(AclPacket),
}

impl<'a> TryFrom<&'a LinuxSnoopPacket> for PacketChild {
    type Error = String;

    fn try_from(item: &'a LinuxSnoopPacket) -> Result<Self, Self::Error> {
        match item.opcode() {
            LinuxSnoopOpcodes::CommandPacket => match CommandPacket::parse(item.data.as_slice()) {
                Ok(command) => Ok(PacketChild::HciCommand(command)),
                Err(e) => Err(format!("Couldn't parse command: {:?}", e)),
            },

            LinuxSnoopOpcodes::EventPacket => match EventPacket::parse(item.data.as_slice()) {
                Ok(event) => Ok(PacketChild::HciEvent(event)),
                Err(e) => Err(format!("Couldn't parse event: {:?}", e)),
            },

            LinuxSnoopOpcodes::AclTxPacket => match AclPacket::parse(item.data.as_slice()) {
                Ok(data) => Ok(PacketChild::AclTx(data)),
                Err(e) => Err(format!("Couldn't parse acl tx: {:?}", e)),
            },

            LinuxSnoopOpcodes::AclRxPacket => match AclPacket::parse(item.data.as_slice()) {
                Ok(data) => Ok(PacketChild::AclRx(data)),
                Err(e) => Err(format!("Couldn't parse acl rx: {:?}", e)),
            },

            // TODO(b/262928525) - Add packet handlers for more packet types.
            _ => Err(format!("Unhandled packet opcode: {:?}", item.opcode())),
        }
    }
}

/// A single processable packet of data.
#[derive(Debug, Clone)]
pub struct Packet {
    /// Timestamp of this packet
    pub ts: NaiveDateTime,

    /// Which adapter this packet is for. Unassociated packets should use 0xFFFE.
    pub adapter_index: u16,

    /// Packet number in current stream.
    pub index: usize,

    /// Inner data for this packet.
    pub inner: PacketChild,
}

impl<'a> TryFrom<(usize, &'a LinuxSnoopPacket)> for Packet {
    type Error = String;

    fn try_from(item: (usize, &'a LinuxSnoopPacket)) -> Result<Self, Self::Error> {
        let (index, packet) = item;
        match PacketChild::try_from(packet) {
            Ok(inner) => {
                let base_ts = i64::try_from(packet.timestamp_magic_us)
                    .map_err(|e| format!("u64 conversion error: {}", e))?;

                let ts_secs = (base_ts / USECS_TO_SECS) + LINUX_SNOOP_OFFSET_TO_UNIXTIME_SECS;
                let ts_nsecs = u32::try_from((base_ts % USECS_TO_SECS) * 1000).unwrap_or(0);
                let ts = NaiveDateTime::from_timestamp_opt(ts_secs, ts_nsecs)
                    .ok_or(format!("timestamp conversion error: {}", base_ts))?;
                let adapter_index = packet.adapter_index();

                Ok(Packet { ts, adapter_index, index, inner })
            }

            Err(e) => Err(e),
        }
    }
}
