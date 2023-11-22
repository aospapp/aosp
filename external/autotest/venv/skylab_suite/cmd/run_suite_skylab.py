# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Wrapper for running suites of tests and waiting for completion."""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import os
import sys

import logging

from lucifer import autotest
from skylab_suite import cros_suite
from skylab_suite import suite_parser
from skylab_suite import suite_runner
from skylab_suite import suite_tracking
from skylab_suite import swarming_lib


PROVISION_SUITE_NAME = 'provision'


def _parse_suite_handler_spec(options):
    provision_num_required = 0
    if 'num_required' in options.suite_args:
        provision_num_required = options.suite_args['num_required']

    return cros_suite.SuiteHandlerSpec(
            suite_name=options.suite_name,
            wait=not options.create_and_return,
            suite_id=options.suite_id,
            timeout_mins=options.timeout_mins,
            passed_mins=options.passed_mins,
            test_retry=options.test_retry,
            max_retries=options.max_retries,
            provision_num_required=provision_num_required)


def _should_run(suite_spec):
    tags = {'build': suite_spec.test_source_build,
            'suite': suite_spec.suite_name}
    tasks = swarming_lib.query_task_by_tags(tags)
    current_task_id = suite_tracking.get_task_id_for_task_summaries(
            os.environ.get('SWARMING_TASK_ID'))
    logging.info('The current task id is: %s', current_task_id)
    extra_task_ids = set([])
    for t in tasks:
        if t['task_id'] != current_task_id:
            extra_task_ids.add(t['task_id'])

    return extra_task_ids


def _run_suite(options):
    swarming_client = swarming_lib.Client(options.swarming_auth_json)
    run_suite_common = autotest.load('site_utils.run_suite_common')
    logging.info('Kicked off suite %s', options.suite_name)
    suite_spec = suite_parser.parse_suite_spec(options)
    if options.pre_check:
        extra_task_ids = _should_run(suite_spec)
        if extra_task_ids:
            logging.info(
                    'The same suites are already run in the past: \n%s',
                    '\n'.join([swarming_lib.get_task_link(tid)
                               for tid in extra_task_ids]))
            return run_suite_common.SuiteResult(
                    run_suite_common.RETURN_CODES.OK)

    if options.suite_name == PROVISION_SUITE_NAME:
        suite_job = cros_suite.ProvisionSuite(suite_spec, swarming_client)
    else:
        suite_job = cros_suite.Suite(suite_spec, swarming_client)

    try:
        suite_job.prepare()
    except Exception as e:
        logging.exception('Infra failure in setting up suite job')
        return run_suite_common.SuiteResult(
                run_suite_common.RETURN_CODES.INFRA_FAILURE)

    suite_handler_spec = _parse_suite_handler_spec(options)
    suite_handler = cros_suite.SuiteHandler(suite_handler_spec, swarming_client)
    suite_runner.run(swarming_client,
                     suite_job.test_specs,
                     suite_handler,
                     options.dry_run)

    if options.create_and_return:
        suite_tracking.log_create_task(suite_job.suite_name,
                                       suite_handler.suite_id)
        suite_tracking.print_child_test_annotations(suite_handler)
        return run_suite_common.SuiteResult(run_suite_common.RETURN_CODES.OK)

    return_code = suite_tracking.log_suite_results(
                suite_job.suite_name, suite_handler)
    return run_suite_common.SuiteResult(return_code)


def parse_args():
    """Parse & validate skylab suite args."""
    parser = suite_parser.make_parser()
    options = parser.parse_args()
    if options.do_nothing:
        logging.info('Exit early because --do_nothing requested.')
        sys.exit(0)

    if not suite_parser.verify_and_clean_options(options):
        parser.print_help()
        sys.exit(1)

    return options


def _setup_env(options):
    """Set environment variables based on commandline options."""
    os.environ['SWARMING_CREDS'] = options.swarming_auth_json


def main():
    """Entry point."""
    autotest.monkeypatch()

    options = parse_args()
    _setup_env(options)
    suite_tracking.setup_logging()
    result = _run_suite(options)
    logging.info('Will return from %s with status: %s',
                 os.path.basename(__file__), result.string_code)
    return result.return_code


if __name__ == "__main__":
    sys.exit(main())
