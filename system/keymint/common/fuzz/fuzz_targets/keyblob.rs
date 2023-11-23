//! Fuzzer for keyblob parsing.

#![no_main]
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // `data` allegedly holds a CBOR-serialized keyblob that has arrived from userspace.  Do we
    // trust it? I don't think so...
    let _ = kmr_common::keyblob::EncryptedKeyBlob::new(data);
});
