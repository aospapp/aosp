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

from acts.config.config_entry_meta import ConfigEntryMeta


class ConfigEntryMetaTest(unittest.TestCase):
    """Tests the ConfigEntryMeta class."""

    def test_cli_kwargs_are_attributes_in_config_entry_meta_class(self):
        """Tests that all cli_kwargs exist as attributes in ConfigEntryMeta."""
        config_entry_meta = ConfigEntryMeta()
        for attribute_name in ConfigEntryMeta.cli_kwarg_attributes:
            if not hasattr(config_entry_meta, attribute_name):
                self.fail('Attribute %s exists in ConfigEntryMeta.cli_kwargs, '
                          'but is not an attribute in ConfigEntryMeta.' %
                          attribute_name)

    def test_config_entry_meta_converts_str_cli_flags_to_a_list(self):
        """Tests that ConfigEntryMeta's cli_flags will return as a list."""
        self.assertTrue(
            type(ConfigEntryMeta(cli_flags='test').cli_flags) is list)

    def test_config_entry_meta_keeps_cli_flags_as_list(self):
        """Tests that ConfigEntryMeta's cli_flags stays as a list."""
        self.assertTrue(
            type(ConfigEntryMeta(cli_flags=['test']).cli_flags) is list)

    def test_get_cli_kwarg_with_prefix(self):
        """Tests that a prefix-less cli_kwarg can be received."""
        key = ConfigEntryMeta.attr_to_cli_kwarg('cli_nargs')
        self.assertEqual('nargs', key, 'The cli_ prefix was not removed '
                                       'properly from the attribute.')

    def test_get_cli_kwarg_no_prefix(self):
        """Tests that a prefix-less cli_kwarg can be received."""
        kwarg = ConfigEntryMeta.attr_to_cli_kwarg('help')
        self.assertEqual('help', kwarg, 'The cli_ prefix was not removed '
                                        'properly from the attribute.')


if __name__ == "__main__":
    unittest.main()
