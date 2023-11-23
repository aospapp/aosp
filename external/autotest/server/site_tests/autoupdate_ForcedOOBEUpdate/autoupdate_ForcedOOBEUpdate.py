# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import random
import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import tpm_utils
from autotest_lib.server.cros.update_engine import update_engine_test

class autoupdate_ForcedOOBEUpdate(update_engine_test.UpdateEngineTest):
    """Runs a forced autoupdate during OOBE."""
    version = 1


    def cleanup(self):
        self._host.run('rm %s' % self._CUSTOM_LSB_RELEASE, ignore_status=True)

        # Get the last two update_engine logs: before and after reboot.
        self._save_extra_update_engine_logs()
        self._change_cellular_setting_in_update_engine(False)

        # Cancel any update still in progress.
        if not self._is_update_engine_idle():
            logging.debug('Canceling the in-progress update.')
            self._host.run('restart update-engine')
        super(autoupdate_ForcedOOBEUpdate, self).cleanup()


    def _wait_for_oobe_update_to_complete(self):
        """Wait for the update that started to complete.

        Repeated check status of update. It should move from DOWNLOADING to
        FINALIZING to COMPLETE (then reboot) to IDLE.
        """
        # 20 minute timeout.
        timeout_minutes = 20
        timeout = time.time() + 60 * timeout_minutes
        while True:
            status = self._get_update_engine_status(timeout=10)

            # During reboot, status will be None
            if status is not None:
                if self._UPDATE_STATUS_IDLE == status[self._CURRENT_OP]:
                    break
            time.sleep(1)
            if time.time() > timeout:
                raise error.TestFail('OOBE update did not finish in %d '
                                     'minutes.' % timeout_minutes)


    def run_once(self, full_payload=True, cellular=False,
                 interrupt=False, max_updates=1, job_repo_url=None):
        """
        Runs a forced autoupdate during ChromeOS OOBE.

        @param full_payload: True for a full payload. False for delta.
        @param cellular: True to do the update over a cellualar connection.
                         Requires that the DUT have a sim card slot.
        @param interrupt: True to interrupt the update in the middle.
        @param max_updates: Used to tell the test how many times it is
                            expected to ping its omaha server.
        @param job_repo_url: Used for debugging locally. This is used to figure
                             out the current build and the devserver to use.
                             The test will read this from a host argument
                             when run in the lab.

        """
        tpm_utils.ClearTPMOwnerRequest(self._host)

        # veyron_rialto is a medical device with a different OOBE that auto
        # completes so this test is not valid on that device.
        if 'veyron_rialto' in self._host.get_board():
            raise error.TestNAError('Rialto has a custom OOBE. Skipping test.')

        update_url = self.get_update_url_for_test(job_repo_url,
                                                  full_payload=full_payload,
                                                  critical_update=True,
                                                  public=cellular,
                                                  max_updates=max_updates)
        before = self._get_chromeos_version()
        payload_info = None
        if cellular:
            self._change_cellular_setting_in_update_engine(True)
            # Get the payload's information (size, SHA256 etc) since we will be
            # setting up our own omaha instance on the DUT. We pass this to
            # the client test.
            payload = self._get_payload_url(full_payload=full_payload)
            staged_url = self._stage_payload_by_uri(payload)
            payload_info = self._get_staged_file_info(staged_url)

        # Call client test to start the forced OOBE update.
        self._run_client_test_and_check_result('autoupdate_StartOOBEUpdate',
                                               image_url=update_url,
                                               cellular=cellular,
                                               payload_info=payload_info,
                                               full_payload=full_payload)


        if interrupt:
            # Choose a random downloaded progress to interrupt the update.
            progress = random.uniform(0.1, 0.8)
            logging.debug('Progress when we will interrupt: %f', progress)
            self._wait_for_progress(progress)
            logging.info('We will start interrupting the update.')

            # Reboot the DUT during the update.
            self._take_screenshot('before_reboot.png')
            completed = self._get_update_progress()
            self._host.reboot()
            # Screenshot to check that if OOBE was not skipped by interruption.
            self._take_screenshot('after_reboot.png')
            if self._is_update_finished_downloading():
                raise error.TestError('Reboot interrupt: Update finished '
                                      'downloading before any more '
                                      'interruptions. Started interrupting '
                                      'at: %f' % progress)
            if self._is_update_engine_idle():
                raise error.TestFail('The update was IDLE after reboot.')

            # Disconnect / Reconnect network.
            completed = self._get_update_progress()
            self._disconnect_then_reconnect_network(update_url)
            self._take_screenshot('after_network.png')
            if self._is_update_finished_downloading():
                raise error.TestError('Network interrupt: Update finished '
                                      'downloading before any more '
                                      'interruptions. Started interrupting '
                                      'at: %f' % progress)
            if not self._update_continued_where_it_left_off(completed):
                raise error.TestFail('The update did not continue where it '
                                     'left off before disconnecting network.')

            # Suspend / Resume.
            completed = self._get_update_progress()
            self._suspend_then_resume()
            self._take_screenshot('after_suspend.png')
            if self._is_update_finished_downloading():
                raise error.TestError('Suspend interrupt: Update finished '
                                      'downloading before any more '
                                      'interruptions. Started interrupting '
                                      'at: %f' % progress)
            if not self._update_continued_where_it_left_off(completed):
                raise error.TestFail('The update did not continue where it '
                                     'left off after suspend/resume.')

        self._wait_for_oobe_update_to_complete()

        if cellular:
            # We didn't have a devserver so we cannot check the hostlog to
            # ensure the update completed successfully. Instead we can check
            # that the second-to-last update engine log has the successful
            # update message. Second to last because its the one before OOBE
            # rebooted.
            before_reboot_file = self._get_second_last_update_engine_log()
            self._check_for_cellular_entries_in_update_log(before_reboot_file)
            success = 'Update successfully applied, waiting to reboot.'
            self._check_update_engine_log_for_entry(success,
                                                    raise_error=True,
                                                    update_engine_log=
                                                    before_reboot_file)
            return

        # Verify that the update completed successfully by checking hostlog.
        rootfs_hostlog, reboot_hostlog = self._create_hostlog_files()
        self.verify_update_events(self._CUSTOM_LSB_VERSION, rootfs_hostlog)
        self.verify_update_events(self._CUSTOM_LSB_VERSION, reboot_hostlog,
                                  self._CUSTOM_LSB_VERSION)

        after = self._get_chromeos_version()
        logging.info('Successfully force updated from %s to %s.', before, after)
