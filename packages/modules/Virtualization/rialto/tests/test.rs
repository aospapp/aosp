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

//! Integration test for Rialto.

use android_system_virtualizationservice::{
    aidl::android::system::virtualizationservice::{
        CpuTopology::CpuTopology, DiskImage::DiskImage, Partition::Partition,
        PartitionType::PartitionType, VirtualMachineConfig::VirtualMachineConfig,
        VirtualMachineRawConfig::VirtualMachineRawConfig,
    },
    binder::{ParcelFileDescriptor, ProcessState},
};
use anyhow::{anyhow, Context, Error};
use log::info;
use std::fs::File;
use std::io::{self, BufRead, BufReader};
use std::os::unix::io::FromRawFd;
use std::panic;
use std::thread;
use std::time::Duration;
use vmclient::{DeathReason, VmInstance};

const SIGNED_RIALTO_PATH: &str = "/data/local/tmp/rialto_test/arm64/rialto.bin";
const UNSIGNED_RIALTO_PATH: &str = "/data/local/tmp/rialto_test/arm64/rialto_unsigned.bin";
const INSTANCE_IMG_PATH: &str = "/data/local/tmp/rialto_test/arm64/instance.img";
const INSTANCE_IMG_SIZE: i64 = 1024 * 1024; // 1MB

#[test]
fn boot_rialto_in_protected_vm_successfully() -> Result<(), Error> {
    boot_rialto_successfully(
        SIGNED_RIALTO_PATH,
        true, // protected_vm
    )
}

#[test]
fn boot_rialto_in_unprotected_vm_successfully() -> Result<(), Error> {
    boot_rialto_successfully(
        UNSIGNED_RIALTO_PATH,
        false, // protected_vm
    )
}

fn boot_rialto_successfully(rialto_path: &str, protected_vm: bool) -> Result<(), Error> {
    android_logger::init_once(
        android_logger::Config::default().with_tag("rialto").with_min_level(log::Level::Debug),
    );

    // Redirect panic messages to logcat.
    panic::set_hook(Box::new(|panic_info| {
        log::error!("{}", panic_info);
    }));

    // We need to start the thread pool for Binder to work properly, especially link_to_death.
    ProcessState::start_thread_pool();

    let virtmgr =
        vmclient::VirtualizationService::new().context("Failed to spawn VirtualizationService")?;
    let service = virtmgr.connect().context("Failed to connect to VirtualizationService")?;

    let rialto = File::open(rialto_path).context("Failed to open Rialto kernel binary")?;
    let console = android_log_fd()?;
    let log = android_log_fd()?;

    let disks = if protected_vm {
        let instance_img = File::options()
            .create(true)
            .read(true)
            .write(true)
            .truncate(true)
            .open(INSTANCE_IMG_PATH)?;
        let instance_img = ParcelFileDescriptor::new(instance_img);

        service
            .initializeWritablePartition(
                &instance_img,
                INSTANCE_IMG_SIZE,
                PartitionType::ANDROID_VM_INSTANCE,
            )
            .context("Failed to initialize instange.img")?;
        let writable_partitions = vec![Partition {
            label: "vm-instance".to_owned(),
            image: Some(instance_img),
            writable: true,
        }];
        vec![DiskImage { image: None, partitions: writable_partitions, writable: true }]
    } else {
        vec![]
    };

    let config = VirtualMachineConfig::RawConfig(VirtualMachineRawConfig {
        name: String::from("RialtoTest"),
        kernel: None,
        initrd: None,
        params: None,
        bootloader: Some(ParcelFileDescriptor::new(rialto)),
        disks,
        protectedVm: protected_vm,
        memoryMib: 300,
        cpuTopology: CpuTopology::ONE_CPU,
        platformVersion: "~1.0".to_string(),
        taskProfiles: vec![],
        gdbPort: 0, // No gdb
    });
    let vm = VmInstance::create(service.as_ref(), &config, Some(console), Some(log), None)
        .context("Failed to create VM")?;

    vm.start().context("Failed to start VM")?;

    // Wait for VM to finish, and check that it shut down cleanly.
    let death_reason = vm
        .wait_for_death_with_timeout(Duration::from_secs(10))
        .ok_or_else(|| anyhow!("Timed out waiting for VM exit"))?;
    assert_eq!(death_reason, DeathReason::Shutdown);

    Ok(())
}

fn android_log_fd() -> io::Result<File> {
    let (reader_fd, writer_fd) = nix::unistd::pipe()?;

    // SAFETY: These are new FDs with no previous owner.
    let reader = unsafe { File::from_raw_fd(reader_fd) };
    let writer = unsafe { File::from_raw_fd(writer_fd) };

    thread::spawn(|| {
        for line in BufReader::new(reader).lines() {
            info!("{}", line.unwrap());
        }
    });
    Ok(writer)
}
