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

from acts.libs.metrics.metric import JsonMetric
from acts.libs.test_binding.all_tests_decorator import for_all_tests


class MockJsonMetric(JsonMetric):
    def __init__(self, mock_json, mock_metric_dir):
        super().__init__()
        self.json = mock_json
        self.metric_dir = mock_metric_dir

    def setup(self, test, testcase, binding_name):
        pass

    def as_json(self):
        return self.json


class JsonMetricTest(unittest.TestCase):
    def test_write_json_on_finish(self):
        mock_open = mock.mock_open()

        mock_json = {
            'v': 10
        }
        mock_path = '/metric/path'
        mock_metric = MockJsonMetric(mock_json, mock_path)

        with patch('builtins.open', mock_open):
            mock_metric.finish()

        mock_open.assert_called_with(os.path.join(mock_path, 'raw.json'),
                                     mode='w')
        mock_open().write.assert_called_with(json.dumps(mock_json, indent=2))


if __name__ == "__main__":
    unittest.main()
