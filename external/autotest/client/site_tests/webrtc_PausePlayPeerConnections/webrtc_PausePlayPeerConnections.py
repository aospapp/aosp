# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os

from autotest_lib.client.bin import test
from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chrome
from autotest_lib.client.cros.video import helper_logger

EXTRA_BROWSER_ARGS = ['--use-fake-ui-for-media-stream',
                      '--use-fake-device-for-media-stream']

# Polling timeout.
TIMEOUT = 70

# The test's runtime.
TEST_RUNTIME_SECONDS = 60

# Number of peer connections to use.
NUM_PEER_CONNECTIONS = 10

# Delay between each iteration of pausing/playing the feeds.
PAUSE_PLAY_ITERATION_DELAY_MILLIS = 20;


class webrtc_PausePlayPeerConnections(test.test):
    """Tests many peerconnections randomly paused and played."""
    version = 1

    def start_test(self, cr, element_type):
        """Opens the WebRTC pause-play page.

        @param cr: Autotest Chrome instance.
        @param element_type: 'video' or 'audio'. String.
        """
        cr.browser.platform.SetHTTPServerDirectories(self.bindir)

        self.tab = cr.browser.tabs[0]
        self.tab.Navigate(cr.browser.platform.http_server.UrlOf(
                os.path.join(self.bindir, 'pause-play.html')))
        self.tab.WaitForDocumentReadyStateToBeComplete()
        self.tab.EvaluateJavaScript(
                "startTest(%d, %d, %d, %s)" % (
                        TEST_RUNTIME_SECONDS,
                        NUM_PEER_CONNECTIONS,
                        PAUSE_PLAY_ITERATION_DELAY_MILLIS,
                        element_type))

    def wait_test_completed(self, timeout_secs):
        """Waits until the test is done.

        @param timeout_secs Max time to wait in seconds.

        @raises TestError on timeout, or javascript eval fails.
        """
        def _test_done():
            status = self.tab.EvaluateJavaScript('testRunner.getStatus()')
            logging.debug(status)
            return status == 'ok-done'

        utils.poll_for_condition(
                _test_done, timeout=timeout_secs, sleep_interval=1,
                desc='pause-play.html reports itself as finished')

    @helper_logger.video_log_wrapper
    def run_once(self, element_type='video'):
        """Runs the test."""
        with chrome.Chrome(extra_browser_args = EXTRA_BROWSER_ARGS + \
                           [helper_logger.chrome_vmodule_flag()],
                           init_network_controller = True) as cr:
            self.start_test(cr, element_type)
            self.wait_test_completed(TIMEOUT)
            self.print_result()

    def print_result(self):
        """Prints results unless status is different from ok-done.

        @raises TestError if the test failed outright.
        """
        status = self.tab.EvaluateJavaScript('testRunner.getStatus()')
        if status != 'ok-done':
            raise error.TestFail('Failed: %s' % status)

        results = self.tab.EvaluateJavaScript('testRunner.getResults()')
        logging.info('runTimeSeconds: %.2f', results['runTimeSeconds'])

        self.output_perf_value(
                description='run_time',
                value=results['runTimeSeconds'],
                units='seconds');
