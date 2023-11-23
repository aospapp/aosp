/*
 * Copyright 2020 The Android Open Source Project
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

#include "MockEvsCamera.h"

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {

MockEvsCamera::MockEvsCamera() {
    mConfigManager =
            ConfigManager::Create(
                    "/vendor/etc/automotive/evs/evs_sample_configuration.xml");
}

Return<void> MockEvsCamera::getCameraInfo(getCameraInfo_cb _hidl_cb) {
    // Not implemented.

    (void)_hidl_cb;
    return {};
}

Return<EvsResult> MockEvsCamera::setMaxFramesInFlight(uint32_t bufferCount) {
    // Not implemented.

    (void)bufferCount;
    return EvsResult::OK;
}

Return<EvsResult> MockEvsCamera::startVideoStream(
        const ::android::sp<IEvsCameraStream_1_0>& stream) {
    LOG(INFO) << __FUNCTION__;

    (void)stream;
    return EvsResult::OK;
}

Return<void> MockEvsCamera::doneWithFrame(const BufferDesc_1_0& buffer) {
    // Not implemented.

    (void)buffer;
    return {};
}

Return<void> MockEvsCamera::stopVideoStream() {
    LOG(INFO) << __FUNCTION__;
    return {};
}

Return<int32_t> MockEvsCamera::getExtendedInfo(uint32_t opaqueIdentifier) {
    // Not implemented.

    (void)opaqueIdentifier;
    return 0;
}

Return<EvsResult> MockEvsCamera::setExtendedInfo(uint32_t opaqueIdentifier,
                                                 int32_t opaqueValue) {
    // Not implemented.

    (void)opaqueIdentifier;
    (void)opaqueValue;
    return EvsResult::OK;
}

Return<void> MockEvsCamera::getCameraInfo_1_1(getCameraInfo_1_1_cb _hidl_cb) {
    // Not implemented.

    (void)_hidl_cb;
    return {};
}

Return<void> MockEvsCamera::getPhysicalCameraInfo(
        const hidl_string& deviceId, getPhysicalCameraInfo_cb _hidl_cb) {
    CameraDesc_1_1 desc = {};
    desc.v1.cameraId = deviceId;

    unique_ptr<ConfigManager::CameraInfo>& cameraInfo =
            mConfigManager->getCameraInfo(deviceId);
    if (cameraInfo != nullptr) {
        desc.metadata.setToExternal(
                (uint8_t*)cameraInfo->characteristics,
                get_camera_metadata_size(cameraInfo->characteristics));
    }

    _hidl_cb(desc);

    return {};
}

Return<EvsResult> MockEvsCamera::doneWithFrame_1_1(
        const hardware::hidl_vec<BufferDesc_1_1>& buffer) {
    // Not implemented.

    (void)buffer;
    return EvsResult::OK;
}

Return<EvsResult> MockEvsCamera::setMaster() {
    // Not implemented.

    return EvsResult::OK;
}

Return<EvsResult> MockEvsCamera::forceMaster(
        const sp<IEvsDisplay_1_0>& display) {
    // Not implemented.

    (void)display;
    return EvsResult::OK;
}

Return<EvsResult> MockEvsCamera::unsetMaster() {
    // Not implemented.

    return EvsResult::OK;
}

Return<void> MockEvsCamera::getParameterList(getParameterList_cb _hidl_cb) {
    // Not implemented.

    (void)_hidl_cb;
    return {};
}

Return<void> MockEvsCamera::getIntParameterRange(
        CameraParam id, getIntParameterRange_cb _hidl_cb) {
    // Not implemented.

    (void)id;
    (void)_hidl_cb;
    return {};
}

Return<void> MockEvsCamera::setIntParameter(CameraParam id, int32_t value,
                                            setIntParameter_cb _hidl_cb) {
    // Not implemented.

    (void)id;
    (void)value;
    (void)_hidl_cb;
    return {};
}

Return<void> MockEvsCamera::getIntParameter(
        CameraParam id, getIntParameter_cb _hidl_cb) {
    // Not implemented.

    (void)id;
    (void)_hidl_cb;
    return {};
}

Return<EvsResult> MockEvsCamera::setExtendedInfo_1_1(
    uint32_t opaqueIdentifier, const hidl_vec<uint8_t>& opaqueValue) {
    // Not implemented.

    (void)opaqueIdentifier;
    (void)opaqueValue;
    return EvsResult::OK;
}

Return<void> MockEvsCamera::getExtendedInfo_1_1(
        uint32_t opaqueIdentifier, getExtendedInfo_1_1_cb _hidl_cb) {
    // Not implemented.

    (void)opaqueIdentifier;
    (void)_hidl_cb;
    return {};
}

Return<void> MockEvsCamera::importExternalBuffers(
        const hidl_vec<BufferDesc_1_1>& buffers,
        importExternalBuffers_cb _hidl_cb) {
    // Not implemented.

    (void)buffers;
    (void)_hidl_cb;
    return {};
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android
