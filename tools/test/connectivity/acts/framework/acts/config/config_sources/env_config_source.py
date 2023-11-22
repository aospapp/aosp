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
import os

from acts.config.config_sources.config_source import ConfigSource


class EnvConfigSource(ConfigSource):
    """Defines how to obtain config data from Environment Variables."""
    def gather_configs(self, config_entry_metas):
        """Gathers configs from the environment (os.environ).

        Args:
            config_entry_metas: A list of ConfigEntryMetas to fetch inputs for.

        Returns:
            A dict of {config_key: value}.
        """
        config = {}
        for entry in config_entry_metas:
            if (entry.env_var_name is not None and
                    entry.env_var_name in os.environ):
                config[entry.acts_config_key] = os.environ[entry.env_var_name]
        return config
