#!/usr/bin/env python
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

from metrics.metric import Metric


class UptimeMetric(Metric):

    COMMAND = "cat /proc/uptime"
    # Fields for response dictionary
    TIME_SECONDS = 'time_seconds'

    def gather_metric(self):
        """Tells how long system has been running

        Returns:
            A dict with the following fields:
              time_seconds: float uptime in total seconds

        """
        # Run shell command
        result = self._shell.run(self.COMMAND).stdout
        # Example stdout:
        # 358350.70 14241538.06

        # Get only first number (total time)
        seconds = float(result.split()[0])
        response = {
            self.TIME_SECONDS: seconds,
        }
        return response
