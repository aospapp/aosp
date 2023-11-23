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

#include "Sensor.h"
#include <hardware/sensors.h>
#include <log/log.h>
#include <utils/SystemClock.h>
#include <cmath>

namespace android {
namespace hardware {
namespace sensors {
namespace V2_0 {
namespace subhal {
namespace implementation {

using ::android::hardware::sensors::V1_0::MetaDataEventType;
using ::android::hardware::sensors::V1_0::SensorFlagBits;
using ::android::hardware::sensors::V1_0::SensorStatus;

SensorBase::SensorBase(int32_t sensorHandle, ISensorsEventCallback* callback, SensorType type)
    : mIsEnabled(false), mSamplingPeriodNs(0), mCallback(callback), mMode(OperationMode::NORMAL) {
    mSensorInfo.type = type;
    mSensorInfo.sensorHandle = sensorHandle;
    mSensorInfo.vendor = "Google";
    mSensorInfo.version = 1;
    mSensorInfo.fifoReservedEventCount = 0;
    mSensorInfo.fifoMaxEventCount = 0;
    mSensorInfo.requiredPermission = "";
    mSensorInfo.flags = 0;
    switch (type) {
        case SensorType::ACCELEROMETER:
            mSensorInfo.typeAsString = SENSOR_STRING_TYPE_ACCELEROMETER;
            break;
        case SensorType::GYROSCOPE:
            mSensorInfo.typeAsString = SENSOR_STRING_TYPE_GYROSCOPE;
            break;
        default:
            ALOGE("unsupported sensor type %d", type);
            break;
    }
    // TODO(jbhayana) : Make the threading policy configurable
    mRunThread = std::thread(std::bind(&SensorBase::run, this));
}

SensorBase::~SensorBase() {
    // Ensure that lock is unlocked before calling mRunThread.join() or a
    // deadlock will occur.
    {
        std::unique_lock<std::mutex> lock(mRunMutex);
        mStopThread = true;
        mIsEnabled = false;
        mWaitCV.notify_all();
    }
    mRunThread.join();
}

HWSensorBase::~HWSensorBase() {
    close(mpollfd_iio.fd);
}

const SensorInfo& SensorBase::getSensorInfo() const {
    return mSensorInfo;
}

void HWSensorBase::batch(int32_t samplingPeriodNs) {
    samplingPeriodNs =
            std::clamp(samplingPeriodNs, mSensorInfo.minDelay * 1000, mSensorInfo.maxDelay * 1000);
    if (mSamplingPeriodNs != samplingPeriodNs) {
        unsigned int sampling_frequency = ns_to_frequency(samplingPeriodNs);
        int i = 0;
        mSamplingPeriodNs = samplingPeriodNs;
        std::vector<double>::iterator low =
                std::lower_bound(miio_data.sampling_freq_avl.begin(),
                                 miio_data.sampling_freq_avl.end(), sampling_frequency);
        i = low - miio_data.sampling_freq_avl.begin();
        set_sampling_frequency(miio_data.sysfspath, miio_data.sampling_freq_avl[i]);
        // Wake up the 'run' thread to check if a new event should be generated now
        mWaitCV.notify_all();
    }
}

void HWSensorBase::activate(bool enable) {
    std::unique_lock<std::mutex> lock(mRunMutex);
    if (mIsEnabled != enable) {
        mIsEnabled = enable;
        enable_sensor(miio_data.sysfspath, enable);
        mWaitCV.notify_all();
    }
}

Result SensorBase::flush() {
    // Only generate a flush complete event if the sensor is enabled and if the sensor is not a
    // one-shot sensor.
    if (!mIsEnabled || (mSensorInfo.flags & static_cast<uint32_t>(SensorFlagBits::ONE_SHOT_MODE))) {
        return Result::BAD_VALUE;
    }

    // Note: If a sensor supports batching, write all of the currently batched events for the sensor
    // to the Event FMQ prior to writing the flush complete event.
    Event ev;
    ev.sensorHandle = mSensorInfo.sensorHandle;
    ev.sensorType = SensorType::META_DATA;
    ev.u.meta.what = MetaDataEventType::META_DATA_FLUSH_COMPLETE;
    std::vector<Event> evs{ev};
    mCallback->postEvents(evs, isWakeUpSensor());
    return Result::OK;
}

void HWSensorBase::processScanData(uint8_t* data, Event* evt) {
    float channelData[NUM_OF_CHANNEL_SUPPORTED - 1];
    int64_t ts;
    unsigned int chanIdx;
    evt->sensorHandle = mSensorInfo.sensorHandle;
    evt->sensorType = mSensorInfo.type;
    for (auto i = 0u; i < miio_data.channelInfo.size(); i++) {
        chanIdx = miio_data.channelInfo[i].index;
        if (miio_data.channelInfo[i].sign) {
            int64_t val = *reinterpret_cast<int64_t*>(
                    data + chanIdx * miio_data.channelInfo[i].storage_bytes);
            if (chanIdx == (miio_data.channelInfo.size() - 1)) {
                ts = val;
            } else {
                channelData[chanIdx] = (static_cast<float>(val) * miio_data.resolution);
            }
        } else {
            uint64_t val = *reinterpret_cast<uint64_t*>(
                    data + chanIdx * miio_data.channelInfo[i].storage_bytes);
            channelData[chanIdx] = (static_cast<float>(val) * miio_data.resolution);
        }
    }
    evt->u.vec3.x = channelData[0];
    evt->u.vec3.y = channelData[1];
    evt->u.vec3.z = channelData[2];
    evt->timestamp = ts;
    evt->u.vec3.status = SensorStatus::ACCURACY_HIGH;
}

void HWSensorBase::run() {
    int err;
    int read_size;
    std::vector<Event> events;
    Event event;

    while (!mStopThread) {
        if (!mIsEnabled || mMode == OperationMode::DATA_INJECTION) {
            std::unique_lock<std::mutex> runLock(mRunMutex);
            mWaitCV.wait(runLock, [&] {
                return ((mIsEnabled && mMode == OperationMode::NORMAL) || mStopThread);
            });
        } else {
            err = poll(&mpollfd_iio, 1, mSamplingPeriodNs * 1000);
            if (err <= 0) {
                ALOGE("Sensor %s poll returned %d", miio_data.name.c_str(), err);
                continue;
            }
            if (mpollfd_iio.revents & POLLIN) {
                read_size = read(mpollfd_iio.fd, &msensor_raw_data[0], mscan_size);
                if (read_size <= 0) {
                    ALOGE("%s: Failed to read data from iio char device.", miio_data.name.c_str());
                    continue;
                }
                events.clear();
                processScanData(&msensor_raw_data[0], &event);
                events.push_back(event);
                mCallback->postEvents(events, isWakeUpSensor());
            }
        }
    }
}

bool SensorBase::isWakeUpSensor() {
    return mSensorInfo.flags & static_cast<uint32_t>(SensorFlagBits::WAKE_UP);
}

void SensorBase::setOperationMode(OperationMode mode) {
    std::unique_lock<std::mutex> lock(mRunMutex);
    if (mMode != mode) {
        mMode = mode;
        mWaitCV.notify_all();
    }
}

bool SensorBase::supportsDataInjection() const {
    return mSensorInfo.flags & static_cast<uint32_t>(SensorFlagBits::DATA_INJECTION);
}

Result SensorBase::injectEvent(const Event& event) {
    Result result = Result::OK;
    if (event.sensorType == SensorType::ADDITIONAL_INFO) {
        // When in OperationMode::NORMAL, SensorType::ADDITIONAL_INFO is used to push operation
        // environment data into the device.
    } else if (!supportsDataInjection()) {
        result = Result::INVALID_OPERATION;
    } else if (mMode == OperationMode::DATA_INJECTION) {
        mCallback->postEvents(std::vector<Event>{event}, isWakeUpSensor());
    } else {
        result = Result::BAD_VALUE;
    }
    return result;
}

ssize_t HWSensorBase::calculateScanSize() {
    ssize_t numBytes = 0;
    for (auto i = 0u; i < miio_data.channelInfo.size(); i++) {
        numBytes += miio_data.channelInfo[i].storage_bytes;
    }
    return numBytes;
}

HWSensorBase::HWSensorBase(int32_t sensorHandle, ISensorsEventCallback* callback, SensorType type,
                           const struct iio_device_data& data)
    : SensorBase(sensorHandle, callback, type) {
    std::string buffer_path;
    mSensorInfo.flags |= SensorFlagBits::CONTINUOUS_MODE;
    mSensorInfo.name = data.name;
    mSensorInfo.resolution = data.resolution;
    mSensorInfo.maxRange = data.max_range * data.resolution;
    mSensorInfo.power =
            (data.power_microwatts / 1000.f) / SENSOR_VOLTAGE_DEFAULT;  // converting uW to mA
    miio_data = data;
    unsigned int max_sampling_frequency = 0;
    unsigned int min_sampling_frequency = UINT_MAX;
    for (auto i = 0u; i < data.sampling_freq_avl.size(); i++) {
        if (max_sampling_frequency < data.sampling_freq_avl[i])
            max_sampling_frequency = data.sampling_freq_avl[i];
        if (min_sampling_frequency > data.sampling_freq_avl[i])
            min_sampling_frequency = data.sampling_freq_avl[i];
    }
    mSensorInfo.minDelay = frequency_to_us(max_sampling_frequency);
    mSensorInfo.maxDelay = frequency_to_us(min_sampling_frequency);
    mscan_size = calculateScanSize();
    buffer_path = "/dev/iio:device";
    buffer_path.append(std::to_string(miio_data.iio_dev_num));
    mpollfd_iio.fd = open(buffer_path.c_str(), O_RDONLY | O_NONBLOCK);
    if (mpollfd_iio.fd < 0) {
        ALOGE("%s: Failed to open iio char device (%s).", data.name.c_str(), buffer_path.c_str());
        return;
    }
    mpollfd_iio.events = POLLIN;
    msensor_raw_data.resize(mscan_size);
}

Accelerometer::Accelerometer(int32_t sensorHandle, ISensorsEventCallback* callback,
                             const struct iio_device_data& data)
    : HWSensorBase(sensorHandle, callback, SensorType::ACCELEROMETER, data) {
}

Gyroscope::Gyroscope(int32_t sensorHandle, ISensorsEventCallback* callback,
                     const struct iio_device_data& data)
    : HWSensorBase(sensorHandle, callback, SensorType::GYROSCOPE, data) {
}

}  // namespace implementation
}  // namespace subhal
}  // namespace V2_0
}  // namespace sensors
}  // namespace hardware
}  // namespace android
