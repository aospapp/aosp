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

import acts.test_utils.power.PowerBaseTest as PBT
from acts.controllers.anritsu_lib._anritsu_utils import AnritsuError
from acts.controllers.anritsu_lib.md8475a import MD8475A
from acts.test_utils.power.tel_simulations.GsmSimulation import GsmSimulation
from acts.test_utils.power.tel_simulations.LteSimulation import LteSimulation
from acts.test_utils.power.tel_simulations.UmtsSimulation import UmtsSimulation
from acts.test_utils.power.tel_simulations.LteCaSimulation import LteCaSimulation


class PowerCellularLabBaseTest(PBT.PowerBaseTest):
    """ Base class for Cellular power related tests.

    Inherits from PowerBaseTest so it has methods to collect power measurements.
    Provides methods to setup and control the Anritsu simulation.

    """

    # List of test name keywords that indicate the RAT to be used

    PARAM_SIM_TYPE_LTE = "lte"
    PARAM_SIM_TYPE_LTE_CA = "lteca"
    PARAM_SIM_TYPE_UMTS = "umts"
    PARAM_SIM_TYPE_GSM = "gsm"

    # User param keywords
    KEY_CALIBRATION_TABLE = "calibration_table"

    def __init__(self, controllers):
        """ Class initialization.

        Sets class attributes to None.
        """

        super().__init__(controllers)

        self.simulation = None
        self.anritsu = None
        self.calibration_table = {}

        # If callbox version was not specified in the config files,
        # set a default value
        if not hasattr(self, "md8475_version"):
            self.md8475_version = "A"

    def setup_class(self):
        """ Executed before any test case is started.

        Sets the device to rockbottom and connects to the anritsu callbox.

        Returns:
            False if connecting to the callbox fails.
        """

        super().setup_class()

        # Gets the name of the interface from which packets are sent
        if hasattr(self, 'packet_senders'):
            self.pkt_sender = self.packet_senders[0]

        # Load calibration tables
        # Load calibration tables
        if self.KEY_CALIBRATION_TABLE in self.user_params:
            self.calibration_table = self.unpack_custom_file(
                self.user_params[self.KEY_CALIBRATION_TABLE], False)

        # Store the value of the key to access the test config in the
        # user_params dictionary.
        self.PARAMS_KEY = self.TAG + "_params"

        # Set DUT to rockbottom
        self.dut_rockbottom()

        # Establish connection to Anritsu Callbox
        return self.connect_to_anritsu()

    def connect_to_anritsu(self):
        """ Connects to Anritsu Callbox and gets handle object.

        Returns:
            False if a connection with the callbox could not be started
        """

        try:

            self.anritsu = MD8475A(
                self.md8475a_ip_address,
                self.log,
                self.wlan_option,
                md8475_version=self.md8475_version)
            return True
        except AnritsuError:
            self.log.error('Error in connecting to Anritsu Callbox')
            return False

    def setup_test(self):
        """ Executed before every test case.

        Parses parameters from the test name and sets a simulation up according
        to those values. Also takes care of attaching the phone to the base
        station. Because starting new simulations and recalibrating takes some
        time, the same simulation object is kept between tests and is only
        destroyed and re instantiated in case the RAT is different from the
        previous tests.

        Children classes need to call the parent method first. This method will
        create the list self.parameters with the keywords separated by
        underscores in the test name and will remove the ones that were consumed
        for the simulation config. The setup_test methods in the children
        classes can then consume the remaining values.
        """

        # Get list of parameters from the test name
        self.parameters = self.current_test_name.split('_')

        # Remove the 'test' keyword
        self.parameters.remove('test')

        # Decide what type of simulation and instantiate it if needed
        if self.consume_parameter(self.PARAM_SIM_TYPE_LTE):
            self.init_simulation(self.PARAM_SIM_TYPE_LTE)
        elif self.consume_parameter(self.PARAM_SIM_TYPE_LTE_CA):
            self.init_simulation(self.PARAM_SIM_TYPE_LTE_CA)
        elif self.consume_parameter(self.PARAM_SIM_TYPE_UMTS):
            self.init_simulation(self.PARAM_SIM_TYPE_UMTS)
        elif self.consume_parameter(self.PARAM_SIM_TYPE_GSM):
            self.init_simulation(self.PARAM_SIM_TYPE_GSM)
        else:
            self.log.error(
                "Simulation type needs to be indicated in the test name.")
            return False

        # Changing cell parameters requires the phone to be detached
        self.simulation.detach()

        # Parse simulation parameters.
        # This may throw a ValueError exception if incorrect values are passed
        # or if required arguments are omitted.
        try:
            self.simulation.parse_parameters(self.parameters)
        except ValueError as error:
            self.log.error(str(error))
            return False

        # Wait for new params to settle
        time.sleep(5)

        # Attach the phone to the basestation
        if not self.simulation.attach():
            return False

        self.simulation.start_test_case()

        # Make the device go to sleep
        self.dut.droid.goToSleepNow()

        return True

    def consume_parameter(self, parameter_name, num_values=0):
        """ Parses a parameter from the test name.

        Allows the test to get parameters from its name. Deletes parameters from
        the list after consuming them to ensure that they are not used twice.

        Args:
            parameter_name: keyword to look up in the test name
            num_values: number of arguments following the parameter name in the
                test name
        Returns:
            A list containing the parameter name and the following num_values
            arguments.
        """

        try:
            i = self.parameters.index(parameter_name)
        except ValueError:
            # parameter_name is not set
            return []

        return_list = []

        try:
            for j in range(num_values + 1):
                return_list.append(self.parameters.pop(i))
        except IndexError:
            self.log.error(
                "Parameter {} has to be followed by {} values.".format(
                    parameter_name, num_values))
            raise ValueError()

        return return_list

    def teardown_class(self):
        """Clean up the test class after tests finish running.

        Stop the simulation and then disconnect from the Anritsu Callbox.

        """
        super().teardown_class()

        if self.anritsu:
            self.anritsu.stop_simulation()
            self.anritsu.disconnect()

    def init_simulation(self, sim_type):
        """ Starts a new simulation only if needed.

        Only starts a new simulation if type is different from the one running
        before.

        Args:
            type: defines the type of simulation to be started.
        """

        simulation_dictionary = {
            self.PARAM_SIM_TYPE_LTE: LteSimulation,
            self.PARAM_SIM_TYPE_UMTS: UmtsSimulation,
            self.PARAM_SIM_TYPE_GSM: GsmSimulation,
            self.PARAM_SIM_TYPE_LTE_CA: LteCaSimulation
        }

        if not sim_type in simulation_dictionary:
            raise ValueError("The provided simulation type is invalid.")

        simulation_class = simulation_dictionary[sim_type]

        if isinstance(self.simulation, simulation_class):
            # The simulation object we already have is enough.
            return

        if self.simulation:
            # Make sure the simulation is stopped before loading a new one
            self.simulation.stop()

        # If the calibration table doesn't have an entry for this simulation
        # type add an empty one
        if sim_type not in self.calibration_table:
            self.calibration_table[sim_type] = {}

        # Instantiate a new simulation
        self.simulation = simulation_class(self.anritsu, self.log, self.dut,
                                           self.user_params[self.PARAMS_KEY],
                                           self.calibration_table[sim_type])

        # Start the simulation
        self.simulation.start()
