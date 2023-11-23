// Copyright (C) 2019 The Android Open Source Project
// Copyright (C) 2019 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#if PLATFORM_SDK_VERSION < 26
#include <cutils/log.h>
#else
#include <log/log.h>
#endif

#include "goldfish_address_space.h"

#ifdef HOST_BUILD

#include "android/base/SubAllocator.h"
#include "android/base/memory/LazyInstance.h"

// AddressSpaceHost is a global class for obtaining physical addresses
// for the host-side build.
class AddressSpaceHost {
public:
    AddressSpaceHost() : mAlloc(0, 16ULL * 1024ULL * 1048576ULL, 4096) { }
    uint64_t alloc(size_t size) {
        return (uint64_t)(uintptr_t)mAlloc.alloc(size);
    }
    void free(uint64_t addr) {
        mAlloc.free((void*)(uintptr_t)addr);
    }
private:
    android::base::SubAllocator mAlloc;
};

static android::base::LazyInstance<AddressSpaceHost> sAddressSpaceHost =
    LAZY_INSTANCE_INIT;

// It may seem like there should be one allocator per provider,
// but to properly reflect the guest behavior where there is only one
// allocator in the system and multiple providers are possible,
// we need to have a separate global object (sAddressSpaceHost).
GoldfishAddressSpaceBlockProvider::GoldfishAddressSpaceBlockProvider()
    : mAlloc(sAddressSpaceHost.ptr()) {}

GoldfishAddressSpaceBlockProvider::~GoldfishAddressSpaceBlockProvider() { }

uint64_t GoldfishAddressSpaceBlockProvider::allocPhys(size_t size) {
    AddressSpaceHost* hostAlloc = reinterpret_cast<AddressSpaceHost*>(mAlloc);
    return hostAlloc->alloc(size);
}

void GoldfishAddressSpaceBlockProvider::freePhys(uint64_t phys) {
    AddressSpaceHost* hostAlloc = reinterpret_cast<AddressSpaceHost*>(mAlloc);
    return hostAlloc->free(phys);
}

GoldfishAddressSpaceBlock::GoldfishAddressSpaceBlock() :
    m_alloced(false), m_guest_ptr(NULL), m_phys_addr(0), m_provider(NULL) {}
GoldfishAddressSpaceBlock::~GoldfishAddressSpaceBlock() { destroy(); }

GoldfishAddressSpaceBlock &GoldfishAddressSpaceBlock::operator=(const GoldfishAddressSpaceBlock &rhs)
{
    m_guest_ptr = rhs.m_guest_ptr;
    m_phys_addr = rhs.m_phys_addr;
    m_provider = rhs.m_provider;
    return *this;
}

bool GoldfishAddressSpaceBlock::allocate(GoldfishAddressSpaceBlockProvider *provider, size_t size)
{
    destroy();
    m_phys_addr = provider->allocPhys(size);
    m_alloced = true;
    m_provider = provider;
    return true;
}

uint64_t GoldfishAddressSpaceBlock::physAddr() const
{
    return m_phys_addr;
}

uint64_t GoldfishAddressSpaceBlock::hostAddr() const
{
    return 42;  // some random number, not used
}

void *GoldfishAddressSpaceBlock::mmap(uint64_t opaque)
{
    m_guest_ptr = reinterpret_cast<void *>(opaque);
    return m_guest_ptr;
}

void *GoldfishAddressSpaceBlock::guestPtr() const
{
    return m_guest_ptr;
}

void GoldfishAddressSpaceBlock::destroy()
{
    if (m_alloced) {
        m_guest_ptr = NULL;
        if (m_provider) {
            m_provider->freePhys(m_phys_addr);
        }
        m_alloced = false;
    }
}

void GoldfishAddressSpaceBlock::replace(GoldfishAddressSpaceBlock *other)
{
    if (other) {
        this->m_guest_ptr = other->m_guest_ptr;
        other->m_guest_ptr = NULL;
    } else {
        this->m_guest_ptr = NULL;
    }
}
#elif __Fuchsia__
#include <fcntl.h>
#include <fuchsia/hardware/goldfish/address/space/c/fidl.h>
#include <lib/fdio/fdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <zircon/process.h>
#include <zircon/syscalls.h>
#include <zircon/syscalls/object.h>

GoldfishAddressSpaceBlockProvider::GoldfishAddressSpaceBlockProvider() {
    fdio_get_service_handle(::open(GOLDFISH_ADDRESS_SPACE_DEVICE_NAME, O_RDWR), &m_channel);
}

GoldfishAddressSpaceBlockProvider::~GoldfishAddressSpaceBlockProvider()
{
    zx_handle_close(m_channel);
}

GoldfishAddressSpaceBlock::GoldfishAddressSpaceBlock()
    : m_vmo(ZX_HANDLE_INVALID)
    , m_channel(ZX_HANDLE_INVALID)
    , m_mmaped_ptr(NULL)
    , m_phys_addr(0)
    , m_host_addr(0)
    , m_offset(0)
    , m_size(0) {}

GoldfishAddressSpaceBlock::~GoldfishAddressSpaceBlock()
{
    destroy();
}

GoldfishAddressSpaceBlock &GoldfishAddressSpaceBlock::operator=(const GoldfishAddressSpaceBlock &rhs)
{
    m_vmo = rhs.m_vmo;
    m_mmaped_ptr = rhs.m_mmaped_ptr;
    m_phys_addr = rhs.m_phys_addr;
    m_host_addr = rhs.m_host_addr;
    m_offset = rhs.m_offset;
    m_size = rhs.m_size;
    m_channel = rhs.m_channel;

    return *this;
}

bool GoldfishAddressSpaceBlock::allocate(GoldfishAddressSpaceBlockProvider *provider, size_t size)
{
    ALOGD("%s: Ask for block of size 0x%llx\n", __func__,
         (unsigned long long)size);

    destroy();

    if (!provider->is_opened()) {
        return false;
    }

    int32_t res = ZX_OK;
    zx_status_t status =
        fuchsia_hardware_goldfish_address_space_DeviceAllocateBlock(
            provider->m_channel, size, &res, &m_phys_addr, &m_vmo);
    if (status != ZX_OK || res != ZX_OK) {
        ALOGE("%s: allocate block failed: %d:%d", __func__, status, res);
        return false;
    }

    m_offset = 0;
    m_size = size;

    ALOGD("%s: allocate returned offset 0x%llx size 0x%llx\n", __func__,
          (unsigned long long)m_offset,
          (unsigned long long)m_size);

    m_channel = provider->m_channel;
    return true;
}

uint64_t GoldfishAddressSpaceBlock::physAddr() const
{
    return m_phys_addr;
}

uint64_t GoldfishAddressSpaceBlock::hostAddr() const
{
    return m_host_addr;
}

void *GoldfishAddressSpaceBlock::mmap(uint64_t host_addr)
{
    if (m_size == 0) {
        ALOGE("%s: called with zero size\n", __func__);
        return NULL;
    }
    if (m_mmaped_ptr) {
        ALOGE("'mmap' called for an already mmaped address block");
        ::abort();
    }

    zx_vaddr_t ptr = 0;
    zx_status_t status = zx_vmar_map(zx_vmar_root_self(),
                                     ZX_VM_PERM_READ | ZX_VM_PERM_WRITE,
                                     0, m_vmo,
                                     m_offset,
                                     m_size,
                                     &ptr);
    if (status != ZX_OK) {
        ALOGE("%s: host memory map failed with size 0x%llx "
              "off 0x%llx status %d\n",
              __func__,
              (unsigned long long)m_size,
              (unsigned long long)m_offset, status);
        return NULL;
    } else {
        m_mmaped_ptr = (void*)ptr;
        m_host_addr = host_addr;
        return guestPtr();
    }
}

void *GoldfishAddressSpaceBlock::guestPtr() const
{
    return reinterpret_cast<char *>(m_mmaped_ptr) + (m_host_addr & (PAGE_SIZE - 1));
}

void GoldfishAddressSpaceBlock::destroy()
{
    if (m_mmaped_ptr && m_size) {
        zx_vmar_unmap(zx_vmar_root_self(),
                      (zx_vaddr_t)m_mmaped_ptr,
                      m_size);
        m_mmaped_ptr = NULL;
    }

    if (m_size) {
        zx_handle_close(m_vmo);
        m_vmo = ZX_HANDLE_INVALID;
        int32_t res = ZX_OK;
        zx_status_t status =
            fuchsia_hardware_goldfish_address_space_DeviceDeallocateBlock(
                m_channel, m_phys_addr, &res);
        if (status != ZX_OK || res != ZX_OK) {
            ALOGE("%s: deallocate block failed: %d:%d", __func__, status, res);
        }
        m_channel = ZX_HANDLE_INVALID;
        m_phys_addr = 0;
        m_host_addr = 0;
        m_offset = 0;
        m_size = 0;
    }
}

void GoldfishAddressSpaceBlock::replace(GoldfishAddressSpaceBlock *other)
{
    destroy();

    if (other) {
        *this = *other;
        *other = GoldfishAddressSpaceBlock();
    }
}

bool GoldfishAddressSpaceBlockProvider::is_opened()
{
    return m_channel != ZX_HANDLE_INVALID;
}
#else
#include <linux/types.h>
#include <linux/ioctl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdlib>
#include <errno.h>

struct goldfish_address_space_allocate_block {
    __u64 size;
    __u64 offset;
    __u64 phys_addr;
};

#define GOLDFISH_ADDRESS_SPACE_IOCTL_MAGIC		'G'
#define GOLDFISH_ADDRESS_SPACE_IOCTL_OP(OP, T)		_IOWR(GOLDFISH_ADDRESS_SPACE_IOCTL_MAGIC, OP, T)
#define GOLDFISH_ADDRESS_SPACE_IOCTL_ALLOCATE_BLOCK	GOLDFISH_ADDRESS_SPACE_IOCTL_OP(10, struct goldfish_address_space_allocate_block)
#define GOLDFISH_ADDRESS_SPACE_IOCTL_DEALLOCATE_BLOCK	GOLDFISH_ADDRESS_SPACE_IOCTL_OP(11, __u64)

const char GOLDFISH_ADDRESS_SPACE_DEVICE_NAME[] = "/dev/goldfish_address_space";

GoldfishAddressSpaceBlockProvider::GoldfishAddressSpaceBlockProvider()
    : m_fd(::open(GOLDFISH_ADDRESS_SPACE_DEVICE_NAME, O_RDWR)) {}

GoldfishAddressSpaceBlockProvider::~GoldfishAddressSpaceBlockProvider()
{
    ::close(m_fd);
}

GoldfishAddressSpaceBlock::GoldfishAddressSpaceBlock()
    : m_mmaped_ptr(NULL)
    , m_phys_addr(0)
    , m_host_addr(0)
    , m_offset(0)
    , m_size(0)
    , m_fd(-1) {}

GoldfishAddressSpaceBlock::~GoldfishAddressSpaceBlock()
{
    destroy();
}

GoldfishAddressSpaceBlock &GoldfishAddressSpaceBlock::operator=(const GoldfishAddressSpaceBlock &rhs)
{
    m_mmaped_ptr = rhs.m_mmaped_ptr;
    m_phys_addr = rhs.m_phys_addr;
    m_host_addr = rhs.m_host_addr;
    m_offset = rhs.m_offset;
    m_size = rhs.m_size;
    m_fd = rhs.m_fd;

    return *this;
}

bool GoldfishAddressSpaceBlock::allocate(GoldfishAddressSpaceBlockProvider *provider, size_t size)
{

    ALOGD("%s: Ask for block of size 0x%llx\n", __func__,
         (unsigned long long)size);

    destroy();

    if (!provider->is_opened()) {
        return false;
    }

    struct goldfish_address_space_allocate_block request;
    long res;

    request.size = size;
    res = ::ioctl(provider->m_fd, GOLDFISH_ADDRESS_SPACE_IOCTL_ALLOCATE_BLOCK, &request);
    if (res) {
        return false;
    } else {
        m_phys_addr = request.phys_addr;

        m_offset = request.offset;
        m_size = request.size;

        ALOGD("%s: ioctl allocate returned offset 0x%llx size 0x%llx\n", __func__,
                (unsigned long long)m_offset,
                (unsigned long long)m_size);

        m_fd = provider->m_fd;
        return true;
    }
}

uint64_t GoldfishAddressSpaceBlock::physAddr() const
{
    return m_phys_addr;
}

uint64_t GoldfishAddressSpaceBlock::hostAddr() const
{
    return m_host_addr;
}

void *GoldfishAddressSpaceBlock::mmap(uint64_t host_addr)
{
    if (m_size == 0) {
        ALOGE("%s: called with zero size\n", __func__);
        return NULL;
    }
    if (m_mmaped_ptr) {
        ALOGE("'mmap' called for an already mmaped address block");
        ::abort();
    }

    void *result = ::mmap64(NULL, m_size, PROT_WRITE, MAP_SHARED, m_fd, m_offset);
    if (result == MAP_FAILED) {
        ALOGE("%s: host memory map failed with size 0x%llx "
              "off 0x%llx errno %d\n",
              __func__,
              (unsigned long long)m_size,
              (unsigned long long)m_offset, errno);
        return NULL;
    } else {
        m_mmaped_ptr = result;
        m_host_addr = host_addr;
        return guestPtr();
    }
}

void *GoldfishAddressSpaceBlock::guestPtr() const
{
    return reinterpret_cast<char *>(m_mmaped_ptr) + (m_host_addr & (PAGE_SIZE - 1));
}

void GoldfishAddressSpaceBlock::destroy()
{
    if (m_mmaped_ptr && m_size) {
        ::munmap(m_mmaped_ptr, m_size);
        m_mmaped_ptr = NULL;
    }

    if (m_size) {
        ::ioctl(m_fd, GOLDFISH_ADDRESS_SPACE_IOCTL_DEALLOCATE_BLOCK, &m_offset);
        m_phys_addr = 0;
        m_host_addr = 0;
        m_offset = 0;
        m_size = 0;
    }
}

void GoldfishAddressSpaceBlock::replace(GoldfishAddressSpaceBlock *other)
{
    destroy();

    if (other) {
        *this = *other;
        *other = GoldfishAddressSpaceBlock();
    }
}

bool GoldfishAddressSpaceBlockProvider::is_opened()
{
    return m_fd >= 0;
}
#endif
