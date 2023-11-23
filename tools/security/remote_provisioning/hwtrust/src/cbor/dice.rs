//! Parsing and encoding DICE chain from and to CBOR.

use crate::cbor::cose_error;
use crate::session::{KeyOpsType, Session};
use anyhow::Result;
use ciborium::value::Value;
use coset::iana::{self, EnumI64};
use coset::{AsCborValue, CoseKey, Label};

mod chain;
mod entry;
mod field_value;

/// Convert a `Value` into a `CoseKey`, respecting the `Session` options that might alter the
/// validation rules for `CoseKey`s in the DICE chain.
fn cose_key_from_cbor_value(session: &Session, mut value: Value) -> Result<CoseKey> {
    if session.options.dice_chain_key_ops_type == KeyOpsType::IntOrArray {
        // Convert any integer key_ops into an array of the same integer so that the coset library
        // can handle it.
        if let Value::Map(ref mut entries) = value {
            for (label, value) in entries.iter_mut() {
                let label = Label::from_cbor_value(label.clone()).map_err(cose_error)?;
                if label == Label::Int(iana::KeyParameter::KeyOps.to_i64()) && value.is_integer() {
                    *value = Value::Array(vec![value.clone()]);
                }
            }
        }
    }
    CoseKey::from_cbor_value(value).map_err(cose_error)
}
