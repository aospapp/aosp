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

//! This module handles the pvmfw payload verification.

use crate::descriptor::{Digest, HashDescriptors};
use crate::error::AvbSlotVerifyError;
use crate::ops::{Ops, Payload};
use crate::partition::PartitionName;
use avb_bindgen::{AvbPartitionData, AvbVBMetaData};
use core::ffi::c_char;

/// Verified data returned when the payload verification succeeds.
#[derive(Debug, PartialEq, Eq)]
pub struct VerifiedBootData<'a> {
    /// DebugLevel of the VM.
    pub debug_level: DebugLevel,
    /// Kernel digest.
    pub kernel_digest: Digest,
    /// Initrd digest if initrd exists.
    pub initrd_digest: Option<Digest>,
    /// Trusted public key.
    pub public_key: &'a [u8],
}

/// This enum corresponds to the `DebugLevel` in `VirtualMachineConfig`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DebugLevel {
    /// Not debuggable at all.
    None,
    /// Fully debuggable.
    Full,
}

fn verify_only_one_vbmeta_exists(
    vbmeta_images: &[AvbVBMetaData],
) -> Result<(), AvbSlotVerifyError> {
    if vbmeta_images.len() == 1 {
        Ok(())
    } else {
        Err(AvbSlotVerifyError::InvalidMetadata)
    }
}

fn verify_vbmeta_is_from_kernel_partition(
    vbmeta_image: &AvbVBMetaData,
) -> Result<(), AvbSlotVerifyError> {
    match (vbmeta_image.partition_name as *const c_char).try_into() {
        Ok(PartitionName::Kernel) => Ok(()),
        _ => Err(AvbSlotVerifyError::InvalidMetadata),
    }
}

fn verify_vbmeta_has_only_one_hash_descriptor(
    hash_descriptors: &HashDescriptors,
) -> Result<(), AvbSlotVerifyError> {
    if hash_descriptors.len() == 1 {
        Ok(())
    } else {
        Err(AvbSlotVerifyError::InvalidMetadata)
    }
}

fn verify_loaded_partition_has_expected_length(
    loaded_partitions: &[AvbPartitionData],
    partition_name: PartitionName,
    expected_len: usize,
) -> Result<(), AvbSlotVerifyError> {
    if loaded_partitions.len() != 1 {
        // Only one partition should be loaded in each verify result.
        return Err(AvbSlotVerifyError::Io);
    }
    let loaded_partition = loaded_partitions[0];
    if !PartitionName::try_from(loaded_partition.partition_name as *const c_char)
        .map_or(false, |p| p == partition_name)
    {
        // Only the requested partition should be loaded.
        return Err(AvbSlotVerifyError::Io);
    }
    if loaded_partition.data_size == expected_len {
        Ok(())
    } else {
        Err(AvbSlotVerifyError::Verification)
    }
}

/// Verifies the payload (signed kernel + initrd) against the trusted public key.
pub fn verify_payload<'a>(
    kernel: &[u8],
    initrd: Option<&[u8]>,
    trusted_public_key: &'a [u8],
) -> Result<VerifiedBootData<'a>, AvbSlotVerifyError> {
    let mut payload = Payload::new(kernel, initrd, trusted_public_key);
    let mut ops = Ops::from(&mut payload);
    let kernel_verify_result = ops.verify_partition(PartitionName::Kernel.as_cstr())?;

    let vbmeta_images = kernel_verify_result.vbmeta_images()?;
    verify_only_one_vbmeta_exists(vbmeta_images)?;
    let vbmeta_image = vbmeta_images[0];
    verify_vbmeta_is_from_kernel_partition(&vbmeta_image)?;
    // SAFETY: It is safe because the `vbmeta_image` is collected from `AvbSlotVerifyData`,
    // which is returned by `avb_slot_verify()` when the verification succeeds. It is
    // guaranteed by libavb to be non-null and to point to a valid VBMeta structure.
    let hash_descriptors = unsafe { HashDescriptors::from_vbmeta(vbmeta_image)? };
    let kernel_descriptor = hash_descriptors.find(PartitionName::Kernel)?;

    if initrd.is_none() {
        verify_vbmeta_has_only_one_hash_descriptor(&hash_descriptors)?;
        return Ok(VerifiedBootData {
            debug_level: DebugLevel::None,
            kernel_digest: kernel_descriptor.digest,
            initrd_digest: None,
            public_key: trusted_public_key,
        });
    }

    let initrd = initrd.unwrap();
    let (debug_level, initrd_verify_result, initrd_partition_name) =
        if let Ok(result) = ops.verify_partition(PartitionName::InitrdNormal.as_cstr()) {
            (DebugLevel::None, result, PartitionName::InitrdNormal)
        } else if let Ok(result) = ops.verify_partition(PartitionName::InitrdDebug.as_cstr()) {
            (DebugLevel::Full, result, PartitionName::InitrdDebug)
        } else {
            return Err(AvbSlotVerifyError::Verification);
        };
    let loaded_partitions = initrd_verify_result.loaded_partitions()?;
    verify_loaded_partition_has_expected_length(
        loaded_partitions,
        initrd_partition_name,
        initrd.len(),
    )?;
    let initrd_descriptor = hash_descriptors.find(initrd_partition_name)?;
    Ok(VerifiedBootData {
        debug_level,
        kernel_digest: kernel_descriptor.digest,
        initrd_digest: Some(initrd_descriptor.digest),
        public_key: trusted_public_key,
    })
}
