# Copyright 2015 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os
import re
import time

from autotest_lib.client.bin import test
from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chrome
from autotest_lib.client.cros.video import device_capability
from autotest_lib.client.cros.video import helper_logger

EXTRA_BROWSER_ARGS = ['--use-fake-ui-for-media-stream']

# Variables from the getusermedia.html page.
IS_TEST_DONE = 'isTestDone'
IS_VIDEO_INPUT_FOUND = 'isVideoInputFound'

# Polling timeout.
SEVERAL_MINUTES_IN_SECS = 240


class video_WebRtcCamera(test.test):
    """Local getUserMedia test with webcam at VGA (and 720p, if supported)."""
    version = 1

    def cleanup(self):
        """Autotest cleanup function

        It is run by common_lib/test.py.
        """
        if utils.is_virtual_machine():
            try:
                utils.run('sudo modprobe -r vivid')
            except Exception as e:
                raise error.TestFail('Failed to unload vivid', e)

    def start_getusermedia(self, cr):
        """Opens the test page.

        @param cr: Autotest Chrome instance.
        """
        cr.browser.platform.SetHTTPServerDirectories(self.bindir)

        self.tab = cr.browser.tabs[0]
        self.tab.Navigate(cr.browser.platform.http_server.UrlOf(
                os.path.join(self.bindir, 'getusermedia.html')))
        self.tab.WaitForDocumentReadyStateToBeComplete()

        if utils.is_virtual_machine():
            # Before calling 'getUserMedia()' again, make sure if Chrome has
            # already recognized vivid as an external camera.
            self.wait_camera_detected()

            # Reload the page to run 'getUserMedia()' again
            self.tab.EvaluateJavaScript('location.reload()')
            self.tab.WaitForDocumentReadyStateToBeComplete()

    def wait_camera_detected(self):
        """Waits until a camera is detected.
        """
        for _ in range(10):
            self.tab.EvaluateJavaScript('checkVideoInput()')
            if self.tab.EvaluateJavaScript(IS_VIDEO_INPUT_FOUND):
                return
            time.sleep(1)

        raise error.TestFail('Can not find video input device')

    def webcam_supports_720p(self):
        """Checks if 720p capture supported.

        @returns: True if 720p supported, false if VGA is supported.
        @raises: TestError if neither 720p nor VGA are supported.
        """
        cmd = 'lsusb -v'
        # Get usb devices and make output a string with no newline marker.
        usb_devices = utils.system_output(cmd, ignore_status=True).splitlines()
        usb_devices = ''.join(usb_devices)

        # Check if 720p resolution supported.
        if re.search(r'\s+wWidth\s+1280\s+wHeight\s+720', usb_devices):
            return True
        # The device should support at least VGA.
        # Otherwise the cam must be broken.
        if re.search(r'\s+wWidth\s+640\s+wHeight\s+480', usb_devices):
            return False
        # This should not happen.
        raise error.TestFail(
                'Could not find any cameras reporting '
                'either VGA or 720p in lsusb output: %s' % usb_devices)


    def wait_test_completed(self, timeout_secs):
        """Waits until the test is done.

        @param timeout_secs Max time to wait in seconds.

        @raises TestError on timeout, or javascript eval fails.
        """
        def _test_done():
            return self.tab.EvaluateJavaScript(IS_TEST_DONE)

        utils.poll_for_condition(
            _test_done, timeout=timeout_secs, sleep_interval=1,
            desc=('getusermedia.html:reportTestDone did not run. Test did not '
                  'complete successfully.'))

    @helper_logger.video_log_wrapper
    def run_once(self, capability):
        """Runs the test.

        @param capability: Capability required for executing this test.
        """
        device_capability.DeviceCapability().ensure_capability(capability)

        self.board = utils.get_current_board()
        with chrome.Chrome(extra_browser_args=EXTRA_BROWSER_ARGS +\
                           [helper_logger.chrome_vmodule_flag()],
                           init_network_controller=True) as cr:

            # TODO(keiichiw): vivid should be loaded in self.setup() after
            # crbug.com/871185 is fixed
            if utils.is_virtual_machine():
                try:
                    utils.run('sudo modprobe vivid n_devs=1 node_types=0x1')
                except Exception as e:
                    raise error.TestFail('Failed to load vivid', e)

            self.start_getusermedia(cr)
            self.print_perf_results()


    def print_perf_results(self):
        """Prints the perf results unless there was an error.

        @returns the empty string if perf results were printed, otherwise
                 a description of the error that occured.
        """
        self.wait_test_completed(SEVERAL_MINUTES_IN_SECS)
        try:
            results = self.tab.EvaluateJavaScript('getResults()')
        except Exception as e:
            raise error.TestFail('Failed to get getusermedia.html results', e)
        logging.info('Results: %s', results)

        errors = []
        for width_height, data in results.iteritems():
            resolution = re.sub(',', 'x', width_height)
            if data['cameraErrors']:
                if (resolution == '1280x720' and
                        not self.webcam_supports_720p()):
                    logging.info('Accepting 720p failure since device webcam '
                                 'does not support 720p')
                    continue

                # Else we had a VGA failure or a legit 720p failure.
                errors.append('Camera error: %s for resolution '
                            '%s.' % (data['cameraErrors'], resolution))
                continue
            if not data['frameStats']:
                errors.append('Frame Stats is empty '
                              'for resolution: %s' % resolution)
                continue

            total_num_frames = data['frameStats']['numFrames']
            num_black_frames = data['frameStats']['numBlackFrames']
            num_frozen_frames = data['frameStats']['numFrozenFrames']

            def _percent(num, total):
                if total == 0:
                    return 1.0
                return float(num) / float(total)

            self.output_perf_value(
                    description='black_frames_percentage_%s' % resolution,
                    value=_percent(num_black_frames, total_num_frames),
                    units='percent', higher_is_better=False)
            self.output_perf_value(
                    description='frozen_frames_percentage_%s' % resolution,
                    value=_percent(num_frozen_frames, total_num_frames),
                    units='percent', higher_is_better=False)

        if errors:
            raise error.TestFail('Found errors: %s' % ', '.join(errors))
