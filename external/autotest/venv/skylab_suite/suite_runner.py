# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Module for CrOS dynamic test suite generation and execution."""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import contextlib
import itertools
import json
import logging
import os
import re
import time

from lucifer import autotest
from skylab_suite import cros_suite
from skylab_suite import swarming_lib


SKYLAB_DRONE_SWARMING_WORKER = '/opt/infra-tools/skylab_swarming_worker'
SKYLAB_SUITE_USER = 'skylab_suite_runner'
SKYLAB_TOOL = '/opt/infra-tools/skylab'

SUITE_WAIT_SLEEP_INTERVAL_SECONDS = 30

# See #5 in crbug.com/873886 for more details.
_NOT_SUPPORTED_DEPENDENCIES = ['skip_provision', 'cleanup-reboot', 'rpm',
                               'modem_repair']


def run(client, test_specs, suite_handler, dry_run=False):
    """Run a CrOS dynamic test suite.

    @param client: A swarming_lib.Client instance.
    @param test_specs: A list of cros_suite.TestSpec objects.
    @param suite_handler: A cros_suite.SuiteHandler object.
    @param dry_run: Whether to kick off dry runs of the tests.
    """
    assert isinstance(client, swarming_lib.Client)
    if suite_handler.suite_id:
        # Resume an existing suite.
        _resume_suite(client, test_specs, suite_handler, dry_run)
    else:
        # Make a new suite.
        _run_suite(test_specs, suite_handler, dry_run)


def _resume_suite(client, test_specs, suite_handler, dry_run=False):
    """Resume a suite and its child tasks by given suite id."""
    assert isinstance(client, swarming_lib.Client)
    suite_id = suite_handler.suite_id
    all_tasks = client.get_child_tasks(suite_id)
    not_yet_scheduled = _get_unscheduled_test_specs(
            test_specs, suite_handler, all_tasks)

    logging.info('Not yet scheduled test_specs: %r', not_yet_scheduled)
    _create_test_tasks(not_yet_scheduled, suite_handler, suite_id, dry_run)

    if suite_id is not None and suite_handler.should_wait():
        _wait_for_results(suite_handler, dry_run=dry_run)


def _get_unscheduled_test_specs(test_specs, suite_handler, all_tasks):
    not_yet_scheduled = []
    for test_spec in test_specs:
        if suite_handler.is_provision():
            # We cannot check bot_id because pending tasks do not have it yet.
            bot_id_tag = 'id:%s' % test_spec.bot_id
            tasks = [t for t in all_tasks if bot_id_tag in t['tags']]
        else:
            tasks = [t for t in all_tasks if t['name']==test_spec.test.name]

        if not tasks:
            not_yet_scheduled.append(test_spec)
            continue

        current_task = _get_current_task(tasks)
        test_task_id = (current_task['task_id'] if current_task
                        else tasks[0]['task_id'])
        remaining_retries = test_spec.test.job_retries - len(tasks)
        previous_retried_ids = [t['task_id'] for t in tasks
                                if t['task_id'] != test_task_id]
        suite_handler.add_test_by_task_id(
                test_task_id,
                cros_suite.TestHandlerSpec(
                        test_spec=test_spec,
                        remaining_retries=remaining_retries,
                        previous_retried_ids=previous_retried_ids))

    return not_yet_scheduled


def _get_current_task(tasks):
    """Get current running task.

    @param tasks: A list of task dicts including task_id, state, etc.

    @return a dict representing the current running task.
    """
    current_task = None
    for t in tasks:
        if t['state'] not in swarming_lib.TASK_FINISHED_STATUS:
            if current_task:
                raise ValueError(
                        'Parent task has 2 same running child tasks: %s, %s'
                        % (current_task['task_id'], t['task_id']))

            current_task = t

    return current_task


def _run_suite(test_specs, suite_handler, dry_run=False):
    """Make a new suite."""
    suite_id = os.environ.get('SWARMING_TASK_ID')
    if not suite_id:
        raise ValueError("Unable to determine suite's task id from env var "
                         "SWARMING_TASK_ID.")
    _create_test_tasks(test_specs, suite_handler, suite_id, dry_run)
    suite_handler.set_suite_id(suite_id)

    if suite_handler.should_wait():
        _wait_for_results(suite_handler, dry_run=dry_run)


def _create_test_tasks(test_specs, suite_handler, suite_id, dry_run=False):
    """Create test tasks for a list of tests (TestSpecs).

    Given a list of TestSpec object, this function will schedule them on
    swarming one by one, and add them to the swarming_task_id-to-test map
    of suite_handler to keep monitoring them.

    @param test_specs: A list of cros_suite.TestSpec objects to schedule.
    @param suite_handler: A cros_suite.SuiteHandler object to monitor the
        test_specs' progress.
    @param suite_id: A string ID for a suite task, it's the parent task id for
        these to-be-scheduled test_specs.
    @param dry_run: Whether to kick off dry runs of the tests.
    """
    for test_spec in test_specs:
        test_task_id = _create_test_task(
                test_spec,
                suite_id=suite_id,
                is_provision=suite_handler.is_provision(),
                dry_run=dry_run)
        suite_handler.add_test_by_task_id(
                test_task_id,
                cros_suite.TestHandlerSpec(
                        test_spec=test_spec,
                        remaining_retries=test_spec.test.job_retries - 1,
                        previous_retried_ids=[]))


def _create_test_task(test_spec, suite_id=None,
                      is_provision=False, dry_run=False):
    """Create a test task for a given test spec.

    @param test_spec: A cros_suite.TestSpec object.
    @param suite_id: the suite task id of the test.
    @param dry_run: If true, don't actually create task.

    @return the swarming task id of this task.
    """
    logging.info('Creating task for test %s', test_spec.test.name)
    skylab_tool_path = os.environ.get('SKYLAB_TOOL', SKYLAB_TOOL)

    cmd = [
        skylab_tool_path, 'create-test',
        '-board', test_spec.board,
        '-image', test_spec.build,
        '-service-account-json', os.environ['SWARMING_CREDS'],
        ]
    if _is_dev():
        cmd += ['-dev']
    if test_spec.pool:
        # TODO(akeshet): Clean up this hack around pool name translation.
        autotest_pool_label = 'pool:%s' % test_spec.pool
        pool_dependency_value = swarming_lib.task_dependencies_from_labels(
            [autotest_pool_label])['label-pool']
        cmd += ['-pool', pool_dependency_value]

    if test_spec.model:
        cmd += ['-model', test_spec.model]
    if test_spec.quota_account:
        cmd += ['-qs-account', test_spec.quota_account]
    if test_spec.test.test_type.lower() == 'client':
        cmd += ['-client-test']

    tags = _compute_tags(test_spec.build, suite_id)
    dimensions = _compute_dimensions(
            test_spec.bot_id, test_spec.test.dependencies)
    keyvals_flat = _compute_job_keyvals_flat(test_spec.keyvals, suite_id)

    for tag in tags:
        cmd += ['-tag', tag]
    for keyval in keyvals_flat:
        cmd += ['-keyval', keyval]
    cmd += [test_spec.test.name]
    cmd += dimensions

    if dry_run:
        logging.info('Would have created task with command %s', cmd)
        return

    # TODO(akeshet): Avoid this late chromite import.
    cros_build_lib = autotest.chromite_load('cros_build_lib')
    result = cros_build_lib.RunCommand(cmd, capture_output=True)
    # TODO(akeshet): Use -json flag and json-parse output of the command instead
    # of regex matching to determine task_id.
    m = re.match('.*id=(.*)$', result.output)
    task_id = m.group(1)
    logging.info('Created task with id %s', task_id)
    return task_id


# TODO(akeshet): Eliminate the need for this, by either adding an explicit
# swarming_server argument to skylab tool, or having the tool respect the
# SWARMING_SERVER environment variable. See crbug.com/948774
def _is_dev():
    """Detect whether skylab tool should be invoked with -dev flag."""
    return 'chromium-swarm-dev' in os.environ['SWARMING_SERVER']

def _compute_tags(build, suite_id):
    tags = [
        'build:%s' % build,
    ]
    if suite_id is not None:
        tags += ['parent_task_id:%s' % suite_id]
    return tags


def _compute_dimensions(bot_id, dependencies):
    dimensions = []
    if bot_id:
        dimensions += ['id:%s' % bot_id]
    deps = _filter_unsupported_dependencies(dependencies)
    flattened_swarming_deps = sorted([
        '%s:%s' % (k, v) for
        k, v in swarming_lib.task_dependencies_from_labels(deps).items()
        ])
    dimensions += flattened_swarming_deps
    return dimensions


def _compute_job_keyvals_flat(keyvals, suite_id):
    # Job keyvals calculation.
    job_keyvals = keyvals.copy()
    if suite_id is not None:
        # TODO(akeshet): Avoid this late autotest constants import.
        constants = autotest.load('server.cros.dynamic_suite.constants')
        job_keyvals[constants.PARENT_JOB_ID] = suite_id
    keyvals_flat = sorted(
        ['%s:%s' % (k, v) for k, v in job_keyvals.items()])
    return keyvals_flat


def _filter_unsupported_dependencies(dependencies):
    """Filter out Skylab-unsupported test dependencies, with a warning."""
    deps = []
    for dep in dependencies:
        if dep in _NOT_SUPPORTED_DEPENDENCIES:
            logging.warning('Dependency %s is not supported in skylab', dep)
        else:
            deps.append(dep)
    return deps


@contextlib.contextmanager
def disable_logging(logging_level):
    """Context manager for disabling logging of a given logging level."""
    try:
        logging.disable(logging_level)
        yield
    finally:
        logging.disable(logging.NOTSET)


def _loop_and_wait_forever(suite_handler, dry_run):
    """Wait for child tasks to finish or break."""
    for iterations in itertools.count(0):
        # Log progress every 300 seconds.
        no_logging = bool(iterations * SUITE_WAIT_SLEEP_INTERVAL_SECONDS % 300)
        with disable_logging(logging.INFO if no_logging else logging.NOTSET):
            suite_handler.handle_results(suite_handler.suite_id)
            if suite_handler.is_finished_waiting():
                break

        for t in suite_handler.retried_tasks:
            _retry_test(suite_handler, t['task_id'], dry_run=dry_run)

        time.sleep(SUITE_WAIT_SLEEP_INTERVAL_SECONDS)


def _wait_for_results(suite_handler, dry_run=False):
    """Wait for child tasks to finish and return their results.

    @param suite_handler: a cros_suite.SuiteHandler object.
    """
    timeout_util = autotest.chromite_load('timeout_util')
    try:
        with timeout_util.Timeout(suite_handler.timeout_mins * 60 -
                                  suite_handler.passed_mins * 60):
            _loop_and_wait_forever(suite_handler, dry_run)
    except timeout_util.TimeoutError:
        logging.error('Timeout in waiting for child tasks.')
        return

    logging.info('Finished to wait for child tasks.')


def _retry_test(suite_handler, task_id, dry_run=False):
    """Retry test for a suite.

    We will execute the following actions for retrying a test:
        1. Schedule the test.
        2. Add the test with the new swarming task id to the suite's
           retry handler, but reduce its remaining retries by 1.
        3. Reduce the suite-level max retries by 1.
        4. Remove prevous failed test from retry handler since it's not
           actively monitored by the suite.

    @param suite_handler: a cros_suite.SuiteHandler object.
    @param task_id: The swarming task id for the retried test.
    @param dry_run: Whether to retry a dry run of the test.
    """
    last_retry_spec = suite_handler.get_test_by_task_id(task_id)
    logging.info('Retrying test %s, remaining %d retries.',
                 last_retry_spec.test_spec.test.name,
                 last_retry_spec.remaining_retries - 1)
    retried_task_id = _create_test_task(
            last_retry_spec.test_spec,
            suite_id=suite_handler.suite_id,
            is_provision=suite_handler.is_provision(),
            dry_run=dry_run)
    previous_retried_ids = last_retry_spec.previous_retried_ids + [task_id]
    suite_handler.add_test_by_task_id(
            retried_task_id,
            cros_suite.TestHandlerSpec(
                    test_spec=last_retry_spec.test_spec,
                    remaining_retries=last_retry_spec.remaining_retries - 1,
                    previous_retried_ids=previous_retried_ids))
    suite_handler.set_max_retries(suite_handler.max_retries - 1)
    suite_handler.remove_test_by_task_id(task_id)


def _convert_dict_to_string(input_dict):
    """Convert dictionary to a string.

    @param input_dict: A dictionary.
    """
    for k, v in input_dict.iteritems():
        if isinstance(v, dict):
            input_dict[k] = _convert_dict_to_string(v)
        else:
            input_dict[k] = str(v)

    return json.dumps(input_dict)
