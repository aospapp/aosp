# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""FAFT config setting overrides for Grunt."""

class Values(object):
    """FAFT config values for Grunt."""
    chrome_ec = True
    ec_capability = ['battery', 'charging', 'keyboard', 'lid', 'x86' ]
    servo_prog_state_delay = 10
    smm_store = False
