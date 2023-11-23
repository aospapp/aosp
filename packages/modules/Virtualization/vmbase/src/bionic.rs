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

//! Low-level compatibility layer between baremetal Rust and Bionic C functions.

use core::ffi::c_char;
use core::ffi::c_int;
use core::ffi::c_void;
use core::ffi::CStr;
use core::slice;
use core::str;

use crate::console;
use crate::eprintln;
use crate::linker;

const EOF: c_int = -1;

/// Reference to __stack_chk_guard.
pub static STACK_CHK_GUARD: &u64 = unsafe { &linker::__stack_chk_guard };

#[no_mangle]
extern "C" fn __stack_chk_fail() -> ! {
    panic!("stack guard check failed");
}

/// Called from C to cause abnormal program termination.
#[no_mangle]
extern "C" fn abort() -> ! {
    panic!("C code called abort()")
}

/// Error number set and read by C functions.
pub static mut ERRNO: c_int = 0;

#[no_mangle]
unsafe extern "C" fn __errno() -> *mut c_int {
    &mut ERRNO as *mut _
}

fn set_errno(value: c_int) {
    // SAFETY - vmbase is currently single-threaded.
    unsafe { ERRNO = value };
}

/// Reports a fatal error detected by Bionic.
///
/// # Safety
///
/// Input strings `prefix` and `format` must be properly NULL-terminated.
///
/// # Note
///
/// This Rust functions is missing the last argument of its C/C++ counterpart, a va_list.
#[no_mangle]
unsafe extern "C" fn async_safe_fatal_va_list(prefix: *const c_char, format: *const c_char) {
    let prefix = CStr::from_ptr(prefix);
    let format = CStr::from_ptr(format);

    if let (Ok(prefix), Ok(format)) = (prefix.to_str(), format.to_str()) {
        // We don't bother with printf formatting.
        eprintln!("FATAL BIONIC ERROR: {prefix}: \"{format}\" (unformatted)");
    }
}

#[repr(usize)]
/// Arbitrary token FILE pseudo-pointers used by C to refer to the default streams.
enum File {
    Stdout = 0x7670cf00,
    Stderr = 0x9d118200,
}

impl TryFrom<usize> for File {
    type Error = &'static str;

    fn try_from(value: usize) -> Result<Self, Self::Error> {
        match value {
            x if x == File::Stdout as _ => Ok(File::Stdout),
            x if x == File::Stderr as _ => Ok(File::Stderr),
            _ => Err("Received Invalid FILE* from C"),
        }
    }
}

#[no_mangle]
static stdout: File = File::Stdout;
#[no_mangle]
static stderr: File = File::Stderr;

#[no_mangle]
extern "C" fn fputs(c_str: *const c_char, stream: usize) -> c_int {
    // SAFETY - Just like libc, we need to assume that `s` is a valid NULL-terminated string.
    let c_str = unsafe { CStr::from_ptr(c_str) };

    if let (Ok(s), Ok(_)) = (c_str.to_str(), File::try_from(stream)) {
        console::write_str(s);
        0
    } else {
        set_errno(EOF);
        EOF
    }
}

#[no_mangle]
extern "C" fn fwrite(ptr: *const c_void, size: usize, nmemb: usize, stream: usize) -> usize {
    let length = size.saturating_mul(nmemb);

    // SAFETY - Just like libc, we need to assume that `ptr` is valid.
    let bytes = unsafe { slice::from_raw_parts(ptr as *const u8, length) };

    if let (Ok(s), Ok(_)) = (str::from_utf8(bytes), File::try_from(stream)) {
        console::write_str(s);
        length
    } else {
        0
    }
}

#[no_mangle]
extern "C" fn strerror(n: c_int) -> *mut c_char {
    // Messages taken from errno(1).
    let s = match n {
        0 => "Success",
        1 => "Operation not permitted",
        2 => "No such file or directory",
        3 => "No such process",
        4 => "Interrupted system call",
        5 => "Input/output error",
        6 => "No such device or address",
        7 => "Argument list too long",
        8 => "Exec format error",
        9 => "Bad file descriptor",
        10 => "No child processes",
        11 => "Resource temporarily unavailable",
        12 => "Cannot allocate memory",
        13 => "Permission denied",
        14 => "Bad address",
        15 => "Block device required",
        16 => "Device or resource busy",
        17 => "File exists",
        18 => "Invalid cross-device link",
        19 => "No such device",
        20 => "Not a directory",
        21 => "Is a directory",
        22 => "Invalid argument",
        23 => "Too many open files in system",
        24 => "Too many open files",
        25 => "Inappropriate ioctl for device",
        26 => "Text file busy",
        27 => "File too large",
        28 => "No space left on device",
        29 => "Illegal seek",
        30 => "Read-only file system",
        31 => "Too many links",
        32 => "Broken pipe",
        33 => "Numerical argument out of domain",
        34 => "Numerical result out of range",
        35 => "Resource deadlock avoided",
        36 => "File name too long",
        37 => "No locks available",
        38 => "Function not implemented",
        39 => "Directory not empty",
        40 => "Too many levels of symbolic links",
        42 => "No message of desired type",
        43 => "Identifier removed",
        44 => "Channel number out of range",
        45 => "Level 2 not synchronized",
        46 => "Level 3 halted",
        47 => "Level 3 reset",
        48 => "Link number out of range",
        49 => "Protocol driver not attached",
        50 => "No CSI structure available",
        51 => "Level 2 halted",
        52 => "Invalid exchange",
        53 => "Invalid request descriptor",
        54 => "Exchange full",
        55 => "No anode",
        56 => "Invalid request code",
        57 => "Invalid slot",
        59 => "Bad font file format",
        60 => "Device not a stream",
        61 => "No data available",
        62 => "Timer expired",
        63 => "Out of streams resources",
        64 => "Machine is not on the network",
        65 => "Package not installed",
        66 => "Object is remote",
        67 => "Link has been severed",
        68 => "Advertise error",
        69 => "Srmount error",
        70 => "Communication error on send",
        71 => "Protocol error",
        72 => "Multihop attempted",
        73 => "RFS specific error",
        74 => "Bad message",
        75 => "Value too large for defined data type",
        76 => "Name not unique on network",
        77 => "File descriptor in bad state",
        78 => "Remote address changed",
        79 => "Can not access a needed shared library",
        80 => "Accessing a corrupted shared library",
        81 => ".lib section in a.out corrupted",
        82 => "Attempting to link in too many shared libraries",
        83 => "Cannot exec a shared library directly",
        84 => "Invalid or incomplete multibyte or wide character",
        85 => "Interrupted system call should be restarted",
        86 => "Streams pipe error",
        87 => "Too many users",
        88 => "Socket operation on non-socket",
        89 => "Destination address required",
        90 => "Message too long",
        91 => "Protocol wrong type for socket",
        92 => "Protocol not available",
        93 => "Protocol not supported",
        94 => "Socket type not supported",
        95 => "Operation not supported",
        96 => "Protocol family not supported",
        97 => "Address family not supported by protocol",
        98 => "Address already in use",
        99 => "Cannot assign requested address",
        100 => "Network is down",
        101 => "Network is unreachable",
        102 => "Network dropped connection on reset",
        103 => "Software caused connection abort",
        104 => "Connection reset by peer",
        105 => "No buffer space available",
        106 => "Transport endpoint is already connected",
        107 => "Transport endpoint is not connected",
        108 => "Cannot send after transport endpoint shutdown",
        109 => "Too many references: cannot splice",
        110 => "Connection timed out",
        111 => "Connection refused",
        112 => "Host is down",
        113 => "No route to host",
        114 => "Operation already in progress",
        115 => "Operation now in progress",
        116 => "Stale file handle",
        117 => "Structure needs cleaning",
        118 => "Not a XENIX named type file",
        119 => "No XENIX semaphores available",
        120 => "Is a named type file",
        121 => "Remote I/O error",
        122 => "Disk quota exceeded",
        123 => "No medium found",
        124 => "Wrong medium type",
        125 => "Operation canceled",
        126 => "Required key not available",
        127 => "Key has expired",
        128 => "Key has been revoked",
        129 => "Key was rejected by service",
        130 => "Owner died",
        131 => "State not recoverable",
        132 => "Operation not possible due to RF-kill",
        133 => "Memory page has hardware error",
        _ => "Unknown errno value",
    };

    s.as_ptr().cast_mut().cast()
}
