# Lint as: python2, python3
# Copyright 2016 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

# repohooks/pre-upload.py currently does not run pylint. But for developers who
# want to check their code manually we disable several harmless pylint warnings
# which just distract from more serious remaining issues.
#
# The instance variable _android_cts is not defined in __init__().
# pylint: disable=attribute-defined-outside-init
#
# Many short variable names don't follow the naming convention.
# pylint: disable=invalid-name

import logging
import os
import subprocess

from autotest_lib.client.bin import utils as client_utils
from autotest_lib.client.common_lib import error
from autotest_lib.server import hosts
from autotest_lib.server import utils
from autotest_lib.server.cros import camerabox_utils
from autotest_lib.server.cros.tradefed import tradefed_test

# Maximum default time allowed for each individual CTS module.
_CTS_TIMEOUT_SECONDS = 3600

_PUBLIC_CTS = 'https://dl.google.com/dl/android/cts/'
_INTERNAL_CTS = 'gs://chromeos-arc-images/cts/bundle/P/'
_PARTNER_CTS = 'gs://chromeos-partner-gts/P/'
_OFFICIAL_ZIP_NAME = 'android-cts-9.0_r20-linux_x86-%s.zip'
_PREVIEW_ZIP_NAME = 'android-cts-8480133-linux_x86-%s.zip'
_BUNDLE_MAP = {
        (None, 'arm'): _PUBLIC_CTS + _OFFICIAL_ZIP_NAME % 'arm',
        (None, 'x86'): _PUBLIC_CTS + _OFFICIAL_ZIP_NAME % 'x86',
        ('DEV_MOBLAB', 'arm'): _PARTNER_CTS + _PREVIEW_ZIP_NAME % 'arm',
        ('DEV_MOBLAB', 'x86'): _PARTNER_CTS + _PREVIEW_ZIP_NAME % 'x86',
        ('LATEST', 'arm'): _INTERNAL_CTS + _OFFICIAL_ZIP_NAME % 'arm',
        ('LATEST', 'x86'): _INTERNAL_CTS + _OFFICIAL_ZIP_NAME % 'x86',
        ('DEV', 'arm'): _INTERNAL_CTS + _PREVIEW_ZIP_NAME % 'arm',
        ('DEV', 'x86'): _INTERNAL_CTS + _PREVIEW_ZIP_NAME % 'x86',
        ('DEV_WAIVER', 'arm'): _INTERNAL_CTS + _PREVIEW_ZIP_NAME % 'arm',
        ('DEV_WAIVER', 'x86'): _INTERNAL_CTS + _PREVIEW_ZIP_NAME % 'x86',
}
_CTS_MEDIA_URI = _PUBLIC_CTS + 'android-cts-media-1.5.zip'
_CTS_MEDIA_LOCALPATH = '/tmp/android-cts-media'


class cheets_CTS_P(tradefed_test.TradefedTest):
    """Sets up tradefed to run CTS tests."""
    version = 1

    _SHARD_CMD = '--shard-count'
    _SCENE_URI = (
            'https://storage.googleapis.com/chromiumos-test-assets-public'
            '/camerabox/cts_portrait_scene.jpg')

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
            # Apply this PATH change only for chroot environment
            if not client_utils.is_moblab():
                try:
                    os.environ['JAVA_HOME'] = '/opt/icedtea-bin-3.4.0'
                    os.environ['PATH'] = os.environ['JAVA_HOME']\
                                       + '/bin:' + os.environ['PATH']
                    logging.info(subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT))
                    # TODO(jiyounha): remove once crbug.com/1105515 is resolved.
                    logging.info(subprocess.check_output(['whereis', 'java'], stderr=subprocess.STDOUT))
                except OSError:
                    logging.error('Can\'t change current PATH directory')

        # Suppress redundant output from tradefed.
        cmd.append('--quiet-output=true')
        return cmd

    def _get_bundle_url(self, uri, bundle):
        if uri and (uri.startswith('http') or uri.startswith('gs')):
            return uri
        else:
            return _BUNDLE_MAP[(uri, bundle)]

    def _get_tradefed_base_dir(self):
        return 'android-cts'

    def _tradefed_cmd_path(self):
        return os.path.join(self._repository, 'tools', 'cts-tradefed')

    def _should_skip_test(self, bundle):
        """Some tests are expected to fail and are skipped."""
        # novato* are x86 VMs without binary translation. Skip the ARM tests.
        no_ARM_ABI_test_boards = ('novato', 'novato-arc64', 'novato-arcnext')
        if self._get_board_name() in no_ARM_ABI_test_boards and bundle == 'arm':
            return True
        return False

    def initialize_camerabox(self, camera_facing, cmdline_args):
        """Configure DUT and chart running in camerabox environment.

        @param camera_facing: the facing of the DUT used in testing
                              (e.g. 'front', 'back').
        """
        chart_address = camerabox_utils.get_chart_address(
            [h.hostname for h in self._hosts], cmdline_args)
        if chart_address is None:
            raise error.TestFail(
                'Error: missing option --args="chart=<CHART IP>"')
        chart_hosts = [hosts.create_host(ip) for ip in chart_address]

        self.chart_fixtures = [
                camerabox_utils.ChartFixture(h, self._SCENE_URI, self.job)
                for h in chart_hosts
        ]
        self.dut_fixtures = [
            camerabox_utils.DUTFixture(self, h, camera_facing)
            for h in self._hosts
        ]

        for chart in self.chart_fixtures:
            chart.initialize()

        for dut in self.dut_fixtures:
            dut.log_camera_scene()
            dut.initialize()

        for host in self._hosts:
            host.run('cras_test_client --mute 1')

    def initialize(self,
                   camera_facing=None,
                   bundle=None,
                   uri=None,
                   host=None,
                   hosts=None,
                   max_retry=None,
                   load_waivers=True,
                   retry_manual_tests=False,
                   warn_on_test_retry=True,
                   cmdline_args=None,
                   hard_reboot_on_failure=False):
        super(cheets_CTS_P, self).initialize(
            bundle=bundle,
            uri=uri,
            host=host,
            hosts=hosts,
            max_retry=max_retry,
            load_waivers=load_waivers,
            retry_manual_tests=retry_manual_tests,
            warn_on_test_retry=warn_on_test_retry,
            hard_reboot_on_failure=hard_reboot_on_failure)
        if camera_facing:
            self.initialize_camerabox(camera_facing, cmdline_args)

    def run_once(self,
                 test_name,
                 run_template,
                 retry_template=None,
                 target_module=None,
                 target_plan=None,
                 needs_push_media=False,
                 enable_default_apps=False,
                 executable_test_count=None,
                 bundle=None,
                 extra_artifacts=[],
                 extra_artifacts_host=[],
                 precondition_commands=[],
                 login_precondition_commands=[],
                 prerequisites=[],
                 timeout=_CTS_TIMEOUT_SECONDS):
        """Runs the specified CTS once, but with several retries.

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
        @param needs_push_media: need to push test media streams.
        @param executable_test_count: the known number of tests in the run
        @param bundle: the type of the CTS bundle: 'arm' or 'x86'
        @param precondition_commands: a list of scripts to be run on the
        dut before the test is run, the scripts must already be installed.
        @param login_precondition_commands: a list of scripts to be run on the
        dut before the log-in for the test is performed.
        @param prerequisites: a list of prerequisites that identify rogue DUTs.
        @param timeout: time after which tradefed can be interrupted.
        """
        self._run_tradefed_with_retries(
            test_name=test_name,
            run_template=run_template,
            retry_template=retry_template,
            timeout=timeout,
            target_module=target_module,
            target_plan=target_plan,
            media_asset=tradefed_test.MediaAsset(
                _CTS_MEDIA_URI if needs_push_media else None,
                _CTS_MEDIA_LOCALPATH),
            enable_default_apps=enable_default_apps,
            executable_test_count=executable_test_count,
            bundle=bundle,
            extra_artifacts=extra_artifacts,
            extra_artifacts_host=extra_artifacts_host,
            login_precondition_commands=login_precondition_commands,
            precondition_commands=precondition_commands,
            prerequisites=prerequisites)

    def cleanup_camerabox(self):
        """Cleanup configuration on DUT and chart tablet for running in

        camerabox environment.
        """
        for dut in self.dut_fixtures:
            dut.cleanup()

        for chart in self.chart_fixtures:
            chart.cleanup()

    def cleanup(self):
        if hasattr(self, 'dut_fixtures'):
            self.cleanup_camerabox()

        super(cheets_CTS_P, self).cleanup()
