use super::*;

// Inject the BoringSSL-based implementations of crypto traits into the smoke tests from
// `kmr_tests`.

#[test]
fn test_rng() {
    let mut rng = rng::BoringRng::default();
    kmr_tests::test_rng(&mut rng);
}

#[test]
fn test_eq() {
    let comparator = eq::BoringEq;
    kmr_tests::test_eq(comparator);
}

#[test]
fn test_hkdf() {
    kmr_tests::test_hkdf(hmac::BoringHmac {});
}

#[test]
fn test_hmac() {
    kmr_tests::test_hmac(hmac::BoringHmac {});
}

#[cfg(soong)]
#[test]
fn test_aes_cmac() {
    kmr_tests::test_aes_cmac(aes_cmac::BoringAesCmac {});
}

#[cfg(soong)]
#[test]
fn test_ckdf() {
    kmr_tests::test_ckdf(aes_cmac::BoringAesCmac {});
}

#[test]
fn test_aes_gcm() {
    kmr_tests::test_aes_gcm(aes::BoringAes {});
}

#[test]
fn test_des() {
    kmr_tests::test_des(des::BoringDes {});
}
