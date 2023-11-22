#!/usr/bin/env python
#
# Copyright (C) 2017 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import logging

from vts.runners.host import asserts
from vts.runners.host import base_test
from vts.runners.host import keys
from vts.runners.host import test_runner
from vts.utils.python.precondition import precondition_utils


class VtsHalGnssV1_0HostTest(base_test.BaseTestClass):
    """A simple testcase for the GNSS HIDL HAL."""
    SYSPROP_GETSTUB = "vts.hal.vts.hidl.get_stub"

    def setUpClass(self):
        """Creates a mirror and turns on the framework-layer GNSS service."""
        self.dut = self.android_devices[0]

        self.passthrough_mode = self.getUserParam(
            keys.ConfigKeys.IKEY_PASSTHROUGH_MODE, default_value=True)

        self.dut.shell.Execute("setenforce 0")  # SELinux permissive mode
        if not precondition_utils.CanRunHidlHalTest(
            self, self.dut, self.dut.shell.default):
            self._skip_all_testcases = True
            return

        mode = "true" if self.passthrough_mode else "false"
        self.dut.shell.Execute(
            "setprop %s %s" % (self.SYSPROP_GETSTUB, mode))

        self.dut.hal.InitHidlHal(
            target_type="gnss",
            target_basepaths=self.dut.libPaths,
            target_version=1.0,
            target_package="android.hardware.gnss",
            target_component_name="IGnss",
            bits=int(self.abi_bitness))

        if self.coverage.enabled:
            self.coverage.LoadArtifacts()
            self.coverage.InitializeDeviceCoverage(self._dut)

    def SetCallback(self):
        """Utility function to set the callbacks."""

        def gnssLocationCb(location):
            logging.info("callback gnssLocationCb")

        def gnssStatusCb(status):
            logging.info("callback gnssStatusCb")

        def gnssSvStatusCb(svInfo):
            logging.info("callback gnssSvStatusCb")

        def gnssNmeaCb(timestamp, nmea):
            logging.info("callback gnssNmeaCb")

        def gnssSetCapabilitesCb(capabilities):
            logging.info("callback gnssSetCapabilitesCb")

        def gnssAcquireWakelockCb():
            logging.info("callback gnssAcquireWakelockCb")

        def gnssReleaseWakelockCb():
            logging.info("callback gnssReleaseWakelockCb")

        def gnssRequestTimeCb():
            logging.info("callback gnssRequestTimeCb")

        def gnssSetSystemInfoCb(info):
            logging.info("callback gnssSetSystemInfoCb")

        client_callback = self.dut.hal.gnss.GetHidlCallbackInterface(
            "IGnssCallback",
            gnssLocationCb=gnssLocationCb,
            gnssStatusCb=gnssStatusCb,
            gnssSvStatusCb=gnssSvStatusCb,
            gnssNmeaCb=gnssNmeaCb,
            gnssSetCapabilitesCb=gnssSetCapabilitesCb,
            gnssAcquireWakelockCb=gnssAcquireWakelockCb,
            gnssReleaseWakelockCb=gnssReleaseWakelockCb,
            gnssRequestTimeCb=gnssRequestTimeCb,
            gnssSetSystemInfoCb=gnssSetSystemInfoCb)

        result = self.dut.hal.gnss.setCallback(client_callback)
        logging.info("setCallback result: %s", result)

    def testExtensionPresence(self):
        """A test case which checks whether each extension exists."""
        self.SetCallback()

        nested_interface = self.dut.hal.gnss.getExtensionAGnssRil()
        if not nested_interface:
            logging.info("getExtensionAGnssRil returned None")
        else:
            result = nested_interface.updateNetworkAvailability(False, "test")
            logging.info("updateNetworkAvailability result: %s", result)

        nested_interface = self.dut.hal.gnss.getExtensionGnssGeofencing()
        if not nested_interface:
            logging.info("getExtensionGnssGeofencing returned None")

        nested_interface = self.dut.hal.gnss.getExtensionAGnss()
        if not nested_interface:
            logging.info("getExtensionAGnss returned None")
        else:
            result = nested_interface.dataConnClosed()
            logging.info("dataConnClosed result: %s", result)

        nested_interface = self.dut.hal.gnss.getExtensionGnssNi()
        if not nested_interface:
            logging.info("getExtensionGnssNi returned None")

        nested_interface = self.dut.hal.gnss.getExtensionGnssMeasurement()
        if not nested_interface:
            logging.info("getExtensionGnssMeasurement returned None")

        nested_interface = self.dut.hal.gnss.getExtensionXtra()
        if not nested_interface:
            logging.info("getExtensionXtra returned None")

        nested_interface = self.dut.hal.gnss.getExtensionGnssConfiguration()
        if not nested_interface:
            logging.info("getExtensionGnssConfiguration returned None")

        nested_interface = self.dut.hal.gnss.getExtensionGnssBatching()
        if not nested_interface:
            logging.info("getExtensionGnssBatching returned None")

    def testExtensionPresenceForUnimplementedOnes(self):
        """A test case which checks whether each extension exists.

        Separate test case for known failures.
        """
        self.SetCallback()

        nested_interface = self.dut.hal.gnss.getExtensionGnssNavigationMessage()
        if not nested_interface:
            logging.error("ExtensionGnssNavigationMessage not implemented")

        nested_interface = self.dut.hal.gnss.getExtensionGnssDebug()
        if not nested_interface:
            logging.error("ExtensionGnssDebug not implemented")

if __name__ == "__main__":
    test_runner.main()
