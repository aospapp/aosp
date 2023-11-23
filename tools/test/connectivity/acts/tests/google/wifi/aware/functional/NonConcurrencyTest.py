#!/usr/bin/python3.4
#
#   Copyright 2017 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

from acts import asserts
import queue
from acts import utils
from acts.test_utils.wifi import wifi_test_utils as wutils
from acts.test_utils.wifi import wifi_constants as wconsts
from acts.test_utils.wifi.aware import aware_const as aconsts
from acts.test_utils.wifi.aware import aware_test_utils as autils
from acts.test_utils.wifi.aware.AwareBaseTest import AwareBaseTest

# arbitrary timeout for events
EVENT_TIMEOUT = 10


class NonConcurrencyTest(AwareBaseTest):
    """Tests lack of concurrency scenarios Wi-Fi Aware with WFD (p2p) and
  SoftAP

  Note: these tests should be modified if the concurrency behavior changes!"""

    SERVICE_NAME = "GoogleTestXYZ"
    TETHER_SSID = "GoogleTestSoftApXYZ"

    def __init__(self, controllers):
        AwareBaseTest.__init__(self, controllers)

    def teardown_test(self):
        AwareBaseTest.teardown_test(self)
        for ad in self.android_devices:
            ad.droid.wifiP2pClose()

    def run_aware_then_incompat_service(self, is_p2p):
        """Run test to validate that a running Aware session terminates when an
    Aware-incompatible service is started.

    Args:
      is_p2p: True for p2p, False for SoftAP
    """
        dut = self.android_devices[0]

        # start Aware
        id = dut.droid.wifiAwareAttach()
        autils.wait_for_event(dut, aconsts.EVENT_CB_ON_ATTACHED)

        # start other service
        if is_p2p:
            dut.droid.wifiP2pInitialize()
        else:
            wutils.start_wifi_tethering(dut, self.TETHER_SSID, password=None)

        # expect an announcement about Aware non-availability
        autils.wait_for_event(dut, aconsts.BROADCAST_WIFI_AWARE_NOT_AVAILABLE)

        # local clean-up
        if not is_p2p:
            wutils.stop_wifi_tethering(dut)

    def run_incompat_service_then_aware(self, is_p2p):
        """Validate that if an Aware-incompatible service is already up then any
    Aware operation fails"""
        dut = self.android_devices[0]

        # start other service
        if is_p2p:
            dut.droid.wifiP2pInitialize()
        else:
            wutils.start_wifi_tethering(dut, self.TETHER_SSID, password=None)

        # expect an announcement about Aware non-availability
        autils.wait_for_event(dut, aconsts.BROADCAST_WIFI_AWARE_NOT_AVAILABLE)

        # try starting anyway (expect failure)
        dut.droid.wifiAwareAttach()
        autils.wait_for_event(dut, aconsts.EVENT_CB_ON_ATTACH_FAILED)

        # stop other service
        if is_p2p:
            dut.droid.wifiP2pClose()
        else:
            wutils.stop_wifi_tethering(dut)

        # expect an announcement about Aware availability
        autils.wait_for_event(dut, aconsts.BROADCAST_WIFI_AWARE_AVAILABLE)

        # try starting Aware
        dut.droid.wifiAwareAttach()
        autils.wait_for_event(dut, aconsts.EVENT_CB_ON_ATTACHED)

    def run_aware_then_connect_to_new_ap(self):
        """Validate interaction of Wi-Fi Aware and infra (STA) association with randomized MAC
    address. Such an association may trigger interface down and up - possibly disrupting a Wi-Fi
    Aware session.

    Test behavior:
    - Start Aware
    - Associate STA
    - Check if an Aware state change Broadcast received
    - If necessary (Broadcast received) restart Aware
    - Start publish
    - Start Subscribe on peer
    - Verify discovery
    """
        dut = self.android_devices[0]
        dut_ap = self.android_devices[1]
        wutils.reset_wifi(dut)
        wutils.reset_wifi(dut_ap)
        p_config = autils.create_discovery_config(self.SERVICE_NAME,
                                                  aconsts.PUBLISH_TYPE_UNSOLICITED)
        s_config = autils.create_discovery_config(self.SERVICE_NAME,
                                                  aconsts.SUBSCRIBE_TYPE_PASSIVE)

        # create random SSID and start softAp on dut_ap
        ap_ssid = self.TETHER_SSID + utils.rand_ascii_str(8)
        ap_password = utils.rand_ascii_str(8)
        config = {wutils.WifiEnums.SSID_KEY: ap_ssid, wutils.WifiEnums.PWD_KEY: ap_password}
        wutils.start_wifi_tethering(dut_ap, ap_ssid, ap_password)
        asserts.assert_true(dut_ap.droid.wifiIsApEnabled(),
                            "SoftAp is not reported as running")

        # dut start Aware attach and connect to softAp on dut_ap
        p_id = dut.droid.wifiAwareAttach()
        autils.wait_for_event(dut, aconsts.EVENT_CB_ON_ATTACHED)

        wutils.wifi_connect(dut, config, check_connectivity=False)
        autils.wait_for_event(dut, wconsts.WIFI_STATE_CHANGED)

        # Check if the WifiAwareState changes then restart the Aware
        try:
            dut.ed.pop_event(aconsts.BROADCAST_WIFI_AWARE_AVAILABLE, EVENT_TIMEOUT)
            dut.log.info(aconsts.BROADCAST_WIFI_AWARE_AVAILABLE)
            p_id = dut.droid.wifiAwareAttach()
            autils.wait_for_event(dut, aconsts.EVENT_CB_ON_ATTACHED)
        except queue.Empty:
            dut.log.info('WifiAware state was not changed')

        # dut start Publish
        p_disc_id = dut.droid.wifiAwarePublish(p_id, p_config)
        autils.wait_for_event(dut, aconsts.SESSION_CB_ON_PUBLISH_STARTED)

        # dut_ap stop softAp and start Subscribe
        wutils.stop_wifi_tethering(dut_ap)
        autils.wait_for_event(dut_ap, aconsts.BROADCAST_WIFI_AWARE_AVAILABLE)
        s_id = dut_ap.droid.wifiAwareAttach()
        autils.wait_for_event(dut_ap, aconsts.EVENT_CB_ON_ATTACHED)
        s_disc_id = dut_ap.droid.wifiAwareSubscribe(s_id, s_config)
        autils.wait_for_event(dut_ap, aconsts.SESSION_CB_ON_SUBSCRIBE_STARTED)

        # Check discovery session
        autils.wait_for_event(dut_ap, aconsts.SESSION_CB_ON_SERVICE_DISCOVERED)

    ##########################################################################

    def test_run_p2p_then_aware(self):
        """Validate that if p2p is already up then any Aware operation fails"""
        self.run_incompat_service_then_aware(is_p2p=True)

    def test_run_aware_then_p2p(self):
        """Validate that a running Aware session terminates when p2p is started"""
        self.run_aware_then_incompat_service(is_p2p=True)

    def test_run_softap_then_aware(self):
        """Validate that if SoftAp is already up then any Aware operation fails"""
        self.run_incompat_service_then_aware(is_p2p=False)

    def test_run_aware_then_softap(self):
        """Validate that a running Aware session terminates when softAp is
    started"""
        self.run_aware_then_incompat_service(is_p2p=False)

    def test_run_aware_then_connect_new_ap(self):
        """Validate connect new ap during Aware session"""
        self.run_aware_then_connect_to_new_ap()
