use crate::battery_manager::{Batteries, BatterySet};
use crate::callbacks::Callbacks;
use crate::{Message, RPCProxy};
use std::collections::HashMap;
use tokio::sync::mpsc::Sender;

/// Callback for BatteryProvider implementers.
pub trait IBatteryProviderCallback: RPCProxy {
    /// Requests that the BatteryProvider send updated battery information.
    fn refresh_battery_info(&mut self);
}

/// Interface for managing BatteryProvider instances.
pub trait IBatteryProviderManager {
    /// Registers a BatteryProvider and generates a unique batttery provider ID for future calls.
    fn register_battery_provider(
        &mut self,
        battery_provider_callback: Box<dyn IBatteryProviderCallback + Send>,
    ) -> u32;

    /// Unregisters a BatteryProvider, potentially removes battery information for the remote device
    /// if there are no other providers.
    fn unregister_battery_provider(&mut self, battery_provider_id: u32);

    /// Updates the battery information for the battery associated with battery_id.
    fn set_battery_info(&mut self, battery_provider_id: u32, battery_set: BatterySet);
}

/// Represents the BatteryProviderManager, a central point for collecting battery information from
/// numerous sources.
pub struct BatteryProviderManager {
    /// Sender for callback communication with the main thread.
    tx: Sender<Message>,
    battery_provider_callbacks: Callbacks<dyn IBatteryProviderCallback + Send>,
    /// Stored information merged from all battery providers.
    battery_info: HashMap<String, Batteries>,
}

impl BatteryProviderManager {
    /// Constructs a new BatteryProviderManager with callbacks communicating on tx.
    pub fn new(tx: Sender<Message>) -> BatteryProviderManager {
        let battery_provider_callbacks =
            Callbacks::new(tx.clone(), Message::BatteryProviderManagerCallbackDisconnected);
        let battery_info = HashMap::new();
        BatteryProviderManager { tx, battery_provider_callbacks, battery_info }
    }

    /// Request battery info refresh from all battery providers.
    pub fn refresh_battery_info(&mut self) {
        self.battery_provider_callbacks
            .for_all_callbacks(|callback| callback.refresh_battery_info());
    }

    /// Get the best battery info available for a given device.
    pub fn get_battery_info(&self, remote_address: String) -> Option<BatterySet> {
        self.battery_info.get(&remote_address)?.pick_best()
    }

    /// Removes a battery provider callback.
    pub fn remove_battery_provider_callback(&mut self, battery_provider_id: u32) {
        self.battery_provider_callbacks.remove_callback(battery_provider_id);
    }
}

impl IBatteryProviderManager for BatteryProviderManager {
    fn register_battery_provider(
        &mut self,
        battery_provider_callback: Box<dyn IBatteryProviderCallback + Send>,
    ) -> u32 {
        self.battery_provider_callbacks.add_callback(battery_provider_callback)
    }

    fn unregister_battery_provider(&mut self, battery_provider_id: u32) {
        self.remove_battery_provider_callback(battery_provider_id);
    }

    fn set_battery_info(&mut self, _battery_provider_id: u32, battery_set: BatterySet) {
        let batteries = self
            .battery_info
            .entry(battery_set.address.clone())
            .or_insert_with(|| Batteries::new());
        batteries.add_or_update_battery_set(battery_set);
        if let Some(best_battery_set) = batteries.pick_best() {
            let tx = self.tx.clone();
            tokio::spawn(async move {
                let _ = tx
                    .send(Message::BatteryProviderManagerBatteryUpdated(
                        best_battery_set.address.clone(),
                        best_battery_set,
                    ))
                    .await;
            });
        }
    }
}
