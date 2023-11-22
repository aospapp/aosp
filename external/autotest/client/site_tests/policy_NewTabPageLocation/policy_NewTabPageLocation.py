# Copyright 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.enterprise import enterprise_policy_base
from autotest_lib.client.cros.input_playback import keyboard

from telemetry.core import exceptions


class policy_NewTabPageLocation(
        enterprise_policy_base.EnterprisePolicyTest):
    """
    Tests the NewTabPageLocation policy in Chrome OS.

    If the NewTabPageLocation policy is set, when a NewTab is opened,
    the page configured page will be directly loaded.

    """
    version = 1

    def _homepage_check(self, case_value):
        """
        Open a new tab and checks the proper page is opened.

        @param case_value: policy value for this case.

        """
        self.keyboard.press_key('ctrl+t')

        # Try to get a policy from the current tab. If it works, that means the
        # policy page is open, and the newtab policy set it. Default (not set)
        # would be chrome://newtab, and this try would not work.
        try:
            self._get_policy_stats_shown(self.cr.browser.tabs[-1],
                                             'NewTabPageLocation')
            if not case_value:
                raise error.TestFail(
                    'NewTabPageLocation was set when it should not be!')

        except exceptions.EvaluateException:
            if case_value:
                raise error.TestFail(
                    'NewTabPageLocation was not set when it should be!')

        if not case_value:
            url = self.cr.browser.tabs[-1].GetUrl()
            if url != 'chrome://newtab/':
                raise error.TestFail(
                    'NewTab was not "chrome://newtab/" instead got {}'
                    .format(url))

    def run_once(self, case):
        """
        Setup and run the test configured for the specified test case.

        @param case: Name of the test case to run.

        """
        self.keyboard = keyboard.Keyboard()
        TEST_CASES = {'Set': 'chrome://policy',
                      'NotSet': None}

        case_value = TEST_CASES[case]
        policy_setting = {'NewTabPageLocation': case_value}
        self.setup_case(user_policies=policy_setting)
        self._homepage_check(case_value)
