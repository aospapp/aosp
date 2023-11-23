// Copyright 2022 the authors.
// This project is dual-licensed under Apache 2.0 and MIT terms.
// See LICENSE-APACHE and LICENSE-MIT for details.

//! PSCI error codes.

pub use crate::smccc::error::SUCCESS;
use core::fmt::{self, Display, Formatter};

pub const NOT_SUPPORTED: i32 = -1;
pub const INVALID_PARAMETERS: i32 = -2;
pub const DENIED: i32 = -3;
pub const ALREADY_ON: i32 = -4;
pub const ON_PENDING: i32 = -5;
pub const INTERNAL_FAILURE: i32 = -6;
pub const NOT_PRESENT: i32 = -7;
pub const DISABLED: i32 = -8;
pub const INVALID_ADDRESS: i32 = -9;

/// Standard PSCI errors.
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
pub enum Error {
    /// PSCI call not supported.
    NotSupported,
    /// Invalid parameters to PSCI call.
    InvalidParameters,
    /// PSCI call denied.
    Denied,
    /// Core already on.
    AlreadyOn,
    /// Core already being turned on.
    OnPending,
    /// Internal failure in PSCI call.
    InternalFailure,
    /// Trusted OS not present on target core.
    NotPresent,
    /// Core disabled.
    Disabled,
    /// Invalid address passed to PSCI call.
    InvalidAddress,
    /// An unexpected return value from a PSCI function.
    Unknown(i32),
}

impl From<Error> for i32 {
    fn from(error: Error) -> i32 {
        match error {
            Error::NotSupported => NOT_SUPPORTED,
            Error::InvalidParameters => INVALID_PARAMETERS,
            Error::Denied => DENIED,
            Error::AlreadyOn => ALREADY_ON,
            Error::OnPending => ON_PENDING,
            Error::InternalFailure => INTERNAL_FAILURE,
            Error::NotPresent => NOT_PRESENT,
            Error::Disabled => DISABLED,
            Error::InvalidAddress => INVALID_ADDRESS,
            Error::Unknown(value) => value,
        }
    }
}

impl From<Error> for i64 {
    fn from(error: Error) -> i64 {
        i32::from(error).into()
    }
}

impl From<i32> for Error {
    fn from(value: i32) -> Self {
        match value {
            NOT_SUPPORTED => Error::NotSupported,
            INVALID_PARAMETERS => Error::InvalidParameters,
            DENIED => Error::Denied,
            ALREADY_ON => Error::AlreadyOn,
            ON_PENDING => Error::OnPending,
            INTERNAL_FAILURE => Error::InternalFailure,
            NOT_PRESENT => Error::NotPresent,
            DISABLED => Error::Disabled,
            INVALID_ADDRESS => Error::InvalidAddress,
            _ => Error::Unknown(value),
        }
    }
}

impl From<i64> for Error {
    fn from(value: i64) -> Self {
        Self::from(value as i32)
    }
}

impl Display for Error {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        match self {
            Self::NotSupported => write!(f, "PSCI call not supported"),
            Self::InvalidParameters => write!(f, "Invalid parameters to PSCI call"),
            Self::Denied => write!(f, "PSCI call denied"),
            Self::AlreadyOn => write!(f, "Core already on"),
            Self::OnPending => write!(f, "Core already being turned on"),
            Self::InternalFailure => write!(f, "Internal failure in PSCI call"),
            Self::NotPresent => write!(f, "Trusted OS not present on target core"),
            Self::Disabled => write!(f, "Core disabled"),
            Self::InvalidAddress => write!(f, "Invalid address passed to PSCI call"),
            Self::Unknown(e) => write!(f, "Unknown PSCI return value {} ({0:#x})", e),
        }
    }
}
