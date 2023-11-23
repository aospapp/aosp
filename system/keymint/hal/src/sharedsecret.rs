//! SharedSecret HAL device implementation.

use crate::binder;
use crate::hal::{
    sharedsecret::{ISharedSecret, SharedSecretParameters::SharedSecretParameters},
    Innto,
};
use crate::{ChannelHalService, SerializedChannel};
use kmr_wire::*;
use std::sync::{Arc, Mutex, MutexGuard};

/// `ISharedSecret` implementation which converts all method invocations to serialized requests that
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
    pub fn new_as_binder(
        channel: Arc<Mutex<T>>,
    ) -> binder::Strong<dyn ISharedSecret::ISharedSecret> {
        ISharedSecret::BnSharedSecret::new_binder(
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

impl<T: SerializedChannel> ISharedSecret::ISharedSecret for Device<T> {
    fn getSharedSecretParameters(&self) -> binder::Result<SharedSecretParameters> {
        let rsp: GetSharedSecretParametersResponse =
            self.execute(GetSharedSecretParametersRequest {})?;
        Ok(rsp.ret.innto())
    }
    fn computeSharedSecret(&self, params: &[SharedSecretParameters]) -> binder::Result<Vec<u8>> {
        let rsp: ComputeSharedSecretResponse =
            self.execute(ComputeSharedSecretRequest { params: params.to_vec().innto() })?;
        Ok(rsp.ret)
    }
}
