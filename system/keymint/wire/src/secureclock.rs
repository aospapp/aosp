//! Local types that are equivalent to those generated for the SecureClock HAL interface

use crate::{cbor_type_error, AsCborValue, CborError};
use alloc::{
    format,
    string::{String, ToString},
    vec::Vec,
};
use kmr_derive::AsCborValue;

pub const TIME_STAMP_MAC_LABEL: &[u8] = b"Auth Verification";

#[derive(Debug, Clone, Eq, Hash, PartialEq, AsCborValue)]
pub struct TimeStampToken {
    pub challenge: i64,
    pub timestamp: Timestamp,
    pub mac: Vec<u8>,
}

#[derive(Debug, Clone, Copy, Eq, Hash, Ord, PartialEq, PartialOrd, AsCborValue)]
pub struct Timestamp {
    pub milliseconds: i64,
}
