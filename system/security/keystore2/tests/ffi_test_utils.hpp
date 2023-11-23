#pragma once

#include "rust/cxx.h"
#include "ffi_test_utils.rs.h"

bool validateCertChain(rust::Vec<rust::u8> cert_buf, uint32_t cert_len, bool strict_issuer_check);
CxxResult createWrappedKey(rust::Vec<rust::u8> encrypted_secure_key,
                              rust::Vec<rust::u8> encrypted_transport_key,
                              rust::Vec<rust::u8> iv,
                              rust::Vec<rust::u8> tag);
CxxResult buildAsn1DerEncodedWrappedKeyDescription();
