# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import random
import urlparse

from autotest_lib.client.common_lib import error
from autotest_lib.server.cros.update_engine import update_engine_test

class autoupdate_Interruptions(update_engine_test.UpdateEngineTest):
    """Ensures an update completes with multiple user interruptions."""
    version = 1

    def cleanup(self):
        self._save_extra_update_engine_logs(4)
        super(autoupdate_Interruptions, self).cleanup()


    def run_once(self, full_payload=True, job_repo_url=None):
        """
        Runs an update with interruptions from the user.

        @param full_payload: True for a full payload. False for delta.
        @param job_repo_url: Used for debugging locally. This is used to figure
                             out the current build and the devserver to use.
                             The test will read this from a host argument
                             when run in the lab.

        """

        update_url = self.get_update_url_for_test(job_repo_url,
                                                  full_payload=full_payload,
                                                  critical_update=True,
                                                  max_updates=2)
        chromeos_version = self._get_chromeos_version()

        # Choose a random downloaded progress to interrupt the update.
        progress = random.uniform(0.1, 0.7)
        logging.info('Progress when we will begin interruptions: %f', progress)

        parsed_url = urlparse.urlparse(update_url)
        server = 'http://%s' % parsed_url.hostname
        # Login, start the update, logout
        self._run_client_test_and_check_result(
            'autoupdate_LoginStartUpdateLogout', server=server,
            port=parsed_url.port, progress_to_complete=progress)
        completed = self._get_update_progress()

        # Reboot the DUT during the update.
        self._host.reboot()
        self._check_for_update(server=server, port=parsed_url.port)
        if self._is_update_finished_downloading():
            raise error.TestError('Reboot interrupt: Update finished '
                                  'downloading before any more interruptions. '
                                  'Started interrupting at: %f' % progress)
        if not self._update_continued_where_it_left_off(completed):
            raise error.TestFail('The update did not continue where it '
                                 'left off before rebooting.')
        completed = self._get_update_progress()

        # Disconnect the network
        self._disconnect_then_reconnect_network(update_url)
        if self._is_update_finished_downloading():
            raise error.TestError('Network interrupt: Update finished '
                                  'downloading before any more interruptions. '
                                  'Started interrupting at: %f' % progress)
        if not self._update_continued_where_it_left_off(completed):
            raise error.TestFail('The update did not continue where it '
                                 'left off before disconnecting network.')
        completed = self._get_update_progress()

        # Suspend / Resume
        self._suspend_then_resume()
        if self._is_update_finished_downloading():
            raise error.TestError('Suspend interrupt: Update finished '
                                  'downloading before any more interruptions. '
                                  'Started interrupting at: %f' % progress)
        if not self._update_continued_where_it_left_off(completed):
            raise error.TestFail('The update did not continue where it '
                                 'left off after suspend/resume.')

        # Add a new user and crash browser.
        self._run_client_test_and_check_result(
            'autoupdate_CrashBrowserAfterUpdate')

        # Update is complete. Reboot the device.
        self._host.reboot()
        self._check_for_update(server=server, port=parsed_url.port)

        # Verify that the update completed successfully by checking hostlog.
        rootfs_hostlog, reboot_hostlog = self._create_hostlog_files()
        self.verify_update_events(chromeos_version, rootfs_hostlog)
        self.verify_update_events(chromeos_version, reboot_hostlog,
                                  chromeos_version)
