# Copyright 2018 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

from lucifer import autotest


def pytest_configure():
  """Configuration befor pytest starts."""
  autotest.monkeypatch()
