# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from autotest_lib.server.cros.update_engine import update_engine_test


class autoupdate_DataPreserved(update_engine_test.UpdateEngineTest):
    """Ensure user data and preferences are preserved during an update."""
    version = 1

    def cleanup(self):
        # Get the last two update_engine logs: before and after reboot.
        files = self._host.run('ls -t -1 %s' %
                               self._UPDATE_ENGINE_LOG_DIR).stdout.splitlines()
        for i in range(2):
            self._host.get_file('%s%s' % (self._UPDATE_ENGINE_LOG_DIR,
                                          files[i]), self.resultsdir)
        super(autoupdate_DataPreserved, self).cleanup()


    def run_once(self, host, full_payload=True, job_repo_url=None):
        """
        Tests that users timezone, input methods, and downloads are preserved
        during an update.

        @param host: The DUT that we are running on.
        @param full_payload: True for a full payload. False for delta.
        @param job_repo_url: Used for debugging locally. This is used to figure
                             out the current build and the devserver to use.
                             The test will read this from a host argument
                             when run in the lab.

        """
        self._host = host

        update_url = self.get_update_url_for_test(job_repo_url,
                                                  full_payload=full_payload,
                                                  critical_update=True)
        logging.info('Update url: %s', update_url)

        # Change input method and timezone, create a file, then start update.
        self._run_client_test_and_check_result('autoupdate_UserData',
                                               update_url=update_url)
        self._wait_for_update_to_complete()
        self._host.reboot()

        # Ensure preferences and downloads are the same as before the update.
        self._run_client_test_and_check_result('autoupdate_UserData',
                                               update_url=update_url,
                                               after_update=True)
