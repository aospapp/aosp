#ifndef __VTS_DRIVER__hal_legacy_wifi_V1_0__
#define __VTS_DRIVER__hal_legacy_wifi_V1_0__

#undef LOG_TAG
#define LOG_TAG "FuzzerExtended_wifi"
#include <hardware/hardware.h>
#include <hardware_legacy/wifi_hal.h>

#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <utils/Log.h>

#include <driver_base/DriverBase.h>
#include <driver_base/DriverCallbackBase.h>

namespace android {
namespace vts {
class FuzzerExtended_wifi : public DriverBase {
 public:
    FuzzerExtended_wifi() : DriverBase(HAL_LEGACY) {}
 protected:
    bool Fuzz(FunctionSpecificationMessage* func_msg, void** result, const string& callback_socket_name);
    bool CallFunction(const FunctionSpecificationMessage& func_msg, const string& callback_socket_name, FunctionSpecificationMessage* result_msg);
    bool VerifyResults(const FunctionSpecificationMessage& expected_result, const FunctionSpecificationMessage& actual_result);
    bool GetAttribute(FunctionSpecificationMessage* func_msg, void** result);
 private:
};


extern "C" {
extern android::vts::DriverBase* vts_func_3_5_V1_0_();
}
}  // namespace vts
}  // namespace android
#endif
