use lazy_static::lazy_static;
use log::{error, info};
use paste::paste;
use std::collections::{BTreeMap, HashMap};
use std::convert::TryFrom;
use std::fmt;
use std::sync::Mutex;

// Fallback to bool when type is not specified
macro_rules! type_expand {
    () => {
        bool
    };
    ($type:ty) => {
        $type
    };
}

macro_rules! default_value {
    () => {
        false
    };
    ($type:ty) => {
        <$type>::default()
    };
    ($($type:ty)? = $default:tt) => {
        $default
    };
}

macro_rules! test_value {
    () => {
        true
    };
    ($type:ty) => {
        <$type>::default()
    };
}

#[cfg(test)]
macro_rules! call_getter_fn {
    ($flag:ident) => {
        paste! {
            [<$flag _is_enabled>]()
        }
    };
    ($flag:ident $type:ty) => {
        paste! {
            [<get_ $flag>]()
        }
    };
}

macro_rules! create_getter_fn {
    ($flag:ident) => {
        paste! {
            #[doc = concat!(" Return true if ", stringify!($flag), " is enabled")]
            pub fn [<$flag _is_enabled>]() -> bool {
                FLAGS.lock().unwrap().$flag
            }
        }
    };
    ($flag:ident $type:ty) => {
        paste! {
            #[doc = concat!(" Return the flag value of ", stringify!($flag))]
            pub fn [<get_ $flag>]() -> $type {
                FLAGS.lock().unwrap().$flag
            }
        }
    };
}

macro_rules! create_setter_fn {
    ($flag:ident) => {
        paste! {
            #[doc = concat!(" Update value of ", stringify!($flag), " at runtime")]
            pub fn [<update_ $flag>](value: bool) {
                FLAGS.lock().unwrap().$flag = value;
            }
        }
    };
    ($flag:ident $type:ty) => {
        paste! {
            #[doc = concat!(" Update value of ", stringify!($flag), " at runtime")]
            pub fn [<update_ $flag>](value: $type) {
                FLAGS.lock().unwrap().$flag = value;
            }
        }
    };
}

macro_rules! init_flags {
    (
        name: $name:ident
        $($args:tt)*
    ) => {
        init_flags_struct! {
            name: $name
            $($args)*
        }

        init_flags_getters! {
            $($args)*
        }
    }
}

trait FlagHolder: Default {
    fn get_defaults_for_test() -> Self;
    fn parse(flags: Vec<String>) -> Self;
    fn dump(&self) -> BTreeMap<&'static str, String>;
    fn reconcile(self) -> Self;
}

macro_rules! init_flags_struct {
    (
     name: $name:ident
     flags: { $($flag:ident $(: $type:ty)? $(= $default:tt)?,)* }
     dynamic_flags: { $($dy_flag:ident $(: $dy_type:ty)? $(= $dy_default:tt)?,)* }
     extra_fields: { $($extra_field:ident : $extra_field_type:ty $(= $extra_default:tt)?,)* }
     extra_parsed_flags: { $($extra_flag:tt => $extra_flag_fn:ident(_, _ $(,$extra_args:tt)*),)*}
     dependencies: { $($parent:ident => $child:ident),* }) => {

        struct $name {
            $($flag : type_expand!($($type)?),)*
            $($dy_flag : type_expand!($($dy_type)?),)*
            $($extra_field : $extra_field_type,)*
        }

        impl Default for $name {
            fn default() -> Self {
                Self {
                    $($flag : default_value!($($type)? $(= $default)?),)*
                    $($dy_flag : default_value!($($dy_type)? $(= $dy_default)?),)*
                    $($extra_field : default_value!($extra_field_type $(= $extra_default)?),)*
                }
            }
        }

        impl FlagHolder for $name {
            fn get_defaults_for_test() -> Self {
                Self {
                    $($flag: test_value!($($type)?),)*
                    $($dy_flag: test_value!($($dy_type)?),)*
                    $($extra_field: test_value!($extra_field_type),)*
                }
            }

            fn dump(&self) -> BTreeMap<&'static str, String> {
                [
                    $((stringify!($flag), format!("{}", self.$flag)),)*
                    $((stringify!($dy_flag), format!("{}", self.$dy_flag)),)*
                    $((stringify!($extra_field), format!("{}", self.$extra_field)),)*
                ].into()
            }

            fn parse(flags: Vec<String>) -> Self {
                let mut init_flags = Self::default();

                for flag in flags {
                    let values: Vec<&str> = flag.split("=").collect();
                    if values.len() != 2 {
                        error!("Bad flag {}, must be in <FLAG>=<VALUE> format", flag);
                        continue;
                    }

                    match values[0] {
                        $(concat!("INIT_", stringify!($flag)) =>
                            init_flags.$flag = values[1].parse().unwrap_or_else(|e| {
                                error!("Parse failure on '{}': {}", flag, e);
                                default_value!($($type)? $(= $default)?)}),)*
                        $(concat!("INIT_", stringify!($dy_flag)) =>
                            init_flags.$dy_flag = values[1].parse().unwrap_or_else(|e| {
                                error!("Parse failure on '{}': {}", flag, e);
                                default_value!($($dy_type)? $(= $dy_default)?)}),)*
                        $($extra_flag => $extra_flag_fn(&mut init_flags, values $(, $extra_args)*),)*
                        _ => error!("Unsaved flag: {} = {}", values[0], values[1])
                    }
                }

                init_flags.reconcile()
            }

            #[allow(unused_mut)]
            fn reconcile(mut self) -> Self {
                loop {
                    // dependencies can be specified in any order
                    $(if self.$parent && !self.$child {
                        self.$child = true;
                        continue;
                    })*
                    break;
                }
                self
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
                write!(f, concat!(
                    concat!($(concat!(stringify!($flag), "={}")),*),
                    concat!($(concat!(stringify!($dy_flag), "={}")),*),
                    $(concat!(stringify!($extra_field), "={}")),*),
                    $(self.$flag),*,
                    $(self.$dy_flag),*,
                    $(self.$extra_field),*)
            }
        }

    }
}

macro_rules! init_flags_getters {
    (
     flags: { $($flag:ident $(: $type:ty)? $(= $default:tt)?,)* }
     dynamic_flags: { $($dy_flag:ident $(: $dy_type:ty)? $(= $dy_default:tt)?,)* }
     extra_fields: { $($extra_field:ident : $extra_field_type:ty $(= $extra_default:tt)?,)* }
     extra_parsed_flags: { $($extra_flag:tt => $extra_flag_fn:ident(_, _ $(,$extra_args:tt)*),)*}
     dependencies: { $($parent:ident => $child:ident),* }) => {

        $(create_getter_fn!($flag $($type)?);)*

        $(create_getter_fn!($dy_flag $($dy_type)?);)*
        $(create_setter_fn!($dy_flag $($dy_type)?);)*

        #[cfg(test)]
        mod tests_autogenerated {
            use super::*;
            $(paste! {
                #[test]
                pub fn [<test_get_ $flag>]() {
                    let _guard = tests::ASYNC_LOCK.lock().unwrap();
                    tests::test_load(vec![
                        &*format!(concat!(concat!("INIT_", stringify!($flag)), "={}"), test_value!($($type)?))
                    ]);
                    let get_value = call_getter_fn!($flag $($type)?);
                    drop(_guard); // Prevent poisonning other tests if a panic occurs
                    assert_eq!(get_value, test_value!($($type)?));
                }
            })*

            $(paste! {
                #[test]
                pub fn [<test_dynamic_get_ $dy_flag>]() {
                    let _guard = tests::ASYNC_LOCK.lock().unwrap();
                    tests::test_load(vec![
                        &*format!(concat!(concat!("INIT_", stringify!($dy_flag)), "={}"), test_value!($($dy_type)?))
                    ]);
                    let get_value = call_getter_fn!($dy_flag $($dy_type)?);
                    drop(_guard); // Prevent poisonning other tests if a panic occurs
                    assert_eq!(get_value, test_value!($($dy_type)?));
                }
            })*
        }
    }
}

#[derive(Default)]
struct ExplicitTagSettings {
    map: HashMap<String, i32>,
}

impl fmt::Display for ExplicitTagSettings {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self.map)
    }
}

struct LogLevel(i32);

impl TryFrom<&str> for LogLevel {
    type Error = &'static str;

    fn try_from(tag_value: &str) -> Result<Self, Self::Error> {
        match tag_value {
            "LOG_FATAL" => Ok(LogLevel(LOG_TAG_FATAL)),
            "LOG_ERROR" => Ok(LogLevel(LOG_TAG_ERROR)),
            "LOG_WARN" => Ok(LogLevel(LOG_TAG_WARN)),
            "LOG_NOTICE" => Ok(LogLevel(LOG_TAG_NOTICE)),
            "LOG_INFO" => Ok(LogLevel(LOG_TAG_INFO)),
            "LOG_DEBUG" => Ok(LogLevel(LOG_TAG_DEBUG)),
            "LOG_VERBOSE" => Ok(LogLevel(LOG_TAG_VERBOSE)),
            _ => Err("Invalid tag value"),
        }
    }
}

fn deprecated_set_debug_logging_enabled_for_all(flags: &mut InitFlags, values: Vec<&str>) {
    let truthy: bool = values[1].parse().unwrap_or(false);
    flags.default_log_level = if truthy { LOG_TAG_VERBOSE } else { LOG_TAG_INFO };

    // Leave a note that this flag is deprecated in the logs.
    log::error!(
        "DEPRECATED flag used: INIT_logging_debug_enabled_for_all. Use INIT_default_log_level_str=LOG_VERBOSE instead.",
    );
}

fn parse_log_level(flags: &mut InitFlags, values: Vec<&str>) {
    if let Ok(v) = LogLevel::try_from(values[1]) {
        flags.default_log_level = v.0;
    }
}

fn parse_logging_tag(flags: &mut InitFlags, values: Vec<&str>) {
    for tag in values[1].split(',') {
        let tagstr = tag.to_string();
        let pair = tagstr.split(':').collect::<Vec<&str>>();
        if pair.len() == 2 {
            if let Ok(v) = LogLevel::try_from(pair[1]) {
                flags.logging_explicit_tag_settings.map.insert(pair[0].into(), v.0);
            }
        }
    }
}

fn parse_debug_logging_tag(flags: &mut InitFlags, values: Vec<&str>, enabled: bool) {
    let log_level: i32 = if enabled { LOG_TAG_VERBOSE } else { LOG_TAG_INFO };

    for tag in values[1].split(',') {
        flags.logging_explicit_tag_settings.map.insert(tag.to_string(), log_level);
    }
}

fn parse_hci_adapter(flags: &mut InitFlags, values: Vec<&str>) {
    flags.hci_adapter = values[1].parse().unwrap_or(0);
}

/// Returns the log level for given flag.
pub fn get_log_level_for_tag(tag: &str) -> i32 {
    let guard = FLAGS.lock().unwrap();
    *guard.logging_explicit_tag_settings.map.get(tag).unwrap_or(&guard.default_log_level)
}

/// Sets all bool flags to true
/// Set all other flags and extra fields to their default type value
pub fn set_all_for_testing() {
    *FLAGS.lock().unwrap() = InitFlags::get_defaults_for_test();
}

// Keep these values in sync with the values in gd/os/log_tags.h
// They are used to control the log level for each tag.

/// Fatal log level.
pub const LOG_TAG_FATAL: i32 = 0;
/// Error log level.
pub const LOG_TAG_ERROR: i32 = 1;
/// Warning log level.
pub const LOG_TAG_WARN: i32 = 2;
/// Notice log level.
pub const LOG_TAG_NOTICE: i32 = 3;
/// Info log level. This is usually the default log level on most systems.
pub const LOG_TAG_INFO: i32 = 4;
/// Debug log level.
pub const LOG_TAG_DEBUG: i32 = 5;
/// Verbose log level.
pub const LOG_TAG_VERBOSE: i32 = 6;

init_flags!(
    name: InitFlags
    flags: {
        asha_packet_drop_frequency_threshold: i32 = 60,
        asha_phy_update_retry_limit: i32 = 5,
        always_send_services_if_gatt_disc_done = true,
        always_use_private_gatt_for_debugging,
        asynchronously_start_l2cap_coc = true,
        btaa_hci = true,
        bta_dm_clear_conn_id_on_client_close = true,
        btm_dm_flush_discovery_queue_on_search_cancel,
        classic_discovery_only,
        clear_hidd_interrupt_cid_on_disconnect = true,
        delay_hidh_cleanup_until_hidh_ready_start = true,
        device_iot_config_logging,
        dynamic_avrcp_version_enhancement = true,
        finite_att_timeout = true,
        gatt_robust_caching_client = true,
        gatt_robust_caching_server,
        gd_core,
        gd_hal_snoop_logger_socket = true,
        gd_hal_snoop_logger_filtering = true,
        gd_l2cap,
        gd_link_policy,
        gd_remote_name_request,
        gd_rust,
        hci_adapter: i32,
        hfp_dynamic_version = true,
        irk_rotation,
        leaudio_targeted_announcement_reconnection_mode = true,
        pass_phy_update_callback = true,
        pbap_pse_dynamic_version_upgrade = false,
        periodic_advertising_adi = true,
        private_gatt = true,
        queue_l2cap_coc_while_encrypting = true,
        read_encryption_key_size = true,
        redact_log = true,
        rust_event_loop = true,
        sco_codec_select_lc3,
        sco_codec_timeout_clear,
        sdp_serialization = true,
        sdp_skip_rnr_if_known = true,
        bluetooth_quality_report_callback = true,
        set_min_encryption = true,
        subrating = true,
        trigger_advertising_callbacks_on_first_resume_after_pause = true,
        use_unified_connection_manager,
        sdp_return_classic_services_when_le_discovery_fails = true,
    }
    // dynamic flags can be updated at runtime and should be accessed directly
    // to check.
    dynamic_flags: {
        default_log_level : i32 = LOG_TAG_INFO,
    }
    // extra_fields are not a 1 to 1 match with "INIT_*" flags
    extra_fields: {
        logging_explicit_tag_settings: ExplicitTagSettings,
    }
    extra_parsed_flags: {
        "INIT_default_log_level_str" => parse_log_level(_, _),
        "INIT_log_level_for_tags" => parse_logging_tag(_, _),
        "INIT_logging_debug_enabled_for_all" => deprecated_set_debug_logging_enabled_for_all(_, _),
        "INIT_logging_debug_enabled_for_tags" => parse_debug_logging_tag(_, _, true),
        "INIT_logging_debug_disabled_for_tags" => parse_debug_logging_tag(_, _, false),
        "--hci" => parse_hci_adapter(_, _),
    }
    dependencies: {
        always_use_private_gatt_for_debugging => private_gatt,
        private_gatt => rust_event_loop
    }
);

lazy_static! {
    /// Store some flag values
    static ref FLAGS: Mutex<InitFlags> = Mutex::new(InitFlags::default());
    /// Store the uid of bluetooth
    pub static ref AID_BLUETOOTH: Mutex<u32> = Mutex::new(1002);
    /// Store the prefix for file system
    pub static ref MISC: Mutex<String> = Mutex::new("/data/misc/".to_string());
}

/// Loads the flag values from the passed-in vector of string values
pub fn load(raw_flags: Vec<String>) {
    crate::init_logging();

    let flags = InitFlags::parse(raw_flags);
    info!("Flags loaded: {}", flags);
    *FLAGS.lock().unwrap() = flags;

    // re-init to respect log levels set by flags
    crate::init_logging();
}

/// Dumps all flag K-V pairs, storing values as strings
pub fn dump() -> BTreeMap<&'static str, String> {
    FLAGS.lock().unwrap().dump()
}

#[cfg(test)]
mod tests {
    use super::*;
    lazy_static! {
        /// do not run concurrent tests as they all use the same global init_flag struct and
        /// accessor
        pub(super) static ref ASYNC_LOCK: Mutex<bool> = Mutex::new(false);
    }

    pub(super) fn test_load(raw_flags: Vec<&str>) {
        let raw_flags = raw_flags.into_iter().map(|x| x.to_string()).collect();
        load(raw_flags);
    }

    #[test]
    fn simple_flag() {
        let _guard = ASYNC_LOCK.lock().unwrap();
        test_load(vec![
            "INIT_btaa_hci=false", //override a default flag
            "INIT_gatt_robust_caching_server=true",
        ]);
        assert!(!btaa_hci_is_enabled());
        assert!(gatt_robust_caching_server_is_enabled());
    }
    #[test]
    fn parsing_failure() {
        let _guard = ASYNC_LOCK.lock().unwrap();
        test_load(vec![
            "foo=bar=?",                                // vec length
            "foo=bar",                                  // flag not save
            "INIT_btaa_hci=not_false",                  // parse error but has default value
            "INIT_gatt_robust_caching_server=not_true", // parse error
        ]);
        assert!(btaa_hci_is_enabled());
        assert!(!gatt_robust_caching_server_is_enabled());
    }
    #[test]
    fn int_flag() {
        let _guard = ASYNC_LOCK.lock().unwrap();
        test_load(vec!["--hci=2"]);
        assert_eq!(get_hci_adapter(), 2);
    }
    #[test]
    fn explicit_flag() {
        let _guard = ASYNC_LOCK.lock().unwrap();
        test_load(vec![
            "INIT_default_log_level_str=LOG_VERBOSE",
            "INIT_logging_debug_enabled_for_tags=foo,bar",
            "INIT_logging_debug_disabled_for_tags=foo,bar2,fizz",
            "INIT_logging_debug_enabled_for_tags=bar2",
            "INIT_log_level_for_tags=fizz:LOG_WARN,buzz:LOG_NOTICE",
        ]);

        assert!(get_log_level_for_tag("foo") == LOG_TAG_INFO);
        assert!(get_log_level_for_tag("bar") == LOG_TAG_VERBOSE);
        assert!(get_log_level_for_tag("bar2") == LOG_TAG_VERBOSE);
        assert!(get_log_level_for_tag("unknown_flag") == LOG_TAG_VERBOSE);
        assert!(get_default_log_level() == LOG_TAG_VERBOSE);
        FLAGS.lock().unwrap().default_log_level = LOG_TAG_INFO;
        assert!(get_log_level_for_tag("foo") == LOG_TAG_INFO);
        assert!(get_log_level_for_tag("bar") == LOG_TAG_VERBOSE);
        assert!(get_log_level_for_tag("bar2") == LOG_TAG_VERBOSE);
        assert!(get_log_level_for_tag("unknown_flag") == LOG_TAG_INFO);
        assert!(get_default_log_level() == LOG_TAG_INFO);
    }
    #[test]
    fn test_redact_logging() {
        let _guard = ASYNC_LOCK.lock().unwrap();
        assert!(redact_log_is_enabled()); // default is true
        test_load(vec!["INIT_redact_log=false"]);
        assert!(!redact_log_is_enabled()); // turned off
        test_load(vec!["INIT_redact_log=foo"]);
        assert!(redact_log_is_enabled()); // invalid value, interpreted as default, true
        test_load(vec!["INIT_redact_log=true"]);
        assert!(redact_log_is_enabled()); // turned on
    }
    #[test]
    fn test_runtime_update() {
        let _guard = ASYNC_LOCK.lock().unwrap();
        test_load(vec!["INIT_btaa_hci=true", "INIT_default_log_level_str=LOG_WARN"]);
        assert!(btaa_hci_is_enabled());
        assert!(get_default_log_level() == LOG_TAG_WARN);

        update_default_log_level(LOG_TAG_DEBUG);
        assert!(get_default_log_level() == LOG_TAG_DEBUG);
        update_default_log_level(LOG_TAG_ERROR);
        assert!(get_default_log_level() == LOG_TAG_ERROR);
    }
    #[test]
    fn test_default_log_level() {
        // Default log level can be provided via int value or string.
        // The string version is just for ease-of-use.
        let _guard = ASYNC_LOCK.lock().unwrap();
        test_load(vec!["INIT_default_log_level=1"]);
        assert!(get_default_log_level() == LOG_TAG_ERROR);
        test_load(vec!["INIT_default_log_level_str=LOG_VERBOSE"]);
        assert!(get_default_log_level() == LOG_TAG_VERBOSE);
        test_load(vec!["INIT_default_log_level_str=LOG_VERBOSE", "INIT_default_log_level=0"]);
        assert!(get_default_log_level() == LOG_TAG_FATAL);
    }
    #[test]
    fn test_deprecated_logging_flag() {
        let _guard = ASYNC_LOCK.lock().unwrap();
        test_load(vec!["INIT_default_log_level_str=1", "INIT_logging_debug_enabled_for_all=true"]);
        assert!(get_default_log_level() == LOG_TAG_VERBOSE);
        test_load(vec!["INIT_logging_debug_enabled_for_all=false"]);
        assert!(get_default_log_level() == LOG_TAG_INFO);
    }

    init_flags_struct!(
        name: InitFlagsForTest
        flags: {
            cat,
        }
        dynamic_flags: {
            dog: i32 = 8,
        }
        extra_fields: {
            elephant: String,
        }
        extra_parsed_flags: {}
        dependencies: {}
    );

    #[test]
    fn test_dumpsys() {
        let flags = InitFlagsForTest { dog: 3, elephant: "Go bears!".into(), ..Default::default() };

        let out = flags.dump();

        assert_eq!(out.len(), 3);
        assert_eq!(out["cat"], "false");
        assert_eq!(out["dog"], "3");
        assert_eq!(out["elephant"], "Go bears!");
    }
}
