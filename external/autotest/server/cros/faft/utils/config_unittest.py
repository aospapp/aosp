#!/usr/bin/python3
#
# Copyright 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import json
import os
import tempfile
import unittest

import common

from autotest_lib.client.common_lib import error
from autotest_lib.server.cros.faft.utils import config


class CanLoadDefaultTestCase(unittest.TestCase):
    """Ensure that configs can load the default JSON"""

    def runTest(self):
        """Main test logic"""
        platform = "foo"
        cfg = config.Config(platform)
        self.assertIsInstance(cfg.has_keyboard, bool)


class _MockConfigTestCaseBaseClass(unittest.TestCase):
    """
    Base class which handles the setup/teardown of mock config files.

    Sub-classes should declare a class attribute, mock_configs,
    as a dict representing all platforms to be written as JSON files.
    This class writes those JSON files during setUp() and deletes them
    during tearDown().
    During runTest(), sub-classes can create config.Config instances by name
    and run assertions as normal.

    """

    mock_configs = None

    def setUp(self):
        """Set up a tempfile containing the test data"""
        if self.mock_configs is None:
            return

        # Setup mock config._CONFIG_DIR, but remember the original.
        self.mock_config_dir = tempfile.mkdtemp()
        self.original_config_dir = config._CONFIG_DIR
        config._CONFIG_DIR = self.mock_config_dir

        # Write mock config file.
        with open(config._consolidated_json_fp(), 'w') as f:
            json.dump(self.mock_configs, f)

    def tearDown(self):
        """After tests are complete, delete the tempfile"""
        if self.mock_configs is None:
            return
        os.remove(config._consolidated_json_fp())
        os.rmdir(self.mock_config_dir)
        config._CONFIG_DIR = self.original_config_dir


class InheritanceTestCase(_MockConfigTestCaseBaseClass):
    """Ensure that platforms inherit attributes correctly"""

    mock_configs = {
            'DEFAULTS': {
                    'no_override': 'default',
                    'parent_override': 'default',
                    'child_override': 'default',
                    'both_override': 'default',
                    'parent': None
            },
            'childboard': {
                    'child_override': 'child',
                    'both_override': 'child',
                    'parent': 'parentboard'
            },
            'parentboard': {
                    'parent_override': 'parent',
                    'both_override': 'parent'
            }
    }

    def runTest(self):
        """
        Verify that the following situations resolve correctly:
            A platform that inherit some overridess from another platform
            A platform that does not inherit from another platform
            A platform not found in the config file
        """
        child_config = config.Config('childboard')
        #print(child_config)
        self.assertEqual(child_config.no_override, 'default')
        self.assertEqual(child_config.parent_override, 'parent')
        self.assertEqual(child_config.child_override, 'child')
        self.assertEqual(child_config.both_override, 'child')
        with self.assertRaises(AttributeError):
            child_config.foo  # pylint: disable=pointless-statement

        parent_config = config.Config('parentboard')
        self.assertEqual(parent_config.no_override, 'default')
        self.assertEqual(parent_config.parent_override, 'parent')
        self.assertEqual(parent_config.child_override, 'default')
        self.assertEqual(parent_config.both_override, 'parent')

        foo_config = config.Config('foo')
        self.assertEqual(foo_config.no_override, 'default')
        self.assertEqual(foo_config.parent_override, 'default')
        self.assertEqual(foo_config.child_override, 'default')
        self.assertEqual(foo_config.both_override, 'default')

        # While we're here, verify that str(config) doesn't break
        str(child_config)  # pylint: disable=pointless-statement


class ModelOverrideTestCase(_MockConfigTestCaseBaseClass):
    """Verify that models of boards inherit overrides with proper precedence"""
    mock_configs = {
            'parentboard': {
                    'attr1': 'parent_attr1',
                    'attr2': 'parent_attr2',
                    'models': {
                            'modelA': {
                                    'attr1': 'parent_modelA_attr1'
                            }
                    }
            },
            'childboard': {
                    'parent': 'parentboard',
                    'attr1': 'child_attr1',
                    'models': {
                            'modelA': {
                                    'attr1': 'child_modelA_attr1'
                            }
                    }
            },
            'DEFAULTS': {
                    'models': None,
                    'attr1': 'default',
                    'attr2': 'default'
            }
    }

    def runTest(self):
        """Run assertions on test data"""
        child_config = config.Config('childboard')
        child_modelA_config = config.Config('childboard', 'modelA')
        child_modelB_config = config.Config('childboard', 'modelB')
        parent_config = config.Config('parentboard')
        parent_modelA_config = config.Config('parentboard', 'modelA')
        parent_modelB_config = config.Config('parentboard', 'modelB')

        self.assertEqual(child_config.attr1, 'child_attr1')
        self.assertEqual(child_config.attr2, 'parent_attr2')
        self.assertEqual(child_modelA_config.attr1, 'child_modelA_attr1')
        self.assertEqual(child_modelA_config.attr2, 'parent_attr2')
        self.assertEqual(child_modelB_config.attr1, 'child_attr1')
        self.assertEqual(child_modelB_config.attr2, 'parent_attr2')
        self.assertEqual(parent_config.attr1, 'parent_attr1')
        self.assertEqual(parent_config.attr2, 'parent_attr2')
        self.assertEqual(parent_modelA_config.attr1, 'parent_modelA_attr1')
        self.assertEqual(parent_modelA_config.attr2, 'parent_attr2')
        self.assertEqual(parent_modelB_config.attr1, 'parent_attr1')
        self.assertEqual(parent_modelB_config.attr2, 'parent_attr2')


class DirectSelfInheritanceTestCase(_MockConfigTestCaseBaseClass):
    """Ensure that a config which inherits from itself raises an error."""

    mock_configs = {
        'selfloop': {
            'parent': 'selfloop',
        },
    }

    def runTest(self):
        """Run assertions on test data."""
        with self.assertRaises(error.TestError):
            config.Config('selfloop')


class IndirectSelfInheritanceTestCase(_MockConfigTestCaseBaseClass):
    """Ensure that configs which inherit from each other raise an error."""

    mock_configs = {
        'indirectloop1': {
            'parent': 'indirectloop2',
        },
        'indirectloop2': {
            'parent': 'indirectloop1',
        },
        'indirectloop3': {
            'parent': 'indirectloop1',
        },
    }

    def runTest(self):
        """Run assertions on test data."""
        with self.assertRaises(error.TestError):
            config.Config('indirectloop1')
        with self.assertRaises(error.TestError):
            config.Config('indirectloop3')


class FindMostSpecificConfigTestCase(_MockConfigTestCaseBaseClass):
    """Ensure that configs named like $BOARD-kernelnext load $BOARD.json."""

    mock_configs = {
            'DEFAULTS': {},
            'samus': {},
            'veyron': {},
            'minnie': {'parent': 'veyron'},
    }

    def runTest(self):
        cfg = config.Config('samus-kernelnext')
        self.assertEqual(config.Config('samus-kernelnext').platform, 'samus')
        self.assertEqual(config.Config('samus-arc-r').platform, 'samus')
        self.assertEqual(config.Config('veyron_minnie').platform, 'minnie')
        self.assertEqual(config.Config('veyron_monroe').platform, 'veyron')
        self.assertEqual(config.Config('veyron_minnie-arc-r').platform, 'minnie')
        self.assertEqual(config.Config('veyron_monroe-arc-r').platform, 'veyron')


if __name__ == '__main__':
    unittest.main()
