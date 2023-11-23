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

import sys
from mock import Mock
from mock import patch

from acts import utils
from acts.config.config_entry_meta import ConfigEntryMeta
from acts.config.config_sources.cli_config_source import CliConfigSource


class CliConfigSourceTest(unittest.TestCase):
    """Tests the CliConfigSource class."""

    def test_get_cli_kwargs_returns_all_cli_kwarg_attributes(self):
        """Tests that _get_cli_kwargs returns all cli kwarg attributes."""
        attributes = {}
        for attr in ConfigEntryMeta.cli_kwarg_attributes:
            attributes[attr] = Mock()
        # This one is renamed:
        constructor_kwargs = dict(attributes)
        constructor_kwargs['acts_config_key'] = constructor_kwargs['cli_dest']
        del constructor_kwargs['cli_dest']
        # Sets all constructor kwargs to their own mock objects.
        meta = ConfigEntryMeta(**constructor_kwargs)

        cli_kwargs = CliConfigSource._get_cli_kwargs(meta)

        expected_dict = {}
        for key, value in attributes.items():
            if value is not None:
                new_key = ConfigEntryMeta.attr_to_cli_kwarg(key)
                expected_dict[new_key] = value

        self.assertDictEqual(expected_dict, cli_kwargs)

    def test_add_arguments(self):
        """Tests that _add_arguments properly adds ConfigEntryMetas as args."""
        config_entry_metas = [
            ConfigEntryMeta(cli_flags='--i-have-a-flag'),
            ConfigEntryMeta(help='I do not have a flag')
        ]
        parser = Mock()
        CliConfigSource()._add_arguments(parser, config_entry_metas)
        parser.add_argument.assert_called_once_with(
            '--i-have-a-flag',
            **CliConfigSource._get_cli_kwargs(config_entry_metas[0]))

    def test_gather_configs(self):
        """Tests gather configs properly parses the CLI arguments."""
        def add_dummy_argument(parser):
            parser.add_argument('-t', '--test', type=str)
        source = CliConfigSource()
        source._add_arguments = lambda parser, __: add_dummy_argument(parser)
        with patch.object(sys, 'argv', ['_', '-t', 'dummy']):
            configs = source.gather_configs([])
        self.assertDictEqual(configs, {'test': 'dummy'})


if __name__ == "__main__":
    unittest.main()
