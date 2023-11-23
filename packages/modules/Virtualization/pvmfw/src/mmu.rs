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

//! Memory management.

use crate::helpers;
use crate::helpers::PVMFW_PAGE_SIZE;
use aarch64_paging::idmap::IdMap;
use aarch64_paging::paging::Attributes;
use aarch64_paging::paging::MemoryRegion;
use aarch64_paging::MapError;
use core::ops::Range;
use vmbase::layout;

// We assume that:
// - MAIR_EL1.Attr0 = "Device-nGnRE memory" (0b0000_0100)
// - MAIR_EL1.Attr1 = "Normal memory, Outer & Inner WB Non-transient, R/W-Allocate" (0b1111_1111)
const MEMORY: Attributes = Attributes::NORMAL.union(Attributes::NON_GLOBAL);
const DEVICE: Attributes = Attributes::DEVICE_NGNRE.union(Attributes::EXECUTE_NEVER);
const CODE: Attributes = MEMORY.union(Attributes::READ_ONLY);
const DATA: Attributes = MEMORY.union(Attributes::EXECUTE_NEVER);
const RODATA: Attributes = DATA.union(Attributes::READ_ONLY);

/// High-level API for managing MMU mappings.
pub struct PageTable {
    idmap: IdMap,
}

fn appended_payload_range() -> Range<usize> {
    let start = helpers::align_up(layout::binary_end(), helpers::SIZE_4KB).unwrap();
    // pvmfw is contained in a 2MiB region so the payload can't be larger than the 2MiB alignment.
    let end = helpers::align_up(start, helpers::SIZE_2MB).unwrap();

    start..end
}

/// Region allocated for the stack.
pub fn stack_range() -> Range<usize> {
    const STACK_PAGES: usize = 8;

    layout::stack_range(STACK_PAGES * PVMFW_PAGE_SIZE)
}

impl PageTable {
    const ASID: usize = 1;
    const ROOT_LEVEL: usize = 1;

    /// Creates an instance pre-populated with pvmfw's binary layout.
    pub fn from_static_layout() -> Result<Self, MapError> {
        let mut page_table = Self { idmap: IdMap::new(Self::ASID, Self::ROOT_LEVEL) };

        page_table.map_code(&layout::text_range())?;
        page_table.map_data(&layout::scratch_range())?;
        page_table.map_data(&stack_range())?;
        page_table.map_rodata(&layout::rodata_range())?;
        page_table.map_data(&appended_payload_range())?;

        Ok(page_table)
    }

    pub unsafe fn activate(&mut self) {
        self.idmap.activate()
    }

    pub fn map_device(&mut self, range: &Range<usize>) -> Result<(), MapError> {
        self.map_range(range, DEVICE)
    }

    pub fn map_data(&mut self, range: &Range<usize>) -> Result<(), MapError> {
        self.map_range(range, DATA)
    }

    pub fn map_code(&mut self, range: &Range<usize>) -> Result<(), MapError> {
        self.map_range(range, CODE)
    }

    pub fn map_rodata(&mut self, range: &Range<usize>) -> Result<(), MapError> {
        self.map_range(range, RODATA)
    }

    fn map_range(&mut self, range: &Range<usize>, attr: Attributes) -> Result<(), MapError> {
        self.idmap.map_range(&MemoryRegion::new(range.start, range.end), attr)
    }
}
