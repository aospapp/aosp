//! SecureClock HAL device implementation.

use super::{ChannelHalService, SerializedChannel};
use crate::binder;
use crate::hal::secureclock::{ISecureClock, TimeStampToken::TimeStampToken};
use crate::hal::Innto;
use kmr_wire::*;
use std::sync::{Arc, Mutex, MutexGuard};

/// `ISecureClock` implementation which converts all method invocations to serialized requests that
/// are sent down the associated channel.
pub struct Device<T: SerializedChannel + 'static> {
    channel: Arc<Mutex<T>>,
}

impl<T: SerializedChannel + Send> binder::Interface for Device<T> {}

impl<T: SerializedChannel + 'static> Device<T> {
    /// Construct a new instance that uses the provided channel.
    pub fn new(channel: Arc<Mutex<T>>) -> Self {
        Self { channel }
    }
    /// Create a new instance wrapped in a proxy object.
    pub fn new_as_binder(channel: Arc<Mutex<T>>) -> binder::Strong<dyn ISecureClock::ISecureClock> {
        ISecureClock::BnSecureClock::new_binder(
            Self::new(channel),
            binder::BinderFeatures::default(),
        )
    }
}

impl<T: SerializedChannel> ChannelHalService<T> for Device<T> {
    fn channel(&self) -> MutexGuard<T> {
        self.channel.lock().unwrap()
    }
}

impl<T: SerializedChannel> ISecureClock::ISecureClock for Device<T> {
    fn generateTimeStamp(&self, challenge: i64) -> binder::Result<TimeStampToken> {
        let rsp: GenerateTimeStampResponse =
            self.execute(GenerateTimeStampRequest { challenge })?;
        Ok(rsp.ret.innto())
    }
}
