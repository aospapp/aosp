# Lint as: python2, python3
# Copyright 2015 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
"""This is a server side external microphone test using the Chameleon board."""

import logging
import os
import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.audio import audio_test_data
from autotest_lib.client.cros.chameleon import audio_test_utils
from autotest_lib.client.cros.chameleon import chameleon_audio_ids
from autotest_lib.client.cros.chameleon import chameleon_audio_helper
from autotest_lib.server.cros.audio import audio_test


class audio_AudioBasicExternalMicrophone(audio_test.AudioTest):
    """Server side external microphone audio test.

    This test talks to a Chameleon board and a Cros device to verify
    external mic audio function of the Cros device.

    """
    version = 1
    DELAY_BEFORE_RECORD_SECONDS = 0.5
    RECORD_SECONDS = 9
    DELAY_AFTER_BINDING = 0.5

    def run_once(self, check_quality=False, blocked_boards=[]):
        """Running basic headphone audio tests.

        @param check_quality: flag to check audio quality.
        @blocked_boards: boards to ignore and exit.

        """
        if self.host.get_board().split(':')[1] in blocked_boards:
            raise error.TestNAError('Board NOT APPLICABLE to test!')
        if not audio_test_utils.has_audio_jack(self.host):
            raise error.TestNAError(
                    'No audio jack for the DUT.'
                    'Please check label of the host and control file.'
                    'Please check the host label and test dependency.')

        golden_file = audio_test_data.GenerateAudioTestData(
                path=os.path.join(self.bindir, 'fix_1330_16.raw'),
                duration_secs=10,
                frequencies=[1330, 1330],
                volume_scale=0.1)

        source = self.widget_factory.create_widget(
                chameleon_audio_ids.ChameleonIds.LINEOUT)
        recorder = self.widget_factory.create_widget(
                chameleon_audio_ids.CrosIds.EXTERNAL_MIC)
        binder = self.widget_factory.create_binder(source, recorder)

        with chameleon_audio_helper.bind_widgets(binder):
            # Checks the node selected by cras is correct.
            time.sleep(self.DELAY_AFTER_BINDING)

            audio_test_utils.dump_cros_audio_logs(
                    self.host, self.facade, self.resultsdir, 'after_binding')

            # Selects and checks the node selected by cras is correct.
            audio_test_utils.check_and_set_chrome_active_node_types(
                    self.facade, None, 'MIC')

            logging.info('Setting playback data on Chameleon')
            source.set_playback_data(golden_file)

            # Starts playing, waits for some time, and then starts recording.
            # This is to avoid artifact caused by chameleon codec initialization
            # in the beginning of playback.
            logging.info('Start playing %s from Chameleon', golden_file.path)
            source.start_playback()

            time.sleep(self.DELAY_BEFORE_RECORD_SECONDS)
            logging.info('Start recording from Cros device.')
            recorder.start_recording()

            time.sleep(self.RECORD_SECONDS)

            recorder.stop_recording()
            logging.info('Stopped recording from Cros device.')

            audio_test_utils.dump_cros_audio_logs(
                    self.host, self.facade, self.resultsdir, 'after_recording')

            recorder.read_recorded_binary()
            logging.info('Read recorded binary from Cros device.')

        recorded_file = os.path.join(self.resultsdir, "recorded.raw")
        logging.info('Saving recorded data to %s', recorded_file)
        recorder.save_file(recorded_file)

        # Removes the beginning of recorded data. This is to avoid artifact
        # caused by Cros device codec initialization in the beginning of
        # recording.
        recorder.remove_head(4.5)

        recorded_file = os.path.join(self.resultsdir, "recorded_clipped.raw")
        logging.info('Saving clipped data to %s', recorded_file)
        recorder.save_file(recorded_file)

        # Compares data by frequency. Audio signal from Chameleon Line-Out to
        # Cros device external microphone has gone through analog processing.
        # This suffers from codec artifacts and noise on the path.
        # Comparing data by frequency is more robust than comparing them by
        # correlation, which is suitable for fully-digital audio path like USB
        # and HDMI.
        audio_test_utils.check_recorded_frequency(
                golden_file,
                recorder,
                check_artifacts=check_quality,
                ignore_frequencies=[50, 60])
