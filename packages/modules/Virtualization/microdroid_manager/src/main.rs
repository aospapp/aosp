// Copyright 2021, The Android Open Source Project
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

//! Microdroid Manager

mod dice;
mod instance;
mod ioutil;
mod payload;
mod swap;
mod vm_payload_service;

use crate::dice::{DiceDriver, derive_sealing_key, format_payload_config_descriptor};
use crate::instance::{ApexData, ApkData, InstanceDisk, MicrodroidData, RootHash};
use crate::vm_payload_service::register_vm_payload_service;
use android_system_virtualizationcommon::aidl::android::system::virtualizationcommon::ErrorCode::ErrorCode;
use android_system_virtualmachineservice::aidl::android::system::virtualmachineservice::IVirtualMachineService::IVirtualMachineService;
use android_system_virtualization_payload::aidl::android::system::virtualization::payload::IVmPayloadService::{
    VM_APK_CONTENTS_PATH,
    VM_PAYLOAD_SERVICE_SOCKET_NAME,
    ENCRYPTEDSTORE_MOUNTPOINT,
};
use anyhow::{anyhow, bail, ensure, Context, Error, Result};
use apkverify::{get_public_key_der, verify, V4Signature};
use binder::Strong;
use diced_open_dice::OwnedDiceArtifacts;
use glob::glob;
use itertools::sorted;
use libc::VMADDR_CID_HOST;
use log::{error, info, warn};
use keystore2_crypto::ZVec;
use microdroid_metadata::{write_metadata, Metadata, PayloadMetadata};
use microdroid_payload_config::{OsConfig, Task, TaskType, VmPayloadConfig};
use nix::fcntl::{fcntl, F_SETFD, FdFlag};
use nix::sys::signal::Signal;
use openssl::sha::Sha512;
use payload::{get_apex_data_from_payload, load_metadata, to_metadata};
use rand::Fill;
use rpcbinder::RpcSession;
use rustutils::sockets::android_get_control_socket;
use rustutils::system_properties;
use rustutils::system_properties::PropertyWatcher;
use std::borrow::Cow::{Borrowed, Owned};
use std::convert::TryInto;
use std::env;
use std::ffi::CString;
use std::fs::{self, create_dir, OpenOptions, File};
use std::io::{Read, Write};
use std::os::unix::process::CommandExt;
use std::os::unix::process::ExitStatusExt;
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::str;
use std::time::{Duration, SystemTime};

const WAIT_TIMEOUT: Duration = Duration::from_secs(10);
const MAIN_APK_PATH: &str = "/dev/block/by-name/microdroid-apk";
const MAIN_APK_IDSIG_PATH: &str = "/dev/block/by-name/microdroid-apk-idsig";
const MAIN_APK_DEVICE_NAME: &str = "microdroid-apk";
const EXTRA_APK_PATH_PATTERN: &str = "/dev/block/by-name/extra-apk-*";
const EXTRA_IDSIG_PATH_PATTERN: &str = "/dev/block/by-name/extra-idsig-*";
const DM_MOUNTED_APK_PATH: &str = "/dev/block/mapper/microdroid-apk";
const AVF_STRICT_BOOT: &str = "/sys/firmware/devicetree/base/chosen/avf,strict-boot";
const AVF_NEW_INSTANCE: &str = "/sys/firmware/devicetree/base/chosen/avf,new-instance";
const AVF_DEBUG_POLICY_RAMDUMP: &str = "/sys/firmware/devicetree/base/avf/guest/common/ramdump";
const DEBUG_MICRODROID_NO_VERIFIED_BOOT: &str =
    "/sys/firmware/devicetree/base/virtualization/guest/debug-microdroid,no-verified-boot";

const APKDMVERITY_BIN: &str = "/system/bin/apkdmverity";
const ENCRYPTEDSTORE_BIN: &str = "/system/bin/encryptedstore";
const ZIPFUSE_BIN: &str = "/system/bin/zipfuse";

const APEX_CONFIG_DONE_PROP: &str = "apex_config.done";
const DEBUGGABLE_PROP: &str = "ro.boot.microdroid.debuggable";

// SYNC WITH virtualizationservice/src/crosvm.rs
const FAILURE_SERIAL_DEVICE: &str = "/dev/ttyS1";

const ENCRYPTEDSTORE_BACKING_DEVICE: &str = "/dev/block/by-name/encryptedstore";
const ENCRYPTEDSTORE_KEY_IDENTIFIER: &str = "encryptedstore_key";
const ENCRYPTEDSTORE_KEYSIZE: usize = 32;

#[derive(thiserror::Error, Debug)]
enum MicrodroidError {
    #[error("Cannot connect to virtualization service: {0}")]
    FailedToConnectToVirtualizationService(String),
    #[error("Payload has changed: {0}")]
    PayloadChanged(String),
    #[error("Payload verification has failed: {0}")]
    PayloadVerificationFailed(String),
    #[error("Payload config is invalid: {0}")]
    InvalidConfig(String),
}

fn translate_error(err: &Error) -> (ErrorCode, String) {
    if let Some(e) = err.downcast_ref::<MicrodroidError>() {
        match e {
            MicrodroidError::PayloadChanged(msg) => (ErrorCode::PAYLOAD_CHANGED, msg.to_string()),
            MicrodroidError::PayloadVerificationFailed(msg) => {
                (ErrorCode::PAYLOAD_VERIFICATION_FAILED, msg.to_string())
            }
            MicrodroidError::InvalidConfig(msg) => {
                (ErrorCode::PAYLOAD_CONFIG_INVALID, msg.to_string())
            }

            // Connection failure won't be reported to VS; return the default value
            MicrodroidError::FailedToConnectToVirtualizationService(msg) => {
                (ErrorCode::UNKNOWN, msg.to_string())
            }
        }
    } else {
        (ErrorCode::UNKNOWN, err.to_string())
    }
}

fn write_death_reason_to_serial(err: &Error) -> Result<()> {
    let death_reason = if let Some(e) = err.downcast_ref::<MicrodroidError>() {
        Borrowed(match e {
            MicrodroidError::FailedToConnectToVirtualizationService(_) => {
                "MICRODROID_FAILED_TO_CONNECT_TO_VIRTUALIZATION_SERVICE"
            }
            MicrodroidError::PayloadChanged(_) => "MICRODROID_PAYLOAD_HAS_CHANGED",
            MicrodroidError::PayloadVerificationFailed(_) => {
                "MICRODROID_PAYLOAD_VERIFICATION_FAILED"
            }
            MicrodroidError::InvalidConfig(_) => "MICRODROID_INVALID_PAYLOAD_CONFIG",
        })
    } else {
        // Send context information back after a separator, to ease diagnosis.
        // These errors occur before the payload runs, so this should not leak sensitive
        // information.
        Owned(format!("MICRODROID_UNKNOWN_RUNTIME_ERROR|{:?}", err))
    };

    let death_reason_bytes = death_reason.as_bytes();
    let mut sent_total = 0;
    while sent_total < death_reason_bytes.len() {
        // TODO(b/220071963): Sometimes, sending more than 16 bytes at once makes MM hang.
        let begin = sent_total;
        let end = std::cmp::min(begin.saturating_add(16), death_reason_bytes.len());
        OpenOptions::new()
            .read(false)
            .write(true)
            .open(FAILURE_SERIAL_DEVICE)?
            .write_all(&death_reason_bytes[begin..end])?;
        sent_total = end;
    }

    Ok(())
}

fn get_vms_rpc_binder() -> Result<Strong<dyn IVirtualMachineService>> {
    // The host is running a VirtualMachineService for this VM on a port equal
    // to the CID of this VM.
    let port = vsock::get_local_cid().context("Could not determine local CID")?;
    RpcSession::new()
        .setup_vsock_client(VMADDR_CID_HOST, port)
        .context("Could not connect to IVirtualMachineService")
}

fn main() -> Result<()> {
    // If debuggable, print full backtrace to console log with stdio_to_kmsg
    if system_properties::read_bool(DEBUGGABLE_PROP, true)? {
        env::set_var("RUST_BACKTRACE", "full");
    }

    scopeguard::defer! {
        info!("Shutting down...");
        if let Err(e) = system_properties::write("sys.powerctl", "shutdown") {
            error!("failed to shutdown {:?}", e);
        }
    }

    try_main().map_err(|e| {
        error!("Failed with {:?}.", e);
        if let Err(e) = write_death_reason_to_serial(&e) {
            error!("Failed to write death reason {:?}", e);
        }
        e
    })
}

fn set_cloexec_on_vm_payload_service_socket() -> Result<()> {
    let fd = android_get_control_socket(VM_PAYLOAD_SERVICE_SOCKET_NAME)?;

    fcntl(fd, F_SETFD(FdFlag::FD_CLOEXEC))?;

    Ok(())
}

fn try_main() -> Result<()> {
    let _ = kernlog::init();
    info!("started.");

    if let Err(e) = set_cloexec_on_vm_payload_service_socket() {
        warn!("Failed to set cloexec on vm payload socket: {:?}", e);
    }

    load_crashkernel_if_supported().context("Failed to load crashkernel")?;

    swap::init_swap().context("Failed to initialise swap")?;
    info!("swap enabled.");

    let service = get_vms_rpc_binder()
        .context("cannot connect to VirtualMachineService")
        .map_err(|e| MicrodroidError::FailedToConnectToVirtualizationService(e.to_string()))?;

    match try_run_payload(&service) {
        Ok(code) => {
            if code == 0 {
                info!("task successfully finished");
            } else {
                error!("task exited with exit code: {}", code);
            }
            if let Err(e) = post_payload_work() {
                error!(
                    "Failed to run post payload work. It is possible that certain tasks
                    like syncing encrypted store might be incomplete. Error: {:?}",
                    e
                );
            };

            info!("notifying payload finished");
            service.notifyPayloadFinished(code)?;
            Ok(())
        }
        Err(err) => {
            let (error_code, message) = translate_error(&err);
            service.notifyError(error_code, &message)?;
            Err(err)
        }
    }
}

fn post_payload_work() -> Result<()> {
    // Sync the encrypted storage filesystem (flushes the filesystem caches).
    if Path::new(ENCRYPTEDSTORE_BACKING_DEVICE).exists() {
        let mountpoint = CString::new(ENCRYPTEDSTORE_MOUNTPOINT).unwrap();

        let ret = unsafe {
            let dirfd = libc::open(
                mountpoint.as_ptr(),
                libc::O_DIRECTORY | libc::O_RDONLY | libc::O_CLOEXEC,
            );
            ensure!(dirfd >= 0, "Unable to open {:?}", mountpoint);
            let ret = libc::syncfs(dirfd);
            libc::close(dirfd);
            ret
        };
        if ret != 0 {
            error!("failed to sync encrypted storage.");
            return Err(anyhow!(std::io::Error::last_os_error()));
        }
    }
    Ok(())
}
fn dice_derivation(
    dice: DiceDriver,
    verified_data: &MicrodroidData,
    payload_metadata: &PayloadMetadata,
) -> Result<OwnedDiceArtifacts> {
    // Calculate compound digests of code and authorities
    let mut code_hash_ctx = Sha512::new();
    let mut authority_hash_ctx = Sha512::new();
    code_hash_ctx.update(verified_data.apk_data.root_hash.as_ref());
    authority_hash_ctx.update(verified_data.apk_data.pubkey.as_ref());
    for extra_apk in &verified_data.extra_apks_data {
        code_hash_ctx.update(extra_apk.root_hash.as_ref());
        authority_hash_ctx.update(extra_apk.pubkey.as_ref());
    }
    for apex in &verified_data.apex_data {
        code_hash_ctx.update(apex.root_digest.as_ref());
        authority_hash_ctx.update(apex.public_key.as_ref());
    }
    let code_hash = code_hash_ctx.finish();
    let authority_hash = authority_hash_ctx.finish();

    let config_descriptor = format_payload_config_descriptor(payload_metadata)?;

    // Check debuggability, conservatively assuming it is debuggable
    let debuggable = system_properties::read_bool(DEBUGGABLE_PROP, true)?;

    // Send the details to diced
    let hidden = verified_data.salt.clone().try_into().unwrap();
    dice.derive(code_hash, &config_descriptor, authority_hash, debuggable, hidden)
}

fn is_strict_boot() -> bool {
    Path::new(AVF_STRICT_BOOT).exists()
}

fn is_new_instance() -> bool {
    Path::new(AVF_NEW_INSTANCE).exists()
}

fn is_verified_boot() -> bool {
    !Path::new(DEBUG_MICRODROID_NO_VERIFIED_BOOT).exists()
}

fn should_export_tombstones(config: &VmPayloadConfig) -> bool {
    match config.export_tombstones {
        Some(b) => b,
        None => system_properties::read_bool(DEBUGGABLE_PROP, true).unwrap_or(false),
    }
}

/// Get debug policy value in bool. It's true iff the value is explicitly set to <1>.
fn get_debug_policy_bool(path: &'static str) -> Result<Option<bool>> {
    let mut file = match File::open(path) {
        Ok(dp) => dp,
        Err(e) => {
            info!("{e:?}. Assumes <0>");
            return Ok(Some(false));
        }
    };
    let mut log: [u8; 4] = Default::default();
    file.read_exact(&mut log).context("Malformed data in {path}")?;
    // DT spec uses big endian although Android is always little endian.
    Ok(Some(u32::from_be_bytes(log) == 1))
}

fn try_run_payload(service: &Strong<dyn IVirtualMachineService>) -> Result<i32> {
    let metadata = load_metadata().context("Failed to load payload metadata")?;
    let dice = DiceDriver::new(Path::new("/dev/open-dice0")).context("Failed to load DICE")?;

    let mut instance = InstanceDisk::new().context("Failed to load instance.img")?;
    let saved_data =
        instance.read_microdroid_data(&dice).context("Failed to read identity data")?;

    if is_strict_boot() {
        // Provisioning must happen on the first boot and never again.
        if is_new_instance() {
            ensure!(
                saved_data.is_none(),
                MicrodroidError::InvalidConfig("Found instance data on first boot.".to_string())
            );
        } else {
            ensure!(
                saved_data.is_some(),
                MicrodroidError::InvalidConfig("Instance data not found.".to_string())
            );
        };
    }

    // Verify the payload before using it.
    let verified_data = verify_payload(&metadata, saved_data.as_ref())
        .context("Payload verification failed")
        .map_err(|e| MicrodroidError::PayloadVerificationFailed(e.to_string()))?;

    // In case identity is ignored (by debug policy), we should reuse existing payload data, even
    // when the payload is changed. This is to keep the derived secret same as before.
    let verified_data = if let Some(saved_data) = saved_data {
        if !is_verified_boot() {
            if saved_data != verified_data {
                info!("Detected an update of the payload, but continue (regarding debug policy)")
            }
        } else {
            ensure!(
                saved_data == verified_data,
                MicrodroidError::PayloadChanged(String::from(
                    "Detected an update of the payload which isn't supported yet."
                ))
            );
            info!("Saved data is verified.");
        }
        saved_data
    } else {
        info!("Saving verified data.");
        instance
            .write_microdroid_data(&verified_data, &dice)
            .context("Failed to write identity data")?;
        verified_data
    };

    let payload_metadata = metadata.payload.ok_or_else(|| {
        MicrodroidError::InvalidConfig("No payload config in metadata".to_string())
    })?;

    // To minimize the exposure to untrusted data, derive dice profile as soon as possible.
    info!("DICE derivation for payload");
    let dice_artifacts = dice_derivation(dice, &verified_data, &payload_metadata)?;

    // Run encryptedstore binary to prepare the storage
    let encryptedstore_child = if Path::new(ENCRYPTEDSTORE_BACKING_DEVICE).exists() {
        info!("Preparing encryptedstore ...");
        Some(prepare_encryptedstore(&dice_artifacts).context("encryptedstore run")?)
    } else {
        None
    };

    let mut zipfuse = Zipfuse::default();

    // Before reading a file from the APK, start zipfuse
    zipfuse.mount(
        MountForExec::Allowed,
        "fscontext=u:object_r:zipfusefs:s0,context=u:object_r:system_file:s0",
        Path::new("/dev/block/mapper/microdroid-apk"),
        Path::new(VM_APK_CONTENTS_PATH),
        "microdroid_manager.apk.mounted".to_owned(),
    )?;

    // Restricted APIs are only allowed to be used by platform or test components. Infer this from
    // the use of a VM config file since those can only be used by platform and test components.
    let allow_restricted_apis = match payload_metadata {
        PayloadMetadata::config_path(_) => true,
        PayloadMetadata::config(_) => false,
    };

    let config = load_config(payload_metadata).context("Failed to load payload metadata")?;

    let task = config
        .task
        .as_ref()
        .ok_or_else(|| MicrodroidError::InvalidConfig("No task in VM config".to_string()))?;

    ensure!(
        config.extra_apks.len() == verified_data.extra_apks_data.len(),
        "config expects {} extra apks, but found {}",
        config.extra_apks.len(),
        verified_data.extra_apks_data.len()
    );
    mount_extra_apks(&config, &mut zipfuse)?;

    // Wait until apex config is done. (e.g. linker configuration for apexes)
    wait_for_apex_config_done()?;

    setup_config_sysprops(&config)?;

    // Set export_tombstones if enabled
    if should_export_tombstones(&config) {
        // This property is read by tombstone_handler.
        system_properties::write("microdroid_manager.export_tombstones.enabled", "1")
            .context("set microdroid_manager.export_tombstones.enabled")?;
    }

    // Wait until zipfuse has mounted the APKs so we can access the payload
    zipfuse.wait_until_done()?;

    register_vm_payload_service(allow_restricted_apis, service.clone(), dice_artifacts)?;

    // Wait for encryptedstore to finish mounting the storage (if enabled) before setting
    // microdroid_manager.init_done. Reason is init stops uneventd after that.
    // Encryptedstore, however requires ueventd
    if let Some(mut child) = encryptedstore_child {
        let exitcode = child.wait().context("Wait for encryptedstore child")?;
        ensure!(exitcode.success(), "Unable to prepare encrypted storage. Exitcode={}", exitcode);
    }

    wait_for_property_true("dev.bootcomplete").context("failed waiting for dev.bootcomplete")?;
    system_properties::write("microdroid_manager.init_done", "1")
        .context("set microdroid_manager.init_done")?;

    info!("boot completed, time to run payload");
    exec_task(task, service).context("Failed to run payload")
}

struct ApkDmverityArgument<'a> {
    apk: &'a str,
    idsig: &'a str,
    name: &'a str,
    saved_root_hash: Option<&'a RootHash>,
}

fn run_apkdmverity(args: &[ApkDmverityArgument]) -> Result<Child> {
    let mut cmd = Command::new(APKDMVERITY_BIN);

    for argument in args {
        cmd.arg("--apk").arg(argument.apk).arg(argument.idsig).arg(argument.name);
        if let Some(root_hash) = argument.saved_root_hash {
            cmd.arg(&to_hex_string(root_hash));
        } else {
            cmd.arg("none");
        }
    }

    cmd.spawn().context("Spawn apkdmverity")
}

enum MountForExec {
    Allowed,
    Disallowed,
}

#[derive(Default)]
struct Zipfuse {
    ready_properties: Vec<String>,
}

impl Zipfuse {
    const MICRODROID_PAYLOAD_UID: u32 = 0; // TODO(b/264861173) should be non-root
    const MICRODROID_PAYLOAD_GID: u32 = 0; // TODO(b/264861173) should be non-root
    fn mount(
        &mut self,
        noexec: MountForExec,
        option: &str,
        zip_path: &Path,
        mount_dir: &Path,
        ready_prop: String,
    ) -> Result<Child> {
        let mut cmd = Command::new(ZIPFUSE_BIN);
        if let MountForExec::Disallowed = noexec {
            cmd.arg("--noexec");
        }
        cmd.args(["-p", &ready_prop, "-o", option]);
        cmd.args(["-u", &Self::MICRODROID_PAYLOAD_UID.to_string()]);
        cmd.args(["-g", &Self::MICRODROID_PAYLOAD_GID.to_string()]);
        cmd.arg(zip_path).arg(mount_dir);
        self.ready_properties.push(ready_prop);
        cmd.spawn().with_context(|| format!("Failed to run zipfuse for {mount_dir:?}"))
    }

    fn wait_until_done(self) -> Result<()> {
        // We check the last-started check first in the hope that by the time it is done
        // all or most of the others will also be done, minimising the number of times we
        // block on a property.
        for property in self.ready_properties.into_iter().rev() {
            wait_for_property_true(&property)
                .with_context(|| format!("Failed waiting for {property}"))?;
        }
        Ok(())
    }
}

fn write_apex_payload_data(
    saved_data: Option<&MicrodroidData>,
    apex_data_from_payload: &[ApexData],
) -> Result<()> {
    if let Some(saved_apex_data) = saved_data.map(|d| &d.apex_data) {
        // We don't support APEX updates. (assuming that update will change root digest)
        ensure!(
            saved_apex_data == apex_data_from_payload,
            MicrodroidError::PayloadChanged(String::from("APEXes have changed."))
        );
        let apex_metadata = to_metadata(apex_data_from_payload);
        // Pass metadata(with public keys and root digests) to apexd so that it uses the passed
        // metadata instead of the default one (/dev/block/by-name/payload-metadata)
        OpenOptions::new()
            .create_new(true)
            .write(true)
            .open("/apex/vm-payload-metadata")
            .context("Failed to open /apex/vm-payload-metadata")
            .and_then(|f| write_metadata(&apex_metadata, f))?;
    }
    Ok(())
}

// Verify payload before executing it. For APK payload, Full verification (which is slow) is done
// when the root_hash values from the idsig file and the instance disk are different. This function
// returns the verified root hash (for APK payload) and pubkeys (for APEX payloads) that can be
// saved to the instance disk.
fn verify_payload(
    metadata: &Metadata,
    saved_data: Option<&MicrodroidData>,
) -> Result<MicrodroidData> {
    let start_time = SystemTime::now();

    // Verify main APK
    let root_hash_from_idsig = get_apk_root_hash_from_idsig(MAIN_APK_IDSIG_PATH)?;
    let root_hash_trustful =
        saved_data.map(|d| d.apk_data.root_hash_eq(root_hash_from_idsig.as_ref())).unwrap_or(false);

    // If root_hash can be trusted, pass it to apkdmverity so that it uses the passed root_hash
    // instead of the value read from the idsig file.
    let main_apk_argument = {
        ApkDmverityArgument {
            apk: MAIN_APK_PATH,
            idsig: MAIN_APK_IDSIG_PATH,
            name: MAIN_APK_DEVICE_NAME,
            saved_root_hash: if root_hash_trustful {
                Some(root_hash_from_idsig.as_ref())
            } else {
                None
            },
        }
    };
    let mut apkdmverity_arguments = vec![main_apk_argument];

    // Verify extra APKs
    // For now, we can't read the payload config, so glob APKs and idsigs.
    // Later, we'll see if it matches with the payload config.

    // sort globbed paths to match apks (extra-apk-{idx}) and idsigs (extra-idsig-{idx})
    // e.g. "extra-apk-0" corresponds to "extra-idsig-0"
    let extra_apks =
        sorted(glob(EXTRA_APK_PATH_PATTERN)?.collect::<Result<Vec<_>, _>>()?).collect::<Vec<_>>();
    let extra_idsigs =
        sorted(glob(EXTRA_IDSIG_PATH_PATTERN)?.collect::<Result<Vec<_>, _>>()?).collect::<Vec<_>>();
    ensure!(
        extra_apks.len() == extra_idsigs.len(),
        "Extra apks/idsigs mismatch: {} apks but {} idsigs",
        extra_apks.len(),
        extra_idsigs.len()
    );

    let extra_root_hashes_from_idsig: Vec<_> = extra_idsigs
        .iter()
        .map(|idsig| {
            get_apk_root_hash_from_idsig(idsig).expect("Can't find root hash from extra idsig")
        })
        .collect();

    let extra_root_hashes_trustful: Vec<_> = if let Some(data) = saved_data {
        extra_root_hashes_from_idsig
            .iter()
            .enumerate()
            .map(|(i, root_hash)| data.extra_apk_root_hash_eq(i, root_hash))
            .collect()
    } else {
        vec![false; extra_root_hashes_from_idsig.len()]
    };
    let extra_apk_names: Vec<_> =
        (0..extra_apks.len()).map(|i| format!("extra-apk-{}", i)).collect();

    for (i, extra_apk) in extra_apks.iter().enumerate() {
        apkdmverity_arguments.push({
            ApkDmverityArgument {
                apk: extra_apk.to_str().unwrap(),
                idsig: extra_idsigs[i].to_str().unwrap(),
                name: &extra_apk_names[i],
                saved_root_hash: if extra_root_hashes_trustful[i] {
                    Some(&extra_root_hashes_from_idsig[i])
                } else {
                    None
                },
            }
        });
    }

    // Start apkdmverity and wait for the dm-verify block
    let mut apkdmverity_child = run_apkdmverity(&apkdmverity_arguments)?;

    // While waiting for apkdmverity to mount APK, gathers public keys and root digests from
    // APEX payload.
    let apex_data_from_payload = get_apex_data_from_payload(metadata)?;

    // Writing /apex/vm-payload-metadata is to verify that the payload isn't changed.
    // Skip writing it if the debug policy ignoring identity is on
    if is_verified_boot() {
        write_apex_payload_data(saved_data, &apex_data_from_payload)?;
    }

    // Start apexd to activate APEXes
    system_properties::write("ctl.start", "apexd-vm")?;

    // TODO(inseob): add timeout
    apkdmverity_child.wait()?;

    // Do the full verification if the root_hash is un-trustful. This requires the full scanning of
    // the APK file and therefore can be very slow if the APK is large. Note that this step is
    // taken only when the root_hash is un-trustful which can be either when this is the first boot
    // of the VM or APK was updated in the host.
    // TODO(jooyung): consider multithreading to make this faster
    let main_apk_pubkey = get_public_key_from_apk(DM_MOUNTED_APK_PATH, root_hash_trustful)?;
    let extra_apks_data = extra_root_hashes_from_idsig
        .into_iter()
        .enumerate()
        .map(|(i, extra_root_hash)| {
            let mount_path = format!("/dev/block/mapper/{}", &extra_apk_names[i]);
            let apk_pubkey = get_public_key_from_apk(&mount_path, extra_root_hashes_trustful[i])?;
            Ok(ApkData { root_hash: extra_root_hash, pubkey: apk_pubkey })
        })
        .collect::<Result<Vec<_>>>()?;

    info!("payload verification successful. took {:#?}", start_time.elapsed().unwrap());

    // Use the salt from a verified instance, or generate a salt for a new instance.
    let salt = if let Some(saved_data) = saved_data {
        saved_data.salt.clone()
    } else if is_strict_boot() {
        // No need to add more entropy as a previous stage must have used a new, random salt.
        vec![0u8; 64]
    } else {
        let mut salt = vec![0u8; 64];
        salt.as_mut_slice().try_fill(&mut rand::thread_rng())?;
        salt
    };

    // At this point, we can ensure that the root_hash from the idsig file is trusted, either by
    // fully verifying the APK or by comparing it with the saved root_hash.
    Ok(MicrodroidData {
        salt,
        apk_data: ApkData { root_hash: root_hash_from_idsig, pubkey: main_apk_pubkey },
        extra_apks_data,
        apex_data: apex_data_from_payload,
    })
}

fn mount_extra_apks(config: &VmPayloadConfig, zipfuse: &mut Zipfuse) -> Result<()> {
    // For now, only the number of apks is important, as the mount point and dm-verity name is fixed
    for i in 0..config.extra_apks.len() {
        let mount_dir = format!("/mnt/extra-apk/{i}");
        create_dir(Path::new(&mount_dir)).context("Failed to create mount dir for extra apks")?;

        // don't wait, just detach
        zipfuse.mount(
            MountForExec::Disallowed,
            "fscontext=u:object_r:zipfusefs:s0,context=u:object_r:extra_apk_file:s0",
            Path::new(&format!("/dev/block/mapper/extra-apk-{i}")),
            Path::new(&mount_dir),
            format!("microdroid_manager.extra_apk.mounted.{i}"),
        )?;
    }

    Ok(())
}

fn setup_config_sysprops(config: &VmPayloadConfig) -> Result<()> {
    if config.enable_authfs {
        system_properties::write("microdroid_manager.authfs.enabled", "1")
            .context("failed to write microdroid_manager.authfs.enabled")?;
    }
    system_properties::write("microdroid_manager.config_done", "1")
        .context("failed to write microdroid_manager.config_done")?;
    Ok(())
}

// Waits until linker config is generated
fn wait_for_apex_config_done() -> Result<()> {
    wait_for_property_true(APEX_CONFIG_DONE_PROP).context("Failed waiting for apex config done")
}

fn wait_for_property_true(property_name: &str) -> Result<()> {
    let mut prop = PropertyWatcher::new(property_name)?;
    loop {
        prop.wait()?;
        if system_properties::read_bool(property_name, false)? {
            break;
        }
    }
    Ok(())
}

fn get_apk_root_hash_from_idsig<P: AsRef<Path>>(idsig_path: P) -> Result<Box<RootHash>> {
    Ok(V4Signature::from_idsig_path(idsig_path)?.hashing_info.raw_root_hash)
}

fn get_public_key_from_apk(apk: &str, root_hash_trustful: bool) -> Result<Box<[u8]>> {
    let current_sdk = get_current_sdk()?;
    if !root_hash_trustful {
        verify(apk, current_sdk).context(MicrodroidError::PayloadVerificationFailed(format!(
            "failed to verify {}",
            apk
        )))
    } else {
        get_public_key_der(apk, current_sdk)
    }
}

fn get_current_sdk() -> Result<u32> {
    let current_sdk = system_properties::read("ro.build.version.sdk")?;
    let current_sdk = current_sdk.ok_or_else(|| anyhow!("SDK version missing"))?;
    current_sdk.parse().context("Malformed SDK version")
}

fn load_config(payload_metadata: PayloadMetadata) -> Result<VmPayloadConfig> {
    match payload_metadata {
        PayloadMetadata::config_path(path) => {
            let path = Path::new(&path);
            info!("loading config from {:?}...", path);
            let file = ioutil::wait_for_file(path, WAIT_TIMEOUT)
                .with_context(|| format!("Failed to read {:?}", path))?;
            Ok(serde_json::from_reader(file)?)
        }
        PayloadMetadata::config(payload_config) => {
            let task = Task {
                type_: TaskType::MicrodroidLauncher,
                command: payload_config.payload_binary_name,
            };
            Ok(VmPayloadConfig {
                os: OsConfig { name: "microdroid".to_owned() },
                task: Some(task),
                apexes: vec![],
                extra_apks: vec![],
                prefer_staged: false,
                export_tombstones: None,
                enable_authfs: false,
            })
        }
    }
}

/// Loads the crashkernel into memory using kexec if debuggable or debug policy says so.
/// The VM should be loaded with `crashkernel=' parameter in the cmdline to allocate memory
/// for crashkernel.
fn load_crashkernel_if_supported() -> Result<()> {
    let supported = std::fs::read_to_string("/proc/cmdline")?.contains(" crashkernel=");
    info!("ramdump supported: {}", supported);

    if !supported {
        return Ok(());
    }

    let debuggable = system_properties::read_bool(DEBUGGABLE_PROP, true)?;
    let ramdump = get_debug_policy_bool(AVF_DEBUG_POLICY_RAMDUMP)?.unwrap_or_default();
    let requested = debuggable | ramdump;

    if requested {
        let status = Command::new("/system/bin/kexec_load").status()?;
        if !status.success() {
            return Err(anyhow!("Failed to load crashkernel: {:?}", status));
        }
        info!("ramdump is loaded: debuggable={debuggable}, ramdump={ramdump}");
    }
    Ok(())
}

/// Executes the given task.
fn exec_task(task: &Task, service: &Strong<dyn IVirtualMachineService>) -> Result<i32> {
    info!("executing main task {:?}...", task);
    let mut command = match task.type_ {
        TaskType::Executable => Command::new(&task.command),
        TaskType::MicrodroidLauncher => {
            let mut command = Command::new("/system/bin/microdroid_launcher");
            command.arg(find_library_path(&task.command)?);
            command
        }
    };

    unsafe {
        // SAFETY: we are not accessing any resource of the parent process.
        command.pre_exec(|| {
            info!("dropping capabilities before executing payload");
            // It is OK to continue with payload execution even if the calls below fail, since
            // whether process can use a capability is controlled by the SELinux. Dropping the
            // capabilities here is just another defense-in-depth layer.
            if let Err(e) = cap::drop_inheritable_caps() {
                error!("failed to drop inheritable capabilities: {:?}", e);
            }
            if let Err(e) = cap::drop_bounding_set() {
                error!("failed to drop bounding set: {:?}", e);
            }
            Ok(())
        });
    }

    command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());

    info!("notifying payload started");
    service.notifyPayloadStarted()?;

    let exit_status = command.spawn()?.wait()?;
    match exit_status.code() {
        Some(exit_code) => Ok(exit_code),
        None => Err(match exit_status.signal() {
            Some(signal) => anyhow!(
                "Payload exited due to signal: {} ({})",
                signal,
                Signal::try_from(signal).map_or("unknown", |s| s.as_str())
            ),
            None => anyhow!("Payload has neither exit code nor signal"),
        }),
    }
}

fn find_library_path(name: &str) -> Result<String> {
    let mut watcher = PropertyWatcher::new("ro.product.cpu.abilist")?;
    let value = watcher.read(|_name, value| Ok(value.trim().to_string()))?;
    let abi = value.split(',').next().ok_or_else(|| anyhow!("no abilist"))?;
    let path = format!("{}/lib/{}/{}", VM_APK_CONTENTS_PATH, abi, name);

    let metadata = fs::metadata(&path).with_context(|| format!("Unable to access {}", path))?;
    if !metadata.is_file() {
        bail!("{} is not a file", &path);
    }

    Ok(path)
}

fn to_hex_string(buf: &[u8]) -> String {
    buf.iter().map(|b| format!("{:02X}", b)).collect()
}

fn prepare_encryptedstore(dice_artifacts: &OwnedDiceArtifacts) -> Result<Child> {
    // Use a fixed salt to scope the derivation to this API.
    // Generated using hexdump -vn32 -e'14/1 "0x%02X, " 1 "\n"' /dev/urandom
    // TODO(b/241541860) : Move this (& other salts) to a salt container, i.e. a global enum
    let salt = [
        0xFC, 0x1D, 0x35, 0x7B, 0x96, 0xF3, 0xEF, 0x17, 0x78, 0x7D, 0x70, 0xED, 0xEA, 0xFE, 0x1D,
        0x6F, 0xB3, 0xF9, 0x40, 0xCE, 0xDD, 0x99, 0x40, 0xAA, 0xA7, 0x0E, 0x92, 0x73, 0x90, 0x86,
        0x4A, 0x75,
    ];
    let mut key = ZVec::new(ENCRYPTEDSTORE_KEYSIZE)?;
    derive_sealing_key(dice_artifacts, &salt, ENCRYPTEDSTORE_KEY_IDENTIFIER.as_bytes(), &mut key)?;

    let mut cmd = Command::new(ENCRYPTEDSTORE_BIN);
    cmd.arg("--blkdevice")
        .arg(ENCRYPTEDSTORE_BACKING_DEVICE)
        .arg("--key")
        .arg(hex::encode(&*key))
        .args(["--mountpoint", ENCRYPTEDSTORE_MOUNTPOINT])
        .spawn()
        .context("encryptedstore failed")
}
