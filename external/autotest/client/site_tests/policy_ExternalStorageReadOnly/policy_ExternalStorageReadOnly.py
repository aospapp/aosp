# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.enterprise import enterprise_policy_base


class policy_ExternalStorageReadOnly(
        enterprise_policy_base.EnterprisePolicyTest):
    version = 1

    POLICY_NAME = 'ExternalStorageReadOnly'
    TEST_CASES = {
        'True_Block': True,
        'False_Allow': False,
        'NotSet_Allow': None
    }

    TEST_FILE = os.path.join(os.sep, 'media', 'removable', 'STATE', 'test')

    def cleanup(self):
        """Delete the test file, if it was created."""
        try:
            os.remove(self.TEST_FILE)
        except OSError:
            # The remove call fails if the file isn't created, but that's ok.
            pass

        super(policy_ExternalStorageReadOnly, self).cleanup()


    def _test_external_storage(self, policy_value):
        """
        Verify the behavior of the ExternalStorageReadOnly policy.

        Attempt to create TEST_FILE on the external storage. This should fail
        if the policy is set to True and succeed otherwise.

        @param policy_value: policy value for this case.

        @raises error.TestFail: If the permissions of the /media/removable
            directory do not match the policy behavior.

        """
        # Attempt to modify the external storage by creating a file.
        try:
            return_code = utils.run('touch %s' % self.TEST_FILE)
        except error.CmdError:
            if not policy_value:
                raise error.TestFail('External storage not readonly but '
                                     'unable to write to storage')
        else:
            if policy_value:
                raise error.TestFail('External storage was readonly but '
                                     'external storage was modified')


    def run_once(self, case):
        """
        Setup and run the test configured for the specified test case.

        @param case: Name of the test case to run.

        """
        case_value = self.TEST_CASES[case]
        self.setup_case(user_policies={self.POLICY_NAME: case_value})
        self._test_external_storage(case_value)
