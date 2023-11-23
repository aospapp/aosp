# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""FAFT configuration overrides for Cheza."""


class Values(object):
    """FAFT config values for Cheza."""
    mode_switcher_type = 'tablet_detachable_switcher'
    fw_bypasser_type = 'tablet_detachable_bypasser'

    has_lid = True
    has_keyboard = False
    chrome_ec = True
    ec_capability = ['arm', 'battery', 'charging', 'lid']
