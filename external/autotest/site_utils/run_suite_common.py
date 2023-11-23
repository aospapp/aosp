# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Shared libs by run_suite.py & run_suite_skylab.py."""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import collections
import json
import sys

from autotest_lib.client.common_lib import enum


# Return code that will be sent back to callers.
RETURN_CODES = enum.Enum(
        'OK',
        'ERROR',
        'WARNING',
        'INFRA_FAILURE',
        'SUITE_TIMEOUT',
        'BOARD_NOT_AVAILABLE',
        'INVALID_OPTIONS',
)


class SuiteResult(collections.namedtuple('SuiteResult',
                                         ['return_code', 'output_dict'])):
    """Result of running a suite to return."""

    def __new__(cls, return_code, output_dict=None):
        if output_dict is None:
            output_dict = dict()
        else:
            output_dict = output_dict.copy()
        output_dict['return_code'] = return_code
        return super(SuiteResult, cls).__new__(cls, return_code, output_dict)

    @property
    def string_code(self):
        """Return the enum string name of the numerical return_code."""
        return RETURN_CODES.get_string(self.return_code)


def dump_json(obj):
    """Write obj JSON to stdout."""
    output_json = json.dumps(obj, sort_keys=True)
    sys.stdout.write('#JSON_START#%s#JSON_END#' % output_json.strip())
