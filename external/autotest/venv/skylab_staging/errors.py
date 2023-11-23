# Copyright 2018 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""errors contains common exception types raised from this package."""

class TestPushError(Exception):
  """Exception raised when one of the TestPush condition fails."""
