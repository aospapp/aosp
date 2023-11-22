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

import time
import math
from enum import Enum

from acts.controllers.anritsu_lib.md8475a import BtsBandwidth
from acts.controllers.anritsu_lib.md8475a import BtsPacketRate
from acts.test_utils.power.tel_simulations.BaseSimulation import BaseSimulation
from acts.test_utils.tel.tel_defines import NETWORK_MODE_LTE_ONLY


class LteSimulation(BaseSimulation):
    """ Simple LTE simulation with only one basestation.

    """

    # Simulation config files in the callbox computer.
    # These should be replaced in the future by setting up
    # the same configuration manually.
    LTE_BASIC_SIM_FILE = 'SIM_default_LTE'
    LTE_BASIC_CELL_FILE = 'CELL_LTE_config'

    # Simulation config keywords contained in the test name
    PARAM_FRAME_CONFIG = "tddconfig"
    PARAM_BW = "bw"
    PARAM_SCHEDULING = "scheduling"
    PARAM_SCHEDULING_STATIC = "static"
    PARAM_SCHEDULING_DYNAMIC = "dynamic"
    PARAM_PATTERN = "pattern"
    PARAM_TM = "tm"
    PARAM_UL_PW = 'pul'
    PARAM_DL_PW = 'pdl'
    PARAM_BAND = "band"
    PARAM_MIMO = "mimo"

    # Test config keywords
    KEY_TBS_PATTERN = "tbs_pattern_on"
    KEY_DL_256_QAM = "256_qam_dl"
    KEY_UL_64_QAM = "64_qam_ul"

    class TransmissionMode(Enum):
        ''' Transmission modes for LTE (e.g., TM1, TM4, ..)

        '''
        TM1 = "TM1"
        TM2 = "TM2"
        TM3 = "TM3"
        TM4 = "TM4"
        TM7 = "TM7"
        TM8 = "TM8"
        TM9 = "TM9"

    class MimoMode(Enum):
        """ Mimo modes """

        MIMO_1x1 = "1x1"
        MIMO_2x2 = "2x2"
        MIMO_4x4 = "4x4"

    class SchedulingMode(Enum):
        ''' Traffic scheduling modes (e.g., STATIC, DYNAMIC)

        '''
        DYNAMIC = "DYNAMIC"
        STATIC = "STATIC"

    class DuplexMode(Enum):
        ''' DL/UL Duplex mode

        '''
        FDD = "FDD"
        TDD = "TDD"

    # RSRP signal levels thresholds (as reported by Android) in dBm/15KHz.
    # Excellent is set to -75 since callbox B Tx power is limited to -30 dBm
    downlink_rsrp_dictionary = {
        'excellent': -75,
        'high': -110,
        'medium': -115,
        'weak': -120
    }

    # Transmitted output power for the phone (dBm)
    uplink_signal_level_dictionary = {
        'max': 23,
        'high': 13,
        'medium': 3,
        'low': -20
    }

    # Total RBs for each bandwidth

    total_rbs_dictionary = {
        BtsBandwidth.LTE_BANDWIDTH_20MHz.value: 100,
        BtsBandwidth.LTE_BANDWIDTH_15MHz.value: 75,
        BtsBandwidth.LTE_BANDWIDTH_10MHz.value: 50,
        BtsBandwidth.LTE_BANDWIDTH_5MHz.value: 25,
        BtsBandwidth.LTE_BANDWIDTH_3MHz.value: 15,
        BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value: 6
    }

    # RB groups for each bandwidth

    rbg_dictionary = {
        BtsBandwidth.LTE_BANDWIDTH_20MHz.value: 4,
        BtsBandwidth.LTE_BANDWIDTH_15MHz.value: 4,
        BtsBandwidth.LTE_BANDWIDTH_10MHz.value: 3,
        BtsBandwidth.LTE_BANDWIDTH_5MHz.value: 2,
        BtsBandwidth.LTE_BANDWIDTH_3MHz.value: 2,
        BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value: 1
    }

    # Table of minimum number of RBs. This is needed to achieve peak
    # throughput.

    min_dl_rbs_dictionary = {
        BtsBandwidth.LTE_BANDWIDTH_20MHz.value: 16,
        BtsBandwidth.LTE_BANDWIDTH_15MHz.value: 12,
        BtsBandwidth.LTE_BANDWIDTH_10MHz.value: 9,
        BtsBandwidth.LTE_BANDWIDTH_5MHz.value: 4,
        BtsBandwidth.LTE_BANDWIDTH_3MHz.value: 4,
        BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value: 2
    }

    min_ul_rbs_dictionary = {
        BtsBandwidth.LTE_BANDWIDTH_20MHz.value: 8,
        BtsBandwidth.LTE_BANDWIDTH_15MHz.value: 6,
        BtsBandwidth.LTE_BANDWIDTH_10MHz.value: 4,
        BtsBandwidth.LTE_BANDWIDTH_5MHz.value: 2,
        BtsBandwidth.LTE_BANDWIDTH_3MHz.value: 2,
        BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value: 1
    }

    # Allowed bandwidth for each band.
    allowed_bandwidth_dictionary = {
        1: [5, 10, 15, 20],
        2: [1.4, 3, 5, 10, 15, 20],
        3: [1.4, 3, 5, 10, 15, 20],
        4: [1.4, 3, 5, 10, 15, 20],
        5: [1.4, 3, 5, 10],
        7: [5, 10, 15, 20],
        8: [1.4, 3, 5, 10],
        10: [5, 10, 15, 20],
        11: [5, 10],
        12: [1.4, 3, 5, 10],
        13: [5, 10],
        14: [5, 10],
        17: [5, 10],
        18: [5, 10, 15],
        19: [5, 10, 15],
        20: [5, 10, 15, 20],
        21: [5, 10, 15],
        22: [5, 10, 15, 20],
        24: [5, 10],
        25: [1.4, 3, 5, 10, 15, 20],
        26: [1.4, 3, 5, 10, 15],
        27: [1.4, 3, 5, 10],
        28: [3, 5, 10, 15, 20],
        29: [3, 5, 10],
        30: [5, 10],
        31: [1.4, 3, 5],
        32: [5, 10, 15, 20],
        33: [5, 10, 15, 20],
        34: [5, 10, 15],
        35: [1.4, 3, 5, 10, 15, 20],
        36: [1.4, 3, 5, 10, 15, 20],
        37: [5, 10, 15, 20],
        38: [20],
        39: [5, 10, 15, 20],
        40: [5, 10, 15, 20],
        41: [5, 10, 15, 20],
        42: [5, 10, 15, 20],
        43: [5, 10, 15, 20],
        44: [3, 5, 10, 15, 20],
        45: [5, 10, 15, 20],
        46: [10, 20],
        47: [10, 20],
        48: [5, 10, 15, 20],
        49: [10, 20],
        50: [3, 5, 10, 15, 20],
        51: [3, 5],
        52: [5, 10, 15, 20],
        65: [5, 10, 15, 20],
        66: [1.4, 3, 5, 10, 15, 20],
        67: [5, 10, 15, 20],
        68: [5, 10, 15],
        69: [5],
        70: [5, 10, 15],
        71: [5, 10, 15, 20],
        72: [1.4, 3, 5],
        73: [1.4, 3, 5],
        74: [1.4, 3, 5, 10, 15, 20],
        75: [5, 10, 15, 20],
        76: [5],
        85: [5, 10],
        252: [20],
        255: [20]
    }

    def __init__(self, anritsu, log, dut, test_config, calibration_table):
        """ Configures Anritsu system for LTE simulation with 1 basetation

        Loads a simple LTE simulation enviroment with 1 basestation.

        Args:
            anritsu: the Anritsu callbox controller
            log: a logger handle
            dut: the android device handler
            test_config: test configuration obtained from the config file
            calibration_table: a dictionary containing path losses for
                different bands.

        """

        super().__init__(anritsu, log, dut, test_config, calibration_table)
        self.file_path = 'C:\\Users\\MD8475{}\\Documents\\DAN_configs\\'.format(
            self.anritsu._md8475_version)

        if self.anritsu._md8475_version == 'A':
            self.sim_file_path = "{}{}.wnssp".format(self.file_path,
                                                     self.LTE_BASIC_SIM_FILE)
            self.cell_file_path = "{}{}.wnscp".format(self.file_path,
                                                      self.LTE_BASIC_CELL_FILE)
        else:
            self.sim_file_path = "{}{}.wnssp2".format(self.file_path,
                                                      self.LTE_BASIC_SIM_FILE)
            self.cell_file_path = "{}{}.wnscp2".format(
                self.file_path, self.LTE_BASIC_CELL_FILE)

        anritsu.load_simulation_paramfile(self.sim_file_path)
        anritsu.load_cell_paramfile(self.cell_file_path)

        if not dut.droid.telephonySetPreferredNetworkTypesForSubscription(
                NETWORK_MODE_LTE_ONLY,
                dut.droid.subscriptionGetDefaultSubId()):
            log.error("Couldn't set preferred network type.")
        else:
            log.info("Preferred network type set.")

        # Get TBS pattern setting from the test configuration
        if self.KEY_TBS_PATTERN not in test_config:
            self.log.warning("The key '{}' is not set in the config file. "
                             "Setting to true by default.".format(
                                 self.KEY_TBS_PATTERN))

        self.tbs_pattern_on = test_config.get(self.KEY_TBS_PATTERN, True)

        # Get the 256-QAM setting from the test configuration
        if self.KEY_DL_256_QAM not in test_config:
            self.log.warning("The key '{}' is not set in the config file. "
                             "Setting to false by default.".format(
                                 self.KEY_DL_256_QAM))

        self.dl_256_qam = test_config.get(self.KEY_DL_256_QAM, False)

        if self.dl_256_qam:
            if anritsu._md8475_version == 'A':
                self.log.warning("The key '{}' is set to true but MD8475A "
                                 "callbox doesn't support that modulation "
                                 "order.".format(self.KEY_DL_256_QAM))
                self.dl_256_qam = False
            else:
                self.bts1.lte_dl_modulation_order = "256QAM"

        # Get the 64-QAM setting from the test configuration
        if self.KEY_UL_64_QAM not in test_config:
            self.log.warning("The key '{}' is not set in the config file. "
                             "Setting to false by default.".format(
                                 self.KEY_UL_64_QAM))

        self.ul_64_qam = test_config.get(self.KEY_UL_64_QAM, False)

        if self.ul_64_qam:
            if anritsu._md8475_version == 'A':
                self.log.warning("The key '{}' is set to true but MD8475A "
                                 "callbox doesn't support that modulation "
                                 "order.".format(self.KEY_UL_64_QAM))
                self.ul_64_qam = False
            else:
                self.bts1.lte_ul_modulation_order = "64QAM"

    def parse_parameters(self, parameters):
        """ Configs an LTE simulation using a list of parameters.

        Calls the parent method first, then consumes parameters specific to LTE.

        Args:
            parameters: list of parameters
        """

        super().parse_parameters(parameters)

        # Setup band

        values = self.consume_parameter(parameters, self.PARAM_BAND, 1)

        if not values:
            raise ValueError(
                "The test name needs to include parameter '{}' followed by "
                "the required band number.".format(self.PARAM_BAND))

        band = values[1]

        self.set_band(self.bts1, band)

        # Set DL/UL frame configuration
        if self.get_duplex_mode(band) == self.DuplexMode.TDD:

            values = self.consume_parameter(parameters,
                                            self.PARAM_FRAME_CONFIG, 1)
            if not values:
                raise ValueError("When a TDD band is selected the frame "
                                 "structure has to be indicated with the '{}' "
                                 "parameter followed by a number from 0 to 6."
                                 .format(self.PARAM_FRAME_CONFIG))

            frame_config = int(values[1])

            self.set_dlul_configuration(self.bts1, frame_config)

        # Setup bandwidth

        values = self.consume_parameter(parameters, self.PARAM_BW, 1)

        if not values:
            raise ValueError(
                "The test name needs to include parameter {} followed by an "
                "int value (to indicate 1.4 MHz use 14).".format(
                    self.PARAM_BW))

        bw = float(values[1])

        if bw == 14:
            bw = 1.4

        self.set_channel_bandwidth(self.bts1, bw)

        # Setup mimo mode

        values = self.consume_parameter(parameters, self.PARAM_MIMO, 1)

        if not values:
            raise ValueError(
                "The test name needs to include parameter '{}' followed by the "
                "mimo mode.".format(self.PARAM_MIMO))

        for mimo_mode in LteSimulation.MimoMode:
            if values[1] == mimo_mode.value:
                self.set_mimo_mode(self.bts1, mimo_mode)
                break
        else:
            raise ValueError("The {} parameter needs to be followed by either "
                             "1x1, 2x2 or 4x4.".format(self.PARAM_MIMO))

        if (mimo_mode == LteSimulation.MimoMode.MIMO_4x4
                and self.anritsu._md8475_version == 'A'):
            raise ValueError("The test requires 4x4 MIMO, but that is not "
                             "supported by the MD8475A callbox.")

        self.set_mimo_mode(self.bts1, mimo_mode)

        # Setup transmission mode

        values = self.consume_parameter(parameters, self.PARAM_TM, 1)

        if not values:
            raise ValueError(
                "The test name needs to include parameter {} followed by an "
                "int value from 1 to 4 indicating transmission mode.".format(
                    self.PARAM_TM))

        for tm in LteSimulation.TransmissionMode:
            if values[1] == tm.value[2:]:
                self.set_transmission_mode(self.bts1, tm)
                break
        else:
            raise ValueError("The {} parameter needs to be followed by either "
                             "TM1, TM2, TM3, TM4, TM7, TM8 or TM9.".format(
                                 self.PARAM_MIMO))

        # Setup scheduling mode

        values = self.consume_parameter(parameters, self.PARAM_SCHEDULING, 1)

        if not values:
            scheduling = LteSimulation.SchedulingMode.STATIC
            self.log.warning(
                "The test name does not include the '{}' parameter. Setting to "
                "static by default.".format(self.PARAM_SCHEDULING))
        elif values[1] == self.PARAM_SCHEDULING_DYNAMIC:
            scheduling = LteSimulation.SchedulingMode.DYNAMIC
        elif values[1] == self.PARAM_SCHEDULING_STATIC:
            scheduling = LteSimulation.SchedulingMode.STATIC
        else:
            raise ValueError(
                "The test name parameter '{}' has to be followed by either "
                "'dynamic' or 'static'.".format(self.PARAM_SCHEDULING))

        if scheduling == LteSimulation.SchedulingMode.STATIC:

            values = self.consume_parameter(parameters, self.PARAM_PATTERN, 2)

            if not values:
                self.log.warning(
                    "The '{}' parameter was not set, using 100% RBs for both "
                    "DL and UL. To set the percentages of total RBs include "
                    "the '{}' parameter followed by two ints separated by an "
                    "underscore indicating downlink and uplink percentages."
                    .format(self.PARAM_PATTERN, self.PARAM_PATTERN))
                dl_pattern = 100
                ul_pattern = 100
            else:
                dl_pattern = int(values[1])
                ul_pattern = int(values[2])

            if not (0 <= dl_pattern <= 100 and 0 <= ul_pattern <= 100):
                raise ValueError(
                    "The scheduling pattern parameters need to be two "
                    "positive numbers between 0 and 100.")

            dl_rbs, ul_rbs = self.allocation_percentages_to_rbs(
                self.bts1, dl_pattern, ul_pattern)

            if self.dl_256_qam and bw == 1.4:
                mcs_dl = 26
            elif not self.dl_256_qam and self.tbs_pattern_on and bw != 1.4:
                mcs_dl = 28
            else:
                mcs_dl = 27

            if self.ul_64_qam:
                mcs_ul = 28
            else:
                mcs_ul = 23

            self.set_scheduling_mode(
                self.bts1,
                LteSimulation.SchedulingMode.STATIC,
                packet_rate=BtsPacketRate.LTE_MANUAL,
                nrb_dl=dl_rbs,
                nrb_ul=ul_rbs,
                mcs_ul=mcs_ul,
                mcs_dl=mcs_dl)

        else:

            self.set_scheduling_mode(self.bts1,
                                     LteSimulation.SchedulingMode.DYNAMIC)

        # Get uplink power

        ul_power = self.get_uplink_power_from_parameters(parameters)

        # Power is not set on the callbox until after the simulation is
        # started. Saving this value in a variable for later
        self.sim_ul_power = ul_power

        # Get downlink power

        dl_power = self.get_downlink_power_from_parameters(parameters)

        # Power is not set on the callbox until after the simulation is
        # started. Saving this value in a variable for later
        self.sim_dl_power = dl_power

    def get_uplink_power_from_parameters(self, parameters):
        """ Reads uplink power from a list of parameters. """

        values = self.consume_parameter(parameters, self.PARAM_UL_PW, 1)

        if not values or values[1] not in self.uplink_signal_level_dictionary:
            raise ValueError(
                "The test name needs to include parameter {} followed by one "
                "the following values: {}.".format(self.PARAM_UL_PW, [
                    val for val in self.uplink_signal_level_dictionary.keys()
                ]))

        return self.uplink_signal_level_dictionary[values[1]]

    def get_downlink_power_from_parameters(self, parameters):
        """ Reads downlink power from a list of parameters. """

        values = self.consume_parameter(parameters, self.PARAM_DL_PW, 1)

        if values:
            if values[1] not in self.downlink_rsrp_dictionary:
                raise ValueError("Invalid signal level value {}.".format(
                    values[1]))
            else:
                return self.downlink_rsrp_dictionary[values[1]]
        else:
            # Use default value
            power = self.downlink_rsrp_dictionary['excellent']
            self.log.info(
                "No DL signal level value was indicated in the test "
                "parameters. Using default value of {} RSRP.".format(power))
            return power

    def set_downlink_rx_power(self, bts, rsrp):
        """ Sets downlink rx power in RSRP using calibration

        Lte simulation overrides this method so that it can convert from
        RSRP to total signal power transmitted from the basestation.

        Args:
            bts: the base station in which to change the signal level
            rsrp: desired rsrp, contained in a key value pair
        """

        power = self.rsrp_to_signal_power(rsrp, bts)

        self.log.info(
            "Setting downlink signal level to {} RSRP ({} dBm)".format(
                rsrp, power))

        # Use parent method to set signal level
        super().set_downlink_rx_power(bts, power)

    def downlink_calibration(self,
                             bts,
                             rat=None,
                             power_units_conversion_func=None):
        """ Computes downlink path loss and returns the calibration value

        The bts needs to be set at the desired config (bandwidth, mode, etc)
        before running the calibration. The phone also needs to be attached
        to the desired basesation for calibration

        Args:
            bts: basestation handle
            rat: ignored, replaced by 'lteRsrp'
            power_units_conversion_func: ignored, replaced by
                self.rsrp_to_signal_power

        Returns:
            Dowlink calibration value and measured DL power. Note that the
            phone only reports RSRP of the primary chain
        """

        return super().downlink_calibration(
            bts,
            rat='lteRsrp',
            power_units_conversion_func=self.rsrp_to_signal_power)

    def rsrp_to_signal_power(self, rsrp, bts):
        """ Converts rsrp to total band signal power

        RSRP is measured per subcarrier, so total band power needs to be
        multiplied by the number of subcarriers being used.

        Args:
            rsrp: desired rsrp in dBm
            bts: basestation handler for which the unit conversion is done

        Returns:
            Total band signal power in dBm
        """

        bandwidth = bts.bandwidth

        if bandwidth == BtsBandwidth.LTE_BANDWIDTH_20MHz.value:  # 100 RBs
            power = rsrp + 30.79
        elif bandwidth == BtsBandwidth.LTE_BANDWIDTH_15MHz.value:  # 75 RBs
            power = rsrp + 29.54
        elif bandwidth == BtsBandwidth.LTE_BANDWIDTH_10MHz.value:  # 50 RBs
            power = rsrp + 27.78
        elif bandwidth == BtsBandwidth.LTE_BANDWIDTH_5MHz.value:  # 25 RBs
            power = rsrp + 24.77
        elif bandwidth == BtsBandwidth.LTE_BANDWIDTH_3MHz.value:  # 15 RBs
            power = rsrp + 22.55
        elif bandwidth == BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value:  # 6 RBs
            power = rsrp + 18.57
        else:
            raise ValueError("Invalid bandwidth value.")

        return power

    def maximum_downlink_throughput(self):
        """ Calculates maximum achievable downlink throughput in the current
            simulation state.

        Returns:
            Maximum throughput in mbps.

        """

        return self.bts_maximum_downlink_throughtput(self.bts1)

    def bts_maximum_downlink_throughtput(self, bts):
        """ Calculates maximum achievable downlink throughput for the selected
        basestation.

        Args:
            bts: basestation handle

        Returns:
            Maximum throughput in mbps.

        """

        bandwidth = bts.bandwidth
        rb_ratio = float(bts.nrb_dl) / self.total_rbs_dictionary[bandwidth]
        streams = float(bts.dl_antenna)
        mcs = bts.lte_mcs_dl

        max_rate_per_stream = None

        if not self.dl_256_qam and self.tbs_pattern_on and mcs == "28":
            max_rate_per_stream = {
                BtsBandwidth.LTE_BANDWIDTH_3MHz.value: 9.96,
                BtsBandwidth.LTE_BANDWIDTH_5MHz.value: 17.0,
                BtsBandwidth.LTE_BANDWIDTH_10MHz.value: 34.7,
                BtsBandwidth.LTE_BANDWIDTH_15MHz.value: 52.7,
                BtsBandwidth.LTE_BANDWIDTH_20MHz.value: 72.2
            }.get(bandwidth, None)
        if not self.dl_256_qam and self.tbs_pattern_on and mcs == "27":
            max_rate_per_stream = {
                BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value: 2.94,
            }.get(bandwidth, None)
        elif not self.dl_256_qam and not self.tbs_pattern_on and mcs == "27":
            max_rate_per_stream = {
                BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value: 2.87,
                BtsBandwidth.LTE_BANDWIDTH_3MHz.value: 7.7,
                BtsBandwidth.LTE_BANDWIDTH_5MHz.value: 14.4,
                BtsBandwidth.LTE_BANDWIDTH_10MHz.value: 28.7,
                BtsBandwidth.LTE_BANDWIDTH_15MHz.value: 42.3,
                BtsBandwidth.LTE_BANDWIDTH_20MHz.value: 57.7
            }.get(bandwidth, None)
        elif self.dl_256_qam and self.tbs_pattern_on and mcs == "27":
            max_rate_per_stream = {
                BtsBandwidth.LTE_BANDWIDTH_3MHz.value: 13.2,
                BtsBandwidth.LTE_BANDWIDTH_5MHz.value: 22.9,
                BtsBandwidth.LTE_BANDWIDTH_10MHz.value: 46.3,
                BtsBandwidth.LTE_BANDWIDTH_15MHz.value: 72.2,
                BtsBandwidth.LTE_BANDWIDTH_20MHz.value: 93.9
            }.get(bandwidth, None)
        elif self.dl_256_qam and self.tbs_pattern_on and mcs == "26":
            max_rate_per_stream = {
                BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value: 3.96,
            }.get(bandwidth, None)
        elif self.dl_256_qam and not self.tbs_pattern_on and mcs == "27":
            max_rate_per_stream = {
                BtsBandwidth.LTE_BANDWIDTH_3MHz.value: 11.3,
                BtsBandwidth.LTE_BANDWIDTH_5MHz.value: 19.8,
                BtsBandwidth.LTE_BANDWIDTH_10MHz.value: 44.1,
                BtsBandwidth.LTE_BANDWIDTH_15MHz.value: 68.1,
                BtsBandwidth.LTE_BANDWIDTH_20MHz.value: 88.4
            }.get(bandwidth, None)
        elif self.dl_256_qam and not self.tbs_pattern_on and mcs == "26":
            max_rate_per_stream = {
                BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value: 3.96,
            }.get(bandwidth, None)

        if not max_rate_per_stream:
            raise NotImplementedError(
                "The calculation for tbs pattern = {} "
                "and mcs = {} is not implemented.".format(
                    "FULLALLOCATION" if self.tbs_pattern_on else "OFF", mcs))

        return max_rate_per_stream * streams * rb_ratio

    def maximum_uplink_throughput(self):
        """ Calculates maximum achievable uplink throughput in the current
            simulation state.

        Returns:
            Maximum throughput in mbps.

        """

        return self.bts_maximum_uplink_throughtput(self.bts1)

    def bts_maximum_uplink_throughtput(self, bts):
        """ Calculates maximum achievable uplink throughput for the selected
        basestation.

        Args:
            bts: basestation handle

        Returns:
            Maximum throughput in mbps.

        """

        bandwidth = bts.bandwidth
        rb_ratio = float(bts.nrb_ul) / self.total_rbs_dictionary[bandwidth]
        mcs = bts.lte_mcs_ul

        max_rate_per_stream = None
        if mcs == "23" and not self.ul_64_qam:
            max_rate_per_stream = {
                BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value: 2.85,
                BtsBandwidth.LTE_BANDWIDTH_3MHz.value: 7.18,
                BtsBandwidth.LTE_BANDWIDTH_5MHz.value: 12.1,
                BtsBandwidth.LTE_BANDWIDTH_10MHz.value: 24.5,
                BtsBandwidth.LTE_BANDWIDTH_15MHz.value: 36.5,
                BtsBandwidth.LTE_BANDWIDTH_20MHz.value: 49.1
            }.get(bandwidth, None)
        elif mcs == "28" and self.ul_64_qam:
            max_rate_per_stream = {
                BtsBandwidth.LTE_BANDWIDTH_1dot4MHz.value: 4.2,
                BtsBandwidth.LTE_BANDWIDTH_3MHz.value: 10.5,
                BtsBandwidth.LTE_BANDWIDTH_5MHz.value: 17.2,
                BtsBandwidth.LTE_BANDWIDTH_10MHz.value: 35.3,
                BtsBandwidth.LTE_BANDWIDTH_15MHz.value: 53.0,
                BtsBandwidth.LTE_BANDWIDTH_20MHz.value: 72.6
            }.get(bandwidth, None)

        if not max_rate_per_stream:
            raise NotImplementedError("The calculation fir mcs = {} is not "
                                      "implemented.".format(
                                          "FULLALLOCATION" if
                                          self.tbs_pattern_on else "OFF", mcs))

        return max_rate_per_stream * rb_ratio

    def set_transmission_mode(self, bts, tmode):
        """ Sets the transmission mode for the LTE basetation

        Args:
            bts: basestation handle
            tmode: Enum list from class 'TransmissionModeLTE'
        """

        # If the selected transmission mode does not support the number of DL
        # antennas, throw an exception.
        if (tmode in [self.TransmissionMode.TM1, self.TransmissionMode.TM7]
                and bts.dl_antenna != '1'):
            # TM1 and TM7 only support 1 DL antenna
            raise ValueError("{} allows only one DL antenna. Change the "
                             "number of DL antennas before setting the "
                             "transmission mode.".format(tmode.value))
        elif tmode == self.TransmissionMode.TM8 and bts.dl_antenna != '2':
            # TM8 requires 2 DL antennas
            raise ValueError("TM2 requires two DL antennas. Change the "
                             "number of DL antennas before setting the "
                             "transmission mode.")
        elif (tmode in [
                self.TransmissionMode.TM2, self.TransmissionMode.TM3,
                self.TransmissionMode.TM4, self.TransmissionMode.TM9
        ] and bts.dl_antenna == '1'):
            # TM2, TM3, TM4 and TM9 require 2 or 4 DL antennas
            raise ValueError("{} requires at least two DL atennas. Change the "
                             "number of DL antennas before setting the "
                             "transmission mode.".format(tmode.value))

        # The TM mode is allowed for the current number of DL antennas, so it
        # is safe to change this setting now
        bts.transmode = tmode.value

        time.sleep(5)  # It takes some time to propagate the new settings

    def set_mimo_mode(self, bts, mimo):
        """ Sets the number of DL antennas for the desired MIMO mode.

        Args:
            bts: basestation handle
            mimo: object of class MimoMode
        """

        # If the requested mimo mode is not compatible with the current TM,
        # warn the user before changing the value.

        if mimo == self.MimoMode.MIMO_1x1:
            if bts.transmode not in [
                    self.TransmissionMode.TM1, self.TransmissionMode.TM7
            ]:
                self.log.warning(
                    "Using only 1 DL antennas is not allowed with "
                    "the current transmission mode. Changing the "
                    "number of DL antennas will override this "
                    "setting.")
            bts.dl_antenna = 1
        elif mimo == self.MimoMode.MIMO_2x2:
            if bts.transmode not in [
                    self.TransmissionMode.TM2, self.TransmissionMode.TM3,
                    self.TransmissionMode.TM4, self.TransmissionMode.TM8,
                    self.TransmissionMode.TM9
            ]:
                self.log.warning("Using two DL antennas is not allowed with "
                                 "the current transmission mode. Changing the "
                                 "number of DL antennas will override this "
                                 "setting.")
            bts.dl_antenna = 2
        elif mimo == self.MimoMode.MIMO_4x4:
            if bts.transmode not in [
                    self.TransmissionMode.TM2, self.TransmissionMode.TM3,
                    self.TransmissionMode.TM4, self.TransmissionMode.TM9
            ]:
                self.log.warning("Using four DL antennas is not allowed with "
                                 "the current transmission mode. Changing the "
                                 "number of DL antennas will override this "
                                 "setting.")

            bts.dl_antenna = 4
        else:
            RuntimeError("The requested MIMO mode is not supported.")

    def set_scheduling_mode(self,
                            bts,
                            scheduling,
                            packet_rate=None,
                            mcs_dl=None,
                            mcs_ul=None,
                            nrb_dl=None,
                            nrb_ul=None):
        """ Sets the scheduling mode for LTE

        Args:
            bts: basestation handle
            scheduling: DYNAMIC or STATIC scheduling (Enum list)
            mcs_dl: Downlink MCS (only for STATIC scheduling)
            mcs_ul: Uplink MCS (only for STATIC scheduling)
            nrb_dl: Number of RBs for downlink (only for STATIC scheduling)
            nrb_ul: Number of RBs for uplink (only for STATIC scheduling)
        """

        bts.lte_scheduling_mode = scheduling.value

        if scheduling == self.SchedulingMode.STATIC:

            if not packet_rate:
                raise RuntimeError("Packet rate needs to be indicated when "
                                   "selecting static scheduling.")

            bts.packet_rate = packet_rate
            bts.tbs_pattern = "FULLALLOCATION" if self.tbs_pattern_on else "OFF"

            if packet_rate == BtsPacketRate.LTE_MANUAL:

                if not (mcs_dl and mcs_ul and nrb_dl and nrb_ul):
                    raise RuntimeError("When using manual packet rate the "
                                       "number of dl/ul RBs and the dl/ul "
                                       "MCS needs to be indicated with the "
                                       "optional arguments.")

                bts.lte_mcs_dl = mcs_dl
                bts.lte_mcs_ul = mcs_ul
                bts.nrb_dl = nrb_dl
                bts.nrb_ul = nrb_ul

        time.sleep(5)  # It takes some time to propagate the new settings

    def allocation_percentages_to_rbs(self, bts, dl, ul):
        """ Converts usage percentages to number of DL/UL RBs

        Because not any number of DL/UL RBs can be obtained for a certain
        bandwidth, this function calculates the number of RBs that most
        closely matches the desired DL/UL percentages.

        Args:
            bts: base station handle
            dl: desired percentage of downlink RBs
            ul: desired percentage of uplink RBs
        Returns:
            a tuple indicating the number of downlink and uplink RBs
        """

        # Validate the arguments
        if (not 0 <= dl <= 100) or (not 0 <= ul <= 100):
            raise ValueError("The percentage of DL and UL RBs have to be two "
                             "positive between 0 and 100.")

        # Get the available number of RBs for the channel bandwidth
        bw = bts.bandwidth
        # Get the current transmission mode
        tm = bts.transmode
        # Get min and max values from tables
        max_rbs = self.total_rbs_dictionary[bw]
        min_dl_rbs = self.min_dl_rbs_dictionary[bw]
        min_ul_rbs = self.min_ul_rbs_dictionary[bw]

        def percentage_to_amount(min_val, max_val, percentage):
            """ Returns the integer between min_val and max_val that is closest
            to percentage/100*max_val
            """

            # Calculate the value that corresponds to the required percentage.
            closest_int = round(max_val * percentage / 100)
            # Cannot be less than min_val
            closest_int = max(closest_int, min_val)
            # RBs cannot be more than max_rbs
            closest_int = min(closest_int, max_val)

            return closest_int

        # Calculate the number of DL RBs

        # Get the number of DL RBs that corresponds to
        #  the required percentage.
        desired_dl_rbs = percentage_to_amount(
            min_val=min_dl_rbs, max_val=max_rbs, percentage=dl)

        if (tm == self.TransmissionMode.TM3.value
                or tm == self.TransmissionMode.TM4.value):

            # For TM3 and TM4 the number of DL RBs needs to be max_rbs or a
            # multiple of the RBG size

            if desired_dl_rbs == max_rbs:
                dl_rbs = max_rbs
            else:
                dl_rbs = (math.ceil(desired_dl_rbs / self.rbg_dictionary[bw]) *
                          self.rbg_dictionary[bw])

        else:
            # The other TMs allow any number of RBs between 1 and max_rbs
            dl_rbs = desired_dl_rbs

        # Calculate the number of UL RBs

        # Get the number of UL RBs that corresponds
        # to the required percentage
        desired_ul_rbs = percentage_to_amount(
            min_val=min_ul_rbs, max_val=max_rbs, percentage=ul)

        # Create a list of all possible UL RBs assignment
        # The standard allows any number that can be written as
        # 2**a * 3**b * 5**c for any combination of a, b and c.

        def pow_range(max_value, base):
            """ Returns a range of all possible powers of base under
              the given max_value.
          """
            return range(int(math.ceil(math.log(max_value, base))))

        possible_ul_rbs = [
            2**a * 3**b * 5**c
            for a in pow_range(max_rbs, 2) for b in pow_range(max_rbs, 3)
            for c in pow_range(max_rbs, 5) if 2**a * 3**b * 5**c <= max_rbs
        ]

        # Find the value in the list that is closest to desired_ul_rbs
        differences = [abs(rbs - desired_ul_rbs) for rbs in possible_ul_rbs]
        ul_rbs = possible_ul_rbs[differences.index(min(differences))]

        # Report what are the obtained RB percentages
        self.log.info("Requested a {}% / {}% RB allocation. Closest possible "
                      "percentages are {}% / {}%.".format(
                          dl, ul,
                          round(100 * dl_rbs / max_rbs),
                          round(100 * ul_rbs / max_rbs)))

        return dl_rbs, ul_rbs

    def set_channel_bandwidth(self, bts, bandwidth):
        """ Sets the LTE channel bandwidth (MHz)

        Args:
            bts: basestation handle
            bandwidth: desired bandwidth (MHz)
        """
        if bandwidth == 20:
            bts.bandwidth = BtsBandwidth.LTE_BANDWIDTH_20MHz
        elif bandwidth == 15:
            bts.bandwidth = BtsBandwidth.LTE_BANDWIDTH_15MHz
        elif bandwidth == 10:
            bts.bandwidth = BtsBandwidth.LTE_BANDWIDTH_10MHz
        elif bandwidth == 5:
            bts.bandwidth = BtsBandwidth.LTE_BANDWIDTH_5MHz
        elif bandwidth == 3:
            bts.bandwidth = BtsBandwidth.LTE_BANDWIDTH_3MHz
        elif bandwidth == 1.4:
            bts.bandwidth = BtsBandwidth.LTE_BANDWIDTH_1dot4MHz
        else:
            msg = "Bandwidth = {} MHz is not valid for LTE".format(bandwidth)
            self.log.Error(msg)
            raise ValueError(msg)
        time.sleep(5)  # It takes some time to propagate the new settings

    def set_dlul_configuration(self, bts, config):
        """ Sets the frame structure for TDD bands.

        Args:
            config: the desired frame structure. An int between 0 and 6.
        """

        if not 0 <= config <= 6:
            raise ValueError("The frame structure configuration has to be a "
                             "number between 0 and 6")

        bts.uldl_configuration = config

        # Wait for the setting to propagate
        time.sleep(5)

    def calibrate(self):
        """ Calculates UL and DL path loss if it wasn't done before

        This method overrides the baseclass specifically for LTE calibration.
        For LTE cal, the simulation is set to TM1 and 1 antenna.

        """

        # Set in TM1 mode and 1 antenna for downlink calibration for LTE
        init_dl_antenna = None
        init_transmode = None
        if int(self.bts1.dl_antenna) != 1:
            init_dl_antenna = self.bts1.dl_antenna
            init_transmode = self.bts1.transmode
            self.bts1.dl_antenna = 1
            self.bts1.transmode = "TM1"
            time.sleep(5)  # It takes some time to propagate the new settings

        super().calibrate()

        if init_dl_antenna is not None:
            self.bts1.dl_antenna = init_dl_antenna
            self.bts1.transmode = init_transmode
            time.sleep(5)  # It takes some time to propagate the new settings

    def get_duplex_mode(self, band):
        """ Determines if the band uses FDD or TDD duplex mode

        Args:
            band: a band number
        Returns:
            an variable of class DuplexMode indicating if band is FDD or TDD
        """

        if 33 <= int(band) <= 46:
            return self.DuplexMode.TDD
        else:
            return self.DuplexMode.FDD

    def set_band(self, bts, band, calibrate_if_necessary=True):
        """ Sets the right duplex mode before switching to a new band.

        Args:
            bts: basestation handle
            band: desired band
            calibrate_if_necessary: if False calibration will be skipped
        """

        bts.duplex_mode = self.get_duplex_mode(band).value

        super().set_band(bts, band, calibrate_if_necessary)
