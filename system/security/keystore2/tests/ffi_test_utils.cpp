#include "ffi_test_utils.hpp"

#include <iostream>

#include <KeyMintAidlTestBase.h>
#include <aidl/android/hardware/security/keymint/ErrorCode.h>
#include <keymaster/UniquePtr.h>

#include <memory>
#include <vector>

#include <hardware/keymaster_defs.h>
#include <keymaster/android_keymaster_utils.h>
#include <keymaster/keymaster_tags.h>

#include <keymaster/km_openssl/attestation_record.h>
#include <keymaster/km_openssl/openssl_err.h>
#include <keymaster/km_openssl/openssl_utils.h>
#include <openssl/asn1t.h>

using aidl::android::hardware::security::keymint::ErrorCode;

#define TAG_SEQUENCE 0x30
#define LENGTH_MASK 0x80
#define LENGTH_VALUE_MASK 0x7F

/**
 * ASN.1 structure for `KeyDescription` Schema.
 * See `IKeyMintDevice.aidl` for documentation of the `KeyDescription` schema.
 *    KeyDescription ::= SEQUENCE(
 *        keyFormat INTEGER,                   # Values from KeyFormat enum.
 *        keyParams AuthorizationList,
 *    )
 */
typedef struct key_description {
    ASN1_INTEGER* key_format;
    keymaster::KM_AUTH_LIST* key_params;
} TEST_KEY_DESCRIPTION;

ASN1_SEQUENCE(TEST_KEY_DESCRIPTION) = {
    ASN1_SIMPLE(TEST_KEY_DESCRIPTION, key_format, ASN1_INTEGER),
    ASN1_SIMPLE(TEST_KEY_DESCRIPTION, key_params, keymaster::KM_AUTH_LIST),
} ASN1_SEQUENCE_END(TEST_KEY_DESCRIPTION);
DECLARE_ASN1_FUNCTIONS(TEST_KEY_DESCRIPTION);

/**
 * ASN.1 structure for `SecureKeyWrapper` Schema.
 * See `IKeyMintDevice.aidl` for documentation of the `SecureKeyWrapper` schema.
 *    SecureKeyWrapper ::= SEQUENCE(
 *        version INTEGER,                     # Contains value 0
 *        encryptedTransportKey OCTET_STRING,
 *        initializationVector OCTET_STRING,
 *        keyDescription KeyDescription,
 *        encryptedKey OCTET_STRING,
 *        tag OCTET_STRING
 *    )
 */
typedef struct secure_key_wrapper {
    ASN1_INTEGER* version;
    ASN1_OCTET_STRING* encrypted_transport_key;
    ASN1_OCTET_STRING* initialization_vector;
    TEST_KEY_DESCRIPTION* key_desc;
    ASN1_OCTET_STRING* encrypted_key;
    ASN1_OCTET_STRING* tag;
} TEST_SECURE_KEY_WRAPPER;

ASN1_SEQUENCE(TEST_SECURE_KEY_WRAPPER) = {
    ASN1_SIMPLE(TEST_SECURE_KEY_WRAPPER, version, ASN1_INTEGER),
    ASN1_SIMPLE(TEST_SECURE_KEY_WRAPPER, encrypted_transport_key, ASN1_OCTET_STRING),
    ASN1_SIMPLE(TEST_SECURE_KEY_WRAPPER, initialization_vector, ASN1_OCTET_STRING),
    ASN1_SIMPLE(TEST_SECURE_KEY_WRAPPER, key_desc, TEST_KEY_DESCRIPTION),
    ASN1_SIMPLE(TEST_SECURE_KEY_WRAPPER, encrypted_key, ASN1_OCTET_STRING),
    ASN1_SIMPLE(TEST_SECURE_KEY_WRAPPER, tag, ASN1_OCTET_STRING),
} ASN1_SEQUENCE_END(TEST_SECURE_KEY_WRAPPER);
DECLARE_ASN1_FUNCTIONS(TEST_SECURE_KEY_WRAPPER);

IMPLEMENT_ASN1_FUNCTIONS(TEST_SECURE_KEY_WRAPPER);
IMPLEMENT_ASN1_FUNCTIONS(TEST_KEY_DESCRIPTION);

struct TEST_KEY_DESCRIPTION_Delete {
    void operator()(TEST_KEY_DESCRIPTION* p) { TEST_KEY_DESCRIPTION_free(p); }
};
struct TEST_SECURE_KEY_WRAPPER_Delete {
    void operator()(TEST_SECURE_KEY_WRAPPER* p) { TEST_SECURE_KEY_WRAPPER_free(p); }
};

/* This function extracts a certificate from the certs_chain_buffer at the given
 * offset. Each DER encoded certificate starts with TAG_SEQUENCE followed by the
 * total length of the certificate. The length of the certificate is determined
 * as per ASN.1 encoding rules for the length octets.
 *
 * @param certs_chain_buffer: buffer containing DER encoded X.509 certificates
 *                            arranged sequentially.
 * @data_size: Length of the DER encoded X.509 certificates buffer.
 * @index: DER encoded X.509 certificates buffer offset.
 * @cert: Encoded certificate to be extracted from buffer as outcome.
 * @return: ErrorCode::OK on success, otherwise ErrorCode::UNKNOWN_ERROR.
 */
ErrorCode
extractCertFromCertChainBuffer(uint8_t* certs_chain_buffer, int certs_chain_buffer_size, int& index,
                               aidl::android::hardware::security::keymint::Certificate& cert) {
    if (index >= certs_chain_buffer_size) {
        return ErrorCode::UNKNOWN_ERROR;
    }

    uint32_t length = 0;
    std::vector<uint8_t> cert_bytes;
    if (certs_chain_buffer[index] == TAG_SEQUENCE) {
        // Short form. One octet. Bit 8 has value "0" and bits 7-1 give the length.
        if (0 == (certs_chain_buffer[index + 1] & LENGTH_MASK)) {
            length = (uint32_t)certs_chain_buffer[index];
            // Add SEQ and Length fields
            length += 2;
        } else {
            // Long form. Two to 127 octets. Bit 8 of first octet has value "1" and
            // bits 7-1 give the number of additional length octets. Second and following
            // octets give the actual length.
            int additionalBytes = certs_chain_buffer[index + 1] & LENGTH_VALUE_MASK;
            if (additionalBytes == 0x01) {
                length = certs_chain_buffer[index + 2];
                // Add SEQ and Length fields
                length += 3;
            } else if (additionalBytes == 0x02) {
                length = (certs_chain_buffer[index + 2] << 8 | certs_chain_buffer[index + 3]);
                // Add SEQ and Length fields
                length += 4;
            } else if (additionalBytes == 0x04) {
                length = certs_chain_buffer[index + 2] << 24;
                length |= certs_chain_buffer[index + 3] << 16;
                length |= certs_chain_buffer[index + 4] << 8;
                length |= certs_chain_buffer[index + 5];
                // Add SEQ and Length fields
                length += 6;
            } else {
                // Length is larger than uint32_t max limit.
                return ErrorCode::UNKNOWN_ERROR;
            }
        }
        cert_bytes.insert(cert_bytes.end(), (certs_chain_buffer + index),
                          (certs_chain_buffer + index + length));
        index += length;

        for (int i = 0; i < cert_bytes.size(); i++) {
            cert.encodedCertificate = std::move(cert_bytes);
        }
    } else {
        // SEQUENCE TAG MISSING.
        return ErrorCode::UNKNOWN_ERROR;
    }

    return ErrorCode::OK;
}

ErrorCode getCertificateChain(
    rust::Vec<rust::u8>& chainBuffer,
    std::vector<aidl::android::hardware::security::keymint::Certificate>& certChain) {
    uint8_t* data = chainBuffer.data();
    int index = 0;
    int data_size = chainBuffer.size();

    while (index < data_size) {
        aidl::android::hardware::security::keymint::Certificate cert =
            aidl::android::hardware::security::keymint::Certificate();
        if (extractCertFromCertChainBuffer(data, data_size, index, cert) != ErrorCode::OK) {
            return ErrorCode::UNKNOWN_ERROR;
        }
        certChain.push_back(std::move(cert));
    }
    return ErrorCode::OK;
}

bool validateCertChain(rust::Vec<rust::u8> cert_buf, uint32_t cert_len, bool strict_issuer_check) {
    std::vector<aidl::android::hardware::security::keymint::Certificate> cert_chain =
        std::vector<aidl::android::hardware::security::keymint::Certificate>();
    if (cert_len <= 0) {
        return false;
    }
    if (getCertificateChain(cert_buf, cert_chain) != ErrorCode::OK) {
        return false;
    }

    for (int i = 0; i < cert_chain.size(); i++) {
        std::cout << cert_chain[i].toString() << "\n";
    }
    auto result = aidl::android::hardware::security::keymint::test::ChainSignaturesAreValid(
        cert_chain, strict_issuer_check);

    if (result == testing::AssertionSuccess()) return true;

    return false;
}

/**
 * Below mentioned key parameters are used to create authorization list of
 * secure key.
 *    Algorithm: AES-256
 *    Padding: PKCS7
 *    Blockmode: ECB
 *    Purpose: Encrypt, Decrypt
 */
keymaster::AuthorizationSet build_wrapped_key_auth_list() {
    return keymaster::AuthorizationSet(keymaster::AuthorizationSetBuilder()
                                           .AesEncryptionKey(256)
                                           .Authorization(keymaster::TAG_BLOCK_MODE, KM_MODE_ECB)
                                           .Authorization(keymaster::TAG_PADDING, KM_PAD_PKCS7)
                                           .Authorization(keymaster::TAG_NO_AUTH_REQUIRED));
}

/**
 * Creates ASN.1 DER-encoded data corresponding to `KeyDescription` schema as
 * AAD. See `IKeyMintDevice.aidl` for documentation of the `KeyDescription` schema.
 */
CxxResult buildAsn1DerEncodedWrappedKeyDescription() {
    CxxResult cxx_result{};
    keymaster_error_t error;
    cxx_result.error = KM_ERROR_OK;

    keymaster::UniquePtr<TEST_KEY_DESCRIPTION, TEST_KEY_DESCRIPTION_Delete> key_description(
        TEST_KEY_DESCRIPTION_new());
    if (!key_description.get()) {
        cxx_result.error = KM_ERROR_MEMORY_ALLOCATION_FAILED;
        return cxx_result;
    }

    // Fill secure key authorizations.
    keymaster::AuthorizationSet auth_list = build_wrapped_key_auth_list();
    error = build_auth_list(auth_list, key_description->key_params);
    if (error != KM_ERROR_OK) {
        cxx_result.error = error;
        return cxx_result;
    }

    // Fill secure key format.
    if (!ASN1_INTEGER_set(key_description->key_format, KM_KEY_FORMAT_RAW)) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }

    // Perform ASN.1 DER encoding of KeyDescription.
    int asn1_data_len = i2d_TEST_KEY_DESCRIPTION(key_description.get(), nullptr);
    if (asn1_data_len < 0) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }
    std::vector<uint8_t> asn1_data(asn1_data_len, 0);

    if (!asn1_data.data()) {
        cxx_result.error = KM_ERROR_MEMORY_ALLOCATION_FAILED;
        return cxx_result;
    }

    uint8_t* p = asn1_data.data();
    asn1_data_len = i2d_TEST_KEY_DESCRIPTION(key_description.get(), &p);
    if (asn1_data_len < 0) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }

    std::move(asn1_data.begin(), asn1_data.end(), std::back_inserter(cxx_result.data));

    return cxx_result;
}

/**
 * Creates wrapped key material to import in ASN.1 DER-encoded data corresponding to
 * `SecureKeyWrapper` schema. See `IKeyMintDevice.aidl` for documentation of the `SecureKeyWrapper`
 * schema.
 */
CxxResult createWrappedKey(rust::Vec<rust::u8> encrypted_secure_key,
                           rust::Vec<rust::u8> encrypted_transport_key, rust::Vec<rust::u8> iv,
                           rust::Vec<rust::u8> tag) {
    CxxResult cxx_result{};
    keymaster_error_t error;
    cxx_result.error = KM_ERROR_OK;

    uint8_t* enc_secure_key_data = encrypted_secure_key.data();
    int enc_secure_key_size = encrypted_secure_key.size();

    uint8_t* iv_data = iv.data();
    int iv_size = iv.size();

    uint8_t* tag_data = tag.data();
    int tag_size = tag.size();

    uint8_t* enc_transport_key_data = encrypted_transport_key.data();
    int enc_transport_key_size = encrypted_transport_key.size();

    keymaster::UniquePtr<TEST_SECURE_KEY_WRAPPER, TEST_SECURE_KEY_WRAPPER_Delete> sec_key_wrapper(
        TEST_SECURE_KEY_WRAPPER_new());
    if (!sec_key_wrapper.get()) {
        cxx_result.error = KM_ERROR_MEMORY_ALLOCATION_FAILED;
        return cxx_result;
    }

    // Fill version = 0
    if (!ASN1_INTEGER_set(sec_key_wrapper->version, 0)) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }

    // Fill encrypted transport key.
    if (enc_transport_key_size &&
        !ASN1_OCTET_STRING_set(sec_key_wrapper->encrypted_transport_key, enc_transport_key_data,
                               enc_transport_key_size)) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }

    // Fill encrypted secure key.
    if (enc_secure_key_size && !ASN1_OCTET_STRING_set(sec_key_wrapper->encrypted_key,
                                                      enc_secure_key_data, enc_secure_key_size)) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }

    // Fill secure key authorization list.
    keymaster::AuthorizationSet auth_list = build_wrapped_key_auth_list();
    error = build_auth_list(auth_list, sec_key_wrapper->key_desc->key_params);
    if (error != KM_ERROR_OK) {
        cxx_result.error = error;
        return cxx_result;
    }

    // Fill secure key format.
    if (!ASN1_INTEGER_set(sec_key_wrapper->key_desc->key_format, KM_KEY_FORMAT_RAW)) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }

    // Fill initialization vector used for encrypting secure key.
    if (iv_size &&
        !ASN1_OCTET_STRING_set(sec_key_wrapper->initialization_vector, iv_data, iv_size)) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }

    // Fill GCM-tag, extracted during secure key encryption.
    if (tag_size && !ASN1_OCTET_STRING_set(sec_key_wrapper->tag, tag_data, tag_size)) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }

    // ASN.1 DER-encoding of secure key wrapper.
    int asn1_data_len = i2d_TEST_SECURE_KEY_WRAPPER(sec_key_wrapper.get(), nullptr);
    if (asn1_data_len < 0) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }
    std::vector<uint8_t> asn1_data(asn1_data_len, 0);

    if (!asn1_data.data()) {
        cxx_result.error = KM_ERROR_MEMORY_ALLOCATION_FAILED;
        return cxx_result;
    }

    uint8_t* p = asn1_data.data();
    asn1_data_len = i2d_TEST_SECURE_KEY_WRAPPER(sec_key_wrapper.get(), &p);
    if (asn1_data_len < 0) {
        cxx_result.error = keymaster::TranslateLastOpenSslError();
        return cxx_result;
    }

    std::move(asn1_data.begin(), asn1_data.end(), std::back_inserter(cxx_result.data));

    return cxx_result;
}
