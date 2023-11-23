/*
 * Copyright (C) 2019 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "GCH_CameraDevice"
#define ATRACE_TAG ATRACE_TAG_CAMERA
#include <log/log.h>
#include <utils/Trace.h>

#include "camera_device.h"
#include "vendor_tags.h"

namespace android {
namespace google_camera_hal {

std::unique_ptr<CameraDevice> CameraDevice::Create(
    std::unique_ptr<CameraDeviceHwl> camera_device_hwl,
    CameraBufferAllocatorHwl* camera_allocator_hwl) {
  ATRACE_CALL();
  auto device = std::unique_ptr<CameraDevice>(new CameraDevice());

  if (device == nullptr) {
    ALOGE("%s: Creating CameraDevice failed.", __FUNCTION__);
    return nullptr;
  }

  status_t res =
      device->Initialize(std::move(camera_device_hwl), camera_allocator_hwl);
  if (res != OK) {
    ALOGE("%s: Initializing CameraDevice failed: %s (%d).", __FUNCTION__,
          strerror(-res), res);
    return nullptr;
  }

  ALOGI("%s: Created a camera device for public(%u)", __FUNCTION__,
        device->GetPublicCameraId());

  return device;
}

status_t CameraDevice::Initialize(
    std::unique_ptr<CameraDeviceHwl> camera_device_hwl,
    CameraBufferAllocatorHwl* camera_allocator_hwl) {
  ATRACE_CALL();
  if (camera_device_hwl == nullptr) {
    ALOGE("%s: camera_device_hwl cannot be nullptr.", __FUNCTION__);
    return BAD_VALUE;
  }

  public_camera_id_ = camera_device_hwl->GetCameraId();
  camera_device_hwl_ = std::move(camera_device_hwl);
  camera_allocator_hwl_ = camera_allocator_hwl;

  return OK;
}

status_t CameraDevice::GetResourceCost(CameraResourceCost* cost) {
  ATRACE_CALL();
  return camera_device_hwl_->GetResourceCost(cost);
}

status_t CameraDevice::GetCameraCharacteristics(
    std::unique_ptr<HalCameraMetadata>* characteristics) {
  ATRACE_CALL();
  status_t res = camera_device_hwl_->GetCameraCharacteristics(characteristics);
  if (res != OK) {
    ALOGE("%s: GetCameraCharacteristics() failed: %s (%d).", __FUNCTION__,
          strerror(-res), res);
    return res;
  }

  return hal_vendor_tag_utils::ModifyCharacteristicsKeys(characteristics->get());
}

status_t CameraDevice::GetPhysicalCameraCharacteristics(
    uint32_t physical_camera_id,
    std::unique_ptr<HalCameraMetadata>* characteristics) {
  ATRACE_CALL();
  status_t res = camera_device_hwl_->GetPhysicalCameraCharacteristics(
      physical_camera_id, characteristics);
  if (res != OK) {
    ALOGE("%s: GetPhysicalCameraCharacteristics() failed: %s (%d).",
          __FUNCTION__, strerror(-res), res);
    return res;
  }

  return hal_vendor_tag_utils::ModifyCharacteristicsKeys(characteristics->get());
}

status_t CameraDevice::SetTorchMode(TorchMode mode) {
  ATRACE_CALL();
  return camera_device_hwl_->SetTorchMode(mode);
}

status_t CameraDevice::DumpState(int fd) {
  ATRACE_CALL();
  return camera_device_hwl_->DumpState(fd);
}

status_t CameraDevice::CreateCameraDeviceSession(
    std::unique_ptr<CameraDeviceSession>* session) {
  ATRACE_CALL();
  if (session == nullptr) {
    ALOGE("%s: session is nullptr.", __FUNCTION__);
    return BAD_VALUE;
  }

  std::unique_ptr<CameraDeviceSessionHwl> session_hwl;
  status_t res = camera_device_hwl_->CreateCameraDeviceSessionHwl(
      camera_allocator_hwl_, &session_hwl);
  if (res != OK) {
    ALOGE("%s: Creating a CameraDeviceSessionHwl failed: %s(%d)", __FUNCTION__,
          strerror(-res), res);
    return res;
  }

  *session = CameraDeviceSession::Create(std::move(session_hwl),
                                         camera_allocator_hwl_);
  if (*session == nullptr) {
    ALOGE("%s: Creating a CameraDeviceSession failed: %s(%d)", __FUNCTION__,
          strerror(-res), res);
    return UNKNOWN_ERROR;
  }

  return OK;
}

bool CameraDevice::IsStreamCombinationSupported(
    const StreamConfiguration& stream_config) {
  bool supported =
      camera_device_hwl_->IsStreamCombinationSupported(stream_config);
  if (!supported) {
    ALOGD("%s: stream config is not supported.", __FUNCTION__);
  }

  return supported;
}

}  // namespace google_camera_hal
}  // namespace android
