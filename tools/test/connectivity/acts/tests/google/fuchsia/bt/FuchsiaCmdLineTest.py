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
"""
Script for initializing a cmd line tool for PTS and other purposes.
Required custom config parameters:
'target_mac_address': '00:00:00:00:00:00'

"""
from acts.base_test import BaseTestClass
from fuchsia_cmd_input import CmdInput
from queue import Empty

import os
import uuid

from acts.test_utils.tel.tel_test_utils import setup_droid_properties


class FuchsiaCmdLineTest(BaseTestClass):
    target_device_name = ""

    def __init__(self, controllers):
        BaseTestClass.__init__(self, controllers)
        if not "target_device_name" in self.user_params.keys():
            self.log.warning("Missing user config \"target_device_name\"!")
            self.target_device_name = ""
        else:
            self.target_device_name = self.user_params["target_device_name"]

        # TODO: Make sl4f function
        # Possibly set_name in fidl_fuchsia_bluetooth_control.rs
        #self.fuchsia_devices[0].droid.bluetoothSetLocalName("CMD LINE Test")

    def test_cmd_line_helper(self):
        cmd_line = CmdInput()
        cmd_line.setup_vars(self.fuchsia_devices, self.target_device_name,
                            self.log)
        cmd_line.cmdloop()
        return True
