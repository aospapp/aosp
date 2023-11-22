# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import time

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.server import autotest, test
from autotest_lib.server.cros.faft.firmware_test import FirmwareTest


class firmware_Cr50Uart(FirmwareTest):
    """Verify Cr50 uart control

    Verify Cr50 will enable/disable the AP and EC uart when servo is
    disconnected/connected.
    """
    version = 1

    # Time used to wait for Cr50 to detect the servo state
    SLEEP = 2

    # Strings used for the Cr50 CCD command
    CCD = 'CCD'
    CCD_UART = 'ccd uart'
    ENABLE = 'enable'
    DISABLE = 'disable'
    GET_STATE = ':\s+(%s|%s)' % (ENABLE, DISABLE)
    CCD_STATE = '%s%s' % (CCD, GET_STATE)
    UART_RESPONSE = ['(AP UART)%s' % GET_STATE, '(EC UART)%s' % GET_STATE]

    # A list of the actions we should verify
    TEST_CASES = [
        'fake_servo on, uart enable, fake_servo off, uart enable',
        'uart enable, fake_servo on, uart disable',
    ]
    # used to reset the CCD uart and servo state
    RESET = 'uart disable, fake_servo off'
    # A dictionary containing an order of steps to verify and the expected uart
    # response as the value.
    # The keys are a list of strings [ap uart state, ec uart state]. There are
    # three valid states: None, 'enable', or 'disable'. None should be used if
    # the state can not be determined and the check should be skipped. 'enable'
    # and 'disable' are the valid states.
    EXPECTED_RESULTS = {
        # We cannot guarantee the ap uart state, because we dont know if servo
        # is connected.
        'uart disable' : [None,  DISABLE],
        # This should be the state used to start of each test
        'uart disable, fake_servo off' : [ENABLE, DISABLE],
        # uart enable will enable both the EC and AP uart
        'uart enable' : [ENABLE, ENABLE],
        # Cr50 cannot detect servo connect when uart is enabled
        'uart enable, fake_servo on' : [ENABLE, ENABLE],
        # Cr50 will disable the AP uart too, because it will detect servo
        'uart enable, fake_servo on, uart disable' : [DISABLE, DISABLE],
        # Cr50 will disable both uarts when servo is connected
        'fake_servo on' : [DISABLE, DISABLE],
        # Cr50 cannot enable uart when servo is connected
        'fake_servo on, uart enable' : [DISABLE, DISABLE],
        # Cr50 will detect the servo disconnect then enable AP uart. It doesn't
        # reenable the ec uart
        'fake_servo on, uart enable, fake_servo off' : [ENABLE, DISABLE],
        # uart enable needs to be run again to reenable ec uart
        'fake_servo on, uart enable, fake_servo off, uart enable' :
            [ENABLE, ENABLE],
    }


    def initialize(self, host, cmdline_args):
        super(firmware_Cr50Uart, self).initialize(host, cmdline_args)
        if not hasattr(self, 'cr50'):
            raise error.TestNAError('Test can only be run on devices with '
                                    'access to the Cr50 console')
        if self.cr50.using_ccd():
            raise error.TestNAError('Use a flex cable instead of CCD cable.')

        if self.servo.get('ccd_lock') == 'on':
            raise error.TestNAError('Console needs to be unlocked to run test')

        self.ccd_set_state(self.ENABLE)


    def cleanup(self):
        """Disable CCD and reenable the EC uart"""
        # reenable the EC uart
        self.fake_servo('on')
        # disable CCD
        self.ccd_set_state(self.DISABLE)
        super(firmware_Cr50Uart, self).cleanup()


    def verify_uart(self, run):
        """Verify the current state matches the expected result from the run.

        Args:
            run: the string representing the actions that have been run.

        Raises:
            TestError if any of the states are not correct
        """
        expected_states = self.EXPECTED_RESULTS[run]
        current_state = self.cr50.send_command_get_output(self.CCD,
                                                          self.UART_RESPONSE)
        for i, expected_state in enumerate(expected_states):
            _, uart, state = current_state[i]
            if expected_state and expected_state != state:
                raise error.TestError('%s: unexpected %s state got %s instead '
                                      'of %s.' % (run, uart.lower(), state,
                                                  expected_state))


    def ccd_set_state(self, state):
        """Enable or disable CCD

        Args:
            state: string 'enable' or 'disable'

        Raises:
            TestError if CCD was not set
        """
        # try to set the state
        self.cr50.send_command('%s %s' % (self.CCD, state))

        # wait for Cr50 to enable/disable CCD
        time.sleep(self.SLEEP)

        # get the state
        resp = self.cr50.send_command_get_output(self.CCD, [self.CCD_STATE])
        logging.info(resp)
        if resp[0][1] != state:
            raise error.TestError('Could not %s ccd' % state.lower())


    def uart(self, state):
        """Enable or disable the CCD uart

        @param state: string 'enable' or 'disable'
        """
        self.cr50.send_command('%s %s' % (self.CCD_UART, state))


    def fake_servo(self, state):
        """Mimic servo on/off

        Cr50 monitors the servo EC uart tx signal to detect servo. If the signal
        is pulled up, then Cr50 will think servo is connnected. Enable the ec
        uart to enable the pullup. Disable the it to remove the pullup.

        It takes some time for Cr50 to detect the servo state so wait 2 seconds
        before returning.
        """
        self.servo.set('ec_uart_en', state)

        # Cr50 needs time to detect the servo state
        time.sleep(self.SLEEP)


    def run_steps(self, steps):
        """Do each step in steps and then verify the uart state.

        The uart state is order dependent, so we need to know all of the
        previous steps to verify the state. This will do all of the steps in
        the string and verify the Cr50 CCD uart state after each step.

        @param steps: a comma separated string with the steps to run
        """
        # The order of steps is separated by ', '. Remove the last step and
        # run all of the steps before it.
        separated_steps = steps.rsplit(', ', 1)
        if len(separated_steps) > 1:
            self.run_steps(separated_steps[0])

        step = separated_steps[-1]
        # the func and state are separated by ' '
        func, state = step.split(' ')
        logging.info('running %s', step)
        getattr(self, func)(state)

        # Verify the AP and EC uart states match the expected result
        self.verify_uart(steps)


    def run_once(self):
        for steps in self.TEST_CASES:
            self.run_steps(self.RESET)
            logging.info('TESTING: %s' % steps)
            self.run_steps(steps)
            logging.info('VERIFIED: %s' % steps)
