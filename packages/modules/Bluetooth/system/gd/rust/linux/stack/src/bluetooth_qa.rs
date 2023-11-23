//! Anything related to the Qualification API (IBluetoothQA).

use crate::Message;
use bt_topshim::btif::BtDiscMode;
use tokio::sync::mpsc::Sender;

/// Defines the Qualification API
pub trait IBluetoothQA {
    fn add_media_player(&self, name: String, browsing_supported: bool);
    fn rfcomm_send_msc(&self, dlci: u8, addr: String);

    /// Returns adapter's discoverable mode.
    fn get_discoverable_mode(&self) -> BtDiscMode;
}

pub struct BluetoothQA {
    tx: Sender<Message>,
    disc_mode: BtDiscMode,
}

impl BluetoothQA {
    pub fn new(tx: Sender<Message>) -> BluetoothQA {
        BluetoothQA { tx, disc_mode: BtDiscMode::NonDiscoverable }
    }

    pub fn handle_discoverable_mode_changed(&mut self, mode: BtDiscMode) {
        self.disc_mode = mode;
    }
}

impl IBluetoothQA for BluetoothQA {
    fn add_media_player(&self, name: String, browsing_supported: bool) {
        let txl = self.tx.clone();
        tokio::spawn(async move {
            let _ = txl.send(Message::QaAddMediaPlayer(name, browsing_supported)).await;
        });
    }
    fn rfcomm_send_msc(&self, dlci: u8, addr: String) {
        let txl = self.tx.clone();
        tokio::spawn(async move {
            let _ = txl.send(Message::QaRfcommSendMsc(dlci, addr)).await;
        });
    }
    fn get_discoverable_mode(&self) -> BtDiscMode {
        self.disc_mode.clone()
    }
}
