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

//! This module handles the interaction with virtual machine payload service.

use android_system_virtualization_payload::aidl::android::system::virtualization::payload::IVmPayloadService::{
    ENCRYPTEDSTORE_MOUNTPOINT, IVmPayloadService, VM_PAYLOAD_SERVICE_SOCKET_NAME, VM_APK_CONTENTS_PATH};
use anyhow::{ensure, bail, Context, Result};
use binder::{Strong, unstable_api::{AIBinder, new_spibinder}};
use lazy_static::lazy_static;
use log::{error, info, Level};
use rpcbinder::{RpcSession, RpcServer};
use std::convert::Infallible;
use std::ffi::CString;
use std::fmt::Debug;
use std::os::raw::{c_char, c_void};
use std::path::Path;
use std::ptr;
use std::sync::{Mutex, atomic::{AtomicBool, Ordering}};

lazy_static! {
    static ref VM_APK_CONTENTS_PATH_C: CString =
        CString::new(VM_APK_CONTENTS_PATH).expect("CString::new failed");
    static ref PAYLOAD_CONNECTION: Mutex<Option<Strong<dyn IVmPayloadService>>> = Mutex::default();
    static ref VM_ENCRYPTED_STORAGE_PATH_C: CString =
        CString::new(ENCRYPTEDSTORE_MOUNTPOINT).expect("CString::new failed");
}

static ALREADY_NOTIFIED: AtomicBool = AtomicBool::new(false);

/// Return a connection to the payload service in Microdroid Manager. Uses the existing connection
/// if there is one, otherwise attempts to create a new one.
fn get_vm_payload_service() -> Result<Strong<dyn IVmPayloadService>> {
    let mut connection = PAYLOAD_CONNECTION.lock().unwrap();
    if let Some(strong) = &*connection {
        Ok(strong.clone())
    } else {
        let new_connection: Strong<dyn IVmPayloadService> = RpcSession::new()
            .setup_unix_domain_client(VM_PAYLOAD_SERVICE_SOCKET_NAME)
            .context(format!("Failed to connect to service: {}", VM_PAYLOAD_SERVICE_SOCKET_NAME))?;
        *connection = Some(new_connection.clone());
        Ok(new_connection)
    }
}

/// Make sure our logging goes to logcat. It is harmless to call this more than once.
fn initialize_logging() {
    android_logger::init_once(
        android_logger::Config::default().with_tag("vm_payload").with_min_level(Level::Info),
    );
}

/// In many cases clients can't do anything useful if API calls fail, and the failure
/// generally indicates that the VM is exiting or otherwise doomed. So rather than
/// returning a non-actionable error indication we just log the problem and abort
/// the process.
fn unwrap_or_abort<T, E: Debug>(result: Result<T, E>) -> T {
    result.unwrap_or_else(|e| {
        let msg = format!("{:?}", e);
        error!("{msg}");
        panic!("{msg}")
    })
}

/// Notifies the host that the payload is ready.
/// Panics on failure.
#[no_mangle]
pub extern "C" fn AVmPayload_notifyPayloadReady() {
    initialize_logging();

    if !ALREADY_NOTIFIED.swap(true, Ordering::Relaxed) {
        unwrap_or_abort(try_notify_payload_ready());

        info!("Notified host payload ready successfully");
    }
}

/// Notifies the host that the payload is ready.
/// Returns a `Result` containing error information if failed.
fn try_notify_payload_ready() -> Result<()> {
    get_vm_payload_service()?.notifyPayloadReady().context("Cannot notify payload ready")
}

/// Runs a binder RPC server, serving the supplied binder service implementation on the given vsock
/// port.
///
/// If and when the server is ready for connections (it is listening on the port), `on_ready` is
/// called to allow appropriate action to be taken - e.g. to notify clients that they may now
/// attempt to connect.
///
/// The current thread joins the binder thread pool to handle incoming messages.
/// This function never returns.
///
/// Panics on error (including unexpected server exit).
///
/// # Safety
///
/// If present, the `on_ready` callback must be a valid function pointer, which will be called at
/// most once, while this function is executing, with the `param` parameter.
#[no_mangle]
pub unsafe extern "C" fn AVmPayload_runVsockRpcServer(
    service: *mut AIBinder,
    port: u32,
    on_ready: Option<unsafe extern "C" fn(param: *mut c_void)>,
    param: *mut c_void,
) -> Infallible {
    initialize_logging();

    // SAFETY: try_run_vsock_server has the same requirements as this function
    unwrap_or_abort(unsafe { try_run_vsock_server(service, port, on_ready, param) })
}

/// # Safety: Same as `AVmPayload_runVsockRpcServer`.
unsafe fn try_run_vsock_server(
    service: *mut AIBinder,
    port: u32,
    on_ready: Option<unsafe extern "C" fn(param: *mut c_void)>,
    param: *mut c_void,
) -> Result<Infallible> {
    // SAFETY: AIBinder returned has correct reference count, and the ownership can
    // safely be taken by new_spibinder.
    let service = unsafe { new_spibinder(service) };
    if let Some(service) = service {
        match RpcServer::new_vsock(service, libc::VMADDR_CID_HOST, port) {
            Ok(server) => {
                if let Some(on_ready) = on_ready {
                    // SAFETY: We're calling the callback with the parameter specified within the
                    // allowed lifetime.
                    unsafe { on_ready(param) };
                }
                server.join();
                bail!("RpcServer unexpectedly terminated");
            }
            Err(err) => {
                bail!("Failed to start RpcServer: {:?}", err);
            }
        }
    } else {
        bail!("Failed to convert the given service from AIBinder to SpIBinder.");
    }
}

/// Get a secret that is uniquely bound to this VM instance.
/// Panics on failure.
///
/// # Safety
///
/// Behavior is undefined if any of the following conditions are violated:
///
/// * `identifier` must be [valid] for reads of `identifier_size` bytes.
/// * `secret` must be [valid] for writes of `size` bytes.
///
/// [valid]: ptr#safety
#[no_mangle]
pub unsafe extern "C" fn AVmPayload_getVmInstanceSecret(
    identifier: *const u8,
    identifier_size: usize,
    secret: *mut u8,
    size: usize,
) {
    initialize_logging();

    // SAFETY: See the requirements on `identifier` above.
    let identifier = unsafe { std::slice::from_raw_parts(identifier, identifier_size) };
    let vm_secret = unwrap_or_abort(try_get_vm_instance_secret(identifier, size));

    // SAFETY: See the requirements on `secret` above; `vm_secret` is known to have length `size`,
    // and cannot overlap `secret` because we just allocated it.
    unsafe {
        ptr::copy_nonoverlapping(vm_secret.as_ptr(), secret, size);
    }
}

fn try_get_vm_instance_secret(identifier: &[u8], size: usize) -> Result<Vec<u8>> {
    let vm_secret = get_vm_payload_service()?
        .getVmInstanceSecret(identifier, i32::try_from(size)?)
        .context("Cannot get VM instance secret")?;
    ensure!(
        vm_secret.len() == size,
        "Returned secret has {} bytes, expected {}",
        vm_secret.len(),
        size
    );
    Ok(vm_secret)
}

/// Get the VM's attestation chain.
/// Panics on failure.
///
/// # Safety
///
/// Behavior is undefined if any of the following conditions are violated:
///
/// * `data` must be [valid] for writes of `size` bytes, if size > 0.
///
/// [valid]: ptr#safety
#[no_mangle]
pub unsafe extern "C" fn AVmPayload_getDiceAttestationChain(data: *mut u8, size: usize) -> usize {
    initialize_logging();

    let chain = unwrap_or_abort(try_get_dice_attestation_chain());
    if size != 0 {
        // SAFETY: See the requirements on `data` above. The number of bytes copied doesn't exceed
        // the length of either buffer, and `chain` cannot overlap `data` because we just allocated
        // it. We allow data to be null, which is never valid, but only if size == 0 which is
        // checked above.
        unsafe { ptr::copy_nonoverlapping(chain.as_ptr(), data, std::cmp::min(chain.len(), size)) };
    }
    chain.len()
}

fn try_get_dice_attestation_chain() -> Result<Vec<u8>> {
    get_vm_payload_service()?.getDiceAttestationChain().context("Cannot get attestation chain")
}

/// Get the VM's attestation CDI.
/// Panics on failure.
///
/// # Safety
///
/// Behavior is undefined if any of the following conditions are violated:
///
/// * `data` must be [valid] for writes of `size` bytes, if size > 0.
///
/// [valid]: ptr#safety
#[no_mangle]
pub unsafe extern "C" fn AVmPayload_getDiceAttestationCdi(data: *mut u8, size: usize) -> usize {
    initialize_logging();

    let cdi = unwrap_or_abort(try_get_dice_attestation_cdi());
    if size != 0 {
        // SAFETY: See the requirements on `data` above. The number of bytes copied doesn't exceed
        // the length of either buffer, and `cdi` cannot overlap `data` because we just allocated
        // it. We allow data to be null, which is never valid, but only if size == 0 which is
        // checked above.
        unsafe { ptr::copy_nonoverlapping(cdi.as_ptr(), data, std::cmp::min(cdi.len(), size)) };
    }
    cdi.len()
}

fn try_get_dice_attestation_cdi() -> Result<Vec<u8>> {
    get_vm_payload_service()?.getDiceAttestationCdi().context("Cannot get attestation CDI")
}

/// Gets the path to the APK contents.
#[no_mangle]
pub extern "C" fn AVmPayload_getApkContentsPath() -> *const c_char {
    VM_APK_CONTENTS_PATH_C.as_ptr()
}

/// Gets the path to the VM's encrypted storage.
#[no_mangle]
pub extern "C" fn AVmPayload_getEncryptedStoragePath() -> *const c_char {
    if Path::new(ENCRYPTEDSTORE_MOUNTPOINT).exists() {
        VM_ENCRYPTED_STORAGE_PATH_C.as_ptr()
    } else {
        ptr::null()
    }
}
