#include "CreatePluginFactories.h"

namespace aidl {
namespace android {
namespace hardware {
namespace drm {
namespace castkey {

std::shared_ptr<DrmFactory> createDrmFactory() {
    return ::ndk::SharedRefBase::make<DrmFactory>();
}

}  // namespace castkey
}  // namespace drm
}  // namespace hardware
}  // namespace android
}  // namespace aidl