# Copyright 2018 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

"""Extra functions for working with files in the Autotest results directory.

These originate from the Autotest scheduler code and have been moved
here as lucifer takes ownership of running jobs from the scheduler.
"""

import os


def write_status_comment(results_dir, comment):
    """Write status comment in status.log.

    @param results_dir: results directory
    @param comment: comment string
    """
    with open(_status_file(results_dir), 'a') as f:
        f.write('INFO\t----\t----\t%s' % (comment,))


def write_host_keyvals(results_dir, hostname, keyvals):
    """Write host keyvals to the results directory.

    @param results_dir: results directory
    @param hostname: Hostname of host as string
    @param keyvals: dict
    """
    keyvals_dir = os.path.join(results_dir, 'host_keyvals')
    try:
        os.makedirs(keyvals_dir)
    except OSError:
        pass
    keyvals_path = os.path.join(keyvals_dir, hostname)
    with open(keyvals_path, 'w') as f:
        f.write(_format_keyvals(keyvals))


def write_keyvals(results_dir, keyvals):
    """Write keyvals to the results directory.

    @param results_dir: results directory
    @param keyvals: dict
    """
    with open(_keyvals_file(results_dir), 'a') as f:
        f.write(_format_keyvals(keyvals))


def _status_file(results_dir):
    """Return the path to the status.log file."""
    return os.path.join(results_dir, 'status.log')


def _keyvals_file(results_dir):
    """Return the path to the keyvals file."""
    return os.path.join(results_dir, 'keyval')


def _format_keyvals(keyvals):
    """Format a dict of keyvals as a string."""
    return ''.join('%s=%s\n' % (k, v) for k, v in keyvals.iteritems())
