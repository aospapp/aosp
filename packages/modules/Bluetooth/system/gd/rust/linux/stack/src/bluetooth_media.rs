//! Anything related to audio and media API.

use bt_topshim::btif::{
    BluetoothInterface, BtBondState, BtConnectionDirection, BtStatus, DisplayAddress, RawAddress,
    ToggleableProfile,
};
use bt_topshim::profiles::a2dp::{
    A2dp, A2dpCallbacks, A2dpCallbacksDispatcher, A2dpCodecBitsPerSample, A2dpCodecChannelMode,
    A2dpCodecConfig, A2dpCodecSampleRate, BtavAudioState, BtavConnectionState,
    PresentationPosition,
};
use bt_topshim::profiles::avrcp::{
    Avrcp, AvrcpCallbacks, AvrcpCallbacksDispatcher, PlayerMetadata,
};
use bt_topshim::profiles::hfp::{
    BthfAudioState, BthfConnectionState, CallHoldCommand, CallInfo, CallState, Hfp, HfpCallbacks,
    HfpCallbacksDispatcher, HfpCodecCapability, PhoneState, TelephonyDeviceStatus,
};
use bt_topshim::profiles::ProfileConnectionState;
use bt_topshim::{metrics, topstack};
use bt_utils::uinput::UInput;

use itertools::Itertools;
use log::{debug, info, warn};
use std::collections::{HashMap, HashSet};
use std::convert::TryFrom;
use std::sync::Arc;
use std::sync::Mutex;

use tokio::sync::mpsc::Sender;
use tokio::task::JoinHandle;
use tokio::time::{sleep, Duration, Instant};

use crate::battery_manager::{Battery, BatterySet};
use crate::battery_provider_manager::{
    BatteryProviderManager, IBatteryProviderCallback, IBatteryProviderManager,
};
use crate::bluetooth::{Bluetooth, BluetoothDevice, IBluetooth};
use crate::callbacks::Callbacks;
use crate::uuid;
use crate::uuid::Profile;
use crate::{Message, RPCProxy};

// The timeout we have to wait for all supported profiles to connect after we
// receive the first profile connected event. The host shall disconnect the
// device after this many seconds of timeout.
const PROFILE_DISCOVERY_TIMEOUT_SEC: u64 = 10;
// The timeout we have to wait for the initiator peer device to complete the
// initial profile connection. After this many seconds, we will begin to
// connect the missing profiles.
// 6s is set to align with Android's default. See "btservice/PhonePolicy".
const CONNECT_MISSING_PROFILES_TIMEOUT_SEC: u64 = 6;
// The duration we assume the role of the initiator, i.e. the side that starts
// the profile connection. If the profile is connected before this many seconds,
// we assume we are the initiator and can keep connecting the remaining
// profiles, otherwise we wait for the peer initiator.
// Set to 5s to align with default page timeout (BT spec vol 4 part E sec 6.6)
const CONNECT_AS_INITIATOR_TIMEOUT_SEC: u64 = 5;

/// The list of profiles we consider as audio profiles for media.
const MEDIA_AUDIO_PROFILES: &[uuid::Profile] =
    &[uuid::Profile::A2dpSink, uuid::Profile::Hfp, uuid::Profile::AvrcpController];

pub trait IBluetoothMedia {
    ///
    fn register_callback(&mut self, callback: Box<dyn IBluetoothMediaCallback + Send>) -> bool;

    /// initializes media (both A2dp and AVRCP) stack
    fn initialize(&mut self) -> bool;

    /// clean up media stack
    fn cleanup(&mut self) -> bool;

    /// connect to available but missing media profiles
    fn connect(&mut self, address: String);
    fn disconnect(&mut self, address: String);

    // Set the device as the active A2DP device
    fn set_active_device(&mut self, address: String);

    // Reset the active A2DP device
    fn reset_active_device(&mut self);

    // Set the device as the active HFP device
    fn set_hfp_active_device(&mut self, address: String);

    fn set_audio_config(
        &mut self,
        sample_rate: i32,
        bits_per_sample: i32,
        channel_mode: i32,
    ) -> bool;

    // Set the A2DP/AVRCP volume. Valid volume specified by the spec should be
    // in the range of 0-127.
    fn set_volume(&mut self, volume: u8);

    // Set the HFP speaker volume. Valid volume specified by the HFP spec should
    // be in the range of 0-15.
    fn set_hfp_volume(&mut self, volume: u8, address: String);
    fn start_audio_request(&mut self) -> bool;
    fn stop_audio_request(&mut self);

    /// Returns true iff A2DP audio has started.
    fn get_a2dp_audio_started(&mut self, address: String) -> bool;

    /// Returns the negotiated codec (CVSD=1, mSBC=2, LC3=4) to use if HFP audio has started.
    /// Returns 0 if HFP audio hasn't started.
    fn get_hfp_audio_final_codecs(&mut self, address: String) -> u8;

    fn get_presentation_position(&mut self) -> PresentationPosition;

    /// Start the SCO setup to connect audio
    fn start_sco_call(&mut self, address: String, sco_offload: bool, force_cvsd: bool) -> bool;
    fn stop_sco_call(&mut self, address: String);

    /// Set the current playback status: e.g., playing, paused, stopped, etc. The method is a copy
    /// of the existing CRAS API, hence not following Floss API conventions.
    fn set_player_playback_status(&mut self, status: String);
    /// Set the position of the current media in microseconds. The method is a copy of the existing
    /// CRAS API, hence not following Floss API conventions.
    fn set_player_position(&mut self, position: i64);
    /// Set the media metadata, including title, artist, album, and length. The method is a
    /// copy of the existing CRAS API, hence not following Floss API conventions. PlayerMetadata is
    /// a custom data type that requires special handlng.
    fn set_player_metadata(&mut self, metadata: PlayerMetadata);
}

pub trait IBluetoothMediaCallback: RPCProxy {
    /// Triggered when a Bluetooth audio device is ready to be used. This should
    /// only be triggered once for a device and send an event to clients. If the
    /// device supports both HFP and A2DP, both should be ready when this is
    /// triggered.
    fn on_bluetooth_audio_device_added(&mut self, device: BluetoothAudioDevice);

    ///
    fn on_bluetooth_audio_device_removed(&mut self, addr: String);

    ///
    fn on_absolute_volume_supported_changed(&mut self, supported: bool);

    /// Triggered when a Bluetooth device triggers an AVRCP/A2DP volume change
    /// event. We need to notify audio client to reflect the change on the audio
    /// stack. The volume should be in the range of 0 to 127.
    fn on_absolute_volume_changed(&mut self, volume: u8);

    /// Triggered when a Bluetooth device triggers a HFP AT command (AT+VGS) to
    /// notify AG about its speaker volume change. We need to notify audio
    /// client to reflect the change on the audio stack. The volume should be
    /// in the range of 0 to 15.
    fn on_hfp_volume_changed(&mut self, volume: u8, addr: String);

    /// Triggered when HFP audio is disconnected, in which case it could be
    /// waiting for the audio client to issue a reconnection request. We need
    /// to notify audio client of this event for it to do appropriate handling.
    fn on_hfp_audio_disconnected(&mut self, addr: String);
}

pub trait IBluetoothTelephony {
    /// Sets whether the device is connected to the cellular network.
    fn set_network_available(&mut self, network_available: bool);
    /// Sets whether the device is roaming.
    fn set_roaming(&mut self, roaming: bool);
    /// Sets the device signal strength, 0 to 5.
    fn set_signal_strength(&mut self, signal_strength: i32) -> bool;
    /// Sets the device battery level, 0 to 5.
    fn set_battery_level(&mut self, battery_level: i32) -> bool;
    /// Enables/disables phone operations.
    /// The call state is fully reset whenever this is called.
    fn set_phone_ops_enabled(&mut self, enable: bool);
    /// Acts like the AG received an incoming call.
    fn incoming_call(&mut self, number: String) -> bool;
    /// Acts like dialing a call from the AG.
    fn dialing_call(&mut self, number: String) -> bool;
    /// Acts like answering an incoming/dialing call from the AG.
    fn answer_call(&mut self) -> bool;
    /// Acts like hanging up an active/incoming/dialing call from the AG.
    fn hangup_call(&mut self) -> bool;
    /// Sets/unsets the memory slot. Note that we store at most one memory
    /// number and return it regardless of which slot is specified by HF.
    fn set_memory_call(&mut self, number: Option<String>) -> bool;
    /// Sets/unsets the last call.
    fn set_last_call(&mut self, number: Option<String>) -> bool;
    /// Releases all of the held calls.
    fn release_held(&mut self) -> bool;
    /// Releases the active call and accepts a held call.
    fn release_active_accept_held(&mut self) -> bool;
    /// Holds the active call and accepts a held call.
    fn hold_active_accept_held(&mut self) -> bool;
    /// Establishes an audio connection to <address>.
    fn audio_connect(&mut self, address: String) -> bool;
    /// Stops the audio connection to <address>.
    fn audio_disconnect(&mut self, address: String);
}

/// Serializable device used in.
#[derive(Debug, Default, Clone)]
pub struct BluetoothAudioDevice {
    pub address: String,
    pub name: String,
    pub a2dp_caps: Vec<A2dpCodecConfig>,
    pub hfp_cap: HfpCodecCapability,
    pub absolute_volume: bool,
}

impl BluetoothAudioDevice {
    pub(crate) fn new(
        address: String,
        name: String,
        a2dp_caps: Vec<A2dpCodecConfig>,
        hfp_cap: HfpCodecCapability,
        absolute_volume: bool,
    ) -> BluetoothAudioDevice {
        BluetoothAudioDevice { address, name, a2dp_caps, hfp_cap, absolute_volume }
    }
}
/// Actions that `BluetoothMedia` can take on behalf of the stack.
pub enum MediaActions {
    Connect(String),
    Disconnect(String),
    ForceEnterConnected(String), // Only used for qualification.
}

#[derive(Debug, Clone, PartialEq)]
enum DeviceConnectionStates {
    Initiating,            // Some profile is connected, initiated from host side
    ConnectingBeforeRetry, // Some profile is connected, probably initiated from peer side
    ConnectingAfterRetry,  // Host initiated requests to missing profiles after timeout
    FullyConnected,        // All profiles (excluding AVRCP) are connected
    Disconnecting,         // Working towards disconnection of each connected profile
}

pub struct BluetoothMedia {
    intf: Arc<Mutex<BluetoothInterface>>,
    battery_provider_manager: Arc<Mutex<Box<BatteryProviderManager>>>,
    battery_provider_id: u32,
    initialized: bool,
    callbacks: Arc<Mutex<Callbacks<dyn IBluetoothMediaCallback + Send>>>,
    tx: Sender<Message>,
    adapter: Option<Arc<Mutex<Box<Bluetooth>>>>,
    a2dp: Option<A2dp>,
    avrcp: Option<Avrcp>,
    avrcp_direction: BtConnectionDirection,
    a2dp_states: HashMap<RawAddress, BtavConnectionState>,
    a2dp_audio_state: HashMap<RawAddress, BtavAudioState>,
    a2dp_has_interrupted_stream: bool, // Only used for qualification.
    hfp: Option<Hfp>,
    hfp_states: HashMap<RawAddress, BthfConnectionState>,
    hfp_audio_state: HashMap<RawAddress, BthfAudioState>,
    a2dp_caps: HashMap<RawAddress, Vec<A2dpCodecConfig>>,
    hfp_cap: HashMap<RawAddress, HfpCodecCapability>,
    fallback_tasks: Arc<Mutex<HashMap<RawAddress, Option<(JoinHandle<()>, Instant)>>>>,
    absolute_volume: bool,
    uinput: UInput,
    delay_enable_profiles: HashSet<uuid::Profile>,
    connected_profiles: HashMap<RawAddress, HashSet<uuid::Profile>>,
    device_states: Arc<Mutex<HashMap<RawAddress, DeviceConnectionStates>>>,
    delay_volume_update: HashMap<uuid::Profile, u8>,
    telephony_device_status: TelephonyDeviceStatus,
    phone_state: PhoneState,
    call_list: Vec<CallInfo>,
    phone_ops_enabled: bool,
    memory_dialing_number: Option<String>,
    last_dialing_number: Option<String>,
}

impl BluetoothMedia {
    pub fn new(
        tx: Sender<Message>,
        intf: Arc<Mutex<BluetoothInterface>>,
        battery_provider_manager: Arc<Mutex<Box<BatteryProviderManager>>>,
    ) -> BluetoothMedia {
        let battery_provider_id = battery_provider_manager
            .lock()
            .unwrap()
            .register_battery_provider(Box::new(BatteryProviderCallback::new()));
        BluetoothMedia {
            intf,
            battery_provider_manager,
            battery_provider_id,
            initialized: false,
            callbacks: Arc::new(Mutex::new(Callbacks::new(
                tx.clone(),
                Message::MediaCallbackDisconnected,
            ))),
            tx,
            adapter: None,
            a2dp: None,
            avrcp: None,
            avrcp_direction: BtConnectionDirection::Unknown,
            a2dp_states: HashMap::new(),
            a2dp_audio_state: HashMap::new(),
            a2dp_has_interrupted_stream: false,
            hfp: None,
            hfp_states: HashMap::new(),
            hfp_audio_state: HashMap::new(),
            a2dp_caps: HashMap::new(),
            hfp_cap: HashMap::new(),
            fallback_tasks: Arc::new(Mutex::new(HashMap::new())),
            absolute_volume: false,
            uinput: UInput::new(),
            delay_enable_profiles: HashSet::new(),
            connected_profiles: HashMap::new(),
            device_states: Arc::new(Mutex::new(HashMap::new())),
            delay_volume_update: HashMap::new(),
            telephony_device_status: TelephonyDeviceStatus::new(),
            phone_state: PhoneState { num_active: 0, num_held: 0, state: CallState::Idle },
            call_list: vec![],
            phone_ops_enabled: false,
            memory_dialing_number: None,
            last_dialing_number: None,
        }
    }

    fn is_profile_connected(&self, addr: &RawAddress, profile: &uuid::Profile) -> bool {
        self.is_any_profile_connected(addr, &[profile.clone()])
    }

    fn is_any_profile_connected(&self, addr: &RawAddress, profiles: &[uuid::Profile]) -> bool {
        if let Some(connected_profiles) = self.connected_profiles.get(addr) {
            return profiles.iter().any(|p| connected_profiles.contains(&p));
        }

        return false;
    }

    fn add_connected_profile(&mut self, addr: RawAddress, profile: uuid::Profile) {
        if self.is_profile_connected(&addr, &profile) {
            warn!("[{}]: profile is already connected", DisplayAddress(&addr));
            return;
        }

        self.connected_profiles.entry(addr).or_insert_with(HashSet::new).insert(profile);

        self.notify_media_capability_updated(addr);
    }

    fn rm_connected_profile(
        &mut self,
        addr: RawAddress,
        profile: uuid::Profile,
        is_profile_critical: bool,
    ) {
        if !self.is_profile_connected(&addr, &profile) {
            warn!("[{}]: profile is already disconnected", DisplayAddress(&addr));
            return;
        }

        self.connected_profiles.entry(addr).or_insert_with(HashSet::new).remove(&profile);
        self.delay_volume_update.remove(&profile);

        if is_profile_critical && self.is_complete_profiles_required() {
            self.notify_critical_profile_disconnected(addr);
        }

        self.notify_media_capability_updated(addr);
    }

    pub fn set_adapter(&mut self, adapter: Arc<Mutex<Box<Bluetooth>>>) {
        self.adapter = Some(adapter);
    }

    pub fn enable_profile(&mut self, profile: &Profile) {
        match profile {
            &Profile::A2dpSource => {
                if let Some(a2dp) = &mut self.a2dp {
                    a2dp.enable();
                }
            }
            &Profile::AvrcpTarget => {
                if let Some(avrcp) = &mut self.avrcp {
                    avrcp.enable();
                }
            }
            &Profile::Hfp => {
                if let Some(hfp) = &mut self.hfp {
                    hfp.enable();
                }
            }
            _ => {
                warn!("Tried to enable {} in bluetooth_media", profile);
                return;
            }
        }

        if self.is_profile_enabled(profile).unwrap() {
            self.delay_enable_profiles.remove(profile);
        } else {
            self.delay_enable_profiles.insert(profile.clone());
        }
    }

    pub fn disable_profile(&mut self, profile: &Profile) {
        match profile {
            &Profile::A2dpSource => {
                if let Some(a2dp) = &mut self.a2dp {
                    a2dp.disable();
                }
            }
            &Profile::AvrcpTarget => {
                if let Some(avrcp) = &mut self.avrcp {
                    avrcp.disable();
                }
            }
            &Profile::Hfp => {
                if let Some(hfp) = &mut self.hfp {
                    hfp.disable();
                }
            }
            _ => {
                warn!("Tried to disable {} in bluetooth_media", profile);
                return;
            }
        }

        self.delay_enable_profiles.remove(profile);
    }

    pub fn is_profile_enabled(&self, profile: &Profile) -> Option<bool> {
        match profile {
            &Profile::A2dpSource => {
                Some(self.a2dp.as_ref().map_or(false, |a2dp| a2dp.is_enabled()))
            }
            &Profile::AvrcpTarget => {
                Some(self.avrcp.as_ref().map_or(false, |avrcp| avrcp.is_enabled()))
            }
            &Profile::Hfp => Some(self.hfp.as_ref().map_or(false, |hfp| hfp.is_enabled())),
            _ => {
                warn!("Tried to query enablement status of {} in bluetooth_media", profile);
                None
            }
        }
    }

    pub fn dispatch_a2dp_callbacks(&mut self, cb: A2dpCallbacks) {
        match cb {
            A2dpCallbacks::ConnectionState(addr, state, error) => {
                if !self.a2dp_states.get(&addr).is_none()
                    && state == *self.a2dp_states.get(&addr).unwrap()
                {
                    return;
                }
                metrics::profile_connection_state_changed(
                    addr,
                    Profile::A2dpSink as u32,
                    error.status,
                    state.clone() as u32,
                );
                match state {
                    BtavConnectionState::Connected => {
                        info!("[{}]: a2dp connected.", DisplayAddress(&addr));
                        self.a2dp_states.insert(addr, state);
                        self.add_connected_profile(addr, uuid::Profile::A2dpSink);
                    }
                    BtavConnectionState::Disconnected => {
                        info!("[{}]: a2dp disconnected.", DisplayAddress(&addr));
                        self.a2dp_states.remove(&addr);
                        self.a2dp_caps.remove(&addr);
                        self.a2dp_audio_state.remove(&addr);
                        self.rm_connected_profile(addr, uuid::Profile::A2dpSink, true);
                        if self.is_complete_profiles_required() {
                            self.disconnect(addr.to_string());
                        }
                    }
                    _ => {
                        self.a2dp_states.insert(addr, state);
                    }
                }
            }
            A2dpCallbacks::AudioState(addr, state) => {
                self.a2dp_audio_state.insert(addr, state);
            }
            A2dpCallbacks::AudioConfig(addr, _config, _local_caps, a2dp_caps) => {
                debug!("[{}]: a2dp updated audio config: {:?}", DisplayAddress(&addr), a2dp_caps);
                self.a2dp_caps.insert(addr, a2dp_caps);
            }
            A2dpCallbacks::MandatoryCodecPreferred(_addr) => {}
        }
    }

    pub fn dispatch_avrcp_callbacks(&mut self, cb: AvrcpCallbacks) {
        match cb {
            AvrcpCallbacks::AvrcpDeviceConnected(addr, supported) => {
                info!(
                    "[{}]: avrcp connected. Absolute volume support: {}.",
                    DisplayAddress(&addr),
                    supported
                );

                match self.uinput.create(self.adapter_get_remote_name(addr), addr.to_string()) {
                    Ok(()) => info!("uinput device created for: {}", DisplayAddress(&addr)),
                    Err(e) => warn!("{}", e),
                }

                // Notify change via callback if device is added.
                if self.absolute_volume != supported {
                    let guard = self.fallback_tasks.lock().unwrap();
                    if let Some(task) = guard.get(&addr) {
                        if task.is_none() {
                            self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                callback.on_absolute_volume_supported_changed(supported);
                            });
                        }
                    }
                }

                self.absolute_volume = supported;

                // If is device initiated the AVRCP connection, emit a fake connecting state as
                // stack don't receive one.
                if self.avrcp_direction != BtConnectionDirection::Outgoing {
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::AvrcpController as u32,
                        BtStatus::Success,
                        BtavConnectionState::Connecting as u32,
                    );
                }
                metrics::profile_connection_state_changed(
                    addr,
                    Profile::AvrcpController as u32,
                    BtStatus::Success,
                    BtavConnectionState::Connected as u32,
                );
                // Reset direction to unknown.
                self.avrcp_direction = BtConnectionDirection::Unknown;

                self.add_connected_profile(addr, uuid::Profile::AvrcpController);
            }
            AvrcpCallbacks::AvrcpDeviceDisconnected(addr) => {
                info!("[{}]: avrcp disconnected.", DisplayAddress(&addr));

                self.uinput.close(addr.to_string());

                // TODO: better support for multi-device
                self.absolute_volume = false;

                // This may be considered a critical profile in the extreme case
                // where only AVRCP was connected.
                let is_profile_critical = match self.connected_profiles.get(&addr) {
                    Some(profiles) => *profiles == HashSet::from([uuid::Profile::AvrcpController]),
                    None => false,
                };

                // If the peer device initiated the AVRCP disconnection, emit a fake connecting
                // state as stack don't receive one.
                if self.avrcp_direction != BtConnectionDirection::Outgoing {
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::AvrcpController as u32,
                        BtStatus::Success,
                        BtavConnectionState::Disconnecting as u32,
                    );
                }
                metrics::profile_connection_state_changed(
                    addr,
                    Profile::AvrcpController as u32,
                    BtStatus::Success,
                    BtavConnectionState::Disconnected as u32,
                );
                // Reset direction to unknown.
                self.avrcp_direction = BtConnectionDirection::Unknown;

                self.rm_connected_profile(
                    addr,
                    uuid::Profile::AvrcpController,
                    is_profile_critical,
                );
            }
            AvrcpCallbacks::AvrcpAbsoluteVolumeUpdate(volume) => {
                for (addr, state) in self.device_states.lock().unwrap().iter() {
                    info!("[{}]: state {:?}", DisplayAddress(&addr), state);
                    match state {
                        DeviceConnectionStates::ConnectingBeforeRetry
                        | DeviceConnectionStates::ConnectingAfterRetry => {
                            self.delay_volume_update.insert(Profile::AvrcpController, volume);
                        }
                        DeviceConnectionStates::FullyConnected => {
                            self.delay_volume_update.remove(&Profile::AvrcpController);
                            self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                callback.on_absolute_volume_changed(volume);
                            });
                            return;
                        }
                        _ => {}
                    }
                }
            }
            AvrcpCallbacks::AvrcpSendKeyEvent(key, value) => {
                match self.uinput.send_key(key, value) {
                    Ok(()) => (),
                    Err(e) => warn!("{}", e),
                }

                const AVRCP_ID_PAUSE: u8 = 0x46;
                const AVRCP_STATE_PRESS: u8 = 0;

                // Per MPS v1.0, on receiving a pause key through AVRCP,
                // central should pause the A2DP stream with an AVDTP suspend command.
                if self.phone_ops_enabled && key == AVRCP_ID_PAUSE && value == AVRCP_STATE_PRESS {
                    self.suspend_audio_request_impl();
                }
            }
            AvrcpCallbacks::AvrcpSetActiveDevice(addr) => {
                self.uinput.set_active_device(addr.to_string());
            }
        }
    }

    pub fn dispatch_media_actions(&mut self, action: MediaActions) {
        match action {
            MediaActions::Connect(address) => self.connect(address),
            MediaActions::Disconnect(address) => self.disconnect(address),
            MediaActions::ForceEnterConnected(address) => self.force_enter_connected(address),
        }
    }

    pub fn dispatch_hfp_callbacks(&mut self, cb: HfpCallbacks) {
        match cb {
            HfpCallbacks::ConnectionState(state, addr) => {
                if !self.hfp_states.get(&addr).is_none()
                    && state == *self.hfp_states.get(&addr).unwrap()
                {
                    return;
                }
                metrics::profile_connection_state_changed(
                    addr,
                    Profile::Hfp as u32,
                    BtStatus::Success,
                    state.clone() as u32,
                );
                match state {
                    BthfConnectionState::Connected => {
                        info!("[{}]: hfp connected.", DisplayAddress(&addr));
                    }
                    BthfConnectionState::SlcConnected => {
                        info!("[{}]: hfp slc connected.", DisplayAddress(&addr));
                        // The device may not support codec-negotiation,
                        // in which case we shall assume it supports CVSD at this point.
                        if !self.hfp_cap.contains_key(&addr) {
                            self.hfp_cap.insert(addr, HfpCodecCapability::CVSD);
                        }
                        self.add_connected_profile(addr, uuid::Profile::Hfp);

                        // Connect SCO if phone operations are enabled and an active call exists.
                        // This is only used for Bluetooth HFP qualification.
                        if self.phone_ops_enabled && self.phone_state.num_active > 0 {
                            debug!("[{}]: Connect SCO due to active call.", DisplayAddress(&addr));
                            self.start_sco_call_impl(addr.to_string(), false, false);
                        }
                    }
                    BthfConnectionState::Disconnected => {
                        info!("[{}]: hfp disconnected.", DisplayAddress(&addr));
                        self.hfp_states.remove(&addr);
                        self.hfp_cap.remove(&addr);
                        self.hfp_audio_state.remove(&addr);
                        self.rm_connected_profile(addr, uuid::Profile::Hfp, true);
                        if self.is_complete_profiles_required() {
                            self.disconnect(addr.to_string());
                        }
                    }
                    BthfConnectionState::Connecting => {
                        info!("[{}]: hfp connecting.", DisplayAddress(&addr));
                    }
                    BthfConnectionState::Disconnecting => {
                        info!("[{}]: hfp disconnecting.", DisplayAddress(&addr));
                    }
                }

                self.hfp_states.insert(addr, state);
            }
            HfpCallbacks::AudioState(state, addr) => {
                if self.hfp_states.get(&addr).is_none()
                    || BthfConnectionState::SlcConnected != *self.hfp_states.get(&addr).unwrap()
                {
                    warn!("[{}]: Unknown address hfp or slc not ready", DisplayAddress(&addr));
                    return;
                }

                match state {
                    BthfAudioState::Connected => {
                        info!("[{}]: hfp audio connected.", DisplayAddress(&addr));

                        self.hfp_audio_state.insert(addr, state);

                        // Change the phone state only when it's currently managed by media stack
                        // (I.e., phone operations are not enabled).
                        if !self.phone_ops_enabled && self.phone_state.num_active != 1 {
                            // This triggers a +CIEV command to set the call status for HFP devices.
                            // It is required for some devices to provide sound.
                            self.phone_state.num_active = 1;
                            self.call_list = vec![CallInfo {
                                index: 1,
                                dir_incoming: false,
                                state: CallState::Active,
                                number: "".into(),
                            }];
                            self.phone_state_change("".into());
                        }
                    }
                    BthfAudioState::Disconnected => {
                        info!("[{}]: hfp audio disconnected.", DisplayAddress(&addr));

                        // Ignore disconnected -> disconnected
                        if let Some(BthfAudioState::Connected) =
                            self.hfp_audio_state.insert(addr, state)
                        {
                            self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                                callback.on_hfp_audio_disconnected(addr.to_string());
                            });
                        }

                        // Change the phone state only when it's currently managed by media stack
                        // (I.e., phone operations are not enabled).
                        if !self.phone_ops_enabled && self.phone_state.num_active != 0 {
                            self.phone_state.num_active = 0;
                            self.call_list = vec![];
                            self.phone_state_change("".into());
                        }

                        // Resume the A2DP stream when a phone call ended (per MPS v1.0).
                        self.try_a2dp_resume();
                    }
                    BthfAudioState::Connecting => {
                        info!("[{}]: hfp audio connecting.", DisplayAddress(&addr));
                    }
                    BthfAudioState::Disconnecting => {
                        info!("[{}]: hfp audio disconnecting.", DisplayAddress(&addr));
                    }
                }
            }
            HfpCallbacks::VolumeUpdate(volume, addr) => {
                if self.hfp_states.get(&addr).is_none()
                    || BthfConnectionState::SlcConnected != *self.hfp_states.get(&addr).unwrap()
                {
                    warn!("[{}]: Unknown address hfp or slc not ready", addr.to_string());
                    return;
                }

                let states = self.device_states.lock().unwrap();
                info!(
                    "[{}]: VolumeUpdate state: {:?}",
                    DisplayAddress(&addr),
                    states.get(&addr).unwrap()
                );
                match states.get(&addr).unwrap() {
                    DeviceConnectionStates::ConnectingBeforeRetry
                    | DeviceConnectionStates::ConnectingAfterRetry => {
                        self.delay_volume_update.insert(Profile::Hfp, volume);
                    }
                    DeviceConnectionStates::FullyConnected => {
                        self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                            callback.on_hfp_volume_changed(volume, addr.to_string());
                        });
                    }
                    _ => {}
                }
            }
            HfpCallbacks::BatteryLevelUpdate(battery_level, addr) => {
                let battery_set = BatterySet::new(
                    addr.to_string(),
                    uuid::HFP.to_string(),
                    "HFP".to_string(),
                    vec![Battery { percentage: battery_level as u32, variant: "".to_string() }],
                );
                self.battery_provider_manager
                    .lock()
                    .unwrap()
                    .set_battery_info(self.battery_provider_id, battery_set);
            }
            HfpCallbacks::WbsCapsUpdate(wbs_supported, addr) => {
                if let Some(cur_hfp_cap) = self.hfp_cap.get_mut(&addr) {
                    if wbs_supported {
                        *cur_hfp_cap |= HfpCodecCapability::MSBC;
                    } else if (*cur_hfp_cap & HfpCodecCapability::MSBC) == HfpCodecCapability::MSBC
                    {
                        *cur_hfp_cap ^= HfpCodecCapability::MSBC;
                    }
                } else {
                    let new_hfp_cap = match wbs_supported {
                        true => HfpCodecCapability::CVSD | HfpCodecCapability::MSBC,
                        false => HfpCodecCapability::CVSD,
                    };
                    self.hfp_cap.insert(addr, new_hfp_cap);
                }
            }
            HfpCallbacks::SwbCapsUpdate(swb_supported, addr) => {
                if let Some(cur_hfp_cap) = self.hfp_cap.get_mut(&addr) {
                    if swb_supported {
                        *cur_hfp_cap |= HfpCodecCapability::LC3;
                    } else if (*cur_hfp_cap & HfpCodecCapability::LC3) == HfpCodecCapability::LC3 {
                        *cur_hfp_cap ^= HfpCodecCapability::LC3;
                    }
                } else {
                    let new_hfp_cap = match swb_supported {
                        true => HfpCodecCapability::CVSD | HfpCodecCapability::LC3,
                        false => HfpCodecCapability::CVSD,
                    };
                    self.hfp_cap.insert(addr, new_hfp_cap);
                }
            }
            HfpCallbacks::IndicatorQuery(addr) => {
                match self.hfp.as_mut() {
                    Some(hfp) => {
                        debug!(
                            "[{}]: Responding CIND query with device={:?} phone={:?}",
                            DisplayAddress(&addr),
                            self.telephony_device_status,
                            self.phone_state,
                        );
                        let status = hfp.indicator_query_response(
                            self.telephony_device_status,
                            self.phone_state,
                            addr,
                        );
                        if status != BtStatus::Success {
                            warn!(
                                "[{}]: CIND response failed, status={:?}",
                                DisplayAddress(&addr),
                                status
                            );
                        }
                    }
                    None => warn!("Uninitialized HFP to notify telephony status"),
                };
            }
            HfpCallbacks::CurrentCallsQuery(addr) => {
                match self.hfp.as_mut() {
                    Some(hfp) => {
                        debug!(
                            "[{}]: Responding CLCC query with call_list={:?}",
                            DisplayAddress(&addr),
                            self.call_list,
                        );
                        let status = hfp.current_calls_query_response(&self.call_list, addr);
                        if status != BtStatus::Success {
                            warn!(
                                "[{}]: CLCC response failed, status={:?}",
                                DisplayAddress(&addr),
                                status
                            );
                        }
                    }
                    None => warn!("Uninitialized HFP to notify telephony status"),
                };
            }
            HfpCallbacks::AnswerCall(addr) => {
                if !self.answer_call_impl() {
                    warn!("[{}]: answer_call triggered by ATA failed", DisplayAddress(&addr));
                    return;
                }
                self.phone_state_change("".into());

                debug!("[{}]: Start SCO call due to ATA", DisplayAddress(&addr));
                self.start_sco_call_impl(addr.to_string(), false, false);
            }
            HfpCallbacks::HangupCall(addr) => {
                if !self.hangup_call_impl() {
                    warn!("[{}]: hangup_call triggered by AT+CHUP failed", DisplayAddress(&addr));
                    return;
                }
                self.phone_state_change("".into());

                // Try resume the A2DP stream (per MPS v1.0) on rejecting an incoming call or an
                // outgoing call is rejected.
                // It may fail if a SCO connection is still active (terminate call case), in that
                // case we will retry on SCO disconnected.
                self.try_a2dp_resume();
            }
            HfpCallbacks::DialCall(number, addr) => {
                let number = if number == "" {
                    self.last_dialing_number.clone()
                } else if number.starts_with(">") {
                    self.memory_dialing_number.clone()
                } else {
                    Some(number)
                };

                let success = number.map_or(false, |num| self.dialing_call_impl(num));

                // Respond OK/ERROR to the HF which sent the command.
                // This should be called before calling phone_state_change.
                self.simple_at_response(success, addr.clone());
                if !success {
                    warn!("[{}]: Unexpected dialing command from HF", DisplayAddress(&addr));
                    return;
                }
                // Inform libbluetooth that the state has changed to dialing.
                self.phone_state_change("".into());
                self.try_a2dp_suspend();
                // Change to alerting state and inform libbluetooth.
                self.dialing_to_alerting();
                self.phone_state_change("".into());
            }
            HfpCallbacks::CallHold(command, addr) => {
                let success = match command {
                    CallHoldCommand::ReleaseHeld => self.release_held_impl(),
                    CallHoldCommand::ReleaseActiveAcceptHeld => {
                        self.release_active_accept_held_impl()
                    }
                    CallHoldCommand::HoldActiveAcceptHeld => self.hold_active_accept_held_impl(),
                    _ => false, // We only support the 3 operations above.
                };
                // Respond OK/ERROR to the HF which sent the command.
                // This should be called before calling phone_state_change.
                self.simple_at_response(success, addr.clone());
                if success {
                    // Success means the call state has changed. Inform libbluetooth.
                    self.phone_state_change("".into());
                } else {
                    warn!(
                        "[{}]: Unexpected or unsupported CHLD command {:?} from HF",
                        DisplayAddress(&addr),
                        command
                    );
                }
            }
        }
    }

    pub fn remove_callback(&mut self, id: u32) -> bool {
        self.callbacks.lock().unwrap().remove_callback(id)
    }

    fn notify_critical_profile_disconnected(&mut self, addr: RawAddress) {
        info!(
            "[{}]: Device connection state: {:?}.",
            DisplayAddress(&addr),
            DeviceConnectionStates::Disconnecting
        );

        let mut states = self.device_states.lock().unwrap();
        let prev_state = states.insert(addr, DeviceConnectionStates::Disconnecting).unwrap();
        if prev_state != DeviceConnectionStates::Disconnecting {
            let mut guard = self.fallback_tasks.lock().unwrap();
            if let Some(task) = guard.get(&addr) {
                match task {
                    // Abort pending task if there is any.
                    Some((handler, _ts)) => {
                        warn!(
                            "[{}]: Device disconnected a critical profile before it was added.",
                            DisplayAddress(&addr)
                        );
                        handler.abort();
                        guard.insert(addr, None);
                    }
                    // Notify device removal if it has been added.
                    None => {
                        info!(
                            "[{}]: Device disconnected a critical profile, removing the device.",
                            DisplayAddress(&addr)
                        );
                        self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                            callback.on_bluetooth_audio_device_removed(addr.to_string());
                        });
                    }
                };
            }
            self.delay_volume_update.clear();
        }
    }

    async fn wait_retry(
        _fallback_tasks: &Arc<Mutex<HashMap<RawAddress, Option<(JoinHandle<()>, Instant)>>>>,
        device_states: &Arc<Mutex<HashMap<RawAddress, DeviceConnectionStates>>>,
        txl: &Sender<Message>,
        addr: &RawAddress,
        first_conn_ts: Instant,
    ) {
        let now_ts = Instant::now();
        let total_duration = Duration::from_secs(CONNECT_MISSING_PROFILES_TIMEOUT_SEC);
        let sleep_duration = (first_conn_ts + total_duration).saturating_duration_since(now_ts);
        sleep(sleep_duration).await;

        device_states.lock().unwrap().insert(*addr, DeviceConnectionStates::ConnectingAfterRetry);

        info!(
            "[{}]: Device connection state: {:?}.",
            DisplayAddress(addr),
            DeviceConnectionStates::ConnectingAfterRetry
        );

        let _ = txl.send(Message::Media(MediaActions::Connect(addr.to_string()))).await;
    }

    async fn wait_disconnect(
        fallback_tasks: &Arc<Mutex<HashMap<RawAddress, Option<(JoinHandle<()>, Instant)>>>>,
        device_states: &Arc<Mutex<HashMap<RawAddress, DeviceConnectionStates>>>,
        txl: &Sender<Message>,
        addr: &RawAddress,
        first_conn_ts: Instant,
    ) {
        let now_ts = Instant::now();
        let total_duration = Duration::from_secs(PROFILE_DISCOVERY_TIMEOUT_SEC);
        let sleep_duration = (first_conn_ts + total_duration).saturating_duration_since(now_ts);
        sleep(sleep_duration).await;

        device_states.lock().unwrap().insert(*addr, DeviceConnectionStates::Disconnecting);
        fallback_tasks.lock().unwrap().insert(*addr, None);

        info!(
            "[{}]: Device connection state: {:?}.",
            DisplayAddress(addr),
            DeviceConnectionStates::Disconnecting
        );

        let _ = txl.send(Message::Media(MediaActions::Disconnect(addr.to_string()))).await;
    }

    async fn wait_force_enter_connected(
        txl: &Sender<Message>,
        addr: &RawAddress,
        first_conn_ts: Instant,
    ) {
        let now_ts = Instant::now();
        let total_duration = Duration::from_secs(CONNECT_MISSING_PROFILES_TIMEOUT_SEC);
        let sleep_duration = (first_conn_ts + total_duration).saturating_duration_since(now_ts);
        sleep(sleep_duration).await;
        let _ = txl.send(Message::Media(MediaActions::ForceEnterConnected(addr.to_string()))).await;
    }

    fn notify_media_capability_updated(&mut self, addr: RawAddress) {
        let mut guard = self.fallback_tasks.lock().unwrap();
        let mut states = self.device_states.lock().unwrap();
        let mut first_conn_ts = Instant::now();

        let is_profile_cleared = self.connected_profiles.get(&addr).unwrap().is_empty();

        if let Some(task) = guard.get(&addr) {
            if let Some((handler, ts)) = task {
                // Abort the pending task. It may be updated or
                // removed depending on whether all profiles are cleared.
                handler.abort();
                first_conn_ts = *ts;
                guard.insert(addr, None);
            } else {
                // The device is already added or is disconnecting.
                // Ignore unless all profiles are cleared.
                if !is_profile_cleared {
                    return;
                }
            }
        }

        // Cleanup if transitioning to empty set.
        if is_profile_cleared {
            info!("[{}]: Device connection state: Disconnected.", DisplayAddress(&addr));
            self.connected_profiles.remove(&addr);
            states.remove(&addr);
            guard.remove(&addr);
            return;
        }

        let available_profiles = self.adapter_get_audio_profiles(addr);
        let connected_profiles = self.connected_profiles.get(&addr).unwrap();
        let missing_profiles =
            available_profiles.difference(&connected_profiles).cloned().collect::<HashSet<_>>();

        // Update device states
        if states.get(&addr).is_none() {
            states.insert(addr, DeviceConnectionStates::ConnectingBeforeRetry);
        }
        if missing_profiles.is_empty()
            || missing_profiles == HashSet::from([Profile::AvrcpController])
        {
            info!(
                "[{}]: Fully connected, available profiles: {:?}, connected profiles: {:?}.",
                DisplayAddress(&addr),
                available_profiles,
                connected_profiles
            );

            states.insert(addr, DeviceConnectionStates::FullyConnected);
        }

        info!(
            "[{}]: Device connection state: {:?}.",
            DisplayAddress(&addr),
            states.get(&addr).unwrap()
        );

        // React on updated device states
        let tasks = self.fallback_tasks.clone();
        let device_states = self.device_states.clone();
        let txl = self.tx.clone();
        let ts = first_conn_ts;
        let is_complete_profiles_required = self.is_complete_profiles_required();
        match states.get(&addr).unwrap() {
            DeviceConnectionStates::Initiating => {
                let task = topstack::get_runtime().spawn(async move {
                    // As initiator we can just immediately start connecting
                    let _ = txl.send(Message::Media(MediaActions::Connect(addr.to_string()))).await;
                    if !is_complete_profiles_required {
                        BluetoothMedia::wait_force_enter_connected(&txl, &addr, ts).await;
                        return;
                    }
                    BluetoothMedia::wait_retry(&tasks, &device_states, &txl, &addr, ts).await;
                    BluetoothMedia::wait_disconnect(&tasks, &device_states, &txl, &addr, ts).await;
                });
                guard.insert(addr, Some((task, ts)));
            }
            DeviceConnectionStates::ConnectingBeforeRetry => {
                let task = topstack::get_runtime().spawn(async move {
                    if !is_complete_profiles_required {
                        BluetoothMedia::wait_force_enter_connected(&txl, &addr, ts).await;
                        return;
                    }
                    BluetoothMedia::wait_retry(&tasks, &device_states, &txl, &addr, ts).await;
                    BluetoothMedia::wait_disconnect(&tasks, &device_states, &txl, &addr, ts).await;
                });
                guard.insert(addr, Some((task, ts)));
            }
            DeviceConnectionStates::ConnectingAfterRetry => {
                let task = topstack::get_runtime().spawn(async move {
                    if !is_complete_profiles_required {
                        BluetoothMedia::wait_force_enter_connected(&txl, &addr, ts).await;
                        return;
                    }
                    BluetoothMedia::wait_disconnect(&tasks, &device_states, &txl, &addr, ts).await;
                });
                guard.insert(addr, Some((task, ts)));
            }
            DeviceConnectionStates::FullyConnected => {
                // Rejecting the unbonded connection after we finished our profile
                // reconnectinglogic to avoid a collision.
                if let Some(adapter) = &self.adapter {
                    if BtBondState::Bonded
                        != adapter.lock().unwrap().get_bond_state_by_addr(&addr.to_string())
                    {
                        warn!(
                            "[{}]: Rejecting a unbonded device's attempt to connect to media profiles",
                            DisplayAddress(&addr));
                        let fallback_tasks = self.fallback_tasks.clone();
                        let device_states = self.device_states.clone();
                        let txl = self.tx.clone();
                        let task = topstack::get_runtime().spawn(async move {
                            {
                                device_states
                                    .lock()
                                    .unwrap()
                                    .insert(addr, DeviceConnectionStates::Disconnecting);
                                fallback_tasks.lock().unwrap().insert(addr, None);
                            }

                            debug!(
                                "[{}]: Device connection state: {:?}.",
                                DisplayAddress(&addr),
                                DeviceConnectionStates::Disconnecting
                            );

                            let _ = txl
                                .send(Message::Media(MediaActions::Disconnect(addr.to_string())))
                                .await;
                        });
                        guard.insert(addr, Some((task, first_conn_ts)));
                        return;
                    }
                }

                let cur_a2dp_caps = self.a2dp_caps.get(&addr);
                let cur_hfp_cap = self.hfp_cap.get(&addr);
                let name = self.adapter_get_remote_name(addr);
                let absolute_volume = self.absolute_volume;
                let device = BluetoothAudioDevice::new(
                    addr.to_string(),
                    name.clone(),
                    cur_a2dp_caps.unwrap_or(&Vec::new()).to_vec(),
                    *cur_hfp_cap.unwrap_or(&HfpCodecCapability::UNSUPPORTED),
                    absolute_volume,
                );

                let hfp_volume = self.delay_volume_update.remove(&Profile::Hfp);
                let avrcp_volume = self.delay_volume_update.remove(&Profile::AvrcpController);

                self.callbacks.lock().unwrap().for_all_callbacks(|callback| {
                    callback.on_bluetooth_audio_device_added(device.clone());
                    if let Some(volume) = hfp_volume {
                        info!("Trigger HFP volume update to {}", DisplayAddress(&addr));
                        callback.on_hfp_volume_changed(volume, addr.to_string());
                    }

                    if let Some(volume) = avrcp_volume {
                        info!("Trigger avrcp volume update");
                        callback.on_absolute_volume_changed(volume);
                    }
                });

                guard.insert(addr, None);
            }
            DeviceConnectionStates::Disconnecting => {}
        }
    }

    fn adapter_get_remote_name(&self, addr: RawAddress) -> String {
        let device = BluetoothDevice::new(
            addr.to_string(),
            // get_remote_name needs a BluetoothDevice just for its address, the
            // name field is unused so construct one with a fake name.
            "Classic Device".to_string(),
        );
        if let Some(adapter) = &self.adapter {
            match adapter.lock().unwrap().get_remote_name(device).as_str() {
                "" => addr.to_string(),
                name => name.into(),
            }
        } else {
            addr.to_string()
        }
    }

    fn adapter_get_audio_profiles(&self, addr: RawAddress) -> HashSet<uuid::Profile> {
        let device = BluetoothDevice::new(addr.to_string(), "".to_string());
        if let Some(adapter) = &self.adapter {
            adapter
                .lock()
                .unwrap()
                .get_remote_uuids(device)
                .into_iter()
                .map(|u| uuid::UuidHelper::is_known_profile(&u))
                .filter(|u| u.is_some())
                .map(|u| u.unwrap())
                .filter(|u| MEDIA_AUDIO_PROFILES.contains(&u))
                .collect()
        } else {
            HashSet::new()
        }
    }

    pub fn get_hfp_connection_state(&self) -> ProfileConnectionState {
        if self.hfp_audio_state.values().any(|state| *state == BthfAudioState::Connected) {
            ProfileConnectionState::Active
        } else {
            let mut winning_state = ProfileConnectionState::Disconnected;
            for state in self.hfp_states.values() {
                // Grab any state higher than the current state.
                match state {
                    // Any SLC completed state means the profile is connected.
                    BthfConnectionState::SlcConnected => {
                        winning_state = ProfileConnectionState::Connected;
                    }

                    // Connecting or Connected are both counted as connecting for profile state
                    // since it's not a complete connection.
                    BthfConnectionState::Connecting | BthfConnectionState::Connected
                        if winning_state != ProfileConnectionState::Connected =>
                    {
                        winning_state = ProfileConnectionState::Connecting;
                    }

                    BthfConnectionState::Disconnecting
                        if winning_state == ProfileConnectionState::Disconnected =>
                    {
                        winning_state = ProfileConnectionState::Disconnecting;
                    }

                    _ => (),
                }
            }

            winning_state
        }
    }

    pub fn get_a2dp_connection_state(&self) -> ProfileConnectionState {
        if self.a2dp_audio_state.values().any(|state| *state == BtavAudioState::Started) {
            ProfileConnectionState::Active
        } else {
            let mut winning_state = ProfileConnectionState::Disconnected;
            for state in self.a2dp_states.values() {
                // Grab any state higher than the current state.
                match state {
                    BtavConnectionState::Connected => {
                        winning_state = ProfileConnectionState::Connected;
                    }

                    BtavConnectionState::Connecting
                        if winning_state != ProfileConnectionState::Connected =>
                    {
                        winning_state = ProfileConnectionState::Connecting;
                    }

                    BtavConnectionState::Disconnecting
                        if winning_state == ProfileConnectionState::Disconnected =>
                    {
                        winning_state = ProfileConnectionState::Disconnecting;
                    }

                    _ => (),
                }
            }

            winning_state
        }
    }

    pub fn filter_to_connected_audio_devices_from(
        &self,
        devices: &Vec<BluetoothDevice>,
    ) -> Vec<BluetoothDevice> {
        devices
            .iter()
            .filter(|d| {
                let addr = match RawAddress::from_string(&d.address) {
                    None => return false,
                    Some(a) => a,
                };

                self.is_any_profile_connected(&addr, &MEDIA_AUDIO_PROFILES)
            })
            .cloned()
            .collect()
    }

    fn start_audio_request_impl(&mut self) -> bool {
        debug!("Start audio request");

        match self.a2dp.as_mut() {
            Some(a2dp) => a2dp.start_audio_request(),
            None => {
                warn!("Uninitialized A2DP to start audio request");
                false
            }
        }
    }

    fn suspend_audio_request_impl(&mut self) {
        match self.a2dp.as_mut() {
            Some(a2dp) => a2dp.suspend_audio_request(),
            None => warn!("Uninitialized A2DP to suspend audio request"),
        };
    }

    fn try_a2dp_resume(&mut self) {
        if !self.phone_ops_enabled {
            return;
        }
        // Make sure there is no any SCO connection and then resume the A2DP stream.
        if self.a2dp_has_interrupted_stream
            && !self.hfp_audio_state.values().any(|state| *state == BthfAudioState::Connected)
        {
            self.a2dp_has_interrupted_stream = false;
            self.start_audio_request_impl();
        }
    }

    fn try_a2dp_suspend(&mut self) {
        if !self.phone_ops_enabled {
            return;
        }
        // Suspend the A2DP stream if there is any.
        if self.a2dp_audio_state.values().any(|state| *state == BtavAudioState::Started) {
            self.a2dp_has_interrupted_stream = true;
            self.suspend_audio_request_impl();
        }
    }

    fn start_sco_call_impl(
        &mut self,
        address: String,
        sco_offload: bool,
        force_cvsd: bool,
    ) -> bool {
        match (|| -> Result<(), &str> {
            let addr = RawAddress::from_string(address.clone())
                .ok_or("Can't start sco call with bad address")?;
            info!("Start sco call for {}", DisplayAddress(&addr));

            let hfp = self.hfp.as_mut().ok_or("Uninitialized HFP to start the sco call")?;
            if hfp.connect_audio(addr, sco_offload, force_cvsd) != 0 {
                return Err("SCO connect_audio status failed");
            }
            info!("SCO connect_audio status success");
            Ok(())
        })() {
            Ok(_) => true,
            Err(msg) => {
                warn!("{}", msg);
                false
            }
        }
    }

    fn stop_sco_call_impl(&mut self, address: String) {
        match (|| -> Result<(), &str> {
            let addr = RawAddress::from_string(address.clone())
                .ok_or("Can't stop sco call with bad address")?;
            info!("Stop sco call for {}", DisplayAddress(&addr));
            let hfp = self.hfp.as_mut().ok_or("Uninitialized HFP to stop the sco call")?;
            hfp.disconnect_audio(addr);
            Ok(())
        })() {
            Ok(_) => {}
            Err(msg) => warn!("{}", msg),
        }
    }

    fn device_status_notification(&mut self) {
        match self.hfp.as_mut() {
            Some(hfp) => {
                for (addr, state) in self.hfp_states.iter() {
                    if *state != BthfConnectionState::SlcConnected {
                        continue;
                    }
                    debug!(
                        "[{}]: Device status notification {:?}",
                        DisplayAddress(addr),
                        self.telephony_device_status
                    );
                    let status =
                        hfp.device_status_notification(self.telephony_device_status, addr.clone());
                    if status != BtStatus::Success {
                        warn!(
                            "[{}]: Device status notification failed, status={:?}",
                            DisplayAddress(addr),
                            status
                        );
                    }
                }
            }
            None => warn!("Uninitialized HFP to notify telephony status"),
        }
    }

    fn phone_state_change(&mut self, number: String) {
        match self.hfp.as_mut() {
            Some(hfp) => {
                for (addr, state) in self.hfp_states.iter() {
                    if *state != BthfConnectionState::SlcConnected {
                        continue;
                    }
                    debug!(
                        "[{}]: Phone state change state={:?} number={}",
                        DisplayAddress(addr),
                        self.phone_state,
                        number
                    );
                    let status = hfp.phone_state_change(self.phone_state, &number, addr.clone());
                    if status != BtStatus::Success {
                        warn!(
                            "[{}]: Device status notification failed, status={:?}",
                            DisplayAddress(addr),
                            status
                        );
                    }
                }
            }
            None => warn!("Uninitialized HFP to notify telephony status"),
        }
    }

    // Returns the minimum unoccupied index starting from 1.
    fn new_call_index(&self) -> i32 {
        (1..)
            .find(|&index| self.call_list.iter().all(|x| x.index != index))
            .expect("There must be an unoccupied index")
    }

    fn simple_at_response(&mut self, ok: bool, addr: RawAddress) {
        match self.hfp.as_mut() {
            Some(hfp) => {
                let status = hfp.simple_at_response(ok, addr.clone());
                if status != BtStatus::Success {
                    warn!("[{}]: AT response failed, status={:?}", DisplayAddress(&addr), status);
                }
            }
            None => warn!("Uninitialized HFP to send AT response"),
        }
    }

    fn answer_call_impl(&mut self) -> bool {
        if !self.phone_ops_enabled || self.phone_state.state == CallState::Idle {
            return false;
        }
        // There must be exactly one incoming/dialing call in the list.
        for c in self.call_list.iter_mut() {
            match c.state {
                CallState::Incoming | CallState::Dialing | CallState::Alerting => {
                    c.state = CallState::Active;
                    break;
                }
                _ => {}
            }
        }
        self.phone_state.state = CallState::Idle;
        self.phone_state.num_active += 1;
        true
    }

    fn hangup_call_impl(&mut self) -> bool {
        if !self.phone_ops_enabled {
            return false;
        }
        match self.phone_state.state {
            CallState::Idle if self.phone_state.num_active > 0 => {
                self.phone_state.num_active -= 1;
            }
            CallState::Incoming | CallState::Dialing | CallState::Alerting => {
                self.phone_state.state = CallState::Idle;
            }
            _ => {
                return false;
            }
        }
        // At this point, there must be exactly one incoming/dialing/alerting/active call to be
        // removed.
        self.call_list.retain(|x| match x.state {
            CallState::Active | CallState::Incoming | CallState::Dialing | CallState::Alerting => {
                false
            }
            _ => true,
        });
        true
    }

    fn dialing_call_impl(&mut self, number: String) -> bool {
        if !self.phone_ops_enabled
            || self.phone_state.state != CallState::Idle
            || self.phone_state.num_active > 0
        {
            return false;
        }
        self.call_list.push(CallInfo {
            index: self.new_call_index(),
            dir_incoming: false,
            state: CallState::Dialing,
            number: number.clone(),
        });
        self.phone_state.state = CallState::Dialing;
        true
    }

    fn dialing_to_alerting(&mut self) -> bool {
        if !self.phone_ops_enabled || self.phone_state.state != CallState::Dialing {
            return false;
        }
        for c in self.call_list.iter_mut() {
            if c.state == CallState::Dialing {
                c.state = CallState::Alerting;
                break;
            }
        }
        self.phone_state.state = CallState::Alerting;
        true
    }

    fn release_held_impl(&mut self) -> bool {
        if !self.phone_ops_enabled || self.phone_state.state != CallState::Idle {
            return false;
        }
        self.call_list.retain(|x| x.state != CallState::Held);
        self.phone_state.num_held = 0;
        true
    }

    fn release_active_accept_held_impl(&mut self) -> bool {
        if !self.phone_ops_enabled || self.phone_state.state != CallState::Idle {
            return false;
        }
        self.call_list.retain(|x| x.state != CallState::Active);
        self.phone_state.num_active = 0;
        // Activate the first held call
        for c in self.call_list.iter_mut() {
            if c.state == CallState::Held {
                c.state = CallState::Active;
                self.phone_state.num_held -= 1;
                self.phone_state.num_active += 1;
                break;
            }
        }
        true
    }

    fn hold_active_accept_held_impl(&mut self) -> bool {
        if !self.phone_ops_enabled || self.phone_state.state != CallState::Idle {
            return false;
        }

        self.phone_state.num_held += self.phone_state.num_active;
        self.phone_state.num_active = 0;

        for c in self.call_list.iter_mut() {
            match c.state {
                // Activate at most one held call
                CallState::Held if self.phone_state.num_active == 0 => {
                    c.state = CallState::Active;
                    self.phone_state.num_held -= 1;
                    self.phone_state.num_active = 1;
                }
                CallState::Active => {
                    c.state = CallState::Held;
                }
                _ => {}
            }
        }
        true
    }

    // Per MPS v1.0 (Multi-Profile Specification), disconnecting or failing to connect
    // a profile should not affect the others.
    // Allow partial profiles connection during qualification (phone operations are enabled).
    fn is_complete_profiles_required(&self) -> bool {
        !self.phone_ops_enabled
    }

    // Force the media enters the FullyConnected state and then triggers a retry.
    // This function is only used for qualification as a replacement of normal retry.
    // Usually PTS initiates the connection of the necessary profiles, and Floss should notify
    // CRAS of the new audio device regardless of the unconnected profiles.
    // Still retry in the end because some test cases require that.
    fn force_enter_connected(&mut self, address: String) {
        let addr = match RawAddress::from_string(address.clone()) {
            None => {
                warn!("Invalid device address for force_enter_connected");
                return;
            }
            Some(addr) => addr,
        };
        self.device_states
            .lock()
            .unwrap()
            .insert(addr.clone(), DeviceConnectionStates::FullyConnected);
        self.notify_media_capability_updated(addr);
        self.connect(address);
    }
    pub fn add_player(&mut self, name: String, browsing_supported: bool) {
        self.avrcp.as_mut().unwrap().add_player(&name, browsing_supported);
    }
}

fn get_a2dp_dispatcher(tx: Sender<Message>) -> A2dpCallbacksDispatcher {
    A2dpCallbacksDispatcher {
        dispatch: Box::new(move |cb| {
            let txl = tx.clone();
            topstack::get_runtime().spawn(async move {
                let _ = txl.send(Message::A2dp(cb)).await;
            });
        }),
    }
}

fn get_avrcp_dispatcher(tx: Sender<Message>) -> AvrcpCallbacksDispatcher {
    AvrcpCallbacksDispatcher {
        dispatch: Box::new(move |cb| {
            let txl = tx.clone();
            topstack::get_runtime().spawn(async move {
                let _ = txl.send(Message::Avrcp(cb)).await;
            });
        }),
    }
}

fn get_hfp_dispatcher(tx: Sender<Message>) -> HfpCallbacksDispatcher {
    HfpCallbacksDispatcher {
        dispatch: Box::new(move |cb| {
            let txl = tx.clone();
            topstack::get_runtime().spawn(async move {
                let _ = txl.send(Message::Hfp(cb)).await;
            });
        }),
    }
}

impl IBluetoothMedia for BluetoothMedia {
    fn register_callback(&mut self, callback: Box<dyn IBluetoothMediaCallback + Send>) -> bool {
        let _id = self.callbacks.lock().unwrap().add_callback(callback);
        true
    }

    fn initialize(&mut self) -> bool {
        if self.initialized {
            return false;
        }
        self.initialized = true;

        // A2DP
        let a2dp_dispatcher = get_a2dp_dispatcher(self.tx.clone());
        self.a2dp = Some(A2dp::new(&self.intf.lock().unwrap()));
        self.a2dp.as_mut().unwrap().initialize(a2dp_dispatcher);

        // AVRCP
        let avrcp_dispatcher = get_avrcp_dispatcher(self.tx.clone());
        self.avrcp = Some(Avrcp::new(&self.intf.lock().unwrap()));
        self.avrcp.as_mut().unwrap().initialize(avrcp_dispatcher);

        // HFP
        let hfp_dispatcher = get_hfp_dispatcher(self.tx.clone());
        self.hfp = Some(Hfp::new(&self.intf.lock().unwrap()));
        self.hfp.as_mut().unwrap().initialize(hfp_dispatcher);

        for profile in self.delay_enable_profiles.clone() {
            self.enable_profile(&profile);
        }
        true
    }

    fn connect(&mut self, address: String) {
        let addr = match RawAddress::from_string(address.clone()) {
            None => {
                warn!("Invalid device address for connecting");
                return;
            }
            Some(addr) => addr,
        };

        let available_profiles = self.adapter_get_audio_profiles(addr);

        info!(
            "[{}]: Connecting to device, available profiles: {:?}.",
            DisplayAddress(&addr),
            available_profiles
        );

        let connected_profiles = self.connected_profiles.entry(addr).or_insert_with(HashSet::new);

        // Sort here so the order of connection is always consistent
        let missing_profiles =
            available_profiles.difference(&connected_profiles).sorted().collect::<Vec<_>>();

        // Connect the profiles one-by-one so it won't stuck at the lower layer.
        // Therefore, just connect to one profile for now.
        // connect() will be called again after the first profile is successfully connected.
        let mut is_connect = false;
        for profile in missing_profiles {
            match profile {
                uuid::Profile::A2dpSink => {
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::A2dpSink as u32,
                        BtStatus::Success,
                        BtavConnectionState::Connecting as u32,
                    );
                    match self.a2dp.as_mut() {
                        Some(a2dp) => {
                            let status: BtStatus = a2dp.connect(addr);
                            if BtStatus::Success != status {
                                metrics::profile_connection_state_changed(
                                    addr,
                                    Profile::A2dpSink as u32,
                                    status,
                                    BtavConnectionState::Disconnected as u32,
                                );
                            } else {
                                is_connect = true;
                                break;
                            }
                        }
                        None => {
                            warn!("Uninitialized A2DP to connect {}", DisplayAddress(&addr));
                            metrics::profile_connection_state_changed(
                                addr,
                                Profile::A2dpSink as u32,
                                BtStatus::NotReady,
                                BtavConnectionState::Disconnected as u32,
                            );
                        }
                    };
                }
                uuid::Profile::Hfp => {
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::Hfp as u32,
                        BtStatus::Success,
                        BtavConnectionState::Connecting as u32,
                    );
                    match self.hfp.as_mut() {
                        Some(hfp) => {
                            let status: BtStatus = hfp.connect(addr);
                            if BtStatus::Success != status {
                                metrics::profile_connection_state_changed(
                                    addr,
                                    Profile::Hfp as u32,
                                    status,
                                    BthfConnectionState::Disconnected as u32,
                                );
                            } else {
                                is_connect = true;
                                break;
                            }
                        }
                        None => {
                            warn!("Uninitialized HFP to connect {}", DisplayAddress(&addr));
                            metrics::profile_connection_state_changed(
                                addr,
                                Profile::Hfp as u32,
                                BtStatus::NotReady,
                                BthfConnectionState::Disconnected as u32,
                            );
                        }
                    };
                }
                uuid::Profile::AvrcpController => {
                    // Fluoride will resolve AVRCP as a part of A2DP connection request.
                    // Explicitly connect to it only when it is considered missing, and don't
                    // bother about it when A2DP is not connected.
                    if !connected_profiles.contains(&Profile::A2dpSink) {
                        continue;
                    }

                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::AvrcpController as u32,
                        BtStatus::Success,
                        BtavConnectionState::Connecting as u32,
                    );
                    match self.avrcp.as_mut() {
                        Some(avrcp) => {
                            self.avrcp_direction = BtConnectionDirection::Outgoing;
                            let status: BtStatus = avrcp.connect(addr);
                            if BtStatus::Success != status {
                                // Reset direction to unknown.
                                self.avrcp_direction = BtConnectionDirection::Unknown;
                                metrics::profile_connection_state_changed(
                                    addr,
                                    Profile::AvrcpController as u32,
                                    status,
                                    BtavConnectionState::Disconnected as u32,
                                );
                            } else {
                                is_connect = true;
                                break;
                            }
                        }

                        None => {
                            warn!("Uninitialized AVRCP to connect {}", DisplayAddress(&addr));
                            metrics::profile_connection_state_changed(
                                addr,
                                Profile::AvrcpController as u32,
                                BtStatus::NotReady,
                                BtavConnectionState::Disconnected as u32,
                            );
                        }
                    };
                }
                _ => warn!("Unknown profile: {:?}", profile),
            }
        }

        if is_connect {
            let mut tasks = self.fallback_tasks.lock().unwrap();
            let mut states = self.device_states.lock().unwrap();
            if !tasks.contains_key(&addr) {
                states.insert(addr, DeviceConnectionStates::Initiating);

                let fallback_tasks = self.fallback_tasks.clone();
                let device_states = self.device_states.clone();
                let now_ts = Instant::now();
                let task = topstack::get_runtime().spawn(async move {
                    sleep(Duration::from_secs(CONNECT_AS_INITIATOR_TIMEOUT_SEC)).await;

                    // If here the task is not yet aborted, probably connection is failed,
                    // therefore here we release the states. Even if later the connection is
                    // actually successful, we will just treat this as if the connection is
                    // initiated by the peer and will reconnect the missing profiles after
                    // some time, so it's safe.
                    {
                        device_states.lock().unwrap().remove(&addr);
                        fallback_tasks.lock().unwrap().remove(&addr);
                    }
                });
                tasks.insert(addr, Some((task, now_ts)));
            }
        }
    }

    fn cleanup(&mut self) -> bool {
        true
    }

    // TODO(b/278963515): Currently this is designed to be called from both the
    // UI and via disconnection callbacks. Remove this workaround once the
    // proper fix has landed.
    fn disconnect(&mut self, address: String) {
        let addr = match RawAddress::from_string(address.clone()) {
            None => {
                warn!("Invalid device address for disconnecting");
                return;
            }
            Some(addr) => addr,
        };

        let connected_profiles = match self.connected_profiles.get(&addr) {
            Some(profiles) => profiles,
            None => {
                warn!(
                    "[{}]: Ignoring disconnection request since there is no connected profile.",
                    DisplayAddress(&addr)
                );
                return;
            }
        };

        for profile in connected_profiles {
            match profile {
                uuid::Profile::A2dpSink => {
                    // Some headsets (b/278963515) will try reconnecting to A2DP
                    // when HFP is running but (requested to be) disconnected.
                    if connected_profiles.contains(&Profile::Hfp) {
                        continue;
                    }
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::A2dpSink as u32,
                        BtStatus::Success,
                        BtavConnectionState::Disconnecting as u32,
                    );
                    match self.a2dp.as_mut() {
                        Some(a2dp) => {
                            let status: BtStatus = a2dp.disconnect(addr);
                            if BtStatus::Success != status {
                                metrics::profile_connection_state_changed(
                                    addr,
                                    Profile::A2dpSource as u32,
                                    status,
                                    BtavConnectionState::Disconnected as u32,
                                );
                            }
                        }
                        None => {
                            warn!("Uninitialized A2DP to disconnect {}", DisplayAddress(&addr));
                            metrics::profile_connection_state_changed(
                                addr,
                                Profile::A2dpSource as u32,
                                BtStatus::NotReady,
                                BtavConnectionState::Disconnected as u32,
                            );
                        }
                    };
                }
                uuid::Profile::Hfp => {
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::Hfp as u32,
                        BtStatus::Success,
                        BthfConnectionState::Disconnecting as u32,
                    );
                    match self.hfp.as_mut() {
                        Some(hfp) => {
                            let status: BtStatus = hfp.disconnect(addr);
                            if BtStatus::Success != status {
                                metrics::profile_connection_state_changed(
                                    addr,
                                    Profile::Hfp as u32,
                                    status,
                                    BthfConnectionState::Disconnected as u32,
                                );
                            }
                        }
                        None => {
                            warn!("Uninitialized HFP to disconnect {}", DisplayAddress(&addr));
                            metrics::profile_connection_state_changed(
                                addr,
                                Profile::Hfp as u32,
                                BtStatus::NotReady,
                                BthfConnectionState::Disconnected as u32,
                            );
                        }
                    };
                }
                uuid::Profile::AvrcpController => {
                    if connected_profiles.contains(&Profile::A2dpSink) {
                        continue;
                    }
                    metrics::profile_connection_state_changed(
                        addr,
                        Profile::AvrcpController as u32,
                        BtStatus::Success,
                        BtavConnectionState::Disconnecting as u32,
                    );
                    match self.avrcp.as_mut() {
                        Some(avrcp) => {
                            self.avrcp_direction = BtConnectionDirection::Outgoing;
                            let status: BtStatus = avrcp.disconnect(addr);
                            if BtStatus::Success != status {
                                // Reset direction to unknown.
                                self.avrcp_direction = BtConnectionDirection::Unknown;
                                metrics::profile_connection_state_changed(
                                    addr,
                                    Profile::AvrcpController as u32,
                                    status,
                                    BtavConnectionState::Disconnected as u32,
                                );
                            }
                        }

                        None => {
                            warn!("Uninitialized AVRCP to disconnect {}", DisplayAddress(&addr));
                            metrics::profile_connection_state_changed(
                                addr,
                                Profile::AvrcpController as u32,
                                BtStatus::NotReady,
                                BtavConnectionState::Disconnected as u32,
                            );
                        }
                    };
                }
                _ => warn!("Unknown profile: {:?}", profile),
            }
        }
    }

    fn set_active_device(&mut self, address: String) {
        let addr = match RawAddress::from_string(address.clone()) {
            None => {
                warn!("Invalid device address for set_active_device");
                return;
            }
            Some(addr) => addr,
        };

        match self.a2dp_states.get(&addr) {
            Some(BtavConnectionState::Connected) => {
                if let Some(a2dp) = self.a2dp.as_mut() {
                    a2dp.set_active_device(addr);
                    self.uinput.set_active_device(addr.to_string());
                } else {
                    warn!("Uninitialized A2DP to set active device");
                }
            }
            _ => warn!("[{}] Not connected or disconnected A2DP address", address),
        };
    }

    fn reset_active_device(&mut self) {
        // During MPS tests, there might be some A2DP stream manipulation unexpected to CRAS.
        // CRAS would then attempt to reset the active device. Ignore it during test.
        if !self.is_complete_profiles_required() {
            return;
        }

        if let Some(a2dp) = self.a2dp.as_mut() {
            a2dp.set_active_device(RawAddress::empty());
        } else {
            warn!("Uninitialized A2DP to set active device");
        }
        self.uinput.set_active_device(RawAddress::empty().to_string());
    }

    fn set_hfp_active_device(&mut self, address: String) {
        let addr = match RawAddress::from_string(address.clone()) {
            None => {
                warn!("Invalid device address for set_hfp_active_device");
                return;
            }
            Some(addr) => addr,
        };

        match self.hfp_states.get(&addr) {
            Some(BthfConnectionState::SlcConnected) => {
                if let Some(hfp) = self.hfp.as_mut() {
                    hfp.set_active_device(addr);
                } else {
                    warn!("Uninitialized HFP to set active device");
                }
            }
            _ => warn!("[{}] Not connected or disconnected HFP address", address),
        }
    }

    fn set_audio_config(
        &mut self,
        sample_rate: i32,
        bits_per_sample: i32,
        channel_mode: i32,
    ) -> bool {
        if !A2dpCodecSampleRate::validate_bits(sample_rate)
            || !A2dpCodecBitsPerSample::validate_bits(bits_per_sample)
            || !A2dpCodecChannelMode::validate_bits(channel_mode)
        {
            return false;
        }

        match self.a2dp.as_mut() {
            Some(a2dp) => {
                a2dp.set_audio_config(sample_rate, bits_per_sample, channel_mode);
                true
            }
            None => {
                warn!("Uninitialized A2DP to set audio config");
                false
            }
        }
    }

    fn set_volume(&mut self, volume: u8) {
        // Guard the range 0-127 by the try_from cast from u8 to i8.
        let vol = match i8::try_from(volume) {
            Ok(val) => val,
            _ => {
                warn!("Ignore invalid volume {}", volume);
                return;
            }
        };

        match self.avrcp.as_mut() {
            Some(avrcp) => avrcp.set_volume(vol),
            None => warn!("Uninitialized AVRCP to set volume"),
        };
    }

    fn set_hfp_volume(&mut self, volume: u8, address: String) {
        let addr = match RawAddress::from_string(address.clone()) {
            None => {
                warn!("Invalid device address for set_hfp_volume");
                return;
            }
            Some(addr) => addr,
        };

        let vol = match i8::try_from(volume) {
            Ok(val) if val <= 15 => val,
            _ => {
                warn!("[{}]: Ignore invalid volume {}", DisplayAddress(&addr), volume);
                return;
            }
        };

        if self.hfp_states.get(&addr).is_none() {
            warn!(
                "[{}]: Ignore volume event for unconnected or disconnected HFP device",
                DisplayAddress(&addr)
            );
            return;
        }

        match self.hfp.as_mut() {
            Some(hfp) => {
                hfp.set_volume(vol, addr);
            }
            None => warn!("Uninitialized HFP to set volume"),
        };
    }

    fn start_audio_request(&mut self) -> bool {
        self.start_audio_request_impl()
    }

    fn stop_audio_request(&mut self) {
        if !self.a2dp_audio_state.values().any(|state| *state == BtavAudioState::Started) {
            info!("No active stream on A2DP device, ignoring request to stop audio.");
            return;
        }

        debug!("Stop audio request");

        match self.a2dp.as_mut() {
            Some(a2dp) => a2dp.stop_audio_request(),
            None => warn!("Uninitialized A2DP to stop audio request"),
        };
    }

    fn start_sco_call(&mut self, address: String, sco_offload: bool, force_cvsd: bool) -> bool {
        self.start_sco_call_impl(address, sco_offload, force_cvsd)
    }

    fn stop_sco_call(&mut self, address: String) {
        self.stop_sco_call_impl(address)
    }

    fn get_a2dp_audio_started(&mut self, address: String) -> bool {
        let addr = match RawAddress::from_string(address.clone()) {
            None => {
                warn!("Invalid device address for get_a2dp_audio_started");
                return false;
            }
            Some(addr) => addr,
        };

        match self.a2dp_audio_state.get(&addr) {
            Some(BtavAudioState::Started) => true,
            _ => false,
        }
    }

    fn get_hfp_audio_final_codecs(&mut self, address: String) -> u8 {
        let addr = match RawAddress::from_string(address.clone()) {
            None => {
                warn!("Invalid device address for get_hfp_audio_final_codecs");
                return 0;
            }
            Some(addr) => addr,
        };

        match self.hfp_audio_state.get(&addr) {
            Some(BthfAudioState::Connected) => match self.hfp_cap.get(&addr) {
                Some(caps) if (*caps & HfpCodecCapability::LC3) == HfpCodecCapability::LC3 => 4,
                Some(caps) if (*caps & HfpCodecCapability::MSBC) == HfpCodecCapability::MSBC => 2,
                Some(caps) if (*caps & HfpCodecCapability::CVSD) == HfpCodecCapability::CVSD => 1,
                _ => {
                    warn!("hfp_cap not found, fallback to CVSD.");
                    1
                }
            },
            _ => 0,
        }
    }

    fn get_presentation_position(&mut self) -> PresentationPosition {
        let position = match self.a2dp.as_mut() {
            Some(a2dp) => a2dp.get_presentation_position(),
            None => {
                warn!("Uninitialized A2DP to get presentation position");
                Default::default()
            }
        };
        PresentationPosition {
            remote_delay_report_ns: position.remote_delay_report_ns,
            total_bytes_read: position.total_bytes_read,
            data_position_sec: position.data_position_sec,
            data_position_nsec: position.data_position_nsec,
        }
    }

    fn set_player_playback_status(&mut self, status: String) {
        debug!("AVRCP received player playback status: {}", status);
        match self.avrcp.as_mut() {
            Some(avrcp) => avrcp.set_playback_status(&status),
            None => warn!("Uninitialized AVRCP to set player playback status"),
        };
    }
    fn set_player_position(&mut self, position_us: i64) {
        debug!("AVRCP received player position: {}", position_us);
        match self.avrcp.as_mut() {
            Some(avrcp) => avrcp.set_position(position_us),
            None => warn!("Uninitialized AVRCP to set player position"),
        };
    }
    fn set_player_metadata(&mut self, metadata: PlayerMetadata) {
        debug!("AVRCP received player metadata: {:?}", metadata);
        match self.avrcp.as_mut() {
            Some(avrcp) => avrcp.set_metadata(&metadata),
            None => warn!("Uninitialized AVRCP to set player playback status"),
        };
    }
}

impl IBluetoothTelephony for BluetoothMedia {
    fn set_network_available(&mut self, network_available: bool) {
        if self.telephony_device_status.network_available == network_available {
            return;
        }
        self.telephony_device_status.network_available = network_available;
        self.device_status_notification();
    }

    fn set_roaming(&mut self, roaming: bool) {
        if self.telephony_device_status.roaming == roaming {
            return;
        }
        self.telephony_device_status.roaming = roaming;
        self.device_status_notification();
    }

    fn set_signal_strength(&mut self, signal_strength: i32) -> bool {
        if signal_strength < 0 || signal_strength > 5 {
            warn!("Invalid signal strength, got {}, want 0 to 5", signal_strength);
            return false;
        }
        if self.telephony_device_status.signal_strength == signal_strength {
            return true;
        }

        self.telephony_device_status.signal_strength = signal_strength;
        self.device_status_notification();

        true
    }

    fn set_battery_level(&mut self, battery_level: i32) -> bool {
        if battery_level < 0 || battery_level > 5 {
            warn!("Invalid battery level, got {}, want 0 to 5", battery_level);
            return false;
        }
        if self.telephony_device_status.battery_level == battery_level {
            return true;
        }

        self.telephony_device_status.battery_level = battery_level;
        self.device_status_notification();

        true
    }

    fn set_phone_ops_enabled(&mut self, enable: bool) {
        if self.phone_ops_enabled == enable {
            return;
        }

        self.call_list = vec![];
        self.phone_state.num_active = 0;
        self.phone_state.num_held = 0;
        self.phone_state.state = CallState::Idle;
        self.memory_dialing_number = None;
        self.last_dialing_number = None;
        self.a2dp_has_interrupted_stream = false;

        if !enable {
            if self.hfp_states.values().any(|x| x == &BthfConnectionState::SlcConnected) {
                self.call_list.push(CallInfo {
                    index: 1,
                    dir_incoming: false,
                    state: CallState::Active,
                    number: "".into(),
                });
                self.phone_state.num_active = 1;
            }
        }

        self.phone_ops_enabled = enable;
        self.phone_state_change("".into());
    }

    fn incoming_call(&mut self, number: String) -> bool {
        if !self.phone_ops_enabled
            || self.phone_state.state != CallState::Idle
            || self.phone_state.num_active > 0
        {
            return false;
        }
        self.call_list.push(CallInfo {
            index: self.new_call_index(),
            dir_incoming: true,
            state: CallState::Incoming,
            number: number.clone(),
        });
        self.phone_state.state = CallState::Incoming;
        self.phone_state_change(number);
        self.try_a2dp_suspend();
        true
    }

    fn dialing_call(&mut self, number: String) -> bool {
        if !self.dialing_call_impl(number) {
            return false;
        }
        self.phone_state_change("".into());
        self.try_a2dp_suspend();
        // Change to alerting state and inform libbluetooth.
        self.dialing_to_alerting();
        self.phone_state_change("".into());
        true
    }

    fn answer_call(&mut self) -> bool {
        if !self.answer_call_impl() {
            return false;
        }
        self.phone_state_change("".into());

        // Find a connected HFP and try to establish an SCO.
        if let Some(addr) = self.hfp_states.iter().find_map(|(addr, state)| {
            if *state == BthfConnectionState::SlcConnected {
                Some(addr.clone())
            } else {
                None
            }
        }) {
            info!("Start SCO call due to call answered");
            self.start_sco_call_impl(addr.to_string(), false, false);
        }

        true
    }

    fn hangup_call(&mut self) -> bool {
        if !self.hangup_call_impl() {
            return false;
        }
        self.phone_state_change("".into());
        // Try resume the A2DP stream (per MPS v1.0) on rejecting an incoming call or an
        // outgoing call is rejected.
        // It may fail if a SCO connection is still active (terminate call case), in that
        // case we will retry on SCO disconnected.
        self.try_a2dp_resume();
        true
    }

    fn set_memory_call(&mut self, number: Option<String>) -> bool {
        if !self.phone_ops_enabled {
            return false;
        }
        self.memory_dialing_number = number;
        true
    }

    fn set_last_call(&mut self, number: Option<String>) -> bool {
        if !self.phone_ops_enabled {
            return false;
        }
        self.last_dialing_number = number;
        true
    }

    fn release_held(&mut self) -> bool {
        if !self.release_held_impl() {
            return false;
        }
        self.phone_state_change("".into());
        true
    }

    fn release_active_accept_held(&mut self) -> bool {
        if !self.release_active_accept_held_impl() {
            return false;
        }
        self.phone_state_change("".into());
        true
    }

    fn hold_active_accept_held(&mut self) -> bool {
        if !self.hold_active_accept_held_impl() {
            return false;
        }
        self.phone_state_change("".into());
        true
    }

    fn audio_connect(&mut self, address: String) -> bool {
        self.start_sco_call_impl(address, false, false)
    }

    fn audio_disconnect(&mut self, address: String) {
        self.stop_sco_call_impl(address)
    }
}

struct BatteryProviderCallback {}

impl BatteryProviderCallback {
    fn new() -> Self {
        Self {}
    }
}

impl IBatteryProviderCallback for BatteryProviderCallback {
    // We do not support refreshing HFP battery information.
    fn refresh_battery_info(&mut self) {}
}

impl RPCProxy for BatteryProviderCallback {
    fn get_object_id(&self) -> String {
        "HFP BatteryProvider Callback".to_string()
    }
}
