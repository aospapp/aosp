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

VirtGpuDevice& VirtGpuDevice::getInstance(enum VirtGpuCapset capset) {
    static VirtGpuDevice mInstance(capset);
    return mInstance;
}

VirtGpuDevice::VirtGpuDevice(enum VirtGpuCapset capset) {
    // Unimplemented stub
}

struct VirtGpuCaps VirtGpuDevice::getCaps(void) { return mCaps; }

int64_t VirtGpuDevice::getDeviceHandle(void) {
    return mDeviceHandle;
}

VirtGpuBlobPtr VirtGpuDevice::createPipeBlob(uint32_t size) {
    return nullptr;
}

VirtGpuBlobPtr VirtGpuDevice::createBlob(const struct VirtGpuCreateBlob& blobCreate) {
    return nullptr;
}

VirtGpuBlobPtr VirtGpuDevice::importBlob(const struct VirtGpuExternalHandle& handle) {
    return nullptr;
}

int VirtGpuDevice::execBuffer(struct VirtGpuExecBuffer& execbuffer, VirtGpuBlobPtr blob) {
    return -1;
}

VirtGpuDevice::~VirtGpuDevice() {
    // Unimplemented stub
}
