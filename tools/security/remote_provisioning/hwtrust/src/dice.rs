//! This module provides functions for handling DICE chains.

mod chain;
mod entry;

pub use chain::{Chain, ChainForm, DegenerateChain};
pub use entry::{ComponentVersion, ConfigDesc, DiceMode, Payload};
pub(crate) use entry::{ConfigDescBuilder, PayloadBuilder};
