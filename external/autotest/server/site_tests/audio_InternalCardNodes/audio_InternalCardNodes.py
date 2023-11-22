# Copyright 2015 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""This is a server side test to check nodes created for internal card."""

import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.chameleon import audio_test_utils
from autotest_lib.server.cros.audio import audio_test
from autotest_lib.client.cros.audio import cras_configs
from autotest_lib.client.cros.audio import audio_spec
from autotest_lib.server.cros.multimedia import remote_facade_factory


class audio_InternalCardNodes(audio_test.AudioTest):
    """Server side test to check audio nodes for internal card.

    This test talks to a Chameleon board and a Cros device to verify
    audio nodes created for internal cards are correct.

    """
    version = 1
    DELAY_AFTER_PLUGGING = 2
    DELAY_AFTER_UNPLUGGING = 2

    def run_once(self, host):
        chameleon_board = host.chameleon
        factory = remote_facade_factory.RemoteFacadeFactory(
                host, results_dir=self.resultsdir)
        audio_facade = factory.create_audio_facade()

        chameleon_board.setup_and_reset(self.outputdir)

        jack_plugger = chameleon_board.get_audio_board().get_jack_plugger()

        expected_plugged_nodes_without_audio_jack = (
                [],
                ['POST_DSP_LOOPBACK',
                 'POST_MIX_LOOPBACK'])

        # 'Headphone' or 'LINEOUT' will be added to expected list after jack
        # is plugged.
        expected_plugged_nodes_with_audio_jack = (
                [],
                ['MIC', 'POST_DSP_LOOPBACK',
                 'POST_MIX_LOOPBACK'])

        # Modify expected nodes for special boards.
        board_name = host.get_board().split(':')[1]
        model_name = host.get_platform()

        if audio_test_utils.has_internal_speaker(host):
            expected_plugged_nodes_without_audio_jack[0].append(
                    'INTERNAL_SPEAKER')
            expected_plugged_nodes_with_audio_jack[0].append(
                    'INTERNAL_SPEAKER')

        if audio_test_utils.has_internal_microphone(host):
            expected_internal_mics = cras_configs.get_plugged_internal_mics(
                    board_name, model_name)
            expected_plugged_nodes_without_audio_jack[1].extend(
                    expected_internal_mics)
            expected_plugged_nodes_with_audio_jack[1].extend(
                    expected_internal_mics)

        if board_name == 'link':
            expected_plugged_nodes_without_audio_jack[1].append('KEYBOARD_MIC')
            expected_plugged_nodes_with_audio_jack[1].append('KEYBOARD_MIC')

        if audio_spec.has_hotwording(board_name, model_name):
            expected_plugged_nodes_without_audio_jack[1].append('HOTWORD')
            expected_plugged_nodes_with_audio_jack[1].append('HOTWORD')

        # If there is no jack plugger, check the nodes without plugging.
        host_info = host.host_info_store.get()
        if jack_plugger is None:
            if 'audio_box' in host_info.labels:
                raise error.TestError("Failed to detect jack plugger.")
            hp_jack_node_type = audio_test_utils.check_hp_or_lineout_plugged(
                    audio_facade)
            expected_plugged_nodes_with_audio_jack[0].append(hp_jack_node_type)

            audio_test_utils.check_plugged_nodes(
                    audio_facade, expected_plugged_nodes_with_audio_jack)
            return

        audio_test_utils.check_plugged_nodes(
                audio_facade, expected_plugged_nodes_without_audio_jack)

        try:
            jack_plugger.plug()
            time.sleep(self.DELAY_AFTER_PLUGGING)

            audio_test_utils.dump_cros_audio_logs(
                    host, audio_facade, self.resultsdir)

            # Checks whether line-out or headphone is detected.
            hp_jack_node_type = audio_test_utils.check_hp_or_lineout_plugged(
                    audio_facade)
            expected_plugged_nodes_with_audio_jack[0].append(hp_jack_node_type)

            audio_test_utils.check_plugged_nodes(
                    audio_facade, expected_plugged_nodes_with_audio_jack)

        finally:
            jack_plugger.unplug()
            time.sleep(self.DELAY_AFTER_UNPLUGGING)

        audio_test_utils.check_plugged_nodes(
                audio_facade, expected_plugged_nodes_without_audio_jack)
