//! Error mapping functionality.

use bssl_ffi as ffi;
use core::convert::TryFrom;
use kmr_wire::keymint::ErrorCode;
use log::error;

/// Map an OpenSSL `Error` into a KeyMint `ErrorCode` value.
pub(crate) fn map_openssl_err(err: &openssl::error::Error) -> ErrorCode {
    let code = err.code();
    let reason = unsafe {
        // Safety: no pointers involved.
        ffi::ERR_GET_REASON_RUST(code)
    };

    // Global error reasons.
    match reason {
        ffi::ERR_R_MALLOC_FAILURE => return ErrorCode::MemoryAllocationFailed,
        ffi::ERR_R_SHOULD_NOT_HAVE_BEEN_CALLED
        | ffi::ERR_R_PASSED_NULL_PARAMETER
        | ffi::ERR_R_INTERNAL_ERROR
        | ffi::ERR_R_OVERFLOW => return ErrorCode::UnknownError,
        _ => {}
    }

    match ffi::ERR_GET_LIB(code) as u32 {
        ffi::ERR_LIB_USER => ErrorCode::try_from(reason).unwrap_or(ErrorCode::UnknownError),
        ffi::ERR_LIB_EVP => translate_evp_error(reason),
        ffi::ERR_LIB_ASN1 => translate_asn1_error(reason),
        ffi::ERR_LIB_CIPHER => translate_cipher_error(reason),
        ffi::ERR_LIB_PKCS8 => translate_pkcs8_error(reason),
        ffi::ERR_LIB_X509V3 => translate_x509v3_error(reason),
        ffi::ERR_LIB_RSA => translate_rsa_error(reason),
        _ => {
            error!("unknown BoringSSL error code {}", code);
            ErrorCode::UnknownError
        }
    }
}

fn translate_evp_error(reason: i32) -> ErrorCode {
    match reason {
        ffi::EVP_R_UNSUPPORTED_ALGORITHM
        | ffi::EVP_R_OPERATON_NOT_INITIALIZED // NOTYPO: upstream typo
        | ffi::EVP_R_OPERATION_NOT_SUPPORTED_FOR_THIS_KEYTYPE => ErrorCode::UnsupportedAlgorithm,

        ffi::EVP_R_BUFFER_TOO_SMALL
        | ffi::EVP_R_EXPECTING_AN_RSA_KEY
        | ffi::EVP_R_EXPECTING_A_DSA_KEY
        | ffi::EVP_R_MISSING_PARAMETERS => ErrorCode::InvalidKeyBlob,

        ffi::EVP_R_DIFFERENT_PARAMETERS | ffi::EVP_R_DECODE_ERROR => ErrorCode::InvalidArgument,

        ffi::EVP_R_DIFFERENT_KEY_TYPES => ErrorCode::IncompatibleAlgorithm,
        _ => ErrorCode::UnknownError,
    }
}

fn translate_asn1_error(reason: i32) -> ErrorCode {
    match reason {
        ffi::ASN1_R_ENCODE_ERROR => ErrorCode::InvalidArgument,
        _ => ErrorCode::UnknownError,
    }
}

fn translate_cipher_error(reason: i32) -> ErrorCode {
    match reason {
        ffi::CIPHER_R_DATA_NOT_MULTIPLE_OF_BLOCK_LENGTH
        | ffi::CIPHER_R_WRONG_FINAL_BLOCK_LENGTH => ErrorCode::InvalidInputLength,

        ffi::CIPHER_R_UNSUPPORTED_KEY_SIZE | ffi::CIPHER_R_BAD_KEY_LENGTH => {
            ErrorCode::UnsupportedKeySize
        }

        ffi::CIPHER_R_BAD_DECRYPT => ErrorCode::InvalidArgument,

        ffi::CIPHER_R_INVALID_KEY_LENGTH => ErrorCode::InvalidKeyBlob,
        _ => ErrorCode::UnknownError,
    }
}
fn translate_pkcs8_error(reason: i32) -> ErrorCode {
    match reason {
        ffi::PKCS8_R_UNSUPPORTED_PRIVATE_KEY_ALGORITHM | ffi::PKCS8_R_UNKNOWN_CIPHER => {
            ErrorCode::UnsupportedAlgorithm
        }

        ffi::PKCS8_R_PRIVATE_KEY_ENCODE_ERROR | ffi::PKCS8_R_PRIVATE_KEY_DECODE_ERROR => {
            ErrorCode::InvalidKeyBlob
        }

        ffi::PKCS8_R_ENCODE_ERROR => ErrorCode::InvalidArgument,

        _ => ErrorCode::UnknownError,
    }
}
fn translate_x509v3_error(reason: i32) -> ErrorCode {
    match reason {
        ffi::X509V3_R_UNKNOWN_OPTION => ErrorCode::UnsupportedAlgorithm,

        _ => ErrorCode::UnknownError,
    }
}
fn translate_rsa_error(reason: i32) -> ErrorCode {
    match reason {
        ffi::RSA_R_KEY_SIZE_TOO_SMALL => ErrorCode::IncompatiblePaddingMode,
        ffi::RSA_R_DATA_TOO_LARGE_FOR_KEY_SIZE | ffi::RSA_R_DATA_TOO_SMALL_FOR_KEY_SIZE => {
            ErrorCode::InvalidInputLength
        }
        ffi::RSA_R_DATA_TOO_LARGE_FOR_MODULUS | ffi::RSA_R_DATA_TOO_LARGE => {
            ErrorCode::InvalidArgument
        }
        _ => ErrorCode::UnknownError,
    }
}
