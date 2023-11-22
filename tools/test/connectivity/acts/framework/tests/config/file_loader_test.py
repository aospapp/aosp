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

from mock import patch

from acts.config.file_loader import ConfigFileParser
from acts.config.file_loader import JsonFileParser
from acts.config.file_loader import ConfigFileLoader


class ConfigFileParserTest(TestCase):
    """Tests the ConfigFileParser class."""

    def test_parse_raises_not_implemented_error(self):
        """Tests that calling parse() raises NotImplementedError"""
        with self.assertRaises(NotImplementedError):
            ConfigFileParser().parse('')


class JsonFileParserTest(TestCase):
    """Tests the JsonFileParser class."""

    @patch('acts.utils.load_config')
    def test_parse_calls_util_load_config(self, mocked_load_config):
        """Tests that parse() calls util.load_config()."""
        file_path = '/file/path'
        JsonFileParser().parse(file_path)

        mocked_load_config.assert_called_with(file_path, log_errors=False)


class ConfigFileParserPass(ConfigFileParser):
    def parse(self, file_path):
        return {'config': 'okay'}


class ConfigFileParserFail(ConfigFileParser):
    def parse(self, file_path):
        raise ValueError('Not Okay')


class ConfigFileLoaderTest(TestCase):
    """Tests the ConfigFileLoader class."""

    def test_file_parsers_are_all_subclasses_of_config_file_parser(self):
        """Tests file_parsers is a list of subclasses of ConfigFileParser."""
        for index, file_parser in enumerate(ConfigFileLoader._file_parsers):
            self.assertTrue(
                issubclass(type(file_parser), ConfigFileParser),
                'Entry %s of file_parsers is of type %s, and is not a '
                'ConfigFileParser' % (index, type(file_parser)))

    def test_load_config_from_file_successfully_parses_file(self):
        """Tests that load_config_from_file can successfully parse a file."""
        ConfigFileLoader._file_parsers = [ConfigFileParserPass()]
        try:
            ConfigFileLoader().load_config_from_file('/file/path')
        except Exception as e:
            self.fail('Raised an error upon successful file parsing: %s' % e)

    def test_load_config_from_file_raises_error_on_all_failed_parse(self):
        """Tests that load_config_from_file raises an error if parsers fail."""
        ConfigFileLoader._file_parsers = [ConfigFileParserFail()]
        with self.assertRaises(ValueError):
            ConfigFileLoader().load_config_from_file('/file/path')

    def test_load_config_from_file_passes_after_the_first_success(self):
        """Tests that a failure can happen, but a success will go through."""
        ConfigFileLoader._file_parsers = [ConfigFileParserFail(),
                                          ConfigFileParserPass()]
        try:
            ConfigFileLoader().load_config_from_file('/file/path')
        except Exception as e:
            self.fail('Raised an error upon successful file parsing: %s' % e)


if __name__ == '__main__':
    unittest.main()
