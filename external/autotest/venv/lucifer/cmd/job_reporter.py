# Copyright 2017 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Run a job against Autotest.

See http://goto.google.com/monitor_db_per_job_refactor

See also https://chromium.googlesource.com/chromiumos/infra/lucifer

job_reporter is a thin wrapper around lucifer and only updates the
Autotest database according to status events.
"""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import atexit
import argparse
import logging
import os
import sys

from lucifer import autotest
from lucifer import eventlib
from lucifer import handlers
from lucifer import jobx
from lucifer import leasing
from lucifer import loglib

logger = logging.getLogger(__name__)


def main(argv):
    """Main function

    @param argv: command line args
    """
    print('job_reporter: Running with argv: %r' % argv, file=sys.stderr)
    args = _parse_args_and_configure_logging(argv[1:])
    logger.info('Running with parsed args: %r', args)
    with leasing.obtain_lease(_lease_path(args.jobdir, args.job_id)):
        autotest.monkeypatch()
        ret = _main(args)
    logger.info('Exiting normally with: %r', ret)
    return ret


def _parse_args_and_configure_logging(args):
    parser = argparse.ArgumentParser(prog='job_reporter', description=__doc__)
    loglib.add_logging_options(parser)

    # General configuration
    parser.add_argument('--jobdir', default='/usr/local/autotest/leases',
                        help='Path to job leases directory.')
    parser.add_argument('--lucifer-path', default='/usr/bin/lucifer',
                        help='Path to lucifer binary')

    # Job specific

    # General
    parser.add_argument('--lucifer-level', required=True,
                        help='Lucifer level', choices=['STARTING'])
    parser.add_argument('--job-id', type=int, required=True,
                        help='Autotest Job ID')
    parser.add_argument('--results-dir', required=True,
                        help='Path to job results directory.')

    # STARTING flags
    parser.add_argument('--execution-tag', default=None,
                        help='Autotest execution tag.')
    parser.add_argument('--parsing-only', action='store_true',
                        help='Whether to only do parsing'
                        ' (only with --lucifer-level STARTING)')

    args = parser.parse_args(args)
    loglib.configure_logging_with_args(parser, args)
    return args


def _main(args):
    """Main program body, running under a lease file.

    @param args: Namespace object containing parsed arguments
    """
    ts_mon_config = autotest.chromite_load('ts_mon_config')
    metrics = autotest.chromite_load('metrics')
    with ts_mon_config.SetupTsMonGlobalState(
            'job_reporter', short_lived=True):
        atexit.register(metrics.Flush)
        return _run_autotest_job(args)


def _run_autotest_job(args):
    """Run a job as seen from Autotest.

    This include some Autotest setup and cleanup around lucifer starting
    proper.
    """
    models = autotest.load('frontend.afe.models')
    job = models.Job.objects.get(id=args.job_id)
    _prepare_autotest_job_files(args, job)
    handler = _make_handler(args, job)
    ret = _run_lucifer_job(handler, args, job)
    if handler.completed:
        _mark_handoff_completed(args.job_id)
    return ret


def _prepare_autotest_job_files(args, job):
    jobx.prepare_control_file(job, args.results_dir)
    jobx.prepare_keyvals_files(job, args.results_dir)


def _make_handler(args, job):
    """Make event handler for lucifer."""
    return handlers.EventHandler(
            metrics=handlers.Metrics(),
            job=job,
            autoserv_exit=None,
            results_dir=args.results_dir,
    )


def _run_lucifer_job(event_handler, args, job):
    """Run lucifer test.

    Issued events will be handled by event_handler.

    @param event_handler: callable that takes an Event
    @param args: parsed arguments
    @returns: exit status of lucifer
    """
    command_args = [args.lucifer_path]
    command_args.extend([
            'test',
            '-autotestdir', '/usr/local/autotest',

            '-abortsock', _abort_sock_path(args.jobdir, args.job_id),
            '-hosts', ','.join(jobx.hostnames(job)),

            '-x-level', args.lucifer_level,
            '-resultsdir', args.results_dir,
    ])
    _add_level_specific_args(command_args, args, job)
    return eventlib.run_event_command(
            event_handler=event_handler, args=command_args)


def _add_level_specific_args(command_args, args, job):
    """Add level specific arguments for lucifer test.

    command_args is modified in place.
    """
    if args.lucifer_level == 'STARTING':
        _add_starting_args(command_args, args, job)
    else:
        raise Exception('Invalid lucifer level %s' % args.lucifer_level)


def _add_starting_args(command_args, args, job):
    """Add STARTING level arguments for lucifer test.

    command_args is modified in place.
    """
    RebootAfter = autotest.load('frontend.afe.model_attributes').RebootAfter
    command_args.extend([
        '-x-control-file', jobx.control_file_path(args.results_dir),
    ])
    if args.execution_tag is not None:
        command_args.extend(['-x-execution-tag', args.execution_tag])
    command_args.extend(['-x-job-owner', job.owner])
    command_args.extend(['-x-job-name', job.name])
    command_args.extend(
            ['-x-reboot-after',
             RebootAfter.get_string(job.reboot_after).lower()])
    if args.parsing_only:
        command_args.append('-x-parse-only')
    if job.run_reset:
        command_args.append('-x-run-reset')
    if jobx.is_client_job(job):
        command_args.append('-x-client-test')
    if jobx.needs_ssp(job):
        command_args.append('-x-require-ssp')
        test_source_build = job.keyval_dict().get('test_source_build', None)
        if test_source_build:
            command_args.extend(['-x-test-source-build', test_source_build])
    if job.parent_job_id:
        command_args.extend(['-x-parent-job-id', str(job.parent_job_id)])


def _mark_handoff_completed(job_id):
    models = autotest.load('frontend.afe.models')
    handoff = models.JobHandoff.objects.get(job_id=job_id)
    handoff.completed = True
    handoff.save()


def _abort_sock_path(jobdir, job_id):
    return _lease_path(jobdir, job_id) + '.sock'


def _lease_path(jobdir, job_id):
    return os.path.join(jobdir, str(job_id))


if __name__ == '__main__':
    sys.exit(main(sys.argv))
