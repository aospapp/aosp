#pragma once

#include <android/aidl/tests/ITestService.h>
#include <binder/IBinder.h>
#include <binder/IInterface.h>
#include <binder/Status.h>
#include <binder/Trace.h>
#include <optional>
#include <utils/StrongPointer.h>

namespace android::aidl::tests {
class ITestService;
}  // namespace android::aidl::tests
namespace android {
namespace aidl {
namespace tests {
class ICircularDelegator;

class ICircular : public ::android::IInterface {
public:
  typedef ICircularDelegator DefaultDelegator;
  DECLARE_META_INTERFACE(Circular)
  virtual ::android::binder::Status GetTestService(::android::sp<::android::aidl::tests::ITestService>* _aidl_return) = 0;
};  // class ICircular

class ICircularDefault : public ICircular {
public:
  ::android::IBinder* onAsBinder() override {
    return nullptr;
  }
  ::android::binder::Status GetTestService(::android::sp<::android::aidl::tests::ITestService>* /*_aidl_return*/) override {
    return ::android::binder::Status::fromStatusT(::android::UNKNOWN_TRANSACTION);
  }
};  // class ICircularDefault
}  // namespace tests
}  // namespace aidl
}  // namespace android
