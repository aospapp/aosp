// Copyright 2023, The Android Open Source Project
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

use crate::hvc;
use core::fmt;
use core::mem::size_of;

pub enum Error {
    /// Error during SMCCC TRNG call.
    Trng(hvc::trng::Error),
    /// Unsupported SMCCC TRNG version.
    UnsupportedVersion((u16, u16)),
}

impl From<hvc::trng::Error> for Error {
    fn from(e: hvc::trng::Error) -> Self {
        Self::Trng(e)
    }
}

pub type Result<T> = core::result::Result<T, Error>;

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Self::Trng(e) => write!(f, "SMCCC TRNG error: {e}"),
            Self::UnsupportedVersion((x, y)) => {
                write!(f, "Unsupported SMCCC TRNG version v{x}.{y}")
            }
        }
    }
}

/// Configure the source of entropy.
pub fn init() -> Result<()> {
    match hvc::trng_version()? {
        (1, _) => Ok(()),
        version => Err(Error::UnsupportedVersion(version)),
    }
}

fn fill_with_entropy(s: &mut [u8]) -> Result<()> {
    const MAX_BYTES_PER_CALL: usize = size_of::<hvc::TrngRng64Entropy>();

    let (aligned, remainder) = s.split_at_mut(s.len() - s.len() % MAX_BYTES_PER_CALL);

    for chunk in aligned.chunks_exact_mut(MAX_BYTES_PER_CALL) {
        let (r, s, t) = repeat_trng_rnd(chunk.len())?;

        let mut words = chunk.chunks_exact_mut(size_of::<u64>());
        words.next().unwrap().clone_from_slice(&t.to_ne_bytes());
        words.next().unwrap().clone_from_slice(&s.to_ne_bytes());
        words.next().unwrap().clone_from_slice(&r.to_ne_bytes());
    }

    if !remainder.is_empty() {
        let mut entropy = [0; MAX_BYTES_PER_CALL];
        let (r, s, t) = repeat_trng_rnd(remainder.len())?;

        let mut words = entropy.chunks_exact_mut(size_of::<u64>());
        words.next().unwrap().clone_from_slice(&t.to_ne_bytes());
        words.next().unwrap().clone_from_slice(&s.to_ne_bytes());
        words.next().unwrap().clone_from_slice(&r.to_ne_bytes());

        remainder.clone_from_slice(&entropy[..remainder.len()]);
    }

    Ok(())
}

fn repeat_trng_rnd(n_bytes: usize) -> hvc::trng::Result<hvc::TrngRng64Entropy> {
    let bits = usize::try_from(u8::BITS).unwrap();
    let n_bits = (n_bytes * bits).try_into().unwrap();
    loop {
        match hvc::trng_rnd64(n_bits) {
            Err(hvc::trng::Error::NoEntropy) => continue,
            res => return res,
        }
    }
}

pub fn random_array<const N: usize>() -> Result<[u8; N]> {
    let mut arr = [0; N];
    fill_with_entropy(&mut arr)?;
    Ok(arr)
}

#[no_mangle]
extern "C" fn CRYPTO_sysrand_for_seed(out: *mut u8, req: usize) {
    CRYPTO_sysrand(out, req)
}

#[no_mangle]
extern "C" fn CRYPTO_sysrand(out: *mut u8, req: usize) {
    // SAFETY - We need to assume that out points to valid memory of size req.
    let s = unsafe { core::slice::from_raw_parts_mut(out, req) };
    let _ = fill_with_entropy(s);
}
