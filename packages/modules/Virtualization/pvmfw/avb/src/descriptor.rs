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

//! Structs and functions relating to the descriptors.

use crate::error::{AvbIOError, AvbSlotVerifyError};
use crate::partition::PartitionName;
use crate::utils::{self, is_not_null, to_nonnull, to_usize, usize_checked_add};
use avb_bindgen::{
    avb_descriptor_foreach, avb_hash_descriptor_validate_and_byteswap, AvbDescriptor,
    AvbHashDescriptor, AvbVBMetaData, AVB_SHA256_DIGEST_SIZE,
};
use core::{
    ffi::c_void,
    mem::{size_of, MaybeUninit},
    ops::Range,
    slice,
};
use tinyvec::ArrayVec;

/// Digest type for kernel and initrd.
pub type Digest = [u8; AVB_SHA256_DIGEST_SIZE as usize];

/// `HashDescriptors` can have maximum one `HashDescriptor` per known partition.
#[derive(Default)]
pub(crate) struct HashDescriptors(
    ArrayVec<[HashDescriptor; PartitionName::NUM_OF_KNOWN_PARTITIONS]>,
);

impl HashDescriptors {
    /// Builds `HashDescriptors` from `AvbVBMetaData`.
    /// Returns an error if the given `AvbVBMetaData` contains non-hash descriptor, hash
    /// descriptor of unknown `PartitionName` or duplicated hash descriptors.
    ///
    /// # Safety
    ///
    /// Behavior is undefined if any of the following conditions are violated:
    /// * `vbmeta.vbmeta_data` must be non-null and points to a valid VBMeta.
    /// * `vbmeta.vbmeta_data` must be valid for reading `vbmeta.vbmeta_size` bytes.
    pub(crate) unsafe fn from_vbmeta(vbmeta: AvbVBMetaData) -> Result<Self, AvbSlotVerifyError> {
        is_not_null(vbmeta.vbmeta_data).map_err(|_| AvbSlotVerifyError::Io)?;
        let mut descriptors = Self::default();
        // SAFETY: It is safe as the raw pointer `vbmeta.vbmeta_data` is a non-null pointer and
        // points to a valid VBMeta structure.
        if !unsafe {
            avb_descriptor_foreach(
                vbmeta.vbmeta_data,
                vbmeta.vbmeta_size,
                Some(check_and_save_descriptor),
                &mut descriptors as *mut _ as *mut c_void,
            )
        } {
            return Err(AvbSlotVerifyError::InvalidMetadata);
        }
        Ok(descriptors)
    }

    fn push(&mut self, descriptor: HashDescriptor) -> utils::Result<()> {
        if self.0.iter().any(|d| d.partition_name_eq(&descriptor)) {
            return Err(AvbIOError::Io);
        }
        self.0.push(descriptor);
        Ok(())
    }

    pub(crate) fn len(&self) -> usize {
        self.0.len()
    }

    /// Finds the `HashDescriptor` for the given `PartitionName`.
    /// Throws an error if no corresponding descriptor found.
    pub(crate) fn find(
        &self,
        partition_name: PartitionName,
    ) -> Result<&HashDescriptor, AvbSlotVerifyError> {
        self.0
            .iter()
            .find(|d| d.partition_name == partition_name)
            .ok_or(AvbSlotVerifyError::InvalidMetadata)
    }
}

/// # Safety
///
/// Behavior is undefined if any of the following conditions are violated:
/// * The `descriptor` pointer must be non-null and points to a valid `AvbDescriptor` struct.
/// * The `user_data` pointer must be non-null and points to a valid `HashDescriptors` struct.
unsafe extern "C" fn check_and_save_descriptor(
    descriptor: *const AvbDescriptor,
    user_data: *mut c_void,
) -> bool {
    // SAFETY: It is safe because the caller must ensure that the `descriptor` pointer and
    // the `user_data` are non-null and valid.
    unsafe { try_check_and_save_descriptor(descriptor, user_data).is_ok() }
}

/// # Safety
///
/// Behavior is undefined if any of the following conditions are violated:
/// * The `descriptor` pointer must be non-null and points to a valid `AvbDescriptor` struct.
/// * The `user_data` pointer must be non-null and points to a valid `HashDescriptors` struct.
unsafe fn try_check_and_save_descriptor(
    descriptor: *const AvbDescriptor,
    user_data: *mut c_void,
) -> utils::Result<()> {
    is_not_null(descriptor)?;
    // SAFETY: It is safe because the caller ensures that `descriptor` is a non-null pointer
    // pointing to a valid struct.
    let desc = unsafe { AvbHashDescriptorWrap::from_descriptor_ptr(descriptor)? };
    // SAFETY: It is safe because the caller ensures that `descriptor` is a non-null pointer
    // pointing to a valid struct.
    let data = unsafe { slice::from_raw_parts(descriptor as *const u8, desc.len()?) };
    let mut descriptors = to_nonnull(user_data as *mut HashDescriptors)?;
    // SAFETY: It is safe because the caller ensures that `user_data` is a non-null pointer
    // pointing to a valid struct.
    let descriptors = unsafe { descriptors.as_mut() };
    descriptors.push(HashDescriptor::new(&desc, data)?)
}

#[derive(Default)]
pub(crate) struct HashDescriptor {
    partition_name: PartitionName,
    pub(crate) digest: Digest,
}

impl HashDescriptor {
    fn new(desc: &AvbHashDescriptorWrap, data: &[u8]) -> utils::Result<Self> {
        let partition_name = data
            .get(desc.partition_name_range()?)
            .ok_or(AvbIOError::RangeOutsidePartition)?
            .try_into()?;
        let partition_digest =
            data.get(desc.digest_range()?).ok_or(AvbIOError::RangeOutsidePartition)?;
        let mut digest = [0u8; size_of::<Digest>()];
        digest.copy_from_slice(partition_digest);
        Ok(Self { partition_name, digest })
    }

    fn partition_name_eq(&self, other: &HashDescriptor) -> bool {
        self.partition_name == other.partition_name
    }
}

/// `AvbHashDescriptor` contains the metadata for the given descriptor.
struct AvbHashDescriptorWrap(AvbHashDescriptor);

impl AvbHashDescriptorWrap {
    /// # Safety
    ///
    /// Behavior is undefined if any of the following conditions are violated:
    /// * The `descriptor` pointer must be non-null and point to a valid `AvbDescriptor`.
    unsafe fn from_descriptor_ptr(descriptor: *const AvbDescriptor) -> utils::Result<Self> {
        is_not_null(descriptor)?;
        // SAFETY: It is safe as the raw pointer `descriptor` is non-null and points to
        // a valid `AvbDescriptor`.
        let desc = unsafe {
            let mut desc = MaybeUninit::uninit();
            if !avb_hash_descriptor_validate_and_byteswap(
                descriptor as *const AvbHashDescriptor,
                desc.as_mut_ptr(),
            ) {
                return Err(AvbIOError::Io);
            }
            desc.assume_init()
        };
        Ok(Self(desc))
    }

    fn len(&self) -> utils::Result<usize> {
        usize_checked_add(
            size_of::<AvbDescriptor>(),
            to_usize(self.0.parent_descriptor.num_bytes_following)?,
        )
    }

    fn partition_name_end(&self) -> utils::Result<usize> {
        usize_checked_add(size_of::<AvbHashDescriptor>(), to_usize(self.0.partition_name_len)?)
    }

    fn partition_name_range(&self) -> utils::Result<Range<usize>> {
        let start = size_of::<AvbHashDescriptor>();
        Ok(start..(self.partition_name_end()?))
    }

    fn digest_range(&self) -> utils::Result<Range<usize>> {
        let start = usize_checked_add(self.partition_name_end()?, to_usize(self.0.salt_len)?)?;
        let end = usize_checked_add(start, to_usize(self.0.digest_len)?)?;
        Ok(start..end)
    }
}
