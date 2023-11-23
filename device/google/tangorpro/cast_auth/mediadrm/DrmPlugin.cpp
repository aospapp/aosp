#define LOG_TAG "castkey-DrmPlugin"

#include <utils/Log.h>
#include <dlfcn.h>
#include <fstream>
#include <vector>
#include <stdlib.h>

#include "Utils.h"
#include "DrmPlugin.h"
#include "SessionLibrary.h"

namespace aidl {
namespace android {
namespace hardware {
namespace drm {
namespace castkey {

using namespace castkeydrm;

DrmPlugin::DrmPlugin() {};
DrmPlugin::~DrmPlugin() {};

constexpr char CAST_CRT_FILE[] = "/mnt/vendor/persist/nest/cast_auth.crt";
constexpr char CAST_ICA_FILE[] = "/vendor/etc/cert-chain.crt";
constexpr unsigned char kSha1Prefix[] = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
constexpr unsigned char kSha256Prefix[] = {0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60,
                              0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02,
                              0x01, 0x05, 0x00, 0x04, 0x20};
constexpr int kSHA1Length = 20;
constexpr int kSHA256Length = 32;
constexpr int DigestInfoPrefixesSize = 2;

struct DigestInfoPrefix {
    const unsigned char* bytes;
    size_t size;
    size_t hash_size;
};

// Array of DigestInfoPrefixes that are currently supported (SHA1 and SHA256).
const DigestInfoPrefix kDigestInfoPrefixes[] = {
    {kSha1Prefix, sizeof(kSha1Prefix), kSHA1Length},
    {kSha256Prefix, sizeof(kSha256Prefix), kSHA256Length},
};

// If we find a raw hash, prepend the appropriate DER prefix.
std::vector<uint8_t> adjustHash(const std::vector<uint8_t>& hash) {
    std::vector<uint8_t> adjusted_hash(hash);
    for (size_t i = 0; i < DigestInfoPrefixesSize; i++) {
        const DigestInfoPrefix& prefix = kDigestInfoPrefixes[i];
        if (hash.size() == prefix.hash_size) {
            adjusted_hash.insert(adjusted_hash.begin(), prefix.bytes,
                       &prefix.bytes[prefix.size]);
            break;
    }
  }
  return adjusted_hash;
}

std::vector<uint8_t> readBinaryFile(const std::string& file_path) {
    std::ifstream fin(file_path, std::ios::in | std::ios::binary);
    fin >> std::noskipws;
    std::vector<uint8_t> buffer((std::istream_iterator<uint8_t>(fin)),
                                std::istream_iterator<uint8_t>());
    return buffer;
}

::ndk::ScopedAStatus DrmPlugin::openSession(
        ::aidl::android::hardware::drm::SecurityLevel in_securityLevel,
        std::vector<uint8_t>* _aidl_return) {
    UNUSED(in_securityLevel);
    std::vector<uint8_t> sessionId = SessionLibrary::get()->createSession();
    *_aidl_return = sessionId;
    return toNdkScopedAStatus(Status::OK);
}

::ndk::ScopedAStatus DrmPlugin::closeSession(const std::vector<uint8_t>& in_sessionId) {
    if (in_sessionId.size() == 0) {
        return toNdkScopedAStatus(Status::BAD_VALUE);
    }
    SessionLibrary::get()->closeSession(in_sessionId);
    return toNdkScopedAStatus(Status::OK);
}

::ndk::ScopedAStatus DrmPlugin::getPropertyByteArray(const std::string& in_propertyName,
                                                     std::vector<uint8_t>* _aidl_return) {
    Status status = Status::OK;
    std::string name(in_propertyName.c_str());
    std::vector<uint8_t> value;
    if (name == "castcert") {
        std::vector<uint8_t> device_cert = readBinaryFile(CAST_CRT_FILE);
        std::vector<uint8_t> ica = readBinaryFile(CAST_ICA_FILE);
        value = std::move(device_cert);
        value.insert(value.end(), ica.begin(), ica.end());
    } else {
        status = Status::ERROR_DRM_CANNOT_HANDLE;
        ALOGE("Unsupported Property: %s", in_propertyName.c_str());
    }
    *_aidl_return = value;
    return toNdkScopedAStatus(status);
}

::ndk::ScopedAStatus DrmPlugin::signRSA(const std::vector<uint8_t>& in_sessionId,
                                          const std::string& in_algorithm,
                                          const std::vector<uint8_t>& in_message,
                                          const std::vector<uint8_t>& in_wrappedkey,
                                          std::vector<uint8_t>* _aidl_return) {
    if (in_sessionId.size() == 0 || in_message.size() == 0) {
        return toNdkScopedAStatus(Status::BAD_VALUE);
    }
    UNUSED(in_algorithm);
    UNUSED(in_wrappedkey);
    Status status = Status::ERROR_DRM_CANNOT_HANDLE;
    std::vector<uint8_t> signature;
    *_aidl_return = std::vector<uint8_t>();
    void* cast_auth_handle = ::dlopen("libcast_auth.so", RTLD_LAZY);
    if (!cast_auth_handle) {
        ALOGE("Fail to get TA for signing");
        return toNdkScopedAStatus(status);
    }
    typedef bool (*func_type)(const std::vector<uint8_t>&,
                              std::vector<uint8_t>*);
    func_type sign_hash_func =
        reinterpret_cast<func_type>(::dlsym(cast_auth_handle, "SignHash"));
    if (!sign_hash_func) {
        ALOGE("Fail to apply signing method");
        dlclose(cast_auth_handle);
        return toNdkScopedAStatus(status);
    }
    const std::vector<uint8_t> msg = adjustHash(in_message);
    int res = sign_hash_func(msg, &signature);
    dlclose(cast_auth_handle);
    if (res == 0) {
        *_aidl_return = signature;
        return toNdkScopedAStatus(Status::OK);
    }
    ALOGE("Get empty result from TA");
    return toNdkScopedAStatus(status);
}

::ndk::ScopedAStatus DrmPlugin::decrypt(const std::vector<uint8_t>& in_sessionId,
                             const std::vector<uint8_t>& in_keyId,
                             const std::vector<uint8_t>& in_input,
                             const std::vector<uint8_t>& in_iv,
                             std::vector<uint8_t>* _aidl_return) {
    UNUSED(in_sessionId);
    UNUSED(in_keyId);
    UNUSED(in_input);
    UNUSED(in_iv);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::encrypt(const std::vector<uint8_t>& in_sessionId,
                             const std::vector<uint8_t>& in_keyId,
                             const std::vector<uint8_t>& in_input,
                             const std::vector<uint8_t>& in_iv,
                             std::vector<uint8_t>* _aidl_return) {
    UNUSED(in_sessionId);
    UNUSED(in_keyId);
    UNUSED(in_input);
    UNUSED(in_iv);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getHdcpLevels(
    ::aidl::android::hardware::drm::HdcpLevels* _aidl_return) {
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getKeyRequest(
    const std::vector<uint8_t>& in_scope,
    const std::vector<uint8_t>& in_initData,
    const std::string& in_mimeType,
    ::aidl::android::hardware::drm::KeyType in_keyType,
    const std::vector<::aidl::android::hardware::drm::KeyValue>& in_optionalParameters,
    ::aidl::android::hardware::drm::KeyRequest* _aidl_return) {
    UNUSED(in_scope);
    UNUSED(in_initData);
    UNUSED(in_mimeType);
    UNUSED(in_keyType);
    UNUSED(in_optionalParameters);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getLogMessages(
    std::vector<::aidl::android::hardware::drm::LogMessage>* _aidl_return) {
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getMetrics(
    std::vector<::aidl::android::hardware::drm::DrmMetricGroup>* _aidl_return) {
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getNumberOfSessions(
    ::aidl::android::hardware::drm::NumberOfSessions* _aidl_return) {
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getOfflineLicenseKeySetIds(
    std::vector<::aidl::android::hardware::drm::KeySetId>* _aidl_return) {
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getOfflineLicenseState(
    const ::aidl::android::hardware::drm::KeySetId& in_keySetId,
    ::aidl::android::hardware::drm::OfflineLicenseState* _aidl_return) {
    UNUSED(in_keySetId);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getPropertyString(const std::string& in_propertyName,
                                       std::string* _aidl_return) {
    UNUSED(in_propertyName);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getProvisionRequest(
    const std::string& in_certificateType,
    const std::string& in_certificateAuthority,
    ::aidl::android::hardware::drm::ProvisionRequest* _aidl_return) {
    UNUSED(in_certificateType);
    UNUSED(in_certificateAuthority);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getSecureStop(
    const ::aidl::android::hardware::drm::SecureStopId& in_secureStopId,
    ::aidl::android::hardware::drm::SecureStop* _aidl_return) {
    UNUSED(in_secureStopId);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getSecureStopIds(
    std::vector<::aidl::android::hardware::drm::SecureStopId>* _aidl_return) {
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getSecureStops(
    std::vector<::aidl::android::hardware::drm::SecureStop>* _aidl_return) {
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::getSecurityLevel(
    const std::vector<uint8_t>& in_sessionId,
    ::aidl::android::hardware::drm::SecurityLevel* _aidl_return) {
    UNUSED(in_sessionId);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::provideKeyResponse(
    const std::vector<uint8_t>& in_scope,
    const std::vector<uint8_t>& in_response,
    ::aidl::android::hardware::drm::KeySetId* _aidl_return) {
    UNUSED(in_scope);
    UNUSED(in_response);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::provideProvisionResponse(
    const std::vector<uint8_t>& in_response,
    ::aidl::android::hardware::drm::ProvideProvisionResponseResult* _aidl_return) {
    UNUSED(in_response);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::queryKeyStatus(
    const std::vector<uint8_t>& in_sessionId,
    std::vector<::aidl::android::hardware::drm::KeyValue>* _aidl_return) {
    UNUSED(in_sessionId);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::releaseAllSecureStops() {
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::releaseSecureStop(
    const ::aidl::android::hardware::drm::SecureStopId& in_secureStopId) {
    UNUSED(in_secureStopId);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::releaseSecureStops(
    const ::aidl::android::hardware::drm::OpaqueData& in_ssRelease) {
    UNUSED(in_ssRelease);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::removeAllSecureStops() {
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::removeKeys(const std::vector<uint8_t>& in_sessionId) {
    UNUSED(in_sessionId);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::removeOfflineLicense(
    const ::aidl::android::hardware::drm::KeySetId& in_keySetId) {
    UNUSED(in_keySetId);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::removeSecureStop(
    const ::aidl::android::hardware::drm::SecureStopId& in_secureStopId) {
    UNUSED(in_secureStopId);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::requiresSecureDecoder(
    const std::string& in_mime,
    ::aidl::android::hardware::drm::SecurityLevel in_level,
    bool* _aidl_return) {
    UNUSED(in_mime);
    UNUSED(in_level);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::restoreKeys(
    const std::vector<uint8_t>& in_sessionId,
    const ::aidl::android::hardware::drm::KeySetId& in_keySetId) {
    UNUSED(in_sessionId);
    UNUSED(in_keySetId);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::setCipherAlgorithm(const std::vector<uint8_t>& in_sessionId,
                                        const std::string& in_algorithm) {
    UNUSED(in_sessionId);
    UNUSED(in_algorithm);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::setListener(
     const std::shared_ptr<::aidl::android::hardware::drm::IDrmPluginListener>&
         in_listener) {
    UNUSED(in_listener);
    return toNdkScopedAStatus(Status::OK);
}

::ndk::ScopedAStatus DrmPlugin::setMacAlgorithm(const std::vector<uint8_t>& in_sessionId,
                                     const std::string& in_algorithm) {
    UNUSED(in_sessionId);
    UNUSED(in_algorithm);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::setPlaybackId(const std::vector<uint8_t>& in_sessionId,
                                   const std::string& in_playbackId) {
    UNUSED(in_sessionId);
    UNUSED(in_playbackId);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::setPropertyByteArray(const std::string& in_propertyName,
                                          const std::vector<uint8_t>& in_value) {
    UNUSED(in_propertyName);
    UNUSED(in_value);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::setPropertyString(const std::string& in_propertyName,
                                       const std::string& in_value) {
    UNUSED(in_propertyName);
    UNUSED(in_value);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::sign(const std::vector<uint8_t>& in_sessionId,
                          const std::vector<uint8_t>& in_keyId,
                          const std::vector<uint8_t>& in_message,
                          std::vector<uint8_t>* _aidl_return) {
    UNUSED(in_sessionId);
    UNUSED(in_keyId);
    UNUSED(in_message);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}

::ndk::ScopedAStatus DrmPlugin::verify(const std::vector<uint8_t>& in_sessionId,
                            const std::vector<uint8_t>& in_keyId,
                            const std::vector<uint8_t>& in_message,
                            const std::vector<uint8_t>& in_signature,
                            bool* _aidl_return) {
    UNUSED(in_sessionId);
    UNUSED(in_keyId);
    UNUSED(in_message);
    UNUSED(in_signature);
    UNUSED(_aidl_return);
    return toNdkScopedAStatus(Status::ERROR_DRM_CANNOT_HANDLE);
}


}  // namespace castkey
}  // namespace drm
}  // namespace hardware
}  // namespace android
}  // namespace aidl
