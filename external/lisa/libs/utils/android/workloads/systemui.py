# SPDX-License-Identifier: Apache-2.0
#
# Copyright (C) 2017, ARM Limited, Google and contributors.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import re
import os
import logging

from subprocess import Popen, PIPE
from time import sleep

from android import Screen, System, Workload

class SystemUi(Workload):
    """
    Android SystemUi jank test workload
    """

    package = 'com.android.systemui'

    # Instrumentation required to run tests
    test_package = 'android.platform.systemui.tests.jank'

    testOpenAllAppsContainer = "LauncherJankTests#testOpenAllAppsContainer"
    testAllAppsContainerSwipe = "LauncherJankTests#testAllAppsContainerSwipe"
    testHomeScreenSwipe = "LauncherJankTests#testHomeScreenSwipe"
    testWidgetsContainerFling = "LauncherJankTests#testWidgetsContainerFling"
    testSettingsFling = "SettingsJankTests#testSettingsFling"
    testRecentAppsFling = "SystemUiJankTests#testRecentAppsFling"
    testRecentAppsDismiss = "SystemUiJankTests#testRecentAppsDismiss"
    testNotificationListPull = "SystemUiJankTests#testNotificationListPull"
    testNotificationListPull_manyNotifications = "SystemUiJankTests#testNotificationListPull_manyNotifications"
    testQuickSettingsPull = "SystemUiJankTests#testQuickSettingsPull"
    testUnlock = "SystemUiJankTests#testUnlock"
    testExpandGroup = "SystemUiJankTests#testExpandGroup"
    testClearAll = "SystemUiJankTests#testClearAll"
    testChangeBrightness = "SystemUiJankTests#testChangeBrightness"
    testNotificationAppear = "SystemUiJankTests#testNotificationAppear"
    testCameraFromLockscreen = "SystemUiJankTests#testCameraFromLockscreen"
    testAmbientWakeUp = "SystemUiJankTests#testAmbientWakeUp"
    testGoToFullShade = "SystemUiJankTests#testGoToFullShade"
    testInlineReply = "SystemUiJankTests#testInlineReply"
    testPinAppearance = "SystemUiJankTests#testPinAppearance"
    testLaunchSettings = "SystemUiJankTests#testLaunchSettings"

    def __init__(self, test_env):
        super(SystemUi, self).__init__(test_env)
        self._log = logging.getLogger('SystemUi')
        self._log.debug('Workload created')

        # Set of output data reported by SystemUi
        self.db_file = None

    def run(self, out_dir, test_name, iterations, collect='gfxinfo'):
        """
        Run single SystemUi jank test workload.

        :param out_dir: Path to experiment directory where to store results.
        :type out_dir: str

        :param test_name: Name of the test to run
        :type test_name: str

        :param iterations: Run benchmark for this required number of iterations
        :type iterations: int

        :param collect: Specifies what to collect. Possible values:
            - 'systrace'
            - 'ftrace'
            - 'gfxinfo'
            - 'surfaceflinger'
            - any combination of the above
        :type collect: list(str)
        """
        if 'energy' in collect:
            raise ValueError('SystemUi workload does not support energy data collection')

        activity = '.' + test_name

        # Keep track of mandatory parameters
        self.out_dir = out_dir
        self.collect = collect

        # Unlock device screen (assume no password required)
        Screen.unlock(self._target)

        # Close and clear application
        System.force_stop(self._target, self.package, clear=True)

        # Set airplane mode
        System.set_airplane_mode(self._target, on=True)

        # Set min brightness
        Screen.set_brightness(self._target, auto=False, percent=0)

        # Force screen in PORTRAIT mode
        Screen.set_orientation(self._target, portrait=True)

        # Reset frame statistics
        System.gfxinfo_reset(self._target, self.package)
        sleep(1)

        # Clear logcat
        os.system(self._adb('logcat -c'));

        # Regexps for benchmark synchronization
        start_logline = r'TestRunner: started'
        SYSTEMUI_BENCHMARK_START_RE = re.compile(start_logline)
        self._log.debug("START string [%s]", start_logline)

        finish_logline = r'TestRunner: finished'
        SYSTEMUI_BENCHMARK_FINISH_RE = re.compile(finish_logline)
        self._log.debug("FINISH string [%s]", finish_logline)

        # Parse logcat output lines
        logcat_cmd = self._adb(
                'logcat TestRunner:* System.out:I *:S BENCH:*'\
                .format(self._target.adb_name))
        self._log.info("%s", logcat_cmd)

        command = "nohup am instrument -e iterations {} -e class {}{} -w {}".format(
            iterations, self.test_package, activity, self.test_package)
        self._target.background(command)

        logcat = Popen(logcat_cmd, shell=True, stdout=PIPE)
        while True:
            # read next logcat line (up to max 1024 chars)
            message = logcat.stdout.readline(1024)

            # Benchmark start trigger
            match = SYSTEMUI_BENCHMARK_START_RE.search(message)
            if match:
                self.tracingStart()
                self._log.debug("Benchmark started!")

            match = SYSTEMUI_BENCHMARK_FINISH_RE.search(message)
            if match:
                self.tracingStop()
                self._log.debug("Benchmark finished!")
                break

        sleep(5)

        # Go back to home screen
        System.home(self._target)

        # Switch back to original settings
        Screen.set_orientation(self._target, auto=True)
        System.set_airplane_mode(self._target, on=False)
        Screen.set_brightness(self._target, auto=True)
