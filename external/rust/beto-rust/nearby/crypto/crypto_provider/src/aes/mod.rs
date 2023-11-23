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

//! An abstraction layer around AES implementations.
//!
//! The design is an attempt to make it easy to provide implementations that are both idiomatic
//! Rust (e.g. RustCrypto) as well as FFI-backed (e.g. openssl and other C impls).
#![forbid(unsafe_code)]
#![deny(missing_docs)]

use core::{array, fmt};

pub mod ctr;

#[cfg(feature = "alloc")]
pub mod cbc;
#[cfg(feature = "gcm_siv")]
pub mod gcm_siv;

/// Block size in bytes for AES (and XTS-AES)
pub const BLOCK_SIZE: usize = 16;

/// A single AES block.
pub type AesBlock = [u8; BLOCK_SIZE];

/// Helper trait to enforce encryption and decryption with the same size key
pub trait Aes {
    /// The AES key containing the raw bytes used to for key scheduling
    type Key: AesKey;

    /// The cipher used for encryption
    type EncryptCipher: AesEncryptCipher<Key = Self::Key>;

    /// the cipher used for decryption
    type DecryptCipher: AesDecryptCipher<Key = Self::Key>;
}

/// The base AesCipher trait which describes common operations to both encryption and decryption ciphers
pub trait AesCipher {
    /// The type of the key used which holds the raw bytes used in key scheduling
    type Key: AesKey;

    /// Creates a new cipher from the AesKey
    fn new(key: &Self::Key) -> Self;
}

/// An AES cipher used for encrypting blocks
pub trait AesEncryptCipher: AesCipher {
    /// Encrypt `block` in place.
    fn encrypt(&self, block: &mut AesBlock);
}

/// An AES cipher used for decrypting blocks
pub trait AesDecryptCipher: AesCipher {
    /// Decrypt `block` in place.
    fn decrypt(&self, block: &mut AesBlock);
}

/// An appropriately sized `[u8; N]` array that the key can be constructed from, e.g. `[u8; 16]`
/// for AES-128.
pub trait AesKey: for<'a> TryFrom<&'a [u8], Error = Self::TryFromError> {
    /// The error used by the `TryFrom` implementation used to construct `Self::Array` from a
    /// slice. For the typical case of `Self::Array` being an `[u8; N]`, this would be
    /// `core::array::TryFromSliceError`.
    ///
    /// This is broken out as a separate type to allow the `fmt::Debug` requirement needed for
    /// `expect()`.
    type TryFromError: fmt::Debug;

    /// The byte array type the key can be represented with
    type Array;

    /// Key size in bytes -- must match the length of `Self::KeyBytes`.`
    ///
    /// Unfortunately `KeyBytes` can't reference this const in the type declaration, so it must be
    /// specified separately.
    const KEY_SIZE: usize;

    /// Returns the key material as a slice
    fn as_slice(&self) -> &[u8];

    /// Returns the key material as an array
    fn as_array(&self) -> &Self::Array;
}

/// An AES-128 key.
#[derive(Clone)]
pub struct Aes128Key {
    key: [u8; 16],
}

impl AesKey for Aes128Key {
    type TryFromError = array::TryFromSliceError;
    type Array = [u8; 16];
    const KEY_SIZE: usize = 16;

    fn as_slice(&self) -> &[u8] {
        &self.key
    }

    fn as_array(&self) -> &Self::Array {
        &self.key
    }
}

impl TryFrom<&[u8]> for Aes128Key {
    type Error = array::TryFromSliceError;

    fn try_from(value: &[u8]) -> Result<Self, Self::Error> {
        value.try_into().map(|arr| Self { key: arr })
    }
}

impl From<[u8; 16]> for Aes128Key {
    fn from(arr: [u8; 16]) -> Self {
        Self { key: arr }
    }
}

/// An AES-256 key.
#[derive(Clone)]
pub struct Aes256Key {
    key: [u8; 32],
}

impl AesKey for Aes256Key {
    type TryFromError = array::TryFromSliceError;
    type Array = [u8; 32];
    const KEY_SIZE: usize = 32;

    fn as_slice(&self) -> &[u8] {
        &self.key
    }

    fn as_array(&self) -> &Self::Array {
        &self.key
    }
}

impl TryFrom<&[u8]> for Aes256Key {
    type Error = array::TryFromSliceError;

    fn try_from(value: &[u8]) -> Result<Self, Self::Error> {
        value.try_into().map(|arr| Self { key: arr })
    }
}

impl From<[u8; 32]> for Aes256Key {
    fn from(arr: [u8; 32]) -> Self {
        Self { key: arr }
    }
}

/// Module for testing implementations of this crate.
#[cfg(feature = "testing")]
pub mod testing {
    use super::*;
    pub use crate::testing::prelude::*;
    use core::marker;
    use hex_literal::hex;
    use rstest_reuse::template;

    /// Test encryption with AES-128
    pub fn aes_128_test_encrypt<A: AesEncryptCipher<Key = Aes128Key>>(
        _marker: marker::PhantomData<A>,
    ) {
        // https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38a.pdf F.1.1
        let key: Aes128Key = hex!("2b7e151628aed2a6abf7158809cf4f3c").into();
        let mut block = [0_u8; 16];
        let enc_cipher = A::new(&key);

        block.copy_from_slice(&hex!("6bc1bee22e409f96e93d7e117393172a"));
        enc_cipher.encrypt(&mut block);
        assert_eq!(hex!("3ad77bb40d7a3660a89ecaf32466ef97"), block);

        block.copy_from_slice(&hex!("ae2d8a571e03ac9c9eb76fac45af8e51"));
        enc_cipher.encrypt(&mut block);
        assert_eq!(hex!("f5d3d58503b9699de785895a96fdbaaf"), block);

        block.copy_from_slice(&hex!("30c81c46a35ce411e5fbc1191a0a52ef"));
        enc_cipher.encrypt(&mut block);
        assert_eq!(hex!("43b1cd7f598ece23881b00e3ed030688"), block);

        block.copy_from_slice(&hex!("f69f2445df4f9b17ad2b417be66c3710"));
        enc_cipher.encrypt(&mut block);
        assert_eq!(hex!("7b0c785e27e8ad3f8223207104725dd4"), block);
    }

    /// Test decryption with AES-128
    pub fn aes_128_test_decrypt<A: AesDecryptCipher<Key = Aes128Key>>(
        _marker: marker::PhantomData<A>,
    ) {
        // https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38a.pdf F.1.2
        let key: Aes128Key = hex!("2b7e151628aed2a6abf7158809cf4f3c").into();
        let mut block = [0_u8; 16];
        let dec_cipher = A::new(&key);

        block.copy_from_slice(&hex!("3ad77bb40d7a3660a89ecaf32466ef97"));
        dec_cipher.decrypt(&mut block);
        assert_eq!(hex!("6bc1bee22e409f96e93d7e117393172a"), block);

        block.copy_from_slice(&hex!("f5d3d58503b9699de785895a96fdbaaf"));
        dec_cipher.decrypt(&mut block);
        assert_eq!(hex!("ae2d8a571e03ac9c9eb76fac45af8e51"), block);

        block.copy_from_slice(&hex!("43b1cd7f598ece23881b00e3ed030688"));
        dec_cipher.decrypt(&mut block);
        assert_eq!(hex!("30c81c46a35ce411e5fbc1191a0a52ef"), block);

        block.copy_from_slice(&hex!("7b0c785e27e8ad3f8223207104725dd4"));
        dec_cipher.decrypt(&mut block);
        assert_eq!(hex!("f69f2445df4f9b17ad2b417be66c3710"), block);
    }

    /// Test encryption with AES-256
    pub fn aes_256_test_encrypt<A: AesEncryptCipher<Key = Aes256Key>>(
        _marker: marker::PhantomData<A>,
    ) {
        // https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38a.pdf F.1.5
        let key: Aes256Key =
            hex!("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4").into();
        let mut block: [u8; 16];
        let enc_cipher = A::new(&key);

        block = hex!("6bc1bee22e409f96e93d7e117393172a");
        enc_cipher.encrypt(&mut block);
        assert_eq!(hex!("f3eed1bdb5d2a03c064b5a7e3db181f8"), block);

        block = hex!("ae2d8a571e03ac9c9eb76fac45af8e51");
        enc_cipher.encrypt(&mut block);
        assert_eq!(hex!("591ccb10d410ed26dc5ba74a31362870"), block);

        block = hex!("30c81c46a35ce411e5fbc1191a0a52ef");
        enc_cipher.encrypt(&mut block);
        assert_eq!(hex!("b6ed21b99ca6f4f9f153e7b1beafed1d"), block);

        block = hex!("f69f2445df4f9b17ad2b417be66c3710");
        enc_cipher.encrypt(&mut block);
        assert_eq!(hex!("23304b7a39f9f3ff067d8d8f9e24ecc7"), block);
    }

    /// Test decryption with AES-256
    pub fn aes_256_test_decrypt<A: AesDecryptCipher<Key = Aes256Key>>(
        _marker: marker::PhantomData<A>,
    ) {
        // https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38a.pdf F.1.6
        let key: Aes256Key =
            hex!("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4").into();
        let mut block: [u8; 16];
        let dec_cipher = A::new(&key);

        block = hex!("f3eed1bdb5d2a03c064b5a7e3db181f8");
        dec_cipher.decrypt(&mut block);
        assert_eq!(hex!("6bc1bee22e409f96e93d7e117393172a"), block);

        block = hex!("591ccb10d410ed26dc5ba74a31362870");
        dec_cipher.decrypt(&mut block);
        assert_eq!(hex!("ae2d8a571e03ac9c9eb76fac45af8e51"), block);

        block = hex!("b6ed21b99ca6f4f9f153e7b1beafed1d");
        dec_cipher.decrypt(&mut block);
        assert_eq!(hex!("30c81c46a35ce411e5fbc1191a0a52ef"), block);

        block = hex!("23304b7a39f9f3ff067d8d8f9e24ecc7");
        dec_cipher.decrypt(&mut block);
        assert_eq!(hex!("f69f2445df4f9b17ad2b417be66c3710"), block);
    }

    /// Generates the test cases to validate the AES-128 implementation.
    /// For example, to test `MyAes128Impl`:
    ///
    /// ```
    /// use crypto_provider::aes::testing::*;
    ///
    /// mod tests {
    ///     #[apply(aes_128_encrypt_test_cases)]
    ///     fn aes_128_tests(f: CryptoProviderTestCase<MyAes128Impl>) {
    ///         f(MyAes128Impl);
    ///     }
    /// }
    /// ```
    #[template]
    #[export]
    #[rstest]
    #[case::encrypt(aes_128_test_encrypt)]
    fn aes_128_encrypt_test_cases<A: AesFactory<Key = Aes128Key>>(
        #[case] testcase: CryptoProviderTestCase<F>,
    ) {
    }

    /// Generates the test cases to validate the AES-128 implementation.
    /// For example, to test `MyAes128Impl`:
    ///
    /// ```
    /// use crypto_provider::aes::testing::*;
    ///
    /// mod tests {
    ///     #[apply(aes_128_decrypt_test_cases)]
    ///     fn aes_128_tests(f: CryptoProviderTestCase<MyAes128Impl>) {
    ///         f(MyAes128Impl);
    ///     }
    /// }
    /// ```
    #[template]
    #[export]
    #[rstest]
    #[case::decrypt(aes_128_test_decrypt)]
    fn aes_128_decrypt_test_cases<F: AesFactory<Key = Aes128Key>>(
        #[case] testcase: CryptoProviderTestCase<F>,
    ) {
    }

    /// Generates the test cases to validate the AES-256 implementation.
    /// For example, to test `MyAes256Impl`:
    ///
    /// ```
    /// use crypto_provider::aes::testing::*;
    ///
    /// mod tests {
    ///     #[apply(aes_256_encrypt_test_cases)]
    ///     fn aes_256_tests(f: CryptoProviderTestCase<MyAes256Impl>) {
    ///         f(MyAes256Impl);
    ///     }
    /// }
    /// ```
    #[template]
    #[export]
    #[rstest]
    #[case::encrypt(aes_256_test_encrypt)]
    fn aes_256_encrypt_test_cases<F: AesFactory<Key = Aes256Key>>(
        #[case] testcase: CryptoProviderTestCase<F>,
    ) {
    }

    /// Generates the test cases to validate the AES-256 implementation.
    /// For example, to test `MyAes256Impl`:
    ///
    /// ```
    /// use crypto_provider::aes::testing::*;
    ///
    /// mod tests {
    ///     #[apply(aes_256_decrypt_test_cases)]
    ///     fn aes_256_tests(f: CryptoProviderTestCase<MyAes256Impl>) {
    ///         f(MyAes256Impl);
    ///     }
    /// }
    /// ```
    #[template]
    #[export]
    #[rstest]
    #[case::decrypt(aes_256_test_decrypt)]
    fn aes_256_decrypt_test_cases<F: AesFactory<Key = Aes256Key>>(
        #[case] testcase: CryptoProviderTestCase<F>,
    ) {
    }
}
