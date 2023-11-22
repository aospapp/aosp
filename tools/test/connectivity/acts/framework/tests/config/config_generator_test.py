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
import unittest
from unittest import TestCase

from mock import patch

from acts.config import config_generator as config_generator_module
from acts.config.config_generator import ConfigGenerator, ActsConfigError
from acts.config.config_sources.config_source import ConfigSource
from acts.keys import Config


class ConfigGeneratorTest(TestCase):
    """Tests the ConfigGenerator class."""

    """The config used to test _post_process_config."""
    post_process_master_config = {
        Config.key_config_full_path.value: os.path.join('foo', 'bar'),
        Config.key_testbed.value: {
            'testbed_1': {}
        }
    }

    def test_get_test_config(self):
        """Tests _get_get_config only returns the requested testbed's data."""
        config_generator = ConfigGenerator()
        config_generator._master_config = {
            'abc': '123',
            Config.key_testbed.value: {
                'testbed_1': {'asdf': 'jkl;'},
                'testbed_2': {'qwer': 'uiop'}
            }
        }
        self.assertDictEqual(
            config_generator._get_test_config('testbed_1')._dictionary,
            {
                'abc': '123',
                Config.key_testbed.value: {
                    'asdf': 'jkl;'
                },
                'asdf': 'jkl;'
            }
        )

    def _get_config_sources_are_all_config_sources(self):
        """Tests that _get_config_sources always yields a ConfigSource obj."""
        config_generator = ConfigGenerator()
        # Insert dummy value so FileConfigSource can be initialized.
        config_generator._master_config[Config.key_config_full_path.value] = ''

        for index, cfg_src in enumerate(config_generator._get_config_sources()):
            self.assertTrue(
                issubclass(type(cfg_src), ConfigSource),
                'get_config_sources returned an object that is not a valid '
                'ConfigSource. The offending object was the object returned '
                'on iteration %s: %s' % (index, cfg_src))

    @patch('acts.config.config_generator._validate_test_config')
    @patch('acts.config.config_generator._validate_testbed_configs')
    def test_post_process_configs_sets_config_directory(self, _, __):
        """Tests _post_process_configs sets the config directory properly."""
        config_generator = ConfigGenerator()
        config_generator._master_config = dict(self.post_process_master_config)
        config_generator._post_process_configs()
        self.assertEqual(
            # Doesn't use .value here on purpose due to backwards compatibility!
            # See b/29836695 and b/78189048.
            config_generator._master_config[Config.key_config_path],
            'foo'
        )

    @patch('acts.config.config_generator._validate_test_config')
    @patch('acts.config.config_generator._validate_testbed_configs')
    def test_post_process_configs_converts_tb_list_to_dict(self, _, __):
        """Tests _post_process_configs converts the testbed list to a dict."""
        config_generator = ConfigGenerator()
        config_generator._master_config = {
            Config.key_config_full_path.value: os.path.join('foo', 'bar'),
            Config.key_testbed.value: [
                {
                    Config.key_testbed_name.value: 'testbed_1'
                }
            ]
        }
        config_generator._post_process_configs()

        self.assertEqual(
            config_generator._master_config[Config.key_testbed.value],
            {'testbed_1': {Config.key_testbed_name.value: 'testbed_1'}}
        )

    def test_generate_configs(self):
        """Tests that configs are properly generated in generate_configs()."""

        class ConfigSource1():
            def gather_configs(self):
                return {
                    'a': '1',
                }

        class ConfigSource2():
            def gather_configs(self):
                return {
                    'a': '10',
                    'b': '20',
                }

        class ConfigSource3():
            def gather_configs(self):
                return {
                    'a': '100',
                    'b': '200',
                    'c': '300',
                }

        class ConfigSource4():
            def gather_configs(self):
                return {
                    Config.key_testbed.value: {
                        'testbed_1': {'tb1': 'val'},
                        'testbed_2': {'tb2': 'val'},
                    }
                }

        config_generator = ConfigGenerator()
        # Ignore post processing functions
        config_generator._post_process_configs = lambda: None
        # Set ConfigSources to be the dummies defined above
        config_generator._get_config_sources = lambda: [
            ConfigSource1, ConfigSource2, ConfigSource3, ConfigSource4]
        expected_dicts = [{
            'a': '1',
            'b': '20',
            'c': '300',
            'tb2': 'val',
            'testbed': {
                'tb2': 'val'
            }
        }, {
            'a': '1',
            'b': '20',
            'c': '300',
            'tb1': 'val',
            'testbed': {
                'tb1': 'val'
            }
        }]

        returned_configs = config_generator.generate_configs()
        obtained_dicts = [wrapped_config._dictionary for wrapped_config in
                          returned_configs]

        self.assertEqual(len(expected_dicts), len(obtained_dicts))
        for config in obtained_dicts:
            self.assertTrue(config in expected_dicts)


class ConfigGeneratorStaticFunctionTests(TestCase):
    """Tests the private static functions in the config_generator module."""

    def test_validate_test_config_raises_error(self):
        """Tests that _validate_test_config errors on invalid configs."""
        config = {}
        with self.assertRaises(ActsConfigError):
            config_generator_module._validate_test_config(config)

    def test_validate_test_config_accepts_valid_configs(self):
        """Tests that _validate_test_config can accept a valid config."""
        config = {key: '' for key in Config.reserved_keys.value}
        try:
            config_generator_module._validate_test_config(config)
        except:
            self.fail('Raised an exception on a valid config.')

    def test_validate_testbed_name_throws_on_falsey_name(self):
        """Tests that _validate_testbed_name throws on name being falsey."""
        with self.assertRaises(ActsConfigError):
            config_generator_module._validate_testbed_name('')

    def test_validate_testbed_name_throws_on_not_string(self):
        """Tests that _validate_testbed_name throws on name is not a string."""
        with self.assertRaises(ActsConfigError):
            config_generator_module._validate_testbed_name(1)

    def test_validate_testbed_name_throws_on_invalid_characters(self):
        """Tests that _validate_testbed_name throws on invalid characters."""
        with self.assertRaises(ActsConfigError):
            config_generator_module._validate_testbed_name('fail!')

    @patch('os.path.isfile', return_value=True)
    def test_update_file_paths_resolves_relative_path(self, _):
        """Tests that _update_file_paths resolves relative paths."""
        config_path = '/abs_path/'
        config = {key: './rel_path' for key in Config.file_path_keys.value}

        config_generator_module._update_file_paths(config, config_path)

        self.assertDictEqual(
            config,
            {key: os.path.join('/abs_path', './rel_path')
             for key in Config.file_path_keys.value}
        )

    @patch('os.path.isfile', return_value=False)
    def test_update_file_paths_raises_error_if_file_not_found(self, _):
        """Tests that _update_file_paths resolves relative paths."""
        config_path = '/abs_path/'
        config = {key: './rel_path' for key in Config.file_path_keys.value}

        if len(config) > 0:
            with self.assertRaises(ActsConfigError):
                config_generator_module._update_file_paths(config, config_path)

    def test_update_file_paths_keeps_abs_paths(self):
        """Tests that _update_file_paths does not modify absolute paths."""
        config_path = '/an/absolute/path'
        config = {key: '/abs_path/' for key in Config.file_path_keys.value}
        expected_config = dict(config)

        config_generator_module._update_file_paths(config, config_path)

        self.assertDictEqual(config, expected_config)

    @patch('acts.config.config_generator._validate_testbed_name')
    @patch('acts.config.config_generator._update_file_paths')
    # Note: patches are applied to arguments in reverse order.
    def test_validate_testbed_configs_calls_on_all_testbeds(
            self, _update_file_paths, _validate_testbed_name):
        """tests _validate_testbed_configs makes the proper calls."""
        testbed_configs = {
            'testbed_1': 'config_1',
            'testbed_2': 'config_2'
        }
        config_path = '/foo/bar'

        config_generator_module._validate_testbed_configs(testbed_configs,
                                                          config_path)

        _validate_testbed_name.assert_any_call('testbed_1')
        _validate_testbed_name.assert_any_call('testbed_2')
        self.assertEqual(_validate_testbed_name.call_count, 2)
        _update_file_paths.assert_any_call('config_1', config_path)
        _update_file_paths.assert_any_call('config_2', config_path)
        self.assertEqual(_update_file_paths.call_count, 2)


if __name__ == '__main__':
    unittest.main()
