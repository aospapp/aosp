#!/usr/bin/env python3
# Copyright 2018 - The Android Open Source Project
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

"""AIDEgen metrics functions."""

import logging
import os
import platform
import sys

from aidegen import constant
from atest import atest_utils

try:
    from asuite.metrics import metrics
    from asuite.metrics import metrics_base
    from asuite.metrics import metrics_utils
except ImportError:
    logging.debug('Import metrics fail, can\'t send metrics')
    metrics = None
    metrics_base = None
    metrics_utils = None


def starts_asuite_metrics(references=None):
    """Starts to record metrics data.

    Send a metrics data to log server at the same time.

    Args:
        references: a list of reference data, when importing whole Android
                    it contains 'is_android_tree'.
    """
    if not metrics:
        return
    atest_utils.print_data_collection_notice()
    metrics_base.MetricsBase.tool_name = constant.AIDEGEN_TOOL_NAME
    metrics_utils.get_start_time()
    command = ' '.join(sys.argv)
    metrics.AtestStartEvent(
        command_line=command,
        test_references=references,
        cwd=os.getcwd(),
        os=platform.platform())


def ends_asuite_metrics(exit_code, stacktrace='', logs=''):
    """Send the end event to log server.

    Args:
        exit_code: An integer of exit code.
        stacktrace: A string of stacktrace.
        logs: A string of logs.
    """
    if not metrics_utils:
        return
    metrics_utils.send_exit_event(
        exit_code,
        stacktrace=stacktrace,
        logs=logs)
