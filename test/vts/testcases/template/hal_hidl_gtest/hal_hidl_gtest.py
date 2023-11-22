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

import copy
import logging

from vts.runners.host import const
from vts.runners.host import keys
from vts.runners.host import test_runner
from vts.testcases.template.gtest_binary_test import gtest_binary_test
from vts.testcases.template.gtest_binary_test import gtest_test_case
from vts.utils.python.cpu import cpu_frequency_scaling


class HidlHalGTest(gtest_binary_test.GtestBinaryTest):
    '''Base class to run a VTS target-side HIDL HAL test.

    Attributes:
        DEVICE_TEST_DIR: string, temp location for storing binary
        TAG_PATH_SEPARATOR: string, separator used to separate tag and path
        tags: all the tags that appeared in binary list
        testcases: list of GtestTestCase objects, list of test cases to run
        _cpu_freq: CpuFrequencyScalingController instance of a target device.
        _dut: AndroidDevice, the device under test as config
    '''

    def setUpClass(self):
        """Checks precondition."""
        super(HidlHalGTest, self).setUpClass()

        opt_params = [keys.ConfigKeys.IKEY_SKIP_IF_THERMAL_THROTTLING]
        self.getUserParams(opt_param_names=opt_params)

        self._skip_if_thermal_throttling = self.getUserParam(
            keys.ConfigKeys.IKEY_SKIP_IF_THERMAL_THROTTLING,
            default_value=False)

        if not self._skip_all_testcases:
            logging.info("Disable CPU frequency scaling")
            self._cpu_freq = cpu_frequency_scaling.CpuFrequencyScalingController(
                self._dut)
            self._cpu_freq.DisableCpuScaling()
        else:
            self._cpu_freq = None

    def CreateTestCases(self):
        """Create testcases and conditionally enable passthrough mode.

        Create testcases as defined in HidlHalGtest. If the passthrough option
        is provided in the configuration or if coverage is enabled, enable
        passthrough mode on the test environment.
        """
        super(HidlHalGTest, self).CreateTestCases()

        passthrough_opt = self.getUserParam(
            keys.ConfigKeys.IKEY_PASSTHROUGH_MODE, default_value=False)

        # Enable coverage if specified in the configuration or coverage enabled.
        # TODO(ryanjcampbell@) support binderized mode
        if passthrough_opt or self.coverage.enabled:
            self._EnablePassthroughMode()

    # @Override
    def CreateTestCase(self, path, tag=''):
        '''Create a list of GtestTestCase objects from a binary path.

        Support testing against different service names by first executing a
        dummpy test case which lists all the registered hal services. Then
        query the service name(s) for each registered service with lshal.
        For each service name, create a new test case each with the service
        name as an additional argument.

        Args:
            path: string, absolute path of a gtest binary on device
            tag: string, a tag that will be appended to the end of test name

        Returns:
            A list of GtestTestCase objects.
        '''
        initial_test_cases = super(HidlHalGTest, self).CreateTestCase(path,
                                                                      tag)
        if not initial_test_cases:
            return initial_test_cases
        # first, run one test with --list_registered_services.
        list_service_test_case = copy.copy(initial_test_cases[0])
        list_service_test_case.args += " --list_registered_services"
        results = self.shell.Execute(list_service_test_case.GetRunCommand())
        if (results[const.EXIT_CODE][0]):
            logging.error('Failed to list test cases from binary %s',
                          list_service_test_case.path)
        # parse the results to get the registered service list.
        registered_services = []
        for line in results[const.STDOUT][0].split('\n'):
            line = str(line)
            if line.startswith('hal_service: '):
                service = line[len('hal_service: '):]
                registered_services.append(service)

        # If no service registered, return the initial test cases directly.
        if not registered_services:
            return initial_test_cases

        # find the correponding service name(s) for each registered service and
        # store the mapping in dict service_instances.
        service_instances = {}
        for service in registered_services:
            cmd = '"lshal -i | grep -o %s/.* | sort -u"' % service
            out = str(self._dut.adb.shell(cmd)).split()
            service_names = map(lambda x: x[x.find('/') + 1:], out)
            logging.info("registered service: %s with name: %s" %
                         (service, ' '.join(service_names)))
            service_instances[service] = service_names

        # get all the combination of service instances.
        service_instance_combinations = self._GetServiceInstancesCombinations(
            registered_services, service_instances)

        new_test_cases = []
        for test_case in initial_test_cases:
            for instance_combination in service_instance_combinations:
                new_test_case = copy.copy(test_case)
                for instance in instance_combination:
                    new_test_case.args += " --hal_service_instance=" + instance
                    new_test_case.tag = instance[instance.find(
                        '/'):] + new_test_case.tag
                new_test_cases.append(new_test_case)
        return new_test_cases

    @classmethod
    def _GetServiceInstancesCombinations(self, services, service_instances):
        '''Create all combinations of instances for all services.

        Args:
            services: list, all services used in the test. e.g. [s1, s2]
            service_instances: dictionary, mapping of each service and the
                               corresponding service name(s).
                               e.g. {"s1": ["n1"], "s2": ["n2", "n3"]}

        Returns:
            A list of all service instance combinations.
            e.g. [[s1/n1, s2/n2], [s1/n1, s2/n3]]
        '''

        service_instance_combinations = []
        if not services:
            return service_instance_combinations
        service = services.pop()
        pre_instance_combs = self._GetServiceInstancesCombinations(
            services, service_instances)
        if service not in service_instances:
            return pre_instance_combs
        for name in service_instances[service]:
            if not pre_instance_combs:
                new_instance_comb = [service + '/' + name]
                service_instance_combinations.append(new_instance_comb)
            else:
                for instance_comb in pre_instance_combs:
                    new_instance_comb = [service + '/' + name]
                    new_instance_comb.extend(instance_comb)
                    service_instance_combinations.append(new_instance_comb)

        return service_instance_combinations

    def _EnablePassthroughMode(self):
        """Enable passthrough mode by setting getStub to true.

        This funciton should be called after super class' setupClass method.
        If called before setupClass, user_params will be changed in order to
        trigger setupClass method to invoke this method again.
        """
        if self.testcases:
            for test_case in self.testcases:
                envp = ' %s=true' % const.VTS_HAL_HIDL_GET_STUB
                test_case.envp += envp
        else:
            logging.warn('No test cases are defined yet. Maybe setupClass '
                         'has not been called. Changing user_params to '
                         'enable passthrough mode option.')
            self.user_params[keys.ConfigKeys.IKEY_PASSTHROUGH_MODE] = True

    def setUp(self):
        """Skips the test case if thermal throttling lasts for 30 seconds."""
        super(HidlHalGTest, self).setUp()

        if (self._skip_if_thermal_throttling and
                getattr(self, "_cpu_freq", None)):
            self._cpu_freq.SkipIfThermalThrottling(retry_delay_secs=30)

    def tearDown(self):
        """Skips the test case if there is thermal throttling."""
        if (self._skip_if_thermal_throttling and
                getattr(self, "_cpu_freq", None)):
            self._cpu_freq.SkipIfThermalThrottling()

        super(HidlHalGTest, self).tearDown()

    def tearDownClass(self):
        """Turns off CPU frequency scaling."""
        if (not self._skip_all_testcases and getattr(self, "_cpu_freq", None)):
            logging.info("Enable CPU frequency scaling")
            self._cpu_freq.EnableCpuScaling()

        super(HidlHalGTest, self).tearDownClass()


if __name__ == "__main__":
    test_runner.main()
