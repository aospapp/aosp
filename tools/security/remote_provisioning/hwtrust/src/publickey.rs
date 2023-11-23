//! This module describes a public key that is restricted to one of the supported algorithms.

use anyhow::{ensure, Context, Result};
use openssl::hash::MessageDigest;
use openssl::nid::Nid;
use openssl::pkey::{HasParams, Id, PKey, PKeyRef, Public};
use openssl::sign::Verifier;
use std::error::Error;
use std::fmt;

/// Enumeration of the kinds of key that are supported.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Kind {
    Ed25519,
    Ec(EcKind),
}

/// Enumeration of the kinds of elliptic curve keys that are supported.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum EcKind {
    P256,
    P384,
}

/// Struct wrapping the public key and relevant validation methods.
#[derive(Clone, Debug)]
pub struct PublicKey {
    kind: Kind,
    pkey: PKey<Public>,
}

impl PublicKey {
    pub(crate) fn kind(&self) -> Kind {
        self.kind
    }

    pub(crate) fn pkey(&self) -> &PKeyRef<Public> {
        &self.pkey
    }

    /// Verify that the signature obtained from signing the given message
    /// with the PublicKey matches the signature provided.
    pub fn verify(&self, signature: &[u8], message: &[u8]) -> Result<()> {
        let mut verifier = match self.kind {
            Kind::Ed25519 => Verifier::new_without_digest(&self.pkey),
            Kind::Ec(ec) => Verifier::new(digest_for_ec(ec), &self.pkey),
        }
        .with_context(|| format!("Failed to create verifier {:?}", self.kind))?;
        let verified =
            verifier.verify_oneshot(signature, message).context("Failed to verify signature")?;
        ensure!(verified, "Signature verification failed.");
        Ok(())
    }

    /// Serializes the public key into a PEM-encoded SubjectPublicKeyInfo structure.
    pub fn to_pem(&self) -> String {
        String::from_utf8(self.pkey.public_key_to_pem().unwrap()).unwrap()
    }
}

/// The error type returned when converting from [`PKey'] to [`PublicKey`] fails.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TryFromPKeyError(());

impl fmt::Display for TryFromPKeyError {
    fn fmt(&self, fmt: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(fmt, "unsupported public key conversion attempted")
    }
}

impl Error for TryFromPKeyError {}

impl TryFrom<PKey<Public>> for PublicKey {
    type Error = TryFromPKeyError;

    fn try_from(pkey: PKey<Public>) -> Result<Self, Self::Error> {
        let kind = pkey_kind(&pkey).ok_or(TryFromPKeyError(()))?;
        Ok(Self { kind, pkey })
    }
}

fn pkey_kind<T: HasParams>(pkey: &PKeyRef<T>) -> Option<Kind> {
    match pkey.id() {
        Id::ED25519 => Some(Kind::Ed25519),
        Id::EC => match pkey.ec_key().unwrap().group().curve_name() {
            Some(Nid::X9_62_PRIME256V1) => Some(Kind::Ec(EcKind::P256)),
            Some(Nid::SECP384R1) => Some(Kind::Ec(EcKind::P384)),
            _ => None,
        },
        _ => None,
    }
}

fn digest_for_ec(ec: EcKind) -> MessageDigest {
    match ec {
        EcKind::P256 => MessageDigest::sha256(),
        EcKind::P384 => MessageDigest::sha384(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn from_ed25519_pkey() {
        let pkey = load_public_pkey(testkeys::ED25519_KEY_PEM[0]);
        let key: PublicKey = pkey.clone().try_into().unwrap();
        assert_eq!(key.to_pem().as_bytes(), pkey.public_key_to_pem().unwrap());
    }

    #[test]
    fn from_p256_pkey() {
        let pkey = load_public_pkey(testkeys::P256_KEY_PEM[0]);
        let key: PublicKey = pkey.clone().try_into().unwrap();
        assert_eq!(key.to_pem().as_bytes(), pkey.public_key_to_pem().unwrap());
    }

    #[test]
    fn from_p384_pkey() {
        let pkey = load_public_pkey(testkeys::P384_KEY_PEM[0]);
        let key: PublicKey = pkey.clone().try_into().unwrap();
        assert_eq!(key.to_pem().as_bytes(), pkey.public_key_to_pem().unwrap());
    }

    #[test]
    fn from_p521_pkey_not_supported() {
        let pkey = load_public_pkey(testkeys::P521_KEY_PEM[0]);
        assert!(PublicKey::try_from(pkey).is_err());
    }

    #[test]
    fn from_rsa2048_pkey_not_supported() {
        let pkey = load_public_pkey(testkeys::RSA2048_KEY_PEM[0]);
        assert!(PublicKey::try_from(pkey).is_err());
    }

    pub fn load_public_pkey(pem: &str) -> PKey<Public> {
        testkeys::public_from_private(&PKey::private_key_from_pem(pem.as_bytes()).unwrap())
    }
}

/// Keys and key handling utilities for use in tests.
#[cfg(test)]
pub(crate) mod testkeys {
    use super::*;
    use openssl::pkey::Private;
    use openssl::sign::Signer;

    pub struct PrivateKey {
        kind: Kind,
        pkey: PKey<Private>,
    }

    impl PrivateKey {
        pub fn from_pem(pem: &str) -> Self {
            let pkey = PKey::private_key_from_pem(pem.as_bytes()).unwrap();
            let kind = pkey_kind(&pkey).expect("unsupported private key");
            Self { kind, pkey }
        }

        pub(crate) fn kind(&self) -> Kind {
            self.kind
        }

        pub fn public_key(&self) -> PublicKey {
            public_from_private(&self.pkey).try_into().unwrap()
        }

        pub fn sign(&self, message: &[u8]) -> Result<Vec<u8>> {
            let mut signer = match self.kind {
                Kind::Ed25519 => Signer::new_without_digest(&self.pkey)?,
                Kind::Ec(ec) => Signer::new(digest_for_ec(ec), &self.pkey)?,
            };
            signer.sign_oneshot_to_vec(message).context("signing message")
        }
    }

    /// Gives the public key that matches the private key.
    pub fn public_from_private(pkey: &PKey<Private>) -> PKey<Public> {
        // It feels like there should be a more direct way to do this but I haven't found it.
        PKey::public_key_from_der(&pkey.public_key_to_der().unwrap()).unwrap()
    }

    /// A selection of Ed25519 private keys.
    pub const ED25519_KEY_PEM: &[&str] = &["-----BEGIN PRIVATE KEY-----\n\
        MC4CAQAwBQYDK2VwBCIEILKW0KEeuieFxhDAzigQPE4XRTiQx+0/AlAjJqHmUWE6\n\
        -----END PRIVATE KEY-----\n"];

    /// A selection of elliptic curve P-256 private keys.
    pub const P256_KEY_PEM: &[&str] = &[
        "-----BEGIN PRIVATE KEY-----\n\
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg+CO3ZBuAsimwPKAL\n\
        IeDyCh4cRZ5EMd6llGu5MQCpibGhRANCAAQObPxc4bIPjupILrvKJjTrpTcyCf6q\n\
        V552FlS67fGphwhg2LDfQ8adEdkuRfQvk+IvKJz8MDcPjErBG3Wlps1N\n\
        -----END PRIVATE KEY-----\n",
        "-----BEGIN PRIVATE KEY-----\n\
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgw1OPIcfQv5twO68B\n\
        H+xNstW3DLXC6e4PGEYG/VppYVahRANCAAQMyWyv4ffVMu+wVNhNEk2mQSaTmSl/\n\
        dLdRbEowfqPwMzdqdQ3QlKSV4ZcU2lsJEuQMkZzmVPz02enY2qcKctmj\n\
        -----END PRIVATE KEY-----\n",
        "-----BEGIN PRIVATE KEY-----\n\
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgbXO6ee7i7sY4YfFS\n\
        Gn60ScPuL3QuYFMX4nJbcqPSQ7+hRANCAAS8i9xA8cIcWStbMG97YrttQsYEIR2a\n\
        15+alxbb6b7422FuxBB0qG5nJ4m+Jd3Bp+N2lwx4rHBFDqU4cp8VlQav\n\
        -----END PRIVATE KEY-----\n",
        "-----BEGIN PRIVATE KEY-----\n\
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg/JuxkbpPyyouat11\n\
        szDR+OA7d/fuMk9IhGkH7z1xHzChRANCAASRlY0D7Uh5T/FmB6txGr21w6jqKW2x\n\
        RXdsaZgCB6XnrXlkgkvuWDc0CTLSBWdPFgW6OX0fyXViglEBH95REyQr\n\
        -----END PRIVATE KEY-----\n",
    ];

    /// A selection of EC keys that should have leading zeros in their coordinates
    pub const P256_KEY_WITH_LEADING_ZEROS_PEM: &[&str] = &[
        // 31 byte Y coordinate:
        "-----BEGIN PRIVATE KEY-----\n\
        MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCCWbRSB3imI03F5YNVq\n\
        8AN8ZbyzW/h+5BQ53caD5VkWJg==\n\
        -----END PRIVATE KEY-----\n",
        // 31 byte X coordinate:
        "-----BEGIN PRIVATE KEY-----\n\
        MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDe5E5WqNmCLxtsCNTc\n\
        UOb9CPXCn6l3CZpbrp0aivb+Bw==\n\
        -----END PRIVATE KEY-----\n",
        // X & Y both have MSB set, and some stacks will add a padding byte
        "-----BEGIN PRIVATE KEY-----\n\
        MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCCWOWcXPDEVZ4Qz3EBK\n\
        uvSqhD9HmxDGxcNe3yxKi9pazw==\n\
        -----END PRIVATE KEY-----\n",
    ];

    /// A selection of elliptic curve P-384 private keys.
    pub const P384_KEY_PEM: &[&str] = &["-----BEGIN PRIVATE KEY-----\n\
        MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDBMZ414LiUpcuNTNq5W\n\
        Ig/qbnbFn0MpuZZxUn5YZ8/+2/tyXFFHRyQoQ4YpNN1P/+qhZANiAAScPDyisb21\n\
        GldmGksI5g82hjPRYscWNs/6pFxQTMcxABE+/1lWaryLR193ZD74VxVRIKDBluRs\n\
        uuHi+VayOreTX1/qlUoxgBT+XTI0nTdLn6WwO6vVO1NIkGEVnYvB2eM=\n\
        -----END PRIVATE KEY-----\n"];

    /// A selection of EC keys that should have leading zeros in their coordinates
    pub const P384_KEY_WITH_LEADING_ZEROS_PEM: &[&str] = &[
        // 47 byte Y coordinate:
        "-----BEGIN PRIVATE KEY-----\n\
        ME4CAQAwEAYHKoZIzj0CAQYFK4EEACIENzA1AgEBBDCzgVHCz7wgmSdb7/IixYik\n\
        3AuQceCtBTiFrJpgpGFluwgLUR0S2NpzIuty4M7xU74=\n\
        -----END PRIVATE KEY-----\n",
        // 47 byte X coordinate:
        "-----BEGIN PRIVATE KEY-----\n\
        ME4CAQAwEAYHKoZIzj0CAQYFK4EEACIENzA1AgEBBDBoW+8zbvwf5fYOS8YPyPEH\n\
        jHP71Vr1MnRYRp/yG1wbthW2XEu0UWbp4qrZ5WTnZPg=\n\
        -----END PRIVATE KEY-----\n",
        // X & Y both have MSB set, and some stacks will add a padding byte
        "-----BEGIN PRIVATE KEY-----\n\
        ME4CAQAwEAYHKoZIzj0CAQYFK4EEACIENzA1AgEBBDD2A69j5M/6oc6/WGoYln4t\n\
        Alnn0C6kpJz1EVC+eH6y0YNrcGamz8pPY4NkzUB/tj4=\n\
        -----END PRIVATE KEY-----\n",
    ];

    /// A selection of elliptic curve P-521 private keys.
    pub const P521_KEY_PEM: &[&str] = &["-----BEGIN PRIVATE KEY-----\n\
        MIHuAgEAMBAGByqGSM49AgEGBSuBBAAjBIHWMIHTAgEBBEIBQuD8Db3jT2yPYR5t\n\
        Y1ZqESxOe4eBWzekFKg7cjVWsSiJEvWTPC1H7CLtXQBHZglO90dwMt4flL91xHkl\n\
        iZOzyHahgYkDgYYABAHACwmmKkZu01fp1QTTTQ0cv7IAfYv9FEBz8yfhNGPnI2WY\n\
        iH1/lYeCfYc9d33aSc/ELY9+vIFzVStJumS/B/WTewEhxVomlKPAkUJeLdCaK5av\n\
        nlUNj7pNQ/5v5FZVxmoFJvAtUAnZqnJqo/QkLtEnmKlzpLte2LuwTPZhG35z0HeL\n\
        2g==\n\
        -----END PRIVATE KEY-----\n"];

    /// A selection of 2048-bit RSA private keys.
    pub const RSA2048_KEY_PEM: &[&str] = &["-----BEGIN PRIVATE KEY-----\n\
        MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQDbOJh7Ys7CuIju\n\
        VVKMlFlZWwDEGBX5bVYD/xNBNNF1FY9bOcV/BG20IwoZkdV0N+vm8eWSuv/uwIJp\n\
        sN2PMWPAEIWbPGGMnSdePpkwrdpFFywhEQqUrfdCFXZ8zeF85Nz5mL8ysl4vlMsL\n\
        mbErCkrq++K0lzs+k7w/FtPCgs4M3WypJfZef5zM0CGWxpHZGoUGm0HW9fen4sv8\n\
        hTmMGNY/r0SJhdZREGmiGCx2v+ksOEBon1r/6QKVTP8S73XFsyNCWyop0hYTakut\n\
        D3HtJ5sWzu2RU8rrch3Txinz0jpGF8PATHk35YMw/9jwwwSqjDw+pOQcYk8SviAf\n\
        glZf8aZlAgMBAAECggEAAS67PK67tuaOWywJSWHLsWGmqJ4I2tZiTzCT9EZ2MOVx\n\
        +4ZChNfjHUsskXUp4XNL/FE0J3WvhEYjXR1u+L37nvqc48mJpjoPN7o/CMb6rM/J\n\
        +ly9A2ZOvEB4ppOYDYh5QVDm7/otmvEMzJxuUOpvxYxqnJpAPgl9dBpNQ0nSt3YX\n\
        jJS4+vuzQpwwSTfchpcCZYU3AX9DpQpxnrLX3/7d3GTs2NuedmSwRz+mCfwaOlFk\n\
        jdrJ2uJJrDLcK6yhSdsE9aNgKkmX6aNLhxbbCFTyDNiGY5HHayyL3mVvyaeovYcn\n\
        ZS+Z+0TJGCgXDRHHSFyIAsgVonxHfn49x9uvfpuMFQKBgQD2cVp26+aQgt46ajVv\n\
        yn4fxbNpfovL0pgtSjo7ekZOWYJ3Is1SDmnni8k1ViKgUYC210dTTlrljxUil8T0\n\
        83e03k2xasDi2c+h/7JFYJPDyZwIm1o19ciUwY73D54iJaRbrzEximFeA0h4LGKw\n\
        Yjd4xkKMJw16CU00gInyI193BwKBgQDjuP0/QEEPpYvzpag5Ul4+h22K/tiOUrFj\n\
        NuSgd+IvQG1hW48zHEa9vXvORQ/FteiQ708racz6ByqY+n2w6QdtdRMj7Hsyo2fk\n\
        SEeNaLrR7Sif6MfkYajbSGFySDD82vj4Jt76vzdt3MjpZfs6ryPmnKLVPWNA3mnS\n\
        4+u2J/+QMwKBgFfiJnugNnG0aaF1PKcoFAAqlYd6XEoMSL5l6QxK14WbP/5SR9wK\n\
        TdQHsnI1zFVVm0wYy1O27o1MkCHs84zSwg6a9CPfyPdc60F/GMjK3wcD/4PGOs5h\n\
        Xu1FdUE/rYnJ2KnleOqMyZooG5DXaz4xWEzWjubCCnlJleGyMP9LhADDAoGAR/jK\n\
        iXgcV/6haeMcdOl0gdy5oWmENg8qo0nRHmplYTvCljei3at9LDC79WhcYMdqdow8\n\
        AGOS9h7XtrvMh+JOh6it4Pe3xDxi9IJnoujLytditI+Uxbib7ppEuiLY4MGwWHWo\n\
        maVftmhGU4X4zgZWmWc+C5k4SmNBHPcOI2cm3YMCgYB5/Ni+tBxng0S/PRAtwCeG\n\
        dVnQnYvS2C5nHCn9D5rmAcVXUKrIJ1/1K4se8vQ15DDcpuBF1SejYTJzdUP8Zgcs\n\
        p8wVq7neK8uSsmG+AfUgxMjbetoAVTP3L8+GbjocznR9auB7BEjFVO25iYSiTp/w\n\
        NNzbIKQRDN+c3vUpneJcuw==\n\
        -----END PRIVATE KEY-----\n"];
}
