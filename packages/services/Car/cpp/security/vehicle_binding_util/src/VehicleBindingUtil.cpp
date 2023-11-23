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

#include "VehicleBindingUtil.h"

#include <android-base/logging.h>
#include <android/hardware/automotive/vehicle/2.0/types.h>
#include <cutils/properties.h>  // for property_get
#include <logwrap/logwrap.h>
#include <utils/SystemClock.h>

#include <fcntl.h>
#include <stdlib.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <vector>

namespace android {
namespace automotive {
namespace security {
namespace {

using android::hardware::automotive::vehicle::V2_0::IVehicle;
using android::hardware::automotive::vehicle::V2_0::StatusCode;
using android::hardware::automotive::vehicle::V2_0::VehicleArea;
using android::hardware::automotive::vehicle::V2_0::VehiclePropConfig;
using android::hardware::automotive::vehicle::V2_0::VehicleProperty;
using android::hardware::automotive::vehicle::V2_0::VehiclePropertyStatus;
using android::hardware::automotive::vehicle::V2_0::VehiclePropValue;

template <typename T>
using hidl_vec = android::hardware::hidl_vec<T>;

bool isSeedVhalPropertySupported(sp<IVehicle> vehicle) {
    bool is_supported = false;

    hidl_vec<int32_t> props = {
            static_cast<int32_t>(VehicleProperty::STORAGE_ENCRYPTION_BINDING_SEED)};
    vehicle->getPropConfigs(props,
                            [&is_supported](StatusCode status,
                                            hidl_vec<VehiclePropConfig> /*propConfigs*/) {
                                is_supported = (status == StatusCode::OK);
                            });
    return is_supported;
}

std::string toHexString(const std::vector<uint8_t>& bytes) {
    const char lookup[] = "0123456789abcdef";
    std::string out;
    out.reserve(bytes.size() * 2);
    for (auto b : bytes) {
        out += lookup[b >> 4];
        out += lookup[b & 0xf];
    }
    return out;
}

BindingStatus setSeedVhalProperty(sp<IVehicle> vehicle, const std::vector<uint8_t>& seed) {
    VehiclePropValue propValue;
    propValue.timestamp = elapsedRealtimeNano();
    propValue.areaId = toInt(VehicleArea::GLOBAL);
    propValue.prop = toInt(VehicleProperty::STORAGE_ENCRYPTION_BINDING_SEED);
    propValue.status = VehiclePropertyStatus::AVAILABLE;
    propValue.value.bytes = seed;
    StatusCode vhal_status = vehicle->set(propValue);
    if (vhal_status == StatusCode::OK) {
        return BindingStatus::OK;
    }

    LOG(ERROR) << "Unable to set the VHAL property: " << toString(vhal_status);
    return BindingStatus::ERROR;
}

BindingStatus getSeedVhalProperty(sp<IVehicle> vehicle, std::vector<uint8_t>* seed) {
    VehiclePropValue desired_prop;
    desired_prop.prop = static_cast<int32_t>(VehicleProperty::STORAGE_ENCRYPTION_BINDING_SEED);
    BindingStatus status = BindingStatus::ERROR;
    vehicle->get(desired_prop,
                 [&status, &seed](StatusCode prop_status, const VehiclePropValue& propValue) {
                     if (prop_status != StatusCode::OK) {
                         LOG(ERROR) << "Error reading vehicle property: " << toString(prop_status);
                     } else {
                         status = BindingStatus::OK;
                         *seed = std::vector<uint8_t>{propValue.value.bytes.begin(),
                                                      propValue.value.bytes.end()};
                     }
                 });

    return status;
}

BindingStatus sendSeedToVold(const Executor& executor, const std::vector<uint8_t>& seed) {
    int status = 0;

    // we pass the seed value via environment variable in the forked process
    setenv("SEED_VALUE", toHexString(seed).c_str(), 1);
    int rc = executor.run({"/system/bin/vdc", "cryptfs", "bindkeys"}, &status);
    unsetenv("SEED_VALUE");
    LOG(INFO) << "rc: " << rc;
    LOG(INFO) << "status: " << status;
    if (rc != 0 || status != 0) {
        LOG(ERROR) << "Error running vdc: " << rc << ", " << status;
        return BindingStatus::ERROR;
    }
    return BindingStatus::OK;
}

}  // namespace

bool DefaultCsrng::fill(void* buffer, size_t size) const {
    int fd = TEMP_FAILURE_RETRY(open("/dev/urandom", O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (fd == -1) {
        LOG(ERROR) << "Error opening urandom: " << errno;
        return false;
    }

    ssize_t bytes_read;
    uint8_t* bufptr = static_cast<uint8_t*>(buffer);
    while ((bytes_read = TEMP_FAILURE_RETRY(read(fd, bufptr, size))) > 0) {
        size -= bytes_read;
        bufptr += bytes_read;
    }

    close(fd);

    if (size != 0) {
        LOG(ERROR) << "Unable to read " << size << " bytes from urandom";
        return false;
    }
    return true;
}

int DefaultExecutor::run(const std::vector<std::string>& cmd_args, int* exit_code) const {
    std::vector<const char*> argv;
    argv.reserve(cmd_args.size());
    for (auto& arg : cmd_args) {
        argv.push_back(arg.c_str());
    }
    int status = 0;
    return logwrap_fork_execvp(argv.size(), argv.data(), exit_code, false /*forward_signals*/,
                               LOG_KLOG, true /*abbreviated*/, nullptr /*file_path*/);
}

BindingStatus setVehicleBindingSeed(sp<IVehicle> vehicle, const Executor& executor,
                                    const Csrng& csrng) {
    if (!isSeedVhalPropertySupported(vehicle)) {
        LOG(WARNING) << "Vehicle binding seed is not supported by the VHAL.";
        return BindingStatus::NOT_SUPPORTED;
    }

    std::vector<uint8_t> seed;
    BindingStatus status = getSeedVhalProperty(vehicle, &seed);
    if (status != BindingStatus::OK) {
        LOG(ERROR) << "Unable to read the seed from the VHAL: " << static_cast<int>(status);
        return status;
    }

    if (seed.empty()) {
        seed = std::vector<uint8_t>(SEED_BYTE_SIZE);
        if (!csrng.fill(seed.data(), seed.size())) {
            LOG(ERROR) << "Error getting random seed: " << static_cast<int>(status);
            return BindingStatus::ERROR;
        }

        status = setSeedVhalProperty(vehicle, seed);
        if (status != BindingStatus::OK) {
            LOG(ERROR) << "Error storing the seed in the VHAL: " << static_cast<int>(status);
            return status;
        }
    }

    status = sendSeedToVold(executor, seed);
    if (status == BindingStatus::OK) {
        LOG(INFO) << "Successfully bound vehicle storage to seed.";
    }
    return status;
}

}  // namespace security
}  // namespace automotive
}  // namespace android
