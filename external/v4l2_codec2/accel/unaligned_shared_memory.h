// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
// Note: ported from Chromium commit head: 690c8b268457
// Note: only necessary functions are ported.
// Note: The version in Chromium has moved away from using base::SharedMemory.

#ifndef UNALIGNED_SHARED_MEMORY_H_
#define UNALIGNED_SHARED_MEMORY_H_

#include <stdint.h>

#include "base/macros.h"
#include "base/memory/shared_memory.h"

namespace media {

// Wrapper over base::SharedMemory that can be mapped at unaligned offsets.
// DEPRECATED! See https://crbug.com/795291.
class UnalignedSharedMemory {
 public:
  // Creates an |UnalignedSharedMemory| instance from a
  // |SharedMemoryHandle|. |size| sets the maximum size that may be mapped. This
  // instance will own the handle.
  UnalignedSharedMemory(const base::SharedMemoryHandle& handle,
                        size_t size,
                        bool read_only);

  ~UnalignedSharedMemory();

  // Map the shared memory region. Note that the passed |size| parameter should
  // be less than or equal to |size()|.
  bool MapAt(off_t offset, size_t size);
  size_t size() const { return size_; }
  void* memory() const { return mapping_ptr_; }

 private:
  // Either |shm_| or the set |region_| and one of the mappings are active,
  // depending on which constructor was used and the value of read_only_. These
  // variables are held to keep the shared memory mapping valid for the lifetime
  // of this instance.
  base::SharedMemory shm_;

  // The size of the region associated with |shm_|.
  size_t size_;

  // Pointer to the unaligned data in the shared memory mapping.
  uint8_t* mapping_ptr_ = nullptr;

  DISALLOW_COPY_AND_ASSIGN(UnalignedSharedMemory);
};

}  // namespace media

#endif  // UNALIGNED_SHARED_MEMORY_H_
