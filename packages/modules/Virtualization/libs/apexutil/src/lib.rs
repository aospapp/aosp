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

//! Routines for handling APEX payload

use std::fs::File;
use std::io::{self, Read};
use thiserror::Error;
use vbmeta::VbMetaImage;
use zip::result::ZipError;
use zip::ZipArchive;

const APEX_PUBKEY_ENTRY: &str = "apex_pubkey";
const APEX_PAYLOAD_ENTRY: &str = "apex_payload.img";

/// Errors from parsing an APEX.
#[derive(Debug, Error)]
pub enum ApexParseError {
    /// There was an IO error.
    #[error("IO error")]
    Io(#[from] io::Error),
    /// The Zip archive was invalid.
    #[error("Cannot read zip archive")]
    InvalidZip(&'static str),
    /// The apex_pubkey file was missing from the APEX.
    #[error("APEX doesn't contain apex_pubkey")]
    PubkeyMissing,
    /// The apex_payload.img file was missing from the APEX.
    #[error("APEX doesn't contain apex_payload.img")]
    PayloadMissing,
    /// There was no hashtree descriptor in the APEX payload's VBMeta image.
    #[error("Non-hashtree descriptor found in payload's VBMeta image")]
    DescriptorNotHashtree,
    /// There was an error parsing the APEX payload's VBMeta image.
    #[error("Could not parse payload's VBMeta image")]
    PayloadVbmetaError(#[from] vbmeta::VbMetaImageParseError),
}

/// Errors from verifying an APEX.
#[derive(Debug, Error)]
pub enum ApexVerificationError {
    /// There was an error parsing the APEX.
    #[error("Cannot parse APEX file")]
    ParseError(#[from] ApexParseError),
    /// There was an error validating the APEX payload's VBMeta image.
    #[error("Could not parse payload's VBMeta image")]
    PayloadVbmetaError(#[from] vbmeta::VbMetaImageVerificationError),
    /// The APEX payload was not verified with the apex_pubkey.
    #[error("APEX pubkey mismatch")]
    ApexPubkeyMistmatch,
}

/// Verification result holds public key and root digest of apex_payload.img
pub struct ApexVerificationResult {
    /// The public key that verifies the payload signature.
    pub public_key: Vec<u8>,
    /// The root digest of the payload hashtree.
    pub root_digest: Vec<u8>,
}

/// Verify APEX payload by AVB verification and return public key and root digest
pub fn verify(path: &str) -> Result<ApexVerificationResult, ApexVerificationError> {
    let apex_file = File::open(path).map_err(ApexParseError::Io)?;
    let (public_key, image_offset, image_size) = get_public_key_and_image_info(&apex_file)?;
    let vbmeta = VbMetaImage::verify_reader_region(apex_file, image_offset, image_size)?;
    let root_digest = find_root_digest(&vbmeta)?;
    match vbmeta.public_key() {
        Some(payload_public_key) if public_key == payload_public_key => {
            Ok(ApexVerificationResult { public_key, root_digest })
        }
        _ => Err(ApexVerificationError::ApexPubkeyMistmatch),
    }
}

fn find_root_digest(vbmeta: &VbMetaImage) -> Result<Vec<u8>, ApexParseError> {
    // APEXs use the root digest from the first hashtree descriptor to describe the payload.
    for descriptor in vbmeta.descriptors()?.iter() {
        if let vbmeta::Descriptor::Hashtree(_) = descriptor {
            return Ok(descriptor.to_hashtree()?.root_digest().to_vec());
        }
    }
    Err(ApexParseError::DescriptorNotHashtree)
}

/// Gets the hash of the payload's verified VBMeta image data.
pub fn get_payload_vbmeta_image_hash(path: &str) -> Result<Vec<u8>, ApexVerificationError> {
    let apex_file = File::open(path).map_err(ApexParseError::Io)?;
    let (_, offset, size) = get_public_key_and_image_info(&apex_file)?;
    let vbmeta = VbMetaImage::verify_reader_region(apex_file, offset, size)?;
    Ok(vbmeta.hash().ok_or(ApexVerificationError::ApexPubkeyMistmatch)?.to_vec())
}

fn get_public_key_and_image_info(apex_file: &File) -> Result<(Vec<u8>, u64, u64), ApexParseError> {
    let mut z = ZipArchive::new(apex_file).map_err(|err| match err {
        ZipError::Io(err) => ApexParseError::Io(err),
        ZipError::InvalidArchive(s) | ZipError::UnsupportedArchive(s) => {
            ApexParseError::InvalidZip(s)
        }
        ZipError::FileNotFound => unreachable!(),
    })?;

    let mut public_key = Vec::new();
    z.by_name(APEX_PUBKEY_ENTRY)
        .map_err(|err| match err {
            ZipError::Io(err) => ApexParseError::Io(err),
            ZipError::FileNotFound => ApexParseError::PubkeyMissing,
            ZipError::InvalidArchive(s) | ZipError::UnsupportedArchive(s) => {
                ApexParseError::InvalidZip(s)
            }
        })?
        .read_to_end(&mut public_key)?;

    let (image_offset, image_size) = z
        .by_name(APEX_PAYLOAD_ENTRY)
        .map(|f| (f.data_start(), f.size()))
        .map_err(|err| match err {
            ZipError::Io(err) => ApexParseError::Io(err),
            ZipError::FileNotFound => ApexParseError::PayloadMissing,
            ZipError::InvalidArchive(s) | ZipError::UnsupportedArchive(s) => {
                ApexParseError::InvalidZip(s)
            }
        })?;

    Ok((public_key, image_offset, image_size))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn apex_verification_returns_valid_result() {
        let res = verify("tests/data/test.apex").unwrap();
        // The expected hex is generated when we ran the method the first time.
        assert_eq!(
            hex::encode(res.root_digest),
            "fe11ab17da0a3a738b54bdc3a13f6139cbdf91ec32f001f8d4bbbf8938e04e39"
        );
    }

    #[test]
    fn payload_vbmeta_has_valid_image_hash() {
        let result = get_payload_vbmeta_image_hash("tests/data/test.apex").unwrap();
        assert_eq!(
            hex::encode(result),
            "296e32a76544de9da01713e471403ab4667705ad527bb4f1fac0cf61e7ce122d"
        );
    }
}
