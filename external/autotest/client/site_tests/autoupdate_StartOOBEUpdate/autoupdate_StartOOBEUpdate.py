# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chrome
from autotest_lib.client.cros.cellular import test_environment
from autotest_lib.client.cros.update_engine import nano_omaha_devserver
from autotest_lib.client.cros.update_engine import update_engine_test

class autoupdate_StartOOBEUpdate(update_engine_test.UpdateEngineTest):
    """Starts a forced update at OOBE.

    Chrome OS will restart when the update is complete so this test will just
    start the update. The rest of the processing will be done in a server
    side test.
    """
    version = 1


    def initialize(self):
        """Test setup."""
        super(autoupdate_StartOOBEUpdate, self).initialize()
        self._clear_custom_lsb_release()


    def _skip_to_oobe_update_screen(self):
        """Skips to the OOBE update check screen."""
        self._oobe.WaitForJavaScriptCondition("typeof Oobe == 'function' && "
                                              "Oobe.readyForTesting",
                                              timeout=30)
        self._oobe.ExecuteJavaScript('Oobe.skipToUpdateForTesting()')


    def _start_oobe_update(self, url):
        """
        Jump to the update check screen at OOBE and wait for update to start.

        @param url: The omaha update URL we expect to call.

        """
        self._create_custom_lsb_release(url)
        # Start chrome instance to interact with OOBE.
        self._chrome = chrome.Chrome(auto_login=False)
        self._oobe = self._chrome.browser.oobe

        self._skip_to_oobe_update_screen()
        try:
            utils.poll_for_condition(self._is_update_started,
                                     error.TestFail('Update did not start.'),
                                     timeout=180)
        except error.TestFail as e:
            if self._critical_update:
                raise e


    def run_once(self, image_url, cellular=False, payload_info=None,
                 full_payload=True, critical_update=True):
        """
        Test that will start a forced update at OOBE.

        @param image_url: The omaha URL to call. It contains the payload url
                          for cellular tests.
        @param cellular: True if we should run this test using a sim card.
        @param payload_info: For cellular tests we need to have our own omaha
                             instance and this is a dictionary of payload
                             information to be used in the omaha response.
        @param full_payload: True for full payloads, False for delta.
        @param critical_update: True if we should have deadline:now in omaha
                                response.

        """
        self._critical_update = critical_update

        if cellular:
            try:
                test_env = test_environment.CellularOTATestEnvironment()
                CONNECT_TIMEOUT = 120
                with test_env:
                    service = test_env.shill.wait_for_cellular_service_object()
                    if not service:
                        raise error.TestError('No cellular service found.')
                    test_env.shill.connect_service_synchronous(
                        service, CONNECT_TIMEOUT)

                    # Setup an omaha instance on the DUT because we cant reach
                    # devservers over cellular.
                    self._omaha = nano_omaha_devserver.NanoOmahaDevserver()
                    self._omaha.set_image_params(image_url,
                                                 payload_info['size'],
                                                 payload_info['sha256'],
                                                 is_delta=not full_payload,
                                                 critical=self._critical_update)
                    self._omaha.start()
                    self._start_oobe_update(self._omaha.get_update_url())

                    # Remove the custom omaha server from lsb release because
                    # after we reboot it will no longer be running.
                    self._clear_custom_lsb_release()

                    # We need to return from the client test before OOBE
                    # reboots or the server side test will hang. But we cannot
                    # return right away when the OOBE update starts because
                    # all of the code from using a cellular connection is in
                    # client side and we will switch back to ethernet. So we
                    # need to wait for the update to get as close to the end as
                    # possible so that we are done downloading the payload
                    # via cellular and don't  need to ping omaha again. When
                    # the DUT reboots it will send a final update ping to
                    # production omaha and then move to the sign in screen.
                    self._wait_for_update_to_complete(finalizing_ok=True)
            except error.TestError as e:
                logging.error('Failure setting up sim card.')
                raise error.TestFail(e)

        else:
            self._start_oobe_update(image_url)


