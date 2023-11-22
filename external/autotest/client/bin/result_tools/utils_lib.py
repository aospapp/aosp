# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Shared constants and methods for result utilities."""

import collections


# Following are key names for directory summaries. The keys are started with /
# so it can be differentiated with a valid file name. The short keys are
# designed for smaller file size of the directory summary.

# Original size of the directory or file
ORIGINAL_SIZE_BYTES = '/S'
# Size of the directory or file after trimming
TRIMMED_SIZE_BYTES = '/T'
# Size of the directory or file being collected from client side
COLLECTED_SIZE_BYTES = '/C'
# A dictionary of sub-directories' summary: name: {directory_summary}
DIRS = '/D'
# Default root directory name. To allow summaries to be merged effectively, all
# summaries are collected with root directory of ''
ROOT_DIR = ''

# Information of test result sizes to be stored in tko_job_keyvals.
# The total size (in kB) of test results that generated during the test,
# including:
#  * server side test logs and result files.
#  * client side test logs, sysinfo, system logs and crash dumps.
# Note that a test can collect the same test result files from DUT multiple
# times during the test, before and after each iteration/test. So the value of
# client_result_collected_KB could be larger than the value of
# result_uploaded_KB, which is the size of result directory on the server side,
# even if the test result throttling is not applied.
#
# Attributes of the named tuple includes:
# client_result_collected_KB: The total size (in KB) of test results collected
#         from test device.
# original_result_total_KB: The original size (in KB) of test results before
#         being trimmed.
# result_uploaded_KB: The total size (in KB) of test results to be uploaded by
#         gs_offloader.
# result_throttled: Flag to indicate if test results collection is throttled.
ResultSizeInfo = collections.namedtuple(
        'ResultSizeInfo',
        ['client_result_collected_KB',
         'original_result_total_KB',
         'result_uploaded_KB',
         'result_throttled'])


def get_result_size_info(client_collected_bytes, summary):
    """Get the result size information.

    @param client_collected_bytes: Size in bytes of results collected from the
            test device.
    @param summary: A dictionary of directory summary.
    @return: A namedtuple of result size informations, including:
            client_result_collected_KB: The total size (in KB) of test results
                    collected from test device.
            original_result_total_KB: The original size (in KB) of test results
                    before being trimmed.
            result_uploaded_KB: The total size (in KB) of test results to be
                    uploaded.
            result_throttled: True if test results collection is throttled.
    """
    root_entry = summary[ROOT_DIR]
    client_result_collected_KB= client_collected_bytes / 1024
    original_result_total_KB = root_entry[ORIGINAL_SIZE_BYTES] / 1024
    result_uploaded_KB = root_entry[TRIMMED_SIZE_BYTES] / 1024
    # Test results are considered to be throttled if the total size of
    # results collected is different from the total size of trimmed results
    # from the client side.
    result_throttled = (
            root_entry[ORIGINAL_SIZE_BYTES] != root_entry[TRIMMED_SIZE_BYTES])

    return ResultSizeInfo(client_result_collected_KB=client_result_collected_KB,
                          original_result_total_KB=original_result_total_KB,
                          result_uploaded_KB=result_uploaded_KB,
                          result_throttled=result_throttled)