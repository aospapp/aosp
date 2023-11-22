# Copyright 2016 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os

from PIL import Image

from autotest_lib.client.bin import test, utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chrome


_SCREENSHOT_PATH = '/tmp/screenshot.png'


class desktopui_MashLogin(test.test):
    """Verifies chrome --mash starts up and logs in correctly."""
    version = 1


    def __screen_visual_sanity_test(self):
        """Capture the screen and sanity check it (more than 5 colors)."""
        try:
            utils.run('screenshot "%s"' % _SCREENSHOT_PATH)
            image = Image.open(_SCREENSHOT_PATH)
        except Exception as e:
            logging.warning('Unable to capture screenshot. %s', e)
            return

        # If colors in |image| is less than _MAX_COLORS, PIL.Image.getcolors
        # returns a list of colors. If colors is more than _MAX_COLORS, it
        # returns None. Expect None because the login screen should contain
        # more than _MAX_COLORS.
        _MAX_COLORS = 5
        if image.getcolors(maxcolors=_MAX_COLORS) is not None:
            image.save(os.path.join(self.resultsdir,
                                    'bad_mash_login_screenshot.png'))
            raise error.TestFail('Mash login screen does not look right.')


    def run_once(self):
        """Entry point of this test."""

        # Flaky on nyan_* boards. http://crbug.com/717275
        boards_to_skip = ['nyan_big', 'nyan_kitty', 'nyan_blaze']
        if utils.get_current_board() in boards_to_skip:
          logging.warning('Skipping test run on this board.')
          return

        # GPU info collection via devtools SystemInfo.getInfo does not work
        # under mash due to differences in how the GPU process is configured
        # with mus hosting viz. http://crbug.com/669965
        mash_browser_args = ['--enable-features=Mash',
                             '--disable-features=SingleProcessMash',
                             '--gpu-no-complete-info-collection']

        logging.info('Testing Chrome with Mash startup.')
        with chrome.Chrome(auto_login=False, extra_browser_args=mash_browser_args):
            logging.info('Chrome with Mash started and loaded OOBE.')
            self.__screen_visual_sanity_test()

        logging.info('Testing Chrome with Mash login.')
        with chrome.Chrome(extra_browser_args=mash_browser_args):
            logging.info('Chrome login with Mash succeeded.')
