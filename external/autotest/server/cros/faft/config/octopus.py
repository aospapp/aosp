# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""FAFT config setting overrides for Octopus."""

class Values(object):
    """FAFT config values for Octopus."""
    chrome_ec = True
    ec_boot_to_console = 0.3
    ec_capability = ['battery', 'charging', 'keyboard', 'lid', 'x86',
                     'usb', 'smart_usb_charge']
    firmware_screen = 25 # Time from deasserting cold_reset to firmware_screen being shown
    usb_plug = 45
    spi_voltage = 'pp1800'
    custom_usb_enable_names = ['EN_USB_A0_5V', 'EN_USB_A1_5V']
    smm_store = False
