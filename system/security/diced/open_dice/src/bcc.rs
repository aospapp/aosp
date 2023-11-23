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

//! This module mirrors the content in open-dice/include/dice/android/bcc.h

use crate::dice::{Cdi, CdiValues, DiceArtifacts, InputValues, CDI_SIZE};
use crate::error::{check_result, DiceError, Result};
use open_dice_bcc_bindgen::{
    BccConfigValues, BccFormatConfigDescriptor, BccHandoverMainFlow, BccHandoverParse, BccMainFlow,
    BCC_INPUT_COMPONENT_NAME, BCC_INPUT_COMPONENT_VERSION, BCC_INPUT_RESETTABLE,
};
use std::{ffi::CStr, ptr};

/// Formats a configuration descriptor following the BCC's specification.
/// See https://cs.android.com/android/platform/superproject/+/master:hardware/interfaces/security/rkp/aidl/android/hardware/security/keymint/ProtectedData.aidl
pub fn bcc_format_config_descriptor(
    name: Option<&CStr>,
    version: Option<u64>,
    resettable: bool,
    buffer: &mut [u8],
) -> Result<usize> {
    let mut inputs = 0;
    if name.is_some() {
        inputs |= BCC_INPUT_COMPONENT_NAME;
    }
    if version.is_some() {
        inputs |= BCC_INPUT_COMPONENT_VERSION;
    }
    if resettable {
        inputs |= BCC_INPUT_RESETTABLE;
    }

    let values = BccConfigValues {
        inputs,
        component_name: name.map_or(ptr::null(), |p| p.as_ptr()),
        component_version: version.unwrap_or(0),
    };

    let mut buffer_size = 0;
    // SAFETY: The function writes to the buffer, within the given bounds, and only reads the
    // input values. It writes its result to buffer_size.
    check_result(unsafe {
        BccFormatConfigDescriptor(&values, buffer.len(), buffer.as_mut_ptr(), &mut buffer_size)
    })?;
    Ok(buffer_size)
}

/// Executes the main BCC flow.
///
/// Given a full set of input values along with the current BCC and CDI values,
/// computes the next CDI values and matching updated BCC.
pub fn bcc_main_flow(
    current_cdi_attest: &Cdi,
    current_cdi_seal: &Cdi,
    current_bcc: &[u8],
    input_values: &InputValues,
    next_cdi_values: &mut CdiValues,
    next_bcc: &mut [u8],
) -> Result<usize> {
    let mut next_bcc_size = 0;
    // SAFETY: `BccMainFlow` only reads the current `bcc` and CDI values and writes
    // to `next_bcc` and next CDI values within its bounds. It also reads
    // `input_values` as a constant input and doesn't store any pointer.
    // The first argument can be null and is not used in the current implementation.
    check_result(unsafe {
        BccMainFlow(
            ptr::null_mut(), // context
            current_cdi_attest.as_ptr(),
            current_cdi_seal.as_ptr(),
            current_bcc.as_ptr(),
            current_bcc.len(),
            input_values.as_ptr(),
            next_bcc.len(),
            next_bcc.as_mut_ptr(),
            &mut next_bcc_size,
            next_cdi_values.cdi_attest.as_mut_ptr(),
            next_cdi_values.cdi_seal.as_mut_ptr(),
        )
    })?;
    Ok(next_bcc_size)
}

/// Executes the main BCC handover flow.
///
/// A BCC handover combines the BCC and CDIs in a single CBOR object.
/// This function takes the current boot stage's BCC handover bundle and produces a
/// bundle for the next stage.
pub fn bcc_handover_main_flow(
    current_bcc_handover: &[u8],
    input_values: &InputValues,
    next_bcc_handover: &mut [u8],
) -> Result<usize> {
    let mut next_bcc_handover_size = 0;
    // SAFETY - The function only reads `current_bcc_handover` and writes to `next_bcc_handover`
    // within its bounds,
    // It also reads `input_values` as a constant input and doesn't store any pointer.
    // The first argument can be null and is not used in the current implementation.
    check_result(unsafe {
        BccHandoverMainFlow(
            ptr::null_mut(), // context
            current_bcc_handover.as_ptr(),
            current_bcc_handover.len(),
            input_values.as_ptr(),
            next_bcc_handover.len(),
            next_bcc_handover.as_mut_ptr(),
            &mut next_bcc_handover_size,
        )
    })?;

    Ok(next_bcc_handover_size)
}

/// A BCC handover combines the BCC and CDIs in a single CBOR object.
/// This struct is used as return of the function `bcc_handover_parse`, its lifetime is tied
/// to the lifetime of the raw BCC handover slice.
#[derive(Debug)]
pub struct BccHandover<'a> {
    /// Attestation CDI.
    cdi_attest: &'a [u8; CDI_SIZE],
    /// Sealing CDI.
    cdi_seal: &'a [u8; CDI_SIZE],
    /// Boot Certificate Chain.
    bcc: Option<&'a [u8]>,
}

impl<'a> DiceArtifacts for BccHandover<'a> {
    fn cdi_attest(&self) -> &[u8; CDI_SIZE] {
        self.cdi_attest
    }

    fn cdi_seal(&self) -> &[u8; CDI_SIZE] {
        self.cdi_seal
    }

    fn bcc(&self) -> Option<&[u8]> {
        self.bcc
    }
}

/// A BCC handover combines the BCC and CDIs in a single CBOR object.
/// This function parses the `bcc_handover` to extracts the BCC and CDIs.
/// The lifetime of the returned `BccHandover` is tied to the given `bcc_handover` slice.
pub fn bcc_handover_parse(bcc_handover: &[u8]) -> Result<BccHandover> {
    let mut cdi_attest: *const u8 = ptr::null();
    let mut cdi_seal: *const u8 = ptr::null();
    let mut bcc: *const u8 = ptr::null();
    let mut bcc_size = 0;
    // SAFETY: The `bcc_handover` is only read and never stored and the returned pointers should all
    // point within the address range of the `bcc_handover` or be NULL.
    check_result(unsafe {
        BccHandoverParse(
            bcc_handover.as_ptr(),
            bcc_handover.len(),
            &mut cdi_attest,
            &mut cdi_seal,
            &mut bcc,
            &mut bcc_size,
        )
    })?;
    let cdi_attest = sub_slice(bcc_handover, cdi_attest, CDI_SIZE)?;
    let cdi_seal = sub_slice(bcc_handover, cdi_seal, CDI_SIZE)?;
    let bcc = sub_slice(bcc_handover, bcc, bcc_size).ok();
    Ok(BccHandover {
        cdi_attest: cdi_attest.try_into().map_err(|_| DiceError::PlatformError)?,
        cdi_seal: cdi_seal.try_into().map_err(|_| DiceError::PlatformError)?,
        bcc,
    })
}

/// Gets a slice the `addr` points to and of length `len`.
/// The slice should be contained in the buffer.
fn sub_slice(buffer: &[u8], addr: *const u8, len: usize) -> Result<&[u8]> {
    if addr.is_null() || !buffer.as_ptr_range().contains(&addr) {
        return Err(DiceError::PlatformError);
    }
    // SAFETY: This is safe because addr is not null and is within the range of the buffer.
    let start: usize = unsafe {
        addr.offset_from(buffer.as_ptr()).try_into().map_err(|_| DiceError::PlatformError)?
    };
    start.checked_add(len).and_then(|end| buffer.get(start..end)).ok_or(DiceError::PlatformError)
}
