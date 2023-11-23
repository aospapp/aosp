use crate::{openssl_err, ossl};
use alloc::boxed::Box;
use alloc::vec::Vec;
use kmr_common::{crypto, crypto::OpaqueOr, explicit, vec_try, Error};
use openssl::symm::{Cipher, Crypter};

/// [`crypto::Des`] implementation based on BoringSSL.
pub struct BoringDes;

impl crypto::Des for BoringDes {
    fn begin(
        &self,
        key: OpaqueOr<crypto::des::Key>,
        mode: crypto::des::Mode,
        dir: crypto::SymmetricOperation,
    ) -> Result<Box<dyn crypto::EmittingOperation>, Error> {
        let key = explicit!(key)?;
        let dir_mode = match dir {
            crypto::SymmetricOperation::Encrypt => openssl::symm::Mode::Encrypt,
            crypto::SymmetricOperation::Decrypt => openssl::symm::Mode::Decrypt,
        };
        let crypter = match mode {
            crypto::des::Mode::EcbNoPadding | crypto::des::Mode::EcbPkcs7Padding => {
                let cipher = Cipher::des_ede3();
                let mut crypter = Crypter::new(cipher, dir_mode, &key.0, None)
                    .map_err(openssl_err!("failed to create ECB Crypter"))?;
                if let crypto::des::Mode::EcbPkcs7Padding = mode {
                    crypter.pad(true);
                } else {
                    crypter.pad(false);
                }
                crypter
            }

            crypto::des::Mode::CbcNoPadding { nonce: n }
            | crypto::des::Mode::CbcPkcs7Padding { nonce: n } => {
                let cipher = Cipher::des_ede3_cbc();
                let mut crypter = Crypter::new(cipher, dir_mode, &key.0, Some(&n[..]))
                    .map_err(openssl_err!("failed to create CBC Crypter"))?;
                if let crypto::des::Mode::CbcPkcs7Padding { nonce: _ } = mode {
                    crypter.pad(true);
                } else {
                    crypter.pad(false);
                }
                crypter
            }
        };

        Ok(Box::new(BoringDesOperation { crypter }))
    }
}

/// [`crypto::DesOperation`] implementation based on BoringSSL.
pub struct BoringDesOperation {
    crypter: openssl::symm::Crypter,
}

impl crypto::EmittingOperation for BoringDesOperation {
    fn update(&mut self, data: &[u8]) -> Result<Vec<u8>, Error> {
        let mut output = vec_try![0; data.len() + crypto::des::BLOCK_SIZE]?;
        let out_len = self
            .crypter
            .update(data, &mut output[..])
            .map_err(openssl_err!("update with {} bytes of data failed", data.len()))?;
        output.truncate(out_len);
        Ok(output)
    }

    fn finish(mut self: Box<Self>) -> Result<Vec<u8>, Error> {
        let mut output = vec_try![0; crypto::des::BLOCK_SIZE]?;
        let out_len = ossl!(self.crypter.finalize(&mut output))?;
        output.truncate(out_len);
        Ok(output)
    }
}
