# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Functions for tracking & reporting a suite run."""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import contextlib
import logging
import logging.config
import mysql.connector

from lucifer import autotest
from skylab_suite import swarming_lib
from skylab_suite import tko_test_views

# Test status in _IGNORED_TEST_STATE won't be reported as test failure.
# Or test may be reported as failure as
# it's probably caused by the DUT is not well-provisioned.
# TODO: Stop ignoring TASK_NO_RESOURCE if we drop TEST_NA feature.
# Blocking issues:
#     - Not all DUT labels are in skylab yet (crbug.com/871978)
_IGNORED_TEST_STATE = [swarming_lib.TASK_NO_RESOURCE]


@contextlib.contextmanager
def _annotate_step(step_name):
    try:
        print('@@@SEED_STEP %s@@@' % step_name)
        print('@@@STEP_CURSOR %s@@@' % step_name)
        print('@@@STEP_STARTED@@@')
        yield
    finally:
        print('@@@STEP_CLOSED@@@')


def print_child_test_annotations(suite_handler):
    """Print LogDog annotations for child tests."""
    with _annotate_step('Scheduled Tests'):
        for task_id, hspec in suite_handler.task_to_test_maps.iteritems():
            anchor_test = hspec.test_spec.test.name
            if suite_handler.is_provision():
                anchor_test += '-' + hspec.test_spec.dut_name

            show_text = '[Test-logs]: %s' % anchor_test
            _print_task_result_link_annotation(task_id, show_text)


def log_suite_results(suite_name, suite_handler):
    """Log suite and its child tests' results & links.

    @param suite_job: A cros_suite.Suite object.

    @return the return code of suite decided by its child tests' results.
    """
    test_results = _parse_test_results(suite_handler)
    suite_state, return_code = _get_suite_state(test_results, suite_handler)
    if not test_results:
        logging.info(('Suite %s timed out in waiting, test results '
                      'are not parsed because they may still run.'), suite_name)
        return return_code

    logging.info('################# SUITE REPORTING #################')
    logging.info('Suite Job %s %s', suite_name, suite_state)
    _log_test_results(test_results)

    logging.info('Links to tests:')
    logging.info('Suite Job %s %s', suite_name,
                 swarming_lib.get_task_link(suite_handler.suite_id))
    _log_test_result_links(test_results)

    _log_buildbot_links(suite_handler, suite_name, test_results)
    return return_code


def _get_failed_test_views_from_tko(task_ids):
    """Get test views corresponding to failed tests from TKO.

    @param task_ids: list of Swarming request IDs.
    @return {task_id: [tko_test_views.Row()]}
    """
    conn = _new_tko_connection()
    if conn is None:
        return {}

    try:
        views = tko_test_views.get(conn, task_ids)
    except mysql.connector.Error:
        logging.exception('Failed to obtain failure reasons from TKO')
        return {}
    return {k: tko_test_views.filter_failed(v) for k, v in views.iteritems()}


def _new_tko_connection():
    global_config = autotest.load('client.common_lib.global_config')
    config = global_config.global_config
    try:
        host = config.get_config_value('AUTOTEST_WEB', 'global_db_host')
        user = config.get_config_value('AUTOTEST_WEB', 'global_db_user')
        password = config.get_config_value('AUTOTEST_WEB', 'global_db_password')
        database = config.get_config_value('AUTOTEST_WEB', 'database')
    except global_config.ConfigError:
        logging.exception('Could not load TKO connection configuration')
        return None
    try:
        if host.startswith('/'):
            return mysql.connector.connect(unix_socket=host, user=user,
                                           password=password, database=database)
        else:
            return mysql.connector.connect(host=host, user=user,
                                           password=password, database=database)
    except mysql.connector.Error:
        logging.exception('Failed to connect to TKO database')
        return None


def _print_task_result_link_annotation(task_id, text):
    """Print the link of task logs.

    Given text: 'dummy_Pass-chromeos4-row7-rack6-host19'
          task_id: '3ee300e77a576e10'

    The printed output will be:
      [Test-logs]: dummy_Pass-chromeos4-row7-rack6-host19

    Clicking it will direct you to
      https://chrome-swarming.appspot.com/task?id=3ee300e77a576e10

    @param anchor_test: a string to show on link.
    @param task_id: a string task_id to form the swarming url.
    """
    annotations = autotest.chromite_load('buildbot_annotations')
    print(annotations.StepLink('[Test-logs]: %s' % text,
                               swarming_lib.get_stainless_logs_link(task_id)))


def get_task_id_for_task_summaries(task_id):
    """Adjust the swarming task id to end in 0 for showing task summaries.

    Milo results are only generated for task summaries, that is, tasks whose
    ids end in 0. This function adjusts the last digit of the task_id. See
    https://goo.gl/LE4rwV for details.
    """
    return task_id[:-1] + '0'


def log_create_task(suite_name, task_id):
    """Print create task of suite."""
    annotations = autotest.chromite_load('buildbot_annotations')
    print(annotations.StepLink(
            'Link to the suite create task: %s' % suite_name,
            swarming_lib.get_task_link(
                    get_task_id_for_task_summaries(task_id))))


def log_wait_task(suite_name, task_id):
    """Print create task of suite."""
    annotations = autotest.chromite_load('buildbot_annotations')
    print(annotations.StepLink(
            'Link to the suite wait task: %s' % suite_name,
            swarming_lib.get_task_link(
                    get_task_id_for_task_summaries(task_id))))


def _log_buildbot_links(suite_handler, suite_name, test_results):
    logging.info('Links for buildbot:')
    if suite_handler.suite_id is not None:
        log_create_task(suite_name, suite_handler.suite_id)

    if suite_handler.task_id is not None:
        log_wait_task(suite_name, suite_handler.task_id)

    if (suite_handler.is_provision() and
        suite_handler.is_provision_successfully_finished()):
        # There could be some child tasks may still run after provision suite
        # finishes and claims that it succeeds. Skip logging them in buildbot.
        return

    failed_results = [t for t in test_results if _is_failed_result(t)]
    if suite_handler.is_provision():
        _log_buildbot_links_for_provision_tasks(failed_results)
    else:
        _log_buildbot_links_for_tasks(failed_results)


def _log_buildbot_links_for_provision_tasks(test_results):
    for result in test_results:
        _print_task_result_link_annotation(result['task_ids'][0],
                                           _get_show_test_name(result))


def _log_buildbot_links_for_tasks(test_results):
    task_ids = []
    for result in test_results:
        task_ids += result.get('task_ids', [])
    failed_test_views = _get_failed_test_views_from_tko(task_ids)

    for result in test_results:
        task_id = result['task_ids'][0]
        test_name = result['test_name']
        if task_id in failed_test_views:
            for v in failed_test_views[task_id]:
                _print_task_result_link_annotation(task_id,
                                                   _reason_from_test_view(v))
        else:
            _print_task_result_link_annotation(task_id, test_name)
        _log_buildbot_links_for_test_history(task_id, test_name)


def _log_buildbot_links_for_test_history(task_id, test_name):
    annotations = autotest.chromite_load('buildbot_annotations')
    reporting_utils = autotest.load('server.cros.dynamic_suite.reporting_utils')
    print(annotations.StepLink(
            '[Test-History]: %s' % test_name,
            reporting_utils.link_test_history(test_name)))


def _reason_from_test_view(test_view):
    reason = '%s: %s' % (test_view.name, test_view.status)
    if test_view.reason:
        reason = '%s: %s' % (reason, test_view.reason)
    return reason


def _log_test_results(test_results):
    """Log child results for a suite."""
    logging.info('Start outputing test results:')
    _log_test_results_with_logging(test_results)
    _print_test_result_links_in_logdog(test_results)


def _get_show_test_name(result):
    """Get the test_name to show.

    @param result: a test result dictionary, which is one item of the returned
        list of _parse_test_results.
    """
    if result['dut_name']:
        return result['test_name'] + '-' + result['dut_name']

    return result['test_name']


def _log_test_results_with_logging(test_results):
    name_column_width = max(len(result['test_name']) + len(result['dut_name'])
                            for result in test_results) + 3
    for result in test_results:
        padded_name = _get_show_test_name(result).ljust(name_column_width)
        logging.info('%s%s', padded_name, result['state'])
        if result['retry_count'] > 0:
            logging.info('%s  retry_count: %s', padded_name,
                         result['retry_count'])


def _print_test_result_links_in_logdog(test_results):
    with _annotate_step('Test Results'):
        for result in test_results:
            _print_single_test_result_link(result)


def _print_single_test_result_link(result):
    anchor_test = _get_show_test_name(result)
    for idx, task_id in enumerate(result['task_ids']):
        retry_suffix = ' (%dth retry)' % idx if idx > 0 else ''
        anchor_test += retry_suffix
        _print_task_result_link_annotation(
                task_id,
                '[%s]: %s' % (anchor_test, result['state']))


def _parse_test_results(suite_handler):
    """Parse test results after the suite job is finished.

    @param suite_handler: A cros_suite.SuiteHandler object.

    @return a list of test results.
    """
    test_results = []
    for child_task in suite_handler.get_active_child_tasks(
            suite_handler.suite_id):
        task_id = child_task['task_id']
        logging.info('Parsing task results of %s', task_id)
        test_handler_spec = suite_handler.get_test_by_task_id(task_id)
        name = test_handler_spec.test_spec.test.name
        dut_name = test_handler_spec.test_spec.dut_name
        retry_count = len(test_handler_spec.previous_retried_ids)
        all_task_ids = test_handler_spec.previous_retried_ids + [task_id]
        state = swarming_lib.get_task_final_state(child_task)
        test_results.append({
                'test_name': name,
                'state': state,
                'dut_name': dut_name,
                'retry_count': retry_count,
                'task_ids': all_task_ids})

    return test_results


def _get_final_suite_states():
    run_suite_common = autotest.load('site_utils.run_suite_common')
    return {
            swarming_lib.TASK_COMPLETED_FAILURE:
            (
                    swarming_lib.TASK_COMPLETED_FAILURE,
                    run_suite_common.RETURN_CODES.ERROR,
            ),
            # Task No_Resource means no available bots to accept the task.
            # Deputy should check whether it's infra failure.
            swarming_lib.TASK_NO_RESOURCE:
            (
                    swarming_lib.TASK_NO_RESOURCE,
                    run_suite_common.RETURN_CODES.INFRA_FAILURE,
            ),
            # Task expired means a task is not triggered, could be caused by
            #   1. No healthy DUTs/bots to run it.
            #   2. Expiration seconds are too low.
            #   3. Suite run is too slow to finish.
            # Deputy should check whether it's infra failure.
            swarming_lib.TASK_EXPIRED:
            (
                    swarming_lib.TASK_EXPIRED,
                    run_suite_common.RETURN_CODES.INFRA_FAILURE,
            ),
            # Task canceled means a task is canceled intentionally. Deputy
            # should check whether it's infra failure.
            swarming_lib.TASK_CANCELED:
            (
                    swarming_lib.TASK_CANCELED,
                    run_suite_common.RETURN_CODES.INFRA_FAILURE,
            ),
            swarming_lib.TASK_TIMEDOUT:
            (
                    swarming_lib.TASK_TIMEDOUT,
                    run_suite_common.RETURN_CODES.SUITE_TIMEOUT,
            ),
            # Task pending means a task is still waiting for picking up, but
            # the suite already hits deadline. So report it as suite TIMEOUT.
            # It could also be an INFRA_FAILURE due to DUTs/bots shortage.
            swarming_lib.TASK_PENDING:
            (
                    swarming_lib.TASK_TIMEDOUT,
                    run_suite_common.RETURN_CODES.SUITE_TIMEOUT,
            ),
    }


def _get_suite_state(child_test_results, suite_handler):
    """Get a suite's final state and return code."""
    run_suite_common = autotest.load('site_utils.run_suite_common')
    if (suite_handler.is_provision() and
        suite_handler.is_provision_successfully_finished()):
        logging.info('Provisioned duts:')
        for dut in list(suite_handler.successfully_provisioned_duts):
            logging.info(dut)
        return (swarming_lib.TASK_COMPLETED_SUCCESS,
                run_suite_common.RETURN_CODES.OK)

    _final_suite_states = _get_final_suite_states()
    for result in child_test_results:
        if ((result['state'] not in _IGNORED_TEST_STATE) and
            result['state'] in _final_suite_states):
            return _final_suite_states[result['state']]

    return (swarming_lib.TASK_COMPLETED_SUCCESS,
            run_suite_common.RETURN_CODES.OK)


def _log_test_result_links(child_test_results):
    """Output child results for a suite."""
    for result in child_test_results:
        for idx, task_id in enumerate(result['task_ids']):
            retry_suffix = ' (%dth retry)' % idx if idx > 0 else ''
            logging.info('%s  %s', result['test_name'] + retry_suffix,
                         swarming_lib.get_stainless_logs_link(task_id))


def setup_logging():
    """Setup the logging for skylab suite."""
    logging.config.dictConfig({
        'version': 1,
        'formatters': {
            'default': {'format': '%(asctime)s %(levelname)-5s| %(message)s'},
        },
        'handlers': {
            'screen': {
                'class': 'logging.StreamHandler',
                'formatter': 'default',
            },
        },
        'root': {
            'level': 'INFO',
            'handlers': ['screen'],
        },
        'disable_existing_loggers': False,
    })


def _is_failed_result(result):
    return result['state'] not in [
            swarming_lib.TASK_COMPLETED_SUCCESS,
            swarming_lib.TASK_RUNNING,
    ]

