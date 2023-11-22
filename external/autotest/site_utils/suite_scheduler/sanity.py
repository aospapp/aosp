# Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.


import logging
import os

import common


_WHITELISTED_SUITES = (
    'arc-cts',
    'arc-cts-perbuild',
    'arc-cts-dev',
    'arc-cts-beta',
    'arc-cts-stable',
    'arc-cts-beta-fri',
    'arc-cts-beta-mon',
    'arc-cts-beta-sat',
    'arc-cts-beta-sun',
    'arc-cts-beta-thu',
    'arc-cts-beta-tue',
    'arc-cts-beta-wed',
    'arc-cts-dev-fri',
    'arc-cts-dev-mon',
    'arc-cts-dev-sat',
    'arc-cts-dev-sun',
    'arc-cts-dev-thu',
    'arc-cts-dev-tue',
    'arc-cts-dev-wed',
    'arc-cts-qual',
    'arc-gts',
    'arc-gts-perbuild',
    'arc-gts-qual',
    'arc-gts-tot',
    'arc-nightly',
    'arc-weekly',
    'crosbolt_arc_perf',
    'crosbolt_arc_perf_nightly',
    'crosbolt_arc_perf_perbuild',
)

def CheckControlFileExistence(tasks):
    """
    Make sure that for any task that schedules a suite, that
    test_suites/control.<suite> exists. this prevents people from accidentally
    adding a suite to suite_scheduler.ini but not adding an actual suite
    control file, thus resulting in their suite not running and the lab team
    getting lots of email

    @param tasks The list of tasks to check.
    @return 0 if no missing control files are found
            1 if there are at least one missing control files
    """
    corrections = False

    for task in tasks:
        suite_path = os.path.join(common.autotest_dir,
                                  'test_suites', 'control.'+task.suite)
        if task.suite in _WHITELISTED_SUITES:
            continue
        if not os.path.exists(suite_path):
            corrections = True
            logging.warning("No suite control file for %s", task.suite)

    return 1 if corrections else 0
