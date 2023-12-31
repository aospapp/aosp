/*
 * Copyright (C) 2023 The Android Open Source Project
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

#pragma once

#include <memory>
#include <aidl/android/hardware/camera/device/CameraMetadata.h>
#include <libexif/exif-data.h>

#include "Rect.h"

namespace android {
namespace hardware {
namespace camera {
namespace provider {
namespace implementation {
namespace exif {

using aidl::android::hardware::camera::device::CameraMetadata;

struct ExifDataDeleter { void operator()(ExifData*) const; };
typedef std::unique_ptr<ExifData, ExifDataDeleter> ExifDataPtr;

ExifDataPtr createExifData(const CameraMetadata&, Rect<uint16_t> size);
void* exifDataAllocThumbnail(ExifData* edata, size_t size);

}  // namespace exif
}  // namespace implementation
}  // namespace provider
}  // namespace camera
}  // namespace hardware
}  // namespace android
