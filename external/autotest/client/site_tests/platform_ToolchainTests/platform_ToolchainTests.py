# Lint as: python2, python3
# Copyright (c) 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from autotest_lib.client.bin import test, utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.crash import crash_test


class platform_ToolchainTests(test.test):
    """
    Verify the code generated by toolchain on DUTs.
    """
    version = 2

    def run_once(self, rootdir="/", args=[]):
        """
        Run `toolchain-tests`, check the exit status, and print the output.
        """

        # Run this, but ignore the crashes it generates
        with crash_test.FilterOut('fortify-runtime-tests'):
            result = utils.run('toolchain-tests', ignore_status=True)

        if result.exit_status != 0:
            logging.error(result.stdout)
            logging.error(result.stderr)
            raise error.TestFail('toolchain-tests failed with return code: %d' %
                                 result.exit_status)
        else:
            logging.debug(result.stdout)
