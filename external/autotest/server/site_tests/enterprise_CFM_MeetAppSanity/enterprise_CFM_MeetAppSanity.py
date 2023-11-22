# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import tpm_utils
from autotest_lib.server import test
from autotest_lib.server.cros.multimedia import remote_facade_factory


LONG_TIMEOUT = 10
SHORT_TIMEOUT = 5


class enterprise_CFM_MeetAppSanity(test.test):
    """Basic sanity test for Meet App to be expanded to cover more cases
    like enterprise_CFM_Sanity test.
    """
    version = 1


    def run_once(self, host=None):
        """Runs the test."""
        self.client = host

        factory = remote_facade_factory.RemoteFacadeFactory(
                host, no_chrome=True)
        self.cfm_facade = factory.create_cfm_facade()

        tpm_utils.ClearTPMOwnerRequest(self.client)

        # Enable USB port on the servo so device can see and talk to the
        # attached peripheral.
        if self.client.servo:
            self.client.servo.switch_usbkey('dut')
            self.client.servo.set('usb_mux_sel3', 'dut_sees_usbkey')
            time.sleep(SHORT_TIMEOUT)
            self.client.servo.set('dut_hub1_rst1', 'off')
            time.sleep(SHORT_TIMEOUT)

        try:
            self.cfm_facade.enroll_device()

            # The following reboot and sleep are a hack around devtools crash
            # issue tracked in crbug.com/739474.
            self.client.reboot()
            time.sleep(SHORT_TIMEOUT)

            self.cfm_facade.skip_oobe_after_enrollment()

            # Following trigger new Thor/Meetings APIs.
            self.cfm_facade.wait_for_meetings_telemetry_commands()
            self.cfm_facade.start_meeting_session()
            time.sleep(LONG_TIMEOUT)
            self.cfm_facade.end_meeting_session()
            time.sleep(SHORT_TIMEOUT)
        except Exception as e:
            raise error.TestFail(str(e))
        finally:
            tpm_utils.ClearTPMOwnerRequest(self.client)
