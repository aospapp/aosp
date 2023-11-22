# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging, time, random

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import tpm_utils
from autotest_lib.server import test
from autotest_lib.server import hosts
from autotest_lib.server.cros.multimedia import remote_facade_factory

CMD = "usb-devices | grep ^P:"
FAILED_TEST_LIST = list()
IDLE_TIME = 30
LONG_TIMEOUT = 20
MEETS_BETWEEN_REBOOT = 10
SHORT_TIMEOUT = 5


class enterprise_CFM_USBPeripheralRebootStress(test.test):
    """Stress test of USB devices in CfM mode by warm rebooting CfM
    multiple times with joining/leaving meetings ."""
    version = 1


    def _peripherals_sanity_test(self):
        """Checks for connected peripherals."""
        if not self.cfm_facade.get_mic_devices():
             FAILED_TEST_LIST.append('No mic detected')
        if not self.cfm_facade.get_speaker_devices():
             FAILED_TEST_LIST.append('No speaker detected')
        if not self.cfm_facade.get_camera_devices():
             FAILED_TEST_LIST.append('No camera detected')
        if not self.cfm_facade.get_preferred_mic():
             FAILED_TEST_LIST.append('No preferred mic')
        if not self.cfm_facade.get_preferred_speaker():
             FAILED_TEST_LIST.append('No preferred speaker')
        if not self.cfm_facade.get_preferred_camera():
             FAILED_TEST_LIST.append('No preferred camera')


    def _run_hangout_session(self, hangout, original_list):
        """Start a hangout session and end the session after random time.

        @raises error.TestFail if any of the checks fail.
        """
        hangout_name = hangout
        logging.info('Session name: %s', hangout_name)
        logging.info('Now joining session.........')
        self.cfm_facade.start_new_hangout_session(hangout_name)
        time.sleep(random.randrange(1, LONG_TIMEOUT))
        if not self._compare_cmd_output(original_list):
            raise error.TestFail(
                'After joining meeting list of USB devices is not the same.')
        self._peripherals_sanity_test()
        self.cfm_facade.end_hangout_session()
        logging.info('Stopping session................')


    def _compare_cmd_output(self, original_list):
        """Compare output of linux cmd."""
        only_in_original = []
        only_in_new = []
        new_output = self.client.run(CMD).stdout.rstrip()
        new_list= new_output.splitlines()
        if not set(new_list) == set(original_list):
            only_in_original = list(set(original_list) - set(new_list))
            only_in_new = list(set(new_list) - set(original_list))
            if only_in_original:
                logging.info('These are devices not in the new list')
                for _device in only_in_original:
                    logging.info('    %s', _device)
            if only_in_new:
                logging.info('These are devices not in the original list')
                for _device in only_in_new:
                    logging.info('    %s', _device)
        return set(new_list) == set(original_list)


    def run_once(self, host, hangout, repeat):
        """Main function to run autotest.

        @param host: Host object representing the DUT.
        @hangout: Name of meeting that DUT will join/leave.
        @param repeat: Number of times CfM joins and leaves meeting.
        """
        counter = 1
        self.client = host
        factory = remote_facade_factory.RemoteFacadeFactory(
                host, no_chrome=True)
        self.cfm_facade = factory.create_cfm_facade()

        tpm_utils.ClearTPMOwnerRequest(self.client)

        if self.client.servo:
            self.client.servo.switch_usbkey('dut')
            self.client.servo.set('usb_mux_sel3', 'dut_sees_usbkey')
            time.sleep(SHORT_TIMEOUT)
            self.client.servo.set('dut_hub1_rst1', 'off')
            time.sleep(SHORT_TIMEOUT)

        try:
            self.cfm_facade.enroll_device()
            self.cfm_facade.restart_chrome_for_cfm()
            self.cfm_facade.wait_for_telemetry_commands()
            if not self.cfm_facade.is_oobe_start_page():
                self.cfm_facade.wait_for_oobe_start_page()
            self.cfm_facade.skip_oobe_screen()
        except Exception as e:
            raise error.TestFail(str(e))

        usb_original_output = host.run(CMD).stdout.rstrip()
        logging.info('The initial usb devices:\n %s', usb_original_output)
        usb_original_list = usb_original_output.splitlines()

        while repeat > 0:
            self.client.reboot()
            time.sleep(random.randrange(1, IDLE_TIME))
            self.cfm_facade.restart_chrome_for_cfm()
            self.cfm_facade.wait_for_telemetry_commands()
            if not self._compare_cmd_output(usb_original_list):
                raise error.TestFail(
                    "After reboot list of USB devices is not the same.")

            for test in range(random.randrange(1, MEETS_BETWEEN_REBOOT)):
                logging.info('Start meeting for loop: #%d', counter)
                counter += 1
                self._run_hangout_session(hangout, usb_original_list)
                if FAILED_TEST_LIST:
                     raise error.TestFail(
                         'Test failed because of following reasons: %s'
                         % ', '.join(map(str, FAILED_TEST_LIST)))
                repeat -= 1
                if repeat == 0:
                   break

        tpm_utils.ClearTPMOwnerRequest(self.client)
