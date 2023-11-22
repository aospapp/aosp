# Copyright 2016 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

# repohooks/pre-upload.py currently does not run pylint. But for developers who
# want to check their code manually we disable several harmless pylint warnings
# which just distract from more serious remaining issues.
#
# The instance variable _android_gts is not defined in __init__().
# pylint: disable=attribute-defined-outside-init
#
# Many short variable names don't follow the naming convention.
# pylint: disable=invalid-name

import logging
import os

from autotest_lib.client.common_lib import error
from autotest_lib.server import utils
from autotest_lib.server.cros import tradefed_test

_PARTNER_GTS_LOCATION = 'gs://chromeos-partner-gts/gts-4.1_r2-3911033.zip'


class cheets_GTS(tradefed_test.TradefedTest):
    """Sets up tradefed to run GTS tests."""
    version = 1
    _target_package = None


    def setup(self, uri=None):
        """Set up GTS bundle from Google Storage.

        @param uri: The location to pull the GTS bundle from.
        """

        if uri:
            self._android_gts = self._install_bundle(uri)
        else:
            self._android_gts = self._install_bundle(_PARTNER_GTS_LOCATION)

        self.waivers = self._get_expected_failures('expectations')


    def _get_gts_test_args(self):
        """ This is the command to run GTS tests."""
        args = ['run', 'commandAndExit', 'gts']
        if self._target_package is not None:
            args += ['--module', self._target_package]
        return args


    def _run_gts_tradefed(self, gts_tradefed_args):
        """This tests runs the GTS(XTS) tradefed binary and collects results.

        @raise TestFail: when a test failure is detected.
        """
        gts_tradefed = os.path.join(
                self._android_gts,
                'android-gts',
                'tools',
                'gts-tradefed')
        logging.info('GTS-tradefed path: %s', gts_tradefed)
        # Run GTS via tradefed and obtain stdout, sterr as output.
        with tradefed_test.adb_keepalive(self._get_adb_target(),
                                         self._install_paths):
            output = self._run(
                    gts_tradefed,
                    args=gts_tradefed_args,
                    verbose=True,
                    # Make sure to tee tradefed stdout/stderr to autotest logs
                    # already during the test run.
                    stdout_tee=utils.TEE_TO_LOGS,
                    stderr_tee=utils.TEE_TO_LOGS)
        result_destination = os.path.join(self.resultsdir, 'android-gts')

        # Gather the global log first. Datetime parsing below can abort the test
        # if tradefed startup had failed. Even then the global log is useful.
        self._collect_tradefed_global_log(output, result_destination)

        # Parse stdout to obtain datetime IDs of directories into which tradefed
        # wrote result xml files and logs.
        datetime_id = self._parse_tradefed_datetime_v2(output)
        repository = os.path.join(self._android_gts, 'android-gts')
        self._collect_logs(repository, datetime_id, result_destination)

        # Result parsing must come after all other essential operations as test
        # warnings, errors and failures can be raised here.
        tests, passed, failed, not_executed, waived = self._parse_result_v2(
            output, waivers=self.waivers)
        passed += waived
        failed -= waived
        if tests != passed or failed > 0 or not_executed > 0:
            raise error.TestFail('Failed: Passed (%d), Failed (%d), '
                                 'Not Executed (%d)' %
                                 (passed, failed, not_executed))

        # All test has passed successfully, here.
        logging.info('The test has passed successfully.')

    def run_once(self, target_package=None, gts_tradefed_args=None):
        """Runs GTS target package exactly once.
        @param target_package: the name of test package to be run. If None is
                               set, full GTS set will run.
        """
        self._target_package = target_package
        with self._login_chrome():
            self._ready_arc()
            if not gts_tradefed_args:
                gts_tradefed_args = self._get_gts_test_args()
            self._run_gts_tradefed(gts_tradefed_args)
