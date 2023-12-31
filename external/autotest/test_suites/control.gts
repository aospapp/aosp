# Copyright 2016 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

AUTHOR = "ARC++ Team"
NAME = "gts"
PURPOSE = "GTS tests for ARC++"
CRITERIA = "All tests with SUITE=gts must pass."

TIME = "SHORT"
TEST_CATEGORY = "General"
TEST_CLASS = "suite"
TEST_TYPE = "Server"

DOC = """
This suite wraps the current GTS bundle for autotest.
"""

import common
from autotest_lib.server.cros.dynamic_suite import dynamic_suite


def predicate(test):
  if not hasattr(test, 'suite') or not hasattr(test, 'name'):
    return False
  if not test.suite == NAME:
    return False
  # Strip off the cheets_GTS. from the test name before comparing to args
  name = test.name[test.name.find('.') + 1:]
  # Typical examples:
  #   name = 'arm.CtsDeqpTestCases.64'
  #     or
  #   name = 'x86.CtsDeqpTestCases.32'
  # and
  #   args_dict['tests'] = ['arm.CtsDeqpTestCases', 'x86.CtsDeqpTestCases']
  # So, we want to includes test with names being subpart of any arg.
  if 'tests' in args_dict and all(arg not in name for arg in args_dict['tests']):
    return False
  return True

args_dict['name'] = NAME
args_dict['job'] = job
args_dict['add_experimental'] = True
args_dict['predicate'] = predicate
dynamic_suite.reimage_and_run(**args_dict)

