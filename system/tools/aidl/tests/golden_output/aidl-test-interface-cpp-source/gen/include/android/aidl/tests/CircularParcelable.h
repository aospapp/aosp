#pragma once

#include <android/aidl/tests/ITestService.h>
#include <android/binder_to_string.h>
#include <binder/Parcel.h>
#include <binder/Status.h>
#include <optional>
#include <tuple>
#include <utils/String16.h>

namespace android::aidl::tests {
class ITestService;
}  // namespace android::aidl::tests
namespace android {
namespace aidl {
namespace tests {
class CircularParcelable : public ::android::Parcelable {
public:
  ::android::sp<::android::aidl::tests::ITestService> testService;
  inline bool operator!=(const CircularParcelable& rhs) const {
    return std::tie(testService) != std::tie(rhs.testService);
  }
  inline bool operator<(const CircularParcelable& rhs) const {
    return std::tie(testService) < std::tie(rhs.testService);
  }
  inline bool operator<=(const CircularParcelable& rhs) const {
    return std::tie(testService) <= std::tie(rhs.testService);
  }
  inline bool operator==(const CircularParcelable& rhs) const {
    return std::tie(testService) == std::tie(rhs.testService);
  }
  inline bool operator>(const CircularParcelable& rhs) const {
    return std::tie(testService) > std::tie(rhs.testService);
  }
  inline bool operator>=(const CircularParcelable& rhs) const {
    return std::tie(testService) >= std::tie(rhs.testService);
  }

  ::android::status_t readFromParcel(const ::android::Parcel* _aidl_parcel) final;
  ::android::status_t writeToParcel(::android::Parcel* _aidl_parcel) const final;
  static const ::android::String16& getParcelableDescriptor() {
    static const ::android::StaticString16 DESCRIPTOR (u"android.aidl.tests.CircularParcelable");
    return DESCRIPTOR;
  }
  inline std::string toString() const {
    std::ostringstream os;
    os << "CircularParcelable{";
    os << "testService: " << ::android::internal::ToString(testService);
    os << "}";
    return os.str();
  }
};  // class CircularParcelable
}  // namespace tests
}  // namespace aidl
}  // namespace android
