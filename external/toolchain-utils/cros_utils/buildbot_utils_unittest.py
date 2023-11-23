#!/usr/bin/env python2

# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
"""Unittest for buildbot_utils.py."""

from __future__ import print_function

from mock import patch

import time
import unittest

from cros_utils import buildbot_utils
from cros_utils import command_executer


class TrybotTest(unittest.TestCase):
  """Test for CommandExecuter class."""

  old_tryjob_out = (
      'Verifying patches...\n'
      'Submitting tryjob...\n'
      'Successfully sent PUT request to [buildbucket_bucket:master.chromiumos.t'
      'ryserver] with [config:success-build] [buildbucket_id:895272114382368817'
      '6].\n'
      'Tryjob submitted!\n'
      'To view your tryjobs, visit:\n'
      '  http://cros-goldeneye/chromeos/healthmonitoring/buildDetails?buildbuck'
      'etId=8952721143823688176\n'
      '  https://uberchromegw.corp.google.com/i/chromiumos.tryserver/waterfall?'
      'committer=laszio@chromium.org&builder=etc\n')
  tryjob_out = (
      '[{"buildbucket_id": "8952721143823688176", "build_config": '
      '"cave-llvm-toolchain-tryjob", "url": '
      '"http://cros-goldeneye/chromeos/healthmonitoring/buildDetails?buildbucketId=8952721143823688176"}]'
  )

  buildresult_out = (
      '{"8952721143823688176": {"status": "pass", "artifacts_url":'
      '"gs://chromeos-image-archive/trybot-elm-release-tryjob/R67-10468.0.0-'
      'b20789"}}')

  buildbucket_id = '8952721143823688176'
  counter_1 = 10

  def testGetTrybotImage(self):
    with patch.object(buildbot_utils, 'SubmitTryjob') as mock_submit:
      with patch.object(buildbot_utils, 'PeekTrybotImage') as mock_peek:
        with patch.object(time, 'sleep', return_value=None):

          def peek(_chromeos_root, _buildbucket_id):
            self.counter_1 -= 1
            if self.counter_1 >= 0:
              return ('running', '')
            return ('pass',
                    'gs://chromeos-image-archive/trybot-elm-release-tryjob/'
                    'R67-10468.0.0-b20789')

          mock_peek.side_effect = peek
          mock_submit.return_value = self.buildbucket_id

          # sync
          buildbucket_id, image = buildbot_utils.GetTrybotImage(
              '/tmp', 'falco-release-tryjob', [])
          self.assertEqual(buildbucket_id, self.buildbucket_id)
          self.assertEqual('trybot-elm-release-tryjob/'
                           'R67-10468.0.0-b20789', image)

          # async
          buildbucket_id, image = buildbot_utils.GetTrybotImage(
              '/tmp', 'falco-release-tryjob', [], async=True)
          self.assertEqual(buildbucket_id, self.buildbucket_id)
          self.assertEqual(' ', image)

  def testSubmitTryjob(self):
    with patch.object(command_executer.CommandExecuter,
                      'RunCommandWOutput') as mocked_run:
      mocked_run.return_value = (0, self.tryjob_out, '')
      buildbucket_id = buildbot_utils.SubmitTryjob('/', 'falco-release-tryjob',
                                                   [], [])
      self.assertEqual(buildbucket_id, self.buildbucket_id)

  def testPeekTrybotImage(self):
    with patch.object(command_executer.CommandExecuter,
                      'RunCommandWOutput') as mocked_run:
      # pass
      mocked_run.return_value = (0, self.buildresult_out, '')
      status, image = buildbot_utils.PeekTrybotImage('/', self.buildbucket_id)
      self.assertEqual('pass', status)
      self.assertEqual(
          'gs://chromeos-image-archive/trybot-elm-release-tryjob/'
          'R67-10468.0.0-b20789', image)

      # running
      mocked_run.return_value = (1, '', '')
      status, image = buildbot_utils.PeekTrybotImage('/', self.buildbucket_id)
      self.assertEqual('running', status)
      self.assertEqual(None, image)

      # fail
      buildresult_fail = self.buildresult_out.replace('\"pass\"', '\"fail\"')
      mocked_run.return_value = (0, buildresult_fail, '')
      status, image = buildbot_utils.PeekTrybotImage('/', self.buildbucket_id)
      self.assertEqual('fail', status)
      self.assertEqual(
          'gs://chromeos-image-archive/trybot-elm-release-tryjob/'
          'R67-10468.0.0-b20789', image)

  def testParseTryjobBuildbucketId(self):
    buildbucket_id = buildbot_utils.ParseTryjobBuildbucketId(self.tryjob_out)
    self.assertEqual(buildbucket_id, self.buildbucket_id)


if __name__ == '__main__':
  unittest.main()
