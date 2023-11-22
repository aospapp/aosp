# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from autotest_lib.client.cros.update_engine import nano_omaha_devserver
from autotest_lib.client.cros.update_engine import update_engine_test

class autoupdate_UrlSwitch(update_engine_test.UpdateEngineTest):
    """Tests that we can continue with the second url when the first fails."""
    version = 1

    def run_once(self, image_url, image_size, sha256):
        # Start an omaha instance on the DUT that will return a response with
        # two Urls. This matches what test and production omaha does today.
        self._omaha = nano_omaha_devserver.NanoOmahaDevserver()
        self._omaha.set_image_params(image_url, image_size, sha256)
        self._omaha.start()

        # Start the update.
        self._check_for_update(port=self._omaha.get_port())
        self._wait_for_progress(0.2)

        # Pull the network cable so the update fails.
        self._disable_internet()

        # It will retry 21 times before giving up.
        self._wait_for_update_to_fail()

        # Check that we are moving to the next Url.
        self._enable_internet()
        self._check_update_engine_log_for_entry('Reached max number of '
                                                'failures for Url')

        # The next update attempt should resume and finish successfully.
        self._check_for_update(port=self._omaha.get_port())
        self._wait_for_update_to_complete()
        self._check_update_engine_log_for_entry('Resuming an update that was '
                                                'previously started.')
