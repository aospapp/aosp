# Lint as: python2, python3
# Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import time
from autotest_lib.server import autotest
from autotest_lib.server import hosts
from autotest_lib.server import test

class hardware_StorageQualTrimStress(test.test):
    """Do traffic and trim while suspending aggressively."""

    version = 1
    def run_once(self, client_ip, duration, cq=False):

        # in a cq run, do not execute the test, just output
        # the order that the test would have run in
        if cq:
            self.write_test_keyval({'storage_qual_cq':
                '%f hardware_StorageQualTrimStress' % time.time()})
            return

        client = hosts.create_host(client_ip)
        client_at = autotest.Autotest(client)
        control = """REQUIRE_SSP = True \n\njob.parallel(
            [lambda: job.run_test('power_SuspendStress', tag='disk',
                duration=%d, init_delay=10, min_suspend=7, min_resume=30,
                check_connection=True)],
            [lambda: job.run_test('hardware_TrimIntegrity', test_length=%d,
                disable_sysinfo=True,
                tag='qual_trim')])""" % (duration, duration)
        client_at.run(control, '.', None)
