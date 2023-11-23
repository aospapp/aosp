#ifndef CASTKEY_TYPES_H_
#define CASTKEY_TYPES_H_

#include <cstdint>
#include <map>
#include <vector>

namespace castkeydrm {

const uint8_t kBlockSize = 16;  // AES_BLOCK_SIZE;
typedef uint8_t KeyId[kBlockSize];
typedef uint8_t Iv[kBlockSize];

typedef std::map<std::vector<uint8_t>, std::vector<uint8_t>> KeyMap;

#define CASTKEY_DISALLOW_COPY_AND_ASSIGN(TypeName)          \
    TypeName(const TypeName&) = delete;                     \
    void operator=(const TypeName&) = delete;

#define CASTKEY_DISALLOW_COPY_AND_ASSIGN_AND_NEW(TypeName)  \
    TypeName() = delete;                                    \
    TypeName(const TypeName&) = delete;                     \
    void operator=(const TypeName&) = delete;

enum CdmResponseType : int32_t {
    OK = 0,
    ERROR_NO_LICENSE = 1,
    ERROR_SESSION_NOT_OPENED = 3,
    ERROR_CANNOT_HANDLE = 4,
    ERROR_INVALID_STATE = 5,
    BAD_VALUE = 6,
    ERROR_DECRYPT = 11,
    ERROR_UNKNOWN = 12,
    ERROR_INSUFFICIENT_SECURITY = 13,
    ERROR_FRAME_TOO_LARGE = 14,
    ERROR_SESSION_LOST_STATE = 15,
    ERROR_RESOURCE_CONTENTION = 16,
};

enum CdmKeyType : int32_t {
    KEY_TYPE_OFFLINE = 0,
    KEY_TYPE_STREAMING = 1,
    KEY_TYPE_RELEASE = 2,
};

}  // namespace castkeydrm

#endif  // CASTKEY_TYPES_H_