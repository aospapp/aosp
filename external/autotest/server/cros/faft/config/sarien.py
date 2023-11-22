# Copyright 2019 Google LLC
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""FAFT configuration overrides for Sarien."""

class Values(object):
    """FAFT config values for Sarien."""
    firmware_screen = 15
    delay_reboot_to_ping = 40
    hold_pwr_button_poweron = 1.2
    has_lid = True
    lid_wake_from_power_off = False
    spi_voltage = 'pp3300'
    # Not a Chrome EC, do not expect keyboard via EC
    chrome_ec = False
    ec_capability = []
    has_keyboard = False
    # Temporary until switch to power button
    rec_button_dev_switch = True
    smm_store = False
    # The EC image is stored in the AP SPI chip, so flashrom -p ec won't work.
    ap_access_ec_flash = False
    # Depthcharge USB stack can drop keys that come in too fast and get stuck
    # exiting developer mode if the delay for confirmation screen is too short.
    confirm_screen = 11
