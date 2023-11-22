# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import os
import time

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chrome
from autotest_lib.client.cros.update_engine import update_engine_test

class autoupdate_UserData(update_engine_test.UpdateEngineTest):
    """
    Logs in, stats an update, waits for a while, then logs out.

    This test is used as part of the server test autoupdate_Interruptions.

    """
    version = 1

    # Input method to change to.
    _NEW_INPUT_METHOD = 'US International keyboard'

    # Javascript code for interacting with chrome://settings.
    _CLICK_TIMEZONE_BUTTON = "document.querySelector('* /deep/ #timeZoneButton').click()"
    _CLICK_AUTODETECT_OFF = "document.querySelector('* /deep/ #timeZoneAutoDetectOff').click()"
    _IS_AUTODETECT_OFF = "document.querySelector('* /deep/ #timeZoneAutoDetectOff').checked"
    _CLICK_INPUT_METHOD_BUTTON = "document.querySelector('* /deep/ #manageInputMethodsSubpageTrigger').click()"
    _CLICK_MANAGE_INPUT_METHODS_BUTTON =  "document.querySelector('* /deep/ #manageInputMethods').click()"
    _CLICK_ADD_US_INTL_OPTION = "document.querySelectorAll('* /deep/ paper-checkbox')[1].click()"
    _CLICK_MAKE_US_INTL_DEFAULT = "document.querySelector('* /deep/ #inputMethodsCollapse div.vertical-list').children[1].click()"
    _GET_DEFAULT_INPUT_METHOD = "document.querySelector('* /deep/ #inputMethodsCollapse div.vertical-list').children[0].innerText"
    _TEST_FILE = '/home/chronos/user/Downloads/test.txt'


    def _execute_javascript(self, tab, code):
        """
        Exeutes javascript code in the tab provided.

        @param tab: The chrome tab to run code in.
        @param code: The javascript code to execute.

        """
        tab.ExecuteJavaScript(code)
        time.sleep(5)


    def _navigate(self, tab, url):
        """
        Navigate a chrome tab to a URL.

        @param tab: The chrome tab.
        @param url: The URL to navigate to.

        """
        tab.Navigate(url)
        tab.WaitForDocumentReadyStateToBeComplete()
        time.sleep(5)


    def _modify_input_methods(self, tab):
        """
        Change default input method to US International.

        @param tab: The chrome tab to user.

        """
        # TODO(dhaddock): A better way to interact with chrome://settings.
        self._navigate(tab, 'chrome://settings/languages')
        self._execute_javascript(tab, self._CLICK_INPUT_METHOD_BUTTON)
        self._execute_javascript(tab, self._CLICK_MANAGE_INPUT_METHODS_BUTTON)
        self._execute_javascript(tab, self._CLICK_ADD_US_INTL_OPTION)
        self._navigate(tab, 'chrome://settings/languages')
        self._execute_javascript(tab, self._CLICK_INPUT_METHOD_BUTTON)
        self._execute_javascript(tab, self._CLICK_MAKE_US_INTL_DEFAULT)


    def _modify_time_zone(self, tab):
        """
        Change time zone to by user selected instead of automatic.

        @param tab: The chrome tab to user.

        """
        # TODO(dhaddock): A better way to interact with chrome://settings.
        self._navigate(tab, 'chrome://settings/dateTime')
        self._execute_javascript(tab, self._CLICK_TIMEZONE_BUTTON)
        self._execute_javascript(tab, self._CLICK_AUTODETECT_OFF)


    def _perform_after_update_checks(self):
        """Check the user preferences and files are the same."""
        with chrome.Chrome(logged_in=True, dont_override_profile=True) as cr:
            tab = cr.browser.tabs[0]

            # Open input methods and ensure the default is the one
            # we selected before the update.
            self._navigate(tab, 'chrome://settings/languages')
            self._execute_javascript(tab, self._CLICK_INPUT_METHOD_BUTTON)
            result = tab.EvaluateJavaScript(self._GET_DEFAULT_INPUT_METHOD)
            if self._NEW_INPUT_METHOD not in result:
                raise error.TestFail('Input method was not preserved.')

            # Make sure we are not autodetecting timezone.
            self._navigate(tab, 'chrome://settings/dateTime')
            self._execute_javascript(tab, self._CLICK_TIMEZONE_BUTTON)
            result = tab.EvaluateJavaScript(self._IS_AUTODETECT_OFF)
            if not result:
                raise error.TestFail('Time zone is back to automatic.')

            if not os.path.exists(self._TEST_FILE):
                raise error.TestFail('Test file is gone after update.')
            utils.run('rm %s' % self._TEST_FILE)


    def run_once(self, update_url=None, after_update=False):
        """
        Tests that user settings are not reset by update.

        @param update_url: The update url to use.
        @param after_update: True for post update checks.

        """
        if after_update:
            self._perform_after_update_checks()
        else:
            with chrome.Chrome(logged_in=True) as cr:
                utils.run('echo hello > %s' % self._TEST_FILE)
                tab = cr.browser.tabs[0]
                self._modify_input_methods(tab)
                self._modify_time_zone(tab)
                self._check_for_update(update_url)
                self._wait_for_progress(0.2)
