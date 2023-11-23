#!/usr/bin/env python3
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

import json
import os
import time

from acts.base_test import BaseTestClass
from acts.test_utils.bt import BtEnum
from acts.test_utils.bt.bt_test_utils import bluetooth_enabled_check
from acts.test_utils.bt.bt_test_utils import disable_bluetooth
from acts.test_utils.bt.bt_test_utils import enable_bluetooth
from acts.test_utils.bt.bt_test_utils import setup_multiple_devices_for_bt_test
from acts.test_utils.coex.coex_test_utils import A2dpDumpsysParser
from acts.test_utils.coex.audio_test_utils import SshAudioCapture
from acts.test_utils.coex.coex_test_utils import (
    collect_bluetooth_manager_dumpsys_logs)
from acts.test_utils.coex.coex_test_utils import is_a2dp_connected
from acts.test_utils.coex.coex_test_utils import multithread_func
from acts.test_utils.coex.coex_test_utils import pair_and_connect_headset
from acts.test_utils.coex.coex_test_utils import push_music_to_android_device
from acts.utils import create_dir

AVRCP_WAIT_TIME = 5


class BtRangeBaseTest(BaseTestClass):

    def __init__(self, controllers):
        super().__init__(controllers)
        self.pri_ad = self.android_devices[0]

    def setup_class(self):
        if not setup_multiple_devices_for_bt_test(self.android_devices):
            self.log.error("Failed to setup devices for bluetooth test")
            return False
        req_params = ["test_params", "Attenuator"]
        opt_params = ["RelayDevice", "required_devices", "audio_params"]
        self.unpack_userparams(req_params, opt_params)
        if hasattr(self, "Attenuator"):
            self.num_atten = self.attenuators[0].instrument.num_atten
        else:
            self.log.error("Attenuator should be connected to run tests.")
            return False
        if hasattr(self, "RelayDevice"):
            self.audio_receiver = self.relay_devices[0]
            self.headset_mac_address = self.audio_receiver.mac_address
        else:
            self.log.warning("Missing Relay config file.")
        if self.audio_params["music_file"]:
            self.music_file_to_play = push_music_to_android_device(
                self.pri_ad, self.audio_params)
            if not self.music_file_to_play:
                return False
        self.bt_attenuation_range = range(self.test_params["bt_atten_start"],
                                          self.test_params["bt_atten_stop"],
                                          self.test_params["bt_atten_step"])

    def setup_test(self):
        self.result = {}
        self.a2dp_dumpsys = A2dpDumpsysParser()
        self.a2dp_dropped_list = []
        self.log_path = os.path.join(self.pri_ad.log_path,
                                     self.current_test_name)
        create_dir(self.log_path)
        self.json_file = os.path.join(self.log_path, "test_results.json")
        self.attenuators[self.num_atten - 1].set_atten(0)
        if not enable_bluetooth(self.pri_ad.droid, self.pri_ad.ed):
            self.log.error("Failed to enable bluetooth")
            return False
        if hasattr(self, "RelayDevice"):
            self.audio_receiver.power_on()
            self.audio_receiver.enter_pairing_mode()
            time.sleep(5)
        if "a2dp_streaming" in self.current_test_name:
            self.audio = SshAudioCapture(self.audio_params, self.log_path)
        if not pair_and_connect_headset(
                self.pri_ad, self.headset_mac_address,
                set([BtEnum.BluetoothProfile.A2DP.value])):
            self.log.error("Failed to pair and connect to headset")
            return False

    def teardown_test(self):
        if "a2dp_streaming" in self.current_test_name:
            self.audio.terminate_and_store_audio_results()
        with open(self.json_file, 'a') as results_file:
            json.dump(self.result, results_file, indent=4, sort_keys=True)
        self.attenuators[self.num_atten - 1].set_atten(0)
        if not disable_bluetooth(self.pri_ad.droid):
            self.log.error("Failed to disable bluetooth")
            return False

    def set_bt_attenuation(self, called_func):
        """Sets bt attenuation and runs the bluetooth functions."""
        for bt_atten in self.bt_attenuation_range:
            self.attenuators[self.num_atten - 1].set_atten(bt_atten)
            self.log.info("Setting bt attenuation = {}".format(bt_atten))
            self.result[bt_atten] = {}
            if not bluetooth_enabled_check(self.pri_ad):
                return False
            if called_func:
                if not multithread_func(self.log, called_func):
                    if not is_a2dp_connected(self.pri_ad,
                                             self.headset_mac_address):
                        self.log.error(
                            'A2DP Connection dropped at %s attenuation',
                            bt_atten)
                    return False
            if "a2dp_streaming" in self.current_test_name:
                file_path = collect_bluetooth_manager_dumpsys_logs(
                    self.pri_ad, self.current_test_name)
                self.a2dp_dropped_list.append(
                    self.a2dp_dumpsys.parse(file_path))
                if self.a2dp_dropped_list[-1] > 0:
                    self.result[bt_atten]["a2dp_packet_drop"] = (
                        self.a2dp_dropped_list[-1])
                    self.log.info("A2dp packets dropped = {}".format(
                        self.a2dp_dropped_list))
                analysis_path = self.audio.audio_quality_analysis(self.log_path)
                with open(analysis_path) as f:
                    self.result[bt_atten]["audio_artifacts"] = f.readline()
        return True
