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
Utility functions for atest.
"""

from __future__ import print_function

import itertools
import logging
import os
import re
import subprocess
import sys
try:
    # If PYTHON2
    from urllib2 import urlopen
except ImportError:
    from urllib.request import urlopen

import constants

_MAKE_CMD = '%s/build/soong/soong_ui.bash' % os.environ.get(
    constants.ANDROID_BUILD_TOP)
BUILD_CMD = [_MAKE_CMD, '--make-mode']
_BASH_RESET_CODE = '\033[0m\n'
# Arbitrary number to limit stdout for failed runs in _run_limited_output.
# Reason for its use is that the make command itself has its own carriage
# return output mechanism that when collected line by line causes the streaming
# full_output list to be extremely large.
_FAILED_OUTPUT_LINE_LIMIT = 100
# Regular expression to match the start of a ninja compile:
# ex: [ 99% 39710/39711]
_BUILD_COMPILE_STATUS = re.compile(r'\[\s*(\d{1,3}%\s+)?\d+/\d+\]')
_BUILD_FAILURE = 'FAILED: '


def _capture_fail_section(full_log):
    """Return the error message from the build output.

    Args:
        full_log: List of strings representing full output of build.

    Returns:
        capture_output: List of strings that are build errors.
    """
    am_capturing = False
    capture_output = []
    for line in full_log:
        if am_capturing and _BUILD_COMPILE_STATUS.match(line):
            break
        if am_capturing or line.startswith(_BUILD_FAILURE):
            capture_output.append(line)
            am_capturing = True
            continue
    return capture_output


def _run_limited_output(cmd, env_vars=None):
    """Runs a given command and streams the output on a single line in stdout.

    Args:
        cmd: A list of strings representing the command to run.
        env_vars: Optional arg. Dict of env vars to set during build.

    Raises:
        subprocess.CalledProcessError: When the command exits with a non-0
            exitcode.
    """
    # Send stderr to stdout so we only have to deal with a single pipe.
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, env=env_vars)
    sys.stdout.write('\n')
    # Determine the width of the terminal. We'll need to clear this many
    # characters when carriage returning.
    _, term_width = os.popen('stty size', 'r').read().split()
    term_width = int(term_width)
    white_space = " " * int(term_width)
    full_output = []
    while proc.poll() is None:
        line = proc.stdout.readline()
        # Readline will often return empty strings.
        if not line:
            continue
        full_output.append(line.decode('utf-8'))
        # Trim the line to the width of the terminal.
        # Note: Does not handle terminal resizing, which is probably not worth
        #       checking the width every loop.
        if len(line) >= term_width:
            line = line[:term_width - 1]
        # Clear the last line we outputted.
        sys.stdout.write('\r%s\r' % white_space)
        sys.stdout.write('%s' % line.strip())
        sys.stdout.flush()
    # Reset stdout (on bash) to remove any custom formatting and newline.
    sys.stdout.write(_BASH_RESET_CODE)
    sys.stdout.flush()
    # Wait for the Popen to finish completely before checking the returncode.
    proc.wait()
    if proc.returncode != 0:
        # Parse out the build error to output.
        output = _capture_fail_section(full_output)
        if not output:
            output = full_output
        if len(output) >= _FAILED_OUTPUT_LINE_LIMIT:
            output = output[-_FAILED_OUTPUT_LINE_LIMIT:]
        output = 'Output (may be trimmed):\n%s' % ''.join(output)
        raise subprocess.CalledProcessError(proc.returncode, cmd, output)


def build(build_targets, verbose=False, env_vars=None):
    """Shell out and make build_targets.

    Args:
        build_targets: A set of strings of build targets to make.
        verbose: Optional arg. If True output is streamed to the console.
                 If False, only the last line of the build output is outputted.
        env_vars: Optional arg. Dict of env vars to set during build.

    Returns:
        Boolean of whether build command was successful, True if nothing to
        build.
    """
    if not build_targets:
        logging.debug('No build targets, skipping build.')
        return True
    full_env_vars = os.environ.copy()
    if env_vars:
        full_env_vars.update(env_vars)
    print('\n%s\n%s' % (colorize("Building Dependencies...", constants.CYAN),
                        ', '.join(build_targets)))
    logging.debug('Building Dependencies: %s', ' '.join(build_targets))
    cmd = BUILD_CMD + list(build_targets)
    logging.debug('Executing command: %s', cmd)
    try:
        if verbose:
            subprocess.check_call(cmd, stderr=subprocess.STDOUT,
                                  env=full_env_vars)
        else:
            # TODO: Save output to a log file.
            _run_limited_output(cmd, env_vars=full_env_vars)
        logging.info('Build successful')
        return True
    except subprocess.CalledProcessError as err:
        logging.error('Error building: %s', build_targets)
        if err.output:
            logging.error(err.output)
        return False


def _can_upload_to_result_server():
    """Return True if we can talk to result server."""
    # TODO: Also check if we have a slow connection to result server.
    if constants.RESULT_SERVER:
        try:
            urlopen(constants.RESULT_SERVER,
                    timeout=constants.RESULT_SERVER_TIMEOUT).close()
            return True
        # pylint: disable=broad-except
        except Exception as err:
            logging.debug('Talking to result server raised exception: %s', err)
    return False


def get_result_server_args():
    """Return list of args for communication with result server."""
    if _can_upload_to_result_server():
        return constants.RESULT_SERVER_ARGS
    return []


def sort_and_group(iterable, key):
    """Sort and group helper function."""
    return itertools.groupby(sorted(iterable, key=key), key=key)


def is_test_mapping(args):
    """Check if the atest command intends to run tests in test mapping.

    When atest runs tests in test mapping, it must have at most one test
    specified. If a test is specified, it must be started with  `:`,
    which means the test value is a test group name in TEST_MAPPING file, e.g.,
    `:postsubmit`.

    If any test mapping options is specified, the atest command must also be
    set to run tests in test mapping files.

    Args:
        args: arg parsed object.

    Returns:
        True if the args indicates atest shall run tests in test mapping. False
        otherwise.
    """
    return (
        args.test_mapping or
        args.include_subdirs or
        not args.tests or
        (len(args.tests) == 1 and args.tests[0][0] == ':'))


def _has_colors(stream):
    """Check the the output stream is colorful.

    Args:
        stream: The standard file stream.

    Returns:
        True if the file stream can interpreter the ANSI color code.
    """
    # Following from Python cookbook, #475186
    if not hasattr(stream, "isatty"):
        return False
    if not stream.isatty():
        # Auto color only on TTYs
        return False
    try:
        import curses
        curses.setupterm()
        return curses.tigetnum("colors") > 2
    # pylint: disable=broad-except
    except Exception as err:
        logging.debug('Checking colorful raised exception: %s', err)
        return False


def colorize(text, color, highlight=False):
    """ Convert to colorful string with ANSI escape code.

    Args:
        text: A string to print.
        color: ANSI code shift for colorful print. They are defined
               in constants_default.py.
        highlight: True to print with highlight.

    Returns:
        Colorful string with ANSI escape code.
    """
    clr_pref = '\033[1;'
    clr_suff = '\033[0m'
    has_colors = _has_colors(sys.stdout)
    if has_colors:
        if highlight:
            ansi_shift = 40 + color
        else:
            ansi_shift = 30 + color
        clr_str = "%s%dm%s%s" % (clr_pref, ansi_shift, text, clr_suff)
    else:
        clr_str = text
    return clr_str


def colorful_print(text, color, highlight=False, auto_wrap=True):
    """Print out the text with color.

    Args:
        text: A string to print.
        color: ANSI code shift for colorful print. They are defined
               in constants_default.py.
        highlight: True to print with highlight.
        auto_wrap: If True, Text wraps while print.
    """
    output = colorize(text, color, highlight)
    if auto_wrap:
        print(output)
    else:
        print(output, end="")


def is_external_run():
    """Check is external run or not.

    Returns:
        True if this is an external run, False otherwise.
    """
    try:
        output = subprocess.check_output(['git', 'config', '--get', 'user.email'],
                                         universal_newlines=True)
        if output and output.strip().endswith(constants.INTERNAL_EMAIL):
            return False
    except OSError:
        # OSError can be raised when running atest_unittests on a host
        # without git being set up.
        # This happens before atest._configure_logging is called to set up
        # logging. Therefore, use print to log the error message, instead of
        # logging.debug.
        print('Unable to determine if this is an external run, git is not found.')
    except subprocess.CalledProcessError:
        print('Unable to determine if this is an external run, email is not '
              'found in git config.')
    return True


def print_data_collection_notice():
    """Print the data collection notice."""
    anonymous = ''
    user_type = 'INTERNAL'
    if is_external_run():
        anonymous = ' anonymous'
        user_type = 'EXTERNAL'
    notice = ('  We collect%s usage statistics in accordance with our Content '
              'Licenses (%s), Contributor License Agreement (%s), Privacy '
              'Policy (%s) and Terms of Service (%s).'
             ) % (anonymous,
                  constants.CONTENT_LICENSES_URL,
                  constants.CONTRIBUTOR_AGREEMENT_URL[user_type],
                  constants.PRIVACY_POLICY_URL,
                  constants.TERMS_SERVICE_URL
                 )
    print('\n==================')
    colorful_print("Notice:", constants.RED)
    colorful_print("%s" % notice, constants.GREEN)
    print('==================\n')
