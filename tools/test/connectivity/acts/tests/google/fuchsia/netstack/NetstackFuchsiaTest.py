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

from acts.base_test import BaseTestClass
from acts import asserts

class NetstackFuchsiaTest(BaseTestClass):
    default_timeout = 10
    active_scan_callback_list = []
    active_adv_callback_list = []
    droid = None

    def __init__(self, controllers):
        BaseTestClass.__init__(self, controllers)

        if (len(self.fuchsia_devices) < 1):
            self.log.error("NetstackFuchsiaTest Init: Not enough fuchsia devices.")
        self.log.info("Running testbed setup with one fuchsia devices")
        self.fuchsia_dev = self.fuchsia_devices[0]

    def teardown_test(self):
        self.fuchsia_dev.clean_up()

    def test_fuchsia_publish_service(self):
        asserts.assert_false(self.fuchsia_dev.netstack_lib.netstackListInterfaces()['error'],
                             "Expected list interfaces to succeed")
        return True
