#!/usr/bin/env python3
#
#   Copyright 2017 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
import json
import os
import unittest

import mock
from mock import patch, Mock, MagicMock

from acts.libs.metrics.metric import RecordableMetric


class MockRecordableMetric(RecordableMetric):
    def __init__(self, mock_metric_dir):
        super().__init__()
        self.metric_dir = mock_metric_dir

    def setup(self, test, testcase, binding_name):
        pass


class RecordableMetricTest(unittest.TestCase):
    def get_mock_channel_stream(self, path, *args, **kwargs):
        if path not in self._mock_channel_stream_lookup:
            self._mock_channel_stream_lookup[path] = mock.MagicMock()

        return self._mock_channel_stream_lookup[path]

    def setUp(self):
        self._mock_channel_stream_lookup = {}

    @patch("os.path.exists")
    @patch("os.makedirs")
    @patch("builtins.open")
    def test_create_channel_stream(self, mock_open, mock_make_dirs,
                                   mock_exists):
        mock_path = '/metric/path'
        mock_metric = MockRecordableMetric(mock_path)

        mock_exists.return_value = False
        mock_open.side_effect = self.get_mock_channel_stream

        mock_metric.record(10, "test", "new")
        mock_open.assert_called_with(os.path.join(mock_path, "new.smetric"),
                                     mode='w')

        mock_metric.record(20, "test", "other")
        mock_open.assert_called_with(os.path.join(mock_path, "other.smetric"),
                                     mode='w')

        mock_metric.finish()

    @patch("os.path.exists")
    @patch("os.makedirs")
    @patch("builtins.open")
    def test_create_channel_error_on_existing_stream(self, mock_open,
                                                     mock_make_dirs,
                                                     mock_exists):
        mock_path = '/metric/path'
        mock_metric = MockRecordableMetric(mock_path)

        mock_exists.return_value = True
        mock_open.side_effect = self.get_mock_channel_stream

        with self.assertRaises(ValueError):
            mock_metric.record(10, "test", "new")

    @patch("os.path.exists")
    @patch("os.makedirs")
    @patch("builtins.open")
    def test_record_to_channel_stream(self, mock_open,
                                      mock_make_dirs,
                                      mock_exists):
        mock_path = '/metric/path'
        new_metric_path = os.path.join(mock_path, 'new.smetric')
        mock_metric = MockRecordableMetric(mock_path)

        mock_exists.return_value = False
        mock_open.side_effect = self.get_mock_channel_stream

        key = "test"
        value = 10
        mock_metric.record(value, key, "new")

        expected_json = json.dumps({
            "key": key,
            "value": value,
        })
        mock_stream = self.get_mock_channel_stream(new_metric_path)
        self.assertTrue(
            mock_stream.write.call_args_list[0] == mock.call(expected_json))
        self.assertTrue(
            mock_stream.write.call_args_list[1] == mock.call("\n"))

    @patch("os.path.exists")
    @patch("os.makedirs")
    @patch("builtins.open")
    def test_close_blobs_on_finished(self, mock_open,
                                      mock_make_dirs,
                                      mock_exists):
        mock_path = '/metric/path'
        new_metric_path = os.path.join(mock_path, 'new.smetric')
        mock_metric = MockRecordableMetric(mock_path)

        mock_exists.return_value = False
        mock_open.side_effect = self.get_mock_channel_stream

        mock_metric.record(10, "test", "new")
        mock_metric.finish()

        mock_stream = self.get_mock_channel_stream(new_metric_path)
        self.assertTrue(mock_stream.close.called)


if __name__ == "__main__":
    unittest.main()
