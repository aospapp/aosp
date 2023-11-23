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

#define FAILURE_DEBUG_PREFIX "CameraDevice"

#include <charconv>
#include <string_view>

#include <system/camera_metadata.h>
#include <system/graphics.h>

#include "CameraDevice.h"
#include "CameraDeviceSession.h"
#include "debug.h"

namespace android {
namespace hardware {
namespace camera {
namespace provider {
namespace implementation {
namespace {
constexpr char kCameraIdPrefix[] = "device@1.0/internal/";

const uint32_t kExtraResultKeys[] = {
    ANDROID_CONTROL_AE_STATE,
    ANDROID_CONTROL_AF_STATE,
    ANDROID_CONTROL_AWB_STATE,
    ANDROID_FLASH_STATE,
    ANDROID_LENS_FOCUS_DISTANCE,
    ANDROID_LENS_STATE,
    ANDROID_REQUEST_PIPELINE_DEPTH,
    ANDROID_SENSOR_TIMESTAMP, // populate with zero, CameraDeviceSession will put an actual value
    ANDROID_SENSOR_ROLLING_SHUTTER_SKEW,
    ANDROID_STATISTICS_SCENE_FLICKER,
};

std::vector<uint32_t> getSortedKeys(const CameraMetadataMap& m) {
    std::vector<uint32_t> keys;
    keys.reserve(m.size());
    for (const auto& [tag, unused] : m) {
        keys.push_back(tag);
    }
    std::sort(keys.begin(), keys.end());
    return keys;
}

camera_metadata_enum_android_control_capture_intent_t
MapRequestTemplateToIntent(const RequestTemplate tpl) {
    switch (tpl) {
    case RequestTemplate::PREVIEW:
        return ANDROID_CONTROL_CAPTURE_INTENT_PREVIEW;
    case RequestTemplate::STILL_CAPTURE:
        return ANDROID_CONTROL_CAPTURE_INTENT_STILL_CAPTURE;
    case RequestTemplate::VIDEO_RECORD:
        return ANDROID_CONTROL_CAPTURE_INTENT_VIDEO_RECORD;
    case RequestTemplate::VIDEO_SNAPSHOT:
        return ANDROID_CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT;
    case RequestTemplate::ZERO_SHUTTER_LAG:
        return ANDROID_CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG;
    case RequestTemplate::MANUAL:
        return ANDROID_CONTROL_CAPTURE_INTENT_MANUAL;
    default:
        return ANDROID_CONTROL_CAPTURE_INTENT_CUSTOM;
    }
}

}  // namespace

using aidl::android::hardware::camera::common::Status;
using hw::HwCameraFactoryProduct;

CameraDevice::CameraDevice(HwCameraFactoryProduct hwCamera)
        : mHwCamera(std::move(hwCamera)) {}

CameraDevice::~CameraDevice() {}

ScopedAStatus CameraDevice::getCameraCharacteristics(CameraMetadata* metadata) {
    CameraMetadataMap m;

    {
        m[ANDROID_COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES]
            .add(ANDROID_COLOR_CORRECTION_ABERRATION_MODE_OFF);
    }
    {   // ANDROID_CONTROL_...
        m[ANDROID_CONTROL_AE_AVAILABLE_ANTIBANDING_MODES]
            .add<uint8_t>(ANDROID_CONTROL_AE_ANTIBANDING_MODE_OFF)
            .add<uint8_t>(ANDROID_CONTROL_AE_ANTIBANDING_MODE_AUTO);
        m[ANDROID_CONTROL_AE_AVAILABLE_MODES]
            .add<uint8_t>(ANDROID_CONTROL_AE_MODE_OFF)
            .add<uint8_t>(ANDROID_CONTROL_AE_MODE_ON);
        {
            auto& ranges = m[ANDROID_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES];
            for (const auto& r : mHwCamera->getTargetFpsRanges()) {
                ranges.add<int32_t>(r.first).add<int32_t>(r.second);
            }
        }
        {
            const auto aeCompensationRange = mHwCamera->getAeCompensationRange();
            m[ANDROID_CONTROL_AE_COMPENSATION_RANGE]
                .add<int32_t>(std::get<0>(aeCompensationRange))
                .add<int32_t>(std::get<1>(aeCompensationRange));

            const camera_metadata_rational_t aeCompensationStep = {
                .numerator = std::get<2>(aeCompensationRange),
                .denominator = std::get<3>(aeCompensationRange),
            };
            m[ANDROID_CONTROL_AE_COMPENSATION_STEP] = aeCompensationStep;
        }
        m[ANDROID_CONTROL_AF_AVAILABLE_MODES]
            .add<uint8_t>(ANDROID_CONTROL_AF_MODE_OFF)
            .add<uint8_t>(ANDROID_CONTROL_AF_MODE_AUTO);
        m[ANDROID_CONTROL_AVAILABLE_EFFECTS]
            .add<uint8_t>(ANDROID_CONTROL_EFFECT_MODE_OFF);
        m[ANDROID_CONTROL_AVAILABLE_SCENE_MODES]
            .add<uint8_t>(ANDROID_CONTROL_SCENE_MODE_DISABLED);
        m[ANDROID_CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]
            .add<uint8_t>(ANDROID_CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        m[ANDROID_CONTROL_AWB_AVAILABLE_MODES]
            .add<uint8_t>(ANDROID_CONTROL_AWB_MODE_OFF)
            .add<uint8_t>(ANDROID_CONTROL_AWB_MODE_AUTO);
        m[ANDROID_CONTROL_MAX_REGIONS]
            .add<int32_t>(0)    // AE
            .add<int32_t>(0)    // AWB
            .add<int32_t>(0);   // AF
        m[ANDROID_CONTROL_AE_LOCK_AVAILABLE] =
            ANDROID_CONTROL_AE_LOCK_AVAILABLE_FALSE;
        m[ANDROID_CONTROL_AWB_LOCK_AVAILABLE] =
            ANDROID_CONTROL_AWB_LOCK_AVAILABLE_FALSE;
        m[ANDROID_CONTROL_AVAILABLE_MODES]
            .add<uint8_t>(ANDROID_CONTROL_MODE_OFF)
            .add<uint8_t>(ANDROID_CONTROL_MODE_AUTO);
        {
            const auto zoomRatioRange = mHwCamera->getZoomRatioRange();
            m[ANDROID_CONTROL_ZOOM_RATIO_RANGE]
                .add<float>(zoomRatioRange.first)
                .add<float>(zoomRatioRange.second);
        }
    }
    {   // ANDROID_EDGE_...
        m[ANDROID_EDGE_AVAILABLE_EDGE_MODES]
            .add<uint8_t>(ANDROID_EDGE_MODE_OFF);
    }
    {   // ANDROID_FLASH_INFO_...
        const auto supportedFlashStrength = mHwCamera->getSupportedFlashStrength();
        if (supportedFlashStrength.first > 0) {
            m[ANDROID_FLASH_INFO_AVAILABLE] = ANDROID_FLASH_INFO_AVAILABLE_TRUE;
            m[ANDROID_FLASH_INFO_STRENGTH_MAXIMUM_LEVEL] =
                int32_t(supportedFlashStrength.first);
            m[ANDROID_FLASH_INFO_STRENGTH_DEFAULT_LEVEL] =
                int32_t(supportedFlashStrength.second);
        } else {
            m[ANDROID_FLASH_INFO_AVAILABLE] = ANDROID_FLASH_INFO_AVAILABLE_FALSE;
        }
    }
    {   // ANDROID_HOT_PIXEL_...
        m[ANDROID_HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES]
            .add<uint8_t>(ANDROID_HOT_PIXEL_MODE_OFF);
    }
    {   // ANDROID_JPEG_...
        {
            auto& v = m[ANDROID_JPEG_AVAILABLE_THUMBNAIL_SIZES];
            for (const auto sz : mHwCamera->getAvailableThumbnailSizes()) {
                v.add<int32_t>(sz.width).add<int32_t>(sz.height);
            }
        }
        m[ANDROID_JPEG_MAX_SIZE] = int32_t(mHwCamera->getJpegMaxSize());
    }
    {   // ANDROID_LENS_...
        m[ANDROID_LENS_FACING] = mHwCamera->isBackFacing() ?
            ANDROID_LENS_FACING_BACK : ANDROID_LENS_FACING_FRONT;

        {
            auto& v = m[ANDROID_LENS_INFO_AVAILABLE_APERTURES];
            for (const float ap : mHwCamera->getAvailableApertures()) {
                v.add<float>(ap);
            }
        }
        {
            auto& v = m[ANDROID_LENS_INFO_AVAILABLE_FOCAL_LENGTHS];
            for (const float ap : mHwCamera->getAvailableFocalLength()) {
                v.add<float>(ap);
            }
        }

        m[ANDROID_LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]
            .add<uint8_t>(ANDROID_LENS_OPTICAL_STABILIZATION_MODE_OFF);
        m[ANDROID_LENS_INFO_HYPERFOCAL_DISTANCE] =
            float(mHwCamera->getHyperfocalDistance());
        m[ANDROID_LENS_INFO_MINIMUM_FOCUS_DISTANCE] =
            float(mHwCamera->getMinimumFocusDistance());
        m[ANDROID_LENS_INFO_FOCUS_DISTANCE_CALIBRATION] =
            ANDROID_LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE;
    }
    {   // ANDROID_NOISE_REDUCTION_...
        m[ANDROID_NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]
            .add<uint8_t>(ANDROID_NOISE_REDUCTION_MODE_OFF);
    }
    {   // ANDROID_REQUEST_...
        {
            const auto maxNumOutputStreams = mHwCamera->getMaxNumOutputStreams();
            m[ANDROID_REQUEST_MAX_NUM_OUTPUT_STREAMS]
                .add<int32_t>(std::get<0>(maxNumOutputStreams))
                .add<int32_t>(std::get<1>(maxNumOutputStreams))
                .add<int32_t>(std::get<2>(maxNumOutputStreams));
        }

        m[ANDROID_REQUEST_MAX_NUM_INPUT_STREAMS] = int32_t(0);
        m[ANDROID_REQUEST_PIPELINE_MAX_DEPTH] = mHwCamera->getPipelineMaxDepth();
        m[ANDROID_REQUEST_PARTIAL_RESULT_COUNT] = int32_t(1);
        m[ANDROID_REQUEST_AVAILABLE_CAPABILITIES]
            .add(ANDROID_REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
            .add(ANDROID_REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS);
    }
    {   // ANDROID_SCALER_...
        {
            auto& v = m[ANDROID_SCALER_AVAILABLE_FORMATS];
            for (const auto fmt : mHwCamera->getSupportedPixelFormats()) {
                v.add(fmt);
            }
        }
        {
            auto& jpeg = m[ANDROID_SCALER_AVAILABLE_JPEG_SIZES];
            auto& processed = m[ANDROID_SCALER_AVAILABLE_PROCESSED_SIZES];

            for (const auto sz : mHwCamera->getSupportedResolutions()) {
                jpeg.add<int32_t>(sz.width).add<int32_t>(sz.height);
                processed.add<int32_t>(sz.width).add<int32_t>(sz.height);
            }
        }

        m[ANDROID_SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] =
            float(mHwCamera->getMaxDigitalZoom());

        {
            auto& streamConfigurations = m[ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS];
            auto& minFrameDurations = m[ANDROID_SCALER_AVAILABLE_MIN_FRAME_DURATIONS];
            auto& stallDurations = m[ANDROID_SCALER_AVAILABLE_STALL_DURATIONS];

            const int64_t minFrameDurationNs = mHwCamera->getMinFrameDurationNs();
            const int64_t stallFrameDurationNs = mHwCamera->getStallFrameDurationNs();
            const auto supportedFormats = mHwCamera->getSupportedPixelFormats();

            for (const auto& res : mHwCamera->getSupportedResolutions()) {
                for (const auto fmt : supportedFormats) {
                    const int32_t fmti = static_cast<int32_t>(fmt);

                    streamConfigurations
                        .add<int32_t>(fmti)
                        .add<int32_t>(res.width).add<int32_t>(res.height)
                        .add<int32_t>(ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT);
                    minFrameDurations
                        .add<int64_t>(fmti)
                        .add<int64_t>(res.width).add<int64_t>(res.height)
                        .add<int64_t>(minFrameDurationNs);
                    stallDurations
                        .add<int64_t>(fmti)
                        .add<int64_t>(res.width).add<int64_t>(res.height)
                        .add<int64_t>((fmt == PixelFormat::BLOB) ? stallFrameDurationNs : 0);
                }
            }
        }

        m[ANDROID_SCALER_CROPPING_TYPE] =
            ANDROID_SCALER_CROPPING_TYPE_CENTER_ONLY;
    }
    {   // ANDROID_SENSOR_...
        m[ANDROID_SENSOR_ORIENTATION] =
            int32_t(mHwCamera->getSensorOrientation());
        m[ANDROID_SENSOR_AVAILABLE_TEST_PATTERN_MODES]
            .add<int32_t>(ANDROID_SENSOR_TEST_PATTERN_MODE_OFF);

        {
            const auto sensorSize = mHwCamera->getSensorSize();
            const auto sensorDPI = mHwCamera->getSensorDPI();

            m[ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE]
                .add<int32_t>(0).add<int32_t>(0)
                .add<int32_t>(sensorSize.width)
                .add<int32_t>(sensorSize.height);
            m[ANDROID_SENSOR_INFO_PHYSICAL_SIZE]
                .add<float>(sensorSize.width / sensorDPI)
                .add<float>(sensorSize.height / sensorDPI);
            m[ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE]
                .add<int32_t>(sensorSize.width)
                .add<int32_t>(sensorSize.height);
            m[ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE]
                .add<int32_t>(0).add<int32_t>(0)
                .add<int32_t>(sensorSize.width)
                .add<int32_t>(sensorSize.height);
        }
        {
            const auto senitivityRange = mHwCamera->getSensorSensitivityRange();

            m[ANDROID_SENSOR_INFO_SENSITIVITY_RANGE]
                .add<int32_t>(senitivityRange.first)
                .add<int32_t>(senitivityRange.second);
        }

        m[ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT] =
            ANDROID_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB;

        {
            const auto exposureTimeRange = mHwCamera->getSensorExposureTimeRange();

            m[ANDROID_SENSOR_INFO_EXPOSURE_TIME_RANGE]
                .add<int64_t>(exposureTimeRange.first)
                .add<int64_t>(exposureTimeRange.second);
        }

        m[ANDROID_SENSOR_INFO_MAX_FRAME_DURATION] =
            int64_t(mHwCamera->getSensorMaxFrameDuration());
        m[ANDROID_SENSOR_INFO_TIMESTAMP_SOURCE] =
            ANDROID_SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN;  // SYSTEM_TIME_MONOTONIC
    }
    {   // ANDROID_SHADING_...
        m[ANDROID_SHADING_AVAILABLE_MODES]
            .add<uint8_t>(ANDROID_SHADING_MODE_OFF);
    }
    {   // ANDROID_STATISTICS_...
        m[ANDROID_STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES]
            .add<uint8_t>(ANDROID_STATISTICS_FACE_DETECT_MODE_OFF);
        m[ANDROID_STATISTICS_INFO_MAX_FACE_COUNT] = int32_t(0);
        m[ANDROID_STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES]
            .add<uint8_t>(ANDROID_STATISTICS_HOT_PIXEL_MAP_MODE_OFF);
        m[ANDROID_STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES]
            .add<uint8_t>(ANDROID_STATISTICS_LENS_SHADING_MAP_MODE_OFF);
    }
    {   // ANDROID_INFO_...
        m[ANDROID_INFO_SUPPORTED_HARDWARE_LEVEL] =
            ANDROID_INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED;
    }
    {   // ANDROID_SYNC_
        m[ANDROID_SYNC_MAX_LATENCY] = ANDROID_SYNC_MAX_LATENCY_UNKNOWN;
    }
    {   // ANDROID_DISTORTION_CORRECTION_...
        m[ANDROID_DISTORTION_CORRECTION_AVAILABLE_MODES]
            .add<uint8_t>(ANDROID_DISTORTION_CORRECTION_MODE_OFF);
    }

    ////////////////////////////////////////////////////////////////////////////
    {
        const std::vector<uint32_t> keys = getSortedKeys(m);
        CameraMetadataValue& val = m[ANDROID_REQUEST_AVAILABLE_CHARACTERISTICS_KEYS];
        for (const uint32_t key : keys) {
            val.add<uint32_t>(key);
        }
    }
    {
        CameraMetadataMap r = constructDefaultRequestSettings(RequestTemplate::PREVIEW);

        {
            const std::vector<uint32_t> keys = getSortedKeys(r);
            CameraMetadataValue& val = m[ANDROID_REQUEST_AVAILABLE_REQUEST_KEYS];
            for (const uint32_t key : keys) {
                val.add<uint32_t>(key);
            }
        }

        for (const uint32_t key : kExtraResultKeys) {
            r[key];
        }

        {
            const std::vector<uint32_t> keys = getSortedKeys(r);
            CameraMetadataValue& val = m[ANDROID_REQUEST_AVAILABLE_RESULT_KEYS];
            for (const uint32_t key : keys) {
                val.add<uint32_t>(key);
            }
        }
    }

    auto maybeMetadata = serializeCameraMetadataMap(m);
    if (maybeMetadata) {
        *metadata = std::move(maybeMetadata.value());
        return ScopedAStatus::ok();
    } else {
        return toScopedAStatus(Status::INTERNAL_ERROR);
    }
}

ScopedAStatus CameraDevice::getPhysicalCameraCharacteristics(
        const std::string& /*physicalCameraId*/, CameraMetadata* /*metadata*/) {
    return toScopedAStatus(FAILURE(Status::OPERATION_NOT_SUPPORTED));
}

ScopedAStatus CameraDevice::getResourceCost(CameraResourceCost* cost) {
    cost->resourceCost = 100;
    return ScopedAStatus::ok();
}

ScopedAStatus CameraDevice::isStreamCombinationSupported(
        const StreamConfiguration& cfg, bool* support) {
    *support = CameraDeviceSession::isStreamCombinationSupported(cfg, *mHwCamera);
    return ScopedAStatus::ok();
}

ScopedAStatus CameraDevice::open(const std::shared_ptr<ICameraDeviceCallback>& callback,
        std::shared_ptr<ICameraDeviceSession>* session) {
    *session = ndk::SharedRefBase::make<CameraDeviceSession>(
        mSelf.lock(), callback, *mHwCamera);
    return ScopedAStatus::ok();
}

ScopedAStatus CameraDevice::openInjectionSession(
        const std::shared_ptr<ICameraDeviceCallback>& /*callback*/,
        std::shared_ptr<ICameraInjectionSession>* /*session*/) {
    return toScopedAStatus(FAILURE(Status::OPERATION_NOT_SUPPORTED));
}

ScopedAStatus CameraDevice::setTorchMode(const bool /*on*/) {
    return toScopedAStatus(FAILURE(Status::OPERATION_NOT_SUPPORTED));
}

ScopedAStatus CameraDevice::turnOnTorchWithStrengthLevel(const int32_t /*strength*/) {
    return toScopedAStatus(FAILURE(Status::OPERATION_NOT_SUPPORTED));
}

ScopedAStatus CameraDevice::getTorchStrengthLevel(int32_t* /*strength*/) {
    return toScopedAStatus(FAILURE(Status::OPERATION_NOT_SUPPORTED));
}

CameraMetadataMap CameraDevice::constructDefaultRequestSettings(const RequestTemplate tpl) const {
    using namespace std::literals;
    const auto sensorSize = mHwCamera->getSensorSize();
    const std::pair<int32_t, int32_t> fpsRange = mHwCamera->getDefaultTargetFpsRange(tpl);

    CameraMetadataMap m;

    m[ANDROID_COLOR_CORRECTION_ABERRATION_MODE] =
        ANDROID_COLOR_CORRECTION_ABERRATION_MODE_OFF;
    m[ANDROID_CONTROL_AE_ANTIBANDING_MODE] = ANDROID_CONTROL_AE_ANTIBANDING_MODE_AUTO;
    m[ANDROID_CONTROL_AE_EXPOSURE_COMPENSATION] = int32_t(0);
    m[ANDROID_CONTROL_AE_LOCK] = ANDROID_CONTROL_AE_LOCK_OFF;
    m[ANDROID_CONTROL_AE_MODE] = (tpl == RequestTemplate::MANUAL) ?
        ANDROID_CONTROL_AE_MODE_OFF : ANDROID_CONTROL_AE_MODE_ON;
    m[ANDROID_CONTROL_AE_TARGET_FPS_RANGE]
        .add<int32_t>(fpsRange.first).add<int32_t>(fpsRange.second);
    m[ANDROID_CONTROL_AE_PRECAPTURE_TRIGGER] = ANDROID_CONTROL_AE_PRECAPTURE_TRIGGER_IDLE;
    m[ANDROID_CONTROL_AF_MODE] = (tpl == RequestTemplate::MANUAL) ?
        ANDROID_CONTROL_AF_MODE_OFF : ANDROID_CONTROL_AF_MODE_AUTO;
    m[ANDROID_CONTROL_AF_TRIGGER] = ANDROID_CONTROL_AF_TRIGGER_IDLE;
    m[ANDROID_CONTROL_AWB_LOCK] = ANDROID_CONTROL_AWB_LOCK_OFF;
    m[ANDROID_CONTROL_AWB_MODE] = (tpl == RequestTemplate::MANUAL) ?
        ANDROID_CONTROL_AWB_MODE_OFF : ANDROID_CONTROL_AWB_MODE_AUTO;
    m[ANDROID_CONTROL_CAPTURE_INTENT] = MapRequestTemplateToIntent(tpl);
    m[ANDROID_CONTROL_EFFECT_MODE] = ANDROID_CONTROL_EFFECT_MODE_OFF;
    m[ANDROID_CONTROL_MODE] = (tpl == RequestTemplate::MANUAL) ?
        ANDROID_CONTROL_MODE_OFF : ANDROID_CONTROL_MODE_AUTO;
    m[ANDROID_CONTROL_SCENE_MODE] = ANDROID_CONTROL_SCENE_MODE_DISABLED;
    m[ANDROID_CONTROL_VIDEO_STABILIZATION_MODE] = ANDROID_CONTROL_VIDEO_STABILIZATION_MODE_OFF;
    m[ANDROID_CONTROL_ZOOM_RATIO] = float(mHwCamera->getZoomRatioRange().first);

    m[ANDROID_EDGE_MODE] = ANDROID_EDGE_MODE_OFF;

    m[ANDROID_FLASH_MODE] = ANDROID_FLASH_MODE_OFF;

    m[ANDROID_HOT_PIXEL_MODE] = ANDROID_HOT_PIXEL_MODE_OFF;

    m[ANDROID_JPEG_ORIENTATION] = int32_t(0);
    m[ANDROID_JPEG_QUALITY] = uint8_t(85);
    m[ANDROID_JPEG_THUMBNAIL_QUALITY] = uint8_t(85);
    m[ANDROID_JPEG_THUMBNAIL_SIZE].add<int32_t>(0).add<int32_t>(0);

    m[ANDROID_LENS_APERTURE] = float(mHwCamera->getDefaultAperture());
    m[ANDROID_LENS_FOCAL_LENGTH] = float(mHwCamera->getDefaultFocalLength());
    m[ANDROID_LENS_FOCUS_DISTANCE] = float(mHwCamera->getMinimumFocusDistance());
    m[ANDROID_LENS_OPTICAL_STABILIZATION_MODE] =
        ANDROID_LENS_OPTICAL_STABILIZATION_MODE_OFF;

    m[ANDROID_NOISE_REDUCTION_MODE] = ANDROID_NOISE_REDUCTION_MODE_OFF;

    m[ANDROID_SENSOR_TEST_PATTERN_MODE] = ANDROID_SENSOR_TEST_PATTERN_MODE_OFF;

    m[ANDROID_REQUEST_FRAME_COUNT] = int32_t(0);
    m[ANDROID_REQUEST_ID] = int32_t(0);
    m[ANDROID_REQUEST_METADATA_MODE] = ANDROID_REQUEST_METADATA_MODE_FULL;
    m[ANDROID_REQUEST_TYPE] = ANDROID_REQUEST_TYPE_CAPTURE;

    m[ANDROID_SCALER_CROP_REGION]
        .add<int32_t>(0).add<int32_t>(0)
        .add<int32_t>(sensorSize.width - 1)
        .add<int32_t>(sensorSize.height - 1);

    m[ANDROID_SENSOR_EXPOSURE_TIME] = int64_t(mHwCamera->getDefaultSensorExpTime());
    m[ANDROID_SENSOR_FRAME_DURATION] = int64_t(mHwCamera->getDefaultSensorFrameDuration());
    m[ANDROID_SENSOR_SENSITIVITY] = int32_t(mHwCamera->getDefaultSensorSensitivity());

    m[ANDROID_STATISTICS_FACE_DETECT_MODE] = ANDROID_STATISTICS_FACE_DETECT_MODE_OFF;
    m[ANDROID_STATISTICS_SHARPNESS_MAP_MODE] = ANDROID_STATISTICS_SHARPNESS_MAP_MODE_OFF;
    m[ANDROID_STATISTICS_HOT_PIXEL_MAP_MODE] = ANDROID_STATISTICS_HOT_PIXEL_MAP_MODE_OFF;
    m[ANDROID_STATISTICS_LENS_SHADING_MAP_MODE] = ANDROID_STATISTICS_LENS_SHADING_MAP_MODE_OFF;

    m[ANDROID_BLACK_LEVEL_LOCK] = ANDROID_BLACK_LEVEL_LOCK_OFF;
    m[ANDROID_DISTORTION_CORRECTION_MODE] = ANDROID_DISTORTION_CORRECTION_MODE_OFF;

    return m;
}

std::string CameraDevice::getPhysicalId(const int index) {
    char buf[sizeof(kCameraIdPrefix) + 8];
    snprintf(buf, sizeof(buf), "%s%d", kCameraIdPrefix, index);
    return buf;
}

std::optional<int> CameraDevice::parsePhysicalId(const std::string_view str) {
    if (str.size() < sizeof(kCameraIdPrefix)) {
        return FAILURE(std::nullopt);
    }

    if (memcmp(str.data(), kCameraIdPrefix, sizeof(kCameraIdPrefix) - 1) != 0) {
        return FAILURE(std::nullopt);
    }

    int index;
    const auto r = std::from_chars(&str[sizeof(kCameraIdPrefix) - 1],
                                   &*str.end(), index, 10);
    if (r.ec == std::errc()) {
        return index;
    } else {
        return FAILURE(std::nullopt);
    }
}

}  // namespace implementation
}  // namespace provider
}  // namespace camera
}  // namespace hardware
}  // namespace android
