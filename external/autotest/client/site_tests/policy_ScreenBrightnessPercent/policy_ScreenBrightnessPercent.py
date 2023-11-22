# Copyright 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.enterprise import enterprise_policy_base
from autotest_lib.client.cros.power import power_utils


class policy_ScreenBrightnessPercent(
        enterprise_policy_base.EnterprisePolicyTest):
    """
    Test effect of ScreenBrightnessPercent policy on Chrome OS.

    This test will set the policy, then check the current screen brightness.
    The brightness reported by the hardware is not the same as the UI bright-
    ness, so a conversion must be done.
    Additionally this policy is only checking the setting when the DUT is on AC
    power.

    """
    version = 1

    POLICY_NAME = 'ScreenBrightnessPercent'

    def _convert_power_percent(self, percent):
        """
        Will convert the UI brightness setting to the hw brightness level.
        See:
        https://chromium.googlesource.com/chromiumos/platform2/+/master/power_manager/docs/screen_brightness.md

        @param percent: int or float, UI brightness settings.

        return: float, hardware brightness percentage (on 0-100 scale).

        """
        max_level = self._backlight.get_max_level()

        # Get the minimum value brightness value to compute the HW brightness
        bl_contr = power_utils.BacklightController()
        bl_contr.set_brightness_to_min()

        # Give the hardware a tiny bit of time to settle
        time.sleep(1)
        min_level = self._backlight.get_level()

        # Use the formula defined in the link above
        fract = (percent - 6.25) / (100 - 6.25)
        level = min_level + ((fract ** 2) * (max_level - min_level))
        finalv = (level / max_level) * 100
        return finalv

    def _test_backlight(self, backlight_level):
        """
        Get the actual backlight percentage and compare it to the set policy.

        Note: There is a slight difference between the computed on the reported
        brightness. This is likely due floating point math differences between
        the implemented formula on the DUT, and the one used here. Because
        of this, a half percent tolerace is added.

        @param backlight_level: int or float, UI brightness settings.

        """
        self._backlight = power_utils.Backlight()
        actual_percent = self._backlight.get_percent()
        set_percent = self._convert_power_percent(backlight_level)

        if abs(actual_percent - set_percent) > 0.5:
            raise error.TestError(
                "Screen brightness incorrect ({}) when it should be {}"
                .format(set_percent, actual_percent))

    def run_once(self, case):
        """
        Setup and run the test configured for the specified test case.

        @param case: Name of the test case to run.

        """
        self.setup_case(user_policies={
            self.POLICY_NAME: {"BrightnessAC": case}})
        self._test_backlight(case)
