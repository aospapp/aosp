# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os
import time

from autotest_lib.client.common_lib import error
from autotest_lib.server import test
import parse

GUADO_GPIO = 218  # For Front-Left USB port
POWER_RECYCLE_WAIT_TIME = 1  # sec


class enterprise_CFM_HuddlyUpdater(test.test):
    """Tests the firmware updatability of HuddlyGo camera.

    An event to trigger the firmware update is to power recycle of a USB port
    which the HuddlyGo camera is attached to. The power recycle emulates
    the power recycle of the ChromeBox or a reconnection of the peripheral
    to the ChromeBox.

    The test scenario involves the power recycling of a specific USB port
    of the Guado ChromeBox: Front-left one. This imposes a restriction in the
    testbed setup. This limitation is to be alleviated after the development
    of full-fledged usb power recycle code. TODO(frankhu).
    """

    version = 1
    _failed_test_list = []

    UPDATER_WAIT_TIME = 60  # sec

    FIRMWARE_PKG_ORG = 'huddly'
    FIRMWARE_PKG_TO_TEST = 'huddly052'
    FIRMWARE_PKG_BACKUP = 'huddly.backup'

    DUT_FIRMWARE_BASE = '/lib/firmware/'
    DUT_FIRMWARE_SRC = os.path.join(DUT_FIRMWARE_BASE, FIRMWARE_PKG_ORG)
    DUT_FIRMWARE_SRC_BACKUP = os.path.join(DUT_FIRMWARE_BASE,
                                           FIRMWARE_PKG_BACKUP)
    DUT_FIRMWARE_SRC_TEST = os.path.join(DUT_FIRMWARE_BASE,
                                         FIRMWARE_PKG_TO_TEST)

    def initialize(self):
        # Placeholder.
        pass

    def cleanup(self):
        """Bring the originally bundled firmware package back."""
        cmd = 'rm -rf {}'.format(self.DUT_FIRMWARE_SRC)
        self._shcmd(cmd)

        cmd = 'mv {} {} && rm -rf {}'.format(self.DUT_FIRMWARE_SRC_BACKUP,
                                             self.DUT_FIRMWARE_SRC,
                                             self.DUT_FIRMWARE_SRC_TEST)
        self._shcmd(cmd)

    def _shcmd(self, cmd):
        """A simple wrapper for remote shell command execution."""
        logging.info('CMD: [%s]', cmd)
        return self._client.run(cmd)

    def copy_firmware(self):
        """Copy test firmware package from server to the DUT."""
        current_dir = os.path.dirname(os.path.realpath(__file__))
        src_firmware_path = os.path.join(current_dir, self.FIRMWARE_PKG_TO_TEST)
        dst_firmware_path = self.DUT_FIRMWARE_BASE

        msg = 'copy firmware from {} to {}'.format(src_firmware_path,
                                                   dst_firmware_path)
        logging.info(msg)
        self._client.send_file(
            src_firmware_path, dst_firmware_path, delete_dest=True)

    def update_firmware(self, firmware_pkg):
        """Update the peripheral's firmware with the specified package.

        @param firmware_pkg: A string of package name specified by the leaf
                directory name in /lib/firmware/. See class constants
                DUT_FIRMWARE_SRC*.
        """
        # Set up the firmware package to test with
        firmware_path = os.path.join(self.DUT_FIRMWARE_BASE, firmware_pkg)
        cmd = 'ln -sfn {} {}'.format(firmware_path, self.DUT_FIRMWARE_SRC)
        self._shcmd(cmd)

        ver_dic = self.get_fw_vers()
        had = ver_dic.get('peripheral', {}).get('app', '')
        want = ver_dic.get('package', {}).get('app', '')

        msg = 'Update plan: from {} to {} with package: {}'.format(
            had, want, firmware_pkg)
        logging.info(msg)

        logging.info('Recycle the power to the USB port '
                     'to which HuddlyGo is attached.')
        self.usb_power_recycle()
        time.sleep(self.UPDATER_WAIT_TIME)

        got = self.get_fw_vers().get('peripheral', {}).get('app', '')

        msg = 'Update result: had {} want {} got {}'.format(
            had, want, got)
        logging.info(msg)

        if want != got:
            self._failed_test_list.append(
                'update_firmware({})'.format(firmware_pkg))

    def run_once(self, host=None):
        """Update two times. First with test package, second with the original.

        Test scenario:
          1. Copy test firmware from the server to the DUT.
          2. Update with the test package. Wait about 50 sec till completion.
             Confirm if the peripheral is updated with the test version.
          3. Update with the original package. Wait about 50 sec.
             Confirm if the peripheral is updated with the original version.
        """
        self._client = host

        try:
            # TODO(porce): How can make initialize() access host?
            cmd = 'mv {} {}'.format(self.DUT_FIRMWARE_SRC,
                                    self.DUT_FIRMWARE_SRC_BACKUP)
            self._shcmd(cmd)

            self.copy_firmware()
            self.update_firmware(self.FIRMWARE_PKG_TO_TEST)
            self.update_firmware(self.FIRMWARE_PKG_BACKUP)

            if self._failed_test_list:
              msg = 'Test failed in {}'.format(
                  ', '.join(map(str, self._failed_test_list)))
              raise error.TestFail(msg)
        except error.AutoservRunError:
            self.cleanup()

    def get_fw_vers(self):
        """Queries the firmware versions.

        Utilizes the output of the command 'huddly-updater --info'.
        It queries and parses the firmware versions of app and bootloader of
        firmware package and the peripheral's running firmwares, respectively.

        @returns a dictionary hierachically storing the firmware versions.
        """

        # TODO(porce): The updater's output is to stdout, but Auto test
        # command output comes to stderr. Investigate.
        cmd = 'huddly-updater --info --log_to=stdout'
        result = self._shcmd(cmd).stderr
        ver_dic = parse.parse_fw_vers(result)
        return ver_dic

    def usb_power_recycle(self):
        """Recycle the power to a USB port.

        # TODO(frankhu): This code supports Guado, at a specific test
        # configuration. Develop an independent tool to perform this task
        # with minimal dependency.
        """

        try:
            # Ignorant handling of GPIO export.
            cmd = 'echo {} > /sys/class/gpio/export'.format(GUADO_GPIO)
            self._shcmd(cmd)
        except error.AutoservRunError:
            pass

        cmd = 'echo out > /sys/class/gpio/gpio{}/direction'.format(GUADO_GPIO)
        self._shcmd(cmd)
        cmd = 'echo 0 > /sys/class/gpio/gpio{}/value'.format(GUADO_GPIO)
        self._shcmd(cmd)

        # Wait for 1 second to avoid too fast removal and reconnection.
        time.sleep(POWER_RECYCLE_WAIT_TIME)
        cmd = 'echo 1 > /sys/class/gpio/gpio{}/value'.format(GUADO_GPIO)
        self._shcmd(cmd)
