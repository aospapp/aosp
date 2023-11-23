# Copyright 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

'''Utilities to summarize TKO results reported by tests in the suite.'''

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import argparse
import collections
import contextlib
import mysql.connector

def Error(Exception):
    """Error detected in this script."""


# Row corresponds to a single row of tko_test_view_2 table in AFE DB, but
# contains only a subset of the columns in the table.
Row = collections.namedtuple(
        'Row',
        'name, status, reason'
)


def get(conn, task_ids):
    """Get tko_test_view_2 Row()s for given skylab task_ids.

    @param conn: A MySQL connection to TKO.
    @param task_ids: list of Skylab task request IDs to collect test views for.
    @return: {task_id: [Row(...)...]}
    """
    try:
        task_job_ids = _get_job_idxs_from_tko(conn, task_ids)
        job_task_ids = {v: k for k, v in task_job_ids.iteritems()}
        job_rows = _get_rows_from_tko(conn, job_task_ids.keys())
        return {job_task_ids[k]: v for k, v in job_rows.iteritems()}
    finally:
        conn.close()


def filter_failed(rows):
    """Filter down given list of test_views Row() to failed tests."""
    return [r for r in rows if r.status in _BAD_STATUSES]


def main():
    '''Entry-point to use this script standalone.'''
    parser = argparse.ArgumentParser(
            description='Summarize TKO results for a Skylab task')
    parser.add_argument(
            '--task-id',
            action='append',
            help='Swarming request ID for the skylab task (may be repeated)',
    )
    parser.add_argument(
            '--host',
            required=True,
            help='TKO host IP',
    )
    parser.add_argument(
            '--port',
            type=int,
            default=3306,
            help='TKO port',
    )
    parser.add_argument(
            '--user',
            required=True,
            help='TKO MySQL user',
    )
    parser.add_argument(
            '--password',
            required=True,
            help='TKO MySQL password',
    )
    args = parser.parse_args()
    if not args.task_id:
        raise Error('Must request at least one --task-id')

    conn = mysql.connector.connect(
            host=args.host,
            port=args.port,
            user=args.user,
            password=args.password,
            database='chromeos_autotest_db',
    )
    views = get(conn, args.task_id)
    for task_id, rows in views.iteritems():
        print('Task ID: %s' % task_id)
        for row in filter_failed(rows):
            print('  %s in status %s' % (row.name, row.status))
            print('    reason: %s' % (row.reason,))
        print('')


_BAD_STATUSES = {
        'ABORT',
        'ERROR',
        'FAIL',
}


def _get_rows_from_tko(conn, tko_job_ids):
    """Get a list of Row() for the given TKO job IDs.

    @param conn: A MySQL connection.
    @param job_ids: List of tko_job_ids to get Row()s for.
    @return: {tko_job_id: [Row]}
    """
    job_rows = collections.defaultdict(list)
    statuses = _get_status_map(conn)

    _GET_TKO_TEST_VIEW_2 = """
    SELECT job_idx, test_name, status_idx, reason FROM tko_test_view_2
            WHERE invalid = 0 AND job_idx IN (%s)
    """
    q = _GET_TKO_TEST_VIEW_2 % ', '.join(['%s'] * len(tko_job_ids))
    with _cursor(conn) as cursor:
        cursor.execute(q, tko_job_ids)
        for job_idx, name, s_idx, reason in cursor.fetchall():
            job_rows[job_idx].append(
                    Row(name, statuses.get(s_idx, 'UNKNOWN'), reason))
    return dict(job_rows)


def _get_job_idxs_from_tko(conn, task_ids):
    """Get tko_job_idx for given task_ids.

    Task execution reports the run ID to TKO, but Skylab clients only knows the
    request ID of the created task.
    Swarming executes a task with increasing run IDs, retrying on bot failure.
    If a task is retried after the point where TKO results are reported, this
    function returns the TKO job_idx corresponding to the last completed
    attempt.

    @param conn: MySQL connection to TKO.
    @param task_ids: List of task request IDs to get TKO job IDs for.
    @return {task_id: job_id}
    """
    task_runs = {}
    run_ids = []
    for task_id in task_ids:
        run_ids += _run_ids_for_request(task_id)
        task_runs[task_id] = list(reversed(run_ids))
    run_job_idxs = _get_job_idxs_for_run_ids(conn, run_ids)

    task_job_idxs = {}
    for task_id, run_ids in task_runs.iteritems():
        for run_id in run_ids:
            if run_id in run_job_idxs:
                task_job_idxs[task_id] = run_job_idxs[run_id]
                break
    return task_job_idxs


def _get_job_idxs_for_run_ids(conn, run_ids):
    """Get tko_job_idx for a given task run_ids.

    @param conn: MySQL connection to TKO.
    @param task_ids: List of task run IDs to get TKO job IDs for.
    @return {run_id: job_id}
    """
    _GET_TKO_JOB_Q = """
    SELECT task_id, tko_job_idx FROM tko_task_references
            WHERE reference_type = "skylab" AND task_id IN (%s)
    """
    q = _GET_TKO_JOB_Q % ', '.join(['%s'] * len(run_ids))

    job_idxs = {}
    with _cursor(conn) as cursor:
        cursor.execute(q, run_ids)
        for run_id, tko_job_idx in cursor.fetchall():
            if run_id in job_idxs:
                raise Error('task run ID %s has multiple tko references' %
                            (run_id,))
            job_idxs[run_id] = tko_job_idx
    return job_idxs


def _get_status_map(conn):
    statuses = {}
    with _cursor(conn) as cursor:
        cursor.execute('SELECT status_idx, word FROM tko_status')
        r = cursor.fetchall()
        for idx, word in r:
            statuses[idx] = word
    return statuses


def _run_ids_for_request(request_id):
    """Return Swarming run IDs for a given request ID, in ascending order."""
    prefix = request_id[:len(request_id)-1]
    return [prefix + i for i in ('1', '2')]


@contextlib.contextmanager
def _cursor(conn):
    c = conn.cursor()
    try:
        yield c
    finally:
        c.close()


if __name__ == '__main__':
  main()
