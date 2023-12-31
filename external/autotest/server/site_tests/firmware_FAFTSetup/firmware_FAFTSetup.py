# Copyright (c) 2012 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
from threading import Timer

from autotest_lib.client.bin.input import linux_input
from autotest_lib.client.common_lib import common
from autotest_lib.client.common_lib import error
from autotest_lib.server.cros.faft.firmware_test import FirmwareTest


class firmware_FAFTSetup(FirmwareTest):
    """This test checks the following FAFT hardware requirement:
      - Warm reset
      - Cold reset
      - Recovery boot with USB stick
      - USB stick is plugged into Servo board, not DUT
      - Keyboard simulation
      - No terminal opened on EC console
    """
    version = 1
    NEEDS_SERVO_USB = True

    # Delay to ensure client is ready to read the key press.
    KEY_PRESS_DELAY = 2

    def console_checker(self):
        """Verify EC console is available if using Chrome EC."""
        if not self.check_ec_capability(suppress_warning=True):
            # Not Chrome EC. Nothing to check.
            return True
        try:
            if self.ec.get_version():
                return True
        except:  # pylint: disable=W0702
            pass

        logging.error("Cannot talk to EC console.")
        logging.error(
                "Please check there is no terminal opened on EC console.")
        raise error.TestFail("Failed EC console check.")

    def base_keyboard_checker(self, press_action):
        """Press key and check from DUT.

        Args:
            press_action: A callable that would press the keys when called.
        """
        result = True
        # Stop UI so that key presses don't go to Chrome.
        self.faft_client.system.run_shell_command("stop ui")

        # Press the keys
        Timer(self.KEY_PRESS_DELAY, press_action).start()

        # Invoke client side script to monitor keystrokes
        if self.faft_client.system.check_keys([
                linux_input.KEY_LEFTCTRL, linux_input.KEY_D,
                linux_input.KEY_ENTER
        ]) < 0:
            result = False

        # Turn UI back on
        self.faft_client.system.run_shell_command("start ui")
        return result

    def keyboard_checker(self):
        """Press '<ctrl_l>', 'd', '<enter>' by servo and check from DUT."""

        def keypress():
            """Press <ctrl_l>, 'd', '<enter>'"""
            self.servo.ctrl_d()
            self.servo.enter_key()

        return self.base_keyboard_checker(keypress)

    def run_once(self):
        """Main test logic"""
        logging.info("Check EC console is available and test warm reboot")
        self.console_checker()
        self.switcher.mode_aware_reboot()

        logging.info("Check test image is on USB stick and run recovery boot")
        self.setup_usbkey(usbkey=True, host=False)
        self.switcher.reboot_to_mode(to_mode='rec')

        self.check_state((self.checkers.crossystem_checker, {
                'mainfw_type': 'recovery'
        }))

        logging.info("Check cold boot")
        self.switcher.mode_aware_reboot(reboot_type='cold')

        if self.faft_config.mode_switcher_type in (
                'menu_switcher',
                'keyboard_dev_switcher') and not self.faft_config.is_detachable:
            logging.info("Check keyboard simulation")
            self.check_state(self.keyboard_checker)
        else:
            logging.info("Skip keyboard simulation on an embedded device")
