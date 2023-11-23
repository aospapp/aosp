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

from acts.config import entries


class ConfigEntryMetaValidatorTest(unittest.TestCase):
    """Tests the ConfigEntryMetas are all valid."""

    def test_config_entries_have_valid_cli_flags(self):
        """Tests that all config_entries have valid cli_flags."""
        for entry in entries.config_entries:
            if entry.cli_flags is None:
                continue
            if type(entry.cli_flags) is not list:
                self.fail('Entry %s has an invalid cli_flags argument. This '
                          'value must be a string or list of strings.' % entry)
            for cli_flag in entry.cli_flags:
                if type(cli_flag) is not str:
                    self.fail('Entry %s has an invalid cli_flags argument. '
                              'Flag %s is of type %s, but must be a str.' %
                              (entry, cli_flag, type(cli_flag)))

    def test_config_entries_define_help(self):
        """Tests that all config_entries define a help function."""
        for entry in entries.config_entries:
            if entry.help is None:
                self.fail('Entry %s must define the help kwarg.' % entry)

    def test_config_entries_env_variables_has_acts_config_key(self):
        """Tests that all env var configs have a corresponding config key."""
        for entry in entries.config_entries:
            if entry.env_var_name is not None and entry.acts_config_key is None:
                self.fail('Entry %s defines env_var_name %s, but does not '
                          'define a corresponding acts_config_key value. In '
                          'order to access this config value during tests, '
                          'This value must be specified.')

    def test_config_no_conflicting_acts_config_keys(self):
        """Tests that all config_entries have unique ACTS config keys.

        This will prevent values from overwriting each other.
        """
        config_entries = entries.config_entries
        uniques = {entry.acts_config_key for entry in config_entries}
        if len(uniques) != len(config_entries):
            keys_list = [entry.acts_config_key for entry in config_entries]
            for key in uniques:
                keys_list.remove(key)

            self.fail('The following acts_config_keys are used at least '
                      'twice: %s. Duplicate acts_config_keys can cause values '
                      'to be overwritten, resulting in lost data.'
                      % set(keys_list))


if __name__ == "__main__":
    unittest.main()
