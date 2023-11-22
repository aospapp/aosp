#!/usr/bin/env python
#
# Copyright 2017, The Android Open Source Project
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

"""Unittests for atest."""

import mock
import os
import unittest

import atest


class AtestUnittests(unittest.TestCase):

    @mock.patch('os.environ.get', return_value=None)
    def test_uninitalized_check_environment(self, mock_os_env_get):
        self.assertFalse(atest._has_environment_variables())


    @mock.patch('os.environ.get', return_value='out/testcases/')
    def test_initalized_check_environment(self, mock_os_env_get):
        self.assertTrue(atest._has_environment_variables())


if __name__ == '__main__':
    unittest.main()
