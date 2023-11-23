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

//! pVM firmware.

#![no_main]
#![no_std]

extern crate alloc;

mod bcc;
mod bootargs;
mod config;
mod crypto;
mod dice;
mod entry;
mod exceptions;
mod fdt;
mod gpt;
mod heap;
mod helpers;
mod hvc;
mod instance;
mod memory;
mod mmu;
mod rand;
mod virtio;

use crate::bcc::Bcc;
use crate::dice::PartialInputs;
use crate::entry::RebootReason;
use crate::fdt::modify_for_next_stage;
use crate::helpers::flush;
use crate::helpers::GUEST_PAGE_SIZE;
use crate::instance::get_or_generate_instance_salt;
use crate::memory::MemoryTracker;
use crate::virtio::pci;
use alloc::boxed::Box;
use core::ops::Range;
use diced_open_dice::{bcc_handover_main_flow, bcc_handover_parse, DiceArtifacts};
use fdtpci::{PciError, PciInfo};
use libfdt::Fdt;
use log::{debug, error, info, trace, warn};
use pvmfw_avb::verify_payload;
use pvmfw_avb::DebugLevel;
use pvmfw_embedded_key::PUBLIC_KEY;

const NEXT_BCC_SIZE: usize = GUEST_PAGE_SIZE;

fn main(
    fdt: &mut Fdt,
    signed_kernel: &[u8],
    ramdisk: Option<&[u8]>,
    current_bcc_handover: &[u8],
    mut debug_policy: Option<&mut [u8]>,
    memory: &mut MemoryTracker,
) -> Result<Range<usize>, RebootReason> {
    info!("pVM firmware");
    debug!("FDT: {:?}", fdt.as_ptr());
    debug!("Signed kernel: {:?} ({:#x} bytes)", signed_kernel.as_ptr(), signed_kernel.len());
    debug!("AVB public key: addr={:?}, size={:#x} ({1})", PUBLIC_KEY.as_ptr(), PUBLIC_KEY.len());
    if let Some(rd) = ramdisk {
        debug!("Ramdisk: {:?} ({:#x} bytes)", rd.as_ptr(), rd.len());
    } else {
        debug!("Ramdisk: None");
    }

    let bcc_handover = bcc_handover_parse(current_bcc_handover).map_err(|e| {
        error!("Invalid BCC Handover: {e:?}");
        RebootReason::InvalidBcc
    })?;
    trace!("BCC: {bcc_handover:x?}");

    let cdi_seal = bcc_handover.cdi_seal();

    let bcc = Bcc::new(bcc_handover.bcc()).map_err(|e| {
        error!("{e}");
        RebootReason::InvalidBcc
    })?;

    // The bootloader should never pass us a debug policy when the boot is secure (the bootloader
    // is locked). If it gets it wrong, disregard it & log it, to avoid it causing problems.
    if debug_policy.is_some() && !bcc.is_debug_mode() {
        warn!("Ignoring debug policy, BCC does not indicate Debug mode");
        debug_policy = None;
    }

    // Set up PCI bus for VirtIO devices.
    let pci_info = PciInfo::from_fdt(fdt).map_err(handle_pci_error)?;
    debug!("PCI: {:#x?}", pci_info);
    let mut pci_root = pci::initialise(pci_info, memory)?;

    let verified_boot_data = verify_payload(signed_kernel, ramdisk, PUBLIC_KEY).map_err(|e| {
        error!("Failed to verify the payload: {e}");
        RebootReason::PayloadVerificationError
    })?;

    let next_bcc = heap::aligned_boxed_slice(NEXT_BCC_SIZE, GUEST_PAGE_SIZE).ok_or_else(|| {
        error!("Failed to allocate the next-stage BCC");
        RebootReason::InternalError
    })?;
    // By leaking the slice, its content will be left behind for the next stage.
    let next_bcc = Box::leak(next_bcc);

    let dice_inputs = PartialInputs::new(&verified_boot_data).map_err(|e| {
        error!("Failed to compute partial DICE inputs: {e:?}");
        RebootReason::InternalError
    })?;
    let (new_instance, salt) = get_or_generate_instance_salt(&mut pci_root, &dice_inputs, cdi_seal)
        .map_err(|e| {
            error!("Failed to get instance.img salt: {e}");
            RebootReason::InternalError
        })?;
    trace!("Got salt from instance.img: {salt:x?}");

    let mut config_descriptor_buffer = [0; 128];
    let dice_inputs =
        dice_inputs.into_input_values(&salt, &mut config_descriptor_buffer).map_err(|e| {
            error!("Failed to generate DICE inputs: {e:?}");
            RebootReason::InternalError
        })?;

    // It is possible that the DICE chain we were given is rooted in the UDS. We do not want to give
    // such a chain to the payload, or even the associated CDIs. So remove the entire chain we
    // were given and taint the CDIs. Note that the resulting CDIs are still deterministically
    // derived from those we received, so will vary iff they do.
    // TODO(b/280405545): Remove this post Android 14.
    let truncated_bcc_handover = bcc::truncate(bcc_handover).map_err(|e| {
        error!("{e}");
        RebootReason::InternalError
    })?;

    let _ = bcc_handover_main_flow(truncated_bcc_handover.as_slice(), &dice_inputs, next_bcc)
        .map_err(|e| {
            error!("Failed to derive next-stage DICE secrets: {e:?}");
            RebootReason::SecretDerivationError
        })?;
    flush(next_bcc);

    let strict_boot = true;
    let debuggable = verified_boot_data.debug_level != DebugLevel::None;
    modify_for_next_stage(fdt, next_bcc, new_instance, strict_boot, debug_policy, debuggable)
        .map_err(|e| {
            error!("Failed to configure device tree: {e}");
            RebootReason::InternalError
        })?;

    info!("Starting payload...");

    let bcc_range = {
        let r = next_bcc.as_ptr_range();
        (r.start as usize)..(r.end as usize)
    };

    Ok(bcc_range)
}

/// Logs the given PCI error and returns the appropriate `RebootReason`.
fn handle_pci_error(e: PciError) -> RebootReason {
    error!("{}", e);
    match e {
        PciError::FdtErrorPci(_)
        | PciError::FdtNoPci
        | PciError::FdtErrorReg(_)
        | PciError::FdtMissingReg
        | PciError::FdtRegEmpty
        | PciError::FdtRegMissingSize
        | PciError::CamWrongSize(_)
        | PciError::FdtErrorRanges(_)
        | PciError::FdtMissingRanges
        | PciError::RangeAddressMismatch { .. }
        | PciError::NoSuitableRange => RebootReason::InvalidFdt,
    }
}
