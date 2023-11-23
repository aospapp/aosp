//! Utility program to parse a legacy authenticated keyblob.

// Explicitly include alloc because macros from `kmr_common` assume it.
extern crate alloc;

use kmr_common::{
    crypto::*,
    get_tag_value,
    keyblob::{legacy::KeyBlob, *},
    tag,
};
use kmr_crypto_boring::{eq::BoringEq, hmac::BoringHmac};
use kmr_wire::{
    keymint,
    keymint::{
        Algorithm, DateTime, EcCurve, ErrorCode, KeyCharacteristics, KeyParam, SecurityLevel,
    },
};
use std::convert::TryInto;

fn main() {
    let mut hex = false;
    let args: Vec<String> = std::env::args().collect();
    for arg in &args[1..] {
        if arg == "--hex" {
            hex = !hex;
        } else {
            process(arg, hex);
        }
    }
}

const SOFTWARE_ROOT_OF_TRUST: &[u8] = b"SW";

/// Remove all instances of some tags from a set of `KeyParameter`s.
pub fn remove_tags(params: &[KeyParam], tags: &[keymint::Tag]) -> Vec<KeyParam> {
    params.iter().filter(|p| !tags.contains(&p.tag())).cloned().collect()
}

fn process(filename: &str, hex: bool) {
    let _ = env_logger::builder().is_test(true).try_init();

    let mut data: Vec<u8> = std::fs::read(filename).unwrap();
    if hex {
        let hexdata = std::str::from_utf8(&data).unwrap().trim();
        data = match hex::decode(hexdata) {
            Ok(v) => v,
            Err(e) => {
                eprintln!(
                    "{}: Failed to parse hex ({:?}): len={} {}",
                    filename,
                    e,
                    hexdata.len(),
                    hexdata
                );
                return;
            }
        };
    }
    let hidden = tag::hidden(&[], SOFTWARE_ROOT_OF_TRUST).unwrap();
    let hmac = BoringHmac {};
    let keyblob = match KeyBlob::deserialize(&hmac, &data, &hidden, BoringEq) {
        Ok(k) => k,
        Err(e) => {
            eprintln!("{}: Failed to parse: {:?}", filename, e);
            return;
        }
    };
    println!(
        "{}: KeyBlob  {{\n  key_material=...(len {}),\n  hw_enforced={:?},\n  sw_enforced={:?},\n}}",
        filename,
        keyblob.key_material.len(),
        keyblob.hw_enforced,
        keyblob.sw_enforced
    );

    #[cfg(soong)]
    {
        // Also round-trip the keyblob to binary and expect to get back where we started.
        let regenerated_data = keyblob.serialize(&hmac, &hidden).unwrap();
        assert_eq!(&regenerated_data[..regenerated_data.len()], &data[..data.len()]);
    }

    // Create a PlaintextKeyBlob from the data.
    let mut combined = keyblob.hw_enforced.clone();
    combined.extend_from_slice(&keyblob.sw_enforced);

    let algo_val = get_tag_value!(&combined, Algorithm, ErrorCode::InvalidArgument)
        .expect("characteristics missing algorithm");

    // To disinguish between Ed25519 and X25519, need to examine the purpose for the key.
    // Look for AgreeKey as it cannot be combined with other purposes.
    let primary_purpose = combined
        .iter()
        .filter_map(
            |param| if let KeyParam::Purpose(purpose) = param { Some(*purpose) } else { None },
        )
        .next();

    let raw_key = keyblob.key_material.clone();
    let key_material = match algo_val {
        Algorithm::Aes => KeyMaterial::Aes(aes::Key::new(keyblob.key_material).unwrap().into()),
        Algorithm::TripleDes => KeyMaterial::TripleDes(
            des::Key(raw_key.try_into().expect("Incorrect length for 3DES key")).into(),
        ),
        Algorithm::Hmac => KeyMaterial::Hmac(hmac::Key(raw_key).into()),
        Algorithm::Ec => {
            let curve_val = tag::get_ec_curve(&combined).expect("characteristics missing EC curve");
            match curve_val {
                EcCurve::P224 => KeyMaterial::Ec(
                    EcCurve::P224,
                    CurveType::Nist,
                    ec::Key::P224(ec::NistKey(raw_key)).into(),
                ),
                EcCurve::P256 => KeyMaterial::Ec(
                    EcCurve::P256,
                    CurveType::Nist,
                    ec::Key::P256(ec::NistKey(raw_key)).into(),
                ),
                EcCurve::P384 => KeyMaterial::Ec(
                    EcCurve::P384,
                    CurveType::Nist,
                    ec::Key::P384(ec::NistKey(raw_key)).into(),
                ),
                EcCurve::P521 => KeyMaterial::Ec(
                    EcCurve::P521,
                    CurveType::Nist,
                    ec::Key::P521(ec::NistKey(raw_key)).into(),
                ),
                EcCurve::Curve25519 => {
                    let key = raw_key.try_into().expect("curve25519 key of wrong size");
                    if primary_purpose == Some(keymint::KeyPurpose::AgreeKey) {
                        KeyMaterial::Ec(
                            EcCurve::Curve25519,
                            CurveType::Xdh,
                            ec::Key::X25519(ec::X25519Key(key)).into(),
                        )
                    } else {
                        KeyMaterial::Ec(
                            EcCurve::Curve25519,
                            CurveType::EdDsa,
                            ec::Key::Ed25519(ec::Ed25519Key(key)).into(),
                        )
                    }
                }
            }
        }
        Algorithm::Rsa => KeyMaterial::Rsa(rsa::Key(raw_key).into()),
    };

    // Test the `tag::extract_key_characteristics()` entrypoint by comparing what it
    // produces against the keyblob's combined characteristics. To do this, we need
    // to simulate a key-generation operation by:
    // - removing the KeyMint-added tags
    // - removing any Keystore-enforced tags
    // - adding any tags required for key generation.
    let mut filtered = keyblob.hw_enforced.clone();
    filtered.extend_from_slice(&keyblob.sw_enforced);
    let filtered = remove_tags(&filtered, tag::AUTO_ADDED_CHARACTERISTICS);
    let mut filtered = remove_tags(&filtered, tag::KEYSTORE_ENFORCED_CHARACTERISTICS);
    filtered.sort_by(tag::legacy::param_compare);

    let mut keygen_params = filtered.clone();
    match tag::get_algorithm(&filtered).unwrap() {
        Algorithm::Ec | Algorithm::Rsa => {
            keygen_params.push(KeyParam::CertificateNotBefore(DateTime { ms_since_epoch: 0 }));
            keygen_params.push(KeyParam::CertificateNotAfter(DateTime {
                ms_since_epoch: 1_900_000_000_000,
            }));
        }
        _ => {}
    }
    keygen_params.sort_by(tag::legacy::param_compare);
    let (extracted, _) = tag::extract_key_gen_characteristics(
        kmr_common::tag::SecureStorage::Unavailable,
        &keygen_params,
        SecurityLevel::Software,
    )
    .unwrap();
    assert_eq!(extracted[0].authorizations, filtered);

    let plaintext_keyblob = PlaintextKeyBlob {
        characteristics: vec![
            KeyCharacteristics {
                security_level: SecurityLevel::TrustedEnvironment,
                authorizations: keyblob.hw_enforced,
            },
            KeyCharacteristics {
                security_level: SecurityLevel::Software,
                authorizations: keyblob.sw_enforced,
            },
        ],
        key_material,
    };
    println!("{}:  => {:?}", filename, plaintext_keyblob);
}
