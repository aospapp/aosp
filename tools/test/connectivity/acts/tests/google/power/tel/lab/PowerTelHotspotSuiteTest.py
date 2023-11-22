#!/usr/bin/env python3.4
#
#   Copyright 2018 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the 'License');
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an 'AS IS' BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

from PowerTelHotspotTest import PowerTelHotspotTest


class PowerTelHotspot_LTE_Test(PowerTelHotspotTest):

    def test_lte_hotspot_band_2_pdl_excellent_pul_low_bw_14_tm_1_mimo_1x1_scheduling_static_direction_dl_pattern_100_0_wifiband_5g_1(self):
        self.power_tel_tethering_test()

    def test_lte_hotspot_band_13_pdl_excellent_pul_high_bw_10_tm_4_mimo_2x2_scheduling_static_direction_ul_pattern_0_100_wifiband_5g_4(self):
        self.power_tel_tethering_test()

    def test_lte_hotspot_band_13_pdl_excellent_pul_high_bw_10_tm_4_mimo_2x2_scheduling_static_direction_dl_pattern_100_0_wifiband_5g_5(self):
        self.power_tel_tethering_test()

    def test_lte_hotspot_band_7_pdl_excellent_pul_max_bw_20_tm_4_mimo_2x2_scheduling_static_direction_dl_pattern_100_0_wifiband_2g_6(self):
        self.power_tel_tethering_test()

    def test_lte_hotspot_band_3_pdl_excellent_pul_low_bw_10_tm_1_mimo_2x2_scheduling_static_direction_dl_pattern_100_0_wifiband_5g_9(self):
        self.power_tel_tethering_test()

    def test_lte_hotspot_band_3_pdl_excellent_pul_low_bw_10_tm_1_mimo_2x2_scheduling_static_direction_dlul_pattern_50_50_wifiband_2g_10(self):
        self.power_tel_tethering_test()

    def test_lte_hotspot_band_2_pdl_excellent_pul_max_bw_20_tm_1_mimo_1x1_scheduling_static_direction_dl_pattern_100_0_wifiband_2g_11(self):
        self.power_tel_tethering_test()

    def test_lte_hotspot_band_12_pdl_excellent_pul_medium_bw_5_tm_1_mimo_1x1_scheduling_static_direction_dlul_pattern_50_50_wifiband_5g_13(self):
        self.power_tel_tethering_test()

    def test_lte_hotspot_band_12_pdl_excellent_pul_medium_bw_5_tm_4_mimo_2x2_scheduling_static_direction_ul_pattern_0_100_wifiband_5g_14(self):
        self.power_tel_tethering_test()

    def test_lte_hotspot_band_5_pdl_excellent_pul_low_bw_3_tm_1_mimo_1x1_scheduling_static_direction_ul_pattern_0_100_wifiband_5g_15(self):
        self.power_tel_tethering_test()
