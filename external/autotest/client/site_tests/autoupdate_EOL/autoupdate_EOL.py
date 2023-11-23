# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chrome
from autotest_lib.client.cros.update_engine import nano_omaha_devserver
from autotest_lib.client.cros.update_engine import update_engine_test

class autoupdate_EOL(update_engine_test.UpdateEngineTest):
    """Tests end of life (EOL) behaviour."""
    version = 1

    _EXPECTED_EOL_STATUS = 'EOL_STATUS=eol'
    _EOL_NOTIFICATION_TITLE = 'This device is no longer supported'

    def cleanup(self):
        self._save_extra_update_engine_logs()
        super(autoupdate_EOL, self).cleanup()


    def _check_eol_status(self):
        """Checks update_engines eol status."""
        result = utils.run('update_engine_client --eol_status').stdout.strip()
        if result != self._EXPECTED_EOL_STATUS:
            raise error.TestFail('Expected status %s. Actual: %s' %
                                 (self._EXPECTED_EOL_STATUS, result))


    def _check_eol_notification(self):
        """Checks that we are showing an EOL notification to the user."""
        with chrome.Chrome(autotest_ext=True, logged_in=True) as cr:
            def find_notification():
                notifications = cr.get_visible_notifications()
                if notifications is None:
                    return False
                else:
                    logging.debug(notifications)
                    for n in notifications:
                        if n['title'] == self._EOL_NOTIFICATION_TITLE:
                            return True

            utils.poll_for_condition(condition=lambda: find_notification(),
                                     desc='Notification is found',
                                     timeout=5,
                                     sleep_interval=1)


    def run_once(self):
        # Start a devserver to return a response with eol entry.
        self._omaha = nano_omaha_devserver.NanoOmahaDevserver(eol=True)
        self._omaha.start()

        # Try to update using the omaha server. It will fail with noupdate.
        self._check_for_update(port=self._omaha.get_port(), ignore_status=True,
                               wait_for_completion=True)

        self._check_eol_status()
        self._check_eol_notification()
