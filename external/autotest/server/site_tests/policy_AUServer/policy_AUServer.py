# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from autotest_lib.client.common_lib.cros import tpm_utils
from autotest_lib.server.cros.update_engine import update_engine_test


class policy_AUServer(update_engine_test.UpdateEngineTest):
    """
    This server test is used just to get the URL of the payload to use. It
    will then call into a client side test to test different things in
    the Omaha response.
    """
    version = 1

    def clear_tpm_if_owned(self):
        """Clear the TPM only if device is already owned."""
        tpm_status = tpm_utils.TPMStatus(self._host)
        logging.info('TPM status: %s', tpm_status)
        if tpm_status['Owned']:
            logging.info('Clearing TPM because this device is owned.')
            tpm_utils.ClearTPMOwnerRequest(self._host)


    def cleanup(self):
        """Cleanup for this test."""
        super(policy_AUServer, self).cleanup()
        self.clear_tpm_if_owned()
        self._host.reboot()


    def run_once(self, client_test, case, full_payload=True,
                 job_repo_url=None, running_at_desk=False):
        """
        Starting point of this test.

        Note: base class sets host as self._host.

        @param client_test: the name of the Client test to run.
        @param case: the case to run for the given Client test.
        @param full_payload: whether the update should be full or incremental.
        @param job_repo_url: url provided at runtime (or passed in locally
                             when running at workstation).
        @param running_at_desk: indicates test is run from a workstation.

        """
        self._job_repo_url = job_repo_url

        # Clear TPM to ensure that client test can enroll device.
        self.clear_tpm_if_owned()

        # Figure out the payload to use for the current build.
        payload = self._get_payload_url(full_payload=full_payload)
        image_url = self._stage_payload_by_uri(payload)
        file_info = self._get_staged_file_info(image_url)

        if running_at_desk:
            image_url = self._copy_payload_to_public_bucket(payload)
            logging.info('We are running from a workstation. Putting URL on a '
                         'public location: %s', image_url)

        logging.info('url: %s', image_url)
        logging.info('file_info: %s', file_info)

        self._run_client_test_and_check_result(client_test,
                                               case=case,
                                               image_url=image_url,
                                               image_size=file_info['size'],
                                               sha256=file_info['sha256'])
