#pragma once

#include <binder/IBinder.h>
#include <binder/IInterface.h>
#include <utils/Errors.h>
#include <android/aidl/tests/ICircular.h>

namespace android {
namespace aidl {
namespace tests {
class BpCircular : public ::android::BpInterface<ICircular> {
public:
  explicit BpCircular(const ::android::sp<::android::IBinder>& _aidl_impl);
  virtual ~BpCircular() = default;
  ::android::binder::Status GetTestService(::android::sp<::android::aidl::tests::ITestService>* _aidl_return) override;
};  // class BpCircular
}  // namespace tests
}  // namespace aidl
}  // namespace android
