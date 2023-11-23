/*
 * Copyright 2022 The Android Open Source Project
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

#include "VirtGpu.h"

VirtGpuBlob::VirtGpuBlob(int64_t deviceHandle, uint32_t blobHandle, uint32_t resourceHandle,
                         uint64_t size)
    : mDeviceHandle(deviceHandle),
      mBlobHandle(blobHandle),
      mResourceHandle(resourceHandle),
      mSize(size) {}

VirtGpuBlob::~VirtGpuBlob(void) {
    // Unimplemented stub
}

uint32_t VirtGpuBlob::getBlobHandle(void) {
    return 0;
}

uint32_t VirtGpuBlob::getResourceHandle(void) {
    return 0;
}

VirtGpuBlobMappingPtr VirtGpuBlob::createMapping(void) {
    return nullptr;
}

int VirtGpuBlob::wait() {
    return -1;
}
