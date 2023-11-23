//! Functionality for KeyMint implementation that is common across HAL and TA.

#![no_std]
extern crate alloc;

use alloc::{
    string::{String, ToString},
    vec::Vec,
};
use core::convert::From;
use der::ErrorKind;
use kmr_wire::{cbor, keymint::ErrorCode, rpc, CborError};

pub use kmr_wire as wire;

pub mod crypto;
pub mod keyblob;
pub mod tag;

/// General error type.
#[derive(Debug)]
pub enum Error {
    Cbor(CborError),
    Der(ErrorKind),
    // The IKeyMintDevice, ISharedSecret and ISecureClock HALs all share the same numbering
    // space for error codes, encoded here as [`kmr_wire::keymint::ErrorCode`].
    Hal(ErrorCode, String),
    // The IRemotelyProvisionedComponent HAL uses its own error codes.
    Rpc(rpc::ErrorCode, String),
    // For an allocation error, hold a string literal rather than an allocated String to
    // avoid allocating in error path.
    Alloc(&'static str),
}

// The following macros for error generation allow the message portion to be automatically
// compiled out in future, avoiding potential information leakage and allocation.

/// Macro to build an [`Error::Hal`] instance for a specific [`ErrorCode`] value known at compile
/// time: `km_err!(InvalidTag, "some {} format", arg)`.
#[macro_export]
macro_rules! km_err {
    { $error_code:ident, $($arg:tt)+ } => {
        $crate::Error::Hal(kmr_wire::keymint::ErrorCode::$error_code,
                           alloc::format!("{}:{}: {}", file!(), line!(), format_args!($($arg)+))) };
}

/// Macro to build an [`Error::Hal`] instance:
/// `km_verr!(rc, "some {} format", arg)`.
#[macro_export]
macro_rules! km_verr {
    { $error_code:expr, $($arg:tt)+ } => {
        $crate::Error::Hal($error_code,
                           alloc::format!("{}:{}: {}", file!(), line!(), format_args!($($arg)+))) };
}

/// Macro to build an [`Error::Alloc`] instance. Note that this builds a `&'static str` at compile
/// time, so there is no allocation needed for the message (which would be failure-prone when
/// dealing with an allocation failure).
#[macro_export]
macro_rules! alloc_err {
    { $len:expr } => {
        $crate::Error::Alloc(
            concat!(file!(), ":", line!(), ": failed allocation of size ", stringify!($len))
        )
    }
}

/// Macro to build an [`Error::Rpc`] instance for a specific [`rpc::ErrorCode`] value known at
/// compile time: `rpc_err!(Removed, "some {} format", arg)`.
#[macro_export]
macro_rules! rpc_err {
    { $error_code:ident, $($arg:tt)+ } => {
        $crate::Error::Rpc(kmr_wire::rpc::ErrorCode::$error_code,
                           alloc::format!("{}:{}: {}", file!(), line!(), format_args!($($arg)+))) };
}

/// Macro to allocate a `Vec<T>` with the given length reserved, detecting allocation failure.
#[macro_export]
macro_rules! vec_try_with_capacity {
    { $len:expr} => {
        {
            let mut v = alloc::vec::Vec::new();
            match v.try_reserve($len) {
                Err(_e) => Err($crate::alloc_err!($len)),
                Ok(_) => Ok(v),
            }
        }
    }
}

/// Macro that mimics `vec!` but which detects allocation failure.
#[macro_export]
macro_rules! vec_try {
    { $elem:expr ; $len:expr } => {
        kmr_wire::vec_try_fill_with_alloc_err($elem, $len, || $crate::alloc_err!($len))
    };
    { $x1:expr, $x2:expr, $x3:expr, $x4:expr $(,)? } => {
        kmr_wire::vec_try4_with_alloc_err($x1, $x2, $x3, $x4, || $crate::alloc_err!(4))
    };
    { $x1:expr, $x2:expr, $x3:expr $(,)? } => {
        kmr_wire::vec_try3_with_alloc_err($x1, $x2, $x3, || $crate::alloc_err!(3))
    };
    { $x1:expr, $x2:expr $(,)? } => {
        kmr_wire::vec_try2_with_alloc_err($x1, $x2, || $crate::alloc_err!(2))
    };
    { $x1:expr $(,)? } => {
        kmr_wire::vec_try1_with_alloc_err($x1, || $crate::alloc_err!(1))
    };
}

/// Function that mimics `slice.to_vec()` but which detects allocation failures.
#[inline]
pub fn try_to_vec<T: Clone>(s: &[T]) -> Result<Vec<T>, Error> {
    let mut v = vec_try_with_capacity!(s.len())?;
    v.extend_from_slice(s);
    Ok(v)
}

/// Extension trait to provide fallible-allocation variants of `Vec` methods.
pub trait FallibleAllocExt<T> {
    fn try_push(&mut self, value: T) -> Result<(), alloc::collections::TryReserveError>;
    fn try_extend_from_slice(
        &mut self,
        other: &[T],
    ) -> Result<(), alloc::collections::TryReserveError>
    where
        T: Clone;
}

impl<T> FallibleAllocExt<T> for Vec<T> {
    fn try_push(&mut self, value: T) -> Result<(), alloc::collections::TryReserveError> {
        self.try_reserve(1)?;
        self.push(value);
        Ok(())
    }
    fn try_extend_from_slice(
        &mut self,
        other: &[T],
    ) -> Result<(), alloc::collections::TryReserveError>
    where
        T: Clone,
    {
        self.try_reserve(other.len())?;
        self.extend_from_slice(other);
        Ok(())
    }
}

impl From<alloc::collections::TryReserveError> for Error {
    fn from(_e: alloc::collections::TryReserveError) -> Self {
        Error::Hal(
            kmr_wire::keymint::ErrorCode::MemoryAllocationFailed,
            "allocation of Vec failed".to_string(),
        )
    }
}

impl From<CborError> for Error {
    fn from(e: CborError) -> Self {
        Error::Cbor(e)
    }
}

impl From<cbor::value::Error> for Error {
    fn from(e: cbor::value::Error) -> Self {
        Self::Cbor(e.into())
    }
}

impl From<der::Error> for Error {
    fn from(e: der::Error) -> Self {
        Error::Der(e.kind())
    }
}

/// Check for an expected error.
#[macro_export]
macro_rules! expect_err {
    ($result:expr, $err_msg:expr) => {
        assert!(
            $result.is_err(),
            "Expected error containing '{}', got success {:?}",
            $err_msg,
            $result
        );
        let err = $result.err();
        assert!(
            alloc::format!("{:?}", err).contains($err_msg),
            "Unexpected error {:?}, doesn't contain '{}'",
            err,
            $err_msg
        );
    };
}
