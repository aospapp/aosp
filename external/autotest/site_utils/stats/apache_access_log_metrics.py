#!/usr/bin/env python2

# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Script to upload metrics from apache access logs to Monarch."""

from __future__ import print_function

import argparse
import re
import sys

import common

from chromite.lib import ts_mon_config
from chromite.lib import metrics

from autotest_lib.site_utils.stats import log_daemon_common


"""
The log format is set to:
  %v:%p %h %l %u %t \"%r\" %>s %O \"%{Referer}i\" \"%{User-Agent}i\" %T

These are documented as follows:
  (from https://httpd.apache.org/docs/current/mod/mod_log_config.html)

%h: Remote host
%l: Remote logname (from identd, if supplied)
%O: Bytes sent, including headers. May be zero in rare cases such as when a
    request is aborted before a response is sent. You need to enable mod_logio
    to use this.
%p: The canonical Port of the server serving the request
%r: First line of request
%s: Status.  For requests that got internally redirected, this is
    the status of the *original* request --- %...>s for the last.
%t: Time, in common log format time format (standard english format)
%T: The time taken to serve the request, in seconds.
%u: Remote user (from auth; may be bogus if return status (%s) is 401)
%v: The canonical ServerName of the server serving the request.
"""

# Lemma: a regex to match sections delimited be double-quotes ("), which
# possible contained escaped quotes (\").
# This works by matching non-quotes or the string r'\"' repeatedly; then it ends
# when finding a quote (") preceeded by a character which is not a backslash.
MATCH_UNTIL_QUOTE = r'([^"]|\\")*[^\\]'

ACCESS_MATCHER = re.compile(
    r'^'
    r'\S+ \S+ \S+ \S+ '               # Ignore %v:%p %h %l %u
    r'\[[^]]+\] '                     # Ignore %t
    r'"('                             # Begin %r
    r'(?P<request_method>\S+) '       # e.g. POST
    r'(?P<endpoint>\S+)'              # e.g. /afe/server/noauth/rpc/
    + MATCH_UNTIL_QUOTE +             # Ignore protocol (e.g. HTTP/1.1)
    r'|-'                             # The request data might just be "-"
    r')" '                            # End %r
    r'(?P<response_code>\d+) '        # %>s (e.g. 200)
    r'(?P<bytes_sent>\d+)'            # %O
    r' "' + MATCH_UNTIL_QUOTE + '"'   # Ignore Referer
    r' "' + MATCH_UNTIL_QUOTE + '"'   # Ignore User-Agent
    r' ?(?P<response_seconds>\d+?)'   # The server time in seconds
    r'.*'                             # Allow adding extra stuff afterward.
)

ACCESS_TIME_METRIC = '/chromeos/autotest/http/server/response_seconds'
ACCESS_BYTES_METRIC = '/chromeos/autotest/http/server/response_bytes'


# TODO(phobbs) use something more systematic than a whitelist.
WHITELISTED_ENDPOINTS = frozenset((
    '/',
    '/afe/clear.cache.gif',
    '/afe/Open+Sans:300.woff',
    '/embedded_spreadsheet/autotest.EmbeddedSpreadsheetClient.nocache.js',
    '/afe/afeclient.css',
    '/afe/common.css',
    '/afe/header.png',
    '/afe/spinner.gif',
    '/afe/standard.css',
    '/afe/2371F6F3D4E42171A3563D94B7BF42BF.cache.html',
    '/afe/autotest.AfeClient.nocache.js',
    '/afe/',
    '/new_tko/server/rpc/',
    '/afe/server/rpc/',
    '/___rPc_sWiTcH___',
    '*',
    '/afe/server/noauth/rpc/',
))


def EmitRequestMetrics(m):
    """Emits metrics for each line in the access log.

    @param m: A regex match object
    """
    # TODO(phobbs) use a memory-efficient structure to detect non-unique paths.
    # We can't just include the endpoint because it will cause a cardinality
    # explosion.
    endpoint = SanitizeEndpoint(m.group('endpoint'))
    fields = {
        'request_method': m.groupdict().get('request_method', ''),
        'endpoint': endpoint,
        'response_code': int(m.group('response_code')),
    }

    # Request seconds and bytes sent are both extremely high cardinality, so
    # they must be the VAL of a metric, not a metric field.
    if m.group('response_seconds'):
      response_seconds = int(m.group('response_seconds'))
      metrics.SecondsDistribution(ACCESS_TIME_METRIC).add(
          response_seconds, fields=fields)

    bytes_sent = int(m.group('bytes_sent'))
    metrics.CumulativeDistribution(ACCESS_BYTES_METRIC).add(
        bytes_sent, fields=fields)


def SanitizeEndpoint(endpoint):
    """Returns empty string if endpoint is not whitelisted.

    @param endpoint: The endpoint to sanitize.
    """
    if endpoint in WHITELISTED_ENDPOINTS:
        return endpoint
    else:
        return ''


MATCHERS = [
    (ACCESS_MATCHER, EmitRequestMetrics),
]


def ParseArgs():
    """Parses the command line arguments."""
    p = argparse.ArgumentParser(
        description='Parses apache logs and emits metrics to Monarch')
    p.add_argument('--output-logfile')
    p.add_argument('--debug-metrics-file',
                   help='Output metrics to the given file instead of sending '
                   'them to production.')
    return p.parse_args()


def Main():
    """Sets up logging and runs matchers against stdin."""
    args = ParseArgs()
    log_daemon_common.SetupLogging(args)

    # Set up metrics sending and go.
    ts_mon_args = {}
    if args.debug_metrics_file:
        ts_mon_args['debug_file'] = args.debug_metrics_file

    with ts_mon_config.SetupTsMonGlobalState('apache_access_log_metrics',
                                             **ts_mon_args):
      log_daemon_common.RunMatchers(sys.stdin, MATCHERS)


if __name__ == '__main__':
    Main()
