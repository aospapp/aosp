# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.enterprise import enterprise_policy_base
from autotest_lib.client.cros.input_playback import keyboard


class policy_IncognitoModeAvailability(
        enterprise_policy_base.EnterprisePolicyTest):
    """
    Tests the IncognitoModeAvailable policy in Chrome OS.

    If the policy is set to Available then the user will be able to open
    a new Incognito window. If the policy is Disabled then the user should not
    be able to open a new Incognito window. Forced is not being tested.

    """
    version = 1

    def initialize(self, **kwargs):
        super(policy_IncognitoModeAvailability, self).initialize(**kwargs)
        self.keyboard = keyboard.Keyboard()
        self.POLICY_NAME = 'IncognitoModeAvailability'
        self.POLICIES = {}
        self.TEST_CASES = {
            'Available': 0,
            'Disabled': 1}

    def _check_incognito_mode_availability(self, case):
        """
        Opens a new chrome://user-actions page and then tries to open a new
        Incognito window. To see if the new window actually opened the test
        checks the number of tabs opened as well as what was recorded in
        user actions.

        @param case: policy description.

        """
        page_scrape_cmd = (
            'document.getElementById("user-actions-table").innerText;')
        self.navigate_to_url('chrome://user-actions')
        current_number_of_chrome_tabs = len(self.cr.browser.tabs)
        self.keyboard.press_key('ctrl+shift+n')
        new_tab_count = len(self.cr.browser.tabs)

        recorded_user_actions = (
            self.cr.browser.tabs[1].EvaluateJavaScript(page_scrape_cmd))

        if case == 'Available':
            if (new_tab_count <= current_number_of_chrome_tabs or
                'NewIncognitoWindow' not in recorded_user_actions):
                    raise error.TestFail('New Incognito window did not open.')

        else:
            if (new_tab_count != current_number_of_chrome_tabs or
                'Accel_New_Incognito_Window' not in recorded_user_actions):
                   raise error.TestFail('New Incognito window did open.')

    def run_once(self, case):
        """
        Setup and run the test configured for the specified test case.

        @param case: Name of the test case to run.

        """
        case_value = self.TEST_CASES[case]
        self.POLICIES[self.POLICY_NAME] = case_value
        self.setup_case(user_policies=self.POLICIES)
        self._check_incognito_mode_availability(case)