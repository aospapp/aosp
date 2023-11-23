#!/usr/bin/env python3
#
#   Copyright 2018 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

"""The list of all metadata on special config entries to the test config.

These values cover the 3 major sources of configuration data:
the CLI arguments, the json config file, and environment variables.
The metadata defines the way to obtain the configuration data from any
ConfigSource. This list will be passed into each ConfigSource.
"""

from acts.config.config_entry_meta import ConfigEntryMeta
from acts.keys import Config

config_entries = [
    ConfigEntryMeta(
        acts_config_key=Config.key_config_full_path.value,
        cli_flags=['-c', '--config'],
        type=str,
        cli_required=True,
        cli_metavar='<PATH>',
        help='Path to the test configuration file.',
    ),
    ConfigEntryMeta(
        acts_config_key='cli_args',
        cli_flags=['--test_args'],
        cli_nargs='+',
        type=str,
        cli_metavar='Arg1 Arg2 ...',
        help='Command-line arguments to be passed to every test case in a '
             'test run. Use with caution.',
    ),
    ConfigEntryMeta(
        acts_config_key='parallel',
        cli_flags=['-p', '--parallel'],
        cli_action='store_true',
        help='If set, tests will be executed on all testbeds in parallel. '
             'Otherwise, tests are executed iteratively testbed by testbed.',
    ),
    ConfigEntryMeta(
        acts_config_key='campaign_iterations',
        cli_flags=['-ci', '--campaign_iterations'],
        cli_metavar='<CAMPAIGN_ITERATIONS>',
        cli_nargs='?',
        type=int,
        cli_const=1,
        cli_default=1,
        help='Number of times to run the campaign or a group of test cases.',
    ),
    ConfigEntryMeta(
        acts_config_key=Config.key_testbeds_under_test.value,
        cli_flags=['-tb', '--testbed'],
        cli_nargs='+',
        type=str,
        cli_metavar='[<TEST BED NAME1> <TEST BED NAME2> ...]',
        help='Specify which test beds to run tests on.',
    ),
    ConfigEntryMeta(
        acts_config_key='logpath',
        env_var_name='ACTS_LOGPATH',
        cli_flags=['-lp', '--logpath'],
        type=str,
        cli_metavar='<PATH>',
        help='Root path under which all logs will be placed.',
    ),
    ConfigEntryMeta(
        acts_config_key='testpaths',
        env_var_name='ACTS_TESTPATHS',
        cli_flags=['-tp', '--testpaths'],
        cli_nargs='*',
        type=str,
        cli_metavar='<PATH> <PATH>',
        help='One or more non-recursive test class search paths.',
    ),
    ConfigEntryMeta(
        acts_config_key='testclass',
        cli_flags=['-tc', '--testclass'],
        cli_nargs='+',
        type=str,
        cli_metavar='[TestClass1 TestClass2:test_xxx ...]',
        help='A list of test classes/cases to run.',
    ),
    ConfigEntryMeta(
        acts_config_key='testfile',
        cli_flags=['-tf', '--testfile'],
        cli_nargs=1,
        type=str,
        cli_metavar='<PATH>',
        help='Path to a file containing a comma delimited list of test '
             'classes to run.',
    ),
    ConfigEntryMeta(
        acts_config_key='random',
        cli_flags=['-r', '--random'],
        cli_action='store_true',
        help='If set, tests will be executed in random order.',
    ),
    ConfigEntryMeta(
        acts_config_key='test_case_iterations',
        cli_flags=['-ti', '--test_case_iterations'],
        cli_metavar='<TEST_CASE_ITERATIONS>',
        cli_nargs='?',
        type=int,
        help='Number of times to run every test case.',
    ),
    ConfigEntryMeta(
        acts_config_key=Config.key_test_failure_tracebacks.value,
        cli_flags=['-tft', '--test_failure_tracebacks'],
        env_var_name='ACTS_TEST_FAILURE_TRACEBACKS',
        cli_action='store_const',
        cli_const=True,
        help='Enables tracebacks to be printed when a test raises TestFailure.',
    )
]
