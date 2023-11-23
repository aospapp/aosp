# Copyright 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import time

from autotest_lib.client.cros.enterprise import enterprise_policy_base
from autotest_lib.client.common_lib import error

class policy_ReportUploadFrequency(
        enterprise_policy_base.EnterprisePolicyTest):
    """
    Tests the ReportUploadFrequency policy in Chrome OS.

    """

    version = 1


    def initialize(self, **kwargs):
        super(policy_ReportUploadFrequency, self).initialize(**kwargs)

        self.POLICY_NAME = 'ReportUploadFrequency'
        self.POLICIES = {}
        self.TEST_CASES = {
            '60s': 60000,
        }


    def _check_report_upload_frequency(self, case_value):
        """
        Grep syslog for "Starting status upload: have_device_status = 1" line

        @param case_value: policy value in milliseconds

        """

        # Case_value is in milliseconds, while upload_frequency must be
        # in seconds
        upload_frequency = case_value/1000

        # Wait and check if a status report was sent
        time.sleep(upload_frequency)
        has_status_upload = False

        with open('/var/log/messages') as syslog:
            for ln in syslog:
                if 'Starting status upload: have_device_status = 1' in ln:
                    has_status_upload = True

        if not has_status_upload:
            raise error.TestFail("No status upload was sent.")


    def run_once(self, case):
        """
        Setup and run the test configured for the specified test case.

        @param case: Name of the test case to run.

        """

        case_value = self.TEST_CASES[case]
        self.POLICIES[self.POLICY_NAME] = case_value

        self.setup_case(device_policies=self.POLICIES, enroll=True)
        self._check_report_upload_frequency(case_value)
