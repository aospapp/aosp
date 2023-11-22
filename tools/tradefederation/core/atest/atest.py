#!/usr/bin/env python
#
# Copyright 2017, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Command line utility for running Android tests through TradeFederation.

atest helps automate the flow of building test modules across the Android
code base and executing the tests via the TradeFederation test harness.

atest is designed to support any test types that can be ran by TradeFederation.
"""

import os
import sys


TARGET_TESTCASES_ENV_VARIBLE = "ANDROID_TARGET_OUT_TESTCASES"


def _has_environment_variables():
    """Verify the local environment has been setup to run atest.

    @returns True if the environment has the correct variables initialized,
             False otherwise.
    """
    return bool(os.environ.get(TARGET_TESTCASES_ENV_VARIBLE))


def main(argv):
    """Entry point atest script.

    @param argv: arguments list
    """
    if not _has_environment_variables():
        print >> sys.stderr, ("Local environment doesn't appear to have been "
                              "initialized. Did you remember to run lunch?")

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
