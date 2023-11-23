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

//! Android VM control tool.

mod create_idsig;
mod create_partition;
mod run;

use android_system_virtualizationservice::aidl::android::system::virtualizationservice::{
    CpuTopology::CpuTopology, IVirtualizationService::IVirtualizationService,
    PartitionType::PartitionType, VirtualMachineAppConfig::DebugLevel::DebugLevel,
};
use anyhow::{Context, Error};
use binder::{ProcessState, Strong};
use clap::Parser;
use create_idsig::command_create_idsig;
use create_partition::command_create_partition;
use run::{command_run, command_run_app, command_run_microdroid};
use std::num::NonZeroU16;
use std::path::{Path, PathBuf};

#[derive(Debug)]
struct Idsigs(Vec<PathBuf>);

#[derive(Parser)]
enum Opt {
    /// Run a virtual machine with a config in APK
    RunApp {
        /// Path to VM Payload APK
        apk: PathBuf,

        /// Path to idsig of the APK
        idsig: PathBuf,

        /// Path to the instance image. Created if not exists.
        instance: PathBuf,

        /// Path to VM config JSON within APK (e.g. assets/vm_config.json)
        #[clap(long)]
        config_path: Option<String>,

        /// Name of VM payload binary within APK (e.g. MicrodroidTestNativeLib.so)
        #[clap(long)]
        #[clap(alias = "payload_path")]
        payload_binary_name: Option<String>,

        /// Name of VM
        #[clap(long)]
        name: Option<String>,

        /// Path to the file backing the storage.
        /// Created if the option is used but the path does not exist in the device.
        #[clap(long)]
        storage: Option<PathBuf>,

        /// Size of the storage. Used only if --storage is supplied but path does not exist
        /// Default size is 10*1024*1024
        #[clap(long)]
        storage_size: Option<u64>,

        /// Path to file for VM console output.
        #[clap(long)]
        console: Option<PathBuf>,

        /// Path to file for VM log output.
        #[clap(long)]
        log: Option<PathBuf>,

        /// Debug level of the VM. Supported values: "none" (default), and "full".
        #[clap(long, default_value = "none", value_parser = parse_debug_level)]
        debug: DebugLevel,

        /// Run VM in protected mode.
        #[clap(short, long)]
        protected: bool,

        /// Memory size (in MiB) of the VM. If unspecified, defaults to the value of `memory_mib`
        /// in the VM config file.
        #[clap(short, long)]
        mem: Option<u32>,

        /// Run VM with vCPU topology matching that of the host. If unspecified, defaults to 1 vCPU.
        #[clap(long, default_value = "one_cpu", value_parser = parse_cpu_topology)]
        cpu_topology: CpuTopology,

        /// Comma separated list of task profile names to apply to the VM
        #[clap(long)]
        task_profiles: Vec<String>,

        /// Paths to extra idsig files.
        #[clap(long = "extra-idsig")]
        extra_idsigs: Vec<PathBuf>,

        /// Port at which crosvm will start a gdb server to debug guest kernel.
        /// Note: this is only supported on Android kernels android14-5.15 and higher.
        #[clap(long)]
        gdb: Option<NonZeroU16>,
    },
    /// Run a virtual machine with Microdroid inside
    RunMicrodroid {
        /// Path to the directory where VM-related files (e.g. instance.img, apk.idsig, etc.) will
        /// be stored. If not specified a random directory under /data/local/tmp/microdroid will be
        /// created and used.
        #[clap(long)]
        work_dir: Option<PathBuf>,

        /// Name of VM
        #[clap(long)]
        name: Option<String>,

        /// Path to the file backing the storage.
        /// Created if the option is used but the path does not exist in the device.
        #[clap(long)]
        storage: Option<PathBuf>,

        /// Size of the storage. Used only if --storage is supplied but path does not exist
        /// Default size is 10*1024*1024
        #[clap(long)]
        storage_size: Option<u64>,

        /// Path to file for VM console output.
        #[clap(long)]
        console: Option<PathBuf>,

        /// Path to file for VM log output.
        #[clap(long)]
        log: Option<PathBuf>,

        /// Debug level of the VM. Supported values: "none" (default), and "full".
        #[clap(long, default_value = "full", value_parser = parse_debug_level)]
        debug: DebugLevel,

        /// Run VM in protected mode.
        #[clap(short, long)]
        protected: bool,

        /// Memory size (in MiB) of the VM. If unspecified, defaults to the value of `memory_mib`
        /// in the VM config file.
        #[clap(short, long)]
        mem: Option<u32>,

        /// Run VM with vCPU topology matching that of the host. If unspecified, defaults to 1 vCPU.
        #[clap(long, default_value = "one_cpu", value_parser = parse_cpu_topology)]
        cpu_topology: CpuTopology,

        /// Comma separated list of task profile names to apply to the VM
        #[clap(long)]
        task_profiles: Vec<String>,

        /// Port at which crosvm will start a gdb server to debug guest kernel.
        /// Note: this is only supported on Android kernels android14-5.15 and higher.
        #[clap(long)]
        gdb: Option<NonZeroU16>,
    },
    /// Run a virtual machine
    Run {
        /// Path to VM config JSON
        config: PathBuf,

        /// Name of VM
        #[clap(long)]
        name: Option<String>,

        /// Run VM with vCPU topology matching that of the host. If unspecified, defaults to 1 vCPU.
        #[clap(long, default_value = "one_cpu", value_parser = parse_cpu_topology)]
        cpu_topology: CpuTopology,

        /// Comma separated list of task profile names to apply to the VM
        #[clap(long)]
        task_profiles: Vec<String>,

        /// Path to file for VM console output.
        #[clap(long)]
        console: Option<PathBuf>,

        /// Path to file for VM log output.
        #[clap(long)]
        log: Option<PathBuf>,

        /// Port at which crosvm will start a gdb server to debug guest kernel.
        /// Note: this is only supported on Android kernels android14-5.15 and higher.
        #[clap(long)]
        gdb: Option<NonZeroU16>,
    },
    /// List running virtual machines
    List,
    /// Print information about virtual machine support
    Info,
    /// Create a new empty partition to be used as a writable partition for a VM
    CreatePartition {
        /// Path at which to create the image file
        path: PathBuf,

        /// The desired size of the partition, in bytes.
        size: u64,

        /// Type of the partition
        #[clap(short = 't', long = "type", default_value = "raw",
               value_parser = parse_partition_type)]
        partition_type: PartitionType,
    },
    /// Creates or update the idsig file by digesting the input APK file.
    CreateIdsig {
        /// Path to VM Payload APK
        apk: PathBuf,

        /// Path to idsig of the APK
        path: PathBuf,
    },
}

fn parse_debug_level(s: &str) -> Result<DebugLevel, String> {
    match s {
        "none" => Ok(DebugLevel::NONE),
        "full" => Ok(DebugLevel::FULL),
        _ => Err(format!("Invalid debug level {}", s)),
    }
}

fn parse_partition_type(s: &str) -> Result<PartitionType, String> {
    match s {
        "raw" => Ok(PartitionType::RAW),
        "instance" => Ok(PartitionType::ANDROID_VM_INSTANCE),
        _ => Err(format!("Invalid partition type {}", s)),
    }
}

fn parse_cpu_topology(s: &str) -> Result<CpuTopology, String> {
    match s {
        "one_cpu" => Ok(CpuTopology::ONE_CPU),
        "match_host" => Ok(CpuTopology::MATCH_HOST),
        _ => Err(format!("Invalid cpu topology {}", s)),
    }
}

fn get_service() -> Result<Strong<dyn IVirtualizationService>, Error> {
    let virtmgr =
        vmclient::VirtualizationService::new().context("Failed to spawn VirtualizationService")?;
    virtmgr.connect().context("Failed to connect to VirtualizationService")
}

fn main() -> Result<(), Error> {
    env_logger::init();
    let opt = Opt::parse();

    // We need to start the thread pool for Binder to work properly, especially link_to_death.
    ProcessState::start_thread_pool();

    match opt {
        Opt::RunApp {
            name,
            apk,
            idsig,
            instance,
            storage,
            storage_size,
            config_path,
            payload_binary_name,
            console,
            log,
            debug,
            protected,
            mem,
            cpu_topology,
            task_profiles,
            extra_idsigs,
            gdb,
        } => command_run_app(
            name,
            get_service()?.as_ref(),
            &apk,
            &idsig,
            &instance,
            storage.as_deref(),
            storage_size,
            config_path,
            payload_binary_name,
            console.as_deref(),
            log.as_deref(),
            debug,
            protected,
            mem,
            cpu_topology,
            task_profiles,
            &extra_idsigs,
            gdb,
        ),
        Opt::RunMicrodroid {
            name,
            work_dir,
            storage,
            storage_size,
            console,
            log,
            debug,
            protected,
            mem,
            cpu_topology,
            task_profiles,
            gdb,
        } => command_run_microdroid(
            name,
            get_service()?.as_ref(),
            work_dir,
            storage.as_deref(),
            storage_size,
            console.as_deref(),
            log.as_deref(),
            debug,
            protected,
            mem,
            cpu_topology,
            task_profiles,
            gdb,
        ),
        Opt::Run { name, config, cpu_topology, task_profiles, console, log, gdb } => {
            command_run(
                name,
                get_service()?.as_ref(),
                &config,
                console.as_deref(),
                log.as_deref(),
                /* mem */ None,
                cpu_topology,
                task_profiles,
                gdb,
            )
        }
        Opt::List => command_list(get_service()?.as_ref()),
        Opt::Info => command_info(),
        Opt::CreatePartition { path, size, partition_type } => {
            command_create_partition(get_service()?.as_ref(), &path, size, partition_type)
        }
        Opt::CreateIdsig { apk, path } => {
            command_create_idsig(get_service()?.as_ref(), &apk, &path)
        }
    }
}

/// List the VMs currently running.
fn command_list(service: &dyn IVirtualizationService) -> Result<(), Error> {
    let vms = service.debugListVms().context("Failed to get list of VMs")?;
    println!("Running VMs: {:#?}", vms);
    Ok(())
}

/// Print information about supported VM types.
fn command_info() -> Result<(), Error> {
    let non_protected_vm_supported = hypervisor_props::is_vm_supported()?;
    let protected_vm_supported = hypervisor_props::is_protected_vm_supported()?;
    match (non_protected_vm_supported, protected_vm_supported) {
        (false, false) => println!("VMs are not supported."),
        (false, true) => println!("Only protected VMs are supported."),
        (true, false) => println!("Only non-protected VMs are supported."),
        (true, true) => println!("Both protected and non-protected VMs are supported."),
    }

    if let Some(version) = hypervisor_props::version()? {
        println!("Hypervisor version: {}", version);
    } else {
        println!("Hypervisor version not set.");
    }

    if Path::new("/dev/kvm").exists() {
        println!("/dev/kvm exists.");
    } else {
        println!("/dev/kvm does not exist.");
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::CommandFactory;

    #[test]
    fn verify_app() {
        // Check that the command parsing has been configured in a valid way.
        Opt::command().debug_assert();
    }
}
