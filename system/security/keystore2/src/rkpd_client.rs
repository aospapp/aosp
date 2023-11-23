// Copyright 2022, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Helper wrapper around RKPD interface.

use crate::error::{map_binder_status_code, Error, ResponseCode};
use crate::globals::get_remotely_provisioned_component_name;
use crate::ks_err;
use crate::utils::watchdog as wd;
use android_hardware_security_keymint::aidl::android::hardware::security::keymint::SecurityLevel::SecurityLevel;
use android_security_rkp_aidl::aidl::android::security::rkp::{
    IGetKeyCallback::BnGetKeyCallback, IGetKeyCallback::ErrorCode::ErrorCode as GetKeyErrorCode,
    IGetKeyCallback::IGetKeyCallback, IGetRegistrationCallback::BnGetRegistrationCallback,
    IGetRegistrationCallback::IGetRegistrationCallback, IRegistration::IRegistration,
    IRemoteProvisioning::IRemoteProvisioning,
    IStoreUpgradedKeyCallback::BnStoreUpgradedKeyCallback,
    IStoreUpgradedKeyCallback::IStoreUpgradedKeyCallback,
    RemotelyProvisionedKey::RemotelyProvisionedKey,
};
use android_security_rkp_aidl::binder::{BinderFeatures, Interface, Strong};
use anyhow::{Context, Result};
use std::sync::Mutex;
use std::time::Duration;
use tokio::sync::oneshot;
use tokio::time::timeout;

// Normally, we block indefinitely when making calls outside of keystore and rely on watchdog to
// report deadlocks. However, RKPD is mainline updatable. Also, calls to RKPD may wait on network
// for certificates. So, we err on the side of caution and timeout instead.
static RKPD_TIMEOUT: Duration = Duration::from_secs(10);

fn tokio_rt() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap()
}

/// Thread-safe channel for sending a value once and only once. If a value has
/// already been send, subsequent calls to send will noop.
struct SafeSender<T> {
    inner: Mutex<Option<oneshot::Sender<T>>>,
}

impl<T> SafeSender<T> {
    fn new(sender: oneshot::Sender<T>) -> Self {
        Self { inner: Mutex::new(Some(sender)) }
    }

    fn send(&self, value: T) {
        if let Some(inner) = self.inner.lock().unwrap().take() {
            // It's possible for the corresponding receiver to time out and be dropped. In this
            // case send() will fail. This error is not actionable though, so only log the error.
            if inner.send(value).is_err() {
                log::error!("SafeSender::send() failed");
            }
        }
    }
}

struct GetRegistrationCallback {
    registration_tx: SafeSender<Result<binder::Strong<dyn IRegistration>>>,
}

impl GetRegistrationCallback {
    pub fn new_native_binder(
        registration_tx: oneshot::Sender<Result<binder::Strong<dyn IRegistration>>>,
    ) -> Strong<dyn IGetRegistrationCallback> {
        let result: Self =
            GetRegistrationCallback { registration_tx: SafeSender::new(registration_tx) };
        BnGetRegistrationCallback::new_binder(result, BinderFeatures::default())
    }
}

impl Interface for GetRegistrationCallback {}

impl IGetRegistrationCallback for GetRegistrationCallback {
    fn onSuccess(&self, registration: &Strong<dyn IRegistration>) -> binder::Result<()> {
        let _wp = wd::watch_millis("IGetRegistrationCallback::onSuccess", 500);
        self.registration_tx.send(Ok(registration.clone()));
        Ok(())
    }
    fn onCancel(&self) -> binder::Result<()> {
        let _wp = wd::watch_millis("IGetRegistrationCallback::onCancel", 500);
        log::warn!("IGetRegistrationCallback cancelled");
        self.registration_tx.send(
            Err(Error::Rc(ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR))
                .context(ks_err!("GetRegistrationCallback cancelled.")),
        );
        Ok(())
    }
    fn onError(&self, description: &str) -> binder::Result<()> {
        let _wp = wd::watch_millis("IGetRegistrationCallback::onError", 500);
        log::error!("IGetRegistrationCallback failed: '{description}'");
        self.registration_tx.send(
            Err(Error::Rc(ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR))
                .context(ks_err!("GetRegistrationCallback failed: {:?}", description)),
        );
        Ok(())
    }
}

/// Make a new connection to a IRegistration service.
async fn get_rkpd_registration(
    security_level: &SecurityLevel,
) -> Result<binder::Strong<dyn IRegistration>> {
    let remote_provisioning: Strong<dyn IRemoteProvisioning> =
        map_binder_status_code(binder::get_interface("remote_provisioning"))
            .context(ks_err!("Trying to connect to IRemoteProvisioning service."))?;

    let rpc_name = get_remotely_provisioned_component_name(security_level)
        .context(ks_err!("Trying to get IRPC name."))?;

    let (tx, rx) = oneshot::channel();
    let cb = GetRegistrationCallback::new_native_binder(tx);

    remote_provisioning
        .getRegistration(&rpc_name, &cb)
        .context(ks_err!("Trying to get registration."))?;

    match timeout(RKPD_TIMEOUT, rx).await {
        Err(e) => {
            Err(Error::Rc(ResponseCode::SYSTEM_ERROR)).context(ks_err!("Waiting for RKPD: {:?}", e))
        }
        Ok(v) => v.unwrap(),
    }
}

struct GetKeyCallback {
    key_tx: SafeSender<Result<RemotelyProvisionedKey>>,
}

impl GetKeyCallback {
    pub fn new_native_binder(
        key_tx: oneshot::Sender<Result<RemotelyProvisionedKey>>,
    ) -> Strong<dyn IGetKeyCallback> {
        let result: Self = GetKeyCallback { key_tx: SafeSender::new(key_tx) };
        BnGetKeyCallback::new_binder(result, BinderFeatures::default())
    }
}

impl Interface for GetKeyCallback {}

impl IGetKeyCallback for GetKeyCallback {
    fn onSuccess(&self, key: &RemotelyProvisionedKey) -> binder::Result<()> {
        let _wp = wd::watch_millis("IGetKeyCallback::onSuccess", 500);
        self.key_tx.send(Ok(RemotelyProvisionedKey {
            keyBlob: key.keyBlob.clone(),
            encodedCertChain: key.encodedCertChain.clone(),
        }));
        Ok(())
    }
    fn onCancel(&self) -> binder::Result<()> {
        let _wp = wd::watch_millis("IGetKeyCallback::onCancel", 500);
        log::warn!("IGetKeyCallback cancelled");
        self.key_tx.send(
            Err(Error::Rc(ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR))
                .context(ks_err!("GetKeyCallback cancelled.")),
        );
        Ok(())
    }
    fn onError(&self, error: GetKeyErrorCode, description: &str) -> binder::Result<()> {
        let _wp = wd::watch_millis("IGetKeyCallback::onError", 500);
        log::error!("IGetKeyCallback failed: {description}");
        let rc = match error {
            GetKeyErrorCode::ERROR_UNKNOWN => ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR,
            GetKeyErrorCode::ERROR_PERMANENT => ResponseCode::OUT_OF_KEYS_PERMANENT_ERROR,
            GetKeyErrorCode::ERROR_PENDING_INTERNET_CONNECTIVITY => {
                ResponseCode::OUT_OF_KEYS_PENDING_INTERNET_CONNECTIVITY
            }
            GetKeyErrorCode::ERROR_REQUIRES_SECURITY_PATCH => {
                ResponseCode::OUT_OF_KEYS_REQUIRES_SYSTEM_UPGRADE
            }
            _ => {
                log::error!("Unexpected error from rkpd: {error:?}");
                ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR
            }
        };
        self.key_tx.send(Err(Error::Rc(rc)).context(ks_err!(
            "GetKeyCallback failed: {:?} {:?}",
            error,
            description
        )));
        Ok(())
    }
}

async fn get_rkpd_attestation_key_from_registration_async(
    registration: &Strong<dyn IRegistration>,
    caller_uid: u32,
) -> Result<RemotelyProvisionedKey> {
    let (tx, rx) = oneshot::channel();
    let cb = GetKeyCallback::new_native_binder(tx);

    registration
        .getKey(caller_uid.try_into().unwrap(), &cb)
        .context(ks_err!("Trying to get key."))?;

    match timeout(RKPD_TIMEOUT, rx).await {
        Err(e) => {
            // Make a best effort attempt to cancel the timed out request.
            if let Err(e) = registration.cancelGetKey(&cb) {
                log::error!("IRegistration::cancelGetKey failed: {:?}", e);
            }
            Err(Error::Rc(ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR))
                .context(ks_err!("Waiting for RKPD key timed out: {:?}", e))
        }
        Ok(v) => v.unwrap(),
    }
}

async fn get_rkpd_attestation_key_async(
    security_level: &SecurityLevel,
    caller_uid: u32,
) -> Result<RemotelyProvisionedKey> {
    let registration = get_rkpd_registration(security_level)
        .await
        .context(ks_err!("Trying to get to IRegistration service."))?;
    get_rkpd_attestation_key_from_registration_async(&registration, caller_uid).await
}

struct StoreUpgradedKeyCallback {
    completer: SafeSender<Result<()>>,
}

impl StoreUpgradedKeyCallback {
    pub fn new_native_binder(
        completer: oneshot::Sender<Result<()>>,
    ) -> Strong<dyn IStoreUpgradedKeyCallback> {
        let result: Self = StoreUpgradedKeyCallback { completer: SafeSender::new(completer) };
        BnStoreUpgradedKeyCallback::new_binder(result, BinderFeatures::default())
    }
}

impl Interface for StoreUpgradedKeyCallback {}

impl IStoreUpgradedKeyCallback for StoreUpgradedKeyCallback {
    fn onSuccess(&self) -> binder::Result<()> {
        let _wp = wd::watch_millis("IGetRegistrationCallback::onSuccess", 500);
        self.completer.send(Ok(()));
        Ok(())
    }

    fn onError(&self, error: &str) -> binder::Result<()> {
        let _wp = wd::watch_millis("IGetRegistrationCallback::onError", 500);
        log::error!("IGetRegistrationCallback failed: {error}");
        self.completer.send(
            Err(Error::Rc(ResponseCode::SYSTEM_ERROR))
                .context(ks_err!("Failed to store upgraded key: {:?}", error)),
        );
        Ok(())
    }
}

async fn store_rkpd_attestation_key_with_registration_async(
    registration: &Strong<dyn IRegistration>,
    key_blob: &[u8],
    upgraded_blob: &[u8],
) -> Result<()> {
    let (tx, rx) = oneshot::channel();
    let cb = StoreUpgradedKeyCallback::new_native_binder(tx);

    registration
        .storeUpgradedKeyAsync(key_blob, upgraded_blob, &cb)
        .context(ks_err!("Failed to store upgraded blob with RKPD."))?;

    match timeout(RKPD_TIMEOUT, rx).await {
        Err(e) => Err(Error::Rc(ResponseCode::SYSTEM_ERROR))
            .context(ks_err!("Waiting for RKPD to complete storing key: {:?}", e)),
        Ok(v) => v.unwrap(),
    }
}

async fn store_rkpd_attestation_key_async(
    security_level: &SecurityLevel,
    key_blob: &[u8],
    upgraded_blob: &[u8],
) -> Result<()> {
    let registration = get_rkpd_registration(security_level)
        .await
        .context(ks_err!("Trying to get to IRegistration service."))?;
    store_rkpd_attestation_key_with_registration_async(&registration, key_blob, upgraded_blob).await
}

/// Get attestation key from RKPD.
pub fn get_rkpd_attestation_key(
    security_level: &SecurityLevel,
    caller_uid: u32,
) -> Result<RemotelyProvisionedKey> {
    let _wp = wd::watch_millis("Calling get_rkpd_attestation_key()", 500);
    tokio_rt().block_on(get_rkpd_attestation_key_async(security_level, caller_uid))
}

/// Store attestation key in RKPD.
pub fn store_rkpd_attestation_key(
    security_level: &SecurityLevel,
    key_blob: &[u8],
    upgraded_blob: &[u8],
) -> Result<()> {
    let _wp = wd::watch_millis("Calling store_rkpd_attestation_key()", 500);
    tokio_rt().block_on(store_rkpd_attestation_key_async(security_level, key_blob, upgraded_blob))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::map_km_error;
    use crate::globals::get_keymint_device;
    use crate::utils::upgrade_keyblob_if_required_with;
    use android_hardware_security_keymint::aidl::android::hardware::security::keymint::{
        Algorithm::Algorithm, AttestationKey::AttestationKey, KeyParameter::KeyParameter,
        KeyParameterValue::KeyParameterValue, Tag::Tag,
    };
    use android_security_rkp_aidl::aidl::android::security::rkp::IRegistration::BnRegistration;
    use keystore2_crypto::parse_subject_from_certificate;
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicU32, Ordering};
    use std::sync::{Arc, Mutex};

    struct MockRegistrationValues {
        key: RemotelyProvisionedKey,
        latency: Option<Duration>,
        thread_join_handles: Vec<Option<std::thread::JoinHandle<()>>>,
    }

    struct MockRegistration(Arc<Mutex<MockRegistrationValues>>);

    impl MockRegistration {
        pub fn new_native_binder(
            key: &RemotelyProvisionedKey,
            latency: Option<Duration>,
        ) -> Strong<dyn IRegistration> {
            let result = Self(Arc::new(Mutex::new(MockRegistrationValues {
                key: RemotelyProvisionedKey {
                    keyBlob: key.keyBlob.clone(),
                    encodedCertChain: key.encodedCertChain.clone(),
                },
                latency,
                thread_join_handles: Vec::new(),
            })));
            BnRegistration::new_binder(result, BinderFeatures::default())
        }
    }

    impl Drop for MockRegistration {
        fn drop(&mut self) {
            let mut values = self.0.lock().unwrap();
            for handle in values.thread_join_handles.iter_mut() {
                // These are test threads. So, no need to worry too much about error handling.
                handle.take().unwrap().join().unwrap();
            }
        }
    }

    impl Interface for MockRegistration {}

    impl IRegistration for MockRegistration {
        fn getKey(&self, _: i32, cb: &Strong<dyn IGetKeyCallback>) -> binder::Result<()> {
            let mut values = self.0.lock().unwrap();
            let key = RemotelyProvisionedKey {
                keyBlob: values.key.keyBlob.clone(),
                encodedCertChain: values.key.encodedCertChain.clone(),
            };
            let latency = values.latency;
            let get_key_cb = cb.clone();

            // Need a separate thread to trigger timeout in the caller.
            let join_handle = std::thread::spawn(move || {
                if let Some(duration) = latency {
                    std::thread::sleep(duration);
                }
                get_key_cb.onSuccess(&key).unwrap();
            });
            values.thread_join_handles.push(Some(join_handle));
            Ok(())
        }

        fn cancelGetKey(&self, _: &Strong<dyn IGetKeyCallback>) -> binder::Result<()> {
            Ok(())
        }

        fn storeUpgradedKeyAsync(
            &self,
            _: &[u8],
            _: &[u8],
            cb: &Strong<dyn IStoreUpgradedKeyCallback>,
        ) -> binder::Result<()> {
            // We are primarily concerned with timing out correctly. Storing the key in this mock
            // registration isn't particularly interesting, so skip that part.
            let values = self.0.lock().unwrap();
            let store_cb = cb.clone();
            let latency = values.latency;

            std::thread::spawn(move || {
                if let Some(duration) = latency {
                    std::thread::sleep(duration);
                }
                store_cb.onSuccess().unwrap();
            });
            Ok(())
        }
    }

    fn get_mock_registration(
        key: &RemotelyProvisionedKey,
        latency: Option<Duration>,
    ) -> Result<binder::Strong<dyn IRegistration>> {
        let (tx, rx) = oneshot::channel();
        let cb = GetRegistrationCallback::new_native_binder(tx);
        let mock_registration = MockRegistration::new_native_binder(key, latency);

        assert!(cb.onSuccess(&mock_registration).is_ok());
        tokio_rt().block_on(rx).unwrap()
    }

    // Using the same key ID makes test cases race with each other. So, we use separate key IDs for
    // different test cases.
    fn get_next_key_id() -> u32 {
        static ID: AtomicU32 = AtomicU32::new(0);
        ID.fetch_add(1, Ordering::Relaxed)
    }

    #[test]
    fn test_get_registration_cb_success() {
        let key: RemotelyProvisionedKey = Default::default();
        let registration = get_mock_registration(&key, /*latency=*/ None);
        assert!(registration.is_ok());
    }

    #[test]
    fn test_get_registration_cb_cancel() {
        let (tx, rx) = oneshot::channel();
        let cb = GetRegistrationCallback::new_native_binder(tx);
        assert!(cb.onCancel().is_ok());

        let result = tokio_rt().block_on(rx).unwrap();
        assert_eq!(
            result.unwrap_err().downcast::<Error>().unwrap(),
            Error::Rc(ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR)
        );
    }

    #[test]
    fn test_get_registration_cb_error() {
        let (tx, rx) = oneshot::channel();
        let cb = GetRegistrationCallback::new_native_binder(tx);
        assert!(cb.onError("error").is_ok());

        let result = tokio_rt().block_on(rx).unwrap();
        assert_eq!(
            result.unwrap_err().downcast::<Error>().unwrap(),
            Error::Rc(ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR)
        );
    }

    #[test]
    fn test_get_key_cb_success() {
        let mock_key =
            RemotelyProvisionedKey { keyBlob: vec![1, 2, 3], encodedCertChain: vec![4, 5, 6] };
        let (tx, rx) = oneshot::channel();
        let cb = GetKeyCallback::new_native_binder(tx);
        assert!(cb.onSuccess(&mock_key).is_ok());

        let key = tokio_rt().block_on(rx).unwrap().unwrap();
        assert_eq!(key, mock_key);
    }

    #[test]
    fn test_get_key_cb_cancel() {
        let (tx, rx) = oneshot::channel();
        let cb = GetKeyCallback::new_native_binder(tx);
        assert!(cb.onCancel().is_ok());

        let result = tokio_rt().block_on(rx).unwrap();
        assert_eq!(
            result.unwrap_err().downcast::<Error>().unwrap(),
            Error::Rc(ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR)
        );
    }

    #[test]
    fn test_get_key_cb_error() {
        let error_mapping = HashMap::from([
            (GetKeyErrorCode::ERROR_UNKNOWN, ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR),
            (GetKeyErrorCode::ERROR_PERMANENT, ResponseCode::OUT_OF_KEYS_PERMANENT_ERROR),
            (
                GetKeyErrorCode::ERROR_PENDING_INTERNET_CONNECTIVITY,
                ResponseCode::OUT_OF_KEYS_PENDING_INTERNET_CONNECTIVITY,
            ),
            (
                GetKeyErrorCode::ERROR_REQUIRES_SECURITY_PATCH,
                ResponseCode::OUT_OF_KEYS_REQUIRES_SYSTEM_UPGRADE,
            ),
        ]);

        // Loop over the generated list of enum values to better ensure this test stays in
        // sync with the AIDL.
        for get_key_error in GetKeyErrorCode::enum_values() {
            let (tx, rx) = oneshot::channel();
            let cb = GetKeyCallback::new_native_binder(tx);
            assert!(cb.onError(get_key_error, "error").is_ok());

            let result = tokio_rt().block_on(rx).unwrap();
            assert_eq!(
                result.unwrap_err().downcast::<Error>().unwrap(),
                Error::Rc(error_mapping[&get_key_error]),
            );
        }
    }

    #[test]
    fn test_store_upgraded_cb_success() {
        let (tx, rx) = oneshot::channel();
        let cb = StoreUpgradedKeyCallback::new_native_binder(tx);
        assert!(cb.onSuccess().is_ok());

        tokio_rt().block_on(rx).unwrap().unwrap();
    }

    #[test]
    fn test_store_upgraded_key_cb_error() {
        let (tx, rx) = oneshot::channel();
        let cb = StoreUpgradedKeyCallback::new_native_binder(tx);
        assert!(cb.onError("oh no! it failed").is_ok());

        let result = tokio_rt().block_on(rx).unwrap();
        assert_eq!(
            result.unwrap_err().downcast::<Error>().unwrap(),
            Error::Rc(ResponseCode::SYSTEM_ERROR)
        );
    }

    #[test]
    fn test_get_mock_key_success() {
        let mock_key =
            RemotelyProvisionedKey { keyBlob: vec![1, 2, 3], encodedCertChain: vec![4, 5, 6] };
        let registration = get_mock_registration(&mock_key, /*latency=*/ None).unwrap();

        let key = tokio_rt()
            .block_on(get_rkpd_attestation_key_from_registration_async(&registration, 0))
            .unwrap();
        assert_eq!(key, mock_key);
    }

    #[test]
    fn test_get_mock_key_timeout() {
        let mock_key =
            RemotelyProvisionedKey { keyBlob: vec![1, 2, 3], encodedCertChain: vec![4, 5, 6] };
        let latency = RKPD_TIMEOUT + Duration::from_secs(1);
        let registration = get_mock_registration(&mock_key, Some(latency)).unwrap();

        let result =
            tokio_rt().block_on(get_rkpd_attestation_key_from_registration_async(&registration, 0));
        assert_eq!(
            result.unwrap_err().downcast::<Error>().unwrap(),
            Error::Rc(ResponseCode::OUT_OF_KEYS_TRANSIENT_ERROR)
        );
    }

    #[test]
    fn test_store_mock_key_success() {
        let mock_key =
            RemotelyProvisionedKey { keyBlob: vec![1, 2, 3], encodedCertChain: vec![4, 5, 6] };
        let registration = get_mock_registration(&mock_key, /*latency=*/ None).unwrap();
        tokio_rt()
            .block_on(store_rkpd_attestation_key_with_registration_async(&registration, &[], &[]))
            .unwrap();
    }

    #[test]
    fn test_store_mock_key_timeout() {
        let mock_key =
            RemotelyProvisionedKey { keyBlob: vec![1, 2, 3], encodedCertChain: vec![4, 5, 6] };
        let latency = RKPD_TIMEOUT + Duration::from_secs(1);
        let registration = get_mock_registration(&mock_key, Some(latency)).unwrap();

        let result = tokio_rt().block_on(store_rkpd_attestation_key_with_registration_async(
            &registration,
            &[],
            &[],
        ));
        assert_eq!(
            result.unwrap_err().downcast::<Error>().unwrap(),
            Error::Rc(ResponseCode::SYSTEM_ERROR)
        );
    }

    #[test]
    fn test_get_rkpd_attestation_key() {
        binder::ProcessState::start_thread_pool();
        let key_id = get_next_key_id();
        let key = get_rkpd_attestation_key(&SecurityLevel::TRUSTED_ENVIRONMENT, key_id).unwrap();
        assert!(!key.keyBlob.is_empty());
        assert!(!key.encodedCertChain.is_empty());
    }

    #[test]
    fn test_get_rkpd_attestation_key_same_caller() {
        binder::ProcessState::start_thread_pool();
        let sec_level = SecurityLevel::TRUSTED_ENVIRONMENT;
        let key_id = get_next_key_id();

        // Multiple calls should return the same key.
        let first_key = get_rkpd_attestation_key(&sec_level, key_id).unwrap();
        let second_key = get_rkpd_attestation_key(&sec_level, key_id).unwrap();

        assert_eq!(first_key.keyBlob, second_key.keyBlob);
        assert_eq!(first_key.encodedCertChain, second_key.encodedCertChain);
    }

    #[test]
    fn test_get_rkpd_attestation_key_different_caller() {
        binder::ProcessState::start_thread_pool();
        let sec_level = SecurityLevel::TRUSTED_ENVIRONMENT;
        let first_key_id = get_next_key_id();
        let second_key_id = get_next_key_id();

        // Different callers should be getting different keys.
        let first_key = get_rkpd_attestation_key(&sec_level, first_key_id).unwrap();
        let second_key = get_rkpd_attestation_key(&sec_level, second_key_id).unwrap();

        assert_ne!(first_key.keyBlob, second_key.keyBlob);
        assert_ne!(first_key.encodedCertChain, second_key.encodedCertChain);
    }

    #[test]
    // Couple of things to note:
    // 1. This test must never run with UID of keystore. Otherwise, it can mess up keys stored by
    //    keystore.
    // 2. Storing and reading the stored key is prone to race condition. So, we only do this in one
    //    test case.
    fn test_store_rkpd_attestation_key() {
        binder::ProcessState::start_thread_pool();
        let sec_level = SecurityLevel::TRUSTED_ENVIRONMENT;
        let key_id = get_next_key_id();
        let key = get_rkpd_attestation_key(&SecurityLevel::TRUSTED_ENVIRONMENT, key_id).unwrap();
        let new_blob: [u8; 8] = rand::random();

        assert!(store_rkpd_attestation_key(&sec_level, &key.keyBlob, &new_blob).is_ok());

        let new_key =
            get_rkpd_attestation_key(&SecurityLevel::TRUSTED_ENVIRONMENT, key_id).unwrap();

        // Restore original key so that we don't leave RKPD with invalid blobs.
        assert!(store_rkpd_attestation_key(&sec_level, &new_blob, &key.keyBlob).is_ok());
        assert_eq!(new_key.keyBlob, new_blob);
    }

    #[test]
    // This is a helper for a manual test. We want to check that after a system upgrade RKPD
    // attestation keys can also be upgraded and stored again with RKPD. The steps are:
    // 1. Run this test and check in stdout that no key upgrade happened.
    // 2. Perform a system upgrade.
    // 3. Run this test and check in stdout that key upgrade did happen.
    //
    // Note that this test must be run with that same UID every time. Running as root, i.e. UID 0,
    // should do the trick. Also, use "--nocapture" flag to get stdout.
    fn test_rkpd_attestation_key_upgrade() {
        binder::ProcessState::start_thread_pool();
        let security_level = SecurityLevel::TRUSTED_ENVIRONMENT;
        let (keymint, _, _) = get_keymint_device(&security_level).unwrap();
        let key_id = get_next_key_id();
        let mut key_upgraded = false;

        let key = get_rkpd_attestation_key(&security_level, key_id).unwrap();
        assert!(!key.keyBlob.is_empty());
        assert!(!key.encodedCertChain.is_empty());

        upgrade_keyblob_if_required_with(
            &*keymint,
            &key.keyBlob,
            /*upgrade_params=*/ &[],
            /*km_op=*/
            |blob| {
                let params = vec![
                    KeyParameter {
                        tag: Tag::ALGORITHM,
                        value: KeyParameterValue::Algorithm(Algorithm::AES),
                    },
                    KeyParameter { tag: Tag::KEY_SIZE, value: KeyParameterValue::Integer(128) },
                ];
                let attestation_key = AttestationKey {
                    keyBlob: blob.to_vec(),
                    attestKeyParams: vec![],
                    issuerSubjectName: parse_subject_from_certificate(&key.encodedCertChain)
                        .unwrap(),
                };

                map_km_error(keymint.generateKey(&params, Some(&attestation_key)))
            },
            /*new_blob_handler=*/
            |new_blob| {
                // This handler is only executed if a key upgrade was performed.
                key_upgraded = true;
                store_rkpd_attestation_key(&security_level, &key.keyBlob, new_blob).unwrap();
                Ok(())
            },
        )
        .unwrap();

        if key_upgraded {
            println!("RKPD key was upgraded and stored with RKPD.");
        } else {
            println!("RKPD key was NOT upgraded.");
        }
    }

    #[test]
    fn test_stress_get_rkpd_attestation_key() {
        binder::ProcessState::start_thread_pool();
        let key_id = get_next_key_id();
        let mut threads = vec![];
        const NTHREADS: u32 = 10;
        const NCALLS: u32 = 1000;

        for _ in 0..NTHREADS {
            threads.push(std::thread::spawn(move || {
                for _ in 0..NCALLS {
                    let key = get_rkpd_attestation_key(&SecurityLevel::TRUSTED_ENVIRONMENT, key_id)
                        .unwrap();
                    assert!(!key.keyBlob.is_empty());
                    assert!(!key.encodedCertChain.is_empty());
                }
            }));
        }

        for t in threads {
            assert!(t.join().is_ok());
        }
    }
}
