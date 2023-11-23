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

//! Structs and functions relating to `AvbOps`.

use crate::error::{
    slot_verify_result_to_verify_payload_result, to_avb_io_result, AvbIOError, AvbSlotVerifyError,
};
use crate::partition::PartitionName;
use crate::utils::{self, as_ref, is_not_null, to_nonnull, write};
use avb_bindgen::{
    avb_slot_verify, avb_slot_verify_data_free, AvbHashtreeErrorMode, AvbIOResult, AvbOps,
    AvbPartitionData, AvbSlotVerifyData, AvbSlotVerifyFlags, AvbVBMetaData,
};
use core::{
    ffi::{c_char, c_void, CStr},
    mem::MaybeUninit,
    ptr, slice,
};

const NULL_BYTE: &[u8] = b"\0";

pub(crate) struct Payload<'a> {
    kernel: &'a [u8],
    initrd: Option<&'a [u8]>,
    trusted_public_key: &'a [u8],
}

impl<'a> AsRef<Payload<'a>> for AvbOps {
    fn as_ref(&self) -> &Payload<'a> {
        let payload = self.user_data as *const Payload;
        // SAFETY: It is safe to cast the `AvbOps.user_data` to Payload as we have saved a
        // pointer to a valid value of Payload in user_data when creating AvbOps.
        unsafe { &*payload }
    }
}

impl<'a> Payload<'a> {
    pub(crate) fn new(
        kernel: &'a [u8],
        initrd: Option<&'a [u8]>,
        trusted_public_key: &'a [u8],
    ) -> Self {
        Self { kernel, initrd, trusted_public_key }
    }

    fn get_partition(&self, partition_name: *const c_char) -> Result<&[u8], AvbIOError> {
        match partition_name.try_into()? {
            PartitionName::Kernel => Ok(self.kernel),
            PartitionName::InitrdNormal | PartitionName::InitrdDebug => {
                self.initrd.ok_or(AvbIOError::NoSuchPartition)
            }
        }
    }
}

/// `Ops` wraps the class `AvbOps` in libavb. It provides pvmfw customized
/// operations used in the verification.
pub(crate) struct Ops(AvbOps);

impl<'a> From<&mut Payload<'a>> for Ops {
    fn from(payload: &mut Payload<'a>) -> Self {
        let avb_ops = AvbOps {
            user_data: payload as *mut _ as *mut c_void,
            ab_ops: ptr::null_mut(),
            atx_ops: ptr::null_mut(),
            read_from_partition: Some(read_from_partition),
            get_preloaded_partition: Some(get_preloaded_partition),
            write_to_partition: None,
            validate_vbmeta_public_key: Some(validate_vbmeta_public_key),
            read_rollback_index: Some(read_rollback_index),
            write_rollback_index: None,
            read_is_device_unlocked: Some(read_is_device_unlocked),
            get_unique_guid_for_partition: Some(get_unique_guid_for_partition),
            get_size_of_partition: Some(get_size_of_partition),
            read_persistent_value: None,
            write_persistent_value: None,
            validate_public_key_for_partition: None,
        };
        Self(avb_ops)
    }
}

impl Ops {
    pub(crate) fn verify_partition(
        &mut self,
        partition_name: &CStr,
    ) -> Result<AvbSlotVerifyDataWrap, AvbSlotVerifyError> {
        let requested_partitions = [partition_name.as_ptr(), ptr::null()];
        let ab_suffix = CStr::from_bytes_with_nul(NULL_BYTE).unwrap();
        let mut out_data = MaybeUninit::uninit();
        // SAFETY: It is safe to call `avb_slot_verify()` as the pointer arguments (`ops`,
        // `requested_partitions` and `ab_suffix`) passed to the method are all valid and
        // initialized.
        let result = unsafe {
            avb_slot_verify(
                &mut self.0,
                requested_partitions.as_ptr(),
                ab_suffix.as_ptr(),
                AvbSlotVerifyFlags::AVB_SLOT_VERIFY_FLAGS_NONE,
                AvbHashtreeErrorMode::AVB_HASHTREE_ERROR_MODE_RESTART_AND_INVALIDATE,
                out_data.as_mut_ptr(),
            )
        };
        slot_verify_result_to_verify_payload_result(result)?;
        // SAFETY: This is safe because `out_data` has been properly initialized after
        // calling `avb_slot_verify` and it returns OK.
        let out_data = unsafe { out_data.assume_init() };
        out_data.try_into()
    }
}

extern "C" fn read_is_device_unlocked(
    _ops: *mut AvbOps,
    out_is_unlocked: *mut bool,
) -> AvbIOResult {
    to_avb_io_result(write(out_is_unlocked, false))
}

extern "C" fn get_preloaded_partition(
    ops: *mut AvbOps,
    partition: *const c_char,
    num_bytes: usize,
    out_pointer: *mut *mut u8,
    out_num_bytes_preloaded: *mut usize,
) -> AvbIOResult {
    to_avb_io_result(try_get_preloaded_partition(
        ops,
        partition,
        num_bytes,
        out_pointer,
        out_num_bytes_preloaded,
    ))
}

fn try_get_preloaded_partition(
    ops: *mut AvbOps,
    partition: *const c_char,
    num_bytes: usize,
    out_pointer: *mut *mut u8,
    out_num_bytes_preloaded: *mut usize,
) -> utils::Result<()> {
    let ops = as_ref(ops)?;
    let partition = ops.as_ref().get_partition(partition)?;
    write(out_pointer, partition.as_ptr() as *mut u8)?;
    write(out_num_bytes_preloaded, partition.len().min(num_bytes))
}

extern "C" fn read_from_partition(
    ops: *mut AvbOps,
    partition: *const c_char,
    offset: i64,
    num_bytes: usize,
    buffer: *mut c_void,
    out_num_read: *mut usize,
) -> AvbIOResult {
    to_avb_io_result(try_read_from_partition(
        ops,
        partition,
        offset,
        num_bytes,
        buffer,
        out_num_read,
    ))
}

fn try_read_from_partition(
    ops: *mut AvbOps,
    partition: *const c_char,
    offset: i64,
    num_bytes: usize,
    buffer: *mut c_void,
    out_num_read: *mut usize,
) -> utils::Result<()> {
    let ops = as_ref(ops)?;
    let partition = ops.as_ref().get_partition(partition)?;
    let buffer = to_nonnull(buffer)?;
    // SAFETY: It is safe to copy the requested number of bytes to `buffer` as `buffer`
    // is created to point to the `num_bytes` of bytes in memory.
    let buffer_slice = unsafe { slice::from_raw_parts_mut(buffer.as_ptr() as *mut u8, num_bytes) };
    copy_data_to_dst(partition, offset, buffer_slice)?;
    write(out_num_read, buffer_slice.len())
}

fn copy_data_to_dst(src: &[u8], offset: i64, dst: &mut [u8]) -> utils::Result<()> {
    let start = to_copy_start(offset, src.len()).ok_or(AvbIOError::InvalidValueSize)?;
    let end = start.checked_add(dst.len()).ok_or(AvbIOError::InvalidValueSize)?;
    dst.copy_from_slice(src.get(start..end).ok_or(AvbIOError::RangeOutsidePartition)?);
    Ok(())
}

fn to_copy_start(offset: i64, len: usize) -> Option<usize> {
    usize::try_from(offset)
        .ok()
        .or_else(|| isize::try_from(offset).ok().and_then(|v| len.checked_add_signed(v)))
}

extern "C" fn get_size_of_partition(
    ops: *mut AvbOps,
    partition: *const c_char,
    out_size_num_bytes: *mut u64,
) -> AvbIOResult {
    to_avb_io_result(try_get_size_of_partition(ops, partition, out_size_num_bytes))
}

fn try_get_size_of_partition(
    ops: *mut AvbOps,
    partition: *const c_char,
    out_size_num_bytes: *mut u64,
) -> utils::Result<()> {
    let ops = as_ref(ops)?;
    let partition = ops.as_ref().get_partition(partition)?;
    let partition_size =
        u64::try_from(partition.len()).map_err(|_| AvbIOError::InvalidValueSize)?;
    write(out_size_num_bytes, partition_size)
}

extern "C" fn read_rollback_index(
    _ops: *mut AvbOps,
    _rollback_index_location: usize,
    out_rollback_index: *mut u64,
) -> AvbIOResult {
    // Rollback protection is not yet implemented, but this method is required by
    // `avb_slot_verify()`.
    // We set `out_rollback_index` to 0 to ensure that the default rollback index (0)
    // is never smaller than it, thus the rollback index check will pass.
    to_avb_io_result(write(out_rollback_index, 0))
}

extern "C" fn get_unique_guid_for_partition(
    _ops: *mut AvbOps,
    _partition: *const c_char,
    _guid_buf: *mut c_char,
    _guid_buf_size: usize,
) -> AvbIOResult {
    // TODO(b/256148034): Check if it's possible to throw an error here instead of having
    // an empty method.
    // This method is required by `avb_slot_verify()`.
    AvbIOResult::AVB_IO_RESULT_OK
}

extern "C" fn validate_vbmeta_public_key(
    ops: *mut AvbOps,
    public_key_data: *const u8,
    public_key_length: usize,
    public_key_metadata: *const u8,
    public_key_metadata_length: usize,
    out_is_trusted: *mut bool,
) -> AvbIOResult {
    to_avb_io_result(try_validate_vbmeta_public_key(
        ops,
        public_key_data,
        public_key_length,
        public_key_metadata,
        public_key_metadata_length,
        out_is_trusted,
    ))
}

fn try_validate_vbmeta_public_key(
    ops: *mut AvbOps,
    public_key_data: *const u8,
    public_key_length: usize,
    _public_key_metadata: *const u8,
    _public_key_metadata_length: usize,
    out_is_trusted: *mut bool,
) -> utils::Result<()> {
    // The public key metadata is not used when we build the VBMeta.
    is_not_null(public_key_data)?;
    // SAFETY: It is safe to create a slice with the given pointer and length as
    // `public_key_data` is a valid pointer and it points to an array of length
    // `public_key_length`.
    let public_key = unsafe { slice::from_raw_parts(public_key_data, public_key_length) };
    let ops = as_ref(ops)?;
    let trusted_public_key = ops.as_ref().trusted_public_key;
    write(out_is_trusted, public_key == trusted_public_key)
}

pub(crate) struct AvbSlotVerifyDataWrap(*mut AvbSlotVerifyData);

impl TryFrom<*mut AvbSlotVerifyData> for AvbSlotVerifyDataWrap {
    type Error = AvbSlotVerifyError;

    fn try_from(data: *mut AvbSlotVerifyData) -> Result<Self, Self::Error> {
        is_not_null(data).map_err(|_| AvbSlotVerifyError::Io)?;
        Ok(Self(data))
    }
}

impl Drop for AvbSlotVerifyDataWrap {
    fn drop(&mut self) {
        // SAFETY: This is safe because `self.0` is checked nonnull when the
        // instance is created. We can free this pointer when the instance is
        // no longer needed.
        unsafe {
            avb_slot_verify_data_free(self.0);
        }
    }
}

impl AsRef<AvbSlotVerifyData> for AvbSlotVerifyDataWrap {
    fn as_ref(&self) -> &AvbSlotVerifyData {
        // This is safe because `self.0` is checked nonnull when the instance is created.
        as_ref(self.0).unwrap()
    }
}

impl AvbSlotVerifyDataWrap {
    pub(crate) fn vbmeta_images(&self) -> Result<&[AvbVBMetaData], AvbSlotVerifyError> {
        let data = self.as_ref();
        is_not_null(data.vbmeta_images).map_err(|_| AvbSlotVerifyError::Io)?;
        // SAFETY: It is safe as the raw pointer `data.vbmeta_images` is a nonnull pointer.
        let vbmeta_images =
            unsafe { slice::from_raw_parts(data.vbmeta_images, data.num_vbmeta_images) };
        Ok(vbmeta_images)
    }

    pub(crate) fn loaded_partitions(&self) -> Result<&[AvbPartitionData], AvbSlotVerifyError> {
        let data = self.as_ref();
        is_not_null(data.loaded_partitions).map_err(|_| AvbSlotVerifyError::Io)?;
        // SAFETY: It is safe as the raw pointer `data.loaded_partitions` is a nonnull pointer and
        // is guaranteed by libavb to point to a valid `AvbPartitionData` array as part of the
        // `AvbSlotVerifyData` struct.
        let loaded_partitions =
            unsafe { slice::from_raw_parts(data.loaded_partitions, data.num_loaded_partitions) };
        Ok(loaded_partitions)
    }
}
