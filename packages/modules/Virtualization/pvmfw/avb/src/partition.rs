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

//! Struct and functions relating to well-known partition names.

use crate::error::AvbIOError;
use crate::utils::is_not_null;
use core::ffi::{c_char, CStr};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum PartitionName {
    /// The default `PartitionName` is needed to build the default `HashDescriptor`.
    #[default]
    Kernel,
    InitrdNormal,
    InitrdDebug,
}

impl PartitionName {
    pub(crate) const NUM_OF_KNOWN_PARTITIONS: usize = 3;

    const KERNEL_PARTITION_NAME: &[u8] = b"boot\0";
    const INITRD_NORMAL_PARTITION_NAME: &[u8] = b"initrd_normal\0";
    const INITRD_DEBUG_PARTITION_NAME: &[u8] = b"initrd_debug\0";

    pub(crate) fn as_cstr(&self) -> &CStr {
        CStr::from_bytes_with_nul(self.as_bytes()).unwrap()
    }

    fn as_non_null_terminated_bytes(&self) -> &[u8] {
        let partition_name = self.as_bytes();
        &partition_name[..partition_name.len() - 1]
    }

    fn as_bytes(&self) -> &[u8] {
        match self {
            Self::Kernel => Self::KERNEL_PARTITION_NAME,
            Self::InitrdNormal => Self::INITRD_NORMAL_PARTITION_NAME,
            Self::InitrdDebug => Self::INITRD_DEBUG_PARTITION_NAME,
        }
    }
}

impl TryFrom<*const c_char> for PartitionName {
    type Error = AvbIOError;

    fn try_from(partition_name: *const c_char) -> Result<Self, Self::Error> {
        is_not_null(partition_name)?;
        // SAFETY: It is safe as the raw pointer `partition_name` is a nonnull pointer.
        let partition_name = unsafe { CStr::from_ptr(partition_name) };
        partition_name.try_into()
    }
}

impl TryFrom<&CStr> for PartitionName {
    type Error = AvbIOError;

    fn try_from(partition_name: &CStr) -> Result<Self, Self::Error> {
        match partition_name.to_bytes_with_nul() {
            Self::KERNEL_PARTITION_NAME => Ok(Self::Kernel),
            Self::INITRD_NORMAL_PARTITION_NAME => Ok(Self::InitrdNormal),
            Self::INITRD_DEBUG_PARTITION_NAME => Ok(Self::InitrdDebug),
            _ => Err(AvbIOError::NoSuchPartition),
        }
    }
}

impl TryFrom<&[u8]> for PartitionName {
    type Error = AvbIOError;

    fn try_from(non_null_terminated_name: &[u8]) -> Result<Self, Self::Error> {
        match non_null_terminated_name {
            x if x == Self::Kernel.as_non_null_terminated_bytes() => Ok(Self::Kernel),
            x if x == Self::InitrdNormal.as_non_null_terminated_bytes() => Ok(Self::InitrdNormal),
            x if x == Self::InitrdDebug.as_non_null_terminated_bytes() => Ok(Self::InitrdDebug),
            _ => Err(AvbIOError::NoSuchPartition),
        }
    }
}
