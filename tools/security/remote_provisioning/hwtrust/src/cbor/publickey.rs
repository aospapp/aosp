//! CBOR encoding and decoding of a [`PublicKey`].

use crate::publickey::{EcKind, Kind, PublicKey};
use anyhow::{anyhow, bail, ensure, Context, Result};
use coset::cbor::value::Value;
use coset::iana::{self, EnumI64};
use coset::{Algorithm, CoseKey, CoseKeyBuilder, CoseSign1, KeyOperation, KeyType, Label};
use openssl::bn::{BigNum, BigNumContext};
use openssl::ec::{EcGroup, EcKey};
use openssl::ecdsa::EcdsaSig;
use openssl::nid::Nid;
use openssl::pkey::{Id, PKey, Public};

impl PublicKey {
    pub(super) fn from_cose_key(cose_key: &CoseKey) -> Result<Self> {
        if !cose_key.key_ops.is_empty() {
            ensure!(cose_key.key_ops.contains(&KeyOperation::Assigned(iana::KeyOperation::Verify)));
        }
        let pkey = match cose_key.kty {
            KeyType::Assigned(iana::KeyType::OKP) => pkey_from_okp_key(cose_key)?,
            KeyType::Assigned(iana::KeyType::EC2) => pkey_from_ec2_key(cose_key)?,
            _ => bail!("Unexpected KeyType value: {:?}", cose_key.kty),
        };
        pkey.try_into().context("Making PublicKey from PKey")
    }

    /// Verifies a COSE_Sign1 signature over its message. This function handles the conversion of
    /// the signature format that is needed for some algorithms.
    pub(in crate::cbor) fn verify_cose_sign1(&self, sign1: &CoseSign1) -> Result<()> {
        ensure!(sign1.protected.header.crit.is_empty(), "No critical headers allowed");
        ensure!(
            sign1.protected.header.alg == Some(Algorithm::Assigned(iana_algorithm(self.kind()))),
            "Algorithm mistmatch in protected header"
        );
        sign1.verify_signature(b"", |signature, message| match self.kind() {
            Kind::Ec(k) => {
                let der = ec_cose_signature_to_der(k, signature).context("Signature to DER")?;
                self.verify(&der, message)
            }
            _ => self.verify(signature, message),
        })
    }

    /// Convert the public key into a [`CoseKey`].
    pub fn to_cose_key(&self) -> Result<CoseKey> {
        let builder = match self.kind() {
            Kind::Ed25519 => {
                let label_crv = iana::OkpKeyParameter::Crv.to_i64();
                let label_x = iana::OkpKeyParameter::X.to_i64();
                let x = self.pkey().raw_public_key().context("Get ed25519 raw public key")?;
                CoseKeyBuilder::new_okp_key()
                    .param(label_crv, Value::from(iana::EllipticCurve::Ed25519.to_i64()))
                    .param(label_x, Value::from(x))
            }
            Kind::Ec(ec) => {
                let key = self.pkey().ec_key().unwrap();
                let group = key.group();
                let mut ctx = BigNumContext::new().context("Failed to create bignum context")?;
                let mut x = BigNum::new().context("Failed to create x coord")?;
                let mut y = BigNum::new().context("Failed to create y coord")?;
                key.public_key()
                    .affine_coordinates_gfp(group, &mut x, &mut y, &mut ctx)
                    .context("Get EC coordinates")?;
                let (crv, coord_len) = match ec {
                    EcKind::P256 => (iana::EllipticCurve::P_256, 32),
                    EcKind::P384 => (iana::EllipticCurve::P_384, 48),
                };

                let x = adjust_coord(x.to_vec(), coord_len);
                let y = adjust_coord(y.to_vec(), coord_len);
                CoseKeyBuilder::new_ec2_pub_key(crv, x, y)
            }
        };
        Ok(builder
            .algorithm(iana_algorithm(self.kind()))
            .add_key_op(iana::KeyOperation::Verify)
            .build())
    }
}

fn adjust_coord(mut coordinate: Vec<u8>, length: usize) -> Vec<u8> {
    // Use loops "just in case". However we should never see a coordinate with more than one
    // extra leading byte. The chances of more than one trailing byte is also quite small --
    // roughly 1/65000.
    while coordinate.len() > length && coordinate[0] == 00 {
        coordinate.remove(0);
    }

    while coordinate.len() < length {
        coordinate.insert(0, 0);
    }

    coordinate
}

fn pkey_from_okp_key(cose_key: &CoseKey) -> Result<PKey<Public>> {
    ensure!(cose_key.kty == KeyType::Assigned(iana::KeyType::OKP));
    ensure!(cose_key.alg == Some(Algorithm::Assigned(iana::Algorithm::EdDSA)));
    let crv = get_label_value(cose_key, Label::Int(iana::OkpKeyParameter::Crv.to_i64()))?;
    let x = get_label_value_as_bytes(cose_key, Label::Int(iana::OkpKeyParameter::X.to_i64()))?;
    ensure!(crv == &Value::from(iana::EllipticCurve::Ed25519.to_i64()));
    PKey::public_key_from_raw_bytes(x, Id::ED25519).context("Failed to instantiate key")
}

fn pkey_from_ec2_key(cose_key: &CoseKey) -> Result<PKey<Public>> {
    ensure!(cose_key.kty == KeyType::Assigned(iana::KeyType::EC2));
    let crv = get_label_value(cose_key, Label::Int(iana::Ec2KeyParameter::Crv.to_i64()))?;
    let x = get_label_value_as_bytes(cose_key, Label::Int(iana::Ec2KeyParameter::X.to_i64()))?;
    let y = get_label_value_as_bytes(cose_key, Label::Int(iana::Ec2KeyParameter::Y.to_i64()))?;
    match cose_key.alg {
        Some(Algorithm::Assigned(iana::Algorithm::ES256)) => {
            ensure!(crv == &Value::from(iana::EllipticCurve::P_256.to_i64()));
            pkey_from_ec_coords(Nid::X9_62_PRIME256V1, x, y).context("Failed to instantiate key")
        }
        Some(Algorithm::Assigned(iana::Algorithm::ES384)) => {
            ensure!(crv == &Value::from(iana::EllipticCurve::P_384.to_i64()));
            pkey_from_ec_coords(Nid::SECP384R1, x, y).context("Failed to instantiate key")
        }
        _ => bail!("Need to specify ES256 or ES384 in the key. Got {:?}", cose_key.alg),
    }
}

fn pkey_from_ec_coords(nid: Nid, x: &[u8], y: &[u8]) -> Result<PKey<Public>> {
    let group = EcGroup::from_curve_name(nid).context("Failed to construct curve group")?;
    let x = BigNum::from_slice(x).context("Failed to create x coord")?;
    let y = BigNum::from_slice(y).context("Failed to create y coord")?;
    let key = EcKey::from_public_key_affine_coordinates(&group, &x, &y)
        .context("Failed to create EC public key")?;
    PKey::from_ec_key(key).context("Failed to create PKey")
}

/// Get the value corresponding to the provided label within the supplied CoseKey or error if it's
/// not present.
fn get_label_value(key: &CoseKey, label: Label) -> Result<&Value> {
    Ok(&key
        .params
        .iter()
        .find(|(k, _)| k == &label)
        .ok_or_else(|| anyhow!("Label {:?} not found", label))?
        .1)
}

/// Get the byte string for the corresponding label within the key if the label exists and the
/// value is actually a byte array.
fn get_label_value_as_bytes(key: &CoseKey, label: Label) -> Result<&[u8]> {
    get_label_value(key, label)?
        .as_bytes()
        .ok_or_else(|| anyhow!("Value not a bstr."))
        .map(Vec::as_slice)
}

fn ec_cose_signature_to_der(kind: EcKind, signature: &[u8]) -> Result<Vec<u8>> {
    let coord_len = ec_coord_len(kind);
    ensure!(signature.len() == coord_len * 2, "Unexpected signature length");
    let r = BigNum::from_slice(&signature[..coord_len]).context("Creating BigNum for r")?;
    let s = BigNum::from_slice(&signature[coord_len..]).context("Creating BigNum for s")?;
    let signature = EcdsaSig::from_private_components(r, s).context("Creating ECDSA signature")?;
    signature.to_der().context("Failed to DER encode signature")
}

fn ec_coord_len(kind: EcKind) -> usize {
    match kind {
        EcKind::P256 => 32,
        EcKind::P384 => 48,
    }
}

fn iana_algorithm(kind: Kind) -> iana::Algorithm {
    match kind {
        Kind::Ed25519 => iana::Algorithm::EdDSA,
        Kind::Ec(EcKind::P256) => iana::Algorithm::ES256,
        Kind::Ec(EcKind::P384) => iana::Algorithm::ES384,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::publickey::testkeys::{
        PrivateKey, ED25519_KEY_PEM, P256_KEY_PEM, P256_KEY_WITH_LEADING_ZEROS_PEM,
        P384_KEY_WITH_LEADING_ZEROS_PEM,
    };
    use coset::{CoseSign1Builder, HeaderBuilder};

    impl PrivateKey {
        pub(in crate::cbor) fn sign_cose_sign1(&self, payload: Vec<u8>) -> CoseSign1 {
            CoseSign1Builder::new()
                .protected(HeaderBuilder::new().algorithm(iana_algorithm(self.kind())).build())
                .payload(payload)
                .create_signature(b"", |m| {
                    let signature = self.sign(m).unwrap();
                    match self.kind() {
                        Kind::Ec(ec) => ec_der_signature_to_cose(ec, &signature),
                        _ => signature,
                    }
                })
                .build()
        }
    }

    fn ec_der_signature_to_cose(kind: EcKind, signature: &[u8]) -> Vec<u8> {
        let coord_len = ec_coord_len(kind).try_into().unwrap();
        let signature = EcdsaSig::from_der(signature).unwrap();
        let mut r = signature.r().to_vec_padded(coord_len).unwrap();
        let mut s = signature.s().to_vec_padded(coord_len).unwrap();
        r.append(&mut s);
        r
    }

    #[test]
    fn sign_and_verify_okp() {
        let key = PrivateKey::from_pem(ED25519_KEY_PEM[0]);
        let sign1 = key.sign_cose_sign1(b"signed payload".to_vec());
        key.public_key().verify_cose_sign1(&sign1).unwrap();
    }

    #[test]
    fn sign_and_verify_ec2() {
        let key = PrivateKey::from_pem(P256_KEY_PEM[0]);
        let sign1 = key.sign_cose_sign1(b"signed payload".to_vec());
        key.public_key().verify_cose_sign1(&sign1).unwrap();
    }

    #[test]
    fn verify_cose_sign1() {
        let key = PrivateKey::from_pem(ED25519_KEY_PEM[0]);
        let sign1 = CoseSign1Builder::new()
            .protected(HeaderBuilder::new().algorithm(iana::Algorithm::EdDSA).build())
            .payload(b"the message".to_vec())
            .create_signature(b"", |m| key.sign(m).unwrap())
            .build();
        key.public_key().verify_cose_sign1(&sign1).unwrap();
    }

    #[test]
    fn verify_cose_sign1_fails_with_wrong_algorithm() {
        let key = PrivateKey::from_pem(ED25519_KEY_PEM[0]);
        let sign1 = CoseSign1Builder::new()
            .protected(HeaderBuilder::new().algorithm(iana::Algorithm::ES256).build())
            .payload(b"the message".to_vec())
            .create_signature(b"", |m| key.sign(m).unwrap())
            .build();
        key.public_key().verify_cose_sign1(&sign1).unwrap_err();
    }

    #[test]
    fn verify_cose_sign1_with_non_crit_header() {
        let key = PrivateKey::from_pem(ED25519_KEY_PEM[0]);
        let sign1 = CoseSign1Builder::new()
            .protected(
                HeaderBuilder::new()
                    .algorithm(iana::Algorithm::EdDSA)
                    .value(1000, Value::from(2000))
                    .build(),
            )
            .payload(b"the message".to_vec())
            .create_signature(b"", |m| key.sign(m).unwrap())
            .build();
        key.public_key().verify_cose_sign1(&sign1).unwrap()
    }

    #[test]
    fn verify_cose_sign1_fails_with_crit_header() {
        let key = PrivateKey::from_pem(ED25519_KEY_PEM[0]);
        let sign1 = CoseSign1Builder::new()
            .protected(
                HeaderBuilder::new()
                    .algorithm(iana::Algorithm::EdDSA)
                    .add_critical(iana::HeaderParameter::Alg)
                    .build(),
            )
            .payload(b"the message".to_vec())
            .create_signature(b"", |m| key.sign(m).unwrap())
            .build();
        key.public_key().verify_cose_sign1(&sign1).unwrap_err();
    }

    #[test]
    fn to_and_from_okp_cose_key() {
        let key = PrivateKey::from_pem(ED25519_KEY_PEM[0]).public_key();
        let value = key.to_cose_key().unwrap();
        let new_key = PublicKey::from_cose_key(&value).unwrap();
        assert!(key.pkey().public_eq(new_key.pkey()));
    }

    #[test]
    fn to_and_from_ec2_cose_key() {
        let key = PrivateKey::from_pem(P256_KEY_PEM[0]).public_key();
        let value = key.to_cose_key().unwrap();
        let new_key = PublicKey::from_cose_key(&value).unwrap();
        assert!(key.pkey().public_eq(new_key.pkey()));
    }

    #[test]
    fn from_p256_pkey_with_leading_zeros() {
        for pem in P256_KEY_WITH_LEADING_ZEROS_PEM {
            let key = PrivateKey::from_pem(pem).public_key();
            let cose_key = key.to_cose_key().unwrap();

            let x =
                get_label_value_as_bytes(&cose_key, Label::Int(iana::Ec2KeyParameter::X.to_i64()))
                    .unwrap();
            assert_eq!(x.len(), 32, "X coordinate is the wrong size\n{}", pem);

            let y =
                get_label_value_as_bytes(&cose_key, Label::Int(iana::Ec2KeyParameter::Y.to_i64()))
                    .unwrap();
            assert_eq!(y.len(), 32, "Y coordinate is the wrong size\n{}", pem);
        }
    }

    #[test]
    fn from_p384_pkey_with_leading_zeros() {
        for pem in P384_KEY_WITH_LEADING_ZEROS_PEM {
            let key = PrivateKey::from_pem(pem).public_key();
            let cose_key = key.to_cose_key().unwrap();

            let x =
                get_label_value_as_bytes(&cose_key, Label::Int(iana::Ec2KeyParameter::X.to_i64()))
                    .unwrap();
            assert_eq!(x.len(), 48, "X coordinate is the wrong size\n{}", pem);

            let y =
                get_label_value_as_bytes(&cose_key, Label::Int(iana::Ec2KeyParameter::Y.to_i64()))
                    .unwrap();
            assert_eq!(y.len(), 48, "Y coordinate is the wrong size\n{}", pem);
        }
    }
}
