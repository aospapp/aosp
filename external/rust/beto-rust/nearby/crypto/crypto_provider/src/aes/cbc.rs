// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Traits for AES-CBC 256 with PKCS7 padding.

extern crate alloc;
use alloc::vec::Vec;

use super::Aes256Key;

/// Type of the initialization vector for AES-CBC
pub type AesCbcIv = [u8; 16];

/// Trait for implementing AES-CBC with PKCS7 padding.
pub trait AesCbcPkcs7Padded {
    /// Encrypt message using `key` and `iv`, returning a ciphertext.
    fn encrypt(key: &Aes256Key, iv: &AesCbcIv, message: &[u8]) -> Vec<u8>;
    /// Decrypt ciphertext using `key` and `iv`, returning the original message if `Ok()` otherwise
    /// a `DecryptionError` indicating the type of error that occurred while decrypting.
    fn decrypt(
        key: &Aes256Key,
        iv: &AesCbcIv,
        ciphertext: &[u8],
    ) -> Result<Vec<u8>, DecryptionError>;
}

/// Error type for describing what went wrong decrypting a ciphertext.
#[derive(Debug, PartialEq, Eq)]
pub enum DecryptionError {
    /// Invalid padding, the input ciphertext does not have valid PKCS7 padding. If you get this
    /// error, check the encryption side generating this data to make sure it is adding the padding
    /// correctly. Exposing padding errors can cause a padding oracle vulnerability.
    BadPadding,
}

/// Module for testing implementations of this crate.
#[cfg(feature = "testing")]
pub mod testing {
    use super::{Aes256Key, AesCbcIv, AesCbcPkcs7Padded};
    pub use crate::testing::prelude::*;
    use core::marker::PhantomData;
    use hex_literal::hex;
    use rstest_reuse::template;

    /// Tests for AES-256-CBC encryption
    pub fn aes_256_cbc_test_encrypt<A: AesCbcPkcs7Padded>(_marker: PhantomData<A>) {
        // http://google3/third_party/wycheproof/testvectors/aes_cbc_pkcs5_test.json;l=1492;rcl=264817632
        let key: Aes256Key =
            hex!("665a02bc265a66d01775091da56726b6668bfd903cb7af66fb1b78a8a062e43c").into();
        let iv: AesCbcIv = hex!("3fb0d5ecd06c71150748b599595833cb");
        let msg = hex!("3f56935def3f");
        let expected_ciphertext = hex!("3f3f39697bd7e88d85a14132be1cbc48");
        assert_eq!(A::encrypt(&key, &iv, &msg), expected_ciphertext);
    }

    /// Tests for AES-256-CBC decryption
    pub fn aes_256_cbc_test_decrypt<A: AesCbcPkcs7Padded>(_marker: PhantomData<A>) {
        // http://google3/third_party/wycheproof/testvectors/aes_cbc_pkcs5_test.json;l=1492;rcl=264817632
        let key: Aes256Key =
            hex!("665a02bc265a66d01775091da56726b6668bfd903cb7af66fb1b78a8a062e43c").into();
        let iv: AesCbcIv = hex!("3fb0d5ecd06c71150748b599595833cb");
        let ciphertext = hex!("3f3f39697bd7e88d85a14132be1cbc48");
        let expected_msg = hex!("3f56935def3f");
        assert_eq!(A::decrypt(&key, &iv, &ciphertext).unwrap(), expected_msg);
    }

    /// Generates the test cases to validate the AES-256-CBC implementation.
    /// For example, to test `MyAesCbc256Impl`:
    ///
    /// ```
    /// use crypto_provider::aes::cbc::testing::*;
    ///
    /// mod tests {
    ///     #[apply(aes_256_cbc_test_cases)]
    ///     fn aes_256_cbc_tests(
    ///             testcase: CryptoProviderTestCases<PhantomData<MyAesCbc256Impl>>) {
    ///         testcase(PhantomData);
    ///     }
    /// }
    /// ```
    #[template]
    #[export]
    #[rstest]
    #[case::encrypt(aes_256_cbc_test_encrypt)]
    #[case::decrypt(aes_256_cbc_test_decrypt)]
    fn aes_256_cbc_test_cases<A: AesCbcPkcs7Padded>(#[case] testcase: CryptoProviderTestCases<F>) {}
}
