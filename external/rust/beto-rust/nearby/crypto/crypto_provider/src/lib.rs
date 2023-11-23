// Copyright 2023 Google LLC
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
// limitations under the License.'
#![no_std]
#![forbid(unsafe_code)]
#![deny(missing_docs)]

//! Crypto abstraction trait only crate, which provides traits for cryptographic primitives

use core::fmt::Debug;

/// mod containing hmac trait
pub mod hkdf;

/// mod containing hkdf trait
pub mod hmac;

/// mod containing X25519 trait
pub mod x25519;

/// mod containing traits for NIST-P256 elliptic curve implementation.
pub mod p256;

/// mod containing traits for elliptic curve cryptography.
pub mod elliptic_curve;

/// mod containing SHA256 trait.
pub mod sha2;

/// mod containing aes trait
pub mod aes;

/// mod containing traits for ed25519 key generation, signing, and verification
pub mod ed25519;

/// Uber crypto trait which defines the traits for all crypto primitives as associated types
pub trait CryptoProvider: Clone + Debug + PartialEq + Eq + Send {
    /// The Hkdf type which implements the hkdf trait
    type HkdfSha256: hkdf::Hkdf;
    /// The Hmac type which implements the hmac trait
    type HmacSha256: hmac::Hmac<32>;
    /// The Hkdf type which implements the hkdf trait
    type HkdfSha512: hkdf::Hkdf;
    /// The Hmac type which implements the hmac trait
    type HmacSha512: hmac::Hmac<64>;
    /// The AES-CBC-PKCS7 implementation to use
    type AesCbcPkcs7Padded: aes::cbc::AesCbcPkcs7Padded;
    /// The X25519 implementation to use for ECDH.
    type X25519: elliptic_curve::EcdhProvider<x25519::X25519>;
    /// The P256 implementation to use for ECDH.
    type P256: p256::P256EcdhProvider;
    /// The SHA256 hash implementation.
    type Sha256: sha2::Sha256;
    /// The SHA512 hash implementation.
    type Sha512: sha2::Sha512;
    /// Plain AES-128 implementation (without block cipher mode).
    type Aes128: aes::Aes<Key = Aes128Key>;
    /// Plain AES-256 implementation (without block cipher mode).
    type Aes256: aes::Aes<Key = Aes256Key>;
    /// AES-128 with CTR block mode
    type AesCtr128: aes::ctr::AesCtr<Key = aes::Aes128Key>;
    /// AES-256 with CTR block mode
    type AesCtr256: aes::ctr::AesCtr<Key = aes::Aes256Key>;
    /// The trait defining ed25519, a Edwards-curve Digital Signature Algorithm signature scheme
    /// using SHA-512 (SHA-2) and Curve25519
    type Ed25519: ed25519::Ed25519Provider;

    /// The cryptographically secure random number generator
    type CryptoRng: CryptoRng;

    /// Compares the two given slices, in constant time, and returns true if they are equal.
    fn constant_time_eq(a: &[u8], b: &[u8]) -> bool;
}

/// Wrapper to a cryptographically secure pseudo random number generator
pub trait CryptoRng {
    /// Returns an instance of the rng
    fn new() -> Self;

    /// Return the next random u64
    fn next_u64(&mut self) -> u64;

    /// Fill dest with random data
    fn fill(&mut self, dest: &mut [u8]);

    /// Generate a random byte
    fn gen<U8>(&mut self) -> u8 {
        let mut arr = [0u8; 1];
        self.fill(&mut arr);
        arr[0]
    }
}

/// If impls want to opt out of passing a Rng they can simply use `()` for the Rng associated type
impl CryptoRng for () {
    fn new() -> Self {}

    fn next_u64(&mut self) -> u64 {
        unimplemented!()
    }

    fn fill(&mut self, _dest: &mut [u8]) {
        unimplemented!()
    }
}

use crate::aes::{Aes128Key, Aes256Key};
#[cfg(feature = "testing")]
pub use rstest_reuse;

/// Utilities for testing implementations of this crate.
#[cfg(feature = "testing")]
pub mod testing {
    extern crate alloc;
    use crate::CryptoProvider;
    use alloc::{format, string::String};
    use core::marker::PhantomData;
    use hex_literal::hex;
    use rand::{Rng, RngCore};
    use rstest_reuse::template;

    /// Common items that needs to be imported to use these test cases
    pub mod prelude {
        pub use super::CryptoProviderTestCase;
        pub use rstest::rstest;
        pub use rstest_reuse;
        pub use rstest_reuse::apply;
    }

    /// A test case for Crypto Provider. A test case is a function that panics if the test fails.
    pub type CryptoProviderTestCase<T> = fn(PhantomData<T>);

    #[derive(Debug)]
    pub(crate) struct TestError(String);

    impl TestError {
        pub(crate) fn new<D: core::fmt::Debug>(value: D) -> Self {
            Self(format!("{value:?}"))
        }
    }

    /// Test for `constant_time_eq` when the two inputs are equal.
    pub fn constant_time_eq_test_equal<C: CryptoProvider>(_marker: PhantomData<C>) {
        assert!(C::constant_time_eq(
            &hex!("00010203040506070809"),
            &hex!("00010203040506070809")
        ));
    }

    /// Test for `constant_time_eq` when the two inputs are not equal.
    pub fn constant_time_eq_test_not_equal<C: CryptoProvider>(_marker: PhantomData<C>) {
        assert!(!C::constant_time_eq(
            &hex!("00010203040506070809"),
            &hex!("00000000000000000000")
        ));
    }

    /// Random tests for `constant_time_eq`.
    pub fn constant_time_eq_random_test<C: CryptoProvider>(_marker: PhantomData<C>) {
        let mut rng = rand::thread_rng();
        for _ in 1..100 {
            // Test using "oracle" of ==, with possibly different lengths for a and b
            let mut a = alloc::vec![0; rng.gen_range(1..1000)];
            rng.fill_bytes(&mut a);
            let mut b = alloc::vec![0; rng.gen_range(1..1000)];
            rng.fill_bytes(&mut b);
            assert_eq!(C::constant_time_eq(&a, &b), a == b);
        }

        for _ in 1..10000 {
            // Test using "oracle" of ==, with same lengths for a and b
            let len = rng.gen_range(1..1000);
            let mut a = alloc::vec![0; len];
            rng.fill_bytes(&mut a);
            let mut b = alloc::vec![0; len];
            rng.fill_bytes(&mut b);
            assert_eq!(C::constant_time_eq(&a, &b), a == b);
        }

        for _ in 1..10000 {
            // Clones and the original should always be equal
            let mut a = alloc::vec![0; rng.gen_range(1..1000)];
            rng.fill_bytes(&mut a);
            assert!(C::constant_time_eq(&a, &a.clone()));
        }
    }

    /// Generates the test cases to validate the P256 implementation.
    /// For example, to test `MyCryptoProvider`:
    ///
    /// ```
    /// use crypto_provider::p256::testing::*;
    ///
    /// mod tests {
    ///     #[apply(constant_time_eq_test_cases)]
    ///     fn constant_time_eq_tests(
    ///             testcase: CryptoProviderTestCase<MyCryptoProvider>) {
    ///         testcase(PhantomData);
    ///     }
    /// }
    /// ```
    #[template]
    #[export]
    #[rstest]
    #[case::constant_time_eq_test_not_equal(constant_time_eq_test_not_equal)]
    #[case::constant_time_eq_test_equal(constant_time_eq_test_equal)]
    #[case::constant_time_eq_random_test(constant_time_eq_random_test)]
    fn constant_time_eq_test_cases<C: CryptoProvider>(#[case] testcase: CryptoProviderTestCase<C>) {
    }
}
