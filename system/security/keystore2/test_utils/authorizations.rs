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

//! This module implements test utils to create Autherizations.

use std::ops::Deref;

use android_hardware_security_keymint::aidl::android::hardware::security::keymint::{
    Algorithm::Algorithm, BlockMode::BlockMode, Digest::Digest, EcCurve::EcCurve,
    KeyParameter::KeyParameter, KeyParameterValue::KeyParameterValue, KeyPurpose::KeyPurpose,
    PaddingMode::PaddingMode, Tag::Tag,
};

/// Helper struct to create set of Authorizations.
#[derive(Debug, Clone, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct AuthSetBuilder(Vec<KeyParameter>);

impl Default for AuthSetBuilder {
    fn default() -> Self {
        Self::new()
    }
}

impl AuthSetBuilder {
    /// Creates new Authorizations list.
    pub fn new() -> Self {
        Self(Vec::new())
    }

    /// Add Purpose.
    pub fn purpose(mut self, p: KeyPurpose) -> Self {
        self.0.push(KeyParameter { tag: Tag::PURPOSE, value: KeyParameterValue::KeyPurpose(p) });
        self
    }

    /// Add Digest.
    pub fn digest(mut self, d: Digest) -> Self {
        self.0.push(KeyParameter { tag: Tag::DIGEST, value: KeyParameterValue::Digest(d) });
        self
    }

    /// Add Algorithm.
    pub fn algorithm(mut self, a: Algorithm) -> Self {
        self.0.push(KeyParameter { tag: Tag::ALGORITHM, value: KeyParameterValue::Algorithm(a) });
        self
    }

    /// Add EC-Curve.
    pub fn ec_curve(mut self, e: EcCurve) -> Self {
        self.0.push(KeyParameter { tag: Tag::EC_CURVE, value: KeyParameterValue::EcCurve(e) });
        self
    }

    /// Add Attestation-Challenge.
    pub fn attestation_challenge(mut self, b: Vec<u8>) -> Self {
        self.0.push(KeyParameter {
            tag: Tag::ATTESTATION_CHALLENGE,
            value: KeyParameterValue::Blob(b),
        });
        self
    }

    /// Add No_auth_required.
    pub fn no_auth_required(mut self) -> Self {
        self.0.push(KeyParameter {
            tag: Tag::NO_AUTH_REQUIRED,
            value: KeyParameterValue::BoolValue(true),
        });
        self
    }

    /// Add RSA_public_exponent.
    pub fn rsa_public_exponent(mut self, e: i64) -> Self {
        self.0.push(KeyParameter {
            tag: Tag::RSA_PUBLIC_EXPONENT,
            value: KeyParameterValue::LongInteger(e),
        });
        self
    }

    /// Add key size.
    pub fn key_size(mut self, s: i32) -> Self {
        self.0.push(KeyParameter { tag: Tag::KEY_SIZE, value: KeyParameterValue::Integer(s) });
        self
    }

    /// Add block mode.
    pub fn block_mode(mut self, b: BlockMode) -> Self {
        self.0.push(KeyParameter { tag: Tag::BLOCK_MODE, value: KeyParameterValue::BlockMode(b) });
        self
    }

    /// Add certificate_not_before.
    pub fn cert_not_before(mut self, b: i64) -> Self {
        self.0.push(KeyParameter {
            tag: Tag::CERTIFICATE_NOT_BEFORE,
            value: KeyParameterValue::DateTime(b),
        });
        self
    }

    /// Add certificate_not_after.
    pub fn cert_not_after(mut self, a: i64) -> Self {
        self.0.push(KeyParameter {
            tag: Tag::CERTIFICATE_NOT_AFTER,
            value: KeyParameterValue::DateTime(a),
        });
        self
    }

    /// Add padding mode.
    pub fn padding_mode(mut self, p: PaddingMode) -> Self {
        self.0.push(KeyParameter { tag: Tag::PADDING, value: KeyParameterValue::PaddingMode(p) });
        self
    }

    /// Add mgf_digest.
    pub fn mgf_digest(mut self, d: Digest) -> Self {
        self.0.push(KeyParameter {
            tag: Tag::RSA_OAEP_MGF_DIGEST,
            value: KeyParameterValue::Digest(d),
        });
        self
    }

    /// Add nonce.
    pub fn nonce(mut self, b: Vec<u8>) -> Self {
        self.0.push(KeyParameter { tag: Tag::NONCE, value: KeyParameterValue::Blob(b) });
        self
    }

    /// Add CALLER_NONCE.
    pub fn caller_nonce(mut self) -> Self {
        self.0.push(KeyParameter {
            tag: Tag::CALLER_NONCE,
            value: KeyParameterValue::BoolValue(true),
        });
        self
    }

    /// Add MAC length.
    pub fn mac_length(mut self, l: i32) -> Self {
        self.0.push(KeyParameter { tag: Tag::MAC_LENGTH, value: KeyParameterValue::Integer(l) });
        self
    }

    /// Add min MAC length.
    pub fn min_mac_length(mut self, l: i32) -> Self {
        self.0
            .push(KeyParameter { tag: Tag::MIN_MAC_LENGTH, value: KeyParameterValue::Integer(l) });
        self
    }
}

impl Deref for AuthSetBuilder {
    type Target = Vec<KeyParameter>;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
