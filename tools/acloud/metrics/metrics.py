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
r"""Acloud metrics functions."""

import logging
import os
import subprocess

from acloud.internal import constants
# pylint: disable=import-error

_METRICS_URL = 'http://asuite-218222.appspot.com/acloud/metrics'
_VALID_DOMAINS = ["google.com", "android.com"]
_COMMAND_GIT_CONFIG = ["git", "config", "--get", "user.email"]

logger = logging.getLogger(__name__)

# pylint: disable=broad-except
def LogUsage(argv):
    """Log acloud start event.

    Log acloud start event and the usage, following are the data we log:
    - tool_name: All asuite tools are storing event in the same database. This
      property is provided to distinguish different tools.
    - command_line: Log all command arguments.
    - test_references: Should be a list, we record the acloud sub-command.
      e.g. create/delete/reconnect/..etc. We could use this property as filter
      criteria to speed up query time.
    - cwd: User's current working directory.
    - os: The platform that users are working at.

    Args:
        argv: A list of system arguments.
    """
    # TODO(b131867764): We could remove this metics tool after we apply clearcut.
    try:
        from asuite import asuite_metrics
        asuite_metrics.log_event(_METRICS_URL, dummy_key_fallback=False,
                                 ldap=_GetLdap())
    except ImportError:
        logger.debug("No metrics recorder available, not sending metrics.")

    #Log start event via clearcut tool.
    try:
        from asuite import atest_utils
        from asuite.metrics import metrics_utils
        atest_utils.print_data_collection_notice()
        metrics_utils.send_start_event(tool_name=constants.TOOL_NAME,
                                       command_line=' '.join(argv),
                                       test_references=[argv[0]])
    except Exception as e:
        logger.debug("Failed to send start event:%s", str(e))


#TODO(b131867764): We could remove this metics tool after we apply clearcut.
def _GetLdap():
    """Return string email username for valid domains only, None otherwise."""
    try:
        acloud_project = os.path.join(
            os.environ[constants.ENV_ANDROID_BUILD_TOP], "tools", "acloud")
        email = subprocess.check_output(_COMMAND_GIT_CONFIG,
                                        cwd=acloud_project).strip()
        ldap, domain = email.split("@", 2)
        if domain in _VALID_DOMAINS:
            return ldap
    # pylint: disable=broad-except
    except Exception as e:
        logger.debug("error retrieving email: %s", e)
    return None

# pylint: disable=broad-except
def LogExitEvent(exit_code, stacktrace="", logs=""):
    """Log acloud exit event.

    A start event should followed by an exit event to calculate the consuming
    time. This function will be run at the end of acloud main process or
    at the init of the error object.

    Args:
        exit_code: Integer, the exit code of acloud main process.
        stacktrace: A string of stacktrace.
        logs: A string of logs.
    """
    try:
        from asuite.metrics import metrics_utils
        metrics_utils.send_exit_event(exit_code, stacktrace=stacktrace,
                                      logs=logs)
    except Exception as e:
        logger.debug("Failed to send exit event:%s", str(e))
