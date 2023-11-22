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
from unittest import TestCase

from acts.config.config_sources.config_source import ConfigSource


class ConfigSourceTest(TestCase):
    """Tests the ConfigSource base class"""
    def test_gather_configs_raises_not_implemented_error(self):
        """Tests that calling gather_configs() raises NotImplementedError"""
        with self.assertRaises(NotImplementedError):
            ConfigSource().gather_configs([])


if __name__ == 'main':
    unittest.main()
