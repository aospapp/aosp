# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import json
import logging
import time

from autotest_lib.client.bin import test
from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chrome
from autotest_lib.client.cros import cryptohome
from autotest_lib.client.cros.input_playback import input_playback


class desktopui_CheckRlzPingSent(test.test):
    """Tests creating a new user, doing a google search, checking RLZ Data."""
    version = 1

    _RLZ_DATA_FILE = "/home/chronos/user/RLZ Data"

    def _verify_rlz_data(self):
        """Checks that the CAF event is in the RLZ Data file."""
        def rlz_data_exists():
            """Check rlz data exists."""
            rlz_data = json.loads(utils.run('cat "%s"' %
                                            self._RLZ_DATA_FILE).stdout)
            logging.debug('rlz data: %s', rlz_data)
            if 'stateful_events' in rlz_data:
                return 'CAF' in rlz_data['stateful_events']['C']['_']
            return False

        utils.poll_for_condition(rlz_data_exists, timeout=120)


    def _check_url_for_rlz(self, cr):
        """
        Does a Google search and ensures there is an rlz parameter.

        @param cr: Chrome instance.

        """
        timeout_minutes = 2
        timeout = time.time() + 60 * timeout_minutes

        # Setup a keyboard emulator to open new tabs and type a search.
        with input_playback.InputPlayback() as player:
            player.emulate(input_type='keyboard')
            player.find_connected_inputs()

            while True:
                # Open a new tab, search in the omnibox.
                player.blocking_playback_of_default_file(
                    input_type='keyboard', filename='keyboard_ctrl+t')
                player.blocking_playback_of_default_file(
                    input_type='keyboard', filename='keyboard_b+a+d+enter')
                logging.info(cr.browser.tabs[-1].url)
                if 'rlz=' in cr.browser.tabs[-1].url:
                    break
                else:
                    if time.time() > timeout:
                        raise error.TestFail('RLZ ping did not send in %d '
                                             'minutes.' % timeout_minutes)
                    time.sleep(10)


    def _wait_for_rlz_lock(self):
        """Waits for the DUT to get into locked state after login."""
        def get_install_lockbox_finalized_status():
            status = cryptohome.get_tpm_more_status()
            return status.get('install_lockbox_finalized')

        try:
            utils.poll_for_condition(
                lambda: get_install_lockbox_finalized_status(),
                exception=utils.TimeoutError(),
                timeout=120)
        except utils.TimeoutError:
            raise error.TestFail('Timed out trying to lock the device')


    def run_once(self, logged_in=True):
        """
        Main entry to the test.

        @param logged_in: True for real login or guest mode.

        """
        # If we are testing the ping is sent in guest mode (logged_in=False),
        # we need to first do a real login and wait for the DUT to become
        # 'locked' for rlz. Then logout and enter guest mode.
        if not logged_in:
            with chrome.Chrome(logged_in=True):
                self._wait_for_rlz_lock()

        with chrome.Chrome(logged_in=logged_in) as cr:
            self._check_url_for_rlz(cr)
            self._verify_rlz_data()
