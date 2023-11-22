#!/usr/bin/env python3
#
# Copyright 2018 - The Android Open Source Project
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

"""Config helper class."""

import copy
import json
import logging
import os

_DEFAULT_CONFIG_FILE = 'aidegen.config'


class AidegenConfig():
    """Class manages AIDEGen's configurations."""

    def __init__(self):
        self._config = {}
        self._config_backup = {}
        self._config_file = self._get_default_config_file()

    def __enter__(self):
        self._load_aidegen_config()
        self._config_backup = copy.deepcopy(self._config)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self._save_aidegen_config()

    @property
    def preferred_version(self):
        """AIDEGen configuration getter.

        Returns:
            The preferred verson item of configuration data if exists, otherwise
            None.
        """
        return self._config.get('preferred_version', '')

    @preferred_version.setter
    def preferred_version(self, preferred_version):
        """AIDEGen configuration setter.

        Args:
            preferred_version: A string, user's preferred version to be set.
        """
        self._config['preferred_version'] = preferred_version

    def _load_aidegen_config(self):
        """Load data from configuration file."""
        if os.path.exists(self._config_file):
            try:
                with open(self._config_file, 'r') as cfg_file:
                    self._config = json.load(cfg_file)
            except ValueError as err:
                info = '{} format is incorrect, error: {}'.format(
                    self._config_file, err)
                logging.info(info)
            except IOError as err:
                logging.error(err)
                raise

    def _save_aidegen_config(self):
        """Save data to configuration file."""
        if self._is_config_modified():
            with open(self._config_file, 'w') as cfg_file:
                json.dump(self._config, cfg_file, indent=4)

    def _is_config_modified(self):
        """Check if configuration data is modified."""
        return any(key for key in self._config if not key in self._config_backup
                   or self._config[key] != self._config_backup[key])

    @staticmethod
    def _get_default_config_file():
        """Return path of default configuration file."""
        cfg_path = os.path.join(
            os.path.expanduser('~'), '.config', 'asuite', 'aidegen')
        if not os.path.exists(cfg_path):
            os.makedirs(cfg_path)
        return os.path.join(cfg_path, _DEFAULT_CONFIG_FILE)
