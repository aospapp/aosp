# Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
import logging
import os
import re
import shutil
import StringIO

import common
from autotest_lib.client.common_lib import error
from autotest_lib.server import test
from autotest_lib.server import utils
from autotest_lib.site_utils import test_runner_utils


TELEMETRY_TIMEOUT_MINS = 60
WAIT_FOR_CMD_TIMEOUT_SECS = 60
DUT_COMMON_SSH_OPTIONS = ['-o StrictHostKeyChecking=no',
                          '-o UserKnownHostsFile=/dev/null',
                          '-o BatchMode=yes',
                          '-o ConnectTimeout=30',
                          '-o ServerAliveInterval=900',
                          '-o ServerAliveCountMax=3',
                          '-o ConnectionAttempts=4',
                          '-o Protocol=2']
DUT_SCP_OPTIONS = ' '.join(DUT_COMMON_SSH_OPTIONS)

CHROME_SRC_ROOT = '/var/cache/chromeos-cache/distfiles/target/'
CLIENT_CHROME_ROOT = '/usr/local/telemetry/src'
RUN_BENCHMARK  = 'tools/perf/run_benchmark'

RSA_KEY = '-i %s' % test_runner_utils.TEST_KEY_PATH
DUT_CHROME_RESULTS_DIR = '/usr/local/telemetry/src/tools/perf'

# Result Statuses
SUCCESS_STATUS = 'SUCCESS'
WARNING_STATUS = 'WARNING'
FAILED_STATUS = 'FAILED'

# Regex for the RESULT output lines understood by chrome buildbot.
# Keep in sync with
# chromium/tools/build/scripts/slave/performance_log_processor.py.
RESULTS_REGEX = re.compile(r'(?P<IMPORTANT>\*)?RESULT '
                           r'(?P<GRAPH>[^:]*): (?P<TRACE>[^=]*)= '
                           r'(?P<VALUE>[\{\[]?[-\d\., ]+[\}\]]?)('
                           r' ?(?P<UNITS>.+))?')
HISTOGRAM_REGEX = re.compile(r'(?P<IMPORTANT>\*)?HISTOGRAM '
                             r'(?P<GRAPH>[^:]*): (?P<TRACE>[^=]*)= '
                             r'(?P<VALUE_JSON>{.*})(?P<UNITS>.+)?')

_RUN_BACKGROUND_TEMPLATE = '(%(cmd)s) </dev/null >/dev/null 2>&1 & echo -n $!'

_WAIT_CMD_TEMPLATE = """\
to=%(timeout)d; \
while test ${to} -ne 0; do \
  ps %(pid)d >/dev/null || break; \
  sleep 1; \
  to=$((to - 1)); \
done; \
! ps %(pid)d >/dev/null \
"""


def _run_in_background(host, cmd, stdout, stderr, timeout):
    """Launch command on host; return without waiting for it to finish.

    @param host: A host object representing where the command runs.
    @param cmd: The command to run.

    @return The result of launching this command, which contains pid info.
    """
    background_cmd = _RUN_BACKGROUND_TEMPLATE % {'cmd': cmd}
    logging.info('BACKGROUND CMD: %s', background_cmd)
    return host.run(background_cmd,
                stdout_tee=stdout,
                stderr_tee=stderr,
                timeout=timeout)


def _wait_for_process(host, pid, timeout=-1):
    """Waits for a process on the DUT to terminate.

    @param host: A host object representing the DUT.
    @param pid: The process ID (integer).
    @param timeout: Number of seconds to wait; default is wait forever.
    """
    wait_cmd = _WAIT_CMD_TEMPLATE % {'pid': pid, 'timeout': timeout}
    host.run(wait_cmd, ignore_status=True).exit_status


def _kill_perf(host):
    """Kills perf on the DUT.

    @param host: A host object representing the DUT.
    """
    # Note that here -2 equals -INT. ChromeOS release image cannot recognize
    # -INT, so we need to specify it here.
    kill_cmd = 'killall -2 perf'
    logging.info('Killing perf using: %s', kill_cmd)
    host.run(kill_cmd, ignore_status=True).exit_status


def _find_chrome_root_dir():
    # Look for chrome source root, either externally mounted, or inside
    # the chroot.  Prefer chrome-src-internal source tree to chrome-src.
    sources_list = ('chrome-src-internal', 'chrome-src')

    dir_list = [os.path.join(CHROME_SRC_ROOT, x) for x in sources_list]
    if 'CHROME_ROOT' in os.environ:
        dir_list.insert(0, os.environ['CHROME_ROOT'])

    for dir in dir_list:
        if os.path.exists(dir):
            chrome_root_dir = dir
            break
    else:
        raise error.TestError('Chrome source directory not found.')

    logging.info('Using Chrome source tree at %s', chrome_root_dir)
    return os.path.join(chrome_root_dir, 'src')


def _ensure_deps(dut, test_name):
    """
    Ensure the dependencies are locally available on DUT.

    @param dut: The autotest host object representing DUT.
    @param test_name: Name of the telemetry test.
    """
    # Get DEPs using host's telemetry.
    chrome_root_dir = _find_chrome_root_dir()
    format_string = ('python %s/tools/perf/fetch_benchmark_deps.py %s')
    command = format_string % (chrome_root_dir, test_name)
    logging.info('Getting DEPs: %s', command)
    stdout = StringIO.StringIO()
    stderr = StringIO.StringIO()
    try:
        result = utils.run(command, stdout_tee=stdout,
                           stderr_tee=stderr)
    except error.CmdError as e:
        logging.debug('Error occurred getting DEPs: %s\n %s\n',
                      stdout.getvalue(), stderr.getvalue())
        raise error.TestFail('Error occurred while getting DEPs.')

    # Download DEPs to DUT.
    # send_file() relies on rsync over ssh. Couldn't be better.
    stdout_str = stdout.getvalue()
    stdout.close()
    stderr.close()
    for dep in stdout_str.split():
        src = os.path.join(chrome_root_dir, dep)
        dst = os.path.join(CLIENT_CHROME_ROOT, dep)
        if not os.path.isfile(src):
            raise error.TestFail('Error occurred while saving DEPs.')
        logging.info('Copying: %s -> %s', src, dst)
        try:
            dut.send_file(src, dst)
        except:
            raise error.TestFail('Error occurred while sending DEPs to dut.\n')


class telemetry_Crosperf(test.test):
    """Run one or more telemetry benchmarks under the crosperf script."""
    version = 1

    def scp_telemetry_results(self, client_ip, dut, file, host_dir):
        """Copy telemetry results from dut.

        @param client_ip: The ip address of the DUT
        @param dut: The autotest host object representing DUT.
        @param file: The file to copy from DUT.
        @param host_dir: The directory on host to put the file .

        @returns status code for scp command.
        """
        cmd=[]
        src = ('root@%s:%s/%s' %
               (dut.hostname if dut else client_ip,
                DUT_CHROME_RESULTS_DIR,
                file))
        cmd.extend(['scp', DUT_SCP_OPTIONS, RSA_KEY, '-v',
                    src, host_dir])
        command = ' '.join(cmd)

        logging.debug('Retrieving Results: %s', command)
        try:
            result = utils.run(command,
                               timeout=WAIT_FOR_CMD_TIMEOUT_SECS)
            exit_code = result.exit_status
        except Exception as e:
            logging.error('Failed to retrieve results: %s', e)
            raise

        logging.debug('command return value: %d', exit_code)
        return exit_code

    def run_once(self, args, client_ip='', dut=None):
        """
        Run a single telemetry test.

        @param args: A dictionary of the arguments that were passed
                to this test.
        @param client_ip: The ip address of the DUT
        @param dut: The autotest host object representing DUT.

        @returns A TelemetryResult instance with the results of this execution.
        """
        test_name = args.get('test', '')
        test_args = args.get('test_args', '')
        profiler_args = args.get('profiler_args', '')

        # Decide whether the test will run locally or by a remote server.
        if args.get('run_local', 'false').lower() == 'true':
            # The telemetry scripts will run on DUT.
            _ensure_deps(dut, test_name)
            format_string = ('python %s --browser=system '
                             '--output-format=chartjson %s %s')
            command = format_string % (os.path.join(CLIENT_CHROME_ROOT,
                                                    RUN_BENCHMARK),
                                       test_args, test_name)
            runner = dut
        else:
            # The telemetry scripts will run on server.
            format_string = ('python %s --browser=cros-chrome --remote=%s '
                             '--output-dir="%s" '
                             '--output-format=chartjson %s %s')
            command = format_string % (os.path.join(_find_chrome_root_dir(),
                                                    RUN_BENCHMARK),
                                       client_ip, self.resultsdir, test_args,
                                       test_name)
            runner = utils

        # Run the test. And collect profile if needed.
        stdout = StringIO.StringIO()
        stderr = StringIO.StringIO()
        try:
            # If profiler_args specified, we want to add several more options
            # to the command so that run_benchmark will collect system wide
            # profiles.
            if profiler_args:
                command += ' --interval-profiling-period=story_run' \
                           ' --interval-profiling-target=system_wide' \
                           ' --interval-profiler-options="%s"' \
                           % (profiler_args)

            logging.info('BENCHMARK CMD: %s', command)
            # Run benchmark at background and get pid of it.
            result = _run_in_background(runner, command, stdout, stderr,
                                        WAIT_FOR_CMD_TIMEOUT_SECS)
            benchmark_pid = int(result.stdout.rstrip())

            # Wait until benchmark run finished
            _wait_for_process(runner, benchmark_pid,
                              TELEMETRY_TIMEOUT_MINS * 60)

            # If no command error happens, set exit_code to 0
            exit_code = 0

        except error.CmdError as e:
            logging.debug('Error occurred executing telemetry.')
            exit_code = e.result_obj.exit_status
            raise error.TestFail('An error occurred while executing '
                                 'telemetry test.')
        except:
            logging.debug('Telemetry aborted with unknown error.')
            exit_code = -1
            raise
        finally:
            # Make sure perf on DUT is gone in case of any unexpected thing
            # happens above. We don't want some perf process continues to run
            # on DUT to fill up the disk after we finish.
            try:
                _kill_perf(dut)
            except:
                pass
            stdout_str = stdout.getvalue()
            stderr_str = stderr.getvalue()
            stdout.close()
            stderr.close()
            logging.info('Telemetry completed with exit code: %d.'
                         '\nstdout:%s\nstderr:%s', exit_code,
                         stdout_str, stderr_str)

        # Copy the results-chart.json file into the test_that results
        # directory, if necessary.
        if args.get('run_local', 'false').lower() == 'true':
            result = self.scp_telemetry_results(client_ip, dut,
                                                'results-chart.json',
                                                self.resultsdir)
        else:
            filepath = os.path.join(self.resultsdir, 'results-chart.json')
            if not os.path.exists(filepath):
                exit_code = -1
                raise RuntimeError('Missing results file: %s' % filepath)

        # Copy the perf data file into the test_that profiling directory,
        # if necessary. It always comes from DUT.
        if profiler_args:
            filepath = os.path.join(self.resultsdir, 'artifacts')
            perf_exist = False
            for filename in os.listdir(filepath):
                if filename.endswith('perf.data'):
                    perf_exist = True
                    shutil.copyfile(os.path.join(filepath, filename),
                                    os.path.join(self.profdir, 'perf.data'))
            if not perf_exist:
                exit_code = -1
                raise error.TestFail('Error: No profiles collected, test may '
                                     'not run correctly.')

        return result
