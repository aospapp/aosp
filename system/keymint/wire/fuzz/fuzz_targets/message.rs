//! Fuzzer for request message parsing.

#![no_main]
use kmr_wire::AsCborValue;
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // `data` allegedly holds a CBOR-serialized request message that has arrived from the HAL
    // service in userspace.  Do we trust it? I don't think so...
    let _ = kmr_wire::PerformOpReq::from_slice(data);
});
