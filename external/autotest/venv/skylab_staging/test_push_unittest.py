# Copyright 2018 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

from skylab_staging import test_push


EXAMPLE_TEST_RESULTS = [
    ('dummy_PassServer', 'GOOD'),
    ('dummy_Pass.bluetooth', 'GOOD'),
    ('dummy_Fail.Fail', 'FAIL'),
    ('dummy_Pass.actionable', 'GOOD'),
    ('dummy_Pass', 'GOOD'),
    ('dummy_Fail.Error', 'ERROR'),
    ('dummy_Fail.Fail', 'FAIL'),
    ('dummy_Fail.Warn', 'WARN'),
    ('dummy_Fail.NAError', 'TEST_NA'),
    ('dummy_Fail.Crash', 'GOOD'),
    ('login_LoginSuccess', 'GOOD'),
    ('dummy_Fail.Error', 'ERROR'),
    ('provision_AutoUpdate.double', 'GOOD'),
]


def _construct_test_views_list():
  return [{'test_name':r[0], 'status':r[1]} for r in EXAMPLE_TEST_RESULTS]


def test_successful_test_push():
  """Test a successfully passed test push."""
  available_views = _construct_test_views_list()
  summary = test_push._verify_and_summarize(available_views,
                                            test_push._EXPECTED_TEST_RESULTS)
  assert len(summary) == 0


def test_failing_test():
  """Test a test push with a failed test."""
  available_views = _construct_test_views_list()
  for v in available_views:
    if v['test_name'] == 'dummy_Pass':
      v['status'] = 'Fail'

  summary = test_push._verify_and_summarize(available_views,
                                            test_push._EXPECTED_TEST_RESULTS)
  assert len(summary) != 0
  assert 'dummy_Pass' in summary[1]


def test_missing_test():
  """Test a test push with a missing test."""
  available_views = _construct_test_views_list()
  for v in available_views:
    if v['test_name'] == 'login_LoginSuccess':
      available_views.remove(v)

  summary = test_push._verify_and_summarize(available_views,
                                            test_push._EXPECTED_TEST_RESULTS)
  assert len(summary) != 0
  assert 'login_LoginSuccess' in summary[1]
