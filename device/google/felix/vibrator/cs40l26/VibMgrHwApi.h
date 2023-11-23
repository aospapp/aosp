/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <fcntl.h>
#include <linux/gpio.h>
#include <log/log.h>

#include <map>

#include "Vibrator.h"
#include "utils.h"

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {

class VibMgrHwApi : public Vibrator::HwGPIO {
  private:
    const uint32_t DEBUG_GPI_PIN = UINT16_MAX;
    const uint32_t DEBUG_GPI_PIN_SHIFT = UINT16_MAX;
    std::string mPropertyPrefix;
    uint32_t mGPIOPin;
    uint32_t mGPIOShift;
    struct gpiohandle_request mRq;

  public:
    static std::unique_ptr<VibMgrHwApi> Create() {
        auto hwapi = std::unique_ptr<VibMgrHwApi>(new VibMgrHwApi());
        return hwapi;
    }
    bool getGPIO() override {
        auto propertyPrefix = std::getenv("PROPERTY_PREFIX");

        if (propertyPrefix != NULL) {
            mPropertyPrefix = std::string(propertyPrefix);
        } else {
            ALOGE("GetGPIO: Failed get property prefix!");
            return false;
        }
        mGPIOPin = utils::getProperty(mPropertyPrefix + "gpio.num", DEBUG_GPI_PIN);
        if (mGPIOPin == DEBUG_GPI_PIN) {
            ALOGE("GetGPIO: Failed to get the GPIO num: %s", strerror(errno));
            return false;
        }
        mGPIOShift = utils::getProperty(mPropertyPrefix + "gpio.shift", DEBUG_GPI_PIN_SHIFT);

        if (mGPIOShift == DEBUG_GPI_PIN_SHIFT) {
            ALOGE("GetGPIO: Failed to get the GPIO shift num: %s", strerror(errno));
            return false;
        }

        return true;
    }
    bool initGPIO() override {
        const auto gpio_dev = std::string() + "/dev/gpiochip" + std::to_string(mGPIOPin);
        int fd = open(gpio_dev.c_str(), O_CREAT | O_WRONLY, 0777);
        if (fd < 0) {
            ALOGE("InitGPIO: Unable to open gpio dev: %s", strerror(errno));
            return false;
        }

        mRq.lineoffsets[0] = mGPIOShift;
        mRq.lines = 1;
        mRq.flags = GPIOHANDLE_REQUEST_OUTPUT;

        int ret = ioctl(fd, GPIO_GET_LINEHANDLE_IOCTL, &mRq);
        if (ret == -1) {
            ALOGE("InitGPIO: Unable to line handle from ioctl : %s", strerror(errno));
            close(fd);
            return false;
        }
        if (close(fd) == -1) {
            ALOGE("Failed to close GPIO char dev");
            return false;
        }
        // Reset gpio status to LOW
        struct gpiohandle_data data;
        data.values[0] = 0;

        ret = ioctl(mRq.fd, GPIOHANDLE_SET_LINE_VALUES_IOCTL, &data);
        if (ret == -1) {
            ALOGE("InitGPIO: Unable to set line value using ioctl : %s", strerror(errno));
            close(mRq.fd);
            return false;
        }
        return true;
    }
    bool setGPIOOutput(bool value) override {
        struct gpiohandle_data data;
        data.values[0] = value;

        int ret = ioctl(mRq.fd, GPIOHANDLE_SET_LINE_VALUES_IOCTL, &data);
        if (ret == -1) {
            ALOGE("SetTrigger: Unable to set line value using ioctl : %s", strerror(errno));
            close(mRq.fd);
            return false;
        }

        return true;
    }
    void debug(int fd) override { ALOGD("Debug: %d", fd); }

  private:
    VibMgrHwApi() { ALOGD("Constructor"); }
};

}  // namespace vibrator
}  // namespace hardware
}  // namespace android
}  // namespace aidl
