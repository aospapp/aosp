/*
 * Copyright (C) 2018 Google, Inc.
 *
 * This software is licensed under the terms of the GNU General Public
 * License version 2, as published by the Free Software Foundation, and
 * may be copied, distributed, and modified under those terms.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 */

#ifndef ANDROID_INCLUDE_HARDWARE_GOLDFISH_ADDRESS_SPACE_H
#define ANDROID_INCLUDE_HARDWARE_GOLDFISH_ADDRESS_SPACE_H

#include <inttypes.h>
#include <stddef.h>

class GoldfishAddressSpaceBlock;

#ifdef HOST_BUILD
class GoldfishAddressSpaceBlockProvider {
public:
    GoldfishAddressSpaceBlockProvider();
    ~GoldfishAddressSpaceBlockProvider();

    uint64_t allocPhys(size_t size);
    void freePhys(uint64_t phys);

private:
   void* mAlloc;

   friend class GoldfishAddressSpaceBlock;
};
#else
class GoldfishAddressSpaceBlockProvider {
public:
    GoldfishAddressSpaceBlockProvider();
    ~GoldfishAddressSpaceBlockProvider();

private:
   GoldfishAddressSpaceBlockProvider(const GoldfishAddressSpaceBlockProvider &rhs);
   GoldfishAddressSpaceBlockProvider &operator=(const GoldfishAddressSpaceBlockProvider &rhs);

   bool is_opened();
#ifdef __Fuchsia__
   uint32_t m_channel;
#else
   int m_fd;
#endif

   friend class GoldfishAddressSpaceBlock;
};
#endif

class GoldfishAddressSpaceBlock {
public:
    GoldfishAddressSpaceBlock();
    ~GoldfishAddressSpaceBlock();

    bool allocate(GoldfishAddressSpaceBlockProvider *provider, size_t size);
    uint64_t physAddr() const;
    uint64_t hostAddr() const;
    void *mmap(uint64_t opaque);
    void *guestPtr() const;
    void replace(GoldfishAddressSpaceBlock *x);

private:
    void destroy();
    GoldfishAddressSpaceBlock &operator=(const GoldfishAddressSpaceBlock &);

#ifdef HOST_BUILD
    bool        m_alloced;
    void     *m_guest_ptr;
    uint64_t  m_phys_addr;
    GoldfishAddressSpaceBlockProvider* m_provider;
#else
#ifdef __Fuchsia__
    uint32_t  m_vmo;
    uint32_t  m_channel;
#else
    int       m_fd;
#endif
    void     *m_mmaped_ptr;
    uint64_t  m_phys_addr;
    uint64_t  m_host_addr;
    uint64_t  m_offset;
    size_t    m_size;
#endif
};

#endif
