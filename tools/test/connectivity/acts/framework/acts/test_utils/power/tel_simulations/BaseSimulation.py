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
from enum import Enum

import numpy as np

from acts.controllers.anritsu_lib._anritsu_utils import AnritsuError
from acts.controllers.anritsu_lib.md8475a import BtsNumber
from acts.test_utils.tel.tel_test_utils import get_telephony_signal_strength
from acts.test_utils.tel.tel_test_utils import toggle_airplane_mode
from acts.test_utils.tel.tel_test_utils import toggle_cell_data_roaming


class BaseSimulation():
    """ Base class for an Anritsu Simulation abstraction.

    Classes that inherit from this base class implement different simulation
    setups. The base class contains methods that are common to all simulation
    configurations.

    """

    NUM_UL_CAL_READS = 3
    NUM_DL_CAL_READS = 5
    DL_CAL_TARGET_POWER = {'A': -15.0, 'B': -35.0}
    MAX_BTS_INPUT_POWER = 30
    MAX_PHONE_OUTPUT_POWER = 23
    DL_MAX_POWER = {'A': -10.0, 'B': -30.0}
    UL_MIN_POWER = -60.0

    # Key to read the calibration setting from the test_config dictionary.
    KEY_CALIBRATION = "calibration"

    # Time in seconds to wait for the phone to settle
    # after attaching to the base station.
    SETTLING_TIME = 10

    # Time in seconds to wait for the phone to attach
    # to the basestation after toggling airplane mode.
    ATTACH_WAITING_TIME = 120

    # Max retries before giving up attaching the phone
    ATTACH_MAX_RETRIES = 3

    def __init__(self, anritsu, log, dut, test_config, calibration_table):
        """ Initializes the Simulation object.

        Keeps a reference to the callbox, log and dut handlers and
        initializes the class attributes.

        Args:
            anritsu: the Anritsu callbox controller
            log: a logger handle
            dut: the android device handler
            test_config: test configuration obtained from the config file
            calibration_table: a dictionary containing path losses for
                different bands.
        """

        self.anritsu = anritsu
        self.log = log
        self.dut = dut
        self.calibration_table = calibration_table

        # Turn calibration on or off depending on the test config value. If the
        # key is not present, set to False by default
        if self.KEY_CALIBRATION not in test_config:
            self.log.warning("The '{} 'key is not set in the testbed "
                             "parameters. Setting to off by default. To "
                             "turn calibration on, include the key with "
                             "a true/false value.".format(
                                 self.KEY_CALIBRATION))

        self.calibration_required = test_config.get(self.KEY_CALIBRATION,
                                                    False)

        # Gets BTS1 since this sim only has 1 BTS
        self.bts1 = self.anritsu.get_BTS(BtsNumber.BTS1)

        # Store the current calibrated band
        self.current_calibrated_band = None

        # Path loss measured during calibration
        self.dl_path_loss = None
        self.ul_path_loss = None

        # Target signal levels obtained during configuration
        self.sim_dl_power = None
        self.sim_ul_power = None

        # Set to default APN
        log.info("Setting preferred APN to anritsu1.com.")
        dut.droid.telephonySetAPN("anritsu1.com", "anritsu1.com")

        # Enable roaming on the phone
        toggle_cell_data_roaming(self.dut, True)

    def start(self):
        """ Start simulation.

        Starts the simulation in the Anritsu Callbox.

        """

        # Make sure airplane mode is on so the phone won't attach right away
        toggle_airplane_mode(self.log, self.dut, True)

        # Wait for airplane mode setting to propagate
        time.sleep(2)

        # Start simulation if it wasn't started
        self.anritsu.start_simulation()

    def attach(self):
        """ Attach the phone to the basestation.

        Sets a good signal level, toggles airplane mode
        and waits for the phone to attach.

        Returns:
            True if the phone was able to attach, False if not.
        """

        # Turn on airplane mode
        toggle_airplane_mode(self.log, self.dut, True)

        # Wait for airplane mode setting to propagate
        time.sleep(2)

        # Provide a good signal power for the phone to attach easily
        self.bts1.input_level = -10
        time.sleep(2)
        self.bts1.output_level = -30

        # Try to attach the phone.
        for i in range(self.ATTACH_MAX_RETRIES):

            try:

                # Turn off airplane mode
                toggle_airplane_mode(self.log, self.dut, False)

                # Wait for the phone to attach.
                self.anritsu.wait_for_registration_state(
                    time_to_wait=self.ATTACH_WAITING_TIME)

            except AnritsuError as e:

                # The phone failed to attach
                self.log.info(
                    "UE failed to attach on attempt number {}.".format(i + 1))
                self.log.info("Error message: {}".format(str(e)))

                # Turn airplane mode on to prepare the phone for a retry.
                toggle_airplane_mode(self.log, self.dut, True)

                # Wait for APM to propagate
                time.sleep(3)

                # Retry
                if i < self.ATTACH_MAX_RETRIES - 1:
                    # Retry
                    continue
                else:
                    # No more retries left. Return False.
                    return False

            else:
                # The phone attached successfully.
                time.sleep(self.SETTLING_TIME)
                self.log.info("UE attached to the callbox.")
                break

        # Set signal levels obtained from the test parameters
        if self.sim_dl_power:
            self.set_downlink_rx_power(self.bts1, self.sim_dl_power)
            time.sleep(2)
        if self.sim_ul_power:
            self.set_uplink_tx_power(self.bts1, self.sim_ul_power)
            time.sleep(2)

        return True

    def detach(self):
        """ Detach the phone from the basestation.

        Turns airplane mode and resets basestation.
        """

        # Set the DUT to airplane mode so it doesn't see the
        # cellular network going off
        toggle_airplane_mode(self.log, self.dut, True)

        # Wait for APM to propagate
        time.sleep(2)

        # Power off basestation
        self.anritsu.set_simulation_state_to_poweroff()

    def stop(self):
        """  Detach phone from the basestation by stopping the simulation.

        Send stop command to anritsu and turn on airplane mode.

        """

        # Set the DUT to airplane mode so it doesn't see the
        # cellular network going off
        toggle_airplane_mode(self.log, self.dut, True)

        # Wait for APM to propagate
        time.sleep(2)

        # Stop the simulation
        self.anritsu.stop_simulation()

    def parse_parameters(self, parameters):
        """ Configures simulation using a list of parameters.

        Consumes parameters from a list.
        Children classes need to call this method first.

        Args:
            parameters: list of parameters
        """

        pass

    def consume_parameter(self, parameters, parameter_name, num_values=0):
        """ Parses a parameter from a list.

        Allows to parse the parameter list. Will delete parameters from the
        list after consuming them to ensure that they are not used twice.

        Args:
            parameters: list of parameters
            parameter_name: keyword to look up in the list
            num_values: number of arguments following the
                parameter name in the list
        Returns:
            A list containing the parameter name and the following num_values
            arguments
        """

        try:
            i = parameters.index(parameter_name)
        except ValueError:
            # parameter_name is not set
            return []

        return_list = []

        try:
            for j in range(num_values + 1):
                return_list.append(parameters.pop(i))
        except IndexError:
            raise ValueError(
                "Parameter {} has to be followed by {} values.".format(
                    parameter_name, num_values))

        return return_list

    def set_downlink_rx_power(self, bts, signal_level):
        """ Sets downlink rx power using calibration if available

        Args:
            bts: the base station in which to change the signal level
            signal_level: desired downlink received power, can be either a
            key value pair, an int or a float
        """

        # Obtain power value if the provided signal_level is a key value pair
        if isinstance(signal_level, Enum):
            power = signal_level.value
        else:
            power = signal_level

        # Try to use measured path loss value. If this was not set, it will
        # throw an TypeError exception
        try:
            calibrated_power = round(power + self.dl_path_loss)
            if (calibrated_power >
                    self.DL_MAX_POWER[self.anritsu._md8475_version]):
                self.log.warning(
                    "Cannot achieve phone DL Rx power of {} dBm. Requested TX "
                    "power of {} dBm exceeds callbox limit!".format(
                        power, calibrated_power))
                calibrated_power = self.DL_MAX_POWER[
                    self.anritsu._md8475_version]
                self.log.warning(
                    "Setting callbox Tx power to max possible ({} dBm)".format(
                        calibrated_power))

            self.log.info(
                "Requested phone DL Rx power of {} dBm, setting callbox Tx "
                "power at {} dBm".format(power, calibrated_power))
            bts.output_level = calibrated_power
            time.sleep(2)
            # Power has to be a natural number so calibration wont be exact.
            # Inform the actual received power after rounding.
            self.log.info(
                "Phone downlink received power is {0:.2f} dBm".format(
                    calibrated_power - self.dl_path_loss))
        except TypeError:
            bts.output_level = round(power)
            self.log.info("Phone downlink received power set to {} (link is "
                          "uncalibrated).".format(round(power)))

    def set_uplink_tx_power(self, bts, signal_level):
        """ Sets uplink tx power using calibration if available

        Args:
            bts: the base station in which to change the signal level
            signal_level: desired uplink transmitted power, can be either a
            key value pair, an int or a float
        """

        # Obtain power value if the provided signal_level is a key value pair
        if isinstance(signal_level, Enum):
            power = signal_level.value
        else:
            power = signal_level

        # Try to use measured path loss value. If this was not set, it will
        # throw an TypeError exception
        try:
            calibrated_power = round(power - self.ul_path_loss)
            if calibrated_power < self.UL_MIN_POWER:
                self.log.warning(
                    "Cannot achieve phone UL Tx power of {} dBm. Requested UL "
                    "power of {} dBm exceeds callbox limit!".format(
                        power, calibrated_power))
                calibrated_power = self.UL_MIN_POWER
                self.log.warning(
                    "Setting UL Tx power to min possible ({} dBm)".format(
                        calibrated_power))

            self.log.info(
                "Requested phone UL Tx power of {} dBm, setting callbox Rx "
                "power at {} dBm".format(power, calibrated_power))
            bts.input_level = calibrated_power
            time.sleep(2)
            # Power has to be a natural number so calibration wont be exact.
            # Inform the actual transmitted power after rounding.
            self.log.info(
                "Phone uplink transmitted power is {0:.2f} dBm".format(
                    calibrated_power + self.ul_path_loss))
        except TypeError:
            bts.input_level = round(power)
            self.log.info("Phone uplink transmitted power set to {} (link is "
                          "uncalibrated).".format(round(power)))

    def calibrate(self):
        """ Calculates UL and DL path loss if it wasn't done before.

        """
        # SET TBS pattern for calibration
        self.bts1.tbs_pattern = "FULLALLOCATION" if self.tbs_pattern_on else "OFF"

        if self.dl_path_loss and self.ul_path_loss:
            self.log.info("Measurements are already calibrated.")

        # Attach the phone to the base station
        if not self.attach():
            self.log.info(
                "Skipping calibration because the phone failed to attach.")
            return

        # If downlink or uplink were not yet calibrated, do it now
        if not self.dl_path_loss:
            self.dl_path_loss = self.downlink_calibration(self.bts1)
        if not self.ul_path_loss:
            self.ul_path_loss = self.uplink_calibration(self.bts1)

        # Detach after calibrating
        self.detach()
        time.sleep(2)

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
            rat: desired RAT to calibrate (matching the label reported by
                the phone)
            power_units_conversion_func: a function to convert the units
                reported by the phone to dBm. needs to take two arguments: the
                reported signal level and bts. use None if no conversion is
                needed.
        Returns:
            Dowlink calibration value and measured DL power.
        """

        # Check if this parameter was set. Child classes may need to override
        # this class passing the necessary parameters.
        if not rat:
            raise ValueError(
                "The parameter 'rat' has to indicate the RAT being used as "
                "reported by the phone.")

        # Set BTS to a good output level to minimize measurement error
        init_output_level = bts.output_level
        initial_screen_timeout = self.dut.droid.getScreenTimeout()
        bts.output_level = self.DL_CAL_TARGET_POWER[
            self.anritsu._md8475_version]

        # Set phone sleep time out
        self.dut.droid.setScreenTimeout(1800)
        self.dut.droid.goToSleepNow()
        time.sleep(2)

        # Starting first the IP traffic (UDP): Using always APN 1
        if not self.tbs_pattern_on:
            try:
                cmd = 'OPERATEIPTRAFFIC START,1'
                self.anritsu.send_command(cmd)
            except AnritsuError as inst:
                self.log.warning(
                    "{}\n".format(inst))  # Typically RUNNING already
            time.sleep(4)

        down_power_measured = []
        for i in range(0, self.NUM_DL_CAL_READS):
            # For some reason, the RSRP gets updated on Screen ON event
            self.dut.droid.wakeUpNow()
            time.sleep(4)
            signal_strength = get_telephony_signal_strength(self.dut)
            down_power_measured.append(signal_strength[rat])
            self.dut.droid.goToSleepNow()
            time.sleep(5)

        # Stop the IP traffic (UDP)
        if not self.tbs_pattern_on:
            try:
                cmd = 'OPERATEIPTRAFFIC STOP,1'
                self.anritsu.send_command(cmd)
            except AnritsuError as inst:
                self.log.warning(
                    "{}\n".format(inst))  # Typically STOPPED already
            time.sleep(2)

        # Reset phone and bts to original settings
        self.dut.droid.goToSleepNow()
        self.dut.droid.setScreenTimeout(initial_screen_timeout)
        bts.output_level = init_output_level
        time.sleep(2)

        # Calculate the mean of the measurements
        reported_asu_power = np.nanmean(down_power_measured)

        # Convert from RSRP to signal power
        if power_units_conversion_func:
            avg_down_power = power_units_conversion_func(
                reported_asu_power, bts)
        else:
            avg_down_power = reported_asu_power

        # Calculate Path Loss
        dl_target_power = self.DL_CAL_TARGET_POWER[
            self.anritsu._md8475_version]
        down_call_path_loss = dl_target_power - avg_down_power

        # Validate the result
        if not 0 < down_call_path_loss < 100:
            raise RuntimeError(
                "Downlink calibration failed. The calculated path loss value "
                "was {} dBm.".format(down_call_path_loss))

        self.log.info(
            "Measured downlink path loss: {} dB".format(down_call_path_loss))

        return down_call_path_loss

    def uplink_calibration(self, bts):
        """ Computes uplink path loss and returns the calibration value

        The bts needs to be set at the desired config (bandwidth, mode, etc)
        before running the calibration. The phone also neeeds to be attached
        to the desired basesation for calibration

        Args:
            bts: basestation handle

        Returns:
            Uplink calibration value and measured UL power
        """

        # Set BTS1 to maximum input allowed in order to perform
        # uplink calibration
        target_power = self.MAX_PHONE_OUTPUT_POWER
        initial_input_level = bts.input_level
        initial_screen_timeout = self.dut.droid.getScreenTimeout()
        bts.input_level = self.MAX_BTS_INPUT_POWER

        # Set phone sleep time out
        self.dut.droid.setScreenTimeout(1800)
        self.dut.droid.wakeUpNow()
        time.sleep(2)

        # Starting first the IP traffic (UDP): Using always APN 1
        if not self.tbs_pattern_on:
            try:
                cmd = 'OPERATEIPTRAFFIC START,1'
                self.anritsu.send_command(cmd)
            except AnritsuError as inst:
                self.log.warning(
                    "{}\n".format(inst))  # Typically RUNNING already
            time.sleep(4)

        up_power_per_chain = []
        # Get the number of chains
        cmd = 'MONITOR? UL_PUSCH'
        uplink_meas_power = self.anritsu.send_query(cmd)
        str_power_chain = uplink_meas_power.split(',')
        num_chains = len(str_power_chain)
        for ichain in range(0, num_chains):
            up_power_per_chain.append([])

        for i in range(0, self.NUM_UL_CAL_READS):
            uplink_meas_power = self.anritsu.send_query(cmd)
            str_power_chain = uplink_meas_power.split(',')

            for ichain in range(0, num_chains):
                if (str_power_chain[ichain] == 'DEACTIVE'):
                    up_power_per_chain[ichain].append(float('nan'))
                else:
                    up_power_per_chain[ichain].append(
                        float(str_power_chain[ichain]))

            time.sleep(3)

        # Stop the IP traffic (UDP)
        if not self.tbs_pattern_on:
            try:
                cmd = 'OPERATEIPTRAFFIC STOP,1'
                self.anritsu.send_command(cmd)
            except AnritsuError as inst:
                self.log.warning(
                    "{}\n".format(inst))  # Typically STOPPED already
            time.sleep(2)

        # Reset phone and bts to original settings
        self.dut.droid.goToSleepNow()
        self.dut.droid.setScreenTimeout(initial_screen_timeout)
        bts.input_level = initial_input_level
        time.sleep(2)

        # Phone only supports 1x1 Uplink so always chain 0
        avg_up_power = np.nanmean(up_power_per_chain[0])
        if np.isnan(avg_up_power):
            raise RuntimeError(
                "Calibration failed because the callbox reported the chain to "
                "be deactive.")

        up_call_path_loss = target_power - avg_up_power

        # Validate the result
        if not 0 < up_call_path_loss < 100:
            raise RuntimeError(
                "Uplink calibration failed. The calculated path loss value "
                "was {} dBm.".format(up_call_path_loss))

        self.log.info(
            "Measured uplink path loss: {} dB".format(up_call_path_loss))

        return up_call_path_loss

    def set_band(self, bts, band, calibrate_if_necessary=True):
        """ Sets the band used for communication.

        When moving to a new band, recalibrate the link.

        Args:
            bts: basestation handle
            band: desired band
            calibrate_if_necessary: if False calibration will be skipped
        """

        bts.band = band
        time.sleep(5)  # It takes some time to propagate the new band

        # Invalidate the calibration values
        self.dl_path_loss = None
        self.ul_path_loss = None

        # Only calibrate when required.
        if self.calibration_required and calibrate_if_necessary:
            # Try loading the path loss values from the calibration table. If
            # they are not available, use the automated calibration procedure.
            try:
                self.dl_path_loss = self.calibration_table[band]["dl"]
                self.ul_path_loss = self.calibration_table[band]["ul"]
            except KeyError:
                self.calibrate()

            # Complete the calibration table with the new values to be used in
            # the next tests.
            if band not in self.calibration_table:
                self.calibration_table[band] = {}

            if "dl" not in self.calibration_table[band] and self.dl_path_loss:
                self.calibration_table[band]["dl"] = self.dl_path_loss

            if "ul" not in self.calibration_table[band] and self.ul_path_loss:
                self.calibration_table[band]["ul"] = self.ul_path_loss

    def maximum_downlink_throughput(self):
        """ Calculates maximum achievable downlink throughput in the current
        simulation state.

        Because thoughput is dependent on the RAT, this method needs to be
        implemented by children classes.

        Returns:
            Maximum throughput in mbps
        """
        raise NotImplementedError()

    def maximum_uplink_throughput(self):
        """ Calculates maximum achievable downlink throughput in the current
        simulation state.

        Because thoughput is dependent on the RAT, this method needs to be
        implemented by children classes.

        Returns:
            Maximum throughput in mbps
        """
        raise NotImplementedError()

    def start_test_case(self):
        """ Starts a test case in the current simulation.

        Requires the phone to be attached.
        """

        pass
