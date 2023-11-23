# Copyright 2018 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""skylab_suite includes functions to run suites in skylab.

This directory includes all tools for running suites of tests and waiting for
their completion.

By default this tool will block until the job is complete, printing a summary
at the end.

This is intended for use only for ChromeOS test suites.
"""
