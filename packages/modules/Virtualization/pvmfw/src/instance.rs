// Copyright 2023, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Support for reading and writing to the instance.img.

use crate::crypto;
use crate::crypto::hkdf_sh512;
use crate::crypto::AeadCtx;
use crate::dice::PartialInputs;
use crate::gpt;
use crate::gpt::Partition;
use crate::gpt::Partitions;
use crate::helpers::ceiling_div;
use crate::rand;
use crate::virtio::pci::VirtIOBlkIterator;
use core::fmt;
use core::mem::size_of;
use core::slice;
use diced_open_dice::DiceMode;
use diced_open_dice::Hash;
use diced_open_dice::Hidden;
use log::trace;
use uuid::Uuid;
use virtio_drivers::transport::pci::bus::PciRoot;

pub enum Error {
    /// Unexpected I/O error while accessing the underlying disk.
    FailedIo(gpt::Error),
    /// Failed to decrypt the entry.
    FailedOpen(crypto::ErrorIterator),
    /// Failed to generate a random salt to be stored.
    FailedSaltGeneration(rand::Error),
    /// Failed to encrypt the entry.
    FailedSeal(crypto::ErrorIterator),
    /// Impossible to create a new instance.img entry.
    InstanceImageFull,
    /// Badly formatted instance.img header block.
    InvalidInstanceImageHeader,
    /// No instance.img ("vm-instance") partition found.
    MissingInstanceImage,
    /// The instance.img doesn't contain a header.
    MissingInstanceImageHeader,
    /// Authority hash found in the pvmfw instance.img entry doesn't match the trusted public key.
    RecordedAuthHashMismatch,
    /// Code hash found in the pvmfw instance.img entry doesn't match the inputs.
    RecordedCodeHashMismatch,
    /// DICE mode found in the pvmfw instance.img entry doesn't match the current one.
    RecordedDiceModeMismatch,
    /// Size of the instance.img entry being read or written is not supported.
    UnsupportedEntrySize(usize),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Self::FailedIo(e) => write!(f, "Failed I/O to disk: {e}"),
            Self::FailedOpen(e_iter) => {
                writeln!(f, "Failed to open the instance.img partition:")?;
                for e in *e_iter {
                    writeln!(f, "\t{e}")?;
                }
                Ok(())
            }
            Self::FailedSaltGeneration(e) => write!(f, "Failed to generate salt: {e}"),
            Self::FailedSeal(e_iter) => {
                writeln!(f, "Failed to seal the instance.img partition:")?;
                for e in *e_iter {
                    writeln!(f, "\t{e}")?;
                }
                Ok(())
            }
            Self::InstanceImageFull => write!(f, "Failed to obtain a free instance.img partition"),
            Self::InvalidInstanceImageHeader => write!(f, "instance.img header is invalid"),
            Self::MissingInstanceImage => write!(f, "Failed to find the instance.img partition"),
            Self::MissingInstanceImageHeader => write!(f, "instance.img header is missing"),
            Self::RecordedAuthHashMismatch => write!(f, "Recorded authority hash doesn't match"),
            Self::RecordedCodeHashMismatch => write!(f, "Recorded code hash doesn't match"),
            Self::RecordedDiceModeMismatch => write!(f, "Recorded DICE mode doesn't match"),
            Self::UnsupportedEntrySize(sz) => write!(f, "Invalid entry size: {sz}"),
        }
    }
}

pub type Result<T> = core::result::Result<T, Error>;

pub fn get_or_generate_instance_salt(
    pci_root: &mut PciRoot,
    dice_inputs: &PartialInputs,
    secret: &[u8],
) -> Result<(bool, Hidden)> {
    let mut instance_img = find_instance_img(pci_root)?;

    let entry = locate_entry(&mut instance_img)?;
    trace!("Found pvmfw instance.img entry: {entry:?}");

    let key = hkdf_sh512::<32>(secret, /*salt=*/ &[], b"vm-instance");
    let mut blk = [0; BLK_SIZE];
    match entry {
        PvmfwEntry::Existing { header_index, payload_size } => {
            if payload_size > blk.len() {
                // We currently only support single-blk entries.
                return Err(Error::UnsupportedEntrySize(payload_size));
            }
            let payload_index = header_index + 1;
            instance_img.read_block(payload_index, &mut blk).map_err(Error::FailedIo)?;

            let payload = &blk[..payload_size];
            let mut entry = [0; size_of::<EntryBody>()];
            let key = key.map_err(Error::FailedOpen)?;
            let aead = AeadCtx::new_aes_256_gcm_randnonce(&key).map_err(Error::FailedOpen)?;
            let decrypted = aead.open(&mut entry, payload).map_err(Error::FailedOpen)?;

            let body: &EntryBody = decrypted.as_ref();
            if body.code_hash != dice_inputs.code_hash {
                Err(Error::RecordedCodeHashMismatch)
            } else if body.auth_hash != dice_inputs.auth_hash {
                Err(Error::RecordedAuthHashMismatch)
            } else if body.mode() != dice_inputs.mode {
                Err(Error::RecordedDiceModeMismatch)
            } else {
                Ok((false, body.salt))
            }
        }
        PvmfwEntry::New { header_index } => {
            let salt = rand::random_array().map_err(Error::FailedSaltGeneration)?;
            let entry_body = EntryBody::new(dice_inputs, &salt);
            let body = entry_body.as_ref();

            let key = key.map_err(Error::FailedSeal)?;
            let aead = AeadCtx::new_aes_256_gcm_randnonce(&key).map_err(Error::FailedSeal)?;
            // We currently only support single-blk entries.
            assert!(body.len() + aead.aead().unwrap().max_overhead() < blk.len());
            let encrypted = aead.seal(&mut blk, body).map_err(Error::FailedSeal)?;
            let payload_size = encrypted.len();
            let payload_index = header_index + 1;
            instance_img.write_block(payload_index, &blk).map_err(Error::FailedIo)?;

            let header = EntryHeader::new(PvmfwEntry::UUID, payload_size);
            let (blk_header, blk_rest) = blk.split_at_mut(size_of::<EntryHeader>());
            blk_header.copy_from_slice(header.as_ref());
            blk_rest.fill(0);
            instance_img.write_block(header_index, &blk).map_err(Error::FailedIo)?;

            Ok((true, salt))
        }
    }
}

#[repr(C, packed)]
struct Header {
    magic: [u8; Header::MAGIC.len()],
    version: u16,
}

impl Header {
    const MAGIC: &[u8] = b"Android-VM-instance";
    const VERSION_1: u16 = 1;

    pub fn is_valid(&self) -> bool {
        self.magic == Self::MAGIC && self.version() == Self::VERSION_1
    }

    fn version(&self) -> u16 {
        u16::from_le(self.version)
    }

    fn from_bytes(bytes: &[u8]) -> Option<&Self> {
        let header: &Self = bytes.as_ref();

        if header.is_valid() {
            Some(header)
        } else {
            None
        }
    }
}

impl AsRef<Header> for [u8] {
    fn as_ref(&self) -> &Header {
        // SAFETY - Assume that the alignement and size match Header.
        unsafe { &*self.as_ptr().cast::<Header>() }
    }
}

fn find_instance_img(pci_root: &mut PciRoot) -> Result<Partition> {
    for device in VirtIOBlkIterator::new(pci_root) {
        match Partition::get_by_name(device, "vm-instance") {
            Ok(Some(p)) => return Ok(p),
            Ok(None) => {}
            Err(e) => log::warn!("error while reading from disk: {e}"),
        };
    }

    Err(Error::MissingInstanceImage)
}

#[derive(Debug)]
enum PvmfwEntry {
    Existing { header_index: usize, payload_size: usize },
    New { header_index: usize },
}

const BLK_SIZE: usize = Partitions::LBA_SIZE;

impl PvmfwEntry {
    const UUID: Uuid = Uuid::from_u128(0x90d2174a038a4bc6adf3824848fc5825);
}

fn locate_entry(partition: &mut Partition) -> Result<PvmfwEntry> {
    let mut blk = [0; BLK_SIZE];
    let mut indices = partition.indices();
    let header_index = indices.next().ok_or(Error::MissingInstanceImageHeader)?;
    partition.read_block(header_index, &mut blk).map_err(Error::FailedIo)?;
    // The instance.img header is only used for discovery/validation.
    let _ = Header::from_bytes(&blk).ok_or(Error::InvalidInstanceImageHeader)?;

    while let Some(header_index) = indices.next() {
        partition.read_block(header_index, &mut blk).map_err(Error::FailedIo)?;

        let header: &EntryHeader = blk[..size_of::<EntryHeader>()].as_ref();
        match (header.uuid(), header.payload_size()) {
            (uuid, _) if uuid.is_nil() => return Ok(PvmfwEntry::New { header_index }),
            (PvmfwEntry::UUID, payload_size) => {
                return Ok(PvmfwEntry::Existing { header_index, payload_size })
            }
            (uuid, payload_size) => {
                trace!("Skipping instance.img entry {uuid}: {payload_size:?} bytes");
                let n = ceiling_div(payload_size, BLK_SIZE).unwrap();
                if n > 0 {
                    let _ = indices.nth(n - 1); // consume
                }
            }
        };
    }

    Err(Error::InstanceImageFull)
}

/// Marks the start of an instance.img entry.
///
/// Note: Virtualization/microdroid_manager/src/instance.rs uses the name "partition".
#[repr(C)]
struct EntryHeader {
    uuid: u128,
    payload_size: u64,
}

impl EntryHeader {
    fn new(uuid: Uuid, payload_size: usize) -> Self {
        Self { uuid: uuid.to_u128_le(), payload_size: u64::try_from(payload_size).unwrap().to_le() }
    }

    fn uuid(&self) -> Uuid {
        Uuid::from_u128_le(self.uuid)
    }

    fn payload_size(&self) -> usize {
        usize::try_from(u64::from_le(self.payload_size)).unwrap()
    }
}

impl AsRef<EntryHeader> for [u8] {
    fn as_ref(&self) -> &EntryHeader {
        assert_eq!(self.len(), size_of::<EntryHeader>());
        // SAFETY - The size of the slice was checked and any value may be considered valid.
        unsafe { &*self.as_ptr().cast::<EntryHeader>() }
    }
}

impl AsRef<[u8]> for EntryHeader {
    fn as_ref(&self) -> &[u8] {
        let s = self as *const Self;
        // SAFETY - Transmute the (valid) bytes into a slice.
        unsafe { slice::from_raw_parts(s.cast::<u8>(), size_of::<Self>()) }
    }
}

#[repr(C)]
struct EntryBody {
    code_hash: Hash,
    auth_hash: Hash,
    salt: Hidden,
    mode: u8,
}

impl EntryBody {
    fn new(dice_inputs: &PartialInputs, salt: &Hidden) -> Self {
        let mode = match dice_inputs.mode {
            DiceMode::kDiceModeNotInitialized => 0,
            DiceMode::kDiceModeNormal => 1,
            DiceMode::kDiceModeDebug => 2,
            DiceMode::kDiceModeMaintenance => 3,
        };

        Self {
            code_hash: dice_inputs.code_hash,
            auth_hash: dice_inputs.auth_hash,
            salt: *salt,
            mode,
        }
    }

    fn mode(&self) -> DiceMode {
        match self.mode {
            1 => DiceMode::kDiceModeNormal,
            2 => DiceMode::kDiceModeDebug,
            3 => DiceMode::kDiceModeMaintenance,
            _ => DiceMode::kDiceModeNotInitialized,
        }
    }
}

impl AsRef<EntryBody> for [u8] {
    fn as_ref(&self) -> &EntryBody {
        assert_eq!(self.len(), size_of::<EntryBody>());
        // SAFETY - The size of the slice was checked and members are validated by accessors.
        unsafe { &*self.as_ptr().cast::<EntryBody>() }
    }
}

impl AsRef<[u8]> for EntryBody {
    fn as_ref(&self) -> &[u8] {
        let s = self as *const Self;
        // SAFETY - Transmute the (valid) bytes into a slice.
        unsafe { slice::from_raw_parts(s.cast::<u8>(), size_of::<Self>()) }
    }
}
