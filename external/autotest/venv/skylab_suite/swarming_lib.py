# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Module for swarming execution."""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import json
import logging
import operator
import os
import urllib
import uuid

from lucifer import autotest
from skylab_suite import errors


DEFAULT_SERVICE_ACCOUNT = (
        '/creds/skylab_swarming_bot/skylab_bot_service_account.json')
SKYLAB_DRONE_POOL = 'ChromeOSSkylab'
SKYLAB_SUITE_POOL = 'ChromeOSSkylab-suite'

TASK_COMPLETED = 'COMPLETED'
TASK_COMPLETED_SUCCESS = 'COMPLETED (SUCCESS)'
TASK_COMPLETED_FAILURE = 'COMPLETED (FAILURE)'
TASK_EXPIRED = 'EXPIRED'
TASK_CANCELED = 'CANCELED'
TASK_TIMEDOUT = 'TIMED_OUT'
TASK_RUNNING = 'RUNNING'
TASK_PENDING = 'PENDING'
TASK_BOT_DIED = 'BOT_DIED'
TASK_NO_RESOURCE = 'NO_RESOURCE'
TASK_KILLED = 'KILLED'
TASK_FINISHED_STATUS = [TASK_COMPLETED,
                        TASK_EXPIRED,
                        TASK_CANCELED,
                        TASK_TIMEDOUT,
                        TASK_BOT_DIED,
                        TASK_NO_RESOURCE,
                        TASK_KILLED]
# The swarming task failure status to retry. TASK_CANCELED won't get
# retried since it's intentionally aborted.
TASK_STATUS_TO_RETRY = [TASK_EXPIRED, TASK_TIMEDOUT, TASK_BOT_DIED,
                        TASK_NO_RESOURCE]

DEFAULT_EXPIRATION_SECS = 10 * 60
DEFAULT_TIMEOUT_SECS = 60 * 60

# A mapping of priorities for skylab hwtest tasks. In swarming,
# lower number means high priorities. Priority lower than 48 will
# be special tasks. The upper bound for priority is 255.
# Use the same priorities mapping as chromite/lib/constants.py
SKYLAB_HWTEST_PRIORITIES_MAP = {
    'Weekly': 230,
    'CTS': 215,
    'Daily': 200,
    'PostBuild': 170,
    'Default': 140,
    'Build': 110,
    'PFQ': 80,
    'CQ': 50,
    'Super': 49,
}
SORTED_SKYLAB_HWTEST_PRIORITY = sorted(
        SKYLAB_HWTEST_PRIORITIES_MAP.items(),
        key=operator.itemgetter(1))

SWARMING_DUT_READY_STATUS = 'ready'

_STAINLESS_LOGS_BROWSER_URL_TEMPLATE = (
        "https://stainless.corp.google.com"
        "/browse/chromeos-autotest-results/swarming-%(request_id)s/"
)

def _get_client_path():
    return os.path.join(
            os.path.expanduser('~'),
            'chromiumos/chromite/third_party/swarming.client/swarming.py')


def task_dependencies_from_labels(labels):
    """Parse dependencies from autotest labels.

    @param labels: A list of label string.

    @return a dict [key: value] to represent dependencies.
    """
    translation_autotest = autotest.deps_load(
            'skylab_inventory.translation.autotest')
    translation_swarming = autotest.deps_load(
            'skylab_inventory.translation.swarming')
    dimensions = translation_swarming.labels_to_dimensions(
            translation_autotest.from_autotest_labels(labels))
    dependencies = {}
    for k, v in dimensions.iteritems():
      if isinstance(v, list):
        if len(v) > 1:
          raise ValueError(
              'Invalid dependencies: Multiple value %r for key %s' % (k, v))

        dependencies[k] = v[0]

    return dependencies


def make_logdog_annotation_url():
    """Return a unique LogDog annotation URL.

    If the appropriate LogDog server cannot be determined, return an
    empty string.
    """
    logdog_server = get_logdog_server()
    if not logdog_server:
        return ''
    return ('logdog://%s/chromeos/skylab/%s/+/annotations'
            % (logdog_server, uuid.uuid4().hex))


def get_swarming_server():
    """Return the swarming server for the current environment."""
    try:
        return os.environ['SWARMING_SERVER']
    except KeyError:
        raise errors.DroneEnvironmentError(
                'SWARMING_SERVER environment variable not set'
        )


def get_logdog_server():
    """Return the LogDog server for the current environment.

    If the appropriate server cannot be determined, return an empty
    string.
    """
    try:
        return os.environ['LOGDOG_SERVER']
    except KeyError:
        raise errors.DroneEnvironmentError(
                'LOGDOG_SERVER environment variable not set'
        )


def _namedtuple_to_dict(value):
    """Recursively converts a namedtuple to a dict.

    Args:
      value: a namedtuple object.

    Returns:
      A dict object with the same value.
    """
    out = dict(value._asdict())
    for k, v in out.iteritems():
      if hasattr(v, '_asdict'):
        out[k] = _namedtuple_to_dict(v)
      elif isinstance(v, (list, tuple)):
        l = []
        for elem in v:
          if hasattr(elem, '_asdict'):
            l.append(_namedtuple_to_dict(elem))
          else:
            l.append(elem)
        out[k] = l

    return out


def get_task_link(task_id):
    return '%s/user/task/%s' % (os.environ.get('SWARMING_SERVER'), task_id)


def get_stainless_logs_link(request_id):
    """Gets a link to the stainless logs for a given task ID."""
    return _STAINLESS_LOGS_BROWSER_URL_TEMPLATE % {
            'request_id': request_id,
    }

def get_task_final_state(task):
    """Get the final state of a swarming task.

    @param task: the json output of a swarming task fetched by API tasks.list.
    """
    state = task['state']
    if state == TASK_COMPLETED:
        state = (TASK_COMPLETED_FAILURE if task['failure'] else
                 TASK_COMPLETED_SUCCESS)

    return state


def get_task_dut_name(task_dimensions):
    """Get the DUT name of running this task.

    @param task_dimensions: a list of dict, e.g. [{'key': k, 'value': v}, ...]
    """
    for dimension in task_dimensions:
        if dimension['key'] == 'dut_name':
            return dimension['value'][0]

    return ''

def bot_available(bot):
    """Check whether a bot is available.

    @param bot: A dict describes a bot's dimensions, i.e. an element in return
        list of |query_bots_list|.

    @return True if a bot is available to run task, otherwise False.
    """
    return not (bot['is_dead'] or bot['quarantined'])


class Client(object):
    """Wrapper for interacting with swarming client."""

    # TODO(akeshet): Drop auth_json_path argument and use the same
    # SWARMING_CREDS envvar that is used to select creds for skylab tool.
    def __init__(self, auth_json_path=DEFAULT_SERVICE_ACCOUNT):
        self._auth_json_path = auth_json_path

    def query_task_by_tags(self, tags):
        """Get tasks for given tags.

        @param tags: A dict of tags for swarming tasks.

        @return a list, which contains all tasks queried by the given tags.
        """
        basic_swarming_cmd = self.get_basic_swarming_cmd('query')
        conditions = [('tags', '%s:%s' % (k, v)) for k, v in tags.iteritems()]
        swarming_cmd = basic_swarming_cmd + ['tasks/list?%s' %
                                            urllib.urlencode(conditions)]
        cros_build_lib = autotest.chromite_load('cros_build_lib')
        result = cros_build_lib.RunCommand(swarming_cmd, capture_output=True)
        json_output = json.loads(result.output)
        return json_output.get('items', [])

    def query_task_by_id(self, task_id):
        """Get task for given id.

        @param task_id: A string to indicate a swarming task id.

        @return a dict, which contains the task with the given task_id.
        """
        basic_swarming_cmd = self.get_basic_swarming_cmd('query')
        swarming_cmd = basic_swarming_cmd + ['task/%s/result' % task_id]
        cros_build_lib = autotest.chromite_load('cros_build_lib')
        result = cros_build_lib.RunCommand(swarming_cmd, capture_output=True)
        return json.loads(result.output)

    def abort_task(self, task_id):
        """Abort a swarming task by its id.

        @param task_id: A string swarming task id.
        """
        basic_swarming_cmd = self.get_basic_swarming_cmd('cancel')
        swarming_cmd = basic_swarming_cmd + ['--kill-running', task_id]
        cros_build_lib = autotest.chromite_load('cros_build_lib')
        try:
            cros_build_lib.RunCommand(swarming_cmd, log_output=True)
        except cros_build_lib.RunCommandError:
            logging.error('Task %s probably already gone, skip canceling it.',
                          task_id)

    def query_bots_list(self, dimensions):
        """Get bots list for given requirements.

        @param dimensions: A dict of dimensions for swarming bots.

        @return a list of bot dicts.
        """
        basic_swarming_cmd = self.get_basic_swarming_cmd('query')
        conditions = [('dimensions', '%s:%s' % (k, v))
                      for k, v in dimensions.iteritems()]
        swarming_cmd = basic_swarming_cmd + ['bots/list?%s' %
                                            urllib.urlencode(conditions)]
        cros_build_lib = autotest.chromite_load('cros_build_lib')
        result = cros_build_lib.RunCommand(swarming_cmd, capture_output=True)
        return json.loads(result.output).get('items', [])

    def get_child_tasks(self, parent_task_id):
        """Get the child tasks based on a parent swarming task id.

        @param parent_task_id: The parent swarming task id.

        @return a list of dicts, each dict refers to the whole stats of a task,
            keys include 'name', 'bot_dimensions', 'tags', 'bot_id', 'state',
            etc.
        """
        swarming_cmd = self.get_basic_swarming_cmd('query')
        swarming_cmd += ['tasks/list?tags=parent_task_id:%s' % parent_task_id]
        timeout_util = autotest.chromite_load('timeout_util')
        cros_build_lib = autotest.chromite_load('cros_build_lib')
        with timeout_util.Timeout(60):
            child_tasks = cros_build_lib.RunCommand(
                    swarming_cmd, capture_output=True)
            return json.loads(child_tasks.output)['items']

    def get_basic_swarming_cmd(self, command):
        cmd = [_get_client_path(), command, '--swarming', get_swarming_server()]
        if self._auth_json_path:
            cmd += ['--auth-service-account-json', self._auth_json_path]
        return cmd

