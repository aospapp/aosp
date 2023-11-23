//
//  Copyright 2023 Google, Inc.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

//! # Time Display class

use chrono::{DateTime, Datelike, NaiveDateTime, Timelike, Utc};

/// A simple class that contains information required to display time
pub struct TimeDisplay {
    /// seconds since std::time::UNIX_EPOCH
    secs: i64,
    /// nano sub seconds since std::time::UNIX_EPOCH
    nsecs: u32,
}

impl TimeDisplay {
    /// Creates a new TimeDisplay with given secs and nsecs
    ///
    /// # Arguments
    ///
    /// * `secs` - seconds since std::time::UNIX_EPOCH
    /// * `nsecs` - nano sub seconds since std::time::UNIX_EPOCH
    pub fn new(secs: i64, nsecs: u32) -> TimeDisplay {
        TimeDisplay { secs, nsecs }
    }

    /// Displayes time in UTC with a format YYYY-MM-DD-HH:MM:SS
    ///
    /// # Returns
    ///
    /// `Ok(String)` if the display was successful, `Error` otherwise.
    pub fn utc_display(&self) -> String {
        if let Some(datetime) = NaiveDateTime::from_timestamp_opt(self.secs, self.nsecs) {
            let current_datetime = DateTime::<Utc>::from_utc(datetime, Utc);
            return format!(
                "{}-{:02}-{:02}-{:02}-{:02}-{:02}",
                current_datetime.year(),
                current_datetime.month(),
                current_datetime.day(),
                current_datetime.hour(),
                current_datetime.minute(),
                current_datetime.second()
            );
        }
        "INVALID-TIMESTAMP".to_string()
    }
}

#[cfg(test)]
mod tests {

    use super::TimeDisplay;

    #[test]
    fn test_utc_display_ok() {
        let epoch_time = TimeDisplay::new(0, 0);
        let utc_epoch = epoch_time.utc_display();
        assert_eq!(utc_epoch, "1970-01-01-00-00-00");
        let twok_time = TimeDisplay::new(946684900, 0);
        let utc_twok = twok_time.utc_display();
        assert_eq!(utc_twok, "2000-01-01-00-01-40");
    }

    #[test]
    fn test_utc_display_err() {
        let max_seconds = TimeDisplay::new(i64::MAX, 0);
        assert_eq!("INVALID-TIMESTAMP", max_seconds.utc_display());
        let max_nanos = TimeDisplay::new(0, 2_000_000_000);
        assert_eq!("INVALID-TIMESTAMP", max_nanos.utc_display());
    }
}
