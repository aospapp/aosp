# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""FAFT config setting overrides for Kalista."""

from autotest_lib.server.cros.faft.config import fizz

class Values(fizz.Values):
    """Inherit overrides from Fizz."""
    pass
