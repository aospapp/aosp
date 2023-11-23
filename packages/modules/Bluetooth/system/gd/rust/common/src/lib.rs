//! Bluetooth common library

use init_flags::{
    get_log_level_for_tag, LOG_TAG_DEBUG, LOG_TAG_ERROR, LOG_TAG_FATAL, LOG_TAG_INFO,
    LOG_TAG_NOTICE, LOG_TAG_VERBOSE, LOG_TAG_WARN,
};

/// Provides waking timer abstractions
pub mod time;

/// Provides parameters
pub mod parameter_provider;

pub mod bridge;

#[macro_use]
mod ready;

#[cfg(test)]
#[macro_use]
mod asserts;

/// Provides runtime configured-at-startup flags
pub mod init_flags;

/// Provides runtime configured system properties. Stubbed for non-Android.
pub mod sys_prop;

fn get_log_level() -> log::Level {
    match get_log_level_for_tag("bluetooth_core") {
        LOG_TAG_FATAL => log::Level::Error,
        LOG_TAG_ERROR => log::Level::Error,
        LOG_TAG_WARN => log::Level::Warn,
        LOG_TAG_NOTICE => log::Level::Info,
        LOG_TAG_INFO => log::Level::Info,
        LOG_TAG_DEBUG => log::Level::Debug,
        LOG_TAG_VERBOSE => log::Level::Trace,
        _ => log::Level::Info, // default level
    }
}

/// Inits logging for Android
#[cfg(target_os = "android")]
pub fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default().with_tag("bt").with_min_level(get_log_level()),
    );
    log::set_max_level(get_log_level().to_level_filter())
}

/// Inits logging for host
#[cfg(not(target_os = "android"))]
pub fn init_logging() {
    env_logger::Builder::new()
        .filter(None, get_log_level().to_level_filter())
        .parse_default_env()
        .try_init()
        .ok();
    log::set_max_level(get_log_level().to_level_filter())
}

/// Indicates the object can be converted to a GRPC service
pub trait GrpcFacade {
    /// Convert the object into the service
    fn into_grpc(self) -> grpcio::Service;
}

/// Useful for distinguishing between BT classic & LE in functions that support both
#[derive(Debug, Clone, Copy)]
pub enum Bluetooth {
    /// Classic BT we all know and love, started in the 90s.
    Classic,
    /// Bluetooth low energy from the 2010s. Also known as BLE, BTLE, etc.
    Le,
}
