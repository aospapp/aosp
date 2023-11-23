# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Definition of a CrOS suite in skylab.

This file is a simplicication of dynamic_suite.suite without any useless
features for skylab suite.

Suite class in this file mainly has 2 features:
    1. Integrate parameters from control file & passed in arguments.
    2. Find proper child tests for a given suite.

Use case:
    See _run_suite() in skylab_suite.run_suite_skylab.
"""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import collections
import logging
import os

from lucifer import autotest
from skylab_suite import errors
from skylab_suite import swarming_lib


SuiteSpec = collections.namedtuple(
        'SuiteSpec',
        [
                'builds',
                'suite_name',
                'suite_file_name',
                'test_source_build',
                'suite_args',
                'priority',
                'board',
                'model',
                'pool',
                'job_keyvals',
                'minimum_duts',
                'timeout_mins',
                'quota_account',
        ])

SuiteHandlerSpec = collections.namedtuple(
        'SuiteHandlerSpec',
        [
                'suite_name',
                'wait',
                'suite_id',
                'timeout_mins',
                'passed_mins',
                'test_retry',
                'max_retries',
                'provision_num_required',
        ])

TestHandlerSpec = collections.namedtuple(
        'TestHandlerSpec',
        [
                'test_spec',
                'remaining_retries',
                'previous_retried_ids',
        ])

TestSpec = collections.namedtuple(
        'TestSpec',
        [
                'test',
                'priority',
                'board',
                'model',
                'pool',
                'build',
                'keyvals',
                # TODO(akeshet): Determine why this is necessary
                # (can't this just be specified as its own dimension?) and
                # delete it if it isn't necessary.
                'bot_id',
                'dut_name',
                'expiration_secs',
                'grace_period_secs',
                'execution_timeout_secs',
                'io_timeout_secs',
                'quota_account',
        ])


class SuiteHandler(object):
    """The class for handling a CrOS suite run.

    Its responsibility includes handling retries for child tests.
    """

    def __init__(self, specs, client):
        self._suite_name = specs.suite_name
        self._wait = specs.wait
        self._timeout_mins = specs.timeout_mins
        self._provision_num_required = specs.provision_num_required
        self._test_retry = specs.test_retry
        self._max_retries = specs.max_retries
        self.passed_mins = specs.passed_mins

        # The swarming task id of the suite that this suite_handler is handling.
        self._suite_id = specs.suite_id
        # The swarming task id of current run_suite_skylab process. It could be
        # different from self._suite_id if a suite_id is passed in.
        self._task_id = os.environ.get('SWARMING_TASK_ID')
        self._task_to_test_maps = {}
        self.successfully_provisioned_duts = set()
        self._client = client

        # It only maintains the swarming task of the final run of each
        # child task, i.e. it doesn't include failed swarming tasks of
        # each child task which will get retried later.
        self._active_child_tasks = []

    def should_wait(self):
        """Return whether to wait for a suite's result."""
        return self._wait

    def is_provision(self):
        """Return whether the suite handler is for provision suite."""
        return self._suite_name == 'provision'

    def set_suite_id(self, suite_id):
        """Set swarming task id for a suite.

        @param suite_id: The swarming task id of this suite.
        """
        self._suite_id = suite_id

    def add_test_by_task_id(self, task_id, test_handler_spec):
        """Record a child test and its swarming task id.

        @param task_id: the swarming task id of a child test.
        @param test_handler_spec: a TestHandlerSpec object.
        """
        self._task_to_test_maps[task_id] = test_handler_spec

    def get_test_by_task_id(self, task_id):
        """Get a child test by its swarming task id.

        @param task_id: the swarming task id of a child test.
        """
        return self._task_to_test_maps[task_id]

    def remove_test_by_task_id(self, task_id):
        """Delete a child test by its swarming task id.

        @param task_id: the swarming task id of a child test.
        """
        self._task_to_test_maps.pop(task_id, None)

    def set_max_retries(self, max_retries):
        """Set the max retries for a suite.

        @param max_retries: The current maximum retries to set.
        """
        self._max_retries = max_retries

    @property
    def task_to_test_maps(self):
        """Get the task_to_test_maps of a suite."""
        return self._task_to_test_maps

    @property
    def timeout_mins(self):
        """Get the timeout minutes of a suite."""
        return self._timeout_mins

    @property
    def suite_id(self):
        """Get the swarming task id of a suite."""
        return self._suite_id

    @property
    def task_id(self):
        """Get swarming task id of current process."""
        return self._task_id

    @property
    def max_retries(self):
        """Get the max num of retries of a suite."""
        return self._max_retries

    def get_active_child_tasks(self, suite_id):
        """Get the child tasks which is actively monitored by a suite.

        The active child tasks list includes tasks which are currently running
        or finished without following retries. E.g.
        Suite task X:
            child task 1: x1 (first try x1_1, second try x1_2)
            child task 2: x2 (first try: x2_1)
        The final active child task list will include task x1_2 and x2_1, won't
        include x1_1 since it's a task which is finished but get retried later.
        """
        all_tasks = self._client.get_child_tasks(suite_id)
        return [t for t in all_tasks if t['task_id'] in self._task_to_test_maps]

    def handle_results(self, suite_id):
        """Handle child tasks' results."""
        self._active_child_tasks = self.get_active_child_tasks(suite_id)
        self.retried_tasks = [t for t in self._active_child_tasks
                              if self._should_retry(t)]
        logging.info('Found %d tests to be retried.', len(self.retried_tasks))

    def _check_all_tasks_finished(self):
        """Check whether all tasks are finished, including retried tasks."""
        finished_tasks = [t for t in self._active_child_tasks if
                          t['state'] in swarming_lib.TASK_FINISHED_STATUS]
        logging.info('%d/%d child tasks finished, %d got retried.',
                     len(finished_tasks), len(self._active_child_tasks),
                     len(self.retried_tasks))
        return (len(finished_tasks) == len(self._active_child_tasks)
                and not self.retried_tasks)

    def _set_successful_provisioned_duts(self):
        """Set successfully provisioned duts."""
        for t in self._active_child_tasks:
            if (swarming_lib.get_task_final_state(t) ==
                swarming_lib.TASK_COMPLETED_SUCCESS):
                dut_name = self.get_test_by_task_id(
                        t['task_id']).test_spec.dut_name
                if dut_name:
                    self.successfully_provisioned_duts.add(dut_name)

    def is_provision_successfully_finished(self):
        """Check whether provision succeeds."""
        logging.info('Found %d successfully provisioned duts, '
                     'the minimum requirement is %d',
                     len(self.successfully_provisioned_duts),
                     self._provision_num_required)
        return (len(self.successfully_provisioned_duts) >=
                self._provision_num_required)

    def is_finished_waiting(self):
        """Check whether the suite should finish its waiting."""
        if self.is_provision():
            self._set_successful_provisioned_duts()
            return (self.is_provision_successfully_finished() or
                    self._check_all_tasks_finished())

        return self._check_all_tasks_finished()

    def _should_retry(self, test_result):
        """Check whether a test should be retried.

        We will retry a test if:
            1. The test-level retry is enabled for this suite.
            2. The test fails.
            3. The test is currently monitored by the suite, i.e.
               it's not a previous retried test.
            4. The test has remaining retries based on JOB_RETRIES in
               its control file.
            5. The suite-level max retries isn't hit.

        @param test_result: A json test result from swarming API.

        @return True if we should retry the test.
        """
        task_id = test_result['task_id']
        state = test_result['state']
        is_failure = test_result['failure']
        return (self._test_retry and
                ((state == swarming_lib.TASK_COMPLETED and is_failure)
                 or (state in swarming_lib.TASK_STATUS_TO_RETRY))
                and (task_id in self._task_to_test_maps)
                and (self._task_to_test_maps[task_id].remaining_retries > 0)
                and (self._max_retries > 0))


class Suite(object):
    """The class for a CrOS suite."""
    EXPIRATION_SECS = swarming_lib.DEFAULT_EXPIRATION_SECS

    def __init__(self, spec, client):
        """Initialize a suite.

        @param spec: A SuiteSpec object.
        @param client: A swarming_lib.Client instance.
        """
        self._ds = None

        self.control_file = ''
        self.test_specs = []
        self.builds = spec.builds
        self.test_source_build = spec.test_source_build
        self.suite_name = spec.suite_name
        self.suite_file_name = spec.suite_file_name
        self.priority = spec.priority
        self.board = spec.board
        self.model = spec.model
        self.pool = spec.pool
        self.job_keyvals = spec.job_keyvals
        self.minimum_duts = spec.minimum_duts
        self.timeout_mins = spec.timeout_mins
        self.quota_account = spec.quota_account
        self._client = client

    @property
    def ds(self):
        """Getter for private |self._ds| property.

        This ensures that once self.ds is called, there's a devserver ready
        for it.
        """
        if self._ds is None:
            raise errors.InValidPropertyError(
                'Property self.ds is None. Please call stage_suite_artifacts() '
                'before calling it.')

        return self._ds

    def _get_cros_build(self):
        provision = autotest.load('server.cros.provision')
        return self.builds.get(provision.CROS_VERSION_PREFIX,
                               self.builds.values()[0])

    def _create_suite_keyvals(self):
        constants = autotest.load('server.cros.dynamic_suite.constants')
        provision = autotest.load('server.cros.provision')
        cros_build = self._get_cros_build()
        keyvals = {
                constants.JOB_BUILD_KEY: cros_build,
                constants.JOB_SUITE_KEY: self.suite_name,
                constants.JOB_BUILDS_KEY: self.builds
        }
        if (cros_build != self.test_source_build or
            len(self.builds) > 1):
            keyvals[constants.JOB_TEST_SOURCE_BUILD_KEY] = (
                    self.test_source_build)
            for prefix, build in self.builds.iteritems():
                if prefix == provision.FW_RW_VERSION_PREFIX:
                    keyvals[constants.FWRW_BUILD]= build
                elif prefix == provision.FW_RO_VERSION_PREFIX:
                    keyvals[constants.FWRO_BUILD] = build

        for key in self.job_keyvals:
            if key in constants.INHERITED_KEYVALS:
                keyvals[key] = self.job_keyvals[key]

        return keyvals

    def prepare(self):
        """Prepare a suite job for execution."""
        self._stage_suite_artifacts()
        self._parse_suite_args()
        keyvals = self._create_suite_keyvals()
        available_bots = self._get_available_bots()
        if len(available_bots) < self.minimum_duts:
            raise errors.NoAvailableDUTsError(
                    self.board, self.pool, len(available_bots),
                    self.minimum_duts)

        tests = self._find_tests(available_bots_num=len(available_bots))
        self.test_specs = self._get_test_specs(tests, available_bots, keyvals)

    def _create_test_spec(self, test, keyvals, bot_id='', dut_name=''):
        return TestSpec(
                test=test,
                priority=self.priority,
                board=self.board,
                model=self.model,
                pool=self.pool,
                build=self.test_source_build,
                bot_id=bot_id,
                dut_name=dut_name,
                keyvals=keyvals,
                expiration_secs=self.timeout_mins * 60,
                grace_period_secs=swarming_lib.DEFAULT_TIMEOUT_SECS,
                execution_timeout_secs=self.timeout_mins * 60,
                io_timeout_secs=swarming_lib.DEFAULT_TIMEOUT_SECS,
                quota_account=self.quota_account,
        )

    def _get_test_specs(self, tests, available_bots, keyvals):
        return [self._create_test_spec(test, keyvals) for test in tests]

    def _stage_suite_artifacts(self):
        """Stage suite control files and suite-to-tests mapping file.

        @param build: The build to stage artifacts.
        """
        suite_common = autotest.load('server.cros.dynamic_suite.suite_common')
        ds, _ = suite_common.stage_build_artifacts(self.test_source_build)
        self._ds = ds

    def _parse_suite_args(self):
        """Get the suite args.

        The suite args includes:
            a. suite args in suite control file.
            b. passed-in suite args by user.
        """
        suite_common = autotest.load('server.cros.dynamic_suite.suite_common')
        self.control_file = suite_common.get_control_file_by_build(
                self.test_source_build, self.ds, self.suite_file_name)

    def _find_tests(self, available_bots_num=0):
        """Fetch the child tests."""
        control_file_getter = autotest.load(
                'server.cros.dynamic_suite.control_file_getter')
        suite_common = autotest.load('server.cros.dynamic_suite.suite_common')
        cf_getter = control_file_getter.DevServerGetter(
                self.test_source_build, self.ds)
        tests = suite_common.retrieve_for_suite(
                cf_getter, self.suite_name)
        return suite_common.filter_tests(
                tests, suite_common.name_in_tag_predicate(self.suite_name))

    def _get_available_bots(self):
        """Get available bots for suites."""
        dimensions = {'pool': swarming_lib.SKYLAB_DRONE_POOL,
                      'label-board': self.board}
        swarming_pool_deps = swarming_lib.task_dependencies_from_labels(
            ['pool:%s' % self.pool])
        dimensions.update(swarming_pool_deps)
        bots = self._client.query_bots_list(dimensions)
        return [bot for bot in bots if swarming_lib.bot_available(bot)]


class ProvisionSuite(Suite):
    """The class for a CrOS provision suite."""
    EXPIRATION_SECS = swarming_lib.DEFAULT_EXPIRATION_SECS

    def __init__(self, spec, client):
        super(ProvisionSuite, self).__init__(spec, client)
        self._num_required = spec.suite_args['num_required']

    def _find_tests(self, available_bots_num=0):
        """Fetch the child tests for provision suite."""
        control_file_getter = autotest.load(
                'server.cros.dynamic_suite.control_file_getter')
        suite_common = autotest.load('server.cros.dynamic_suite.suite_common')
        cf_getter = control_file_getter.DevServerGetter(
                self.test_source_build, self.ds)
        dummy_test = suite_common.retrieve_control_data_for_test(
                cf_getter, 'dummy_Pass')
        logging.info('Get %d available DUTs for provision.', available_bots_num)
        if available_bots_num < self._num_required:
            logging.warning('Not enough available DUTs for provision.')
            raise errors.NoAvailableDUTsError(
                    self.board, self.pool, available_bots_num,
                    self._num_required)

        return [dummy_test] * max(self._num_required, available_bots_num)

    def _get_test_specs(self, tests, available_bots, keyvals):
        test_specs = []
        for idx, test in enumerate(tests):
            if idx < len(available_bots):
                bot = available_bots[idx]
                test_specs.append(self._create_test_spec(
                        test, keyvals, bot_id=bot['bot_id'],
                        dut_name=swarming_lib.get_task_dut_name(
                                bot['dimensions'])))
            else:
                test_specs.append(self._create_test_spec(test, keyvals))

        return test_specs
