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
import shutil
import tempfile

from autotest_lib.server import utils
from autotest_lib.server.cros import tradefed_test

# Maximum default time allowed for each individual GTS module.
_GTS_TIMEOUT_SECONDS = 3600
_PARTNER_GTS_BUCKET = 'gs://chromeos-partner-gts/'
_PARTNER_GTS_LOCATION = _PARTNER_GTS_BUCKET + 'gts-6.0_r4-5356336.zip'
_PARTNER_GTS_AUTHKEY = _PARTNER_GTS_BUCKET + 'gts-arc.json'


class cheets_GTS(tradefed_test.TradefedTest):
    """Sets up tradefed to run GTS tests."""
    version = 1

    _SHARD_CMD = '--shard-count'

    def _tradefed_retry_command(self, template, session_id):
        """Build tradefed 'retry' command from template."""
        cmd = []
        for arg in template:
            cmd.append(arg.format(session_id=session_id))
        return cmd

    def _tradefed_run_command(self, template):
        """Build tradefed 'run' command from template."""
        cmd = template[:]
        # If we are running outside of the lab we can collect more data.
        if not utils.is_in_container():
            logging.info('Running outside of lab, adding extra debug options.')
            cmd.append('--log-level-display=DEBUG')
        return cmd

    def _get_default_bundle_url(self, bundle):
        return _PARTNER_GTS_LOCATION

    def _get_default_authkey(self):
        return _PARTNER_GTS_AUTHKEY

    def _get_tradefed_base_dir(self):
        return 'android-gts'

    def _run_tradefed(self, commands):
        """Kick off GTS.

        @param commands: the command(s) to pass to GTS.
        @return: The result object from utils.run.
        """
        gts_tradefed = os.path.join(self._repository, 'tools', 'gts-tradefed')
        env = None
        if self._authkey:
            env = dict(os.environ, APE_API_KEY=self._authkey)
        with tradefed_test.adb_keepalive(self._get_adb_targets(),
                                         self._install_paths):
            for command in commands:
                timeout = self._timeout * self._timeout_factor
                logging.info('RUN(timeout=%d): ./gts-tradefed %s', timeout,
                             ' '.join(command))
                output = self._run(
                    gts_tradefed,
                    args=tuple(command),
                    env=env,
                    timeout=timeout,
                    verbose=True,
                    ignore_status=False,
                    # Make sure to tee tradefed stdout/stderr to autotest logs
                    # continuously during the test run.
                    stdout_tee=utils.TEE_TO_LOGS,
                    stderr_tee=utils.TEE_TO_LOGS,
                    # Also send the output to the test_that console.
                    stdout_level=logging.INFO)
                logging.info('END: ./gts-tradefed %s\n', ' '.join(command))
        return output

    def run_once(self,
                 test_name,
                 run_template,
                 retry_template=None,
                 target_module=None,
                 target_plan=None,
                 target_class=None,
                 target_method=None,
                 needs_push_media=False,
                 precondition_commands=[],
                 login_precondition_commands=[],
                 authkey=None,
                 timeout=_GTS_TIMEOUT_SECONDS):
        """Runs the specified GTS once, but with several retries.

        Run an arbitrary tradefed command.

        @param test_name: the name of test. Used for logging.
        @param run_template: the template to construct the run command.
                             Example: ['run', 'commandAndExit', 'cts',
                                       '--skip-media-download']
        @param retry_template: the template to construct the retry command.
                               Example: ['run', 'commandAndExit', 'retry',
                                         '--skip-media-download', '--retry',
                                         '{session_id}']
        @param target_module: the name of test module to run.
        @param target_plan: the name of the test plan to run.
        @param target_class: the name of the class to be tested.
        @param target_method: the name of the method to be tested.
        @param needs_push_media: need to push test media streams.
        @param timeout: time after which tradefed can be interrupted.
        @param precondition_commands: a list of scripts to be run on the
        dut before the test is run, the scripts must already be installed.
        @param login_precondition_commands: a list of scripts to be run on the
        dut before the log-in for the test is performed.
        """
        # Download the GTS auth key to the local temp directory.
        tmpdir = tempfile.mkdtemp()
        try:
            self._authkey = self._download_to_dir(
                authkey or self._get_default_authkey(), tmpdir)

            self._run_tradefed_with_retries(
                test_name=test_name,
                run_template=run_template,
                retry_template=retry_template,
                timeout=timeout,
                target_module=target_module,
                target_plan=target_plan,
                needs_push_media=needs_push_media,
                login_precondition_commands=login_precondition_commands,
                precondition_commands=precondition_commands)
        finally:
            shutil.rmtree(tmpdir)
