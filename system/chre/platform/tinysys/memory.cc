/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "chre/platform/memory.h"
#include "chre/platform/shared/memory.h"
#include "mt_alloc.h"
#include "mt_dma.h"
#include "portable.h"

extern "C" {
#include "resource_req.h"
}

namespace chre {

// no-op since the dma access is controlled by the kernel automatically
void forceDramAccess() {}

void nanoappBinaryFree(void *pointer) {
  aligned_free(pointer);
}

void nanoappBinaryDramFree(void *pointer) {
  aligned_dram_free(pointer);
}

void *memoryAllocDram(size_t size) {
  return pvPortDramMalloc(size);
}

void memoryFreeDram(void *pointer) {
  vPortDramFree(pointer);
}

void *palSystemApiMemoryAlloc(size_t size) {
  return memoryAlloc(size);
}

void palSystemApiMemoryFree(void *pointer) {
  memoryFree(pointer);
}

void *nanoappBinaryAlloc(size_t size, size_t alignment) {
  return aligned_malloc(size, alignment);
}

void *nanoappBinaryDramAlloc(size_t size, size_t alignment) {
  // aligned_dram_malloc() requires the alignment being multiple of
  // CACHE_LINE_SIZE (128 bytes), we will align to page size (4k)
  return aligned_dram_malloc(size, alignment);
}

}  // namespace chre
