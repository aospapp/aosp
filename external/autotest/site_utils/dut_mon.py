#!/usr/bin/env python
# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""A simple service to monitor DUT statuses from master db/afe."""
import collections
import logging
import sys
import time

import common
from autotest_lib.server import constants
from autotest_lib.server import frontend
from chromite.lib import metrics
from chromite.lib import ts_mon_config

from infra_libs import ts_mon


DutCountBucket = collections.namedtuple('DutCountBucket',
                                        ['board',
                                         'model',
                                         'pool',
                                         'is_locked',
                                         'status']
                                        )


def _get_bucket_for_host(host):
    """Determine the counter bucket for |host|.

    Args:
        host: A Host object as returned by afe.

    Returns:
        A DutCountBucket instance describing the bucket for this host.
    """
    board = _get_unique_label(host.labels, constants.Labels.BOARD_PREFIX)
    model = _get_unique_label(host.labels, constants.Labels.MODEL_PREFIX)
    pool = _get_unique_label(host.labels, constants.Labels.POOL_PREFIX)
    if pool in constants.Pools.MANAGED_POOLS:
        pool = 'managed:' + pool
    status = host.status or '[None]'
    is_locked = host.locked
    return DutCountBucket(board, model, pool, is_locked, status)


def _get_unique_label(labels, prefix):
    """Return the labels for a given prefix, with prefix stripped.

    If prefixed label does not occur, return '[None]'
    If prefixed label occurs multiply, return '[Multiple]'

    _get_unique_label(['foo:1', 'foo:2', 'bar1'], 'foo:') -> '[Multiple]'

    _get_unique_label(['foo:1', 'bar2', 'baz3'], 'foo:') -> '1'

    _get_prefixed_labels(['bar1', 'baz1'], 'foo:') -> '[None]'
    """
    ls = [l[len(prefix):] for l in labels if l.startswith(prefix)]
    if not ls:
        return '[None]'
    elif len(ls) == 1:
        return ls[0]
    else:
        return '[Multiple]'


def main(argv):
    """Entry point for dut_mon."""
    logging.getLogger().setLevel(logging.INFO)

    with ts_mon_config.SetupTsMonGlobalState('dut_mon', indirect=True):
        afe = frontend.AFE()
        counters = collections.defaultdict(lambda: 0)

        field_spec = [ts_mon.StringField('board'),
                      ts_mon.StringField('model'),
                      ts_mon.StringField('pool'),
                      ts_mon.BooleanField('is_locked'),
                      ts_mon.StringField('status'),
                      ]
        dut_count = metrics.Gauge('chromeos/autotest/dut_mon/dut_count',
                                  description='The number of duts in a given '
                                              'state and bucket.',
                                  field_spec=field_spec)
        tick_count = metrics.Counter('chromeos/autotest/dut_mon/tick',
                                     description='Tick counter of dut_mon.')

        while True:
            # Note: We reset all counters to zero in each loop rather than
            # creating a new defaultdict, because we want to ensure that any
            # gauges that were previously set to a nonzero value by this process
            # get set back to zero if necessary.
            for k in counters:
                counters[k] = 0

            logging.info('Fetching all hosts.')
            hosts = afe.get_hosts()
            logging.info('Fetched %s hosts.', len(hosts))
            for host in hosts:
                fields = _get_bucket_for_host(host)
                counters[fields] += 1

            for field, value in counters.iteritems():
                logging.info('%s %s', field, value)
                dut_count.set(value, fields=field.__dict__)

            tick_count.increment()
            logging.info('Sleeping for 2 minutes.')
            time.sleep(120)


if __name__ == '__main__':
    main(sys.argv)
