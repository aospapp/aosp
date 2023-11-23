// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
// Note: ported from Chromium commit head: 690c8b268457
// Note: only necessary functions are ported.

#include "unaligned_shared_memory.h"

#include <limits>

#include "base/logging.h"
#include "base/sys_info.h"

namespace media {

namespace {

bool CalculateMisalignmentAndOffset(size_t size,
                                    off_t offset,
                                    size_t* misalignment,
                                    off_t* adjusted_offset) {
  /* |   |   |   |   |   |  shm pages
   *       |                offset (may exceed max size_t)
   *       |-----------|    size
   *     |-|                misalignment
   *     |                  adjusted offset
   *     |-------------|    requested mapping
   */

  // Note: result of % computation may be off_t or size_t, depending on the
  // relative ranks of those types. In any case we assume that
  // VMAllocationGranularity() fits in both types, so the final result does too.
  DCHECK_GE(offset, 0);
  *misalignment = offset % base::SysInfo::VMAllocationGranularity();

  // Above this |max_size|, |size| + |*misalignment| overflows.
  size_t max_size = std::numeric_limits<size_t>::max() - *misalignment;
  if (size > max_size) {
    DLOG(ERROR) << "Invalid size";
    return false;
  }

  *adjusted_offset = offset - static_cast<off_t>(*misalignment);

  return true;
}

}  // namespace
UnalignedSharedMemory::UnalignedSharedMemory(
    const base::SharedMemoryHandle& handle,
    size_t size,
    bool read_only)
    : shm_(handle, read_only), size_(size) {}

UnalignedSharedMemory::~UnalignedSharedMemory() = default;

bool UnalignedSharedMemory::MapAt(off_t offset, size_t size) {
  if (offset < 0) {
    DLOG(ERROR) << "Invalid offset";
    return false;
  }

  size_t misalignment;
  off_t adjusted_offset;

  if (!CalculateMisalignmentAndOffset(size, offset, &misalignment,
                                      &adjusted_offset)) {
    return false;
  }

  if (!shm_.MapAt(adjusted_offset, size + misalignment)) {
    DLOG(ERROR) << "Failed to map shared memory";
    return false;
  }
  mapping_ptr_ = static_cast<uint8_t*>(shm_.memory());

  DCHECK(mapping_ptr_);
  // There should be no way for the IsValid() checks above to succeed and yet
  // |mapping_ptr_| remain null. However, since an invalid but non-null pointer
  // could be disastrous an extra-careful check is done.
  if (mapping_ptr_)
    mapping_ptr_ += misalignment;
  return true;
}

}  // namespace media
