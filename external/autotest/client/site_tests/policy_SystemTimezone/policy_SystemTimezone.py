# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib import utils
from autotest_lib.client.cros.enterprise import enterprise_policy_base


class policy_SystemTimezone(
        enterprise_policy_base.EnterprisePolicyTest):
    """
    Test effect of SystemTimezone policy on Chrome OS behavior.

    This will test that both the timezone can be set by the policy, and that
    when the policy is not set a user can change the settings.

    """
    version = 1
    POLICY_NAME = 'SystemTimezone'

    JS_WRAPPER = "document.querySelector('{ID}'){action}"
    DROPDOWN_MENU = "* /deep/ #timezoneSelector /deep/ #userTimeZoneSelector /deep/ #dropdownMenu"

    def change_timezone(self, settings_tab, selection):
        """
        Change the timezone via the dropdown menu on the settings page.

        @param settings_tab: The tab object for the settings page.
        @param selection: int, index of item in DROPDOWN_MENU to change to.

        """
        SELECT_INDEX = ".selectedIndex = '{n}'"

        selected_index = SELECT_INDEX.format(n=selection)
        change_timezone = self.JS_WRAPPER.format(ID=self.DROPDOWN_MENU,
                                                 action=selected_index)
        settings_tab.ExecuteJavaScript(change_timezone)
        settings_tab.WaitForDocumentReadyStateToBeComplete()
        self._dispatch_event(settings_tab)

    def _dispatch_event(self, settings_tab):
        """
        Confirms the dropdown select by running a JS dispatchEvent().

        @param settings_tab: The tab object for the settings page.

        """
        new_event = "var event = new Event('change');"
        dispatch = ".dispatchEvent(event);"
        settings_tab.ExecuteJavaScript(new_event)
        settings_tab.ExecuteJavaScript(
            self.JS_WRAPPER.format(ID=self.DROPDOWN_MENU,
                                   action=dispatch))

    def _test_timezone(self, expected):
        """
        Verify the Timezone set on the device.

        This is done by running the UNIX date command (%z) and verifying the
        timezone matches the expected result.

        """
        def check_timezone(expected):
            return utils.system_output('date +%z') == expected

        utils.poll_for_condition(
            lambda: check_timezone(expected),
            exception=error.TestFail('Time zone was not set! Expected {}'
                                     .format(expected)),
            timeout=5,
            sleep_interval=1,
            desc='Polling for timezone change')

    def set_timezones(self):
        """
        Iterate through different time zones, and verify they can be set.

        This is specifically being done to verify the timezone via seeing
        the reported timezone is changing, and not just on the first one via
        luck.

        """
        cases = [{'policy': 'America/Costa_Rica', 'expected': '-0600'},
                 {'policy': 'Asia/Kathmandu', 'expected': '+0545'}]

        for setting in cases:
            policy_value = setting['policy']
            expected = setting['expected']
            policies = {self.POLICY_NAME: policy_value}
            self.setup_case(device_policies=policies, enroll=True)

            # Logout so the policy can take effect
            self.log_out_via_keyboard()

            self._test_timezone(expected)

    def set_empty_timezone(self):
        """
        Manually set and verify the timezone when the policy is empty.

        This will be done by adjusting the setting on the ://settings page,
        and verfying the date reported. Additionally log out, then verify the
        timezone matches as well.

        """
        SETTINGS_URL = "chrome://settings/dateTime/timeZone"
        CLICK = ".click()"
        USER_TIMEZONE_BUTTON = "* /deep/ #timeZoneAutoDetectOff"
        autodetect_disable = self.JS_WRAPPER.format(ID=USER_TIMEZONE_BUTTON,
                                                    action=CLICK)

        policies = {self.POLICY_NAME: ''}
        self.setup_case(device_policies=policies, enroll=True)

        # Open the Timezone settings page
        settings_tab = self.navigate_to_url(SETTINGS_URL)
        settings_tab.WaitForDocumentReadyStateToBeComplete()

        # Select the manual timezone settings radio button
        settings_tab.ExecuteJavaScript(autodetect_disable)
        settings_tab.WaitForDocumentReadyStateToBeComplete()

        # Change the timezone to the first index on the list
        self.change_timezone(settings_tab, 0)
        self._test_timezone('-1100')

        # Close the tab, then logout
        settings_tab.Close()
        self.log_out_via_keyboard()
        self._test_timezone('-1100')

    def run_once(self, case):
        """
        Run the proper test based on the selected case.

        @param case: bool or None, value of the test case to run.

        """
        if case:
            self.set_timezones()
        else:
            self.set_empty_timezone()
