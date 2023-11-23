use log::{error, info, warn};

use std::collections::HashMap;
use std::process::Command;

use crate::iface_bluetooth_experimental::IBluetoothExperimental;
use crate::iface_bluetooth_manager::{
    AdapterWithEnabled, IBluetoothManager, IBluetoothManagerCallback,
};
use crate::state_machine::{
    state_to_enabled, AdapterState, Message, StateMachineProxy, VirtualHciIndex,
};
use crate::{config_util, migrate};

const BLUEZ_INIT_TARGET: &str = "bluetoothd";

/// Implementation of IBluetoothManager.
pub struct BluetoothManager {
    proxy: StateMachineProxy,
    callbacks: HashMap<u32, Box<dyn IBluetoothManagerCallback + Send>>,
}

impl BluetoothManager {
    pub fn new(proxy: StateMachineProxy) -> BluetoothManager {
        BluetoothManager { proxy, callbacks: HashMap::new() }
    }

    fn is_adapter_enabled(&self, hci_device: VirtualHciIndex) -> bool {
        state_to_enabled(self.proxy.get_process_state(hci_device))
    }

    fn is_adapter_present(&self, hci_device: VirtualHciIndex) -> bool {
        self.proxy.get_state(hci_device, move |a| Some(a.present)).unwrap_or(false)
    }

    pub(crate) fn callback_hci_device_change(&mut self, hci_device: i32, present: bool) {
        for (_, callback) in &mut self.callbacks {
            callback.on_hci_device_changed(hci_device, present);
        }
    }

    pub(crate) fn callback_hci_enabled_change(&mut self, hci_device: i32, enabled: bool) {
        if enabled {
            info!("Started {}", hci_device);
        } else {
            info!("Stopped {}", hci_device);
        }

        for (_, callback) in &mut self.callbacks {
            callback.on_hci_enabled_changed(hci_device, enabled);
        }
    }

    pub(crate) fn callback_default_adapter_change(&mut self, hci_device: i32) {
        for (_, callback) in &mut self.callbacks {
            callback.on_default_adapter_changed(hci_device);
        }
    }

    pub(crate) fn callback_disconnected(&mut self, id: u32) {
        self.callbacks.remove(&id);
    }
}

impl IBluetoothManager for BluetoothManager {
    fn start(&mut self, hci_interface: i32) {
        info!("Starting {}", hci_interface);

        if !config_util::modify_hci_n_enabled(hci_interface, true) {
            error!("Config is not successfully modified");
        }

        let virt_hci = VirtualHciIndex(hci_interface);

        // Store that this adapter is meant to be started in state machine.
        self.proxy.modify_state(virt_hci, move |a: &mut AdapterState| a.config_enabled = true);

        // Ignore the request if adapter is already enabled or not present.
        if self.is_adapter_enabled(virt_hci) || !self.is_adapter_present(virt_hci) {
            return;
        }

        self.proxy.start_bluetooth(virt_hci);
    }

    fn stop(&mut self, hci_interface: i32) {
        info!("Stopping {}", hci_interface);
        if !config_util::modify_hci_n_enabled(hci_interface, false) {
            error!("Config is not successfully modified");
        }

        let virt_hci = VirtualHciIndex(hci_interface);

        // Store that this adapter is meant to be stopped in state machine.
        self.proxy.modify_state(virt_hci, move |a: &mut AdapterState| a.config_enabled = false);

        // Ignore the request if adapter is already disabled.
        if !self.is_adapter_enabled(virt_hci) {
            return;
        }

        self.proxy.stop_bluetooth(virt_hci);
    }

    fn get_adapter_enabled(&mut self, hci_interface: i32) -> bool {
        self.is_adapter_enabled(VirtualHciIndex(hci_interface))
    }

    fn register_callback(&mut self, mut callback: Box<dyn IBluetoothManagerCallback + Send>) {
        let tx = self.proxy.get_tx();

        let id = callback.register_disconnect(Box::new(move |cb_id| {
            let tx = tx.clone();
            tokio::spawn(async move {
                let _result = tx.send(Message::CallbackDisconnected(cb_id)).await;
            });
        }));

        self.callbacks.insert(id, callback);
    }

    fn get_floss_enabled(&mut self) -> bool {
        self.proxy.get_floss_enabled()
    }

    fn set_floss_enabled(&mut self, enabled: bool) {
        let prev = self.proxy.set_floss_enabled(enabled);
        config_util::write_floss_enabled(enabled);

        if prev != enabled && enabled {
            if let Err(e) = Command::new("initctl").args(&["stop", BLUEZ_INIT_TARGET]).output() {
                warn!("Failed to stop bluetoothd: {}", e);
            }
            migrate::migrate_bluez_devices();
            for hci in config_util::list_hci_devices() {
                if config_util::is_hci_n_enabled(hci) {
                    let _ = self.proxy.start_bluetooth(VirtualHciIndex(hci));
                }
            }
        } else if prev != enabled {
            for hci in config_util::list_hci_devices() {
                if config_util::is_hci_n_enabled(hci) {
                    let _ = self.proxy.stop_bluetooth(VirtualHciIndex(hci));
                }
            }
            migrate::migrate_floss_devices();
            if let Err(e) = Command::new("initctl").args(&["start", BLUEZ_INIT_TARGET]).output() {
                warn!("Failed to start bluetoothd: {}", e);
            }
        }
    }

    fn get_available_adapters(&mut self) -> Vec<AdapterWithEnabled> {
        self.proxy
            .get_valid_adapters()
            .iter()
            .map(|a| AdapterWithEnabled {
                hci_interface: a.virt_hci.to_i32(),
                enabled: state_to_enabled(a.state),
            })
            .collect::<Vec<AdapterWithEnabled>>()
    }

    fn get_default_adapter(&mut self) -> i32 {
        self.proxy.get_default_adapter().to_i32()
    }

    fn set_desired_default_adapter(&mut self, adapter_index: i32) {
        self.proxy.set_desired_default_adapter(VirtualHciIndex(adapter_index));
    }
}

/// Implementation of IBluetoothExperimental
impl IBluetoothExperimental for BluetoothManager {
    fn set_ll_privacy(&mut self, enabled: bool) {
        let current_status = match config_util::read_floss_ll_privacy_enabled() {
            Ok(true) => true,
            _ => false,
        };

        if current_status == enabled {
            return;
        }

        if let Err(e) = config_util::write_floss_ll_privacy_enabled(enabled) {
            error!("Failed to write ll privacy status: {}", e);
            return;
        }
    }

    fn set_devcoredump(&mut self, enabled: bool) -> bool {
        info!("Set floss devcoredump to {}", enabled);
        config_util::write_coredump_state_to_file(enabled)
    }
}
