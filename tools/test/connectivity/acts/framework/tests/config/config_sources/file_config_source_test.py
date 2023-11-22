#!/usr/bin/env python3
#
#   Copyright 2018 - The Android Open Source Project
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
import unittest
from unittest import TestCase

from acts.config.config_sources.file_config_source import FileConfigSource
from mock import patch, Mock


class FileConfigSourceTest(TestCase):
    """Tests the FileConfigSource class."""

    @patch('acts.config.file_loader.ConfigFileLoader.load_config_from_file')
    def test_gather_configs_calls_load_config_from_file(self, mocked_load):
        """Verifies gather_configs() calls load_config_from_file()."""
        file_path = '/file/path'
        mock_config_entry_metas = Mock()
        FileConfigSource(file_path).gather_configs(mock_config_entry_metas)

        mocked_load.assert_called_with(file_path)


if __name__ == '__main__':
    unittest.main()
