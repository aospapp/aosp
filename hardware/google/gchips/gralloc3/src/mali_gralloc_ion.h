/*
 * Copyright (C) 2016, 2018-2019 ARM Limited. All rights reserved.
 *
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef MALI_GRALLOC_ION_H_
#define MALI_GRALLOC_ION_H_

#include "mali_gralloc_module.h"
#include "mali_gralloc_bufferdescriptor.h"

/*
 *  Allocates ION buffers
 *
 * @param descriptors     [in]    Buffer request descriptors
 * @param numDescriptors  [in]    Number of descriptors
 * @param pHandle         [out]   Handle for each allocated buffer
 * @param shared_backend  [out]   Shared buffers flag
 * @param ion_fd          [in]    Optional fd of an allocated ION buffer
 *
 * @return File handle which can be used for allocation, on success
 *         -1, otherwise.
 *
 * In the case ion_fd != -1, this functions wraps ion_fd in a buffer_handle_t
 * instead.
 */
int mali_gralloc_ion_allocate(const gralloc_buffer_descriptor_t *descriptors,
                              uint32_t numDescriptors, buffer_handle_t *pHandle,
                              bool *alloc_from_backing_store, int ion_fd = -1);
void mali_gralloc_ion_free(private_handle_t * const hnd);
int mali_gralloc_ion_sync_start(const private_handle_t * const hnd,
                                const bool read, const bool write);
int mali_gralloc_ion_sync_end(const private_handle_t * const hnd,
                              const bool read, const bool write);
int mali_gralloc_ion_map(private_handle_t *hnd);
void mali_gralloc_ion_unmap(private_handle_t *hnd);
void mali_gralloc_ion_close(void);

int import_exynos_ion_handles(private_handle_t *hnd);
void free_exynos_ion_handles(private_handle_t *hnd);

int mali_gralloc_ion_open(void);

#if GRALLOC_USE_ASHMEM_METADATA != 1
int alloc_metadata(void);
#endif

#endif /* MALI_GRALLOC_ION_H_ */
