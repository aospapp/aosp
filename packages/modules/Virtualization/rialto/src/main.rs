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

//! Project Rialto main source file.

#![no_main]
#![no_std]

mod error;
mod exceptions;

extern crate alloc;

use crate::error::{Error, Result};
use aarch64_paging::{
    idmap::IdMap,
    paging::{Attributes, MemoryRegion},
};
use buddy_system_allocator::LockedHeap;
use core::ops::Range;
use hyp::get_hypervisor;
use log::{debug, error, info};
use vmbase::{layout, main, power::reboot};

const SZ_1K: usize = 1024;
const SZ_4K: usize = 4 * SZ_1K;
const SZ_64K: usize = 64 * SZ_1K;
const SZ_1M: usize = 1024 * SZ_1K;
const SZ_1G: usize = 1024 * SZ_1M;

// Root level is given by the value of TCR_EL1.TG0 and TCR_EL1.T0SZ, set in
// entry.S. For 4KB granule and 39-bit VA, the root level is 1.
const PT_ROOT_LEVEL: usize = 1;
const PT_ASID: usize = 1;

const PROT_DEV: Attributes = Attributes::from_bits_truncate(
    Attributes::DEVICE_NGNRE.bits() | Attributes::EXECUTE_NEVER.bits(),
);
const PROT_RX: Attributes = Attributes::from_bits_truncate(
    Attributes::NORMAL.bits() | Attributes::NON_GLOBAL.bits() | Attributes::READ_ONLY.bits(),
);
const PROT_RO: Attributes = Attributes::from_bits_truncate(
    Attributes::NORMAL.bits()
        | Attributes::NON_GLOBAL.bits()
        | Attributes::READ_ONLY.bits()
        | Attributes::EXECUTE_NEVER.bits(),
);
const PROT_RW: Attributes = Attributes::from_bits_truncate(
    Attributes::NORMAL.bits() | Attributes::NON_GLOBAL.bits() | Attributes::EXECUTE_NEVER.bits(),
);

#[global_allocator]
static HEAP_ALLOCATOR: LockedHeap<32> = LockedHeap::<32>::new();

static mut HEAP: [u8; SZ_64K] = [0; SZ_64K];

fn into_memreg(r: &Range<usize>) -> MemoryRegion {
    MemoryRegion::new(r.start, r.end)
}

fn init_heap() {
    // SAFETY: Allocator set to otherwise unused, static memory.
    unsafe {
        HEAP_ALLOCATOR.lock().init(&mut HEAP as *mut u8 as usize, HEAP.len());
    }
}

fn init_kernel_pgt(pgt: &mut IdMap) -> Result<()> {
    // The first 1 GiB of address space is used by crosvm for MMIO.
    let reg_dev = MemoryRegion::new(0, SZ_1G);
    let reg_text = into_memreg(&layout::text_range());
    let reg_rodata = into_memreg(&layout::rodata_range());
    let reg_scratch = into_memreg(&layout::scratch_range());
    let reg_stack = into_memreg(&layout::stack_range(40 * SZ_4K));

    debug!("Preparing kernel page table.");
    debug!("  dev:    {}-{}", reg_dev.start(), reg_dev.end());
    debug!("  text:   {}-{}", reg_text.start(), reg_text.end());
    debug!("  rodata: {}-{}", reg_rodata.start(), reg_rodata.end());
    debug!("  scratch:{}-{}", reg_scratch.start(), reg_scratch.end());
    debug!("  stack:  {}-{}", reg_stack.start(), reg_stack.end());

    pgt.map_range(&reg_dev, PROT_DEV)?;
    pgt.map_range(&reg_text, PROT_RX)?;
    pgt.map_range(&reg_rodata, PROT_RO)?;
    pgt.map_range(&reg_scratch, PROT_RW)?;
    pgt.map_range(&reg_stack, PROT_RW)?;

    pgt.activate();
    info!("Activated kernel page table.");
    Ok(())
}

fn try_init_logger() -> Result<()> {
    match get_hypervisor().mmio_guard_init() {
        // pKVM blocks MMIO by default, we need to enable MMIO guard to support logging.
        Ok(()) => get_hypervisor().mmio_guard_map(vmbase::console::BASE_ADDRESS)?,
        // MMIO guard enroll is not supported in unprotected VM.
        Err(hyp::Error::MmioGuardNotsupported) => {}
        Err(e) => return Err(e.into()),
    };
    vmbase::logger::init(log::LevelFilter::Debug).map_err(|_| Error::LoggerInit)
}

fn try_main() -> Result<()> {
    info!("Welcome to Rialto!");

    let mut pgt = IdMap::new(PT_ASID, PT_ROOT_LEVEL);
    init_kernel_pgt(&mut pgt)?;
    Ok(())
}

/// Entry point for Rialto.
pub fn main(_a0: u64, _a1: u64, _a2: u64, _a3: u64) {
    init_heap();
    if try_init_logger().is_err() {
        // Don't log anything if the logger initialization fails.
        reboot();
    }
    match try_main() {
        Ok(()) => info!("Rialto ends successfully."),
        Err(e) => {
            error!("Rialto failed with {e}");
            reboot()
        }
    }
}

main!(main);
