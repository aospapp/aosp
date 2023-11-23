#ifndef UTILS_H_
#define UTILS_H_

#include <string>

#include "SessionLibrary.h"

#include <android/binder_auto_utils.h>
#include "aidl/android/hardware/drm/Status.h"
#include "CastKeyTypes.h"

namespace aidl {
namespace android {
namespace hardware {
namespace drm {
namespace castkey {

inline ::ndk::ScopedAStatus toNdkScopedAStatus(::aidl::android::hardware::drm::Status status,
                                               const char* msg = nullptr) {
    if (Status::OK == status) {
        return ::ndk::ScopedAStatus::ok();
    } else {
        auto err = static_cast<int32_t>(status);
        if (msg) {
            return ::ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(err, msg);
        } else {
            return ::ndk::ScopedAStatus::fromServiceSpecificError(err);
        }
    }
}

inline ::ndk::ScopedAStatus toNdkScopedAStatus(castkeydrm::CdmResponseType res) {
    return toNdkScopedAStatus(static_cast<::aidl::android::hardware::drm::Status>(res));
}

#define UNUSED(x) (void)(x);

}  // namespace castkey
}  // namespace drm
}  // namespace hardware
}  // namespace android
}  // namespace aidl

#endif  // UTILS_H_
