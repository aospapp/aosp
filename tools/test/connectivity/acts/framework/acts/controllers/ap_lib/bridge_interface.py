#!/usr/bin/env python3.4
#
#   Copyright 2017 - Google, Inc.
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import logging
import time
from acts.libs.proc import job

# TODO(@qijiang): will change to brctl when it's built in image
_BRCTL = '/home/root/bridge-utils/sbin/brctl'
BRIDGE_NAME = 'br0'
CREATE_BRIDGE = '%s addbr %s' % (_BRCTL, BRIDGE_NAME)
DELETE_BRIDGE = '%s delbr %s' % (_BRCTL, BRIDGE_NAME)
BRING_DOWN_BRIDGE = 'ifconfig %s down' % BRIDGE_NAME


class BridgeInterfaceConfigs(object):
    """Configs needed for creating bridge interface between LAN and WLAN.

    """

    def __init__(self, iface_wlan, iface_lan, bridge_ip):
        """Set bridge interface configs based on the channel info.

        Args:
            iface_wlan: the wlan interface as part of the bridge
            iface_lan: the ethernet LAN interface as part of the bridge
            bridge_ip: the ip address assigned to the bridge interface
        """
        self.iface_wlan = iface_wlan
        self.iface_lan = iface_lan
        self.bridge_ip = bridge_ip


class BridgeInterface(object):
    """Class object for bridge interface betwen WLAN and LAN

    """

    def __init__(self, ssh_session):
        """Initialize the BridgeInterface class.

        Bridge interface will be added between ethernet LAN port and WLAN port.
        Args:
            ssh_session: ssh session to the AP
        """
        self.ssh = ssh_session
        self.log = logging.getLogger()

    def startup(self, brconfigs):
        """Start up the bridge interface.

        Args:
            brconfigs: the bridge interface config, type BridgeInterfaceConfigs
        """

        self.log.info('Create bridge interface between LAN and WLAN')
        # Create the bridge
        try:
            self.ssh.run(CREATE_BRIDGE)
        except job.Error:
            self.log.warning(
                'Bridge interface {} already exists, no action needed'.format(
                    BRIDGE_NAME))

        # Enable 4addr mode on for the wlan interface
        ENABLE_4ADDR = 'iw dev %s set 4addr on' % (brconfigs.iface_wlan)
        try:
            self.ssh.run(ENABLE_4ADDR)
        except job.Error:
            self.log.warning(
                '4addr is already enabled on {}'.format(brconfigs.iface_wlan))

        # Add both LAN and WLAN interfaces to the bridge interface
        for interface in [brconfigs.iface_lan, brconfigs.iface_wlan]:
            ADD_INTERFACE = '%s addif %s %s' % (_BRCTL, BRIDGE_NAME, interface)
            try:
                self.ssh.run(ADD_INTERFACE)
            except job.Error:
                self.log.warning('{} has alrady been added to {}'.format(
                    interface, BRIDGE_NAME))
        time.sleep(5)

        # Set IP address on the bridge interface to bring it up
        SET_BRIDGE_IP = 'ifconfig %s %s' % (BRIDGE_NAME, brconfigs.bridge_ip)
        self.ssh.run(SET_BRIDGE_IP)
        time.sleep(2)

        # Bridge interface is up
        self.log.info('Bridge interface is up and running')

    def teardown(self, brconfigs):
        """Tear down the bridge interface.

        Args:
            brconfigs: the bridge interface config, type BridgeInterfaceConfigs
        """
        self.log.info('Bringing down the bridge interface')
        # Delete the bridge interface
        self.ssh.run(BRING_DOWN_BRIDGE)
        time.sleep(1)
        self.ssh.run(DELETE_BRIDGE)

        # Bring down wlan interface and disable 4addr mode
        BRING_DOWN_WLAN = 'ifconfig %s down' % brconfigs.iface_wlan
        self.ssh.run(BRING_DOWN_WLAN)
        time.sleep(2)
        DISABLE_4ADDR = 'iw dev %s set 4addr off' % (brconfigs.iface_wlan)
        self.ssh.run(DISABLE_4ADDR)
        time.sleep(1)
        self.log.info('Bridge interface is down')
