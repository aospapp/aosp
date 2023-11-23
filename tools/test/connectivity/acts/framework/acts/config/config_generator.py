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
import logging
import os

from acts import utils
from acts.config import entries

from acts.config.config_sources.cli_config_source import CliConfigSource
from acts.config.config_sources.env_config_source import EnvConfigSource
from acts.config.config_sources.file_config_source import FileConfigSource
from acts.config.config_wrapper import ConfigWrapper
from acts.keys import Config


class ActsConfigError(Exception):
    """Raised when there is a problem in test configuration file."""


class ConfigGenerator(object):
    """A class for generating the master configuration for running tests."""

    def __init__(self):
        """Initializes the ConfigGenerator."""
        self._master_config = {}

    def _get_test_config(self, testbed_name):
        """Returns a test config for a given testbed."""
        test_config = dict(self._master_config)
        testbed_data = (
            self._master_config[Config.key_testbed.value][testbed_name])

        test_config[Config.key_testbed.value] = testbed_data
        # Keys in each test bed config will be copied to a level up to be
        # picked up for user_params. If the key already exists in the upper
        # level, the local one defined in test bed config overwrites the
        # general one.
        test_config.update(testbed_data)
        return ConfigWrapper(test_config)

    def generate_configs(self):
        """Returns the configuration to be used for the tests and framework."""
        config_entries = entries.config_entries
        for config_src in self._get_config_sources():
            for key, value in config_src.gather_configs(config_entries).items():
                if key not in self._master_config:
                    self._master_config[key] = value

        self._post_process_configs()

        generated_testbeds = []
        for name in self._master_config.get(
                Config.key_testbeds_under_test.value,
                self._master_config[Config.key_testbed.value].keys()):
            generated_testbeds.append(self._get_test_config(name))
        return generated_testbeds

    def _get_config_sources(self):
        """Returns the sources in the order of most-to-least significant.

        Values from ConfigSources returned earlier should not be overwritten by
        the values obtained by later sources.
        """
        yield CliConfigSource()
        yield FileConfigSource(
            self._master_config[Config.key_config_full_path.value])
        yield EnvConfigSource()

    def _post_process_configs(self):
        """Does post processing on the configs.

        This function will handle obtaining additional values from the configs,
        well as handle the testbed config creation.
        """
        # Sets the config directory.
        # TODO(b/29836695): Remove after the key has been deprecated.
        config_dir = os.path.dirname(
            self._master_config[Config.key_config_full_path.value])
        self._master_config[Config.key_config_path] = config_dir

        # Normalizes the "testbed" field to be a dictionary if not already.
        if type(self._master_config[Config.key_testbed.value]) is list:
            self._master_config[Config.key_testbed.value] = (
                utils.list_of_dict_to_dict_of_dict(
                    self._master_config[Config.key_testbed.value],
                    Config.key_testbed_name.value))

        # For backwards compatibility, makes sure the name of each testbed name
        # is available to the testbed data.
        for name, testbed in (
                   self._master_config[Config.key_testbed.value].items()):
            if Config.key_testbed_name.value not in testbed:
                testbed[Config.key_testbed_name.value] = name

        # Validates the given configs.
        _validate_test_config(self._master_config)
        _validate_testbed_configs(self._master_config[Config.key_testbed.value],
                                  config_dir)


def _validate_test_config(test_config):
    """Validates the raw configuration loaded from the config file.

    Makes sure all the required fields exist.
    """
    for k in Config.reserved_keys.value:
        if k not in test_config:
            raise ActsConfigError(
                'Required key "%s" is missing from the test config.' % k)


def _validate_testbed_configs(testbed_configs, config_path):
    """Validates the testbed configurations.

    Args:
        testbed_configs: A list of testbed configuration json objects.
        config_path : The path to the config file, which can be used to
                      generate absolute paths from relative paths in configs.

    Raises:
        If any part of the configuration is invalid, ActsConfigError is raised.
    """
    for name, config in testbed_configs.items():
        _validate_testbed_name(name)
        # TODO(b/78189048): Remove this after deprecating relative paths.
        _update_file_paths(config, config_path)


def _update_file_paths(config, config_path):
    """ Checks if the path entries are valid.

    If the file path is invalid, assume it is a relative path and append
    that to the config file path.

    Args:
        config : the config object to verify.
        config_path : The path to the config file, which can be used to
                      generate absolute paths from relative paths in configs.

    Raises:
        If the file path is invalid, ActsConfigError is raised.
    """
    # Check the file_path_keys and update if it is a relative path.
    for file_path_key in Config.file_path_keys.value:
        if file_path_key in config:
            config_file = config[file_path_key]
            if type(config_file) is str:
                if os.path.isabs(config_file):
                    continue
                logging.warning(
                    'Relative paths for key "%s" will be deprecated shortly. '
                    'Please update your configuration to use an absolute path.',
                    file_path_key
                )
                config_file = os.path.join(config_path, config_file)
                if not os.path.isfile(config_file):
                    raise ActsConfigError('Unable to load config %s from test '
                                          'config file.', config_file)
                config[file_path_key] = config_file


def _validate_testbed_name(name):
    """Validates the name of a test bed.

    Since test bed names are used as part of the test run id, it needs to meet
    certain requirements.

    Args:
        name: The test bed's name specified in config file.

    Raises:
        If the name does not meet any criteria, ActsConfigError is raised.
    """
    if not name:
        raise ActsConfigError('Testbed names cannot be empty.')
    if not isinstance(name, str):
        raise ActsConfigError('Testbed names have to be string.')
    invalid_chars = set(name) - set(utils.valid_filename_chars)
    if invalid_chars:
        raise ActsConfigError(
            'Testbed "%s" has following disallowed characters: %s' %
            (name, set(name) - set(utils.valid_filename_chars)))
