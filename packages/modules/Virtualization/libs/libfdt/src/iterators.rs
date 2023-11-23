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

//! Iterators over cells, and various layers on top of them.

use crate::{AddrCells, SizeCells};
use core::marker::PhantomData;
use core::{mem::size_of, ops::Range, slice::ChunksExact};

/// Iterator over cells of a DT property.
#[derive(Debug)]
pub struct CellIterator<'a> {
    chunks: ChunksExact<'a, u8>,
}

impl<'a> CellIterator<'a> {
    pub(crate) fn new(bytes: &'a [u8]) -> Self {
        const CHUNK_SIZE: usize = size_of::<<CellIterator as Iterator>::Item>();

        Self { chunks: bytes.chunks_exact(CHUNK_SIZE) }
    }
}

impl<'a> Iterator for CellIterator<'a> {
    type Item = u32;

    fn next(&mut self) -> Option<Self::Item> {
        Some(Self::Item::from_be_bytes(self.chunks.next()?.try_into().ok()?))
    }
}

/// Iterator over a 'reg' property of a DT node.
#[derive(Debug)]
pub struct RegIterator<'a> {
    cells: CellIterator<'a>,
    addr_cells: AddrCells,
    size_cells: SizeCells,
}

/// Represents a contiguous region within the address space defined by the parent bus.
/// Commonly means the offsets and lengths of MMIO blocks, but may have a different meaning on some
/// bus types. Addresses in the address space defined by the root node are CPU real addresses.
#[derive(Copy, Clone, Debug)]
pub struct Reg<T> {
    /// Base address of the region.
    pub addr: T,
    /// Size of the region (optional).
    pub size: Option<T>,
}

impl<'a> RegIterator<'a> {
    pub(crate) fn new(
        cells: CellIterator<'a>,
        addr_cells: AddrCells,
        size_cells: SizeCells,
    ) -> Self {
        Self { cells, addr_cells, size_cells }
    }
}

impl<'a> Iterator for RegIterator<'a> {
    type Item = Reg<u64>;

    fn next(&mut self) -> Option<Self::Item> {
        let addr = FromAddrCells::from_addr_cells(&mut self.cells, self.addr_cells)?;
        // If the parent node specifies a value of 0 for #size-cells, 'size' shall be omitted.
        let size = if self.size_cells == SizeCells::None {
            None
        } else {
            Some(FromSizeCells::from_size_cells(&mut self.cells, self.size_cells)?)
        };

        Some(Self::Item { addr, size })
    }
}

// Converts two cells into bytes of the same size
fn two_cells_to_bytes(cells: [u32; 2]) -> [u8; 2 * size_of::<u32>()] {
    // SAFETY: the size of the two arrays are the same
    unsafe { core::mem::transmute::<[u32; 2], [u8; 2 * size_of::<u32>()]>(cells) }
}

impl Reg<u64> {
    const NUM_CELLS: usize = 2;
    /// Converts addr and (optional) size to the format that is consumable by libfdt.
    pub fn to_cells(
        &self,
    ) -> ([u8; Self::NUM_CELLS * size_of::<u32>()], Option<[u8; Self::NUM_CELLS * size_of::<u32>()]>)
    {
        let addr =
            two_cells_to_bytes([((self.addr >> 32) as u32).to_be(), (self.addr as u32).to_be()]);
        let size = if self.size.is_some() {
            let size = self.size.unwrap();
            Some(two_cells_to_bytes([((size >> 32) as u32).to_be(), (size as u32).to_be()]))
        } else {
            None
        };
        (addr, size)
    }
}

/// Iterator over the address ranges defined by the /memory/ node.
#[derive(Debug)]
pub struct MemRegIterator<'a> {
    reg: RegIterator<'a>,
}

impl<'a> MemRegIterator<'a> {
    pub(crate) fn new(reg: RegIterator<'a>) -> Self {
        Self { reg }
    }
}

impl<'a> Iterator for MemRegIterator<'a> {
    type Item = Range<usize>;

    fn next(&mut self) -> Option<Self::Item> {
        let next = self.reg.next()?;
        let addr = usize::try_from(next.addr).ok()?;
        let size = usize::try_from(next.size?).ok()?;

        Some(addr..addr.checked_add(size)?)
    }
}

/// Iterator over the 'ranges' property of a DT node.
#[derive(Debug)]
pub struct RangesIterator<'a, A, P, S> {
    cells: CellIterator<'a>,
    addr_cells: AddrCells,
    parent_addr_cells: AddrCells,
    size_cells: SizeCells,
    _addr: PhantomData<A>,
    _parent_addr: PhantomData<P>,
    _size: PhantomData<S>,
}

/// An address range from the 'ranges' property of a DT node.
#[derive(Clone, Debug, Default)]
pub struct AddressRange<A, P, S> {
    /// The physical address of the range within the child bus's address space.
    pub addr: A,
    /// The physical address of the range in the parent bus's address space.
    pub parent_addr: P,
    /// The size of the range in the child's address space.
    pub size: S,
}

impl<'a, A, P, S> RangesIterator<'a, A, P, S> {
    pub(crate) fn new(
        cells: CellIterator<'a>,
        addr_cells: AddrCells,
        parent_addr_cells: AddrCells,
        size_cells: SizeCells,
    ) -> Self {
        Self {
            cells,
            addr_cells,
            parent_addr_cells,
            size_cells,
            _addr: Default::default(),
            _parent_addr: Default::default(),
            _size: Default::default(),
        }
    }
}

impl<'a, A: FromAddrCells, P: FromAddrCells, S: FromSizeCells> Iterator
    for RangesIterator<'a, A, P, S>
{
    type Item = AddressRange<A, P, S>;

    fn next(&mut self) -> Option<Self::Item> {
        let addr = FromAddrCells::from_addr_cells(&mut self.cells, self.addr_cells)?;
        let parent_addr = FromAddrCells::from_addr_cells(&mut self.cells, self.parent_addr_cells)?;
        let size = FromSizeCells::from_size_cells(&mut self.cells, self.size_cells)?;
        Some(AddressRange { addr, parent_addr, size })
    }
}

trait FromAddrCells: Sized {
    fn from_addr_cells(cells: &mut CellIterator, cell_count: AddrCells) -> Option<Self>;
}

impl FromAddrCells for u64 {
    fn from_addr_cells(cells: &mut CellIterator, cell_count: AddrCells) -> Option<Self> {
        Some(match cell_count {
            AddrCells::Single => cells.next()?.into(),
            AddrCells::Double => (cells.next()? as Self) << 32 | cells.next()? as Self,
            _ => panic!("Invalid addr_cells {:?} for u64", cell_count),
        })
    }
}

impl FromAddrCells for (u32, u64) {
    fn from_addr_cells(cells: &mut CellIterator, cell_count: AddrCells) -> Option<Self> {
        Some(match cell_count {
            AddrCells::Triple => {
                (cells.next()?, (cells.next()? as u64) << 32 | cells.next()? as u64)
            }
            _ => panic!("Invalid addr_cells {:?} for (u32, u64)", cell_count),
        })
    }
}

trait FromSizeCells: Sized {
    fn from_size_cells(cells: &mut CellIterator, cell_count: SizeCells) -> Option<Self>;
}

impl FromSizeCells for u64 {
    fn from_size_cells(cells: &mut CellIterator, cell_count: SizeCells) -> Option<Self> {
        Some(match cell_count {
            SizeCells::Single => cells.next()?.into(),
            SizeCells::Double => (cells.next()? as Self) << 32 | cells.next()? as Self,
            _ => panic!("Invalid size_cells {:?} for u64", cell_count),
        })
    }
}

impl AddressRange<(u32, u64), u64, u64> {
    const SIZE_CELLS: usize = 7;
    /// Converts to the format that is consumable by libfdt
    pub fn to_cells(&self) -> [u8; Self::SIZE_CELLS * size_of::<u32>()] {
        let buf = [
            self.addr.0.to_be(),
            ((self.addr.1 >> 32) as u32).to_be(),
            (self.addr.1 as u32).to_be(),
            ((self.parent_addr >> 32) as u32).to_be(),
            (self.parent_addr as u32).to_be(),
            ((self.size >> 32) as u32).to_be(),
            (self.size as u32).to_be(),
        ];
        // SAFETY: the size of the two arrays are the same
        unsafe {
            core::mem::transmute::<[u32; Self::SIZE_CELLS], [u8; Self::SIZE_CELLS * size_of::<u32>()]>(
                buf,
            )
        }
    }
}
