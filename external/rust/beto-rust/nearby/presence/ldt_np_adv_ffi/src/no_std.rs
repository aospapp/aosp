// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// mod to handle all of the impls needed for no_std

use libc_alloc::LibcAlloc;

extern crate panic_abort;

#[global_allocator]
static ALLOCATOR: LibcAlloc = LibcAlloc;

#[lang = "eh_personality"]
extern "C" fn eh_personality() {}

#[alloc_error_handler]
#[allow(clippy::panic)]
fn default_handler(layout: core::alloc::Layout) -> ! {
    panic!("memory allocation of {} bytes failed", layout.size())
}
