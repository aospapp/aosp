# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os
import utils

from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.audio import audio_helper
from autotest_lib.client.cros.audio import cmd_utils
from autotest_lib.client.cros.audio import cras_utils
from autotest_lib.client.cros.enterprise import enterprise_policy_base
from autotest_lib.client.cros.input_playback import input_playback


class policy_AudioOutputAllowed(
        enterprise_policy_base.EnterprisePolicyTest):
    version = 1

    POLICY_NAME = 'AudioOutputAllowed'
    # How long (sec) to capture output for
    SAMPLE_DURATION = 1

    TEST_CASES = {
        'NotSet_Allow': None,
        'True_Allow': True,
        'False_Block': False
    }

    def initialize(self, **kwargs):
        """Initialize objects for test."""
        super(policy_AudioOutputAllowed, self).initialize(**kwargs)
        audio_helper.cras_rms_test_setup()

    def wait_for_active_stream_count(self, expected_count):
        """
        Waits for there to be the expected number of audio streams.

        @param expected_count: Number of audio streams to wait for.

        @raises error.TestError: if there is a timeout before the there is the
        desired number of audio streams.

        """
        utils.poll_for_condition(
            lambda: cras_utils.get_active_stream_count() == expected_count,
            exception=error.TestError(
                'Timeout waiting active stream count to become %d' %
                 expected_count))


    def is_muted(self):
        """
        Returns mute status of system.

        @returns: True if system muted, False if not.

        """
        MUTE_STATUS = 'Muted'
        CTC_GREP_FOR_MUTED = 'cras_test_client --dump_server_info | grep muted'

        output = utils.system_output(CTC_GREP_FOR_MUTED)
        muted = output.split(':')[-1].strip()
        return muted == MUTE_STATUS


    def _test_audio_disabled(self, policy_value):
        """
        Verify the AudioOutputAllowed policy behaves as expected.

        Generate and play a sample audio file. When enabled, the difference
        between the muted and unmuted RMS should be greater than 0.75. When
        disabled, the RMS difference should be less than 0.05.

        @param policy_value: policy value for this case.

        @raises error.TestFail: In the case where the audio behavior
            does not match the policy value.

        """
        audio_allowed = policy_value or policy_value is None

        RAW_FILE = os.path.join(self.enterprise_dir, 'test_audio.raw')
        noise_file = os.path.join(self.resultsdir, 'noise.wav')
        recorded_file = os.path.join(self.resultsdir, 'recorded-cras.raw')
        recorded_rms = []

        # Record a sample of silence to use as a noise profile.
        cras_utils.capture(noise_file, duration=2)
        logging.info('NOISE: %s', audio_helper.get_rms(noise_file))

        # Get two RMS samples: one when muted and one when not
        for muted in [False, True]:
            cras_utils.set_system_mute(muted)

            # Play the audio file and capture the output
            self.wait_for_active_stream_count(0)
            p = cmd_utils.popen(cras_utils.playback_cmd(RAW_FILE))
            try:
                self.wait_for_active_stream_count(1)
                cras_utils.capture(recorded_file, duration=self.SAMPLE_DURATION)

                if p.poll() is not None:
                    raise error.TestError('Audio playback stopped prematurely')
            finally:
                cmd_utils.kill_or_log_returncode(p)

            rms_value = audio_helper.reduce_noise_and_get_rms(
                recorded_file, noise_file)[0]

            logging.info('muted (%s): %s' % (muted, rms_value))
            recorded_rms.append(rms_value)

        rms_diff = recorded_rms[0] - recorded_rms[1]
        self.write_perf_keyval({'rms_diff': rms_diff})

        if audio_allowed:
            if rms_diff < 0.4:
                raise error.TestFail('RMS difference not large enough between '
                                     'mute and ummute: %s' % rms_diff)
        else:
            if abs(rms_diff) > 0.05:
                raise error.TestFail('RMS difference too wide while audio '
                                     'disabled: %s' % rms_diff)


    def _test_unmute_disabled(self, policy_value):
        """
        Verify AudioOutputAllowed does not allow unmuting when disabled.

        Attempt to unmute the system with CRAS and check the system state
        after.

        @param policy_value: policy value for this case.

        @raises error.TestFail: In the case where the audio behavior
            does not match the policy value.

        """
        audio_allowed = policy_value or policy_value is None

        cras_utils.set_system_mute(False)

        if not audio_allowed and not self.is_muted():
            raise error.TestFail('System should be muted, but is not')
        elif audio_allowed and self.is_muted():
            raise error.TestFail('System is muted but should not be')


    def run_once(self, case):
        """
        Setup and run the test configured for the specified test case.

        @param case: Name of the test case to run.

        """
        case_value = self.TEST_CASES[case]
        self.setup_case(user_policies={self.POLICY_NAME: case_value})
        self._test_audio_disabled(case_value)
        self._test_unmute_disabled(case_value)
