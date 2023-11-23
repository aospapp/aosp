#ifndef CREATE_PLUGIN_FACTORIES_H_
#define CREATE_PLUGIN_FACTORIES_H_

#include "DrmFactory.h"

namespace aidl {
namespace android {
namespace hardware {
namespace drm {
namespace castkey {

std::shared_ptr<DrmFactory> createDrmFactory();

}  // namespace castkey
}  // namespace drm
}  // namespace hardware
}  // namespace android
}  // namespace aidl

#endif  // CREATE_PLUGIN_FACTORIES_H_