#pragma once

#include "aidl/android/aidl/tests/ICircular.h"

#include <android/binder_ibinder.h>

namespace aidl {
namespace android {
namespace aidl {
namespace tests {
class BpCircular : public ::ndk::BpCInterface<ICircular> {
public:
  explicit BpCircular(const ::ndk::SpAIBinder& binder);
  virtual ~BpCircular();

  ::ndk::ScopedAStatus GetTestService(std::shared_ptr<::aidl::android::aidl::tests::ITestService>* _aidl_return) override;
};
}  // namespace tests
}  // namespace aidl
}  // namespace android
}  // namespace aidl
