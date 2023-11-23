//! Local types that are equivalent to those generated for the SharedSecret HAL interface

use crate::{cbor_type_error, AsCborValue, CborError};
use alloc::{
    format,
    string::{String, ToString},
    vec::Vec,
};
use kmr_derive::AsCborValue;

pub const KEY_AGREEMENT_LABEL: &str = "KeymasterSharedMac";
pub const KEY_CHECK_LABEL: &str = "Keymaster HMAC Verification";

#[derive(Debug, Clone, Eq, Hash, PartialEq, Default, AsCborValue)]
pub struct SharedSecretParameters {
    pub seed: Vec<u8>,
    pub nonce: Vec<u8>,
}
