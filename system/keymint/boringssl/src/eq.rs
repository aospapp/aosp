use kmr_common::crypto;

/// Constant time comparator based on BoringSSL.
#[derive(Clone)]
pub struct BoringEq;

impl crypto::ConstTimeEq for BoringEq {
    fn eq(&self, left: &[u8], right: &[u8]) -> bool {
        if left.len() != right.len() {
            return false;
        }
        openssl::memcmp::eq(left, right)
    }
}
