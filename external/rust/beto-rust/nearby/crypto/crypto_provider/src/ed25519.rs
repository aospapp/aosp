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
// limitations under the License.

use core::fmt::Debug;

/// Collection of types used to provide an implementation of ed25519, the Edwards-curve Digital
/// Signature Algorithm scheme using sha-512 (sha2) and Curve25519
pub trait Ed25519Provider {
    /// The keypair which includes both public and secret halves of an asymmetric key.
    type KeyPair: KeyPair<PublicKey = Self::PublicKey, Signature = Self::Signature>;
    /// The ed25519 public key, used when verifying a message
    type PublicKey: PublicKey<Signature = Self::Signature>;
    /// The ed25519 signature which is the result of signing a message
    type Signature: Signature;
}

/// The length of a ed25519 `Signature`, in bytes.
pub const SIGNATURE_LENGTH: usize = 64;

/// The length of an ed25519 `KeyPair`, in bytes.
pub const KEY_PAIR_LENGTH: usize = 64;

/// The length of an ed25519 `PublicKey`, in bytes.
pub const KEY_LENGTH: usize = 32;

/// The keypair which includes both public and secret halves of an asymmetric key.
pub trait KeyPair: Sized {
    /// The ed25519 public key, used when verifying a message
    type PublicKey: PublicKey;

    /// The ed25519 signature returned when signing a message
    type Signature: Signature;

    /// Converts the key-pair to an array of bytes consisting
    /// of the bytes of the private key followed by the bytes
    /// of the public key. This method should only ever be called
    /// by code which securely stores private credentials.
    fn to_bytes(&self) -> [u8; KEY_PAIR_LENGTH];

    /// Builds this key-pair from an array of bytes in the
    /// format yielded by `to_bytes`. This method should
    /// only ever be called by code which securely stores private
    /// credentials.
    fn from_bytes(bytes: [u8; KEY_PAIR_LENGTH]) -> Result<Self, InvalidBytes>
    where
        Self: Sized;

    /// Sign the given message and return a digital signature
    fn sign(&self, msg: &[u8]) -> Self::Signature;

    /// Generate an ed25519 keypair from a CSPRNG
    /// generate is not available in `no-std`
    #[cfg(feature = "std")]
    fn generate() -> Self;

    /// getter function for the Public Key of the key pair
    fn public(&self) -> Self::PublicKey;
}

/// An ed25519 signature
pub trait Signature: Sized {
    /// Create a new signature from a byte slice, and return an error on an invalid signature
    /// An `Ok` result does not guarantee that the Signature is valid, however it will catch a
    /// number of invalid signatures relatively inexpensively.
    fn from_bytes(bytes: &[u8]) -> Result<Self, InvalidSignature>;

    /// Returns a slice of the signature bytes
    fn to_bytes(&self) -> [u8; SIGNATURE_LENGTH];
}

/// An ed25519 public key
pub trait PublicKey {
    /// the signature type being used by verify
    type Signature: Signature;

    /// Builds this public key from an array of bytes in
    /// the format yielded by `to_bytes`.
    fn from_bytes(bytes: [u8; KEY_LENGTH]) -> Result<Self, InvalidBytes>
    where
        Self: Sized;

    /// Yields the bytes of the public key
    fn to_bytes(&self) -> [u8; KEY_LENGTH];

    /// Succeeds if the signature was a valid signature created by this Keypair on the prehashed_message.
    fn verify_strict(
        &self,
        message: &[u8],
        signature: &Self::Signature,
    ) -> Result<(), SignatureError>;
}

/// error returned when bad bytes are provided to generate keypair
#[derive(Debug)]
pub struct InvalidBytes;

/// Error returned if the verification on the signature + message fails
#[derive(Debug)]
pub struct SignatureError;

/// Error returned if invalid signature bytes are provided
#[derive(Debug)]
pub struct InvalidSignature;

#[cfg(feature = "testing")]
/// Utilities for testing. Implementations can use the test cases and functions provided to test
/// their implementation.
pub mod testing {
    extern crate alloc;
    extern crate std;

    use crate::ed25519::{Ed25519Provider, KeyPair, PublicKey, Signature};
    use alloc::borrow::ToOwned;
    use alloc::string::String;
    use alloc::vec::Vec;
    use wycheproof::TestResult;

    // These are test vectors from the creators of Ed25519: https://ed25519.cr.yp.to/ which are referenced
    // as the SOT for the test vectors in the RFC: https://www.rfc-editor.org/rfc/rfc8032#section-7.1
    // The vectors have been formatted into a easily parsable/readable format by libgcrypt which is
    // also used for test cases in the above RFC:
    // https://dev.gnupg.org/source/libgcrypt/browse/master/tests/t-ed25519.inp
    const PATH_TO_RFC_VECTORS_FILE: &str =
        "crypto/crypto_provider/src/testdata/ecdsa/rfc_test_vectors.txt";

    /// Runs set of Ed25519 wycheproof test vectors against a provided ed25519 implementation
    /// Tests vectors from Project Wycheproof: <https://github.com/google/wycheproof>
    pub fn run_wycheproof_test_vectors<E>()
    where
        E: Ed25519Provider,
    {
        let test_set = wycheproof::eddsa::TestSet::load(wycheproof::eddsa::TestName::Ed25519)
            .expect("should be able to load test set");

        for test_group in test_set.test_groups {
            let key_pair = test_group.key;
            let public_key = key_pair.pk;
            let secret_key = key_pair.sk;

            for test in test_group.tests {
                let tc_id = test.tc_id;
                let comment = test.comment;
                let sig = test.sig;
                let msg = test.msg;

                let valid = match test.result {
                    TestResult::Invalid => false,
                    TestResult::Valid | TestResult::Acceptable => true,
                };
                let result = run_test::<E>(
                    public_key.clone(),
                    secret_key.clone(),
                    sig.clone(),
                    msg.clone(),
                );
                if valid {
                    if let Err(desc) = result {
                        panic!(
                            "\n\
                         Failed test {}: {}\n\
                         msg:\t{:?}\n\
                         sig:\t{:?}\n\
                         comment:\t{:?}\n",
                            tc_id, desc, msg, sig, comment,
                        );
                    }
                } else {
                    assert!(result.is_err())
                }
            }
        }
    }

    /// Runs the RFC specified test vectors against an Ed25519 implementation
    pub fn run_rfc_test_vectors<E>()
    where
        E: Ed25519Provider,
    {
        let file_contents =
            std::fs::read_to_string(test_helper::get_data_file(PATH_TO_RFC_VECTORS_FILE))
                .expect("should be able to read file");

        let mut split_cases: Vec<&str> = file_contents.as_str().split("\n\n").collect();
        // remove the comments
        split_cases.remove(0);
        for case in split_cases {
            let test_case: Vec<&str> = case.split('\n').collect();

            let tc_id = extract_string(test_case[0]);
            let sk = extract_hex(test_case[1]);
            let pk = extract_hex(test_case[2]);
            let msg = extract_hex(test_case[3]);
            let sig = extract_hex(test_case[4]);

            let result = run_test::<E>(pk.clone(), sk.clone(), sig.clone(), msg.clone());
            if let Err(desc) = result {
                panic!(
                    "\n\
                         Failed test {}: {}\n\
                         msg:\t{:?}\n\
                         sig:\t{:?}\n\"",
                    tc_id, desc, msg, sig,
                );
            }
        }
    }

    fn extract_hex(line: &str) -> Vec<u8> {
        test_helper::string_to_hex(extract_string(line).as_str())
    }

    fn extract_string(line: &str) -> String {
        line.split(':').collect::<Vec<&str>>()[1].trim().to_owned()
    }

    fn run_test<E>(
        pub_key: Vec<u8>,
        secret_key: Vec<u8>,
        sig: Vec<u8>,
        msg: Vec<u8>,
    ) -> Result<(), &'static str>
    where
        E: Ed25519Provider,
    {
        let kp_bytes: [u8; 64] = [secret_key.as_slice(), pub_key.as_slice()]
            .concat()
            .try_into()
            .map_err(|_| "invalid length keypair")?;
        let kp = E::KeyPair::from_bytes(kp_bytes)
            .map_err(|_| "Should be able to create Keypair from bytes")?;

        let sig_result = kp.sign(msg.as_slice());
        (sig.as_slice() == sig_result.to_bytes())
            .then_some(())
            .ok_or("sig not matching expected")?;
        let signature = E::Signature::from_bytes(sig.as_slice())
            .map_err(|_| "unable to parse sign from test case")?;

        let pub_key = kp.public();
        pub_key
            .verify_strict(msg.as_slice(), &signature)
            .map_err(|_| "verify failed")?;

        Ok(())
    }
}
