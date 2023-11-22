#!/usr/bin/env python3.5
#
#   Copyright 2019 - The Android Open Source Project
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
import time
from multiprocessing import Process

from acts import utils
from acts.base_test import BaseTestClass
from acts.test_decorators import test_tracker_info
from acts.test_utils.wifi import wifi_test_utils as wutils
from acts.test_utils.tel import tel_test_utils as tutils
from acts.test_utils.gnss import gnss_test_utils as gutils
from acts.utils import get_current_epoch_time


class GNSSSanityTest(BaseTestClass):
    """ GNSS Function Sanity Tests"""
    def __init__(self, controllers):
        BaseTestClass.__init__(self, controllers)
        self.ad = self.android_devices[0]
        req_params = ["pixel_lab_network",
                      "standalone_cs_criteria", "supl_cs_criteria",
                      "xtra_ws_criteria", "xtra_cs_criteria",
                      "weak_signal_supl_cs_criteria",
                      "weak_signal_xtra_ws_criteria",
                      "weak_signal_xtra_cs_criteria",
                      "default_gnss_signal_attenuation",
                      "weak_gnss_signal_attenuation",
                      "no_gnss_signal_attenuation"]
        self.unpack_userparams(req_param_names=req_params)
        # create hashmap for SSID
        self.ssid_map = {}
        for network in self.pixel_lab_network:
            SSID = network['SSID']
            self.ssid_map[SSID] = network

    def setup_class(self):
        self.ad.droid.wakeLockAcquireBright()
        self.ad.droid.wakeUpNow()
        tutils.print_radio_info(self.ad)
        gutils.set_attenuator_gnss_signal(self.ad, self.attenuators,
                                          self.default_gnss_signal_attenuation)
        gutils._init_device(self.ad)
        if not tutils.verify_internet_connection(self.ad.log,
                                                 self.ad,
                                                 retries=3,
                                                 expected_state=True):
            tutils.abort_all_tests(self.ad.log,
                                   "Fail to connect to LTE network")
        if not gutils.check_location_service(self.ad):
            tutils.abort_all_tests(self.ad.log, "Fail to switch Location on")

    def setup_test(self):
        gutils.clear_logd_gnss_qxdm_log(self.ad)

    def teardown_test(self):
        tutils.stop_qxdm_logger(self.ad)
        if tutils.check_call_state_connected_by_adb(self.ad):
            tutils.hangup_call(self.ad.log, self.ad)
        if not int(self.ad.adb.shell("settings get global airplane_mode_on")) == 0:
            self.ad.log.info("Force airplane mode off")
            utils.force_airplane_mode(self.ad, False)
        if self.ad.droid.wifiCheckState():
            wutils.wifi_toggle_state(self.ad, False)
        if not int(self.ad.adb.shell("settings get global mobile_data")) == 1:
            gutils.set_mobile_data(self.ad, True)
        if not int(self.ad.adb.shell(
            "settings get global wifi_scan_always_enabled")) == 1:
            gutils.set_wifi_and_bt_scanning(self.ad, True)
        if not int(self.attenuators[0].get_atten()) == self.default_gnss_signal_attenuation:
            gutils.set_attenuator_gnss_signal(self.ad, self.attenuators,
                                              self.default_gnss_signal_attenuation)

    def on_fail(self, test_name, begin_time):
        gutils.get_gnss_qxdm_log(self.ad, test_name)
        self.ad.take_bug_report(test_name, begin_time)

    """ Test Cases """

    @test_tracker_info(uuid="ff318483-411c-411a-8b1a-422bd54f4a3f")
    def test_supl_capabilities(self):
        """Verify SUPL capabilities.

        Steps:
            1. Root DUT.
            2. Check SUPL capabilities.

        Expected Results:
            CAPABILITIES=0x37 which supports MSA + MSB.

        Return:
            True if PASS, False if FAIL.
        """
        capabilities_state = str(self.ad.adb.shell("cat vendor/etc/gps.conf | "
                                                   "grep CAPABILITIES"))
        self.ad.log.info("SUPL capabilities - %s" % capabilities_state)
        if "CAPABILITIES=0x37" in capabilities_state:
            return True
        return False

    @test_tracker_info(uuid="dcae6979-ddb4-4cad-9d14-fbdd9439cf42")
    def test_sap_valid_modes(self):
        """Verify SAP Valid Modes.

        Steps:
            1. Root DUT.
            2. Check SAP Valid Modes.

        Expected Results:
            SAP=PREMIUM

        Return:
            True if PASS, False if FAIL.
        """
        sap_state = str(self.ad.adb.shell("cat vendor/etc/izat.conf | grep "
                                          "SAP="))
        self.ad.log.info("SAP Valid Modes - %s" % sap_state)
        if "SAP=PREMIUM" in sap_state:
            return True
        return False

    @test_tracker_info(uuid="14daaaba-35b4-42d9-8d2c-2a803dd746a6")
    def test_network_location_provider_cell(self):
        """Verify LocationManagerService API reports cell Network Location.

        Steps:
            1. WiFi scanning and Bluetooth scanning in Location Setting are OFF.
            2. Launch GTW_GPSTool.
            3. Verify whether test devices could report cell Network Location.
            4. Repeat Step 2. to Step 3. for 5 times.

        Expected Results:
            Test devices could report cell Network Location.

        Return:
            True if PASS, False if FAIL.
        """
        test_result_all = []
        tutils.start_qxdm_logger(self.ad, get_current_epoch_time())
        gutils.set_wifi_and_bt_scanning(self.ad, False)
        for i in range(1, 6):
            test_result = gutils.check_network_location(
                self.ad, retries=3, location_type = "networkLocationType=cell")
            test_result_all.append(test_result)
            self.ad.log.info("Iteraion %d => %s" % (i, test_result))
        gutils.set_wifi_and_bt_scanning(self.ad, True)
        return all(test_result_all)

    @test_tracker_info(uuid="a45bdc7d-29fa-4a1d-ba34-6340b90e308d")
    def test_network_location_provider_wifi(self):
        """Verify LocationManagerService API reports wifi Network Location.

        Steps:
            1. WiFi scanning and Bluetooth scanning in Location Setting are ON.
            2. Launch GTW_GPSTool.
            3. Verify whether test devices could report wifi Network Location.
            4. Repeat Step 2. to Step 3. for 5 times.

        Expected Results:
            Test devices could report wifi Network Location.

        Return:
            True if PASS, False if FAIL.
        """
        test_result_all = []
        tutils.start_qxdm_logger(self.ad, get_current_epoch_time())
        gutils.set_wifi_and_bt_scanning(self.ad, True)
        for i in range(1, 6):
            test_result = gutils.check_network_location(
                self.ad, retries=3, location_type = "networkLocationType=wifi")
            test_result_all.append(test_result)
            self.ad.log.info("Iteraion %d => %s" % (i, test_result))
        return all(test_result_all)

    @test_tracker_info(uuid="0919d375-baf2-4fe7-b66b-3f72d386f791")
    def test_gmap_location_report_gps_network(self):
        """Verify GnssLocationProvider API reports location to Google Map
           when GPS and Location Accuracy are on.

        Steps:
            1. GPS and NLP are on.
            2. Launch Google Map.
            3. Verify whether test devices could report location.
            4. Repeat Step 2. to Step 3. for 5 times.

        Expected Results:
            Test devices could report location to Google Map.

        Return:
            True if PASS, False if FAIL.
        """
        test_result_all = []
        tutils.start_qxdm_logger(self.ad, get_current_epoch_time())
        for i in range(1, 6):
            gutils.launch_google_map(self.ad)
            test_result = gutils.check_location_api(self.ad, retries=3)
            self.ad.send_keycode("HOME")
            test_result_all.append(test_result)
            self.ad.log.info("Iteraion %d => %s" % (i, test_result))
        return all(test_result_all)

    @test_tracker_info(uuid="513361d2-7d72-41b0-a944-fb259c606b81")
    def test_gmap_location_report_gps(self):
        """Verify GnssLocationProvider API reports location to Google Map
           when GPS is on and Location Accuracy is off.

        Steps:
            1. GPS is on.
            2. Location Accuracy is off.
            3. Launch Google Map.
            4. Verify whether test devices could report location.
            5. Repeat Step 3. to Step 4. for 5 times.

        Expected Results:
            Test devices could report location to Google Map.

        Return:
            True if PASS, False if FAIL.
        """
        test_result_all = []
        tutils.start_qxdm_logger(self.ad, get_current_epoch_time())
        self.ad.adb.shell("settings put secure location_providers_allowed "
                          "-network")
        out = self.ad.adb.shell("settings get secure location_providers_allowed")
        self.ad.log.info("Modify current Location Provider to %s" % out)
        for i in range(1, 6):
            gutils.launch_google_map(self.ad)
            test_result = gutils.check_location_api(self.ad, retries=3)
            self.ad.send_keycode("HOME")
            test_result_all.append(test_result)
            self.ad.log.info("Iteraion %d => %s" % (i, test_result))
        self.ad.adb.shell("settings put secure location_providers_allowed "
                          "+network")
        out = self.ad.adb.shell("settings get secure location_providers_allowed")
        self.ad.log.info("Modify current Location Provider to %s" % out)
        return all(test_result_all)

    @test_tracker_info(uuid="91a65121-b87d-450d-bd0f-387ade450ab7")
    def test_gmap_location_report_battery_saver(self):
        """Verify GnssLocationProvider API reports location to Google Map
           when Battery Saver is enabled.

        Steps:
            1. GPS and NLP are on.
            2. Enable Battery Saver.
            3. Launch Google Map.
            4. Verify whether test devices could report location.
            5. Repeat Step 3. to Step 4. for 5 times.
            6. Disable Battery Saver.

        Expected Results:
            Test devices could report location to Google Map.

        Return:
            True if PASS, False if FAIL.
        """
        test_result_all = []
        tutils.start_qxdm_logger(self.ad, get_current_epoch_time())
        gutils.set_battery_saver_mode(self.ad, True)
        for i in range(1, 6):
            gutils.launch_google_map(self.ad)
            test_result = gutils.check_location_api(self.ad, retries=3)
            self.ad.send_keycode("HOME")
            test_result_all.append(test_result)
            self.ad.log.info("Iteraion %d => %s" % (i, test_result))
        gutils.set_battery_saver_mode(self.ad, False)
        return all(test_result_all)

    @test_tracker_info(uuid="60c0aeec-0c8f-4a96-bc6c-05cba1260e73")
    def test_supl_ongoing_call(self):
        """Verify SUPL functionality during phone call.

        Steps:
            1. Kill XTRA daemon to support SUPL only case.
            2. Initiate call on DUT.
            3. SUPL TTFF Cold Start for 10 iteration.
            4. DUT hang up call.

        Expected Results:
            All SUPL TTFF Cold Start results should be less than
            supl_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        begin_time = get_current_epoch_time()
        tutils.start_qxdm_logger(self.ad, begin_time)
        gutils.kill_xtra_daemon(self.ad)
        self.ad.droid.setVoiceCallVolume(25)
        tutils.initiate_call(self.ad.log, self.ad, "99117")
        time.sleep(5)
        if tutils.check_call_state_idle_by_adb(self.ad):
            self.ad.log.error("Call is not connected.")
            return False
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.supl_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=10)
        ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        return gutils.check_ttff_result(self.ad, ttff_result,
                                        ttff_mode="Cold Start",
                                        criteria=self.supl_cs_criteria)

    @test_tracker_info(uuid="df605509-328f-43e8-b6d8-00635bf701ef")
    def test_supl_downloading_files(self):
        """Verify SUPL functionality when downloading files.

        Steps:
            1. Kill XTRA daemon to support SUPL only case.
            2. DUT start downloading files by sl4a.
            3. SUPL TTFF Cold Start for 10 iteration.
            4. DUT cancel downloading files.

        Expected Results:
            All SUPL TTFF Cold Start results should be within supl_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        begin_time = get_current_epoch_time()
        tutils.start_qxdm_logger(self.ad, begin_time)
        gutils.kill_xtra_daemon(self.ad)
        download = Process(target=tutils.http_file_download_by_sl4a,
                           args=(self.ad, "https://speed.hetzner.de/10GB.bin",
                                 None, None, True, 3600))
        download.start()
        time.sleep(10)
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.supl_cs_criteria):
            download.terminate()
            time.sleep(3)
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=10)
        ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        download.terminate()
        time.sleep(3)
        return gutils.check_ttff_result(self.ad, ttff_result,
                                        ttff_mode="Cold Start",
                                        criteria=self.supl_cs_criteria)

    @test_tracker_info(uuid="66b9f9d4-1397-4da7-9e55-8b89b1732017")
    def test_supl_watching_youtube(self):
        """Verify SUPL functionality when watching video on youtube.

        Steps:
            1. Kill XTRA daemon to support SUPL only case.
            2. DUT start watching video on youtube.
            3. SUPL TTFF Cold Start for 10 iteration at the background.
            4. DUT stop watching video on youtube.

        Expected Results:
            All SUPL TTFF Cold Start results should be within supl_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        begin_time = get_current_epoch_time()
        tutils.start_qxdm_logger(self.ad, begin_time)
        gutils.kill_xtra_daemon(self.ad)
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.supl_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=10)
        if not gutils.start_youtube_video(
            self.ad, "https://www.youtube.com/watch?v=AbdVsi1VjQY", retries=3):
            return False
        ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        return gutils.check_ttff_result(self.ad, ttff_result,
                                        ttff_mode="Cold Start",
                                        criteria=self.supl_cs_criteria)

    @test_tracker_info(uuid="a748af8b-e1eb-4ec6-bde3-74bcefa1c680")
    def test_supl_modem_ssr(self):
        """Verify SUPL functionality after modem silent reboot.

        Steps:
            1. Trigger modem crash by adb.
            2. Wait 1 minute for modem to recover.
            3. SUPL TTFF Cold Start for 3 iteration.
            4. Repeat Step 1. to Step 3. for 5 times.

        Expected Results:
            All SUPL TTFF Cold Start results should be within supl_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        supl_ssr_test_result_all = []
        tutils.start_qxdm_logger(self.ad, get_current_epoch_time())
        gutils.kill_xtra_daemon(self.ad)
        for times in range(1, 6):
            begin_time = get_current_epoch_time()
            before_modem_ssr = gutils.get_modem_ssr_crash_count(self.ad)
            tutils.trigger_modem_crash(self.ad, timeout=60)
            after_modem_ssr = gutils.get_modem_ssr_crash_count(self.ad)
            if not int(after_modem_ssr) == int(before_modem_ssr) + 1:
                self.ad.log.error("Simulated Modem SSR Failed.")
                return False
            if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.supl_cs_criteria):
                return False
            gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=3)
            ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
            supl_ssr_test_result = gutils.check_ttff_result(
                self.ad, ttff_result, "Cold Start", self.supl_cs_criteria)
            self.ad.log.info("SUPL after Modem SSR test %d times -> %s"
                             % (times, supl_ssr_test_result))
            supl_ssr_test_result_all.append(supl_ssr_test_result)
        return all(supl_ssr_test_result_all)

    @test_tracker_info(uuid="01602e65-8ded-4459-8df1-7df70a1bfe8a")
    def test_gnss_airplane_mode_on(self):
        """Verify Standalone GNSS functionality while airplane mode is on.

        Steps:
            1. Turn on airplane mode.
            2. TTFF Cold Start for 10 iteration.
            3. Turn off airplane mode.

        Expected Results:
            All Standalone TTFF Cold Start results should be within
            standalone_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        begin_time = get_current_epoch_time()
        tutils.start_qxdm_logger(self.ad, begin_time)
        self.ad.log.info("Turn airplane mode on")
        utils.force_airplane_mode(self.ad, True)
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.standalone_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=10)
        ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        return gutils.check_ttff_result(self.ad, ttff_result,
                                        ttff_mode="Cold Start",
                                        criteria=self.standalone_cs_criteria)

    @test_tracker_info(uuid="23731b0d-cb80-4c79-a877-cfe7c2faa447")
    def test_gnss_mobile_data_off(self):
        """Verify Standalone GNSS functionality while mobile radio is off.

        Steps:
            1. Disable mobile data.
            2. TTFF Cold Start for 10 iteration.
            3. Enable mobile data.

        Expected Results:
            All Standalone TTFF Cold Start results should be within
            standalone_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        begin_time = get_current_epoch_time()
        tutils.start_qxdm_logger(self.ad, begin_time)
        gutils.kill_xtra_daemon(self.ad)
        gutils.set_mobile_data(self.ad, False)
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.standalone_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=10)
        ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        return gutils.check_ttff_result(self.ad, ttff_result,
                                        ttff_mode="Cold Start",
                                        criteria=self.standalone_cs_criteria)

    @test_tracker_info(uuid="085b86a9-0212-4c0f-8ca1-2e467a0a2e6e")
    def test_supl_without_gnss_signal(self):
        """Verify SUPL functionality after no GNSS signal for awhile.

        Steps:
            1. Get location fixed.
            2  Let device do GNSS tracking for 1 minute.
            3. Set attenuation value to 60 to block GNSS signal.
            4. Let DUT stay in no GNSS signal for 5 minutes.
            5. Set attenuation value to 23 to regain GNSS signal.
            6. Try to get location reported again.
            7. Repeat Step 1. to Step 6. for 5 times.

        Expected Results:
            After setting attenuation value to 10 (GPS signal regain),
            DUT could get location fixed again.

        Return:
            True if PASS, False if FAIL.
        """
        supl_no_gnss_signal_all = []
        tutils.start_qxdm_logger(self.ad, get_current_epoch_time())
        for times in range(1, 6):
            if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.supl_cs_criteria):
                return False
            self.ad.log.info("Let device do GNSS tracking for 1 minute.")
            time.sleep(60)
            gutils.set_attenuator_gnss_signal(self.ad, self.attenuators,
                                              self.no_gnss_signal_attenuation)
            self.ad.log.info("Let device stay in no GNSS signal for 5 minutes.")
            time.sleep(300)
            gutils.set_attenuator_gnss_signal(self.ad, self.attenuators,
                                              self.default_gnss_signal_attenuation)
            supl_no_gnss_signal = gutils.check_location_api(self.ad, retries=3)
            gutils.start_gnss_by_gtw_gpstool(self.ad, False)
            self.ad.log.info("SUPL without GNSS signal test %d times -> %s"
                             % (times, supl_no_gnss_signal))
            supl_no_gnss_signal_all.append(supl_no_gnss_signal)
        return all(supl_no_gnss_signal_all)

    @test_tracker_info(uuid="3ff2f2fa-42d8-47fa-91de-060816cca9df")
    def test_supl_weak_gnss_signal(self):
        """Verify SUPL TTFF functionality under weak GNSS signal.

        Steps:
            1. Set attenuation value to 40 to set weak GNSS signal.
            2. Kill XTRA daemon to support SUPL only case.
            3. SUPL TTFF Cold Start for 10 iteration.
            4. Set attenuation value to 23 to set default GNSS signal.

        Expected Results:
            All SUPL TTFF Cold Start results should be less than
            weak_signal_supl_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        gutils.set_attenuator_gnss_signal(self.ad, self.attenuators,
                                          self.weak_gnss_signal_attenuation)
        begin_time = get_current_epoch_time()
        tutils.start_qxdm_logger(self.ad, begin_time)
        gutils.kill_xtra_daemon(self.ad)
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.weak_signal_supl_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=10)
        ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        return gutils.check_ttff_result(self.ad, ttff_result, "Cold Start",
                                        self.weak_signal_supl_cs_criteria)

    @test_tracker_info(uuid="4ad4a371-949a-42e1-b1f4-628c79fa8ddc")
    def test_supl_factory_reset(self):
        """Verify SUPL functionality after factory reset.

        Steps:
            1. Factory reset device.
            2. Kill XTRA daemon to support SUPL only case.
            3. SUPL TTFF Cold Start for 10 iteration.
            4. Repeat Step 1. to Step 3. for 3 times.

        Expected Results:
            All SUPL TTFF Cold Start results should be within supl_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        for times in range(1, 4):
            gutils.fastboot_factory_reset(self.ad)
            self.ad.unlock_screen(password=None)
            gutils._init_device(self.ad)
            if not gutils.check_location_service(self.ad):
                return False
            begin_time = get_current_epoch_time()
            tutils.start_qxdm_logger(self.ad, begin_time)
            gutils.kill_xtra_daemon(self.ad)
            if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.supl_cs_criteria):
                return False
            gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=10)
            ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
            if not gutils.check_ttff_result(self.ad, ttff_result,
                                            ttff_mode="Cold Start",
                                            criteria=self.supl_cs_criteria):
                self.ad.log.error("SUPL after Factory Reset test %d times "
                                  "-> FAIL" % times)
                return False
            self.ad.log.info("SUPL after Factory Reset test %d times -> "
                             "PASS" % times)
        return True

    @test_tracker_info(uuid="ea3096cf-4f72-4e91-bfb3-0bcbfe865ab4")
    def test_xtra_ttff_mobile_data(self):
        """Verify XTRA TTFF functionality with mobile data.

        Steps:
            1. Disable SUPL mode.
            2. TTFF Warm Start for 10 iteration.
            3. TTFF Cold Start for 10 iteration.

        Expected Results:
            XTRA TTFF Warm Start results should be within xtra_ws_criteria.
            XTRA TTFF Cold Start results should be within xtra_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        gutils.disable_supl_mode(self.ad)
        begin_time = get_current_epoch_time()
        tutils.start_qxdm_logger(self.ad, begin_time)
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.xtra_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="ws", iteration=10)
        ws_ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        if not gutils.check_ttff_result(self.ad, ws_ttff_result, "Warm Start",
                                        self.xtra_ws_criteria):
            return False
        begin_time = get_current_epoch_time()
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.xtra_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=10)
        cs_ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        return gutils.check_ttff_result(self.ad, cs_ttff_result, "Cold Start",
                                        self.xtra_cs_criteria)

    @test_tracker_info(uuid="c91ba740-220e-41de-81e5-43af31f63907")
    def test_xtra_ttff_weak_gnss_signal(self):
        """Verify XTRA TTFF functionality under weak GNSS signal.

        Steps:
            1. Set attenuation value to 40 to set weak GNSS signal.
            2. TTFF Warm Start for 10 iteration.
            3. TTFF Cold Start for 10 iteration.
            4. Set attenuation value to 23 to set default GNSS signal.

        Expected Results:
            XTRA TTFF Warm Start results should be within
            weak_signal_xtra_ws_criteria.
            XTRA TTFF Cold Start results should be within
            weak_signal_xtra_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        gutils.disable_supl_mode(self.ad)
        gutils.set_attenuator_gnss_signal(self.ad, self.attenuators,
                                          self.weak_gnss_signal_attenuation)
        begin_time = get_current_epoch_time()
        tutils.start_qxdm_logger(self.ad, begin_time)
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.weak_signal_xtra_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="ws", iteration=10)
        ws_ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        if not gutils.check_ttff_result(self.ad, ws_ttff_result, "Warm Start",
                                        self.weak_signal_xtra_ws_criteria):
            return False
        begin_time = get_current_epoch_time()
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.weak_signal_xtra_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=10)
        cs_ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        return gutils.check_ttff_result(self.ad, cs_ttff_result, "Cold Start",
                                        self.weak_signal_xtra_cs_criteria)

    @test_tracker_info(uuid="beeb3454-bcb2-451e-83fb-26289e89b515")
    def test_xtra_ttff_wifi(self):
        """Verify XTRA TTFF functionality with WiFi.

        Steps:
            1. Disable SUPL mode and turn airplane mode on.
            2. Connect to WiFi.
            3. TTFF Warm Start for 10 iteration.
            4. TTFF Cold Start for 10 iteration.

        Expected Results:
            XTRA TTFF Warm Start results should be within xtra_ws_criteria.
            XTRA TTFF Cold Start results should be within xtra_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        gutils.disable_supl_mode(self.ad)
        begin_time = get_current_epoch_time()
        tutils.start_qxdm_logger(self.ad, begin_time)
        self.ad.log.info("Turn airplane mode on")
        utils.force_airplane_mode(self.ad, True)
        wutils.wifi_toggle_state(self.ad, True)
        gutils.connect_to_wifi_network(
            self.ad, self.ssid_map[self.pixel_lab_network[0]["SSID"]])
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.xtra_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="ws", iteration=10)
        ws_ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        if not gutils.check_ttff_result(self.ad, ws_ttff_result, "Warm Start",
                                        self.xtra_ws_criteria):
            return False
        begin_time = get_current_epoch_time()
        if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.xtra_cs_criteria):
            return False
        gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=10)
        cs_ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
        return gutils.check_ttff_result(self.ad, cs_ttff_result, "Cold Start",
                                        self.xtra_cs_criteria)

    @test_tracker_info(uuid="1745b8a4-5925-4aa0-809a-1b17e848dc9c")
    def test_xtra_modem_ssr(self):
        """Verify XTRA functionality after modem silent reboot.

        Steps:
            1. Trigger modem crash by adb.
            2. Wait 1 minute for modem to recover.
            3. XTRA TTFF Cold Start for 3 iteration.
            4. Repeat Step1. to Step 3. for 5 times.

        Expected Results:
            All XTRA TTFF Cold Start results should be within xtra_cs_criteria.

        Return:
            True if PASS, False if FAIL.
        """
        gutils.disable_supl_mode(self.ad)
        xtra_ssr_test_result_all = []
        tutils.start_qxdm_logger(self.ad, get_current_epoch_time())
        for times in range(1, 6):
            begin_time = get_current_epoch_time()
            before_modem_ssr = gutils.get_modem_ssr_crash_count(self.ad)
            tutils.trigger_modem_crash(self.ad, timeout=60)
            after_modem_ssr = gutils.get_modem_ssr_crash_count(self.ad)
            if not int(after_modem_ssr) == int(before_modem_ssr) + 1:
                self.ad.log.error("Simulated Modem SSR Failed.")
                return False
            if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.xtra_cs_criteria):
                return False
            gutils.start_ttff_by_gtw_gpstool(self.ad, ttff_mode="cs", iteration=3)
            ttff_result = gutils.process_ttff_by_gtw_gpstool(self.ad, begin_time)
            xtra_ssr_test_result = gutils.check_ttff_result(self.ad,
                                                            ttff_result,
                                                            "Cold Start",
                                                            self.xtra_cs_criteria)
            self.ad.log.info("XTRA after Modem SSR test %d times -> %s"
                             % (times, xtra_ssr_test_result))
            xtra_ssr_test_result_all.append(xtra_ssr_test_result)
        return all(xtra_ssr_test_result_all)

    @test_tracker_info(uuid="4d6e81e1-3abb-4e03-b732-7b6b497a2258")
    def test_xtra_download_mobile_data(self):
        """Verify XTRA data could be downloaded via mobile data.

        Steps:
            1. Delete all GNSS aiding data.
            2. Get location fixed.
            3. Verify whether XTRA is downloaded and injected.
            4. Repeat Step 1. to Step 3. for 5 times.

        Expected Results:
            XTRA data is properly downloaded and injected via mobile data.

        Return:
            True if PASS, False if FAIL.
        """
        mobile_xtra_result_all = []
        gutils.disable_supl_mode(self.ad)
        tutils.start_qxdm_logger(self.ad, get_current_epoch_time())
        for i in range(1, 6):
            begin_time = get_current_epoch_time()
            if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.xtra_cs_criteria):
                return False
            time.sleep(5)
            gutils.start_gnss_by_gtw_gpstool(self.ad, False)
            mobile_xtra_result = gutils.check_xtra_download(self.ad, begin_time)
            self.ad.log.info("Iteration %d => %s" % (i, mobile_xtra_result))
            mobile_xtra_result_all.append(mobile_xtra_result)
        return all(mobile_xtra_result_all)

    @test_tracker_info(uuid="625ac665-1446-4406-a722-e6a19645222c")
    def test_xtra_download_wifi(self):
        """Verify XTRA data could be downloaded via WiFi.

        Steps:
            1. Connect to WiFi.
            2. Delete all GNSS aiding data.
            3. Get location fixed.
            4. Verify whether XTRA is downloaded and injected.
            5. Repeat Step 2. to Step 4. for 5 times.

        Expected Results:
            XTRA data is properly downloaded and injected via WiFi.

        Return:
            True if PASS, False if FAIL.
        """
        wifi_xtra_result_all = []
        gutils.disable_supl_mode(self.ad)
        tutils.start_qxdm_logger(self.ad, get_current_epoch_time())
        self.ad.log.info("Turn airplane mode on")
        utils.force_airplane_mode(self.ad, True)
        wutils.wifi_toggle_state(self.ad, True)
        gutils.connect_to_wifi_network(
            self.ad, self.ssid_map[self.pixel_lab_network[0]["SSID"]])
        for i in range(1, 6):
            begin_time = get_current_epoch_time()
            if not gutils.process_gnss_by_gtw_gpstool(self.ad, self.xtra_cs_criteria):
                return False
            time.sleep(5)
            gutils.start_gnss_by_gtw_gpstool(self.ad, False)
            wifi_xtra_result = gutils.check_xtra_download(self.ad, begin_time)
            wifi_xtra_result_all.append(wifi_xtra_result)
            self.ad.log.info("Iteraion %d => %s" % (i, wifi_xtra_result))
        return all(wifi_xtra_result_all)
