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

import os
from mock import patch

from acts.config.config_entry_meta import ConfigEntryMeta
from acts.config.config_sources.env_config_source import EnvConfigSource


class EnvConfigSourceTest(unittest.TestCase):
    """Tests EnvConfigSource"""

    def test_gather_configs(self):
        """Tests that the environment variables are properly gathered."""
        config_entry_metas = [
            ConfigEntryMeta(env_var_name='here', acts_config_key='there'),
            ConfigEntryMeta(cli_flags=['not', 'here'])
        ]
        with patch.object(os, 'environ', {'here': 'value'}):
            configs = EnvConfigSource().gather_configs(config_entry_metas)
        self.assertDictEqual({'there': 'value'}, configs)


if __name__ == "__main__":
    unittest.main()
