#include <iostream>
#include <binder/Parcel.h>
#include <android/binder_to_string.h>
#include <android/aidl/tests/IDeprecated.h>
namespace {
android::status_t analyzeIDeprecated(uint32_t _aidl_code, const android::Parcel& _aidl_data, const android::Parcel& _aidl_reply) {
  android::status_t _aidl_ret_status;
  switch(_aidl_code) {
    default:
    {
      std::cout << "  Transaction code " << _aidl_code << " not known." << std::endl;
    _aidl_ret_status = android::UNKNOWN_TRANSACTION;
    }
  }
  return _aidl_ret_status;
  // To prevent unused variable warnings
  (void)_aidl_ret_status; (void)_aidl_data; (void)_aidl_reply;
}

} // namespace

#include <Analyzer.h>
using android::aidl::Analyzer;
__attribute__((constructor)) static void addAnalyzer() {
  Analyzer::installAnalyzer(std::make_unique<Analyzer>("android.aidl.tests.IDeprecated", "IDeprecated", &analyzeIDeprecated));
}
