# Copyright 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import time

from autotest_lib.client.common_lib import error
from autotest_lib.server.cros.faft.cr50_test import Cr50Test


class firmware_Cr50DeferredECReset(Cr50Test):
    """Verify EC_RST_L stays asserted only if all conditions below are True.
    (1) System got 'Power-On reset'.
    (2) RDD cable is connected.
    (3) The power button is held.

    After this, EC_RST_L should be deasserted as soon as the power button
    gets released.
    """
    version = 1

    def initialize(self, host, cmdline_args, full_args):
        """Initialize the test and check if
           cr50 is exists,
           DTS is controllable, and
           power delivery mode and power button is adjustable.
        """
        super(firmware_Cr50DeferredECReset, self).initialize(host, cmdline_args,
                full_args)
        if not hasattr(self, 'cr50'):
            raise error.TestNAError('Test can only be run on devices with '
                                    'access to the Cr50 console')
        if not self.cr50.servo_v4_supports_dts_mode():
            raise error.TestNAError('Need working servo v4 DTS control')

        self.fast_open(enable_testlab=True)
        if not self.cr50.testlab_is_on():
            raise error.TestNAError('Cr50 testlab mode needs to be enabled')

        # Test the external power delivery
        self.servo.set('servo_v4_role', 'snk')
        time.sleep(3)

        rv = self.ec.send_command_get_output('chgstate',['.*>'])[0].strip()
        logging.info(rv)
        if not 'ac = 0' in rv:
            raise error.TestFail('Failed in setting servo_v4_role sink')

        # Test stopping the external power delivery
        self.servo.set('servo_v4_role', 'src')
        time.sleep(3)

        rv = self.ec.send_command_get_output('chgstate',['.*>'])[0].strip()
        logging.info(rv)
        if not 'ac = 1' in rv:
            raise error.TestFail('Failed in setting  servo_v4_role source')

        # Test if the power button is adjustable.
        self.servo.set('pwr_button', 'press')
        self.servo.set('pwr_button', 'release')

    def check_ecrst_asserted(self, expect_assert):
        """Ask CR50 whether EC_RST_L is asserted or deasserted.

        Args:
            expect_assert: True if it is expected asserted.
                           False otherwise.
        """

        # If the console is responsive, then the EC is awake.
        rv = self.cr50.send_command_get_output('ecrst',
                ['EC_RST_L is \w{0,2}asserted.*>'])[0].strip()
        logging.info(rv)
        expecting_txt = ' asserted' if expect_assert else ' deasserted'

        if not expecting_txt in rv:
            raise error.TestFail(rv)

    def ping_ec(self, expect_response):
        """Check if EC is running and responding.

        Args:
            expect_response: True if EC should respond
                             False otherwise.
        """
        try:
            rv = self.ec.send_command_get_output('time',
                    ['time.*>'])[0].strip()
        except error.TestFail as e:
            logging.info(e)
            if 'Timeout waiting for response' in str(e):
                if not expect_response:
                    return
            raise e
        else:
            if not expect_response:
                raise error.TestFail(rv)

    def test_deferred_ec_reset(self, power_button_hold, rdd_enable,
            expect_ec_response):
        """Do a power-on reset, and check if EC responds.

        Args:
            power_button_hold: True if it should be pressed on a system reset.
                               False otherwise.
            rdd_enable: True if RDD should be detected on a system reset.
                        False otherwise.
            expect_ec_response: True if EC should run and response on a system
                                reset.
                                False otherwise.
        """
        logging.info('Test deferred_ec_reset starts.')
        logging.info('Power button held: %s', power_button_hold)
        logging.info('RDD connection   : %s', rdd_enable)

        # Stop power delivery to DUT
        self.servo.set('servo_v4_role', 'snk')
        time.sleep(3)

        # Battery Cutoff
        self.ec.send_command('cutoff')
        time.sleep(3)

        # EC should not respond
        self.ping_ec(False)

        # press (or release) the power button
        power_button_setval = 'press' if power_button_hold else 'release'
        # call set_nocheck, since power button shall be recognized as pressed
        # at this point.
        self.servo.set_nocheck('pwr_button', power_button_setval)

        # enable RDD Connection (or disable)
        self.servo.set_nocheck('servo_v4_dts_mode',
                'on' if rdd_enable else 'off')
        time.sleep(self.cr50.SHORT_WAIT)

        # Enable power delivery to DUT
        self.servo.set('servo_v4_role', 'src')

        # Wait for a while
        wait_sec = 30
        logging.info('waiting for %d seconds', wait_sec)
        time.sleep(wait_sec)

        # Check if EC_RST_L is asserted (or deasserted) and EC is on (or off).
        self.check_ecrst_asserted(not expect_ec_response)
        self.ping_ec(expect_ec_response)

        # Release power button
        self.servo.set('pwr_button', 'release')

        # Check if EC_RST_L is deasserted and EC is on.
        self.check_ecrst_asserted(False)
        self.ping_ec(True)

        # Recover CCD
        if self.servo.get('servo_v4_dts_mode') == 'off':
            self.cr50.ccd_enable()

    def run_once(self):
        """Test deferred EC reset feature. """

        # Release power button and disable RDD on power-on reset.
        # EC should be running.
        self.test_deferred_ec_reset(power_button_hold=False, rdd_enable=False,
            expect_ec_response=True)

        # Release power button but enable RDD on power-on reset.
        # EC should be running.
        self.test_deferred_ec_reset(power_button_hold=False, rdd_enable=True,
            expect_ec_response=True)

        # Hold power button but disable RDD on power-on reset.
        # EC should be running.
        self.test_deferred_ec_reset(power_button_hold=True, rdd_enable=False,
            expect_ec_response=True)

        # Hold power button and enable RDD on power-on reset.
        # EC should not be running.
        self.test_deferred_ec_reset(power_button_hold=True, rdd_enable=True,
            expect_ec_response=False)
