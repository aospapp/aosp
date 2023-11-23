#define LOG_TAG "castkey-DrmFactory"

#include <utils/Log.h>

#include "DrmFactory.h"

#include "DrmPlugin.h"
#include "Utils.h"

namespace aidl {
namespace android {
namespace hardware {
namespace drm {
namespace castkey {

namespace {

const std::array<uint8_t, 16> kCastKeyUUID{
    0xBC, 0xB4, 0x81, 0xCB, 0xA1, 0xD5, 0x42, 0xAF,
    0xB1, 0xE3, 0x7B, 0xFF, 0x14, 0x73, 0xEB, 0x85
};

bool isCastKeyUUID(const uint8_t uuid[16]) {
    return !memcmp(uuid, kCastKeyUUID.data(), 16);
}

}

using std::string;
using std::vector;

using ::aidl::android::hardware::drm::Status;
using ::aidl::android::hardware::drm::Uuid;
using namespace castkeydrm;

::ndk::ScopedAStatus DrmFactory::createDrmPlugin(
        const Uuid& in_uuid, const string& in_appPackageName,
        std::shared_ptr<::aidl::android::hardware::drm::IDrmPlugin>* _aidl_return) {
    UNUSED(in_appPackageName);
    if (!isCastKeyUUID(in_uuid.uuid.data())) {
        ALOGE("Castkey Drm HAL: failed to create drm plugin, "
              "invalid crypto scheme");
        *_aidl_return = nullptr;
        return toNdkScopedAStatus(Status::BAD_VALUE);
    }
    std::shared_ptr<DrmPlugin> plugin =
            ::ndk::SharedRefBase::make<DrmPlugin>();
    *_aidl_return = plugin;
    return toNdkScopedAStatus(Status::OK);
}

::ndk::ScopedAStatus DrmFactory::createCryptoPlugin(
        const Uuid& in_uuid, const std::vector<uint8_t>& in_initData,
        std::shared_ptr<::aidl::android::hardware::drm::ICryptoPlugin>* _aidl_return) {
    UNUSED(in_uuid);
    UNUSED(in_initData);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmFactory::getSupportedCryptoSchemes(CryptoSchemes* _aidl_return) {
    CryptoSchemes schemes{};
    schemes.uuids.push_back({kCastKeyUUID});
    *_aidl_return = schemes;
    return toNdkScopedAStatus(Status::OK);
}

binder_status_t DrmFactory::dump(int fd, const char** args, uint32_t numArgs) {
    UNUSED(args);
    UNUSED(numArgs);
    if (fd < 0) {
        ALOGE("%s: negative fd", __FUNCTION__);
        return STATUS_BAD_VALUE;
    }
    return STATUS_OK;
}

}  // namespace castkey
}  // namespace drm
}  // namespace hardware
}  // namespace android
}  // namespace aidl
