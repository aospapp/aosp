#!/usr/bin/env python3
#
# Copyright 2016 - The Android Open Source Project
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

import fnmatch
import importlib
import logging
import os
import traceback
from concurrent.futures import ThreadPoolExecutor

from acts import asserts
from acts import keys
from acts import logger
from acts import records
from acts import signals
from acts import error
from acts import tracelogger
from acts import utils
from acts.event import event_bus
from acts.event import subscription_bundle
from acts.event.decorators import subscribe_static
from acts.event.event import TestCaseBeginEvent
from acts.event.event import TestCaseEndEvent
from acts.event.event import TestClassBeginEvent
from acts.event.event import TestClassEndEvent
from acts.event.subscription_bundle import SubscriptionBundle

from mobly import controller_manager
from mobly.records import ExceptionRecord

# Macro strings for test result reporting
TEST_CASE_TOKEN = "[Test Case]"
RESULT_LINE_TEMPLATE = TEST_CASE_TOKEN + " %s %s"


@subscribe_static(TestCaseBeginEvent)
def _logcat_log_test_begin(event):
    """Ensures that logcat is running. Write a logcat line indicating test case
     begin."""
    test_instance = event.test_class
    try:
        for ad in getattr(test_instance, 'android_devices', []):
            if not ad.is_adb_logcat_on:
                ad.start_adb_logcat()
            # Write test start token to adb log if android device is attached.
            if not ad.skip_sl4a:
                ad.droid.logV("%s BEGIN %s" % (TEST_CASE_TOKEN,
                                               event.test_case_name))

    except error.ActsError as e:
        test_instance.results.error.append(
            ExceptionRecord(
                e, 'Logcat for test begin: %s' % event.test_case_name))
        test_instance.log.error('BaseTest setup_test error: %s' % e.message)

    except Exception as e:
        test_instance.log.warning(
            'Unable to send BEGIN log command to all devices.')
        test_instance.log.warning('Error: %s' % e)


@subscribe_static(TestCaseEndEvent)
def _logcat_log_test_end(event):
    """Write a logcat line indicating test case end."""
    test_instance = event.test_class
    try:
        # Write test end token to adb log if android device is attached.
        for ad in getattr(test_instance, 'android_devices', []):
            if not ad.skip_sl4a:
                ad.droid.logV("%s END %s" % (TEST_CASE_TOKEN,
                                             event.test_case_name))

    except error.ActsError as e:
        test_instance.results.error.append(
            ExceptionRecord(
                e, 'Logcat for test end: %s' % event.test_case_name))
        test_instance.log.error('BaseTest teardown_test error: %s' % e.message)

    except Exception as e:
        test_instance.log.warning(
            'Unable to send END log command to all devices.')
        test_instance.log.warning('Error: %s' % e)


event_bus.register_subscription(_logcat_log_test_begin.subscription)
event_bus.register_subscription(_logcat_log_test_end.subscription)


class Error(Exception):
    """Raised for exceptions that occured in BaseTestClass."""


class BaseTestClass(object):
    """Base class for all test classes to inherit from.

    This class gets all the controller objects from test_runner and executes
    the test cases requested within itself.

    Most attributes of this class are set at runtime based on the configuration
    provided.

    Attributes:
        tests: A list of strings, each representing a test case name.
        TAG: A string used to refer to a test class. Default is the test class
             name.
        log: A logger object used for logging.
        results: A records.TestResult object for aggregating test results from
                 the execution of test cases.
        consecutive_failures: Tracks the number of consecutive test case
                              failures within this class.
        consecutive_failure_limit: Number of consecutive test failures to allow
                                   before blocking remaining tests in the same
                                   test class.
        size_limit_reached: True if the size of the log directory has reached
                            its limit.
        current_test_name: A string that's the name of the test case currently
                           being executed. If no test is executing, this should
                           be None.
    """

    TAG = None

    def __init__(self, configs):
        self.class_subscriptions = SubscriptionBundle()
        self.class_subscriptions.register()
        self.all_subscriptions = [self.class_subscriptions]

        self.tests = []
        if not self.TAG:
            self.TAG = self.__class__.__name__
        # Set all the controller objects and params.
        self.user_params = {}
        self.testbed_configs = {}
        for name, value in configs.items():
            setattr(self, name, value)
        self.results = records.TestResult()
        self.current_test_name = None
        self.log = tracelogger.TraceLogger(self.log)
        self.consecutive_failures = 0
        self.consecutive_failure_limit = self.user_params.get(
            'consecutive_failure_limit', -1)
        self.size_limit_reached = False

        # Initialize a controller manager (Mobly)
        self._controller_manager = controller_manager.ControllerManager(
            class_name=self.__class__.__name__,
            controller_configs=self.testbed_configs)

        # Import and register the built-in controller modules specified
        # in testbed config.
        for module in self._import_builtin_controllers():
            self.register_controller(module, builtin=True)
        if hasattr(self, 'android_devices'):
            for ad in self.android_devices:
                if ad.droid:
                    utils.set_location_service(ad, False)
                    utils.sync_device_time(ad)
        self.testbed_name = ''

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self._exec_func(self.clean_up)

    def unpack_userparams(self,
                          req_param_names=[],
                          opt_param_names=[],
                          **kwargs):
        """An optional function that unpacks user defined parameters into
        individual variables.

        After unpacking, the params can be directly accessed with self.xxx.

        If a required param is not provided, an exception is raised. If an
        optional param is not provided, a warning line will be logged.

        To provide a param, add it in the config file or pass it in as a kwarg.
        If a param appears in both the config file and kwarg, the value in the
        config file is used.

        User params from the config file can also be directly accessed in
        self.user_params.

        Args:
            req_param_names: A list of names of the required user params.
            opt_param_names: A list of names of the optional user params.
            **kwargs: Arguments that provide default values.
                e.g. unpack_userparams(required_list, opt_list, arg_a="hello")
                     self.arg_a will be "hello" unless it is specified again in
                     required_list or opt_list.

        Raises:
            Error is raised if a required user params is not provided.
        """
        for k, v in kwargs.items():
            if k in self.user_params:
                v = self.user_params[k]
            setattr(self, k, v)
        for name in req_param_names:
            if hasattr(self, name):
                continue
            if name not in self.user_params:
                raise Error(("Missing required user param '%s' in test "
                             "configuration.") % name)
            setattr(self, name, self.user_params[name])
        for name in opt_param_names:
            if hasattr(self, name):
                continue
            if name in self.user_params:
                setattr(self, name, self.user_params[name])
            else:
                self.log.warning(("Missing optional user param '%s' in "
                                  "configuration, continue."), name)

    def _import_builtin_controllers(self):
        """Import built-in controller modules.

        Go through the testbed configs, find any built-in controller configs
        and import the corresponding controller module from acts.controllers
        package.

        Returns:
            A list of controller modules.
        """
        builtin_controllers = []
        for ctrl_name in keys.Config.builtin_controller_names.value:
            if ctrl_name in self.testbed_configs:
                module_name = keys.get_module_name(ctrl_name)
                module = importlib.import_module(
                    "acts.controllers.%s" % module_name)
                builtin_controllers.append(module)
        return builtin_controllers

    @staticmethod
    def get_module_reference_name(a_module):
        """Returns the module's reference name.

        This is largely for backwards compatibility with log parsing. If the
        module defines ACTS_CONTROLLER_REFERENCE_NAME, it will return that
        value, or the module's submodule name.

        Args:
            a_module: Any module. Ideally, a controller module.
        Returns:
            A string corresponding to the module's name.
        """
        if hasattr(a_module, 'ACTS_CONTROLLER_REFERENCE_NAME'):
            return a_module.ACTS_CONTROLLER_REFERENCE_NAME
        else:
            return a_module.__name__.split('.')[-1]

    def register_controller(self,
                            controller_module,
                            required=True,
                            builtin=False):
        """Registers an ACTS controller module for a test class. Invokes Mobly's
        implementation of register_controller.

        An ACTS controller module is a Python lib that can be used to control
        a device, service, or equipment. To be ACTS compatible, a controller
        module needs to have the following members:

            def create(configs):
                [Required] Creates controller objects from configurations.
                Args:
                    configs: A list of serialized data like string/dict. Each
                             element of the list is a configuration for a
                             controller object.
                Returns:
                    A list of objects.

            def destroy(objects):
                [Required] Destroys controller objects created by the create
                function. Each controller object shall be properly cleaned up
                and all the resources held should be released, e.g. memory
                allocation, sockets, file handlers etc.
                Args:
                    A list of controller objects created by the create function.

            def get_info(objects):
                [Optional] Gets info from the controller objects used in a test
                run. The info will be included in test_result_summary.json under
                the key "ControllerInfo". Such information could include unique
                ID, version, or anything that could be useful for describing the
                test bed and debugging.
                Args:
                    objects: A list of controller objects created by the create
                             function.
                Returns:
                    A list of json serializable objects, each represents the
                    info of a controller object. The order of the info object
                    should follow that of the input objects.
            def get_post_job_info(controller_list):
                [Optional] Returns information about the controller after the
                test has run. This info is sent to test_run_summary.json's
                "Extras" key.
                Args:
                    The list of controller objects created by the module
                Returns:
                    A (name, data) tuple.
        Registering a controller module declares a test class's dependency the
        controller. If the module config exists and the module matches the
        controller interface, controller objects will be instantiated with
        corresponding configs. The module should be imported first.

        Args:
            controller_module: A module that follows the controller module
                interface.
            required: A bool. If True, failing to register the specified
                controller module raises exceptions. If False, returns None upon
                failures.
            builtin: Specifies that the module is a builtin controller module in
                ACTS. If true, adds itself to test attributes.
        Returns:
            A list of controller objects instantiated from controller_module, or
            None.

        Raises:
            When required is True, ControllerError is raised if no corresponding
            config can be found.
            Regardless of the value of "required", ControllerError is raised if
            the controller module has already been registered or any other error
            occurred in the registration process.
        """
        module_ref_name = self.get_module_reference_name(controller_module)

        # Substitute Mobly controller's module config name with the ACTS one
        module_config_name = controller_module.ACTS_CONTROLLER_CONFIG_NAME
        controller_module.MOBLY_CONTROLLER_CONFIG_NAME = module_config_name

        # Get controller objects from Mobly's register_controller
        controllers = self._controller_manager.register_controller(
            controller_module, required=required)
        if not controllers:
            return None

        # Collect controller information and write to test result.
        # Implementation of "get_info" is optional for a controller module.
        if hasattr(controller_module, "get_info"):
            controller_info = controller_module.get_info(controllers)
            self.log.info("Controller %s: %s", module_config_name,
                          controller_info)
            self.results.add_controller_info(module_config_name,
                                             controller_info)
        else:
            self.log.warning("No controller info obtained for %s",
                             module_config_name)
        self._record_controller_info()

        if builtin:
            setattr(self, module_ref_name, controllers)
        return controllers

    def unregister_controllers(self):
        """Destroy controller objects and clear internal registry. Invokes
        Mobly's controller manager's unregister_controllers.

        This will be called upon test class teardown.
        """
        controller_modules = self._controller_manager._controller_modules
        controller_objects = self._controller_manager._controller_objects
        # Record post job info for the controller
        for name, controller_module in controller_modules.items():
            if hasattr(controller_module, 'get_post_job_info'):
                self.log.debug('Getting post job info for %s', name)
                try:
                    name, value = controller_module.get_post_job_info(
                        controller_objects[name])
                    self.results.set_extra_data(name, value)
                    self.summary_writer.dump(
                        {name: value}, records.TestSummaryEntryType.USER_DATA)
                except:
                    self.log.error("Fail to get post job info for %s", name)
        self._controller_manager.unregister_controllers()

    def _record_controller_info(self):
        """Collect controller information and write to summary file."""
        try:
            manager = self._controller_manager
            for record in manager.get_controller_info_records():
                self.summary_writer.dump(
                    record.to_dict(),
                    records.TestSummaryEntryType.CONTROLLER_INFO)
        except Exception:
            self.log.exception('Unable to write controller info records to'
                               ' summary file')

    def _setup_class(self):
        """Proxy function to guarantee the base implementation of setup_class
        is called.
        """
        event_bus.post(TestClassBeginEvent(self))
        return self.setup_class()

    def setup_class(self):
        """Setup function that will be called before executing any test case in
        the test class.

        To signal setup failure, return False or raise an exception. If
        exceptions were raised, the stack trace would appear in log, but the
        exceptions would not propagate to upper levels.

        Implementation is optional.
        """

    def _teardown_class(self):
        """Proxy function to guarantee the base implementation of teardown_class
        is called.
        """
        self.teardown_class()
        self.unregister_controllers()
        event_bus.post(TestClassEndEvent(self, self.results))

    def teardown_class(self):
        """Teardown function that will be called after all the selected test
        cases in the test class have been executed.

        Implementation is optional.
        """

    def _setup_test(self, test_name):
        """Proxy function to guarantee the base implementation of setup_test is
        called.
        """
        self.current_test_name = test_name

        # Skip the test if the consecutive test case failure limit is reached.
        if self.consecutive_failures == self.consecutive_failure_limit:
            raise signals.TestError('Consecutive test failure')

        return self.setup_test()

    def setup_test(self):
        """Setup function that will be called every time before executing each
        test case in the test class.

        To signal setup failure, return False or raise an exception. If
        exceptions were raised, the stack trace would appear in log, but the
        exceptions would not propagate to upper levels.

        Implementation is optional.
        """
        return True

    def _teardown_test(self, test_name):
        """Proxy function to guarantee the base implementation of teardown_test
        is called.
        """
        self.log.debug('Tearing down test %s' % test_name)

        try:
            self.teardown_test()
        finally:
            self.current_test_name = None

    def teardown_test(self):
        """Teardown function that will be called every time a test case has
        been executed.

        Implementation is optional.
        """

    def _on_fail(self, record):
        """Proxy function to guarantee the base implementation of on_fail is
        called.

        Args:
            record: The records.TestResultRecord object for the failed test
                    case.
        """
        self.consecutive_failures += 1
        if record.details:
            self.log.error(record.details)
        self.log.info(RESULT_LINE_TEMPLATE, record.test_name, record.result)
        self.on_fail(record.test_name, record.begin_time)

    def on_fail(self, test_name, begin_time):
        """A function that is executed upon a test case failure.

        User implementation is optional.

        Args:
            test_name: Name of the test that triggered this function.
            begin_time: Logline format timestamp taken when the test started.
        """

    def _on_pass(self, record):
        """Proxy function to guarantee the base implementation of on_pass is
        called.

        Args:
            record: The records.TestResultRecord object for the passed test
                    case.
        """
        self.consecutive_failures = 0
        msg = record.details
        if msg:
            self.log.info(msg)
        self.log.info(RESULT_LINE_TEMPLATE, record.test_name, record.result)
        self.on_pass(record.test_name, record.begin_time)

    def on_pass(self, test_name, begin_time):
        """A function that is executed upon a test case passing.

        Implementation is optional.

        Args:
            test_name: Name of the test that triggered this function.
            begin_time: Logline format timestamp taken when the test started.
        """

    def _on_skip(self, record):
        """Proxy function to guarantee the base implementation of on_skip is
        called.

        Args:
            record: The records.TestResultRecord object for the skipped test
                    case.
        """
        self.log.info(RESULT_LINE_TEMPLATE, record.test_name, record.result)
        self.log.info("Reason to skip: %s", record.details)
        self.on_skip(record.test_name, record.begin_time)

    def on_skip(self, test_name, begin_time):
        """A function that is executed upon a test case being skipped.

        Implementation is optional.

        Args:
            test_name: Name of the test that triggered this function.
            begin_time: Logline format timestamp taken when the test started.
        """

    def _on_exception(self, record):
        """Proxy function to guarantee the base implementation of on_exception
        is called.

        Args:
            record: The records.TestResultRecord object for the failed test
                    case.
        """
        self.log.exception(record.details)
        self.on_exception(record.test_name, record.begin_time)

    def on_exception(self, test_name, begin_time):
        """A function that is executed upon an unhandled exception from a test
        case.

        Implementation is optional.

        Args:
            test_name: Name of the test that triggered this function.
            begin_time: Logline format timestamp taken when the test started.
        """

    def _exec_procedure_func(self, func, tr_record):
        """Executes a procedure function like on_pass, on_fail etc.

        This function will alternate the 'Result' of the test's record if
        exceptions happened when executing the procedure function.

        This will let signals.TestAbortAll through so abort_all works in all
        procedure functions.

        Args:
            func: The procedure function to be executed.
            tr_record: The TestResultRecord object associated with the test
                       case executed.
        """
        try:
            func(tr_record)
        except signals.TestAbortAll:
            raise
        except Exception as e:
            self.log.exception("Exception happened when executing %s for %s.",
                               func.__name__, self.current_test_name)
            tr_record.add_error(func.__name__, e)

    def exec_one_testcase(self, test_name, test_func, args, **kwargs):
        """Executes one test case and update test results.

        Executes one test case, create a records.TestResultRecord object with
        the execution information, and add the record to the test class's test
        results.

        Args:
            test_name: Name of the test.
            test_func: The test function.
            args: A tuple of params.
            kwargs: Extra kwargs.
        """
        class_name = self.__class__.__name__
        tr_record = records.TestResultRecord(test_name, class_name)
        tr_record.test_begin()
        self.begin_time = int(tr_record.begin_time)
        self.log_begin_time = tr_record.log_begin_time
        self.test_name = tr_record.test_name
        event_bus.post(TestCaseBeginEvent(self, self.test_name))
        self.log.info("%s %s", TEST_CASE_TOKEN, test_name)

        # Enable test retry if specified in the ACTS config
        retry_tests = self.user_params.get('retry_tests', [])
        full_test_name = '%s.%s' % (class_name, self.test_name)
        if any(name in retry_tests for name in [class_name, full_test_name]):
            test_func = self.get_func_with_retry(test_func)

        verdict = None
        test_signal = None
        try:
            try:
                ret = self._setup_test(self.test_name)
                asserts.assert_true(ret is not False,
                                    "Setup for %s failed." % test_name)
                if args or kwargs:
                    verdict = test_func(*args, **kwargs)
                else:
                    verdict = test_func()

            finally:
                try:
                    self._teardown_test(self.test_name)
                except signals.TestAbortAll:
                    raise
                except Exception as e:
                    self.log.error(traceback.format_exc())
                    tr_record.add_error("teardown_test", e)
                    self._exec_procedure_func(self._on_exception, tr_record)
        except (signals.TestFailure, AssertionError) as e:
            test_signal = e
            if self.user_params.get(
                    keys.Config.key_test_failure_tracebacks.value, False):
                self.log.exception(e)
            tr_record.test_fail(e)
            self._exec_procedure_func(self._on_fail, tr_record)
        except signals.TestSkip as e:
            # Test skipped.
            test_signal = e
            tr_record.test_skip(e)
            self._exec_procedure_func(self._on_skip, tr_record)
        except (signals.TestAbortClass, signals.TestAbortAll) as e:
            # Abort signals, pass along.
            test_signal = e
            tr_record.test_fail(e)
            self._exec_procedure_func(self._on_fail, tr_record)
            raise e
        except signals.TestPass as e:
            # Explicit test pass.
            test_signal = e
            tr_record.test_pass(e)
            self._exec_procedure_func(self._on_pass, tr_record)
        except error.ActsError as e:
            test_signal = e
            tr_record.test_error(e)
            self.log.error(
                'BaseTest execute_one_test_case error: %s' % e.message)
        except Exception as e:
            test_signal = e
            self.log.error(traceback.format_exc())
            # Exception happened during test.
            tr_record.test_error(e)
            self._exec_procedure_func(self._on_exception, tr_record)
            self._exec_procedure_func(self._on_fail, tr_record)
        else:
            if verdict or (verdict is None):
                # Test passed.
                tr_record.test_pass()
                self._exec_procedure_func(self._on_pass, tr_record)
                return
            tr_record.test_fail()
            self._exec_procedure_func(self._on_fail, tr_record)
        finally:
            self.results.add_record(tr_record)
            self.summary_writer.dump(
                tr_record.to_dict(), records.TestSummaryEntryType.RECORD)
            self.current_test_name = None
            event_bus.post(TestCaseEndEvent(self, self.test_name, test_signal))

    def get_func_with_retry(self, func, attempts=2):
        """Returns a wrapped test method that re-runs after failure. Return test
        result upon success. If attempt limit reached, collect all failure
        messages and raise a TestFailure signal.

        Params:
            func: The test method
            attempts: Number of attempts to run test

        Returns: result of the test method
        """
        def wrapper(*args, **kwargs):
            error_msgs = []
            extras = {}
            retry = False
            for i in range(attempts):
                try:
                    if retry:
                        self.teardown_test()
                        self.setup_test()
                    return func(*args, **kwargs)
                except signals.TestFailure as e:
                    retry = True
                    msg = 'Failure on attempt %d: %s' % (i+1, e.details)
                    self.log.warning(msg)
                    error_msgs.append(msg)
                    if e.extras:
                        extras['Attempt %d' % (i+1)] = e.extras
            raise signals.TestFailure('\n'.join(error_msgs), extras)

        return wrapper

    def run_generated_testcases(self,
                                test_func,
                                settings,
                                args=None,
                                kwargs=None,
                                tag="",
                                name_func=None,
                                format_args=False):
        """Runs generated test cases.

        Generated test cases are not written down as functions, but as a list
        of parameter sets. This way we reduce code repetition and improve
        test case scalability.

        Args:
            test_func: The common logic shared by all these generated test
                       cases. This function should take at least one argument,
                       which is a parameter set.
            settings: A list of strings representing parameter sets. These are
                      usually json strings that get loaded in the test_func.
            args: Iterable of additional position args to be passed to
                  test_func.
            kwargs: Dict of additional keyword args to be passed to test_func
            tag: Name of this group of generated test cases. Ignored if
                 name_func is provided and operates properly.
            name_func: A function that takes a test setting and generates a
                       proper test name. The test name should be shorter than
                       utils.MAX_FILENAME_LEN. Names over the limit will be
                       truncated.
            format_args: If True, args will be appended as the first argument
                         in the args list passed to test_func.

        Returns:
            A list of settings that did not pass.
        """
        args = args or ()
        kwargs = kwargs or {}
        failed_settings = []

        for setting in settings:
            test_name = "{} {}".format(tag, setting)

            if name_func:
                try:
                    test_name = name_func(setting, *args, **kwargs)
                except:
                    self.log.exception(("Failed to get test name from "
                                        "test_func. Fall back to default %s"),
                                       test_name)

            self.results.requested.append(test_name)

            if len(test_name) > utils.MAX_FILENAME_LEN:
                test_name = test_name[:utils.MAX_FILENAME_LEN]

            previous_success_cnt = len(self.results.passed)

            if format_args:
                self.exec_one_testcase(test_name, test_func,
                                       args + (setting,), **kwargs)
            else:
                self.exec_one_testcase(test_name, test_func,
                                       (setting,) + args, **kwargs)

            if len(self.results.passed) - previous_success_cnt != 1:
                failed_settings.append(setting)

        return failed_settings

    def _exec_func(self, func, *args):
        """Executes a function with exception safeguard.

        This will let signals.TestAbortAll through so abort_all works in all
        procedure functions.

        Args:
            func: Function to be executed.
            args: Arguments to be passed to the function.

        Returns:
            Whatever the function returns, or False if unhandled exception
            occured.
        """
        try:
            return func(*args)
        except signals.TestAbortAll:
            raise
        except:
            self.log.exception("Exception happened when executing %s in %s.",
                               func.__name__, self.TAG)
            return False

    def _get_all_test_names(self):
        """Finds all the function names that match the test case naming
        convention in this class.

        Returns:
            A list of strings, each is a test case name.
        """
        test_names = []
        for name in dir(self):
            if name.startswith("test_"):
                test_names.append(name)
        return test_names

    def _get_test_funcs(self, test_names):
        """Obtain the actual functions of test cases based on test names.

        Args:
            test_names: A list of strings, each string is a test case name.

        Returns:
            A list of tuples of (string, function). String is the test case
            name, function is the actual test case function.

        Raises:
            Error is raised if the test name does not follow
            naming convention "test_*". This can only be caused by user input
            here.
        """
        test_funcs = []
        for test_name in test_names:
            test_funcs.append(self._get_test_func(test_name))

        return test_funcs

    def _get_test_func(self, test_name):
        """Obtain the actual function of test cases based on the test name.

        Args:
            test_name: String, The name of the test.

        Returns:
            A tuples of (string, function). String is the test case
            name, function is the actual test case function.

        Raises:
            Error is raised if the test name does not follow
            naming convention "test_*". This can only be caused by user input
            here.
        """
        if not test_name.startswith("test_"):
            raise Error(("Test case name %s does not follow naming "
                         "convention test_*, abort.") % test_name)
        try:
            return test_name, getattr(self, test_name)
        except:

            def test_skip_func(*args, **kwargs):
                raise signals.TestSkip("Test %s does not exist" % test_name)

            self.log.info("Test case %s not found in %s.", test_name, self.TAG)
            return test_name, test_skip_func

    def _block_all_test_cases(self, tests, reason='Failed class setup'):
        """
        Block all passed in test cases.
        Args:
            tests: The tests to block.
            reason: Message describing the reason that the tests are blocked.
                Default is 'Failed class setup'
        """
        for test_name, test_func in tests:
            signal = signals.TestError(reason)
            record = records.TestResultRecord(test_name, self.TAG)
            record.test_begin()
            if hasattr(test_func, 'gather'):
                signal.extras = test_func.gather()
            record.test_error(signal)
            self.results.add_record(record)
            self.summary_writer.dump(
                record.to_dict(), records.TestSummaryEntryType.RECORD)
            self._on_skip(record)

    def run(self, test_names=None, test_case_iterations=1):
        """Runs test cases within a test class by the order they appear in the
        execution list.

        One of these test cases lists will be executed, shown here in priority
        order:
        1. The test_names list, which is passed from cmd line.
        2. The self.tests list defined in test class. Invalid names are
           ignored.
        3. All function that matches test case naming convention in the test
           class.

        Args:
            test_names: A list of string that are test case names/patterns
             requested in cmd line.

        Returns:
            The test results object of this class.
        """
        self.register_test_class_event_subscriptions()
        self.log.info("==========> %s <==========", self.TAG)
        # Devise the actual test cases to run in the test class.
        if self.tests:
            # Specified by run list in class.
            valid_tests = list(self.tests)
        else:
            # No test case specified by user, execute all in the test class
            valid_tests = self._get_all_test_names()
        if test_names:
            # Match test cases with any of the user-specified patterns
            matches = []
            for test_name in test_names:
                for valid_test in valid_tests:
                    if (fnmatch.fnmatch(valid_test, test_name)
                            and valid_test not in matches):
                        matches.append(valid_test)
        else:
            matches = valid_tests
        self.results.requested = matches
        self.summary_writer.dump(self.results.requested_test_names_dict(),
                                 records.TestSummaryEntryType.TEST_NAME_LIST)
        tests = self._get_test_funcs(matches)

        # Setup for the class.
        setup_fail = False
        try:
            if self._setup_class() is False:
                self.log.error("Failed to setup %s.", self.TAG)
                self._block_all_test_cases(tests)
                setup_fail = True
        except signals.TestAbortClass:
            try:
                self._exec_func(self._teardown_class)
            except Exception as e:
                self.log.warning(e)
            setup_fail = True
        except Exception as e:
            self.log.exception("Failed to setup %s.", self.TAG)
            self._block_all_test_cases(tests)
            self._exec_func(self._teardown_class)
            setup_fail = True
        if setup_fail:
            self.log.info("Summary for test class %s: %s", self.TAG,
                          self.results.summary_str())
            return self.results

        # Run tests in order.
        try:
            for test_name, test_func in tests:
                for _ in range(test_case_iterations):
                    self.exec_one_testcase(test_name, test_func, self.cli_args)
            return self.results
        except signals.TestAbortClass:
            return self.results
        except signals.TestAbortAll as e:
            # Piggy-back test results on this exception object so we don't lose
            # results from this test class.
            setattr(e, "results", self.results)
            raise e
        finally:
            self._exec_func(self._teardown_class)
            self.log.info("Summary for test class %s: %s", self.TAG,
                          self.results.summary_str())

    def clean_up(self):
        """A function that is executed upon completion of all tests cases
        selected in the test class.

        This function should clean up objects initialized in the constructor by
        user.
        """

    def _ad_take_bugreport(self, ad, test_name, begin_time):
        for i in range(3):
            try:
                ad.take_bug_report(test_name, begin_time)
                return True
            except Exception as e:
                ad.log.error("bugreport attempt %s error: %s", i + 1, e)

    def _ad_take_extra_logs(self, ad, test_name, begin_time):
        result = True
        if getattr(ad, "qxdm_log", False):
            # Gather qxdm log modified 3 minutes earlier than test start time
            if begin_time:
                qxdm_begin_time = begin_time - 1000 * 60 * 3
            else:
                qxdm_begin_time = None
            try:
                ad.get_qxdm_logs(test_name, qxdm_begin_time)
            except Exception as e:
                ad.log.error("Failed to get QXDM log for %s with error %s",
                             test_name, e)
                result = False

        try:
            ad.check_crash_report(test_name, begin_time, log_crash_report=True)
        except Exception as e:
            ad.log.error("Failed to check crash report for %s with error %s",
                         test_name, e)
            result = False
        return result

    def _skip_bug_report(self):
        """A function to check whether we should skip creating a bug report.

        Returns: True if bug report is to be skipped.
        """
        if "no_bug_report_on_fail" in self.user_params:
            return True

        # If the current test class or test case is found in the set of
        # problematic tests, we skip bugreport and other failure artifact
        # creation.
        class_name = self.__class__.__name__
        quiet_tests = self.user_params.get('quiet_tests', [])
        if class_name in quiet_tests:
            self.log.info(
                "Skipping bug report, as directed for this test class.")
            return True
        full_test_name = '%s.%s' % (class_name, self.test_name)
        if full_test_name in quiet_tests:
            self.log.info(
                "Skipping bug report, as directed for this test case.")
            return True

        # Once we hit a certain log path size, it's not going to get smaller.
        # We cache the result so we don't have to keep doing directory walks.
        if self.size_limit_reached:
            return True
        try:
            max_log_size = int(
                self.user_params.get("soft_output_size_limit") or "invalid")
            log_path = getattr(logging, "log_path", None)
            if log_path:
                curr_log_size = utils.get_directory_size(log_path)
                if curr_log_size > max_log_size:
                    self.log.info(
                        "Skipping bug report, as we've reached the size limit."
                    )
                    self.size_limit_reached = True
                    return True
        except ValueError:
            pass
        return False

    def _take_bug_report(self, test_name, begin_time):
        if self._skip_bug_report():
            return

        executor = ThreadPoolExecutor(max_workers=10)
        for ad in getattr(self, 'android_devices', []):
            executor.submit(self._ad_take_bugreport, ad, test_name, begin_time)
            executor.submit(self._ad_take_extra_logs, ad, test_name,
                            begin_time)
        executor.shutdown()

    def _reboot_device(self, ad):
        ad.log.info("Rebooting device.")
        ad = ad.reboot()

    def _cleanup_logger_sessions(self):
        for (mylogger, session) in self.logger_sessions:
            self.log.info("Resetting a diagnostic session %s, %s", mylogger,
                          session)
            mylogger.reset()
        self.logger_sessions = []

    def _pull_diag_logs(self, test_name, begin_time):
        for (mylogger, session) in self.logger_sessions:
            self.log.info("Pulling diagnostic session %s", mylogger)
            mylogger.stop(session)
            diag_path = os.path.join(
                self.log_path, logger.epoch_to_log_line_timestamp(begin_time))
            utils.create_dir(diag_path)
            mylogger.pull(session, diag_path)

    def register_test_class_event_subscriptions(self):
        self.class_subscriptions = subscription_bundle.create_from_instance(
            self)
        self.class_subscriptions.register()

    def unregister_test_class_event_subscriptions(self):
        for package in self.all_subscriptions:
            package.unregister()
