# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import time

from autotest_lib.client.common_lib import enum, error
from autotest_lib.server import test
from autotest_lib.server.cros.dark_resume_utils import DarkResumeUtils
from autotest_lib.server.cros.faft.config.config import Config as FAFTConfig
from autotest_lib.server.cros.servo import chrome_ec


# Possible states base can be forced into.
BASE_STATE = enum.Enum('ATTACH', 'DETACH', 'RESET')


 # List of wake sources expected to cause a full resume.
FULL_WAKE_SOURCES = ['PWR_BTN', 'LID_OPEN', 'BASE_ATTACH',
                     'BASE_DETACH', 'INTERNAL_KB']

# Max time taken by the system to resume.
RESUME_DURATION_SECS = 5

# Time in future after which RTC goes off.
RTC_WAKE_SECS = 30

# Max time taken by the system to suspend.
SUSPEND_DURATION_SECS = 5

# Time to allow lid transition to take effect.
WAIT_TIME_LID_TRANSITION_SECS = 5


class power_WakeSources(test.test):
    """
    Verify that wakes from input devices can trigger a full
    resume. Currently tests :
        1. power button
        2. lid open
        3. base attach
        4. base detach

    Also tests RTC triggers a dark resume.

    """
    version = 1

    def _after_resume(self, wake_source):
        """Cleanup to perform after resuming the device.

        @param wake_source: Wake source that has been tested.
        """
        if wake_source in ['BASE_ATTACH', 'BASE_DETACH']:
            self._force_base_state(BASE_STATE.RESET)

    def _before_suspend(self, wake_source):
        """Prep before suspend.

        @param wake_source: Wake source that is going to be tested.

        @return: Boolean, whether _before_suspend action is successful.
        """
        if wake_source == 'BASE_ATTACH':
            # Force detach before suspend so that attach won't be ignored.
            return self._force_base_state(BASE_STATE.DETACH)
        if wake_source == 'BASE_DETACH':
            # Force attach before suspend so that detach won't be ignored.
            return self._force_base_state(BASE_STATE.ATTACH)
        if wake_source == 'LID_OPEN':
            # Set the power policy for lid closed action to suspend.
            return self._host.run(
                'set_power_policy --lid_closed_action suspend',
                ignore_status=True).exit_status == 0
        return True

    def _force_base_state(self, base_state):
        """Send EC command to force the |base_state|.

        @param base_state: State to force base to. One of |BASE_STATE| enum.

        @return: False if the command does not exist in the current EC build.

        @raise error.TestFail : If base state change fails.
        """
        ec_cmd = 'basestate '
        ec_arg = {
            BASE_STATE.ATTACH: 'a',
            BASE_STATE.DETACH: 'd',
            BASE_STATE.RESET: 'r'
        }

        ec_cmd += ec_arg[base_state]

        try:
            self._ec.send_command(ec_cmd)
        except error.TestFail as e:
            if 'No control named' in str(e):
                # Since the command is added recently, this might not exist on
                # every board.
                logging.warning('basestate command does not exist on the EC. '
                                'Please verify the base state manually.')
                return False
            else:
                raise e
        return True

    def _is_valid_wake_source(self, wake_source):
        """Check if |wake_source| is valid for DUT.

        @param wake_source: wake source to verify.
        @return: False if |wake_source| is not valid for DUT, True otherwise
        """
        if wake_source.startswith('BASE'):
            if self._host.run('which hammerd', ignore_status=True).\
                exit_status == 0:
                # Smoke test to see if EC has support to reset base.
                return self._force_base_state(BASE_STATE.RESET)
            else:
                return False
        if wake_source == 'LID_OPEN':
            return self._dr_utils.host_has_lid()
        if wake_source == 'INTERNAL_KB':
            return self._faft_config.has_keyboard
        return True

    def _test_full_wake(self, wake_source):
        """Test if |wake_source| triggers a full resume.

        @param wake_source: wake source to test. One of |FULL_WAKE_SOURCES|.
        @return: True, if we are able to successfully test the |wake source|
            triggers a full wake.
        """
        is_success = True
        logging.info('Testing wake by %s triggers a '
                     'full wake when dark resume is enabled.', wake_source)
        if not self._before_suspend(wake_source):
            logging.error('Before suspend action failed for %s', wake_source)
            is_success = False
        else:
            count_before = self._dr_utils.count_dark_resumes()
            with self._dr_utils.suspend() as _:
                logging.info('DUT suspended! Waiting to resume...')
                # Wait at least |SUSPEND_DURATION_SECS| secs for the kernel to
                # fully suspend.
                time.sleep(SUSPEND_DURATION_SECS)
                self._trigger_wake(wake_source)
                # Wait at least |RESUME_DURATION_SECS| secs for the device to
                # resume.
                time.sleep(RESUME_DURATION_SECS)

                if not self._host.is_up():
                    logging.error('Device did not resume from suspend for %s',
                                  wake_source)
                    is_success = False

            count_after = self._dr_utils.count_dark_resumes()
            if count_before != count_after:
                logging.error('%s caused a dark resume.', wake_source)
                is_success = False
        self._after_resume(wake_source)
        return is_success

    def _test_rtc(self):
        """Suspend the device and test if RTC triggers a dark_resume.

        @return boolean, true if RTC alarm caused a dark resume.
        """

        logging.info('Testing RTC triggers dark resume when enabled.')

        count_before = self._dr_utils.count_dark_resumes()
        with self._dr_utils.suspend(RTC_WAKE_SECS) as _:
            logging.info('DUT suspended! Waiting to resume...')
            time.sleep(SUSPEND_DURATION_SECS + RTC_WAKE_SECS +
                       RESUME_DURATION_SECS)

            if not self._host.is_up():
                logging.error('Device did not resume from suspend for RTC')
                return False

        count_after = self._dr_utils.count_dark_resumes()
        if count_before != count_after - 1:
            logging.error(' RTC did not cause a dark resume.'
                          'count before = %d, count after = %d',
                          count_before, count_after)
            return False
        return True

    def _trigger_wake(self, wake_source):
        """Trigger wake using the given |wake_source|.

        @param wake_source : wake_source that is being tested.
            One of |FULL_WAKE_SOURCES|.
        """
        if wake_source == 'PWR_BTN':
            self._host.servo.power_short_press()
        elif wake_source == 'LID_OPEN':
            self._host.servo.lid_close()
            time.sleep(WAIT_TIME_LID_TRANSITION_SECS)
            self._host.servo.lid_open()
        elif wake_source == 'BASE_ATTACH':
            self._force_base_state(BASE_STATE.ATTACH)
        elif wake_source == 'BASE_DETACH':
            self._force_base_state(BASE_STATE.DETACH)
        elif wake_source == 'INTERNAL_KB':
            self._host.servo.ctrl_key()

    def cleanup(self):
        """cleanup."""
        self._dr_utils.stop_resuspend_on_dark_resume(False)
        self._dr_utils.teardown()

    def initialize(self, host):
        """Initialize wake sources tests.

        @param host: Host on which the test will be run.
        """
        self._host = host
        self._dr_utils = DarkResumeUtils(host)
        self._dr_utils.stop_resuspend_on_dark_resume()
        self._ec = chrome_ec.ChromeEC(self._host.servo)
        self._faft_config = FAFTConfig(self._host.get_platform())

    def run_once(self):
        """Body of the test."""

        test_ws = set(ws for ws in FULL_WAKE_SOURCES if \
            self._is_valid_wake_source(ws))
        passed_ws = set(ws for ws in test_ws if self._test_full_wake(ws))
        failed_ws = test_ws.difference(passed_ws)
        skipped_ws = set(FULL_WAKE_SOURCES).difference(test_ws)

        if self._test_rtc():
            passed_ws.add('RTC')
        else:
            failed_ws.add('RTC')
        if len(passed_ws):
            logging.info('[%s] woke the device as expected.',
                         ''.join(str(elem) + ', ' for elem in passed_ws))
        if skipped_ws:
            logging.info('[%s] are not wake sources on this platform. '
                         'Please test manually if not the case.',
                         ''.join(str(elem) + ', ' for elem in skipped_ws))

        if len(failed_ws):
            raise error.TestFail(
                '[%s] wake sources did not behave as expected.'
                % (''.join(str(elem) + ', ' for elem in failed_ws)))
