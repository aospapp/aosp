#!/usr/bin/env python3
#
# Copyright (C) 2019 The Android Open Source Project
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
"""Stream music through connected device from phone across different
attenuation."""

import time
from acts import asserts
from acts.signals import TestPass
from acts.test_utils.bt.A2dpCodecBaseTest import A2dpCodecBaseTest
from acts.test_utils.bt.bt_test_utils import set_bluetooth_codec

DEFAULT_THDN_THRESHOLD = 0.9
HEADSET_CONTROL_SLEEP_TIME = 10
PHONE_BT_ENABLE_WAITING_TIME = 10

class BtRangeCodecTest(A2dpCodecBaseTest):
    def __init__(self, configs):
        super().__init__(configs)
        self.attenuator = self.attenuators[0]
        req_params = [
            'bt_atten_start', 'bt_atten_stop',
            'bt_atten_step', 'codecs',
        ]
        opt_params = ['RelayDevice', 'required_devices', 'audio_params']
        self.unpack_userparams(req_params, opt_params)

        for codec_config in self.codecs:
            self.generate_test_case(codec_config)

    def generate_test_case(self, codec_config):
        def test_case_fn():
            self.stream_music_on_codec_vs_atten(codec_config)

        test_case_name = 'test_streaming_{}'.format('_'.join(
            str(codec_config[key])
            for key in sorted(codec_config.keys(), reverse=True)
        ))
        setattr(self, test_case_name, test_case_fn)

    def setup_test(self):
        self.attenuator.set_atten(0)

        # let phone undiscoverable before headset power cycle
        self.android.droid.bluetoothMakeUndiscoverable()

        # power cycle headset
        self.log.info('power down headset')
        self.bt_device.power_off()
        time.sleep(HEADSET_CONTROL_SLEEP_TIME)
        self.bt_device.power_on()
        self.log.info('headset is powered on')

        # enable phone BT discoverability after headset paging sequence is done
        # to keep phone at master role
        time.sleep(PHONE_BT_ENABLE_WAITING_TIME)
        self.log.info('Make phone BT in connectable mode')
        self.android.droid.bluetoothMakeConnectable()
        super().setup_test()

    def teardown_test(self):
        super().teardown_test()
        self.bt_device.power_off()
        # after the test, reset the attenuation
        self.attenuator.set_atten(0)

    def stream_music_on_codec_vs_atten(self, codec_config):
        attenuation_range = range(self.bt_atten_start,
                                       self.bt_atten_stop + 1,
                                       self.bt_atten_step)

        results = []

        codec_set = set_bluetooth_codec(self.android, **codec_config)
        asserts.assert_true(codec_set, 'Codec configuration failed.',
                            extras=self.metrics)

        #loop RSSI with the same codec setting
        for atten in attenuation_range:
            self.attenuator.set_atten(atten)
            self.log.info('atten %d', atten)

            self.play_and_record_audio()

            thdns = self.run_thdn_analysis()
            results.append(thdns)
            self.log.info('attenuation is %d', atten)
            self.log.info('THD+N result is %s', str(results[-1]))

            for thdn in thdns:
               if thdn >= self.user_params.get('thdn_threshold',
                                                DEFAULT_THDN_THRESHOLD):
                   self.log.info(
                        'stop increasing attenuation and '
                        'get into next codec test. THD+N=, %s', str(thdn)
                   )
                   raise TestPass(
                         'test run through attenuations before audio is broken.'
                         'Successfully recorded and analyzed audio.',
                         extras=self.metrics)

        raise TestPass(
            'test run through all attenuations.'
            'Successfully recorded and analyzed audio.',
            extras=self.metrics)
