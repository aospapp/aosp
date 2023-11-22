#!/usr/bin/env python
#
# Copyright 2018, The Android Open Source Project
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
Atest Argument Parser class for atest.
"""

import argparse

import atest_utils
import constants


class AtestArgParser(argparse.ArgumentParser):
    """Atest wrapper of ArgumentParser."""

    def __init__(self):
        """Initialise an ArgumentParser instance."""
        atest_utils.print_data_collection_notice()
        super(AtestArgParser, self).__init__(
            description=constants.HELP_DESC,
            epilog=self.EPILOG_TEXT,
            formatter_class=argparse.RawTextHelpFormatter)

    def add_atest_args(self):
        """A function that does ArgumentParser.add_argument()"""
        self.add_argument('tests', nargs='*', help='Tests to build and/or run.')
        self.add_argument('-b', '--build', action='append_const', dest='steps',
                          const=constants.BUILD_STEP, help='Run a build.')
        self.add_argument('-i', '--install', action='append_const', dest='steps',
                          const=constants.INSTALL_STEP, help='Install an APK.')
        self.add_argument('--info', action='store_true',
                          help='Show module information.')
        self.add_argument('--dry-run', action='store_true',
                          help='Dry run atest without building, installing and running '
                               'tests in real.')
        self.add_argument('-t', '--test', action='append_const', dest='steps',
                          const=constants.TEST_STEP,
                          help='Run the tests. WARNING: Many test configs force cleanup '
                               'of device after test run. In this case, -d must be used in '
                               'previous test run to disable cleanup, for -t to work. '
                               'Otherwise, device will need to be setup again with -i.')
        self.add_argument('-s', '--serial', help='The device to run the test on.')
        self.add_argument('-L', '--list-modules', help='List testable modules for the given suite.')
        self.add_argument('-d', '--disable-teardown', action='store_true',
                          help='Disables test teardown and cleanup.')
        self.add_argument('-m', constants.REBUILD_MODULE_INFO_FLAG, action='store_true',
                          help='Forces a rebuild of the module-info.json file. '
                               'This may be necessary following a repo sync or '
                               'when writing a new test.')
        self.add_argument('-w', '--wait-for-debugger', action='store_true',
                          help='Only for instrumentation tests. Waits for '
                               'debugger prior to execution.')
        self.add_argument('-v', '--verbose', action='store_true',
                          help='Display DEBUG level logging.')
        self.add_argument('-a', '--all-abi', action='store_true',
                          help='Set to run tests for all abi.')
        self.add_argument('--generate-baseline', nargs='?', type=int, const=5, default=0,
                          help='Generate baseline metrics, run 5 iterations by default. '
                               'Provide an int argument to specify # iterations.')
        self.add_argument('--generate-new-metrics', nargs='?', type=int, const=5, default=0,
                          help='Generate new metrics, run 5 iterations by default. '
                               'Provide an int argument to specify # iterations.')
        self.add_argument('--detect-regression', nargs='*',
                          help='Run regression detection algorithm. Supply '
                               'path to baseline and/or new metrics folders.')
        # Options related to module parameterization
        self.add_argument('--instant', action='store_true',
                          help='Run the instant_app version of the module, '
                               'if the module supports it. Note: running a test '
                               'that does not support instant with --instant '
                               'will result in nothing running.')
        # Options related to Test Mapping
        self.add_argument('-p', '--test-mapping', action='store_true',
                          help='Run tests in TEST_MAPPING files.')
        self.add_argument('--include-subdirs', action='store_true',
                          help='Include tests in TEST_MAPPING files in sub directories.')
        # Options related to deviceless testing.
        self.add_argument('--host', action='store_true',
                          help='Run the test completely on the host without '
                               'a device. (Note: running a host test that '
                               'requires a device with --host will fail.)')
        # This arg actually doesn't consume anything, it's primarily used for the
        # help description and creating custom_args in the NameSpace object.
        self.add_argument('--', dest='custom_args', nargs='*',
                          help='Specify custom args for the test runners. '
                               'Everything after -- will be consumed as custom args.')

    def get_args(self):
        """This method is to get args from actions and return optional args.

        Returns:
            A list of optional arguments.
        """
        argument_list = []
        # The output of _get_optional_actions(): [['-t', '--test'], [--info]]
        # return an argument list: ['-t', '--test', '--info']
        for arg in self._get_optional_actions():
            argument_list.extend(arg.option_strings)
        return argument_list


    EPILOG_TEXT = '''

- - - - - - - - -
IDENTIFYING TESTS
- - - - - - - - -

    The positional argument <tests> should be a reference to one or more
    of the tests you'd like to run. Multiple tests can be run in one command by
    separating test references with spaces.

    Usage template: atest <reference_to_test_1> <reference_to_test_2>

    A <reference_to_test> can be satisfied by the test's MODULE NAME,
    MODULE:CLASS, CLASS NAME, TF INTEGRATION TEST, FILE PATH or PACKAGE NAME.
    Explanations and examples of each follow.


    < MODULE NAME >

        Identifying a test by its module name will run the entire module. Input
        the name as it appears in the LOCAL_MODULE or LOCAL_PACKAGE_NAME
        variables in that test's Android.mk or Android.bp file.

        Note: Use < TF INTEGRATION TEST > to run non-module tests integrated
        directly into TradeFed.

        Examples:
            atest FrameworksServicesTests
            atest CtsJankDeviceTestCases


    < MODULE:CLASS >

        Identifying a test by its class name will run just the tests in that
        class and not the whole module. MODULE:CLASS is the preferred way to run
        a single class. MODULE is the same as described above. CLASS is the
        name of the test class in the .java file. It can either be the fully
        qualified class name or just the basic name.

        Examples:
            atest FrameworksServicesTests:ScreenDecorWindowTests
            atest FrameworksServicesTests:com.android.server.wm.ScreenDecorWindowTests
            atest CtsJankDeviceTestCases:CtsDeviceJankUi


    < CLASS NAME >

        A single class can also be run by referencing the class name without
        the module name.

        Examples:
            atest ScreenDecorWindowTests
            atest CtsDeviceJankUi

        However, this will take more time than the equivalent MODULE:CLASS
        reference, so we suggest using a MODULE:CLASS reference whenever
        possible. Examples below are ordered by performance from the fastest
        to the slowest:

        Examples:
            atest FrameworksServicesTests:com.android.server.wm.ScreenDecorWindowTests
            atest FrameworksServicesTests:ScreenDecorWindowTests
            atest ScreenDecorWindowTests

    < TF INTEGRATION TEST >

        To run tests that are integrated directly into TradeFed (non-modules),
        input the name as it appears in the output of the "tradefed.sh list
        configs" cmd.

        Examples:
           atest example/reboot
           atest native-benchmark


    < FILE PATH >

        Both module-based tests and integration-based tests can be run by
        inputting the path to their test file or dir as appropriate. A single
        class can also be run by inputting the path to the class's java file.
        Both relative and absolute paths are supported.

        Example - 2 ways to run the `CtsJankDeviceTestCases` module via path:
        1. run module from android <repo root>:
            atest cts/tests/jank/jank

        2. from <android root>/cts/tests/jank:
            atest .

        Example - run a specific class within CtsJankDeviceTestCases module
        from <android repo> root via path:
           atest cts/tests/jank/src/android/jank/cts/ui/CtsDeviceJankUi.java

        Example - run an integration test from <android repo> root via path:
           atest tools/tradefederation/contrib/res/config/example/reboot.xml


    < PACKAGE NAME >

        Atest supports searching tests from package name as well.

        Examples:
           atest com.android.server.wm
           atest android.jank.cts


- - - - - - - - - - - - - - - - - - - - - - - - - -
SPECIFYING INDIVIDUAL STEPS: BUILD, INSTALL OR RUN
- - - - - - - - - - - - - - - - - - - - - - - - - -

    The -b, -i and -t options allow you to specify which steps you want to run.
    If none of those options are given, then all steps are run. If any of these
    options are provided then only the listed steps are run.

    Note: -i alone is not currently support and can only be included with -t.
    Both -b and -t can be run alone.

    Examples:
        atest -b <test>    (just build targets)
        atest -t <test>    (run tests only)
        atest -it <test>   (install apk and run tests)
        atest -bt <test>   (build targets, run tests, but skip installing apk)


    Atest now has the ability to force a test to skip its cleanup/teardown step.
    Many tests, e.g. CTS, cleanup the device after the test is run, so trying to
    rerun your test with -t will fail without having the --disable-teardown
    parameter. Use -d before -t to skip the test clean up step and test iteratively.

        atest -d <test>    (disable installing apk and cleanning up device)
        atest -t <test>

    Note that -t disables both setup/install and teardown/cleanup of the
    device. So you can continue to rerun your test with just

        atest -t <test>

    as many times as you want.


- - - - - - - - - - - - -
RUNNING SPECIFIC METHODS
- - - - - - - - - - - - -

    It is possible to run only specific methods within a test class. To run
    only specific methods, identify the class in any of the ways supported for
    identifying a class (MODULE:CLASS, FILE PATH, etc) and then append the
    name of the method or method using the following template:

      <reference_to_class>#<method1>

    Multiple methods can be specified with commas:

      <reference_to_class>#<method1>,<method2>,<method3>...

    Examples:
      atest com.android.server.wm.ScreenDecorWindowTests#testMultipleDecors

      atest FrameworksServicesTests:ScreenDecorWindowTests#testFlagChange,testRemoval


- - - - - - - - - - - - -
RUNNING MULTIPLE CLASSES
- - - - - - - - - - - - -

    To run multiple classes, deliminate them with spaces just like you would
    when running multiple tests.  Atest will handle building and running
    classes in the most efficient way possible, so specifying a subset of
    classes in a module will improve performance over running the whole module.


    Examples:
    - two classes in same module:
      atest FrameworksServicesTests:ScreenDecorWindowTests FrameworksServicesTests:DimmerTests

    - two classes, different modules:
      atest FrameworksServicesTests:ScreenDecorWindowTests CtsJankDeviceTestCases:CtsDeviceJankUi


- - - - - - - - - - -
REGRESSION DETECTION
- - - - - - - - - - -

    Generate pre-patch or post-patch metrics without running regression detection:

    Example:
        atest <test> --generate-baseline <optional iter>
        atest <test> --generate-new-metrics <optional iter>

    Local regression detection can be run in three options:

    1) Provide a folder containing baseline (pre-patch) metrics (generated
       previously). Atest will run the tests n (default 5) iterations, generate
       a new set of post-patch metrics, and compare those against existing metrics.

    Example:
        atest <test> --detect-regression </path/to/baseline> --generate-new-metrics <optional iter>

    2) Provide a folder containing post-patch metrics (generated previously).
       Atest will run the tests n (default 5) iterations, generate a new set of
       pre-patch metrics, and compare those against those provided. Note: the
       developer needs to revert the device/tests to pre-patch state to generate
       baseline metrics.

    Example:
        atest <test> --detect-regression </path/to/new> --generate-baseline <optional iter>

    3) Provide 2 folders containing both pre-patch and post-patch metrics. Atest
       will run no tests but the regression detection algorithm.

    Example:
        atest --detect-regression </path/to/baseline> </path/to/new>


- - - - - - - - - - - -
TESTS IN TEST MAPPING
- - - - - - - - - - - -

    Atest can run tests in TEST_MAPPING files:

    1) Run presubmit tests in TEST_MAPPING files in current and parent
       directories. You can also specify a target directory.

    Example:
        atest  (run presubmit tests in TEST_MAPPING files in current and parent directories)
        atest --test-mapping </path/to/project>
               (run presubmit tests in TEST_MAPPING files in </path/to/project> and its parent directories)

    2) Run a specified test group in TEST_MAPPING files.

    Example:
        atest :postsubmit
              (run postsubmit tests in TEST_MAPPING files in current and parent directories)
        atest :all
              (Run tests from all groups in TEST_MAPPING files)
        atest --test-mapping </path/to/project>:postsubmit
              (run postsubmit tests in TEST_MAPPING files in </path/to/project> and its parent directories)

    3) Run tests in TEST_MAPPING files including sub directories

    By default, atest will only search for tests in TEST_MAPPING files in
    current (or given directory) and its parent directories. If you want to run
    tests in TEST_MAPPING files in the sub-directories, you can use option
    --include-subdirs to force atest to include those tests too.

    Example:
        atest --include-subdirs [optional </path/to/project>:<test_group_name>]
              (run presubmit tests in TEST_MAPPING files in current, sub and parent directories)
    A path can be provided optionally if you want to search for tests in a give
    directory, with optional test group name. By default, the test group is
    presubmit.

'''
