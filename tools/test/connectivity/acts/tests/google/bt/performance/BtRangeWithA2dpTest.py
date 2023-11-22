# /usr/bin/env python3
#
# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.

from acts.test_utils.bt.bt_test_utils import clear_bonded_devices
from acts.test_utils.bt.BtRangeBaseTest import BtRangeBaseTest
from acts.test_utils.coex.coex_test_utils import music_play_and_check
from acts.test_utils.coex.coex_test_utils import avrcp_actions


class BtRangeWithA2dpTest(BtRangeBaseTest):

    def __init__(self, controllers):
        super().__init__(controllers)

    def teardown_test(self):
        clear_bonded_devices(self.pri_ad)
        super().teardown_test()

    def initiate_music_streaming_to_headset(self):
        """Initiate music streaming to headset and capture the audio."""
        tasks = [(self.audio.capture_audio, ()),
                 (music_play_and_check, (self.pri_ad,
                        self.headset_mac_address,
                        self.music_file_to_play,
                        self.audio_params["music_play_time"]))]
        return self.set_bt_attenuation(tasks)

    def music_streaming_and_avrcp_controls(self):
        """Initiate music streaming and check for avrcp controls."""
        tasks = [(self.audio.capture_audio, ()),
                 (music_play_and_check,
                  (self.pri_ad, self.headset_mac_address,
                   self.music_file_to_play,
                   self.audio_params["music_play_time"])),
                 (avrcp_actions, (self.pri_ad, self.audio_receiver))]
        return self.set_bt_attenuation(tasks)

    def test_range_for_a2dp_streaming(self):
        """Range test to start streaming music.
        This test is to start music streaming to a2dp headset.

        Steps:
        1. Start music streaming to a2dp headset.

        Returns:
            True if successful, False otherwise.
        """
        return self.initiate_music_streaming_to_headset()

    def test_range_for_a2dp_streaming_with_avrcp_controls(self):
        """Range test to start music streaming with avrcp controls.
        This test is to start a2dp music streaming and avrcp controls.

        Steps:
        1. Start media streaming to a2dp headset.
        2. Check all avrcp related controls.

        Returns:
            True if successful, False otherwise.
        """
        return self.music_streaming_and_avrcp_controls()
