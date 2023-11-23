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

use nix::unistd::getuid;

use android_hardware_security_keymint::aidl::android::hardware::security::keymint::{
    Algorithm::Algorithm, BlockMode::BlockMode, Digest::Digest, EcCurve::EcCurve,
    ErrorCode::ErrorCode, KeyPurpose::KeyPurpose, PaddingMode::PaddingMode,
    SecurityLevel::SecurityLevel,
};
use android_system_keystore2::aidl::android::system::keystore2::{
    Domain::Domain, KeyDescriptor::KeyDescriptor, ResponseCode::ResponseCode,
};

use keystore2_test_utils::{
    authorizations, get_keystore_service, key_generations, key_generations::Error,
};

use crate::ffi_test_utils::validate_certchain;

use crate::{
    keystore2_client_test_utils::app_attest_key_feature_exists,
    skip_test_if_no_app_attest_key_feature,
};

/// Generate RSA and EC attestation keys and use them for signing RSA-signing keys.
/// Test should be able to generate attestation keys and use them successfully.
#[test]
fn keystore2_attest_rsa_signing_key_success() {
    skip_test_if_no_app_attest_key_feature!();

    let keystore2 = get_keystore_service();
    let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();
    let att_challenge: &[u8] = b"foo";

    for algo in [Algorithm::RSA, Algorithm::EC] {
        // Create attestation key.
        let attestation_key_metadata =
            key_generations::generate_attestation_key(&sec_level, algo, att_challenge).unwrap();

        let mut cert_chain: Vec<u8> = Vec::new();
        cert_chain.extend(attestation_key_metadata.certificate.as_ref().unwrap());
        cert_chain.extend(attestation_key_metadata.certificateChain.as_ref().unwrap());
        validate_certchain(&cert_chain).expect("Error while validating cert chain.");

        // Create RSA signing key and use attestation key to sign it.
        let sign_key_alias = format!("ks_attest_rsa_signing_key_{}", getuid());
        let sign_key_metadata = key_generations::generate_rsa_key(
            &sec_level,
            Domain::APP,
            -1,
            Some(sign_key_alias),
            &key_generations::KeyParams {
                key_size: 2048,
                purpose: vec![KeyPurpose::SIGN, KeyPurpose::VERIFY],
                padding: Some(PaddingMode::RSA_PKCS1_1_5_SIGN),
                digest: Some(Digest::SHA_2_256),
                mgf_digest: None,
                block_mode: None,
                att_challenge: Some(att_challenge.to_vec()),
            },
            Some(&attestation_key_metadata.key),
        )
        .unwrap();

        let mut cert_chain: Vec<u8> = Vec::new();
        cert_chain.extend(sign_key_metadata.certificate.as_ref().unwrap());
        cert_chain.extend(attestation_key_metadata.certificate.as_ref().unwrap());
        cert_chain.extend(attestation_key_metadata.certificateChain.as_ref().unwrap());
        validate_certchain(&cert_chain).expect("Error while validating cert chain");
    }
}

/// Generate RSA and EC attestation keys and use them for signing RSA encrypt/decrypt keys.
/// Test should be able to generate attestation keys and use them successfully.
#[test]
fn keystore2_attest_rsa_encrypt_key_success() {
    skip_test_if_no_app_attest_key_feature!();

    let keystore2 = get_keystore_service();
    let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();
    let att_challenge: &[u8] = b"foo";

    for algo in [Algorithm::RSA, Algorithm::EC] {
        // Create attestation key.
        let attestation_key_metadata =
            key_generations::generate_attestation_key(&sec_level, algo, att_challenge).unwrap();

        let mut cert_chain: Vec<u8> = Vec::new();
        cert_chain.extend(attestation_key_metadata.certificate.as_ref().unwrap());
        cert_chain.extend(attestation_key_metadata.certificateChain.as_ref().unwrap());
        validate_certchain(&cert_chain).expect("Error while validating cert chain.");

        // Create RSA encrypt/decrypt key and use attestation key to sign it.
        let decrypt_key_alias = format!("ks_attest_rsa_encrypt_key_{}", getuid());
        let decrypt_key_metadata = key_generations::generate_rsa_key(
            &sec_level,
            Domain::APP,
            -1,
            Some(decrypt_key_alias),
            &key_generations::KeyParams {
                key_size: 2048,
                purpose: vec![KeyPurpose::ENCRYPT, KeyPurpose::DECRYPT],
                padding: Some(PaddingMode::RSA_PKCS1_1_5_ENCRYPT),
                digest: Some(Digest::SHA_2_256),
                mgf_digest: None,
                block_mode: None,
                att_challenge: Some(att_challenge.to_vec()),
            },
            Some(&attestation_key_metadata.key),
        )
        .unwrap();

        let mut cert_chain: Vec<u8> = Vec::new();
        cert_chain.extend(decrypt_key_metadata.certificate.as_ref().unwrap());
        cert_chain.extend(attestation_key_metadata.certificate.as_ref().unwrap());
        cert_chain.extend(attestation_key_metadata.certificateChain.as_ref().unwrap());

        validate_certchain(&cert_chain).expect("Error while validating cert chain.");
    }
}

/// Generate RSA and EC attestation keys and use them for signing EC keys.
/// Test should be able to generate attestation keys and use them successfully.
#[test]
fn keystore2_attest_ec_key_success() {
    skip_test_if_no_app_attest_key_feature!();

    let keystore2 = get_keystore_service();
    let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();
    let att_challenge: &[u8] = b"foo";

    for algo in [Algorithm::RSA, Algorithm::EC] {
        // Create attestation key.
        let attestation_key_metadata =
            key_generations::generate_attestation_key(&sec_level, algo, att_challenge).unwrap();

        let mut cert_chain: Vec<u8> = Vec::new();
        cert_chain.extend(attestation_key_metadata.certificate.as_ref().unwrap());
        cert_chain.extend(attestation_key_metadata.certificateChain.as_ref().unwrap());
        validate_certchain(&cert_chain).expect("Error while validating cert chain.");

        // Create EC key and use attestation key to sign it.
        let ec_key_alias = format!("ks_ec_attested_test_key_{}", getuid());
        let ec_key_metadata = key_generations::generate_ec_256_attested_key(
            &sec_level,
            Some(ec_key_alias),
            att_challenge,
            &attestation_key_metadata.key,
        )
        .unwrap();

        let mut cert_chain: Vec<u8> = Vec::new();
        cert_chain.extend(ec_key_metadata.certificate.as_ref().unwrap());
        cert_chain.extend(attestation_key_metadata.certificate.as_ref().unwrap());
        cert_chain.extend(attestation_key_metadata.certificateChain.as_ref().unwrap());

        validate_certchain(&cert_chain).expect("Error while validating cert chain.");
    }
}

/// Generate EC-CURVE_25519 attestation key and use it for signing RSA-signing keys.
/// Test should be able to generate RSA signing key with EC-CURVE_25519 as attestation key
/// successfully.
#[test]
fn keystore2_attest_rsa_signing_key_with_ec_25519_key_success() {
    skip_test_if_no_app_attest_key_feature!();

    let keystore2 = get_keystore_service();
    let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();
    let att_challenge: &[u8] = b"foo";

    // Create EcCurve::CURVE_25519 attestation key.
    let attestation_key_metadata = key_generations::generate_ec_attestation_key(
        &sec_level,
        att_challenge,
        Digest::NONE,
        EcCurve::CURVE_25519,
    )
    .unwrap();

    let mut cert_chain: Vec<u8> = Vec::new();
    cert_chain.extend(attestation_key_metadata.certificate.as_ref().unwrap());
    cert_chain.extend(attestation_key_metadata.certificateChain.as_ref().unwrap());
    validate_certchain(&cert_chain).expect("Error while validating cert chain.");

    // Create RSA signing key and use attestation key to sign it.
    let sign_key_alias = format!("ksrsa_attested_sign_test_key_{}", getuid());
    let sign_key_metadata = key_generations::generate_rsa_key(
        &sec_level,
        Domain::APP,
        -1,
        Some(sign_key_alias),
        &key_generations::KeyParams {
            key_size: 2048,
            purpose: vec![KeyPurpose::SIGN, KeyPurpose::VERIFY],
            padding: Some(PaddingMode::RSA_PKCS1_1_5_SIGN),
            digest: Some(Digest::SHA_2_256),
            mgf_digest: None,
            block_mode: None,
            att_challenge: Some(att_challenge.to_vec()),
        },
        Some(&attestation_key_metadata.key),
    )
    .unwrap();

    let mut cert_chain: Vec<u8> = Vec::new();
    cert_chain.extend(sign_key_metadata.certificate.as_ref().unwrap());
    cert_chain.extend(attestation_key_metadata.certificate.as_ref().unwrap());
    cert_chain.extend(attestation_key_metadata.certificateChain.as_ref().unwrap());
    validate_certchain(&cert_chain).expect("Error while validating cert chain");
}

/// Try to generate RSA attestation key with multiple purposes. Test should fail with error code
/// `INCOMPATIBLE_PURPOSE` to generate an attestation key.
#[test]
fn keystore2_generate_rsa_attest_key_with_multi_purpose_fail() {
    skip_test_if_no_app_attest_key_feature!();

    let keystore2 = get_keystore_service();
    let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();

    let digest = Digest::SHA_2_256;
    let padding = PaddingMode::RSA_PKCS1_1_5_SIGN;
    let key_size = 2048;

    let attest_key_alias =
        format!("ksrsa_attest_multipurpose_key_{}{}{}", getuid(), key_size, digest.0);

    let attest_gen_params = authorizations::AuthSetBuilder::new()
        .no_auth_required()
        .algorithm(Algorithm::RSA)
        .purpose(KeyPurpose::ATTEST_KEY)
        .purpose(KeyPurpose::SIGN)
        .purpose(KeyPurpose::VERIFY)
        .digest(digest)
        .key_size(key_size)
        .rsa_public_exponent(65537)
        .padding_mode(padding);

    let result = key_generations::map_ks_error(sec_level.generateKey(
        &KeyDescriptor {
            domain: Domain::APP,
            nspace: -1,
            alias: Some(attest_key_alias),
            blob: None,
        },
        None,
        &attest_gen_params,
        0,
        b"entropy",
    ));
    assert!(result.is_err());
    assert_eq!(Error::Km(ErrorCode::INCOMPATIBLE_PURPOSE), result.unwrap_err());
}

/// Try to generate EC attestation key with multiple purposes. Test should fail with error code
/// `INCOMPATIBLE_PURPOSE` to generate an attestation key.
#[test]
fn keystore2_ec_attest_key_with_multi_purpose_fail() {
    skip_test_if_no_app_attest_key_feature!();

    let keystore2 = get_keystore_service();
    let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();

    let attest_key_alias = format!("ks_ec_attest_multipurpose_key_{}", getuid());

    let attest_gen_params = authorizations::AuthSetBuilder::new()
        .no_auth_required()
        .algorithm(Algorithm::EC)
        .purpose(KeyPurpose::ATTEST_KEY)
        .purpose(KeyPurpose::SIGN)
        .purpose(KeyPurpose::VERIFY)
        .digest(Digest::SHA_2_256)
        .ec_curve(EcCurve::P_256);

    let result = key_generations::map_ks_error(sec_level.generateKey(
        &KeyDescriptor {
            domain: Domain::APP,
            nspace: -1,
            alias: Some(attest_key_alias),
            blob: None,
        },
        None,
        &attest_gen_params,
        0,
        b"entropy",
    ));
    assert!(result.is_err());
    assert_eq!(Error::Km(ErrorCode::INCOMPATIBLE_PURPOSE), result.unwrap_err());
}

/// Generate RSA attestation key and try to use it for signing RSA key without providing
/// attestation challenge. Test should fail to generate a key with error code
/// `ATTESTATION_CHALLENGE_MISSING`.
#[test]
fn keystore2_attest_key_fails_missing_challenge() {
    skip_test_if_no_app_attest_key_feature!();

    let keystore2 = get_keystore_service();
    let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();
    let att_challenge: &[u8] = b"foo";

    // Create RSA attestation key.
    let attestation_key_metadata =
        key_generations::generate_attestation_key(&sec_level, Algorithm::RSA, att_challenge)
            .unwrap();

    let mut cert_chain: Vec<u8> = Vec::new();
    cert_chain.extend(attestation_key_metadata.certificate.as_ref().unwrap());
    cert_chain.extend(attestation_key_metadata.certificateChain.as_ref().unwrap());
    validate_certchain(&cert_chain).expect("Error while validating cert chain.");

    // Try to attest RSA signing key without providing attestation challenge.
    let sign_key_alias = format!("ksrsa_attested_test_key_missing_challenge{}", getuid());
    let result = key_generations::map_ks_error(key_generations::generate_rsa_key(
        &sec_level,
        Domain::APP,
        -1,
        Some(sign_key_alias),
        &key_generations::KeyParams {
            key_size: 2048,
            purpose: vec![KeyPurpose::SIGN, KeyPurpose::VERIFY],
            padding: Some(PaddingMode::RSA_PKCS1_1_5_SIGN),
            digest: Some(Digest::SHA_2_256),
            mgf_digest: None,
            block_mode: None,
            att_challenge: None,
        },
        Some(&attestation_key_metadata.key),
    ));
    assert!(result.is_err());
    assert_eq!(Error::Km(ErrorCode::ATTESTATION_CHALLENGE_MISSING), result.unwrap_err());
}

/// Generate an asymmetric key which doesn't possess ATTEST_KEY purpose. Try to use this key as
/// attestation key while generating RSA key. Test should fail to generate a key with error
/// code `INCOMPATIBLE_PURPOSE`.
#[test]
fn keystore2_attest_rsa_key_with_non_attest_key_fails_incompat_purpose_error() {
    skip_test_if_no_app_attest_key_feature!();

    let keystore2 = get_keystore_service();
    let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();
    let att_challenge: &[u8] = b"foo";

    let alias = format!("non_attest_key_{}", getuid());
    let non_attest_key_metadata = key_generations::generate_ec_p256_signing_key(
        &sec_level,
        Domain::APP,
        -1,
        Some(alias),
        None,
    )
    .unwrap();

    // Try to generate RSA signing key with non-attestation key to sign it.
    let sign_key_alias = format!("ksrsa_attested_sign_test_key_non_attest_{}", getuid());
    let result = key_generations::map_ks_error(key_generations::generate_rsa_key(
        &sec_level,
        Domain::APP,
        -1,
        Some(sign_key_alias),
        &key_generations::KeyParams {
            key_size: 2048,
            purpose: vec![KeyPurpose::SIGN, KeyPurpose::VERIFY],
            padding: Some(PaddingMode::RSA_PKCS1_1_5_SIGN),
            digest: Some(Digest::SHA_2_256),
            mgf_digest: None,
            block_mode: None,
            att_challenge: Some(att_challenge.to_vec()),
        },
        Some(&non_attest_key_metadata.key),
    ));
    assert!(result.is_err());
    assert_eq!(Error::Km(ErrorCode::INCOMPATIBLE_PURPOSE), result.unwrap_err());
}

/// Generate a symmetric key. Try to use this symmetric key as attestation key while generating RSA
/// key. Test should fail to generate a key with response code `INVALID_ARGUMENT`.
#[test]
fn keystore2_attest_rsa_key_with_symmetric_key_fails_sys_error() {
    skip_test_if_no_app_attest_key_feature!();

    let keystore2 = get_keystore_service();
    let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();
    let att_challenge: &[u8] = b"foo";

    let alias = "aes_attest_key";
    let sym_key_metadata = key_generations::generate_sym_key(
        &sec_level,
        Algorithm::AES,
        128,
        alias,
        &PaddingMode::NONE,
        &BlockMode::ECB,
        None,
    )
    .unwrap();

    // Try to generate RSA signing key with symmetric key as attestation key.
    let sign_key_alias = format!("ksrsa_attested_sign_test_key_sym_attest_{}", getuid());
    let result = key_generations::map_ks_error(key_generations::generate_rsa_key(
        &sec_level,
        Domain::APP,
        -1,
        Some(sign_key_alias),
        &key_generations::KeyParams {
            key_size: 2048,
            purpose: vec![KeyPurpose::SIGN, KeyPurpose::VERIFY],
            padding: Some(PaddingMode::RSA_PKCS1_1_5_SIGN),
            digest: Some(Digest::SHA_2_256),
            mgf_digest: None,
            block_mode: None,
            att_challenge: Some(att_challenge.to_vec()),
        },
        Some(&sym_key_metadata.key),
    ));
    assert!(result.is_err());
    assert_eq!(Error::Rc(ResponseCode::INVALID_ARGUMENT), result.unwrap_err());
}

/// Generate RSA attestation key and try to use it as attestation key while generating symmetric
/// key. Test should generate symmetric key successfully. Verify that generated symmetric key
/// should not have attestation record or certificate.
#[test]
fn keystore2_attest_symmetric_key_fail_sys_error() {
    skip_test_if_no_app_attest_key_feature!();

    let keystore2 = get_keystore_service();
    let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();
    let att_challenge: &[u8] = b"foo";

    // Create attestation key.
    let attestation_key_metadata =
        key_generations::generate_attestation_key(&sec_level, Algorithm::RSA, att_challenge)
            .unwrap();

    let mut cert_chain: Vec<u8> = Vec::new();
    cert_chain.extend(attestation_key_metadata.certificate.as_ref().unwrap());
    cert_chain.extend(attestation_key_metadata.certificateChain.as_ref().unwrap());
    validate_certchain(&cert_chain).expect("Error while validating cert chain.");

    // Generate symmetric key with above generated key as attestation key.
    let gen_params = authorizations::AuthSetBuilder::new()
        .no_auth_required()
        .algorithm(Algorithm::AES)
        .purpose(KeyPurpose::ENCRYPT)
        .purpose(KeyPurpose::DECRYPT)
        .key_size(128)
        .padding_mode(PaddingMode::NONE)
        .block_mode(BlockMode::ECB)
        .attestation_challenge(att_challenge.to_vec());

    let alias = format!("ks_test_sym_key_attest_{}", getuid());
    let aes_key_metadata = sec_level
        .generateKey(
            &KeyDescriptor { domain: Domain::APP, nspace: -1, alias: Some(alias), blob: None },
            Some(&attestation_key_metadata.key),
            &gen_params,
            0,
            b"entropy",
        )
        .unwrap();

    // Should not have public certificate.
    assert!(aes_key_metadata.certificate.is_none());

    // Should not have an attestation record.
    assert!(aes_key_metadata.certificateChain.is_none());
}
