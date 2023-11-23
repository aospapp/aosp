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

//! Logic for handling the DICE values and boot operations.

use anyhow::{anyhow, bail, Context, Error, Result};
use byteorder::{NativeEndian, ReadBytesExt};
use ciborium::{cbor, ser};
use diced_open_dice::{
    bcc_handover_parse, retry_bcc_main_flow, BccHandover, Config, DiceArtifacts, DiceMode, Hash,
    Hidden, InputValues, OwnedDiceArtifacts,
};
use keystore2_crypto::ZVec;
use libc::{c_void, mmap, munmap, MAP_FAILED, MAP_PRIVATE, PROT_READ};
use microdroid_metadata::PayloadMetadata;
use openssl::hkdf::hkdf;
use openssl::md::Md;
use std::fs;
use std::os::unix::io::AsRawFd;
use std::path::{Path, PathBuf};
use std::ptr::null_mut;
use std::slice;

/// Derives a sealing key from the DICE sealing CDI.
pub fn derive_sealing_key(
    dice_artifacts: &dyn DiceArtifacts,
    salt: &[u8],
    info: &[u8],
    key: &mut [u8],
) -> Result<()> {
    Ok(hkdf(key, Md::sha256(), dice_artifacts.cdi_seal(), salt, info)?)
}

/// Artifacts that are mapped into the process address space from the driver.
pub enum DiceDriver<'a> {
    Real {
        driver_path: PathBuf,
        mmap_addr: *mut c_void,
        mmap_size: usize,
        bcc_handover: BccHandover<'a>,
    },
    Fake(OwnedDiceArtifacts),
}

impl DiceDriver<'_> {
    fn dice_artifacts(&self) -> &dyn DiceArtifacts {
        match self {
            Self::Real { bcc_handover, .. } => bcc_handover,
            Self::Fake(owned_dice_artifacts) => owned_dice_artifacts,
        }
    }

    pub fn new(driver_path: &Path) -> Result<Self> {
        if driver_path.exists() {
            log::info!("Using DICE values from driver");
        } else if super::is_strict_boot() {
            bail!("Strict boot requires DICE value from driver but none were found");
        } else {
            log::warn!("Using sample DICE values");
            let dice_artifacts = diced_sample_inputs::make_sample_bcc_and_cdis()
                .expect("Failed to create sample dice artifacts.");
            return Ok(Self::Fake(dice_artifacts));
        };

        let mut file = fs::File::open(driver_path)
            .map_err(|error| Error::new(error).context("Opening driver"))?;
        let mmap_size =
            file.read_u64::<NativeEndian>()
                .map_err(|error| Error::new(error).context("Reading driver"))? as usize;
        // It's safe to map the driver as the service will only create a single
        // mapping per process.
        let mmap_addr = unsafe {
            let fd = file.as_raw_fd();
            mmap(null_mut(), mmap_size, PROT_READ, MAP_PRIVATE, fd, 0)
        };
        if mmap_addr == MAP_FAILED {
            bail!("Failed to mmap {:?}", driver_path);
        }
        // The slice is created for the region of memory that was just
        // successfully mapped into the process address space so it will be
        // accessible and not referenced from anywhere else.
        let mmap_buf =
            unsafe { slice::from_raw_parts((mmap_addr as *const u8).as_ref().unwrap(), mmap_size) };
        let bcc_handover =
            bcc_handover_parse(mmap_buf).map_err(|_| anyhow!("Failed to parse Bcc Handover"))?;
        Ok(Self::Real {
            driver_path: driver_path.to_path_buf(),
            mmap_addr,
            mmap_size,
            bcc_handover,
        })
    }

    /// Derives a sealing key of `key_length` bytes from the DICE sealing CDI.
    pub fn get_sealing_key(&self, identifier: &[u8], key_length: usize) -> Result<ZVec> {
        // Deterministically derive a key to use for sealing data, rather than using the CDI
        // directly, so we have the chance to rotate the key if needed. A salt isn't needed as the
        // input key material is already cryptographically strong.
        let mut key = ZVec::new(key_length)?;
        let salt = &[];
        derive_sealing_key(self.dice_artifacts(), salt, identifier, &mut key)?;
        Ok(key)
    }

    pub fn derive(
        self,
        code_hash: Hash,
        config_desc: &[u8],
        authority_hash: Hash,
        debug: bool,
        hidden: Hidden,
    ) -> Result<OwnedDiceArtifacts> {
        let input_values = InputValues::new(
            code_hash,
            Config::Descriptor(config_desc),
            authority_hash,
            if debug { DiceMode::kDiceModeDebug } else { DiceMode::kDiceModeNormal },
            hidden,
        );
        let current_dice_artifacts = self.dice_artifacts();
        let next_dice_artifacts = retry_bcc_main_flow(
            current_dice_artifacts.cdi_attest(),
            current_dice_artifacts.cdi_seal(),
            current_dice_artifacts.bcc().ok_or_else(|| anyhow!("bcc is none"))?,
            &input_values,
        )
        .context("DICE derive from driver")?;
        if let Self::Real { driver_path, .. } = &self {
            // Writing to the device wipes the artifacts. The string is ignored by the driver but
            // included for documentation.
            fs::write(driver_path, "wipe")
                .map_err(|err| Error::new(err).context("Wiping driver"))?;
        }
        Ok(next_dice_artifacts)
    }
}

impl Drop for DiceDriver<'_> {
    fn drop(&mut self) {
        if let &mut Self::Real { mmap_addr, mmap_size, .. } = self {
            // All references to the mapped region have the same lifetime as self. Since self is
            // being dropped, so are all the references to the mapped region meaning its safe to
            // unmap.
            let ret = unsafe { munmap(mmap_addr, mmap_size) };
            if ret != 0 {
                log::warn!("Failed to munmap ({})", ret);
            }
        }
    }
}

/// Returns a configuration descriptor of the given payload following the BCC's specification:
/// https://cs.android.com/android/platform/superproject/+/master:hardware/interfaces/security/rkp/aidl/android/hardware/security/keymint/ProtectedData.aidl
/// {
///   -70002: "Microdroid payload",
///   ? -71000: tstr // payload_config_path
///   ? -71001: PayloadConfig
/// }
/// PayloadConfig = {
///   1: tstr // payload_binary_name
/// }
pub fn format_payload_config_descriptor(payload_metadata: &PayloadMetadata) -> Result<Vec<u8>> {
    const MICRODROID_PAYLOAD_COMPONENT_NAME: &str = "Microdroid payload";

    let config_descriptor_cbor_value = match payload_metadata {
        PayloadMetadata::config_path(payload_config_path) => cbor!({
            -70002 => MICRODROID_PAYLOAD_COMPONENT_NAME,
            -71000 => payload_config_path
        }),
        PayloadMetadata::config(payload_config) => cbor!({
            -70002 => MICRODROID_PAYLOAD_COMPONENT_NAME,
            -71001 => {1 => payload_config.payload_binary_name}
        }),
    }
    .context("Failed to build a CBOR Value from payload metadata")?;
    let mut config_descriptor = Vec::new();
    ser::into_writer(&config_descriptor_cbor_value, &mut config_descriptor)?;
    Ok(config_descriptor)
}

#[cfg(test)]
mod tests {
    use super::*;
    use microdroid_metadata::PayloadConfig;

    #[test]
    fn payload_metadata_with_path_formats_correctly() -> Result<()> {
        let payload_metadata = PayloadMetadata::config_path("/config_path".to_string());
        let config_descriptor = format_payload_config_descriptor(&payload_metadata)?;
        static EXPECTED_CONFIG_DESCRIPTOR: &[u8] = &[
            0xa2, 0x3a, 0x00, 0x01, 0x11, 0x71, 0x72, 0x4d, 0x69, 0x63, 0x72, 0x6f, 0x64, 0x72,
            0x6f, 0x69, 0x64, 0x20, 0x70, 0x61, 0x79, 0x6c, 0x6f, 0x61, 0x64, 0x3a, 0x00, 0x01,
            0x15, 0x57, 0x6c, 0x2f, 0x63, 0x6f, 0x6e, 0x66, 0x69, 0x67, 0x5f, 0x70, 0x61, 0x74,
            0x68,
        ];
        assert_eq!(EXPECTED_CONFIG_DESCRIPTOR, &config_descriptor);
        Ok(())
    }

    #[test]
    fn payload_metadata_with_config_formats_correctly() -> Result<()> {
        let payload_config = PayloadConfig {
            payload_binary_name: "payload_binary".to_string(),
            ..Default::default()
        };
        let payload_metadata = PayloadMetadata::config(payload_config);
        let config_descriptor = format_payload_config_descriptor(&payload_metadata)?;
        static EXPECTED_CONFIG_DESCRIPTOR: &[u8] = &[
            0xa2, 0x3a, 0x00, 0x01, 0x11, 0x71, 0x72, 0x4d, 0x69, 0x63, 0x72, 0x6f, 0x64, 0x72,
            0x6f, 0x69, 0x64, 0x20, 0x70, 0x61, 0x79, 0x6c, 0x6f, 0x61, 0x64, 0x3a, 0x00, 0x01,
            0x15, 0x58, 0xa1, 0x01, 0x6e, 0x70, 0x61, 0x79, 0x6c, 0x6f, 0x61, 0x64, 0x5f, 0x62,
            0x69, 0x6e, 0x61, 0x72, 0x79,
        ];
        assert_eq!(EXPECTED_CONFIG_DESCRIPTOR, &config_descriptor);
        Ok(())
    }
}
