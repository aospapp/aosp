#ifndef DRM_FACTORY_H_
#define DRM_FACTORY_H_

#include <aidl/android/hardware/drm/BnDrmFactory.h>
#include <aidl/android/hardware/drm/IDrmFactory.h>
#include <aidl/android/hardware/drm/IDrmPlugin.h>
#include <aidl/android/hardware/drm/ICryptoPlugin.h>

#include <string>
#include <vector>

#include "CastKeyTypes.h"

namespace aidl {
namespace android {
namespace hardware {
namespace drm {
namespace castkey {

struct DrmFactory : public BnDrmFactory {
    DrmFactory() {};
    virtual ~DrmFactory() {};

    ::ndk::ScopedAStatus createDrmPlugin(
            const ::aidl::android::hardware::drm::Uuid& in_uuid,
            const std::string& in_appPackageName,
            std::shared_ptr<::aidl::android::hardware::drm::IDrmPlugin>* _aidl_return) override;

    ::ndk::ScopedAStatus createCryptoPlugin(
            const ::aidl::android::hardware::drm::Uuid& in_uuid,
            const std::vector<uint8_t>& in_initData,
            std::shared_ptr<::aidl::android::hardware::drm::ICryptoPlugin>* _aidl_return) override;

    ::ndk::ScopedAStatus getSupportedCryptoSchemes(
            ::aidl::android::hardware::drm::CryptoSchemes* _aidl_return) override;

    binder_status_t dump(int fd, const char** args, uint32_t numArgs) override;


  private:
    CASTKEY_DISALLOW_COPY_AND_ASSIGN(DrmFactory);
};

}  // namespace castkey
}  // namespace drm
}  // namespace hardware
}  // namespace android
}  // namespace aidl

#endif  // DRM_FACTORIES_H_
