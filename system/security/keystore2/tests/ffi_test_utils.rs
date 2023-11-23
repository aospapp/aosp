// Copyright 2022, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use keystore2_test_utils::key_generations::Error;

#[cxx::bridge]
mod ffi {
    struct CxxResult {
        data: Vec<u8>,
        error: i32,
    }

    unsafe extern "C++" {
        include!("ffi_test_utils.hpp");
        fn validateCertChain(cert_buf: Vec<u8>, cert_len: u32, strict_issuer_check: bool) -> bool;
        fn createWrappedKey(
            encrypted_secure_key: Vec<u8>,
            encrypted_transport_key: Vec<u8>,
            iv: Vec<u8>,
            tag: Vec<u8>,
        ) -> CxxResult;
        fn buildAsn1DerEncodedWrappedKeyDescription() -> CxxResult;
    }
}

/// Validate given certificate chain.
pub fn validate_certchain(cert_buf: &[u8]) -> Result<bool, Error> {
    if ffi::validateCertChain(cert_buf.to_vec(), cert_buf.len().try_into().unwrap(), true) {
        return Ok(true);
    }

    Err(Error::ValidateCertChainFailed)
}

fn get_result(result: ffi::CxxResult) -> Result<Vec<u8>, Error> {
    if result.error == 0 && !result.data.is_empty() {
        Ok(result.data)
    } else {
        Err(Error::DerEncodeFailed)
    }
}

/// Creates wrapped key material to import in ASN.1 DER-encoded data corresponding to
/// `SecureKeyWrapper`. See `IKeyMintDevice.aidl` for documentation of the `SecureKeyWrapper`
/// schema.
pub fn create_wrapped_key(
    encrypted_secure_key: &[u8],
    encrypted_transport_key: &[u8],
    iv: &[u8],
    tag: &[u8],
) -> Result<Vec<u8>, Error> {
    get_result(ffi::createWrappedKey(
        encrypted_secure_key.to_vec(),
        encrypted_transport_key.to_vec(),
        iv.to_vec(),
        tag.to_vec(),
    ))
}

/// Creates ASN.1 DER-encoded data corresponding to `KeyDescription` schema.
/// See `IKeyMintDevice.aidl` for documentation of the `KeyDescription` schema.
/// Below mentioned key parameters are used -
///     Algorithm: AES-256
///     Padding: PKCS7
///     Blockmode: ECB
///     Purpose: Encrypt, Decrypt
pub fn create_wrapped_key_additional_auth_data() -> Result<Vec<u8>, Error> {
    get_result(ffi::buildAsn1DerEncodedWrappedKeyDescription())
}
