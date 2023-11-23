# Copyright 2017, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Atest Tradefed test runner class.
"""

from __future__ import print_function
import json
import logging
import os
import re
import socket
import subprocess

from functools import partial

# pylint: disable=import-error
import atest_utils
import constants
from event_handler import EventHandler
from test_finders import test_info
from test_runners import test_runner_base

POLL_FREQ_SECS = 10
SOCKET_HOST = '127.0.0.1'
SOCKET_QUEUE_MAX = 1
SOCKET_BUFFER = 4096
# Socket Events of form FIRST_EVENT {JSON_DATA}\nSECOND_EVENT {JSON_DATA}
# EVENT_RE has groups for the name and the data. "." does not match \n.
EVENT_RE = re.compile(r'^(?P<event_name>[A-Z_]+) (?P<json_data>{.*})(?:\n|$)')

EXEC_DEPENDENCIES = ('adb', 'aapt')

TRADEFED_EXIT_MSG = ('TradeFed subprocess exited early with exit code=%s.')


class TradeFedExitError(Exception):
    """Raised when TradeFed exists before test run has finished."""


class AtestTradefedTestRunner(test_runner_base.TestRunnerBase):
    """TradeFed Test Runner class."""
    NAME = 'AtestTradefedTestRunner'
    EXECUTABLE = 'atest_tradefed.sh'
    _TF_TEMPLATE = 'template/local_min'
    _RUN_CMD = ('{exe} {template} --template:map '
                'test=atest {args}')
    _BUILD_REQ = {'tradefed-core'}

    def __init__(self, results_dir, module_info=None, **kwargs):
        """Init stuff for base class."""
        super(AtestTradefedTestRunner, self).__init__(results_dir, **kwargs)
        self.module_info = module_info
        self.run_cmd_dict = {'exe': self.EXECUTABLE,
                             'template': self._TF_TEMPLATE,
                             'args': ''}
        self.is_verbose = logging.getLogger().isEnabledFor(logging.DEBUG)
        self.root_dir = os.environ.get(constants.ANDROID_BUILD_TOP)

    def _try_set_gts_authentication_key(self):
        """Set GTS authentication key if it is available or exists.

        Strategy:
            Get APE_API_KEY from os.environ:
                - If APE_API_KEY is already set by user -> do nothing.
            Get the APE_API_KEY from constants:
                - If the key file exists -> set to env var.
            If APE_API_KEY isn't set and the key file doesn't exist:
                - Warn user some GTS tests may fail without authentication.
        """
        if os.environ.get('APE_API_KEY'):
            logging.debug('APE_API_KEY is set by developer.')
            return
        ape_api_key = constants.GTS_GOOGLE_SERVICE_ACCOUNT
        key_path = os.path.join(self.root_dir, ape_api_key)
        if ape_api_key and os.path.exists(key_path):
            logging.debug('Set APE_API_KEY: %s', ape_api_key)
            os.environ['APE_API_KEY'] = ape_api_key
        else:
            logging.debug('APE_API_KEY not set, some GTS tests may fail'
                          'without authentication.')

    def run_tests(self, test_infos, extra_args, reporter):
        """Run the list of test_infos. See base class for more.

        Args:
            test_infos: A list of TestInfos.
            extra_args: Dict of extra args to add to test run.
            reporter: An instance of result_report.ResultReporter.

        Returns:
            0 if tests succeed, non-zero otherwise.
        """
        # Set google service key if it's available or found before running tests.
        self._try_set_gts_authentication_key()
        if os.getenv(test_runner_base.OLD_OUTPUT_ENV_VAR):
            return self.run_tests_raw(test_infos, extra_args, reporter)
        return self.run_tests_pretty(test_infos, extra_args, reporter)

    def run_tests_raw(self, test_infos, extra_args, reporter):
        """Run the list of test_infos. See base class for more.

        Args:
            test_infos: A list of TestInfos.
            extra_args: Dict of extra args to add to test run.
            reporter: An instance of result_report.ResultReporter.

        Returns:
            0 if tests succeed, non-zero otherwise.
        """
        iterations = self._generate_iterations(extra_args)
        reporter.register_unsupported_runner(self.NAME)

        ret_code = constants.EXIT_CODE_SUCCESS
        for _ in range(iterations):
            run_cmds = self.generate_run_commands(test_infos, extra_args)
            subproc = self.run(run_cmds[0], output_to_stdout=True)
            ret_code |= self.wait_for_subprocess(subproc)
        return ret_code

    def run_tests_pretty(self, test_infos, extra_args, reporter):
        """Run the list of test_infos. See base class for more.

        Args:
            test_infos: A list of TestInfos.
            extra_args: Dict of extra args to add to test run.
            reporter: An instance of result_report.ResultReporter.

        Returns:
            0 if tests succeed, non-zero otherwise.
        """
        iterations = self._generate_iterations(extra_args)
        ret_code = constants.EXIT_CODE_SUCCESS
        for _ in range(iterations):
            server = self._start_socket_server()
            run_cmds = self.generate_run_commands(test_infos, extra_args,
                                                  server.getsockname()[1])
            subproc = self.run(run_cmds[0], output_to_stdout=self.is_verbose)
            event_handler = EventHandler(reporter, self.NAME)
            self.handle_subprocess(subproc, partial(self._start_monitor,
                                                    server,
                                                    subproc,
                                                    event_handler))
            server.close()
            ret_code |= self.wait_for_subprocess(subproc)
        return ret_code

    def _start_monitor(self, server, tf_subproc, event_handler):
        """Polling and process event.

        Args:
            server: Socket server object.
            tf_subproc: The tradefed subprocess to poll.
            event_handler: EventHandler object.
        """
        conn, addr = self._exec_with_tf_polling(server.accept, tf_subproc)
        logging.debug('Accepted connection from %s', addr)
        self._process_connection(conn, event_handler)

    def _start_socket_server(self):
        """Start a TCP server."""
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # Port 0 lets the OS pick an open port between 1024 and 65535.
        server.bind((SOCKET_HOST, 0))
        server.listen(SOCKET_QUEUE_MAX)
        server.settimeout(POLL_FREQ_SECS)
        logging.debug('Socket server started on port %s',
                      server.getsockname()[1])
        return server

    def _exec_with_tf_polling(self, socket_func, tf_subproc):
        """Check for TF subproc exit during blocking socket func.

        Args:
            socket_func: A blocking socket function, e.g. recv(), accept().
            tf_subproc: The tradefed subprocess to poll.
        """
        while True:
            try:
                return socket_func()
            except socket.timeout:
                logging.debug('Polling TF subproc for early exit.')
                if tf_subproc.poll() is not None:
                    logging.debug('TF subproc exited early')
                    raise TradeFedExitError(TRADEFED_EXIT_MSG
                                            % tf_subproc.returncode)

    def _process_connection(self, conn, event_handler):
        """Process a socket connection from TradeFed.

        Expect data of form EVENT_NAME {JSON_DATA}.  Multiple events will be
        \n deliminated.  Need to buffer data in case data exceeds socket
        buffer.

        Args:
            conn: A socket connection.
            event_handler: EventHandler object.
        """
        conn.settimeout(None)
        buf = ''
        while True:
            logging.debug('Waiting to receive data')
            data = conn.recv(SOCKET_BUFFER)
            logging.debug('received: %s', data)
            if data:
                buf += data
                while True:
                    match = EVENT_RE.match(buf)
                    if match:
                        try:
                            event_data = json.loads(match.group('json_data'))
                        except ValueError:
                            # Json incomplete, wait for more data.
                            break
                        event_name = match.group('event_name')
                        buf = buf[match.end():]
                        event_handler.process_event(event_name, event_data)
                        continue
                    break
            else:
                # client sent empty string, so no more data.
                conn.close()
                break

    def host_env_check(self):
        """Check that host env has everything we need.

        We actually can assume the host env is fine because we have the same
        requirements that atest has. Update this to check for android env vars
        if that changes.
        """
        pass

    @staticmethod
    def _is_missing_exec(executable):
        """Check if system build executable is available.

        Args:
            executable: Executable we are checking for.
        Returns:
            True if executable is missing, False otherwise.
        """
        try:
            output = subprocess.check_output(['which', executable])
        except subprocess.CalledProcessError:
            return True
        # TODO: Check if there is a clever way to determine if system adb is
        # good enough.
        root_dir = os.environ.get(constants.ANDROID_BUILD_TOP)
        return os.path.commonprefix([output, root_dir]) != root_dir

    def get_test_runner_build_reqs(self):
        """Return the build requirements.

        Returns:
            Set of build targets.
        """
        build_req = self._BUILD_REQ
        # Use different base build requirements if google-tf is around.
        if self.module_info.is_module(constants.GTF_MODULE):
            build_req = {constants.GTF_TARGET}
        # Always add ATest's own TF target.
        build_req.add(constants.ATEST_TF_MODULE)
        # Add adb if we can't find it.
        for executable in EXEC_DEPENDENCIES:
            if self._is_missing_exec(executable):
                build_req.add(executable)
        return build_req

    @staticmethod
    def _parse_extra_args(extra_args):
        """Convert the extra args into something tf can understand.

        Args:
            extra_args: Dict of args

        Returns:
            Tuple of args to append and args not supported.
        """
        args_to_append = []
        args_not_supported = []
        for arg in extra_args:
            if constants.WAIT_FOR_DEBUGGER == arg:
                args_to_append.append('--wait-for-debugger')
                continue
            if constants.DISABLE_INSTALL == arg:
                args_to_append.append('--disable-target-preparers')
                continue
            if constants.SERIAL == arg:
                args_to_append.append('--serial')
                args_to_append.append(extra_args[arg])
                continue
            if constants.DISABLE_TEARDOWN == arg:
                args_to_append.append('--disable-teardown')
                continue
            if constants.HOST == arg:
                args_to_append.append('-n')
                args_to_append.append('--prioritize-host-config')
                args_to_append.append('--skip-host-arch-check')
                continue
            if constants.CUSTOM_ARGS == arg:
                # We might need to sanitize it prior to appending but for now
                # let's just treat it like a simple arg to pass on through.
                args_to_append.extend(extra_args[arg])
                continue
            if constants.ALL_ABI == arg:
                args_to_append.append('--all-abi')
                continue
            if constants.DRY_RUN == arg:
                continue
            if constants.INSTANT == arg:
                args_to_append.append('--enable-parameterized-modules')
                args_to_append.append('--module-parameter')
                args_to_append.append('instant_app')
                continue
            args_not_supported.append(arg)
        return args_to_append, args_not_supported

    def _generate_metrics_folder(self, extra_args):
        """Generate metrics folder."""
        metrics_folder = ''
        if extra_args.get(constants.PRE_PATCH_ITERATIONS):
            metrics_folder = os.path.join(self.results_dir, 'baseline-metrics')
        elif extra_args.get(constants.POST_PATCH_ITERATIONS):
            metrics_folder = os.path.join(self.results_dir, 'new-metrics')
        return metrics_folder

    def _generate_iterations(self, extra_args):
        """Generate iterations."""
        iterations = 1
        if extra_args.get(constants.PRE_PATCH_ITERATIONS):
            iterations = extra_args.pop(constants.PRE_PATCH_ITERATIONS)
        elif extra_args.get(constants.POST_PATCH_ITERATIONS):
            iterations = extra_args.pop(constants.POST_PATCH_ITERATIONS)
        return iterations

    def generate_run_commands(self, test_infos, extra_args, port=None):
        """Generate a single run command from TestInfos.

        Args:
            test_infos: A set of TestInfo instances.
            extra_args: A Dict of extra args to append.
            port: Optional. An int of the port number to send events to. If
                  None, then subprocess reporter in TF won't try to connect.

        Returns:
            A list that contains the string of atest tradefed run command.
            Only one command is returned.
        """
        args = self._create_test_args(test_infos)
        metrics_folder = self._generate_metrics_folder(extra_args)

        # Create a copy of args as more args could be added to the list.
        test_args = list(args)
        if port:
            test_args.extend(['--subprocess-report-port', str(port)])
        if metrics_folder:
            test_args.extend(['--metrics-folder', metrics_folder])
            logging.info('Saved metrics in: %s', metrics_folder)
        log_level = 'VERBOSE' if self.is_verbose else 'WARN'
        test_args.extend(['--log-level', log_level])

        args_to_add, args_not_supported = self._parse_extra_args(extra_args)

        # TODO(b/122889707) Remove this after finding the root cause.
        env_serial = os.environ.get(constants.ANDROID_SERIAL)
        # Use the env variable ANDROID_SERIAL if it's set by user but only when
        # the target tests are not deviceless tests.
        if env_serial and '--serial' not in args_to_add and '-n' not in args_to_add:
            args_to_add.append("--serial")
            args_to_add.append(env_serial)

        test_args.extend(args_to_add)
        if args_not_supported:
            logging.info('%s does not support the following args %s',
                         self.EXECUTABLE, args_not_supported)

        test_args.extend(atest_utils.get_result_server_args())
        self.run_cmd_dict['args'] = ' '.join(test_args)
        return [self._RUN_CMD.format(**self.run_cmd_dict)]

    def _flatten_test_infos(self, test_infos):
        """Sort and group test_infos by module_name and sort and group filters
        by class name.

            Example of three test_infos in a set:
                Module1, {(classA, {})}
                Module1, {(classB, {Method1})}
                Module1, {(classB, {Method2}}
            Becomes a set with one element:
                Module1, {(ClassA, {}), (ClassB, {Method1, Method2})}
            Where:
                  Each line is a test_info namedtuple
                  {} = Frozenset
                  () = TestFilter namedtuple

        Args:
            test_infos: A set of TestInfo namedtuples.

        Returns:
            A set of TestInfos flattened.
        """
        results = set()
        key = lambda x: x.test_name
        for module, group in atest_utils.sort_and_group(test_infos, key):
            # module is a string, group is a generator of grouped TestInfos.
            # Module Test, so flatten test_infos:
            no_filters = False
            filters = set()
            test_runner = None
            build_targets = set()
            data = {}
            module_args = []
            for test_info_i in group:
                data.update(test_info_i.data)
                # Extend data with constants.TI_MODULE_ARG instead of overwriting.
                module_args.extend(test_info_i.data.get(constants.TI_MODULE_ARG, []))
                test_runner = test_info_i.test_runner
                build_targets |= test_info_i.build_targets
                test_filters = test_info_i.data.get(constants.TI_FILTER)
                if not test_filters or no_filters:
                    # test_info wants whole module run, so hardcode no filters.
                    no_filters = True
                    filters = set()
                    continue
                filters |= test_filters
            if module_args:
                data[constants.TI_MODULE_ARG] = module_args
            data[constants.TI_FILTER] = self._flatten_test_filters(filters)
            results.add(
                test_info.TestInfo(test_name=module,
                                   test_runner=test_runner,
                                   build_targets=build_targets,
                                   data=data))
        return results

    @staticmethod
    def _flatten_test_filters(filters):
        """Sort and group test_filters by class_name.

            Example of three test_filters in a frozenset:
                classA, {}
                classB, {Method1}
                classB, {Method2}
            Becomes a frozenset with these elements:
                classA, {}
                classB, {Method1, Method2}
            Where:
                Each line is a TestFilter namedtuple
                {} = Frozenset

        Args:
            filters: A frozenset of test_filters.

        Returns:
            A frozenset of test_filters flattened.
        """
        results = set()
        key = lambda x: x.class_name
        for class_name, group in atest_utils.sort_and_group(filters, key):
            # class_name is a string, group is a generator of TestFilters
            assert class_name is not None
            methods = set()
            for test_filter in group:
                if not test_filter.methods:
                    # Whole class should be run
                    methods = set()
                    break
                methods |= test_filter.methods
            results.add(test_info.TestFilter(class_name, frozenset(methods)))
        return frozenset(results)

    def _create_test_args(self, test_infos):
        """Compile TF command line args based on the given test infos.

        Args:
            test_infos: A set of TestInfo instances.

        Returns: A list of TF arguments to run the tests.
        """
        args = []
        if not test_infos:
            return []

        # Only need to check one TestInfo to determine if the tests are
        # configured in TEST_MAPPING.
        if test_infos[0].from_test_mapping:
            args.extend(constants.TEST_MAPPING_RESULT_SERVER_ARGS)
        test_infos = self._flatten_test_infos(test_infos)

        for info in test_infos:
            args.extend([constants.TF_INCLUDE_FILTER, info.test_name])
            filters = set()
            for test_filter in info.data.get(constants.TI_FILTER, []):
                filters.update(test_filter.to_set_of_tf_strings())
            for test_filter in filters:
                filter_arg = constants.TF_ATEST_INCLUDE_FILTER_VALUE_FMT.format(
                    test_name=info.test_name, test_filter=test_filter)
                args.extend([constants.TF_ATEST_INCLUDE_FILTER, filter_arg])
            for option in info.data.get(constants.TI_MODULE_ARG, []):
                if constants.TF_INCLUDE_FILTER_OPTION == option[0]:
                    suite_filter = (
                        constants.TF_SUITE_FILTER_ARG_VALUE_FMT.format(
                            test_name=info.test_name, option_value=option[1]))
                    args.extend([constants.TF_INCLUDE_FILTER, suite_filter])
                elif constants.TF_EXCLUDE_FILTER_OPTION == option[0]:
                    suite_filter = (
                        constants.TF_SUITE_FILTER_ARG_VALUE_FMT.format(
                            test_name=info.test_name, option_value=option[1]))
                    args.extend([constants.TF_EXCLUDE_FILTER, suite_filter])
                else:
                    module_arg = (
                        constants.TF_MODULE_ARG_VALUE_FMT.format(
                            test_name=info.test_name, option_name=option[0],
                            option_value=option[1]))
                    args.extend([constants.TF_MODULE_ARG, module_arg])
        return args
