use btstack::bluetooth_qa::IBluetoothQA;

use bt_topshim::btif::BtDiscMode;
use dbus_macros::{dbus_method, generate_dbus_exporter};
use dbus_projection::dbus_generated;

use crate::dbus_arg::DBusArg;

struct IBluetoothQADBus {}

#[generate_dbus_exporter(export_bluetooth_qa_dbus_intf, "org.chromium.bluetooth.BluetoothQA")]
impl IBluetoothQA for IBluetoothQADBus {
    #[dbus_method("AddMediaPlayer")]
    fn add_media_player(&self, name: String, browsing_supported: bool) {
        dbus_generated!()
    }
    #[dbus_method("RfcommSendMsc")]
    fn rfcomm_send_msc(&self, dlci: u8, addr: String) {
        dbus_generated!()
    }
    #[dbus_method("GetDiscoverableMode")]
    fn get_discoverable_mode(&self) -> BtDiscMode {
        dbus_generated!()
    }
}
