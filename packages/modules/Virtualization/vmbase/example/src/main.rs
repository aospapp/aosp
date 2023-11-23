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

//! VM bootloader example.

#![no_main]
#![no_std]

mod exceptions;
mod layout;
mod pci;

extern crate alloc;

use crate::layout::{
    bionic_tls, boot_stack_range, dtb_range, print_addresses, rodata_range, scratch_range,
    stack_chk_guard, text_range, DEVICE_REGION,
};
use crate::pci::{check_pci, get_bar_region};
use aarch64_paging::{idmap::IdMap, paging::Attributes};
use alloc::{vec, vec::Vec};
use buddy_system_allocator::LockedHeap;
use core::ffi::CStr;
use fdtpci::PciInfo;
use libfdt::Fdt;
use log::{debug, error, info, trace, warn, LevelFilter};
use vmbase::{logger, main};

static INITIALISED_DATA: [u32; 4] = [1, 2, 3, 4];
static mut ZEROED_DATA: [u32; 10] = [0; 10];
static mut MUTABLE_DATA: [u32; 4] = [1, 2, 3, 4];

const ASID: usize = 1;
const ROOT_LEVEL: usize = 1;

#[global_allocator]
static HEAP_ALLOCATOR: LockedHeap<32> = LockedHeap::<32>::new();

static mut HEAP: [u8; 65536] = [0; 65536];

main!(main);

/// Entry point for VM bootloader.
pub fn main(arg0: u64, arg1: u64, arg2: u64, arg3: u64) {
    logger::init(LevelFilter::Debug).unwrap();

    info!("Hello world");
    info!("x0={:#018x}, x1={:#018x}, x2={:#018x}, x3={:#018x}", arg0, arg1, arg2, arg3);
    print_addresses();
    assert_eq!(arg0, dtb_range().start.0 as u64);
    check_data();
    check_stack_guard();

    info!("Checking FDT...");
    let fdt = dtb_range();
    let fdt =
        unsafe { core::slice::from_raw_parts_mut(fdt.start.0 as *mut u8, fdt.end.0 - fdt.start.0) };
    let fdt = Fdt::from_mut_slice(fdt).unwrap();
    info!("FDT passed verification.");
    check_fdt(fdt);

    let pci_info = PciInfo::from_fdt(fdt).unwrap();
    debug!("Found PCI CAM at {:#x}-{:#x}", pci_info.cam_range.start, pci_info.cam_range.end);

    modify_fdt(fdt);

    unsafe {
        HEAP_ALLOCATOR.lock().init(HEAP.as_mut_ptr() as usize, HEAP.len());
    }

    check_alloc();

    let mut idmap = IdMap::new(ASID, ROOT_LEVEL);
    idmap.map_range(&DEVICE_REGION, Attributes::DEVICE_NGNRE | Attributes::EXECUTE_NEVER).unwrap();
    idmap
        .map_range(
            &text_range().into(),
            Attributes::NORMAL | Attributes::NON_GLOBAL | Attributes::READ_ONLY,
        )
        .unwrap();
    idmap
        .map_range(
            &rodata_range().into(),
            Attributes::NORMAL
                | Attributes::NON_GLOBAL
                | Attributes::READ_ONLY
                | Attributes::EXECUTE_NEVER,
        )
        .unwrap();
    idmap
        .map_range(
            &scratch_range().into(),
            Attributes::NORMAL | Attributes::NON_GLOBAL | Attributes::EXECUTE_NEVER,
        )
        .unwrap();
    idmap
        .map_range(
            &boot_stack_range().into(),
            Attributes::NORMAL | Attributes::NON_GLOBAL | Attributes::EXECUTE_NEVER,
        )
        .unwrap();
    idmap
        .map_range(
            &dtb_range().into(),
            Attributes::NORMAL
                | Attributes::NON_GLOBAL
                | Attributes::READ_ONLY
                | Attributes::EXECUTE_NEVER,
        )
        .unwrap();
    idmap
        .map_range(&get_bar_region(&pci_info), Attributes::DEVICE_NGNRE | Attributes::EXECUTE_NEVER)
        .unwrap();

    info!("Activating IdMap...");
    trace!("{:?}", idmap);
    idmap.activate();
    info!("Activated.");

    check_data();
    check_dice();

    let mut pci_root = unsafe { pci_info.make_pci_root() };
    check_pci(&mut pci_root);

    emit_suppressed_log();
}

fn check_stack_guard() {
    const BIONIC_TLS_STACK_GRD_OFF: usize = 40;

    info!("Testing stack guard");
    assert_eq!(bionic_tls(BIONIC_TLS_STACK_GRD_OFF), stack_chk_guard());
}

fn check_data() {
    info!("INITIALISED_DATA: {:?}", INITIALISED_DATA.as_ptr());
    unsafe {
        info!("ZEROED_DATA: {:?}", ZEROED_DATA.as_ptr());
        info!("MUTABLE_DATA: {:?}", MUTABLE_DATA.as_ptr());
        info!("HEAP: {:?}", HEAP.as_ptr());
    }

    assert_eq!(INITIALISED_DATA[0], 1);
    assert_eq!(INITIALISED_DATA[1], 2);
    assert_eq!(INITIALISED_DATA[2], 3);
    assert_eq!(INITIALISED_DATA[3], 4);

    unsafe {
        for element in ZEROED_DATA.iter() {
            assert_eq!(*element, 0);
        }
        ZEROED_DATA[0] = 13;
        assert_eq!(ZEROED_DATA[0], 13);
        ZEROED_DATA[0] = 0;
        assert_eq!(ZEROED_DATA[0], 0);

        assert_eq!(MUTABLE_DATA[0], 1);
        assert_eq!(MUTABLE_DATA[1], 2);
        assert_eq!(MUTABLE_DATA[2], 3);
        assert_eq!(MUTABLE_DATA[3], 4);
        MUTABLE_DATA[0] += 41;
        assert_eq!(MUTABLE_DATA[0], 42);
        MUTABLE_DATA[0] -= 41;
        assert_eq!(MUTABLE_DATA[0], 1);
    }
    info!("Data looks good");
}

fn check_fdt(reader: &Fdt) {
    for reg in reader.memory().unwrap().unwrap() {
        info!("memory @ {reg:#x?}");
    }

    let compatible = CStr::from_bytes_with_nul(b"ns16550a\0").unwrap();

    for c in reader.compatible_nodes(compatible).unwrap() {
        let reg = c.reg().unwrap().unwrap().next().unwrap();
        info!("node compatible with '{}' at {reg:?}", compatible.to_str().unwrap());
    }
}

fn modify_fdt(writer: &mut Fdt) {
    writer.unpack().unwrap();
    info!("FDT successfully unpacked.");

    let path = CStr::from_bytes_with_nul(b"/memory\0").unwrap();
    let mut node = writer.node_mut(path).unwrap().unwrap();
    let name = CStr::from_bytes_with_nul(b"child\0").unwrap();
    let mut child = node.add_subnode(name).unwrap();
    info!("Created subnode '{}/{}'.", path.to_str().unwrap(), name.to_str().unwrap());

    let name = CStr::from_bytes_with_nul(b"str-property\0").unwrap();
    child.appendprop(name, b"property-value\0").unwrap();
    info!("Appended property '{}'.", name.to_str().unwrap());

    let name = CStr::from_bytes_with_nul(b"pair-property\0").unwrap();
    let addr = 0x0123_4567u64;
    let size = 0x89ab_cdefu64;
    child.appendprop_addrrange(name, addr, size).unwrap();
    info!("Appended property '{}'.", name.to_str().unwrap());

    let writer = child.fdt();
    writer.pack().unwrap();
    info!("FDT successfully packed.");

    info!("FDT checks done.");
}

fn check_alloc() {
    info!("Allocating a Vec...");
    let mut vector: Vec<u32> = vec![1, 2, 3, 4];
    assert_eq!(vector[0], 1);
    assert_eq!(vector[1], 2);
    assert_eq!(vector[2], 3);
    assert_eq!(vector[3], 4);
    vector[2] = 42;
    assert_eq!(vector[2], 42);
    info!("Vec seems to work.");
}

fn check_dice() {
    info!("Testing DICE integration...");
    let hash = diced_open_dice::hash("hello world".as_bytes()).expect("DiceHash failed");
    assert_eq!(
        hash,
        [
            0x30, 0x9e, 0xcc, 0x48, 0x9c, 0x12, 0xd6, 0xeb, 0x4c, 0xc4, 0x0f, 0x50, 0xc9, 0x02,
            0xf2, 0xb4, 0xd0, 0xed, 0x77, 0xee, 0x51, 0x1a, 0x7c, 0x7a, 0x9b, 0xcd, 0x3c, 0xa8,
            0x6d, 0x4c, 0xd8, 0x6f, 0x98, 0x9d, 0xd3, 0x5b, 0xc5, 0xff, 0x49, 0x96, 0x70, 0xda,
            0x34, 0x25, 0x5b, 0x45, 0xb0, 0xcf, 0xd8, 0x30, 0xe8, 0x1f, 0x60, 0x5d, 0xcf, 0x7d,
            0xc5, 0x54, 0x2e, 0x93, 0xae, 0x9c, 0xd7, 0x6f
        ]
    );
}

macro_rules! log_all_levels {
    ($msg:literal) => {{
        error!($msg);
        warn!($msg);
        info!($msg);
        debug!($msg);
        trace!($msg);
    }};
}

fn emit_suppressed_log() {
    {
        let _guard = logger::suppress();
        log_all_levels!("Suppressed message");
    }
    log_all_levels!("Unsuppressed message");
}
