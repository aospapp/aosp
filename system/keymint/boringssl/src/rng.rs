#[cfg(soong)]
use bssl_ffi as ffi;
use kmr_common::crypto;

/// [`crypto::Rng`] implementation based on BoringSSL.
#[derive(Default)]
pub struct BoringRng;

impl crypto::Rng for BoringRng {
    fn add_entropy(&mut self, data: &[u8]) {
        #[cfg(soong)]
        unsafe {
            // Safety: `data` is a valid slice.
            ffi::RAND_seed(data.as_ptr() as *const libc::c_void, data.len() as libc::c_int);
        }
        #[cfg(not(soong))]
        unsafe {
            // Safety: `data` is a valid slice.
            ffi::RAND_add(
                data.as_ptr() as *const libc::c_void,
                data.len() as libc::c_int,
                data.len() as f64,
            );
        }
    }
    fn fill_bytes(&mut self, dest: &mut [u8]) {
        openssl::rand::rand_bytes(dest).unwrap(); // safe: BoringSSL's RAND_bytes() never fails
    }
}
