/*
 * Copyright (C) 2020 The Android Open Source Project
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
#include "SensorsSubHal.h"
#include <android/hardware/sensors/2.0/types.h>
#include <log/log.h>

ISensorsSubHal* sensorsHalGetSubHal(uint32_t* version) {
    static ::android::hardware::sensors::V2_0::subhal::implementation::SensorsSubHal subHal;
    *version = SUB_HAL_2_0_VERSION;
    return &subHal;
}

namespace android {
namespace hardware {
namespace sensors {
namespace V2_0 {
namespace subhal {
namespace implementation {

using ::android::hardware::Void;
using ::android::hardware::sensors::V1_0::Event;
using ::android::hardware::sensors::V1_0::OperationMode;
using ::android::hardware::sensors::V1_0::RateLevel;
using ::android::hardware::sensors::V1_0::Result;
using ::android::hardware::sensors::V1_0::SharedMemInfo;
using ::android::hardware::sensors::V2_0::SensorTimeout;
using ::android::hardware::sensors::V2_0::WakeLockQueueFlagBits;
using ::android::hardware::sensors::V2_0::implementation::ScopedWakelock;

#define SENSOR_SUPPORTED(SENSOR_NAME, SENSOR_TYPE) \
    { .name = SENSOR_NAME, .type = SENSOR_TYPE, }

static const std::vector<sensors_supported_hal> sensors_supported = {
        SENSOR_SUPPORTED("scmi.iio.accel", SensorType::ACCELEROMETER),
        SENSOR_SUPPORTED("scmi.iio.gyro", SensorType::GYROSCOPE),
};

SensorsSubHal::SensorsSubHal() : mCallback(nullptr), mNextHandle(1) {
    int err;
    std::vector<iio_device_data> iio_devices;
    err = load_iio_devices(&iio_devices, sensors_supported);
    if (err == 0) {
        for (auto i = 0u; i < iio_devices.size(); i++) {
            err = scan_elements(iio_devices[i].sysfspath, &iio_devices[i]);
            if (err == 0) {
                err = enable_sensor(iio_devices[i].sysfspath, false);
                if (err == 0) {
                    switch (iio_devices[i].type) {
                        case SensorType::ACCELEROMETER:
                            if (iio_devices[i].channelInfo.size() == NUM_OF_CHANNEL_SUPPORTED) {
                                AddSensor<Accelerometer>(iio_devices[i]);
                            } else {
                                ALOGE("Unexpected number of channels for Accelerometer");
                            }
                            break;

                        case SensorType::GYROSCOPE:
                            if (iio_devices[i].channelInfo.size() == NUM_OF_CHANNEL_SUPPORTED) {
                                AddSensor<Gyroscope>(iio_devices[i]);
                            } else {
                                ALOGE("Unexpected number fo channels for Gyroscope");
                            }
                            break;

                        default:
                            break;
                    }
                } else {
                    ALOGE("SensorsSubHal(): Error in enabling_sensor %s to %d error code %d",
                          iio_devices[i].sysfspath.c_str(), false, err);
                }
            } else {
                ALOGE("SensorsSubHal(): Error in scanning channels for IIO device %s error code %d",
                      iio_devices[i].sysfspath.c_str(), err);
            }
        }
    } else {
        ALOGE("SensorsSubHal: load_iio_devices returned error %d", err);
    }
}

// Methods from ::android::hardware::sensors::V2_0::ISensors follow.
Return<void> SensorsSubHal::getSensorsList(getSensorsList_cb _hidl_cb) {
    std::vector<SensorInfo> sensors;
    for (const auto& sensor : mSensors) {
        SensorInfo sensorInfo = sensor.second->getSensorInfo();
        sensorInfo.flags &= ~static_cast<uint32_t>(V1_0::SensorFlagBits::MASK_DIRECT_CHANNEL);
        sensorInfo.flags &= ~static_cast<uint32_t>(V1_0::SensorFlagBits::MASK_DIRECT_REPORT);
        sensors.push_back(sensorInfo);
    }

    _hidl_cb(sensors);
    return Void();
}

Return<Result> SensorsSubHal::setOperationMode(OperationMode mode) {
    for (auto& sensor : mSensors) {
        sensor.second->setOperationMode(mode);
    }
    mCurrentOperationMode = mode;
    return Result::OK;
}

Return<Result> SensorsSubHal::activate(int32_t sensorHandle, bool enabled) {
    auto sensor = mSensors.find(sensorHandle);
    if (sensor != mSensors.end()) {
        sensor->second->activate(enabled);
        return Result::OK;
    }
    return Result::BAD_VALUE;
}

Return<Result> SensorsSubHal::batch(int32_t sensorHandle, int64_t samplingPeriodNs,
                                    int64_t /* maxReportLatencyNs */) {
    auto sensor = mSensors.find(sensorHandle);
    if (sensor != mSensors.end()) {
        sensor->second->batch(samplingPeriodNs);
        return Result::OK;
    }
    return Result::BAD_VALUE;
}

Return<Result> SensorsSubHal::flush(int32_t sensorHandle) {
    auto sensor = mSensors.find(sensorHandle);
    if (sensor != mSensors.end()) {
        return sensor->second->flush();
    }
    return Result::BAD_VALUE;
}

Return<Result> SensorsSubHal::injectSensorData(const Event& /* event */) {
    return Result::INVALID_OPERATION;
}

Return<void> SensorsSubHal::registerDirectChannel(const SharedMemInfo& /* mem */,
                                                  registerDirectChannel_cb _hidl_cb) {
    _hidl_cb(Result::INVALID_OPERATION, -1 /* channelHandle */);
    return Return<void>();
}

Return<Result> SensorsSubHal::unregisterDirectChannel(int32_t /* channelHandle */) {
    return Result::INVALID_OPERATION;
}

Return<void> SensorsSubHal::configDirectReport(int32_t /* sensorHandle */,
                                               int32_t /* channelHandle */, RateLevel /* rate */,
                                               configDirectReport_cb _hidl_cb) {
    _hidl_cb(Result::INVALID_OPERATION, 0 /* reportToken */);
    return Return<void>();
}

Return<void> SensorsSubHal::debug(const hidl_handle& fd, const hidl_vec<hidl_string>& args) {
    if (fd.getNativeHandle() == nullptr || fd->numFds < 1) {
        ALOGE("%s: missing fd for writing", __FUNCTION__);
        return Void();
    }
    FILE* out = fdopen(dup(fd->data[0]), "w");

    if (args.size() != 0) {
        fprintf(out,
                "Note: sub-HAL %s currently does not support args. Input arguments are "
                "ignored.\n",
                getName().c_str());
    }

    std::ostringstream stream;
    stream << "Available sensors:" << std::endl;
    for (auto& sensor : mSensors) {
        SensorInfo info = sensor.second->getSensorInfo();
        HWSensorBase* hwSensor = static_cast<HWSensorBase*>(sensor.second.get());
        stream << "Name: " << info.name << std::endl;
        stream << "handle: " << info.sensorHandle << std::endl;
        stream << "resolution: " << info.resolution << " minDelay: " << info.minDelay
               << " maxDelay:" << info.maxDelay << std::endl;
        stream << "iio path" << hwSensor->miio_data.sysfspath << std::endl;
    }

    stream << std::endl;

    fprintf(out, "%s", stream.str().c_str());

    fclose(out);
    return Return<void>();
}

Return<Result> SensorsSubHal::initialize(const sp<IHalProxyCallback>& halProxyCallback) {
    mCallback = halProxyCallback;
    setOperationMode(OperationMode::NORMAL);
    return Result::OK;
}

void SensorsSubHal::postEvents(const std::vector<Event>& events, bool wakeup) {
    ScopedWakelock wakelock = mCallback->createScopedWakelock(wakeup);
    mCallback->postEvents(events, std::move(wakelock));
}

}  // namespace implementation
}  // namespace subhal
}  // namespace V2_0
}  // namespace sensors
}  // namespace hardware
}  // namespace android
