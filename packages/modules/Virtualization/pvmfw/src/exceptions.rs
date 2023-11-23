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

use crate::{helpers::page_4kb_of, read_sysreg};
use core::fmt;
use vmbase::console;
use vmbase::logger;
use vmbase::{eprintln, power::reboot};

const UART_PAGE: usize = page_4kb_of(console::BASE_ADDRESS);

#[derive(Debug)]
enum HandleExceptionError {
    UnknownException,
}

impl fmt::Display for HandleExceptionError {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Self::UnknownException => write!(f, "An unknown exception occurred, not handled."),
        }
    }
}

#[derive(Debug, PartialEq, Copy, Clone)]
enum Esr {
    DataAbortTranslationFault,
    DataAbortPermissionFault,
    DataAbortSyncExternalAbort,
    Unknown(usize),
}

impl Esr {
    const EXT_DABT_32BIT: usize = 0x96000010;
    const TRANSL_FAULT_BASE_32BIT: usize = 0x96000004;
    const TRANSL_FAULT_ISS_MASK_32BIT: usize = !0x143;
    const PERM_FAULT_BASE_32BIT: usize = 0x9600004C;
    const PERM_FAULT_ISS_MASK_32BIT: usize = !0x103;
}

impl From<usize> for Esr {
    fn from(esr: usize) -> Self {
        if esr == Self::EXT_DABT_32BIT {
            Self::DataAbortSyncExternalAbort
        } else if esr & Self::TRANSL_FAULT_ISS_MASK_32BIT == Self::TRANSL_FAULT_BASE_32BIT {
            Self::DataAbortTranslationFault
        } else if esr & Self::PERM_FAULT_ISS_MASK_32BIT == Self::PERM_FAULT_BASE_32BIT {
            Self::DataAbortPermissionFault
        } else {
            Self::Unknown(esr)
        }
    }
}

impl fmt::Display for Esr {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Self::DataAbortSyncExternalAbort => write!(f, "Synchronous external abort"),
            Self::DataAbortTranslationFault => write!(f, "Translation fault"),
            Self::DataAbortPermissionFault => write!(f, "Permission fault"),
            Self::Unknown(v) => write!(f, "Unknown exception esr={v:#08x}"),
        }
    }
}

fn handle_exception(_esr: Esr, _far: usize) -> Result<(), HandleExceptionError> {
    Err(HandleExceptionError::UnknownException)
}

#[inline]
fn handling_uart_exception(esr: Esr, far: usize) -> bool {
    esr == Esr::DataAbortSyncExternalAbort && page_4kb_of(far) == UART_PAGE
}

#[no_mangle]
extern "C" fn sync_exception_current(_elr: u64, _spsr: u64) {
    // Disable logging in exception handler to prevent unsafe writes to UART.
    let _guard = logger::suppress();
    let esr: Esr = read_sysreg!("esr_el1").into();
    let far = read_sysreg!("far_el1");

    if let Err(e) = handle_exception(esr, far) {
        // Don't print to the UART if we are handling an exception it could raise.
        if !handling_uart_exception(esr, far) {
            eprintln!("sync_exception_current");
            eprintln!("{e}");
            eprintln!("{esr}, far={far:#08x}");
        }
        reboot()
    }
}

#[no_mangle]
extern "C" fn irq_current(_elr: u64, _spsr: u64) {
    eprintln!("irq_current");
    reboot();
}

#[no_mangle]
extern "C" fn fiq_current(_elr: u64, _spsr: u64) {
    eprintln!("fiq_current");
    reboot();
}

#[no_mangle]
extern "C" fn serr_current(_elr: u64, _spsr: u64) {
    let esr = read_sysreg!("esr_el1");
    eprintln!("serr_current");
    eprintln!("esr={esr:#08x}");
    reboot();
}

#[no_mangle]
extern "C" fn sync_lower(_elr: u64, _spsr: u64) {
    let esr = read_sysreg!("esr_el1");
    eprintln!("sync_lower");
    eprintln!("esr={esr:#08x}");
    reboot();
}

#[no_mangle]
extern "C" fn irq_lower(_elr: u64, _spsr: u64) {
    eprintln!("irq_lower");
    reboot();
}

#[no_mangle]
extern "C" fn fiq_lower(_elr: u64, _spsr: u64) {
    eprintln!("fiq_lower");
    reboot();
}

#[no_mangle]
extern "C" fn serr_lower(_elr: u64, _spsr: u64) {
    let esr = read_sysreg!("esr_el1");
    eprintln!("serr_lower");
    eprintln!("esr={esr:#08x}");
    reboot();
}
