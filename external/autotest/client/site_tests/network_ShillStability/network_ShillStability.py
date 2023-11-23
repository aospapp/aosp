# Copyright (c) 2018 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import datetime
import logging
import time

from autotest_lib.client.bin import test
from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error


class network_ShillStability(test.test):
    """
    Ensure shill is stable (its PID is constant).
    """
    version = 1

    SHILL_PID_CHECK_INTERVAL_SECONDS = 2
    SHILL_PID_CHECK_DURATION_SECONDS = 20

    def _get_shill_pid(self):
        pids = utils.get_process_list('shill')
        if not pids:
            raise error.TestFail('Shill is not running on DUT.')
        if len(pids) > 1:
            raise error.TestFail('Found more than one shill: %r' % pids)
        return int(pids[0])

    def _assert_shill_pid_constant(self, old_pid):
        pid = self._get_shill_pid()
        logging.info('Shill: old PID: %d, current PID: %d', old_pid, pid)
        if pid != old_pid:
            raise error.TestFail('Shill PID changed: %d -> %d' % (old_pid, pid))

    def run_once(self):
        """
        Check that shill's PID is constant over 90 seconds.
        Shill is set to respawn every 10 seconds if it crashes, so this check
        will fail if shill is crashy.
        """
        old_pid = self._get_shill_pid()
        logging.info('Shill PID: %d', old_pid)

        start_time = datetime.datetime.now()
        end_time = (start_time +
            datetime.timedelta(seconds = self.SHILL_PID_CHECK_DURATION_SECONDS))
        while (datetime.datetime.now() < end_time):
            self._assert_shill_pid_constant(old_pid)
            logging.info('shill PID constant at: %d', old_pid)
            time.sleep(self.SHILL_PID_CHECK_INTERVAL_SECONDS)
