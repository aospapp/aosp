# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Helper class for power autotests requiring telemetry devices."""

import logging
import time

CUSTOM_START = 'PowerTelemetryLogger custom start.'
CUSTOM_END = 'PowerTelemetryLogger custom end.'

def log_event_ts(message=None, timestamp=None, offset=0):
    """Log the event and timestamp for parsing later.

    @param message: description of the event.
    @param timestamp: timestamp to for the event, if not provided, default to
           current time. Local seconds since epoch.
    @param offset: offset in seconds from the provided timestamp, or offset from
           current time if timestamp is not provided. Can be positive or
           negative.
    """
    if not message:
        return
    if timestamp:
        ts = timestamp + offset
    else:
        ts = time.time() + offset
    logging.debug("%s %s", message, ts)

def start_measurement(timestamp=None, offset=0):
    """Mark the start of power telemetry measurement.

    Optional. Use only once in the client side test that is wrapped in
    power_MeasurementWrapper to help pinpoint exactly where power telemetry
    data should start. PowerTelemetryLogger will trim off excess data before
    this point. If not used, power telemetry data will start right before the
    client side test.
    @param timestamp: timestamp for the start of measurement, if not provided,
           default to current time. Local seconds since epoch.
    @param offset: offset in seconds from the provided timestamp, or offset from
           current time if timestamp is not provided. Can be positive or
           negative.
    """
    log_event_ts(CUSTOM_START, timestamp, offset)

def end_measurement(timestamp=None, offset=0):
    """Mark the end of power telemetry measurement.

    Optional. Use only once in the client side test that is wrapped in
    power_MeasurementWrapper to help pinpoint exactly where power telemetry
    data should end. PowerTelemetryLogger will trim off excess data after
    this point. If not used, power telemetry data will end right after the
    client side test.
    @param timestamp: timestamp for the end of measurement, if not provided,
           default to current time. Local seconds since epoch.
    @param offset: offset in seconds from the provided timestamp, or offset from
           current time if timestamp is not provided. Can be positive or
           negative.
    """
    log_event_ts(CUSTOM_END, timestamp, offset)
