# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import time

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.server import autotest, test
from autotest_lib.server.cros.faft.firmware_test import FirmwareTest


class firmware_Cr50Unlock(FirmwareTest):
    """Verify cr50 unlock.

    Enable the lock on cr50, run 'lock disable', and then press the power
    button until it is unlocked.
    """
    version = 1

    ACCESS_DENIED = 'Access Denied'


    def initialize(self, host, cmdline_args):
        super(firmware_Cr50Unlock, self).initialize(host, cmdline_args)
        if not hasattr(self, 'cr50'):
            raise error.TestNAError('Test can only be run on devices with '
                                    'access to the Cr50 console')
        if self.cr50.using_ccd():
            raise error.TestNAError('Use a flex cable instead of CCD cable.')


    def run_once(self):
        # Enable the lock
        rv = self.cr50.send_command_get_output('lock dummy', ['[\w\s]+'])
        # Certain prod images are permanently locked out. We can't do anything
        # on these images.
        if self.ACCESS_DENIED in rv[0]:
            raise error.TestNAError('Cr50 image is permanently locked.')
        self.cr50.lock_enable()
        self.cr50.lock_disable()

