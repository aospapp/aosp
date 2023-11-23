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

//! Memory layout.

use core::ops::Range;
use core::ptr::addr_of;

/// Get an address from a linker-defined symbol.
#[macro_export]
macro_rules! linker_addr {
    ($symbol:ident) => {{
        unsafe { addr_of!($crate::linker::$symbol) as usize }
    }};
}

/// Get the address range between a pair of linker-defined symbols.
#[macro_export]
macro_rules! linker_region {
    ($begin:ident,$end:ident) => {{
        let start = linker_addr!($begin);
        let end = linker_addr!($end);

        start..end
    }};
}

/// Memory reserved for the DTB.
pub fn dtb_range() -> Range<usize> {
    linker_region!(dtb_begin, dtb_end)
}

/// Executable code.
pub fn text_range() -> Range<usize> {
    linker_region!(text_begin, text_end)
}

/// Read-only data.
pub fn rodata_range() -> Range<usize> {
    linker_region!(rodata_begin, rodata_end)
}

/// Initialised writable data.
pub fn data_range() -> Range<usize> {
    linker_region!(data_begin, data_end)
}

/// Zero-initialised writable data.
pub fn bss_range() -> Range<usize> {
    linker_region!(bss_begin, bss_end)
}

/// Writable data region for the stack.
pub fn stack_range(stack_size: usize) -> Range<usize> {
    let end = linker_addr!(init_stack_pointer);
    let start = end.checked_sub(stack_size).unwrap();
    assert!(start >= linker_addr!(stack_limit));

    start..end
}

/// All writable sections, excluding the stack.
pub fn scratch_range() -> Range<usize> {
    linker_region!(eh_stack_limit, bss_end)
}

/// Read-write data (original).
pub fn data_load_address() -> usize {
    linker_addr!(data_lma)
}

/// End of the binary image.
pub fn binary_end() -> usize {
    linker_addr!(bin_end)
}
