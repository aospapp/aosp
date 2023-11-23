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

//! Exception handlers.

use core::arch::asm;
use vmbase::{console::emergency_write_str, eprintln, power::reboot};

#[no_mangle]
extern "C" fn sync_exception_current() {
    emergency_write_str("sync_exception_current\n");
    print_esr();
    reboot();
}

#[no_mangle]
extern "C" fn irq_current() {
    emergency_write_str("irq_current\n");
    reboot();
}

#[no_mangle]
extern "C" fn fiq_current() {
    emergency_write_str("fiq_current\n");
    reboot();
}

#[no_mangle]
extern "C" fn serr_current() {
    emergency_write_str("serr_current\n");
    print_esr();
    reboot();
}

#[no_mangle]
extern "C" fn sync_lower() {
    emergency_write_str("sync_lower\n");
    print_esr();
    reboot();
}

#[no_mangle]
extern "C" fn irq_lower() {
    emergency_write_str("irq_lower\n");
    reboot();
}

#[no_mangle]
extern "C" fn fiq_lower() {
    emergency_write_str("fiq_lower\n");
    reboot();
}

#[no_mangle]
extern "C" fn serr_lower() {
    emergency_write_str("serr_lower\n");
    print_esr();
    reboot();
}

#[inline]
fn print_esr() {
    let mut esr: u64;
    unsafe {
        asm!("mrs {esr}, esr_el1", esr = out(reg) esr);
    }
    eprintln!("esr={:#08x}", esr);
}
