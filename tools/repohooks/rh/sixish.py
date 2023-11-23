# -*- coding:utf-8 -*-
# Copyright 2017 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Local version of the standard six module.

Since this module is often unavailable by default on distros (or only available
for specific versions), manage our own little copy.
"""

from __future__ import print_function

import os
import sys

_path = os.path.realpath(__file__ + '/../..')
if sys.path[0] != _path:
    sys.path.insert(0, _path)
del _path


# Our attempts to wrap things below for diff versions of python confuse pylint.
# pylint: disable=import-error,no-name-in-module,unused-import


try:
    import configparser
except ImportError:
    import ConfigParser as configparser


# We allow mock to be disabled as it's not needed by non-unittest code.
try:
    import unittest.mock as mock
except ImportError:
    try:
        import mock
    except ImportError:
        pass


if sys.version_info.major < 3:
    # pylint: disable=basestring-builtin,undefined-variable
    string_types = basestring
else:
    string_types = str


def setenv(var, val):
    """Set |var| in the environment to |val|.

    Python 2 wants ASCII strings, not unicode.
    Python 3 only wants unicode strings.
    """
    try:
        os.environ[var] = val
    except UnicodeEncodeError:
        os.environ[var] = val.encode('utf-8')
