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

//! Command to run a VM.

use crate::create_partition::command_create_partition;
use android_system_virtualizationservice::aidl::android::system::virtualizationservice::{
    CpuTopology::CpuTopology,
    IVirtualizationService::IVirtualizationService,
    PartitionType::PartitionType,
    VirtualMachineAppConfig::{DebugLevel::DebugLevel, Payload::Payload, VirtualMachineAppConfig},
    VirtualMachineConfig::VirtualMachineConfig,
    VirtualMachinePayloadConfig::VirtualMachinePayloadConfig,
    VirtualMachineState::VirtualMachineState,
};
use anyhow::{anyhow, bail, Context, Error};
use binder::ParcelFileDescriptor;
use glob::glob;
use microdroid_payload_config::VmPayloadConfig;
use rand::{distributions::Alphanumeric, Rng};
use std::fs;
use std::fs::File;
use std::io;
use std::num::NonZeroU16;
use std::os::unix::io::{AsRawFd, FromRawFd};
use std::path::{Path, PathBuf};
use vmclient::{ErrorCode, VmInstance};
use vmconfig::{open_parcel_file, VmConfig};
use zip::ZipArchive;

/// Run a VM from the given APK, idsig, and config.
#[allow(clippy::too_many_arguments)]
pub fn command_run_app(
    name: Option<String>,
    service: &dyn IVirtualizationService,
    apk: &Path,
    idsig: &Path,
    instance: &Path,
    storage: Option<&Path>,
    storage_size: Option<u64>,
    config_path: Option<String>,
    payload_binary_name: Option<String>,
    console_path: Option<&Path>,
    log_path: Option<&Path>,
    debug_level: DebugLevel,
    protected: bool,
    mem: Option<u32>,
    cpu_topology: CpuTopology,
    task_profiles: Vec<String>,
    extra_idsigs: &[PathBuf],
    gdb: Option<NonZeroU16>,
) -> Result<(), Error> {
    let apk_file = File::open(apk).context("Failed to open APK file")?;

    let extra_apks = match config_path.as_deref() {
        Some(path) => parse_extra_apk_list(apk, path)?,
        None => vec![],
    };

    if extra_apks.len() != extra_idsigs.len() {
        bail!(
            "Found {} extra apks, but there are {} extra idsigs",
            extra_apks.len(),
            extra_idsigs.len()
        )
    }

    for i in 0..extra_apks.len() {
        let extra_apk_fd = ParcelFileDescriptor::new(File::open(&extra_apks[i])?);
        let extra_idsig_fd = ParcelFileDescriptor::new(File::create(&extra_idsigs[i])?);
        service.createOrUpdateIdsigFile(&extra_apk_fd, &extra_idsig_fd)?;
    }

    let idsig_file = File::create(idsig).context("Failed to create idsig file")?;

    let apk_fd = ParcelFileDescriptor::new(apk_file);
    let idsig_fd = ParcelFileDescriptor::new(idsig_file);
    service.createOrUpdateIdsigFile(&apk_fd, &idsig_fd)?;

    let idsig_file = File::open(idsig).context("Failed to open idsig file")?;
    let idsig_fd = ParcelFileDescriptor::new(idsig_file);

    if !instance.exists() {
        const INSTANCE_FILE_SIZE: u64 = 10 * 1024 * 1024;
        command_create_partition(
            service,
            instance,
            INSTANCE_FILE_SIZE,
            PartitionType::ANDROID_VM_INSTANCE,
        )?;
    }

    let storage = if let Some(path) = storage {
        if !path.exists() {
            command_create_partition(
                service,
                path,
                storage_size.unwrap_or(10 * 1024 * 1024),
                PartitionType::ENCRYPTEDSTORE,
            )?;
        }
        Some(open_parcel_file(path, true)?)
    } else {
        None
    };

    let extra_idsig_files: Result<Vec<File>, _> = extra_idsigs.iter().map(File::open).collect();
    let extra_idsig_fds = extra_idsig_files?.into_iter().map(ParcelFileDescriptor::new).collect();

    let payload = if let Some(config_path) = config_path {
        if payload_binary_name.is_some() {
            bail!("Only one of --config-path or --payload-binary-name can be defined")
        }
        Payload::ConfigPath(config_path)
    } else if let Some(payload_binary_name) = payload_binary_name {
        Payload::PayloadConfig(VirtualMachinePayloadConfig {
            payloadBinaryName: payload_binary_name,
        })
    } else {
        bail!("Either --config-path or --payload-binary-name must be defined")
    };

    let payload_config_str = format!("{:?}!{:?}", apk, payload);

    let config = VirtualMachineConfig::AppConfig(VirtualMachineAppConfig {
        name: name.unwrap_or_else(|| String::from("VmRunApp")),
        apk: apk_fd.into(),
        idsig: idsig_fd.into(),
        extraIdsigs: extra_idsig_fds,
        instanceImage: open_parcel_file(instance, true /* writable */)?.into(),
        encryptedStorageImage: storage,
        payload,
        debugLevel: debug_level,
        protectedVm: protected,
        memoryMib: mem.unwrap_or(0) as i32, // 0 means use the VM default
        cpuTopology: cpu_topology,
        taskProfiles: task_profiles,
        gdbPort: gdb.map(u16::from).unwrap_or(0) as i32, // 0 means no gdb
    });
    run(service, &config, &payload_config_str, console_path, log_path)
}

fn find_empty_payload_apk_path() -> Result<PathBuf, Error> {
    const GLOB_PATTERN: &str = "/apex/com.android.virt/app/**/EmptyPayloadApp*.apk";
    let mut entries: Vec<PathBuf> =
        glob(GLOB_PATTERN).context("failed to glob")?.filter_map(|e| e.ok()).collect();
    if entries.len() > 1 {
        return Err(anyhow!("Found more than one apk matching {}", GLOB_PATTERN));
    }
    match entries.pop() {
        Some(path) => Ok(path),
        None => Err(anyhow!("No apks match {}", GLOB_PATTERN)),
    }
}

fn create_work_dir() -> Result<PathBuf, Error> {
    let s: String =
        rand::thread_rng().sample_iter(&Alphanumeric).take(17).map(char::from).collect();
    let work_dir = PathBuf::from("/data/local/tmp/microdroid").join(s);
    println!("creating work dir {}", work_dir.display());
    fs::create_dir_all(&work_dir).context("failed to mkdir")?;
    Ok(work_dir)
}

/// Run a VM with Microdroid
#[allow(clippy::too_many_arguments)]
pub fn command_run_microdroid(
    name: Option<String>,
    service: &dyn IVirtualizationService,
    work_dir: Option<PathBuf>,
    storage: Option<&Path>,
    storage_size: Option<u64>,
    console_path: Option<&Path>,
    log_path: Option<&Path>,
    debug_level: DebugLevel,
    protected: bool,
    mem: Option<u32>,
    cpu_topology: CpuTopology,
    task_profiles: Vec<String>,
    gdb: Option<NonZeroU16>,
) -> Result<(), Error> {
    let apk = find_empty_payload_apk_path()?;
    println!("found path {}", apk.display());

    let work_dir = work_dir.unwrap_or(create_work_dir()?);
    let idsig = work_dir.join("apk.idsig");
    println!("apk.idsig path: {}", idsig.display());
    let instance_img = work_dir.join("instance.img");
    println!("instance.img path: {}", instance_img.display());

    let payload_binary_name = "MicrodroidEmptyPayloadJniLib.so";
    let extra_sig = [];
    command_run_app(
        name,
        service,
        &apk,
        &idsig,
        &instance_img,
        storage,
        storage_size,
        /* config_path= */ None,
        Some(payload_binary_name.to_owned()),
        console_path,
        log_path,
        debug_level,
        protected,
        mem,
        cpu_topology,
        task_profiles,
        &extra_sig,
        gdb,
    )
}

/// Run a VM from the given configuration file.
#[allow(clippy::too_many_arguments)]
pub fn command_run(
    name: Option<String>,
    service: &dyn IVirtualizationService,
    config_path: &Path,
    console_path: Option<&Path>,
    log_path: Option<&Path>,
    mem: Option<u32>,
    cpu_topology: CpuTopology,
    task_profiles: Vec<String>,
    gdb: Option<NonZeroU16>,
) -> Result<(), Error> {
    let config_file = File::open(config_path).context("Failed to open config file")?;
    let mut config =
        VmConfig::load(&config_file).context("Failed to parse config file")?.to_parcelable()?;
    if let Some(mem) = mem {
        config.memoryMib = mem as i32;
    }
    if let Some(name) = name {
        config.name = name;
    } else {
        config.name = String::from("VmRun");
    }
    if let Some(gdb) = gdb {
        config.gdbPort = gdb.get() as i32;
    }
    config.cpuTopology = cpu_topology;
    config.taskProfiles = task_profiles;
    run(
        service,
        &VirtualMachineConfig::RawConfig(config),
        &format!("{:?}", config_path),
        console_path,
        log_path,
    )
}

fn state_to_str(vm_state: VirtualMachineState) -> &'static str {
    match vm_state {
        VirtualMachineState::NOT_STARTED => "NOT_STARTED",
        VirtualMachineState::STARTING => "STARTING",
        VirtualMachineState::STARTED => "STARTED",
        VirtualMachineState::READY => "READY",
        VirtualMachineState::FINISHED => "FINISHED",
        VirtualMachineState::DEAD => "DEAD",
        _ => "(invalid state)",
    }
}

fn run(
    service: &dyn IVirtualizationService,
    config: &VirtualMachineConfig,
    payload_config: &str,
    console_path: Option<&Path>,
    log_path: Option<&Path>,
) -> Result<(), Error> {
    let console = if let Some(console_path) = console_path {
        Some(
            File::create(console_path)
                .with_context(|| format!("Failed to open console file {:?}", console_path))?,
        )
    } else {
        Some(duplicate_stdout()?)
    };
    let log = if let Some(log_path) = log_path {
        Some(
            File::create(log_path)
                .with_context(|| format!("Failed to open log file {:?}", log_path))?,
        )
    } else {
        Some(duplicate_stdout()?)
    };

    let callback = Box::new(Callback {});
    let vm = VmInstance::create(service, config, console, log, Some(callback))
        .context("Failed to create VM")?;
    vm.start().context("Failed to start VM")?;

    println!(
        "Created VM from {} with CID {}, state is {}.",
        payload_config,
        vm.cid(),
        state_to_str(vm.state()?)
    );

    // Wait until the VM or VirtualizationService dies. If we just returned immediately then the
    // IVirtualMachine Binder object would be dropped and the VM would be killed.
    let death_reason = vm.wait_for_death();
    println!("VM ended: {:?}", death_reason);
    Ok(())
}

fn parse_extra_apk_list(apk: &Path, config_path: &str) -> Result<Vec<String>, Error> {
    let mut archive = ZipArchive::new(File::open(apk)?)?;
    let config_file = archive.by_name(config_path)?;
    let config: VmPayloadConfig = serde_json::from_reader(config_file)?;
    Ok(config.extra_apks.into_iter().map(|x| x.path).collect())
}

struct Callback {}

impl vmclient::VmCallback for Callback {
    fn on_payload_started(&self, _cid: i32) {
        eprintln!("payload started");
    }

    fn on_payload_ready(&self, _cid: i32) {
        eprintln!("payload is ready");
    }

    fn on_payload_finished(&self, _cid: i32, exit_code: i32) {
        eprintln!("payload finished with exit code {}", exit_code);
    }

    fn on_error(&self, _cid: i32, error_code: ErrorCode, message: &str) {
        eprintln!("VM encountered an error: code={:?}, message={}", error_code, message);
    }
}

/// Safely duplicate the standard output file descriptor.
fn duplicate_stdout() -> io::Result<File> {
    let stdout_fd = io::stdout().as_raw_fd();
    // Safe because this just duplicates a file descriptor which we know to be valid, and we check
    // for an error.
    let dup_fd = unsafe { libc::dup(stdout_fd) };
    if dup_fd < 0 {
        Err(io::Error::last_os_error())
    } else {
        // Safe because we have just duplicated the file descriptor so we own it, and `from_raw_fd`
        // takes ownership of it.
        Ok(unsafe { File::from_raw_fd(dup_fd) })
    }
}
