# Lint as: python2, python3
# Copyright 2015 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""A module providing common resources for different facades."""

import logging
import time

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chrome
from autotest_lib.client.common_lib.cros import retry
from autotest_lib.client.cros import constants
from telemetry.internal.backends.chrome_inspector import devtools_http

import py_utils

_FLAKY_CALL_RETRY_TIMEOUT_SEC = 60
_FLAKY_CHROME_CALL_RETRY_DELAY_SEC = 1

retry_chrome_call = retry.retry(
        (chrome.Error, IndexError, Exception),
        timeout_min=_FLAKY_CALL_RETRY_TIMEOUT_SEC / 60.0,
        delay_sec=_FLAKY_CHROME_CALL_RETRY_DELAY_SEC)


class FacadeResoureError(Exception):
    """Error in FacadeResource."""
    pass


_FLAKY_CHROME_START_RETRY_TIMEOUT_SEC = 120
_FLAKY_CHROME_START_RETRY_DELAY_SEC = 10


# Telemetry sometimes fails to start Chrome.
retry_start_chrome = retry.retry(
        (Exception,),
        timeout_min=_FLAKY_CHROME_START_RETRY_TIMEOUT_SEC / 60.0,
        delay_sec=_FLAKY_CHROME_START_RETRY_DELAY_SEC,
        exception_to_raise=FacadeResoureError,
        label='Start Chrome')


class FacadeResource(object):
    """This class provides access to telemetry chrome wrapper."""

    ARC_DISABLED = 'disabled'
    ARC_ENABLED = 'enabled'
    ARC_VERSION = 'CHROMEOS_ARC_VERSION'
    EXTRA_BROWSER_ARGS = ['--enable-gpu-benchmarking', '--use-fake-ui-for-media-stream']

    def __init__(self, chrome_object=None, restart=False):
        """Initializes a FacadeResource.

        @param chrome_object: A chrome.Chrome object or None.
        @param restart: Preserve the previous browser state.

        """
        self._chrome = chrome_object

    @property
    def _browser(self):
        """Gets the browser object from Chrome."""
        return self._chrome.browser


    @retry_start_chrome
    def _start_chrome(self, kwargs):
        """Start a Chrome with given arguments.

        @param kwargs: A dict of keyword arguments passed to Chrome.

        @return: A chrome.Chrome object.

        """
        logging.debug('Try to start Chrome with kwargs: %s', kwargs)
        return chrome.Chrome(**kwargs)


    def start_custom_chrome(self, kwargs):
        """Start a custom Chrome with given arguments.

        @param kwargs: A dict of keyword arguments passed to Chrome.

        @return: True on success, False otherwise.

        """
        # Close the previous Chrome.
        if self._chrome:
            self._chrome.close()

        # Start the new Chrome.
        try:
            self._chrome = self._start_chrome(kwargs)
        except FacadeResoureError:
            logging.error('Failed to start Chrome after retries')
            return False
        else:
            logging.info('Chrome started successfully')

        # The opened tabs are stored by tab descriptors.
        # Key is the tab descriptor string.
        # We use string as the key because of RPC Call. Client can use the
        # string to locate the tab object.
        # Value is the tab object.
        self._tabs = dict()

        # Workaround for issue crbug.com/588579.
        # On daisy, Chrome freezes about 30 seconds after login because of
        # TPM error. Avoid test accessing Chrome during this time.
        # Check issue crbug.com/588579 and crbug.com/591646.
        if utils.get_board() == 'daisy':
            logging.warning('Delay 30s for issue 588579 on daisy')
            time.sleep(30)

        return True


    def start_default_chrome(self, restart=False, extra_browser_args=None,
                             disable_arc=False):
        """Start the default Chrome.

        @param restart: True to start Chrome without clearing previous state.
        @param extra_browser_args: A list containing extra browser args passed
                                   to Chrome. This list will be appened to
                                   default EXTRA_BROWSER_ARGS.
        @param disable_arc: True to disable ARC++.

        @return: True on success, False otherwise.

        """
        # TODO: (crbug.com/618111) Add test driven switch for
        # supporting arc_mode enabled or disabled. At this time
        # if ARC build is tested, arc_mode is always enabled.
        if not disable_arc and utils.get_board_property(self.ARC_VERSION):
            arc_mode = self.ARC_ENABLED
        else:
            arc_mode = self.ARC_DISABLED
        kwargs = {
            'extension_paths': [constants.AUDIO_TEST_EXTENSION,
                                constants.DISPLAY_TEST_EXTENSION],
            'extra_browser_args': self.EXTRA_BROWSER_ARGS,
            'clear_enterprise_policy': not restart,
            'arc_mode': arc_mode,
            'autotest_ext': True
        }
        if extra_browser_args:
            kwargs['extra_browser_args'] += extra_browser_args
        return self.start_custom_chrome(kwargs)


    def __enter__(self):
        return self


    def __exit__(self, *args):
        if self._chrome:
            self._chrome.close()
            self._chrome = None


    @staticmethod
    def _generate_tab_descriptor(tab):
        """Generate tab descriptor by tab object.

        @param tab: the tab object.
        @return a str, the tab descriptor of the tab.

        """
        return hex(id(tab))


    def clean_unexpected_tabs(self):
        """Clean all tabs that are not opened by facade_resource

        It is used to make sure our chrome browser is clean.

        """
        # If they have the same length we can assume there is no unexpected
        # tabs.
        browser_tabs = self.get_tabs()
        if len(browser_tabs) == len(self._tabs):
            return

        for tab in browser_tabs:
            if self._generate_tab_descriptor(tab) not in self._tabs:
                # TODO(mojahsu): Reevaluate this code. crbug.com/719592
                try:
                    tab.Close()
                except py_utils.TimeoutException:
                    logging.warning('close tab timeout %r, %s', tab, tab.url)


    @retry_chrome_call
    def get_extension(self, extension_path=None):
        """Gets the extension from the indicated path.

        @param extension_path: the path of the target extension.
                               Set to None to get autotest extension.
                               Defaults to None.
        @return an extension object.

        @raise RuntimeError if the extension is not found.
        @raise chrome.Error if the found extension has not yet been
               retrieved succesfully.

        """
        try:
            if extension_path is None:
                extension = self._chrome.autotest_ext
            else:
                extension = self._chrome.get_extension(extension_path)
        except KeyError as errmsg:
            # Trigger retry_chrome_call to retry to retrieve the
            # found extension.
            raise chrome.Error(errmsg)
        if not extension:
            if extension_path is None:
                raise RuntimeError('Autotest extension not found')
            else:
                raise RuntimeError('Extension not found in %r'
                                    % extension_path)
        return extension


    def get_visible_notifications(self):
        """Gets the visible notifications

        @return: Returns all visible notifications in list format. Ex:
                [{title:'', message:'', prority:'', id:''}]
        """
        return self._chrome.get_visible_notifications()


    @retry_chrome_call
    def load_url(self, url):
        """Loads the given url in a new tab. The new tab will be active.

        @param url: The url to load as a string.
        @return a str, the tab descriptor of the opened tab.

        """
        tab = self._browser.tabs.New()
        tab.Navigate(url)
        tab.Activate()
        tab.WaitForDocumentReadyStateToBeComplete()
        tab_descriptor = self._generate_tab_descriptor(tab)
        self._tabs[tab_descriptor] = tab
        self.clean_unexpected_tabs()
        return tab_descriptor


    def set_http_server_directories(self, directories):
        """Starts an HTTP server.

        @param directories: Directories to start serving.

        @return True on success. False otherwise.

        """
        return self._chrome.browser.platform.SetHTTPServerDirectories(directories)


    def http_server_url_of(self, fullpath):
        """Converts a path to a URL.

        @param fullpath: String containing the full path to the content.

        @return the URL for the provided path.

        """
        return self._chrome.browser.platform.http_server.UrlOf(fullpath)


    def get_tabs(self):
        """Gets the tabs opened by browser.

        @returns: The tabs attribute in telemetry browser object.

        """
        return self._browser.tabs


    def get_tab_by_descriptor(self, tab_descriptor):
        """Gets the tab by the tab descriptor.

        @returns: The tab object indicated by the tab descriptor.

        """
        return self._tabs[tab_descriptor]


    @retry_chrome_call
    def close_tab(self, tab_descriptor):
        """Closes the tab.

        @param tab_descriptor: Indicate which tab to be closed.

        """
        if tab_descriptor not in self._tabs:
            raise RuntimeError('There is no tab for %s' % tab_descriptor)
        tab = self._tabs[tab_descriptor]
        del self._tabs[tab_descriptor]
        tab.Close()
        self.clean_unexpected_tabs()


    def wait_for_javascript_expression(
            self, tab_descriptor, expression, timeout):
        """Waits for the given JavaScript expression to be True on the given tab

        @param tab_descriptor: Indicate on which tab to wait for the expression.
        @param expression: Indiate for what expression to wait.
        @param timeout: Indicate the timeout of the expression.
        """
        if tab_descriptor not in self._tabs:
            raise RuntimeError('There is no tab for %s' % tab_descriptor)
        self._tabs[tab_descriptor].WaitForJavaScriptCondition(
                expression, timeout=timeout)


    def execute_javascript(self, tab_descriptor, statement, timeout):
        """Executes a JavaScript statement on the given tab.

        @param tab_descriptor: Indicate on which tab to execute the statement.
        @param statement: Indiate what statement to execute.
        @param timeout: Indicate the timeout of the statement.
        """
        if tab_descriptor not in self._tabs:
            raise RuntimeError('There is no tab for %s' % tab_descriptor)
        self._tabs[tab_descriptor].ExecuteJavaScript(
                statement, timeout=timeout)


    def evaluate_javascript(self, tab_descriptor, expression, timeout):
        """Evaluates a JavaScript expression on the given tab.

        @param tab_descriptor: Indicate on which tab to evaluate the expression.
        @param expression: Indiate what expression to evaluate.
        @param timeout: Indicate the timeout of the expression.
        @return the JSONized result of the given expression
        """
        if tab_descriptor not in self._tabs:
            raise RuntimeError('There is no tab for %s' % tab_descriptor)
        return self._tabs[tab_descriptor].EvaluateJavaScript(
                expression, timeout=timeout)

class Application(FacadeResource):
    """ This class provides access to WebStore Applications"""

    APP_NAME_IDS = {
        'camera' : 'njfbnohfdkmbmnjapinfcopialeghnmh',
        'files' : 'hhaomjibdihmijegdhdafkllkbggdgoj'
    }
    # Time in seconds to load the app
    LOAD_TIME = 5

    def __init__(self, chrome_object=None):
        super(Application, self).__init__(chrome_object)

    @retry_chrome_call
    def evaluate_javascript(self, code):
        """Executes javascript and returns some result.

        Occasionally calls to EvaluateJavascript on the autotest_ext will fail
        to find the extension. Instead of wrapping every call in a try/except,
        calls will go through this function instead.

        @param code: The javascript string to execute

        """
        try:
            result = self._chrome.autotest_ext.EvaluateJavaScript(code)
            return result
        except KeyError:
            logging.exception('Could not find autotest_ext')
        except (devtools_http.DevToolsClientUrlError,
                devtools_http.DevToolsClientConnectionError):
            logging.exception('Could not connect to DevTools')

        raise error.TestError("Could not execute %s" % code)

    def click_on(self, ui, name, isRegex=False, role=None):
        """
        Click on given role and name matches

        @ui: ui_utils object
        @param name: item node name.
        @param isRegex: If name is in regex format then isRegex should be
                        True otherwise False.
        @param role: role of the element. Example: button or window etc.
        @raise error.TestError if the test is failed to find given node
        """
        if not ui.item_present(name, isRegex=isRegex, role=role):
            raise error.TestError("name=%s, role=%s did not appeared with in "
                                 "time" % (name, role))
        ui.doDefault_on_obj(name, isRegex=isRegex, role=role)

    def is_app_opened(self, name):
        """
        Verify if the Webstore app is opened or not

        @param name: Name of the app to verify.

        """
        self.evaluate_javascript("var isShown = null;")
        is_app_shown_js = """
            chrome.autotestPrivate.isAppShown('%s',
            function(appShown){isShown = appShown});
            """ % self.APP_NAME_IDS[name.lower()]
        self.evaluate_javascript(is_app_shown_js)
        return self.evaluate_javascript('isShown')

    def launch_app(self, name):
        """
        Launch the app/extension by its ID and verify that it opens.

        @param name: Name of the app to launch.

        """
        logging.info("Launching %s app" % name)
        if name == "camera":
            webapps_js = "chrome.autotestPrivate.waitForSystemWebAppsInstall(" \
                     "function(){})"
            self.evaluate_javascript(webapps_js)
            launch_js = "chrome.autotestPrivate.launchSystemWebApp('%s', '%s', " \
                    "function(){})" % ("Camera",
                                       "chrome://camera-app/views/main.html")
        else:
            launch_js = "chrome.autotestPrivate.launchApp('%s', function(){})" \
                         % self.APP_NAME_IDS[name.lower()]
        self.evaluate_javascript(launch_js)
        def is_app_opened():
            return self.is_app_opened(name)
        utils.poll_for_condition(condition=is_app_opened,
                    desc="%s app is not launched" % name,
                    timeout=self.LOAD_TIME)
        logging.info('%s app is launched', name)

    def close_app(self, name):
        """
        Close the app/extension by its ID and verify that it closes.

        @param name: Name of the app to close.

        """
        close_js = "chrome.autotestPrivate.closeApp('%s', function(){})" \
                    % self.APP_NAME_IDS[name.lower()]
        self.evaluate_javascript(close_js)
        def is_app_closed():
            return not self.is_app_opened(name)
        utils.poll_for_condition(condition=is_app_closed,
                    desc="%s app is not closed" % name,
                    timeout=self.LOAD_TIME)
        logging.info('%s app is closed', name)
