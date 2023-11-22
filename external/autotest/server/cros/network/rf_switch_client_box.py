# Copyright (c) 2017 The Chromium OS Authors. All rights reserved.
# # Use of this source code is governed by a BSD-style license that can be
# # found in the LICENSE file.

import itertools

from autotest_lib.server import frontend
from autotest_lib.server import site_utils


CLIENT_BOX_STR = 'client_box_'
RF_SWITCH_STR = 'rf_switch_'
RF_SWITCH_DUT = 'rf_switch_dut'
RF_SWITCH_CLIENT = 'rf_switch_client'


class ClientBox(object):
    """Class to manage devices in the Client Box."""


    def __init__(self, client_box_host):
        """Constructor for the ClientBox.

        @param client_box_host: Client Box AFE Host."""
        self.client_box_host = client_box_host
        self.labels = self.client_box_host.labels
        for s in self.labels:
            if s.startswith(CLIENT_BOX_STR):
                self.client_box_label = s
        self.devices = self._get_devices()


    def _get_devices(self):
        """Return all devices in the Client Box.

        @returns a list of autotest_lib.server.frontend.Host objects."""
        rf_switch_label = ''
        for label in self.labels:
            if label.startswith(RF_SWITCH_STR) and (
                    label is not RF_SWITCH_CLIENT):
                rf_switch_label = label
        afe = frontend.AFE(
                debug=True, server=site_utils.get_global_afe_hostname())
        hosts = afe.get_hosts(label=self.client_box_label)
        devices = []
        for host in hosts:
            labels = list(itertools.ifilter(
                    lambda x: x in host.labels, [RF_SWITCH_DUT, rf_switch_label]))
            # If host has both labels, then add to the devices list.
            if len(labels) == 2:
                devices.append(host)
        return devices


    def get_devices(self):
        """Return all devices in the Client Box.

        @returns a list of autotest_lib.server.frontend.Host objects."""
        return self.devices
