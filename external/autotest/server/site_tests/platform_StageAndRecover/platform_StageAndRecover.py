# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import time

from autotest_lib.server import test
from autotest_lib.client.common_lib import error

class platform_StageAndRecover(test.test):
    """Installs the same version recovery image onto a servo-connected DUT."""
    version = 1

    _INSTALL_DELAY_TIMEOUT = 540
    _TEST_IMAGE_BOOT_DELAY = 120

    def cleanup(self):
        """ Clean up by switching servo usb towards servo host. """
        self.host.servo.switch_usbkey('host')


    def set_servo_usb_reimage(self):
        """ Turns USB_HUB_2 servo port to DUT, and disconnects servo from DUT.
        Avoiding peripherals plugged at this servo port.
        """
        self.host.servo.set('usb_mux_sel3', 'dut_sees_usbkey')
        self.host.servo.set('dut_hub1_rst1','on')

        # Switch usb_mux_sel1 to enumerate as /dev/sda
        self.host.servo.set('usb_mux_sel1', 'dut_sees_usbkey')
        self.host.servo.set('usb_mux_sel1', 'servo_sees_usbkey')


    def set_servo_usb_recover(self):
        """ Turns USB_HUB_2 servo port to servo, and connects servo to DUT.
        Avoiding peripherals plugged at this servo port.
        """
        self.host.servo.set('usb_mux_sel3', 'servo_sees_usbkey')
        self.host.servo.set('dut_hub1_rst1','off')


    def stage_copy_recover_with(self, artifact):
        """ Stage image, copy image to servo usb, and 'rec' boot the device.

        @param artifact: image type - recovery_image or test_image
        """
        # Stage the image on dev server
        image_path = self.host.stage_image_for_servo(
            self.release_builder_path,
            artifact=artifact)
        logging.info('%s staged at %s' % (artifact, image_path))

        # Make servo sees only DUT_HUB1
        self.set_servo_usb_reimage()
        # Reimage servo USB
        self.host.servo.image_to_servo_usb(image_path,
                                           make_image_noninteractive=True)
        self.set_servo_usb_recover()

        # Boot DUT in recovery mode for image to install
        self.host.servo.boot_in_recovery_mode()


    def wait_for_dut_ping_after(self, process, timeout):
        """ Wait for DUT after reimaging or rebooting.

        @param process: process to check timeout for
        @param timeout: timeout to wait for DUT to answer to ping_wait_up

        @raise error.TestFail: if timeout is reached
        """
        logging.info('Started %s. Will wait up to %d seconds to complete' %
                     (process, timeout))
        start_time = time.time()
        result = self.host.ping_wait_up(timeout=timeout)
        if result:
            logging.info('Device came back up successfully in %d seconds.',
                         time.time() - start_time)
        else:
            self.error_messages.append('Host failed to come back after %s '
                                       'in %d seconds.' % (process, timeout))
        return result


    def run_once(self, host):
        """ Runs the test."""
        self.host = host
        self.error_messages = []

        self.release_builder_path = self.host.get_release_builder_path()

        self.stage_copy_recover_with('recovery_image')
        self.wait_for_dut_ping_after('RECOVERY', self._INSTALL_DELAY_TIMEOUT)

        self.stage_copy_recover_with('test_image')

        # Install the test image back on DUT and reboot
        if self.wait_for_dut_ping_after('TEST_IMAGE RECOVERY BOOT FROM USB',
                                        self._TEST_IMAGE_BOOT_DELAY):
            self.host.run('chromeos-install --yes',
                        timeout=self._INSTALL_DELAY_TIMEOUT)
            self.host.reboot()

        if self.error_messages:
            raise error.TestFail('Failures: %s' % ' '.join(self.error_messages))
