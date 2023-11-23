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
from acts.config.config_sources.config_source import ConfigSource
from acts.config.file_loader import ConfigFileLoader


class FileConfigSource(ConfigSource):
    """Defines the method to pull config data from a file."""
    def __init__(self, file_path):
        self.file_path = file_path

    def gather_configs(self, config_entry_metas):
        """Gathers configs from a config file. Is file-type agnostic.

        Args:
            config_entry_metas: A list of ConfigEntryMetas to fetch inputs for.

        Returns:
            The configuration dict pulled from the file.
        """
        # Ignore the values in the metas, since ACTS config files must always
        # Adhere to the acts_config_value.
        return ConfigFileLoader.load_config_from_file(self.file_path)
