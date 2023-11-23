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

//! Functions to scan the PCI bus for VirtIO device.

use aarch64_paging::paging::MemoryRegion;
use alloc::alloc::{alloc, dealloc, handle_alloc_error, Layout};
use core::{mem::size_of, ptr::NonNull};
use fdtpci::PciInfo;
use log::{debug, info};
use virtio_drivers::{
    device::{blk::VirtIOBlk, console::VirtIOConsole},
    transport::{
        pci::{bus::PciRoot, virtio_device_type, PciTransport},
        DeviceType, Transport,
    },
    BufferDirection, Hal, PhysAddr, PAGE_SIZE,
};

/// The standard sector size of a VirtIO block device, in bytes.
const SECTOR_SIZE_BYTES: usize = 512;

/// The size in sectors of the test block device we expect.
const EXPECTED_SECTOR_COUNT: usize = 4;

pub fn check_pci(pci_root: &mut PciRoot) {
    let mut checked_virtio_device_count = 0;
    for (device_function, info) in pci_root.enumerate_bus(0) {
        let (status, command) = pci_root.get_status_command(device_function);
        info!("Found {} at {}, status {:?} command {:?}", info, device_function, status, command);
        if let Some(virtio_type) = virtio_device_type(&info) {
            info!("  VirtIO {:?}", virtio_type);
            let mut transport = PciTransport::new::<HalImpl>(pci_root, device_function).unwrap();
            info!(
                "Detected virtio PCI device with device type {:?}, features {:#018x}",
                transport.device_type(),
                transport.read_device_features(),
            );
            if check_virtio_device(transport, virtio_type) {
                checked_virtio_device_count += 1;
            }
        }
    }

    assert_eq!(checked_virtio_device_count, 4);
}

/// Checks the given VirtIO device, if we know how to.
///
/// Returns true if the device was checked, or false if it was ignored.
fn check_virtio_device(transport: impl Transport, device_type: DeviceType) -> bool {
    match device_type {
        DeviceType::Block => {
            let mut blk =
                VirtIOBlk::<HalImpl, _>::new(transport).expect("failed to create blk driver");
            info!("Found {} KiB block device.", blk.capacity() * SECTOR_SIZE_BYTES as u64 / 1024);
            assert_eq!(blk.capacity(), EXPECTED_SECTOR_COUNT as u64);
            let mut data = [0; SECTOR_SIZE_BYTES * EXPECTED_SECTOR_COUNT];
            for i in 0..EXPECTED_SECTOR_COUNT {
                blk.read_block(i, &mut data[i * SECTOR_SIZE_BYTES..(i + 1) * SECTOR_SIZE_BYTES])
                    .expect("Failed to read block device.");
            }
            for (i, chunk) in data.chunks(size_of::<u32>()).enumerate() {
                assert_eq!(chunk, &(i as u32).to_le_bytes());
            }
            info!("Read expected data from block device.");
            true
        }
        DeviceType::Console => {
            let mut console = VirtIOConsole::<HalImpl, _>::new(transport)
                .expect("Failed to create VirtIO console driver");
            info!("Found console device: {:?}", console.info());
            for &c in b"Hello VirtIO console\n" {
                console.send(c).expect("Failed to send character to VirtIO console device");
            }
            info!("Wrote to VirtIO console.");
            true
        }
        _ => false,
    }
}

/// Gets the memory region in which BARs are allocated.
pub fn get_bar_region(pci_info: &PciInfo) -> MemoryRegion {
    MemoryRegion::new(pci_info.bar_range.start as usize, pci_info.bar_range.end as usize)
}

struct HalImpl;

unsafe impl Hal for HalImpl {
    fn dma_alloc(pages: usize, _direction: BufferDirection) -> (PhysAddr, NonNull<u8>) {
        debug!("dma_alloc: pages={}", pages);
        let layout = Layout::from_size_align(pages * PAGE_SIZE, PAGE_SIZE).unwrap();
        // Safe because the layout has a non-zero size.
        let vaddr = unsafe { alloc(layout) };
        let vaddr =
            if let Some(vaddr) = NonNull::new(vaddr) { vaddr } else { handle_alloc_error(layout) };
        let paddr = virt_to_phys(vaddr);
        (paddr, vaddr)
    }

    unsafe fn dma_dealloc(paddr: PhysAddr, vaddr: NonNull<u8>, pages: usize) -> i32 {
        debug!("dma_dealloc: paddr={:#x}, pages={}", paddr, pages);
        let layout = Layout::from_size_align(pages * PAGE_SIZE, PAGE_SIZE).unwrap();
        // Safe because the memory was allocated by `dma_alloc` above using the same allocator, and
        // the layout is the same as was used then.
        unsafe {
            dealloc(vaddr.as_ptr(), layout);
        }
        0
    }

    unsafe fn mmio_phys_to_virt(paddr: PhysAddr, _size: usize) -> NonNull<u8> {
        NonNull::new(paddr as _).unwrap()
    }

    unsafe fn share(buffer: NonNull<[u8]>, _direction: BufferDirection) -> PhysAddr {
        let vaddr = buffer.cast();
        // Nothing to do, as the host already has access to all memory.
        virt_to_phys(vaddr)
    }

    unsafe fn unshare(_paddr: PhysAddr, _buffer: NonNull<[u8]>, _direction: BufferDirection) {
        // Nothing to do, as the host already has access to all memory and we didn't copy the buffer
        // anywhere else.
    }
}

fn virt_to_phys(vaddr: NonNull<u8>) -> PhysAddr {
    vaddr.as_ptr() as _
}
