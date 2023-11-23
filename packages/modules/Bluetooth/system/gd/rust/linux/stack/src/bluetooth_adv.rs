//! BLE Advertising types and utilities

use bt_topshim::btif::Uuid;
use bt_topshim::profiles::gatt::{AdvertisingStatus, Gatt, LePhy};

use itertools::Itertools;
use log::warn;
use num_traits::clamp;
use std::collections::HashMap;
use std::sync::atomic::{AtomicIsize, Ordering};
use tokio::sync::mpsc::Sender;

use crate::callbacks::Callbacks;
use crate::uuid::UuidHelper;
use crate::{Message, RPCProxy, SuspendMode};

pub type AdvertiserId = i32;
pub type CallbackId = u32;
pub type RegId = i32;
pub type ManfId = u16;

/// Advertising parameters for each BLE advertising set.
#[derive(Debug, Default, Clone)]
pub struct AdvertisingSetParameters {
    /// Whether the advertisement will be connectable.
    pub connectable: bool,
    /// Whether the advertisement will be scannable.
    pub scannable: bool,
    /// Whether the legacy advertisement will be used.
    pub is_legacy: bool,
    /// Whether the advertisement will be anonymous.
    pub is_anonymous: bool,
    /// Whether the TX Power will be included.
    pub include_tx_power: bool,
    /// Primary advertising phy. Valid values are: 1 (1M), 2 (2M), 3 (Coded).
    pub primary_phy: LePhy,
    /// Secondary advertising phy. Valid values are: 1 (1M), 2 (2M), 3 (Coded).
    pub secondary_phy: LePhy,
    /// The advertising interval. Bluetooth LE Advertising interval, in 0.625 ms unit.
    /// The valid range is from 160 (100 ms) to 16777215 (10485.759375 sec).
    /// Recommended values are: 160 (100 ms), 400 (250 ms), 1600 (1 sec).
    pub interval: i32,
    /// Transmission power of Bluetooth LE Advertising, in dBm. The valid range is [-127, 1].
    /// Recommended values are: -21, -15, 7, 1.
    pub tx_power_level: i32,
    /// Own address type for advertising to control public or privacy mode.
    /// The valid types are: -1 (default), 0 (public), 1 (random).
    pub own_address_type: i32,
}

/// Represents the data to be advertised and the scan response data for active scans.
#[derive(Debug, Default, Clone)]
pub struct AdvertiseData {
    /// A list of service UUIDs within the advertisement that are used to identify
    /// the Bluetooth GATT services.
    pub service_uuids: Vec<Uuid>,
    /// A list of service solicitation UUIDs within the advertisement that we invite to connect.
    pub solicit_uuids: Vec<Uuid>,
    /// A list of transport discovery data.
    pub transport_discovery_data: Vec<Vec<u8>>,
    /// A collection of manufacturer Id and the corresponding manufacturer specific data.
    pub manufacturer_data: HashMap<ManfId, Vec<u8>>,
    /// A map of 128-bit UUID and its corresponding service data.
    pub service_data: HashMap<String, Vec<u8>>,
    /// Whether TX Power level will be included in the advertising packet.
    pub include_tx_power_level: bool,
    /// Whether the device name will be included in the advertisement packet.
    pub include_device_name: bool,
}

/// Parameters of the periodic advertising packet for BLE advertising set.
#[derive(Debug, Default)]
pub struct PeriodicAdvertisingParameters {
    /// Whether TX Power level will be included.
    pub include_tx_power: bool,
    /// Periodic advertising interval in 1.25 ms unit. Valid values are from 80 (100 ms) to
    /// 65519 (81.89875 sec). Value from range [interval, interval+20ms] will be picked as
    /// the actual value.
    pub interval: i32,
}

/// Interface for advertiser callbacks to clients, passed to
/// `IBluetoothGatt::start_advertising_set`.
pub trait IAdvertisingSetCallback: RPCProxy {
    /// Callback triggered in response to `start_advertising_set` indicating result of
    /// the operation.
    ///
    /// * `reg_id` - Identifies the advertising set registered by `start_advertising_set`.
    /// * `advertiser_id` - ID for the advertising set. It will be used in other advertising methods
    ///     and callbacks.
    /// * `tx_power` - Transmit power that will be used for this advertising set.
    /// * `status` - Status of this operation.
    fn on_advertising_set_started(
        &mut self,
        reg_id: i32,
        advertiser_id: i32,
        tx_power: i32,
        status: AdvertisingStatus,
    );

    /// Callback triggered in response to `get_own_address` indicating result of the operation.
    fn on_own_address_read(&mut self, advertiser_id: i32, address_type: i32, address: String);

    /// Callback triggered in response to `stop_advertising_set` indicating the advertising set
    /// is stopped.
    fn on_advertising_set_stopped(&mut self, advertiser_id: i32);

    /// Callback triggered in response to `enable_advertising_set` indicating result of
    /// the operation.
    fn on_advertising_enabled(
        &mut self,
        advertiser_id: i32,
        enable: bool,
        status: AdvertisingStatus,
    );

    /// Callback triggered in response to `set_advertising_data` indicating result of the operation.
    fn on_advertising_data_set(&mut self, advertiser_id: i32, status: AdvertisingStatus);

    /// Callback triggered in response to `set_scan_response_data` indicating result of
    /// the operation.
    fn on_scan_response_data_set(&mut self, advertiser_id: i32, status: AdvertisingStatus);

    /// Callback triggered in response to `set_advertising_parameters` indicating result of
    /// the operation.
    fn on_advertising_parameters_updated(
        &mut self,
        advertiser_id: i32,
        tx_power: i32,
        status: AdvertisingStatus,
    );

    /// Callback triggered in response to `set_periodic_advertising_parameters` indicating result of
    /// the operation.
    fn on_periodic_advertising_parameters_updated(
        &mut self,
        advertiser_id: i32,
        status: AdvertisingStatus,
    );

    /// Callback triggered in response to `set_periodic_advertising_data` indicating result of
    /// the operation.
    fn on_periodic_advertising_data_set(&mut self, advertiser_id: i32, status: AdvertisingStatus);

    /// Callback triggered in response to `set_periodic_advertising_enable` indicating result of
    /// the operation.
    fn on_periodic_advertising_enabled(
        &mut self,
        advertiser_id: i32,
        enable: bool,
        status: AdvertisingStatus,
    );

    /// When advertising module changes its suspend mode due to system suspend/resume.
    fn on_suspend_mode_change(&mut self, suspend_mode: SuspendMode);
}

// Advertising interval range.
const INTERVAL_MAX: i32 = 0xff_ffff; // 10485.759375 sec
const INTERVAL_MIN: i32 = 160; // 100 ms
const INTERVAL_DELTA: i32 = 50; // 31.25 ms gap between min and max

// Periodic advertising interval range.
const PERIODIC_INTERVAL_MAX: i32 = 65519; // 81.89875 sec
const PERIODIC_INTERVAL_MIN: i32 = 80; // 100 ms
const PERIODIC_INTERVAL_DELTA: i32 = 16; // 20 ms gap between min and max

// Device name length.
const DEVICE_NAME_MAX: usize = 26;

// Advertising data types.
const COMPLETE_LIST_16_BIT_SERVICE_UUIDS: u8 = 0x03;
const COMPLETE_LIST_32_BIT_SERVICE_UUIDS: u8 = 0x05;
const COMPLETE_LIST_128_BIT_SERVICE_UUIDS: u8 = 0x07;
const SHORTENED_LOCAL_NAME: u8 = 0x08;
const COMPLETE_LOCAL_NAME: u8 = 0x09;
const TX_POWER_LEVEL: u8 = 0x0a;
const LIST_16_BIT_SERVICE_SOLICITATION_UUIDS: u8 = 0x14;
const LIST_128_BIT_SERVICE_SOLICITATION_UUIDS: u8 = 0x15;
const SERVICE_DATA_16_BIT_UUID: u8 = 0x16;
const LIST_32_BIT_SERVICE_SOLICITATION_UUIDS: u8 = 0x1f;
const SERVICE_DATA_32_BIT_UUID: u8 = 0x20;
const SERVICE_DATA_128_BIT_UUID: u8 = 0x21;
const TRANSPORT_DISCOVERY_DATA: u8 = 0x26;
const MANUFACTURER_SPECIFIC_DATA: u8 = 0xff;
const SERVICE_AD_TYPES: [u8; 3] = [
    COMPLETE_LIST_16_BIT_SERVICE_UUIDS,
    COMPLETE_LIST_32_BIT_SERVICE_UUIDS,
    COMPLETE_LIST_128_BIT_SERVICE_UUIDS,
];
const SOLICIT_AD_TYPES: [u8; 3] = [
    LIST_16_BIT_SERVICE_SOLICITATION_UUIDS,
    LIST_32_BIT_SERVICE_SOLICITATION_UUIDS,
    LIST_128_BIT_SERVICE_SOLICITATION_UUIDS,
];

// Invalid advertising set id.
const INVALID_ADV_ID: i32 = 0xff;

// Invalid advertising set id.
pub const INVALID_REG_ID: i32 = -1;

impl Into<bt_topshim::profiles::gatt::AdvertiseParameters> for AdvertisingSetParameters {
    fn into(self) -> bt_topshim::profiles::gatt::AdvertiseParameters {
        let mut props: u16 = 0;
        if self.connectable {
            props |= 0x01;
        }
        if self.scannable {
            props |= 0x02;
        }
        if self.is_legacy {
            props |= 0x10;
        }
        if self.is_anonymous {
            props |= 0x20;
        }
        if self.include_tx_power {
            props |= 0x40;
        }

        let interval = clamp(self.interval, INTERVAL_MIN, INTERVAL_MAX - INTERVAL_DELTA);

        bt_topshim::profiles::gatt::AdvertiseParameters {
            advertising_event_properties: props,
            min_interval: interval as u32,
            max_interval: (interval + INTERVAL_DELTA) as u32,
            channel_map: 0x07 as u8, // all channels
            tx_power: self.tx_power_level as i8,
            primary_advertising_phy: self.primary_phy.into(),
            secondary_advertising_phy: self.secondary_phy.into(),
            scan_request_notification_enable: 0 as u8, // false
            own_address_type: self.own_address_type as i8,
        }
    }
}

impl AdvertiseData {
    fn append_adv_data(dest: &mut Vec<u8>, ad_type: u8, ad_payload: &[u8]) {
        let len = clamp(ad_payload.len(), 0, 254);
        dest.push((len + 1) as u8);
        dest.push(ad_type);
        dest.extend(&ad_payload[..len]);
    }

    fn append_uuids(dest: &mut Vec<u8>, ad_types: &[u8; 3], uuids: &Vec<Uuid>) {
        let mut uuid16_bytes = Vec::<u8>::new();
        let mut uuid32_bytes = Vec::<u8>::new();
        let mut uuid128_bytes = Vec::<u8>::new();

        // For better transmission efficiency, we generate a compact
        // advertisement data byconverting UUIDs into shorter binary forms
        // and then group them by their length in order.
        // The data generated for UUIDs looks like:
        // [16-bit_UUID_LIST, 32-bit_UUID_LIST, 128-bit_UUID_LIST].
        for uuid in uuids {
            let uuid_slice = UuidHelper::get_shortest_slice(&uuid.uu);
            let id: Vec<u8> = uuid_slice.iter().rev().cloned().collect();
            match id.len() {
                2 => uuid16_bytes.extend(id),
                4 => uuid32_bytes.extend(id),
                16 => uuid128_bytes.extend(id),
                _ => (),
            }
        }

        let bytes_list = vec![uuid16_bytes, uuid32_bytes, uuid128_bytes];
        for (ad_type, bytes) in
            ad_types.iter().zip(bytes_list.iter()).filter(|(_, bytes)| bytes.len() > 0)
        {
            AdvertiseData::append_adv_data(dest, *ad_type, bytes);
        }
    }

    fn append_service_uuids(dest: &mut Vec<u8>, uuids: &Vec<Uuid>) {
        AdvertiseData::append_uuids(dest, &SERVICE_AD_TYPES, uuids);
    }

    fn append_solicit_uuids(dest: &mut Vec<u8>, uuids: &Vec<Uuid>) {
        AdvertiseData::append_uuids(dest, &SOLICIT_AD_TYPES, uuids);
    }

    fn append_service_data(dest: &mut Vec<u8>, service_data: &HashMap<String, Vec<u8>>) {
        for (uuid, data) in
            service_data.iter().filter_map(|(s, d)| UuidHelper::parse_string(s).map(|s| (s, d)))
        {
            let uuid_slice = UuidHelper::get_shortest_slice(&uuid.uu);
            let concated: Vec<u8> = uuid_slice.iter().rev().chain(data).cloned().collect();
            match uuid_slice.len() {
                2 => AdvertiseData::append_adv_data(dest, SERVICE_DATA_16_BIT_UUID, &concated),
                4 => AdvertiseData::append_adv_data(dest, SERVICE_DATA_32_BIT_UUID, &concated),
                16 => AdvertiseData::append_adv_data(dest, SERVICE_DATA_128_BIT_UUID, &concated),
                _ => (),
            }
        }
    }

    fn append_device_name(dest: &mut Vec<u8>, device_name: &String) {
        if device_name.len() == 0 {
            return;
        }

        let (ad_type, name) = if device_name.len() > DEVICE_NAME_MAX {
            (SHORTENED_LOCAL_NAME, [&device_name.as_bytes()[..DEVICE_NAME_MAX], &[0]].concat())
        } else {
            (COMPLETE_LOCAL_NAME, [device_name.as_bytes(), &[0]].concat())
        };
        AdvertiseData::append_adv_data(dest, ad_type, &name);
    }

    fn append_manufacturer_data(dest: &mut Vec<u8>, manufacturer_data: &HashMap<ManfId, Vec<u8>>) {
        for (m, data) in manufacturer_data.iter().sorted() {
            let concated = [&m.to_le_bytes()[..], data].concat();
            AdvertiseData::append_adv_data(dest, MANUFACTURER_SPECIFIC_DATA, &concated);
        }
    }

    fn append_transport_discovery_data(
        dest: &mut Vec<u8>,
        transport_discovery_data: &Vec<Vec<u8>>,
    ) {
        for tdd in transport_discovery_data.iter().filter(|tdd| tdd.len() > 0) {
            AdvertiseData::append_adv_data(dest, TRANSPORT_DISCOVERY_DATA, &tdd);
        }
    }

    /// Creates raw data from the AdvertiseData.
    pub fn make_with(&self, device_name: &String) -> Vec<u8> {
        let mut bytes = Vec::<u8>::new();
        if self.include_device_name {
            AdvertiseData::append_device_name(&mut bytes, device_name);
        }
        if self.include_tx_power_level {
            // Lower layers will fill tx power level.
            AdvertiseData::append_adv_data(&mut bytes, TX_POWER_LEVEL, &[0]);
        }
        AdvertiseData::append_manufacturer_data(&mut bytes, &self.manufacturer_data);
        AdvertiseData::append_service_uuids(&mut bytes, &self.service_uuids);
        AdvertiseData::append_service_data(&mut bytes, &self.service_data);
        AdvertiseData::append_solicit_uuids(&mut bytes, &self.solicit_uuids);
        AdvertiseData::append_transport_discovery_data(&mut bytes, &self.transport_discovery_data);
        bytes
    }
}

impl Into<bt_topshim::profiles::gatt::PeriodicAdvertisingParameters>
    for PeriodicAdvertisingParameters
{
    fn into(self) -> bt_topshim::profiles::gatt::PeriodicAdvertisingParameters {
        let mut p = bt_topshim::profiles::gatt::PeriodicAdvertisingParameters::default();

        let interval = clamp(
            self.interval,
            PERIODIC_INTERVAL_MIN,
            PERIODIC_INTERVAL_MAX - PERIODIC_INTERVAL_DELTA,
        );

        p.enable = true;
        p.include_adi = false;
        p.min_interval = interval as u16;
        p.max_interval = p.min_interval + (PERIODIC_INTERVAL_DELTA as u16);
        if self.include_tx_power {
            p.periodic_advertising_properties |= 0x40;
        }

        p
    }
}

/// Monotonically increasing counter for reg_id.
static REG_ID_COUNTER: AtomicIsize = AtomicIsize::new(0);

// Keeps information of an advertising set.
#[derive(Debug, PartialEq, Copy, Clone)]
pub(crate) struct AdvertisingSetInfo {
    /// Identifies the advertising set when it's started successfully.
    adv_id: Option<AdvertiserId>,

    /// Identifies callback associated.
    callback_id: CallbackId,

    /// Identifies the advertising set when it's registered.
    reg_id: RegId,

    /// Whether the advertising set has been enabled.
    enabled: bool,

    /// Whether the advertising set has been paused.
    paused: bool,

    /// Advertising duration, in 10 ms unit.
    adv_timeout: u16,

    /// Maximum number of extended advertising events the controller
    /// shall attempt to send before terminating the extended advertising.
    adv_events: u8,
}

impl AdvertisingSetInfo {
    pub(crate) fn new(callback_id: CallbackId, adv_timeout: u16, adv_events: u8) -> Self {
        let mut reg_id = REG_ID_COUNTER.fetch_add(1, Ordering::SeqCst) as RegId;
        if reg_id == INVALID_REG_ID {
            reg_id = REG_ID_COUNTER.fetch_add(1, Ordering::SeqCst) as RegId;
        }
        AdvertisingSetInfo {
            adv_id: None,
            callback_id,
            reg_id,
            enabled: false,
            paused: false,
            adv_timeout,
            adv_events,
        }
    }

    /// Gets advertising set registration ID.
    pub(crate) fn reg_id(&self) -> RegId {
        self.reg_id
    }

    /// Gets associated callback ID.
    pub(crate) fn callback_id(&self) -> CallbackId {
        self.callback_id
    }

    /// Updates advertiser ID.
    pub(crate) fn set_adv_id(&mut self, id: Option<AdvertiserId>) {
        self.adv_id = id;
    }

    /// Gets advertiser ID, which is required for advertising |BleAdvertiserInterface|.
    pub(crate) fn adv_id(&self) -> u8 {
        // As advertiser ID was from topshim originally, type casting is safe.
        self.adv_id.unwrap_or(INVALID_ADV_ID) as u8
    }

    /// Updates advertising set status.
    pub(crate) fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
    }

    /// Returns true if the advertising set has been enabled, false otherwise.
    pub(crate) fn is_enabled(&self) -> bool {
        self.enabled
    }

    /// Marks the advertising set as paused or not.
    pub(crate) fn set_paused(&mut self, paused: bool) {
        self.paused = paused;
    }

    /// Returns true if the advertising set has been paused, false otherwise.
    pub(crate) fn is_paused(&self) -> bool {
        self.paused
    }

    /// Gets adv_timeout.
    pub(crate) fn adv_timeout(&self) -> u16 {
        self.adv_timeout
    }

    /// Gets adv_events.
    pub(crate) fn adv_events(&self) -> u8 {
        self.adv_events
    }
}

// Manages advertising sets and the callbacks.
pub(crate) struct Advertisers {
    callbacks: Callbacks<dyn IAdvertisingSetCallback + Send>,
    sets: HashMap<RegId, AdvertisingSetInfo>,
    suspend_mode: SuspendMode,
}

impl Advertisers {
    pub(crate) fn new(tx: Sender<Message>) -> Self {
        Advertisers {
            callbacks: Callbacks::new(tx, Message::AdvertiserCallbackDisconnected),
            sets: HashMap::new(),
            suspend_mode: SuspendMode::Normal,
        }
    }

    /// Adds an advertising set.
    pub(crate) fn add(&mut self, s: AdvertisingSetInfo) {
        if let Some(old) = self.sets.insert(s.reg_id(), s) {
            warn!("An advertising set with the same reg_id ({}) exists. Drop it!", old.reg_id);
        }
    }

    /// Returns an iterator of valid advertising sets.
    pub(crate) fn valid_sets(&self) -> impl Iterator<Item = &AdvertisingSetInfo> {
        self.sets.iter().filter_map(|(_, s)| s.adv_id.map(|_| s))
    }

    /// Returns a mutable iterator of valid advertising sets.
    pub(crate) fn valid_sets_mut(&mut self) -> impl Iterator<Item = &mut AdvertisingSetInfo> {
        self.sets.iter_mut().filter_map(|(_, s)| s.adv_id.map(|_| s))
    }

    /// Returns an iterator of enabled advertising sets.
    pub(crate) fn enabled_sets(&self) -> impl Iterator<Item = &AdvertisingSetInfo> {
        self.valid_sets().filter(|s| s.is_enabled())
    }

    /// Returns a mutable iterator of enabled advertising sets.
    pub(crate) fn enabled_sets_mut(&mut self) -> impl Iterator<Item = &mut AdvertisingSetInfo> {
        self.valid_sets_mut().filter(|s| s.is_enabled())
    }

    /// Returns a mutable iterator of paused advertising sets.
    pub(crate) fn paused_sets_mut(&mut self) -> impl Iterator<Item = &mut AdvertisingSetInfo> {
        self.valid_sets_mut().filter(|s| s.is_paused())
    }

    fn find_reg_id(&self, adv_id: AdvertiserId) -> Option<RegId> {
        for (_, s) in &self.sets {
            if s.adv_id == Some(adv_id) {
                return Some(s.reg_id());
            }
        }
        return None;
    }

    /// Returns a mutable reference to the advertising set with the reg_id specified.
    pub(crate) fn get_mut_by_reg_id(&mut self, reg_id: RegId) -> Option<&mut AdvertisingSetInfo> {
        self.sets.get_mut(&reg_id)
    }

    /// Returns a shared reference to the advertising set with the reg_id specified.
    pub(crate) fn get_by_reg_id(&self, reg_id: RegId) -> Option<&AdvertisingSetInfo> {
        self.sets.get(&reg_id)
    }

    /// Returns a mutable reference to the advertising set with the advertiser ID specified.
    pub(crate) fn get_mut_by_advertiser_id(
        &mut self,
        adv_id: AdvertiserId,
    ) -> Option<&mut AdvertisingSetInfo> {
        if let Some(reg_id) = self.find_reg_id(adv_id) {
            return self.get_mut_by_reg_id(reg_id);
        }
        None
    }

    /// Returns a shared reference to the advertising set with the advertiser ID specified.
    pub(crate) fn get_by_advertiser_id(&self, adv_id: AdvertiserId) -> Option<&AdvertisingSetInfo> {
        if let Some(reg_id) = self.find_reg_id(adv_id) {
            return self.get_by_reg_id(reg_id);
        }
        None
    }

    /// Removes the advertising set with the reg_id specified.
    ///
    /// Returns the advertising set if found, None otherwise.
    pub(crate) fn remove_by_reg_id(&mut self, reg_id: RegId) -> Option<AdvertisingSetInfo> {
        self.sets.remove(&reg_id)
    }

    /// Removes the advertising set with the specified advertiser ID.
    ///
    /// Returns the advertising set if found, None otherwise.
    pub(crate) fn remove_by_advertiser_id(
        &mut self,
        adv_id: AdvertiserId,
    ) -> Option<AdvertisingSetInfo> {
        if let Some(reg_id) = self.find_reg_id(adv_id) {
            return self.remove_by_reg_id(reg_id);
        }
        None
    }

    /// Adds an advertiser callback.
    pub(crate) fn add_callback(
        &mut self,
        callback: Box<dyn IAdvertisingSetCallback + Send>,
    ) -> CallbackId {
        self.callbacks.add_callback(callback)
    }

    /// Returns callback of the advertising set.
    pub(crate) fn get_callback(
        &mut self,
        s: &AdvertisingSetInfo,
    ) -> Option<&mut Box<dyn IAdvertisingSetCallback + Send>> {
        self.callbacks.get_by_id_mut(s.callback_id())
    }

    /// Removes an advertiser callback and unregisters all advertising sets associated with that callback.
    pub(crate) fn remove_callback(&mut self, callback_id: CallbackId, gatt: &mut Gatt) -> bool {
        for (_, s) in
            self.sets.iter().filter(|(_, s)| s.callback_id() == callback_id && s.adv_id.is_some())
        {
            gatt.advertiser.unregister(s.adv_id());
        }
        self.sets.retain(|_, s| s.callback_id() != callback_id);

        self.callbacks.remove_callback(callback_id)
    }

    /// Update suspend mode.
    pub(crate) fn set_suspend_mode(&mut self, suspend_mode: SuspendMode) {
        if suspend_mode != self.suspend_mode {
            self.suspend_mode = suspend_mode;
            self.notify_suspend_mode();
        }
    }

    /// Gets current suspend mode.
    pub(crate) fn suspend_mode(&mut self) -> SuspendMode {
        self.suspend_mode.clone()
    }

    /// Notify current suspend mode to all active callbacks.
    fn notify_suspend_mode(&mut self) {
        let suspend_mode = &self.suspend_mode;
        self.callbacks.for_all_callbacks(|callback| {
            callback.on_suspend_mode_change(suspend_mode.clone());
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;
    use std::iter::FromIterator;

    #[test]
    fn test_append_ad_data_clamped() {
        let mut bytes = Vec::<u8>::new();
        let mut ans = Vec::<u8>::new();
        ans.push(255);
        ans.push(102);
        ans.extend(Vec::<u8>::from_iter(0..254));

        let payload = Vec::<u8>::from_iter(0..255);
        AdvertiseData::append_adv_data(&mut bytes, 102, &payload);
        assert_eq!(bytes, ans);
    }

    #[test]
    fn test_append_ad_data_multiple() {
        let mut bytes = Vec::<u8>::new();

        let payload = vec![0 as u8, 1, 2, 3, 4];
        AdvertiseData::append_adv_data(&mut bytes, 100, &payload);
        AdvertiseData::append_adv_data(&mut bytes, 101, &[0]);
        assert_eq!(bytes, vec![6 as u8, 100, 0, 1, 2, 3, 4, 2, 101, 0]);
    }

    #[test]
    fn test_new_advising_set_info() {
        let mut uniq = HashSet::new();
        for callback_id in 0..256 {
            let s = AdvertisingSetInfo::new(callback_id, 0, 0);
            assert_eq!(s.callback_id(), callback_id);
            assert_eq!(uniq.insert(s.reg_id()), true);
        }
    }

    #[test]
    fn test_iterate_adving_set_info() {
        let (tx, _rx) = crate::Stack::create_channel();
        let mut advertisers = Advertisers::new(tx.clone());

        let size = 256;
        for i in 0..size {
            let callback_id: CallbackId = i as CallbackId;
            let adv_id: AdvertiserId = i as AdvertiserId;
            let mut s = AdvertisingSetInfo::new(callback_id, 0, 0);
            s.set_adv_id(Some(adv_id));
            advertisers.add(s);
        }

        assert_eq!(advertisers.valid_sets().count(), size);
        for s in advertisers.valid_sets() {
            assert_eq!(s.callback_id() as u32, s.adv_id() as u32);
        }
    }

    #[test]
    fn test_append_service_uuids() {
        let mut bytes = Vec::<u8>::new();
        let uuid_16 =
            Uuid::from(UuidHelper::from_string("0000fef3-0000-1000-8000-00805f9b34fb").unwrap());
        let uuids = vec![uuid_16.clone()];
        let exp_16: Vec<u8> = vec![3, 0x3, 0xf3, 0xfe];
        AdvertiseData::append_service_uuids(&mut bytes, &uuids);
        assert_eq!(bytes, exp_16);

        let mut bytes = Vec::<u8>::new();
        let uuid_32 =
            Uuid::from(UuidHelper::from_string("00112233-0000-1000-8000-00805f9b34fb").unwrap());
        let uuids = vec![uuid_32.clone()];
        let exp_32: Vec<u8> = vec![5, 0x5, 0x33, 0x22, 0x11, 0x0];
        AdvertiseData::append_service_uuids(&mut bytes, &uuids);
        assert_eq!(bytes, exp_32);

        let mut bytes = Vec::<u8>::new();
        let uuid_128 =
            Uuid::from(UuidHelper::from_string("00010203-0405-0607-0809-0a0b0c0d0e0f").unwrap());
        let uuids = vec![uuid_128.clone()];
        let exp_128: Vec<u8> = vec![
            17, 0x7, 0xf, 0xe, 0xd, 0xc, 0xb, 0xa, 0x9, 0x8, 0x7, 0x6, 0x5, 0x4, 0x3, 0x2, 0x1, 0x0,
        ];
        AdvertiseData::append_service_uuids(&mut bytes, &uuids);
        assert_eq!(bytes, exp_128);

        let mut bytes = Vec::<u8>::new();
        let uuids = vec![uuid_16, uuid_32, uuid_128];
        let exp_bytes: Vec<u8> =
            [exp_16.as_slice(), exp_32.as_slice(), exp_128.as_slice()].concat();
        AdvertiseData::append_service_uuids(&mut bytes, &uuids);
        assert_eq!(bytes, exp_bytes);

        // Interleaved UUIDs.
        let mut bytes = Vec::<u8>::new();
        let uuid_16_2 =
            Uuid::from(UuidHelper::from_string("0000aabb-0000-1000-8000-00805f9b34fb").unwrap());
        let uuids = vec![uuid_16, uuid_128, uuid_16_2, uuid_32];
        let exp_16: Vec<u8> = vec![5, 0x3, 0xf3, 0xfe, 0xbb, 0xaa];
        let exp_bytes: Vec<u8> =
            [exp_16.as_slice(), exp_32.as_slice(), exp_128.as_slice()].concat();
        AdvertiseData::append_service_uuids(&mut bytes, &uuids);
        assert_eq!(bytes, exp_bytes);
    }

    #[test]
    fn test_append_solicit_uuids() {
        let mut bytes = Vec::<u8>::new();
        let uuid_16 =
            Uuid::from(UuidHelper::from_string("0000fef3-0000-1000-8000-00805f9b34fb").unwrap());
        let uuid_32 =
            Uuid::from(UuidHelper::from_string("00112233-0000-1000-8000-00805f9b34fb").unwrap());
        let uuid_128 =
            Uuid::from(UuidHelper::from_string("00010203-0405-0607-0809-0a0b0c0d0e0f").unwrap());
        let uuids = vec![uuid_16, uuid_32, uuid_128];
        let exp_16: Vec<u8> = vec![3, 0x14, 0xf3, 0xfe];
        let exp_32: Vec<u8> = vec![5, 0x1f, 0x33, 0x22, 0x11, 0x0];
        let exp_128: Vec<u8> = vec![
            17, 0x15, 0xf, 0xe, 0xd, 0xc, 0xb, 0xa, 0x9, 0x8, 0x7, 0x6, 0x5, 0x4, 0x3, 0x2, 0x1,
            0x0,
        ];
        let exp_bytes: Vec<u8> =
            [exp_16.as_slice(), exp_32.as_slice(), exp_128.as_slice()].concat();
        AdvertiseData::append_solicit_uuids(&mut bytes, &uuids);
        assert_eq!(bytes, exp_bytes);
    }

    #[test]
    fn test_append_service_data_good_id() {
        let mut bytes = Vec::<u8>::new();
        let uuid_str = "0000fef3-0000-1000-8000-00805f9b34fb".to_string();
        let mut service_data = HashMap::new();
        let data: Vec<u8> = vec![
            0x4A, 0x17, 0x23, 0x41, 0x39, 0x37, 0x45, 0x11, 0x16, 0x60, 0x1D, 0xB8, 0x27, 0xA2,
            0xEF, 0xAA, 0xFE, 0x58, 0x04, 0x9F, 0xE3, 0x8F, 0xD0, 0x04, 0x29, 0x4F, 0xC2,
        ];
        service_data.insert(uuid_str, data.clone());
        let mut exp_bytes: Vec<u8> = vec![30, 0x16, 0xf3, 0xfe];
        exp_bytes.extend(data);
        AdvertiseData::append_service_data(&mut bytes, &service_data);
        assert_eq!(bytes, exp_bytes);
    }

    #[test]
    fn test_append_service_data_bad_id() {
        let mut bytes = Vec::<u8>::new();
        let uuid_str = "fef3".to_string();
        let mut service_data = HashMap::new();
        let data: Vec<u8> = vec![
            0x4A, 0x17, 0x23, 0x41, 0x39, 0x37, 0x45, 0x11, 0x16, 0x60, 0x1D, 0xB8, 0x27, 0xA2,
            0xEF, 0xAA, 0xFE, 0x58, 0x04, 0x9F, 0xE3, 0x8F, 0xD0, 0x04, 0x29, 0x4F, 0xC2,
        ];
        service_data.insert(uuid_str, data.clone());
        let exp_bytes: Vec<u8> = Vec::new();
        AdvertiseData::append_service_data(&mut bytes, &service_data);
        assert_eq!(bytes, exp_bytes);
    }

    #[test]
    fn test_append_device_name() {
        let mut bytes = Vec::<u8>::new();
        let complete_name = "abc".to_string();
        let exp_bytes: Vec<u8> = vec![5, 0x9, 0x61, 0x62, 0x63, 0x0];
        AdvertiseData::append_device_name(&mut bytes, &complete_name);
        assert_eq!(bytes, exp_bytes);

        let mut bytes = Vec::<u8>::new();
        let shortened_name = "abcdefghijklmnopqrstuvwxyz7890".to_string();
        let exp_bytes: Vec<u8> = vec![
            28, 0x8, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d,
            0x6e, 0x6f, 0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x0,
        ];
        AdvertiseData::append_device_name(&mut bytes, &shortened_name);
        assert_eq!(bytes, exp_bytes);
    }

    #[test]
    fn test_append_manufacturer_data() {
        let mut bytes = Vec::<u8>::new();
        let manufacturer_data = HashMap::from([(0x0123 as u16, vec![0, 1, 2])]);
        let exp_bytes: Vec<u8> = vec![6, 0xff, 0x23, 0x01, 0x0, 0x1, 0x2];
        AdvertiseData::append_manufacturer_data(&mut bytes, &manufacturer_data);
        assert_eq!(bytes, exp_bytes);
    }

    #[test]
    fn test_append_transport_discovery_data() {
        let mut bytes = Vec::<u8>::new();
        let transport_discovery_data = vec![vec![0, 1, 2]];
        let exp_bytes: Vec<u8> = vec![0x4, 0x26, 0x0, 0x1, 0x2];
        AdvertiseData::append_transport_discovery_data(&mut bytes, &transport_discovery_data);
        assert_eq!(bytes, exp_bytes);

        let mut bytes = Vec::<u8>::new();
        let transport_discovery_data = vec![vec![1, 2, 4, 8], vec![0xa, 0xb]];
        let exp_bytes: Vec<u8> = vec![0x5, 0x26, 0x1, 0x2, 0x4, 0x8, 3, 0x26, 0xa, 0xb];
        AdvertiseData::append_transport_discovery_data(&mut bytes, &transport_discovery_data);
        assert_eq!(bytes, exp_bytes);
    }
}
