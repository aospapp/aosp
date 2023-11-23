//! TA functionality for secure clocks.

use alloc::vec::Vec;
use core::mem::size_of;
use kmr_common::{km_err, vec_try_with_capacity, Error};
use kmr_wire::secureclock::{TimeStampToken, TIME_STAMP_MAC_LABEL};

impl<'a> crate::KeyMintTa<'a> {
    pub(crate) fn generate_timestamp(&self, challenge: i64) -> Result<TimeStampToken, Error> {
        if let Some(clock) = &self.imp.clock {
            let mut ret =
                TimeStampToken { challenge, timestamp: clock.now().into(), mac: Vec::new() };
            let mac_input = self.dev.keys.timestamp_token_mac_input(&ret)?;
            ret.mac = self.device_hmac(&mac_input)?;
            Ok(ret)
        } else {
            Err(km_err!(Unimplemented, "no clock available"))
        }
    }
}

/// Build the HMAC input for a [`TimeStampToken`]
pub fn timestamp_token_mac_input(token: &TimeStampToken) -> Result<Vec<u8>, Error> {
    let mut result = vec_try_with_capacity!(
        TIME_STAMP_MAC_LABEL.len() +
        size_of::<i64>() + // challenge (BE)
        size_of::<i64>() + // timestamp (BE)
        size_of::<u32>() // 1u32 (BE)
    )?;
    result.extend_from_slice(TIME_STAMP_MAC_LABEL);
    result.extend_from_slice(&token.challenge.to_be_bytes()[..]);
    result.extend_from_slice(&token.timestamp.milliseconds.to_be_bytes()[..]);
    result.extend_from_slice(&1u32.to_be_bytes()[..]);
    Ok(result)
}
