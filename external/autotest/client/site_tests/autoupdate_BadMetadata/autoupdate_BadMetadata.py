# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.update_engine import nano_omaha_devserver
from autotest_lib.client.cros.update_engine import update_engine_test

class autoupdate_BadMetadata(update_engine_test.UpdateEngineTest):
    """Tests updates fail when the metadata in the omaha response is invalid."""
    version = 1

    _SHA256_ERROR = 'Updating payload state for error code: 10 (' \
                    'ErrorCode::kPayloadHashMismatchError)'
    _METADATA_SIZE_ERROR = 'Updating payload state for error code: 32 (' \
                           'ErrorCode::kDownloadInvalidMetadataSize)'


    def _setup_bad_metadata_response(self, image_url, image_size, sha256,
                                     metadata_size):
        """
        Sets nano_omaha_devserver to return invalid metadata_size value.

        @param image_url: The payload url.
        @param image_size: The payload size.
        @param sha256: The payloads SHA256 value.
        @param metadata_size: An invalid metadata_size.

        """
        logging.info('Setting up bad metadata response: %s', metadata_size)
        self._omaha.set_image_params(image_url, image_size, sha256,
                                     metadata_size,
                                     public_key=self._IMAGE_PUBLIC_KEY)


    def _setup_bad_sha256_response(self, image_url, image_size, sha256):
        """
        Sets nano_omaha_devserver to return invalid SHA256 value.

        @param image_url: The payload url.
        @param image_size: The payload size.
        @param sha256: An invalid SHA256 value.

        """
        logging.info('Setting up bad SHA256 response: %s', sha256)
        self._omaha.set_image_params(image_url, image_size, sha256)


    def _test_update_fails_as_expected(self, error_string):
        """
        Tests that the update fails.

        @param error_string: The error to look for in update_engine logs.

        """
        self._omaha.start()
        try:
            self._check_for_update(port=self._omaha.get_port(),
                                   wait_for_completion=True)
        except error.CmdError as e:
            logging.error(e)
            self._check_update_engine_log_for_entry(error_string,
                                                    raise_error=True)
            return

        raise error.TestFail('Update completed when it should have failed. '
                             'Check the update_engine log.')


    def run_once(self, image_url, image_size, sha256, metadata_size=None):
        """
        Tests update_engine can deal with invalid data in the omaha response.

        @param image_url: The payload url.
        @param image_size: The payload size.
        @param sha256: The payloads SHA256 value.
        @param metadata_size: The payloads metadata_size.

        """
        self._omaha = nano_omaha_devserver.NanoOmahaDevserver()

        # Setup an omaha response with a bad metadata size.
        if metadata_size is not None:
            self._setup_bad_metadata_response(image_url, image_size, sha256,
                                              metadata_size)
            self._test_update_fails_as_expected(self._METADATA_SIZE_ERROR)
        # Setup an omaha response with a bad SHA256 value.
        else:
            self._setup_bad_sha256_response(image_url, image_size, sha256)
            self._test_update_fails_as_expected(self._SHA256_ERROR)
