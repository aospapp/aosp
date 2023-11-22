# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import math
import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.enterprise import enterprise_au_context
from autotest_lib.client.cros.enterprise import enterprise_policy_base


class policy_DeviceAutoUpdateDisabled(
        enterprise_policy_base.EnterprisePolicyTest):
    """Test for the DeviceAutoUpdateDisabled policy."""
    version = 1
    _POLICY = 'DeviceAutoUpdateDisabled'


    def _test_update_disabled(self, should_update):
        """
        Main test function.

        Try to update and poll for start (or lack of start) to the update.
        Check whether an update request was sent.

        @param should_update: True or False whether the device should update.

        """
        # Log time is only in second accuracy.  Assume no update request has
        # occured since the current whole second started.
        start_time = math.floor(time.time())
        logging.info('Update test start time: %s', start_time)

        try:
            self._au_context.update_and_poll_for_update_start()
        except error.TestFail as e:
            if should_update:
                raise e
        else:
            if not should_update:
                raise error.TestFail('Update started when it should not have!')

        update_time = self._au_context.get_time_of_last_update_request()
        logging.info('Last update time: %s', update_time)

        if should_update and (not update_time or update_time < start_time):
            raise error.TestFail('No update request was sent!')
        if not should_update and update_time and update_time >= start_time:
            raise error.TestFail('Update request was sent!')


    def run_once(self, case, image_url, image_size, sha256, enroll=True):
        """
        Entry point of this test.

        @param case: True, False, or None for the value of the update policy.
        @param image_url: Url of update image (this build).
        @param image_size: Size of the update.
        @param sha256: Sha256 hash of the update.

        """
        self.setup_case(device_policies={self._POLICY: case}, enroll=enroll)

        self._au_context = enterprise_au_context.NanoOmahaEnterpriseAUContext(
                image_url=image_url, image_size=image_size, sha256=sha256)

        # When policy is False or not set, user should update.
        self._test_update_disabled(should_update=case is not True)
