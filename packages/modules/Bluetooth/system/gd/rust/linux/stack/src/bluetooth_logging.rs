use bt_common::init_flags;
use log::LevelFilter;
use syslog::{BasicLogger, Facility, Formatter3164};

/// API to modify log levels.
pub trait IBluetoothLogging {
    /// Check whether debug logging is enabled.
    fn is_debug_enabled(&self) -> bool;

    /// Change whether debug logging is enabled.
    fn set_debug_logging(&mut self, enabled: bool);
}

/// Logging related implementation.
pub struct BluetoothLogging {
    is_debug: bool,
}

impl BluetoothLogging {
    pub fn new(is_debug: bool, log_output: &str) -> Self {
        let level = if is_debug { LevelFilter::Debug } else { LevelFilter::Info };

        if log_output == "stderr" {
            env_logger::Builder::new().filter(None, level).init();
        } else {
            let formatter = Formatter3164 {
                facility: Facility::LOG_USER,
                hostname: None,
                process: "btadapterd".into(),
                pid: 0,
            };

            let logger = syslog::unix(formatter).expect("could not connect to syslog");
            let _ = log::set_boxed_logger(Box::new(BasicLogger::new(logger)))
                .map(|()| log::set_max_level(level));
        }

        Self { is_debug }
    }
}

impl IBluetoothLogging for BluetoothLogging {
    fn is_debug_enabled(&self) -> bool {
        self.is_debug
    }

    fn set_debug_logging(&mut self, enabled: bool) {
        self.is_debug = enabled;

        // Update log level in Linux stack.
        let level = if self.is_debug { LevelFilter::Debug } else { LevelFilter::Info };
        log::set_max_level(level);

        // Update log level in libbluetooth.
        let level =
            if self.is_debug { init_flags::LOG_TAG_DEBUG } else { init_flags::LOG_TAG_INFO };
        init_flags::update_default_log_level(level);

        // Mark the start of debug logging with a debug print.
        if self.is_debug {
            log::debug!("Debug logging successfully enabled!");
        }

        log::info!("Setting debug logging to {}", self.is_debug);
    }
}
