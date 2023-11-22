# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Utility to deploy and run result utils on a DUT.
"""

import logging
import os

import common
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib import utils as client_utils

try:
    from chromite.lib import metrics
except ImportError:
    metrics = client_utils.metrics_mock

THROTTLE_OPTION_FMT = '-m %s'
BUILD_DIR_SUMMARY_CMD = '%s/result_tools/utils.py -p %s %s'
BUILD_DIR_SUMMARY_TIMEOUT = 120

def run_on_client(host, client_results_dir, enable_result_throttling=False):
    """Run result utils on the given host.

    @param host: Host to run the result utils.
    @param client_results_dir: Path to the results directory on the client.
    @param enable_result_throttling: True to enable result throttling.
    """
    with metrics.SecondsTimer(
            'chromeos/autotest/job/dir_summary_collection_duration',
            fields={'dut_host_name': host.hostname}):
        try:
            logging.debug('Deploy result utilities to %s', host.hostname)
            host.send_file(os.path.dirname(__file__), host.autodir)
            logging.debug('Getting directory summary for %s.',
                          client_results_dir)
            throttle_option = ''
            if enable_result_throttling:
                throttle_option = (THROTTLE_OPTION_FMT %
                                   host.job.max_result_size_KB)
            cmd = (BUILD_DIR_SUMMARY_CMD %
                   (host.autodir, client_results_dir + '/', throttle_option))
            host.run(cmd, ignore_status=False,
                     timeout=BUILD_DIR_SUMMARY_TIMEOUT)
        except error.AutoservRunError:
            logging.exception(
                    'Failed to create directory summary for %s.',
                    client_results_dir)
