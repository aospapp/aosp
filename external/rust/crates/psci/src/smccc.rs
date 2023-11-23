// Copyright 2022 the authors.
// This project is dual-licensed under Apache 2.0 and MIT terms.
// See LICENSE-APACHE and LICENSE-MIT for details.

//! Functions for making SMCCC calls.

pub mod arch;
pub mod error;

#[cfg(any(feature = "hvc", feature = "smc"))]
#[inline(always)]
pub(crate) fn call32(function: u32, args: [u32; 7]) -> [u32; 8] {
    #[cfg(feature = "hvc")]
    {
        hvc32(function, args)
    }
    #[cfg(feature = "smc")]
    {
        smc32(function, args)
    }
}

#[cfg(any(feature = "hvc", feature = "smc"))]
#[inline(always)]
pub(crate) fn call64(function: u32, args: [u64; 17]) -> [u64; 18] {
    #[cfg(feature = "hvc")]
    {
        hvc64(function, args)
    }
    #[cfg(feature = "smc")]
    {
        smc64(function, args)
    }
}

/// Makes an HVC32 call to the hypervisor, following the SMC Calling Convention version 1.3.
#[inline(always)]
pub fn hvc32(function: u32, args: [u32; 7]) -> [u32; 8] {
    #[cfg(target_arch = "aarch64")]
    unsafe {
        let mut ret = [0; 8];

        core::arch::asm!(
            "hvc #0",
            inout("w0") function => ret[0],
            inout("w1") args[0] => ret[1],
            inout("w2") args[1] => ret[2],
            inout("w3") args[2] => ret[3],
            inout("w4") args[3] => ret[4],
            inout("w5") args[4] => ret[5],
            inout("w6") args[5] => ret[6],
            inout("w7") args[6] => ret[7],
            options(nomem, nostack)
        );

        ret
    }

    #[cfg(not(target_arch = "aarch64"))]
    unimplemented!();
}

/// Makes an SMC32 call to the firmware, following the SMC Calling Convention version 1.3.
#[inline(always)]
pub fn smc32(function: u32, args: [u32; 7]) -> [u32; 8] {
    #[cfg(target_arch = "aarch64")]
    unsafe {
        let mut ret = [0; 8];

        core::arch::asm!(
            "smc #0",
            inout("w0") function => ret[0],
            inout("w1") args[0] => ret[1],
            inout("w2") args[1] => ret[2],
            inout("w3") args[2] => ret[3],
            inout("w4") args[3] => ret[4],
            inout("w5") args[4] => ret[5],
            inout("w6") args[5] => ret[6],
            inout("w7") args[6] => ret[7],
            options(nomem, nostack)
        );

        ret
    }

    #[cfg(not(target_arch = "aarch64"))]
    unimplemented!();
}

/// Makes an HVC64 call to the hypervisor, following the SMC Calling Convention version 1.3.
#[inline(always)]
pub fn hvc64(function: u32, args: [u64; 17]) -> [u64; 18] {
    #[cfg(target_arch = "aarch64")]
    unsafe {
        let mut ret = [0; 18];

        core::arch::asm!(
            "hvc #0",
            inout("x0") function as u64 => ret[0],
            inout("x1") args[0] => ret[1],
            inout("x2") args[1] => ret[2],
            inout("x3") args[2] => ret[3],
            inout("x4") args[3] => ret[4],
            inout("x5") args[4] => ret[5],
            inout("x6") args[5] => ret[6],
            inout("x7") args[6] => ret[7],
            inout("x8") args[7] => ret[8],
            inout("x9") args[8] => ret[9],
            inout("x10") args[9] => ret[10],
            inout("x11") args[10] => ret[11],
            inout("x12") args[11] => ret[12],
            inout("x13") args[12] => ret[13],
            inout("x14") args[13] => ret[14],
            inout("x15") args[14] => ret[15],
            inout("x16") args[15] => ret[16],
            inout("x17") args[16] => ret[17],
            options(nomem, nostack)
        );

        ret
    }

    #[cfg(not(target_arch = "aarch64"))]
    unimplemented!();
}

/// Makes an SMC64 call to the firmware, following the SMC Calling Convention version 1.3.
#[inline(always)]
pub fn smc64(function: u32, args: [u64; 17]) -> [u64; 18] {
    #[cfg(target_arch = "aarch64")]
    unsafe {
        let mut ret = [0; 18];

        core::arch::asm!(
            "smc #0",
            inout("x0") function as u64 => ret[0],
            inout("x1") args[0] => ret[1],
            inout("x2") args[1] => ret[2],
            inout("x3") args[2] => ret[3],
            inout("x4") args[3] => ret[4],
            inout("x5") args[4] => ret[5],
            inout("x6") args[5] => ret[6],
            inout("x7") args[6] => ret[7],
            inout("x8") args[7] => ret[8],
            inout("x9") args[8] => ret[9],
            inout("x10") args[9] => ret[10],
            inout("x11") args[10] => ret[11],
            inout("x12") args[11] => ret[12],
            inout("x13") args[12] => ret[13],
            inout("x14") args[13] => ret[14],
            inout("x15") args[14] => ret[15],
            inout("x16") args[15] => ret[16],
            inout("x17") args[16] => ret[17],
            options(nomem, nostack)
        );

        ret
    }

    #[cfg(not(target_arch = "aarch64"))]
    unimplemented!();
}
