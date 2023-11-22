# Copyright 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib import utils

from autotest_lib.client.cros.enterprise import enterprise_policy_base
from autotest_lib.client.cros.input_playback import keyboard


class policy_PrintingEnabled(
        enterprise_policy_base.EnterprisePolicyTest):
    """
    Test effect of PrintingEnabled policy on Chrome OS.

    The test will open a page, and attempt to 'print' the page as a local
    PDF. If PDF is present after the print, printing is Enabled. If not,
    printing is disabled.

    """
    version = 1

    POLICY_NAME = 'PrintingEnabled'

    def _input_key(self, key):
        """
        Press the key specified, wait a short time for the page to respond.

        There is a 2 second wait for each button push to allow the dialog to
        open, and load before proceeding. Because this dialog is not part of
        the controllable telemetry view, there is not an easier/better way to
        know if the dialog is fully loaded or not.

        @key: string of the key(s) to press"""

        self.keyboard.press_key(key)
        time.sleep(2)

    def _print_check(self, case):
        """
        Navigates to the chrome://policy page, and will check to see if the
        print button is enabled/blocked.

        @param case: bool or None, the setting of the PrintingEnabled Policy

        """
        self.navigate_to_url('chrome://policy')

        # Open the print page, and hit enter to print, and save (as local pdf).
        self._input_key('ctrl+p')
        self._input_key('enter')
        self._input_key('enter')

        download_dur = utils.system_output('ls /home/chronos/user/Downloads/')

        if case or case is None:
            if 'Policies' not in download_dur:
                raise error.TestError('Printing not enabled when it should be')
        else:
            if 'Policies' in download_dur:
                raise error.TestError('Printing enabled when it should not be')

    def run_once(self, case):
        """
        Entry point of the test.

        @param case: Name of the test case to run.

        """
        self.keyboard = keyboard.Keyboard()
        self.setup_case(user_policies={'PrintingEnabled': case},
                        disable_default_apps=False)

        self._print_check(case)
        self.keyboard.close()
