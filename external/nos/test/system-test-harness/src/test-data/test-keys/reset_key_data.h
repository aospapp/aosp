#ifndef SRC_TEST_RESET_KEYS_H
#define SRC_TEST_RESET_KEYS_H

#include <stddef.h>
#include <stdint.h>

namespace test_data {

extern const uint8_t kResetKeyPem[];
extern const size_t kResetKeyPemSize;
extern const uint8_t *kResetSignatures[];
extern const size_t kResetSignatureLengths[];
extern const size_t kResetSignatureCount;
}  // namespace test_data


#endif  // SRC_TEST_RESET_KEYS_H
