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
#ifndef ANDROID_HARDWARE_SENSORS_V2_0_SENSOR_H
#define ANDROID_HARDWARE_SENSORS_V2_0_SENSOR_H

#include <android/hardware/sensors/1.0/types.h>
#include <poll.h>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include "iio_utils.h"

#define NUM_OF_CHANNEL_SUPPORTED 4

using ::android::hardware::sensors::V1_0::Event;
using ::android::hardware::sensors::V1_0::OperationMode;
using ::android::hardware::sensors::V1_0::Result;
using ::android::hardware::sensors::V1_0::SensorInfo;
using ::android::hardware::sensors::V1_0::SensorType;

namespace android {
namespace hardware {
namespace sensors {
namespace V2_0 {
namespace subhal {
namespace implementation {

static constexpr unsigned int frequency_to_us(unsigned int x) {
    return (1E6 / x);
}

static constexpr unsigned int ns_to_frequency(unsigned int x) {
    return (1E9 / x);
}

// SCMI IIO driver gives sensor power in microwatts. Sensor HAL expects
// it in mA. Conversion process needs the input voltage for the IMU.
// Based on commonly used IMUs, 3.6V is picked as the default.
constexpr auto SENSOR_VOLTAGE_DEFAULT = 3.6f;

class ISensorsEventCallback {
  public:
    virtual ~ISensorsEventCallback() = default;
    virtual void postEvents(const std::vector<Event>& events, bool wakeup) = 0;
};

// Virtual Base Class for Sensor
class SensorBase {
  public:
    SensorBase(int32_t sensorHandle, ISensorsEventCallback* callback, SensorType type);
    virtual ~SensorBase();
    const SensorInfo& getSensorInfo() const;
    virtual void batch(int32_t samplingPeriodNs) = 0;
    virtual void activate(bool enable) = 0;
    Result flush();
    void setOperationMode(OperationMode mode);
    bool supportsDataInjection() const;
    Result injectEvent(const Event& event);

  protected:
    virtual void run() = 0;
    bool isWakeUpSensor();

    bool mIsEnabled;
    int64_t mSamplingPeriodNs;
    SensorInfo mSensorInfo;
    std::atomic_bool mStopThread;
    std::condition_variable mWaitCV;
    std::mutex mRunMutex;
    std::thread mRunThread;
    ISensorsEventCallback* mCallback;
    OperationMode mMode;
};

// HWSensorBase represents the actual physical sensor provided as the IIO device
class HWSensorBase : public SensorBase {
  public:
    HWSensorBase(int32_t sensorHandle, ISensorsEventCallback* callback, SensorType type,
                 const struct iio_device_data& iio_data);
    virtual ~HWSensorBase() = 0;
    void batch(int32_t samplingPeriodNs);
    void activate(bool enable);

    struct iio_device_data miio_data;

  private:
    ssize_t mscan_size;
    struct pollfd mpollfd_iio;
    std::vector<uint8_t> msensor_raw_data;

    ssize_t calculateScanSize();
    void run();
    void processScanData(uint8_t* data, Event* evt);
};

class Accelerometer : public HWSensorBase {
  public:
    Accelerometer(int32_t sensorHandle, ISensorsEventCallback* callback,
                  const struct iio_device_data& iio_data);
};

class Gyroscope : public HWSensorBase {
  public:
    Gyroscope(int32_t sensorHandle, ISensorsEventCallback* callback,
              const struct iio_device_data& iio_data);
};

}  // namespace implementation
}  // namespace subhal
}  // namespace V2_0
}  // namespace sensors
}  // namespace hardware
}  // namespace android
#endif  // ANDROID_HARDWARE_SENSORS_V2_0_SENSOR_H
