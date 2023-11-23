// Copyright 2022, The Android Open Source Project
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

//! Support for the pvmfw configuration data format.

use crate::helpers;
use core::fmt;
use core::mem;
use core::ops::Range;
use core::result;
use zerocopy::{FromBytes, LayoutVerified};

/// Configuration data header.
#[repr(C, packed)]
#[derive(Clone, Copy, Debug, FromBytes)]
struct Header {
    /// Magic number; must be `Header::MAGIC`.
    magic: u32,
    /// Version of the header format.
    version: u32,
    /// Total size of the configuration data.
    total_size: u32,
    /// Feature flags; currently reserved and must be zero.
    flags: u32,
    /// (offset, size) pairs used to locate individual entries appended to the header.
    entries: [HeaderEntry; Entry::COUNT],
}

#[derive(Debug)]
pub enum Error {
    /// Reserved region can't fit configuration header.
    BufferTooSmall,
    /// Header has the wrong alignment
    HeaderMisaligned,
    /// Header doesn't contain the expect magic value.
    InvalidMagic,
    /// Version of the header isn't supported.
    UnsupportedVersion(u16, u16),
    /// Header sets flags incorrectly or uses reserved flags.
    InvalidFlags(u32),
    /// Header describes configuration data that doesn't fit in the expected buffer.
    InvalidSize(usize),
    /// Header entry is missing.
    MissingEntry(Entry),
    /// Header entry is invalid.
    InvalidEntry(Entry, EntryError),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Self::BufferTooSmall => write!(f, "Reserved region is smaller than config header"),
            Self::HeaderMisaligned => write!(f, "Reserved region is misaligned"),
            Self::InvalidMagic => write!(f, "Wrong magic number"),
            Self::UnsupportedVersion(x, y) => write!(f, "Version {x}.{y} not supported"),
            Self::InvalidFlags(v) => write!(f, "Flags value {v:#x} is incorrect or reserved"),
            Self::InvalidSize(sz) => write!(f, "Total size ({sz:#x}) overflows reserved region"),
            Self::MissingEntry(entry) => write!(f, "Mandatory {entry:?} entry is missing"),
            Self::InvalidEntry(entry, e) => write!(f, "Invalid {entry:?} entry: {e}"),
        }
    }
}

pub type Result<T> = result::Result<T, Error>;

#[derive(Debug)]
pub enum EntryError {
    /// Offset isn't between the fixed minimum value and size of configuration data.
    InvalidOffset(usize),
    /// Size must be zero when offset is and not be when it isn't.
    InvalidSize(usize),
    /// Entry isn't fully within the configuration data structure.
    OutOfBounds { offset: usize, size: usize, limit: usize },
}

impl fmt::Display for EntryError {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Self::InvalidOffset(offset) => write!(f, "Invalid offset: {offset:#x?}"),
            Self::InvalidSize(sz) => write!(f, "Invalid size: {sz:#x?}"),
            Self::OutOfBounds { offset, size, limit } => {
                let range = Header::PADDED_SIZE..*limit;
                let entry = *offset..(*offset + *size);
                write!(f, "Out of bounds: {entry:#x?} must be within range {range:#x?}")
            }
        }
    }
}

impl Header {
    const MAGIC: u32 = u32::from_ne_bytes(*b"pvmf");
    const VERSION_1_0: u32 = Self::version(1, 0);
    const PADDED_SIZE: usize =
        helpers::unchecked_align_up(mem::size_of::<Self>(), mem::size_of::<u64>());

    pub const fn version(major: u16, minor: u16) -> u32 {
        ((major as u32) << 16) | (minor as u32)
    }

    pub const fn version_tuple(&self) -> (u16, u16) {
        ((self.version >> 16) as u16, self.version as u16)
    }

    pub fn total_size(&self) -> usize {
        self.total_size as usize
    }

    pub fn body_size(&self) -> usize {
        self.total_size() - Self::PADDED_SIZE
    }

    fn get_body_range(&self, entry: Entry) -> Result<Option<Range<usize>>> {
        let e = self.entries[entry as usize];
        let offset = e.offset as usize;
        let size = e.size as usize;

        match self._get_body_range(offset, size) {
            Ok(r) => Ok(r),
            Err(EntryError::InvalidSize(0)) => {
                // As our bootloader currently uses this (non-compliant) case, permit it for now.
                log::warn!("Config entry {entry:?} uses non-zero offset with zero size");
                // TODO(b/262181812): Either make this case valid or fix the bootloader.
                Ok(None)
            }
            Err(e) => Err(Error::InvalidEntry(entry, e)),
        }
    }

    fn _get_body_range(
        &self,
        offset: usize,
        size: usize,
    ) -> result::Result<Option<Range<usize>>, EntryError> {
        match (offset, size) {
            (0, 0) => Ok(None),
            (0, size) | (_, size @ 0) => Err(EntryError::InvalidSize(size)),
            _ => {
                let start = offset
                    .checked_sub(Header::PADDED_SIZE)
                    .ok_or(EntryError::InvalidOffset(offset))?;
                let end = start
                    .checked_add(size)
                    .filter(|x| *x <= self.body_size())
                    .ok_or(EntryError::OutOfBounds { offset, size, limit: self.total_size() })?;

                Ok(Some(start..end))
            }
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub enum Entry {
    Bcc = 0,
    DebugPolicy = 1,
}

impl Entry {
    const COUNT: usize = 2;
}

#[repr(packed)]
#[derive(Clone, Copy, Debug, FromBytes)]
struct HeaderEntry {
    offset: u32,
    size: u32,
}

#[derive(Debug)]
pub struct Config<'a> {
    body: &'a mut [u8],
    bcc_range: Range<usize>,
    dp_range: Option<Range<usize>>,
}

impl<'a> Config<'a> {
    /// Take ownership of a pvmfw configuration consisting of its header and following entries.
    ///
    /// SAFETY - 'data' should respect the alignment of Header.
    pub unsafe fn new(data: &'a mut [u8]) -> Result<Self> {
        let header = data.get(..Header::PADDED_SIZE).ok_or(Error::BufferTooSmall)?;

        let (header, _) =
            LayoutVerified::<_, Header>::new_from_prefix(header).ok_or(Error::HeaderMisaligned)?;
        let header = header.into_ref();

        if header.magic != Header::MAGIC {
            return Err(Error::InvalidMagic);
        }

        if header.version != Header::VERSION_1_0 {
            let (major, minor) = header.version_tuple();
            return Err(Error::UnsupportedVersion(major, minor));
        }

        if header.flags != 0 {
            return Err(Error::InvalidFlags(header.flags));
        }

        let bcc_range =
            header.get_body_range(Entry::Bcc)?.ok_or(Error::MissingEntry(Entry::Bcc))?;
        let dp_range = header.get_body_range(Entry::DebugPolicy)?;

        let body_size = header.body_size();
        let total_size = header.total_size();
        let body = data
            .get_mut(Header::PADDED_SIZE..)
            .ok_or(Error::BufferTooSmall)?
            .get_mut(..body_size)
            .ok_or(Error::InvalidSize(total_size))?;

        Ok(Self { body, bcc_range, dp_range })
    }

    /// Get slice containing the platform BCC.
    pub fn get_entries(&mut self) -> (&mut [u8], Option<&mut [u8]>) {
        let bcc_start = self.bcc_range.start;
        let bcc_end = self.bcc_range.len();
        let (_, rest) = self.body.split_at_mut(bcc_start);
        let (bcc, rest) = rest.split_at_mut(bcc_end);

        let dp = if let Some(dp_range) = &self.dp_range {
            let dp_start = dp_range.start.checked_sub(self.bcc_range.end).unwrap();
            let dp_end = dp_range.len();
            let (_, rest) = rest.split_at_mut(dp_start);
            let (dp, _) = rest.split_at_mut(dp_end);
            Some(dp)
        } else {
            None
        };

        (bcc, dp)
    }
}
