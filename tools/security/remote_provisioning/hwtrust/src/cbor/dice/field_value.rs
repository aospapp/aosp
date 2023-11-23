//! This module defines a helper for parsing fields in a CBOR map.

use anyhow::{anyhow, bail, Result};
use coset::cbor::value::Value;

pub(super) struct FieldValue {
    name: &'static str,
    value: Option<Value>,
}

impl FieldValue {
    pub fn new(name: &'static str) -> Self {
        Self { name, value: None }
    }

    pub fn set(&mut self, value: Value) -> Result<()> {
        if let Some(existing) = &self.value {
            bail!("Duplicate values for {}: {:?} and {:?}", self.name, existing, value);
        } else {
            self.value = Some(value);
            Ok(())
        }
    }

    pub fn is_bytes(&self) -> bool {
        self.value.as_ref().map_or(false, |v| v.is_bytes())
    }

    pub fn into_optional_bytes(self) -> Result<Option<Vec<u8>>> {
        self.value
            .map(|v| {
                if let Value::Bytes(b) = v {
                    Ok(b)
                } else {
                    bail!("{}: expected bytes, got {:?}", self.name, v)
                }
            })
            .transpose()
    }

    pub fn into_bytes(self) -> Result<Vec<u8>> {
        require_present(self.name, self.into_optional_bytes())
    }

    pub fn into_optional_string(self) -> Result<Option<String>> {
        self.value
            .map(|v| {
                if let Value::Text(s) = v {
                    Ok(s)
                } else {
                    bail!("{}: expected text, got {:?}", self.name, v)
                }
            })
            .transpose()
    }

    pub fn into_string(self) -> Result<String> {
        require_present(self.name, self.into_optional_string())
    }

    pub fn is_null(&self) -> Result<bool> {
        // If there's no value, return false; if there is a null value, return true; anything else
        // is an error.
        self.value
            .as_ref()
            .map(|v| {
                if *v == Value::Null {
                    Ok(true)
                } else {
                    bail!("{}: expected null, got {:?}", self.name, v)
                }
            })
            .unwrap_or(Ok(false))
    }

    pub fn is_integer(&self) -> bool {
        self.value.as_ref().map_or(false, |v| v.is_integer())
    }

    pub fn into_optional_i64(self) -> Result<Option<i64>> {
        self.value
            .map(|v| {
                let value =
                    if let Value::Integer(i) = v { i128::from(i).try_into().ok() } else { None };
                value.ok_or_else(|| anyhow!("{}: expected integer, got {:?}", self.name, v))
            })
            .transpose()
    }
}

fn require_present<T>(name: &'static str, value: Result<Option<T>>) -> Result<T> {
    value.and_then(|opt| opt.ok_or_else(|| anyhow!("{} must be present", name)))
}
