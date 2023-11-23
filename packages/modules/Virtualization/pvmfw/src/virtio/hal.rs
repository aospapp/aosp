// Copyright 2023, The Android Open Source Project
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

//! HAL for the virtio_drivers crate.

use super::pci::PCI_INFO;
use crate::helpers::RangeExt as _;
use crate::memory::{alloc_shared, dealloc_shared, phys_to_virt, virt_to_phys};
use core::alloc::Layout;
use core::mem::size_of;
use core::ptr::{copy_nonoverlapping, NonNull};
use log::trace;
use virtio_drivers::{BufferDirection, Hal, PhysAddr, PAGE_SIZE};

pub struct HalImpl;

/// Implements the `Hal` trait for `HalImpl`.
///
/// # Safety
///
/// Callers of this implementatation must follow the safety requirements documented in the `Hal`
/// trait for the unsafe methods.
unsafe impl Hal for HalImpl {
    /// Allocates the given number of contiguous physical pages of DMA memory for VirtIO use.
    ///
    /// # Implementation Safety
    ///
    /// `dma_alloc` ensures the returned DMA buffer is not aliased with any other allocation or
    ///  reference in the program until it is deallocated by `dma_dealloc` by allocating a unique
    ///  block of memory using `alloc_shared` and returning a non-null pointer to it that is
    ///  aligned to `PAGE_SIZE`.
    fn dma_alloc(pages: usize, _direction: BufferDirection) -> (PhysAddr, NonNull<u8>) {
        let vaddr = alloc_shared(dma_layout(pages))
            .expect("Failed to allocate and share VirtIO DMA range with host");
        // TODO(ptosi): Move this zeroing to virtio_drivers, if it silently wants a zeroed region.
        // SAFETY - vaddr points to a region allocated for the caller so is safe to access.
        unsafe { core::ptr::write_bytes(vaddr.as_ptr(), 0, dma_layout(pages).size()) };
        let paddr = virt_to_phys(vaddr);
        (paddr, vaddr)
    }

    unsafe fn dma_dealloc(_paddr: PhysAddr, vaddr: NonNull<u8>, pages: usize) -> i32 {
        // SAFETY - Memory was allocated by `dma_alloc` using `alloc_shared` with the same size.
        unsafe { dealloc_shared(vaddr, dma_layout(pages)) }
            .expect("Failed to unshare VirtIO DMA range with host");
        0
    }

    /// Converts a physical address used for MMIO to a virtual address which the driver can access.
    ///
    /// # Implementation Safety
    ///
    /// `mmio_phys_to_virt` satisfies the requirement by checking that the mapped memory region
    /// is within the PCI MMIO range.
    unsafe fn mmio_phys_to_virt(paddr: PhysAddr, size: usize) -> NonNull<u8> {
        let pci_info = PCI_INFO.get().expect("VirtIO HAL used before PCI_INFO was initialised");
        let bar_range = {
            let start = pci_info.bar_range.start.try_into().unwrap();
            let end = pci_info.bar_range.end.try_into().unwrap();

            start..end
        };
        let mmio_range = paddr..paddr.checked_add(size).expect("PCI MMIO region end overflowed");

        // Check that the region is within the PCI MMIO range that we read from the device tree. If
        // not, the host is probably trying to do something malicious.
        assert!(
            mmio_range.is_within(&bar_range),
            "PCI MMIO region was outside of expected BAR range.",
        );

        phys_to_virt(paddr)
    }

    unsafe fn share(buffer: NonNull<[u8]>, direction: BufferDirection) -> PhysAddr {
        let size = buffer.len();

        let bounce = alloc_shared(bb_layout(size))
            .expect("Failed to allocate and share VirtIO bounce buffer with host");
        let paddr = virt_to_phys(bounce);
        if direction == BufferDirection::DriverToDevice {
            let src = buffer.cast::<u8>().as_ptr().cast_const();
            trace!("VirtIO bounce buffer at {bounce:?} (PA:{paddr:#x}) initialized from {src:?}");
            // SAFETY - Both regions are valid, properly aligned, and don't overlap.
            unsafe { copy_nonoverlapping(src, bounce.as_ptr(), size) };
        }

        paddr
    }

    unsafe fn unshare(paddr: PhysAddr, buffer: NonNull<[u8]>, direction: BufferDirection) {
        let bounce = phys_to_virt(paddr);
        let size = buffer.len();
        if direction == BufferDirection::DeviceToDriver {
            let dest = buffer.cast::<u8>().as_ptr();
            trace!("VirtIO bounce buffer at {bounce:?} (PA:{paddr:#x}) copied back to {dest:?}");
            // SAFETY - Both regions are valid, properly aligned, and don't overlap.
            unsafe { copy_nonoverlapping(bounce.as_ptr(), dest, size) };
        }

        // SAFETY - Memory was allocated by `share` using `alloc_shared` with the same size.
        unsafe { dealloc_shared(bounce, bb_layout(size)) }
            .expect("Failed to unshare and deallocate VirtIO bounce buffer");
    }
}

fn dma_layout(pages: usize) -> Layout {
    let size = pages.checked_mul(PAGE_SIZE).unwrap();
    Layout::from_size_align(size, PAGE_SIZE).unwrap()
}

fn bb_layout(size: usize) -> Layout {
    // In theory, it would be legal to align to 1-byte but use a larger alignment for good measure.
    const VIRTIO_BOUNCE_BUFFER_ALIGN: usize = size_of::<u128>();
    Layout::from_size_align(size, VIRTIO_BOUNCE_BUFFER_ALIGN).unwrap()
}
