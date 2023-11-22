# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import tpm_utils
from autotest_lib.server import test, autotest
from autotest_lib.server.cros.faft.firmware_test import FirmwareTest


class firmware_FWMPDisableCCD(FirmwareTest):
    """A test that uses cryptohome to set the FWMP flags and verifies that
    cr50 disables/enables console unlock."""
    version = 1

    FWMP_DEV_DISABLE_CCD_UNLOCK = (1 << 6)


    def initialize(self, host, cmdline_args):
        """Initialize servo check if cr50 exists"""
        super(firmware_FWMPDisableCCD, self).initialize(host, cmdline_args)

        self.host = host
        self.test_cr50_unlock = hasattr(self, "cr50")

        if self.test_cr50_unlock:
            rv = self.cr50.send_command_get_output('lock dummy', ['.+>'])
            if 'Access Denied' in rv[0]:
                self.test_cr50_unlock = False
                logging.warning('Cr50 image is permanently locked.')


    def cr50_try_unlock(self, fwmp_disabled_unlock):
        """Run lock disable

        The FWMP flags may disable ccd. If they do then we expect lock disable
        to fail.

        @param fwmp_disabled_unlock: True if the unlock process should fail
        """
        if fwmp_disabled_unlock:
            if 'DBG' in self.servo.get('cr50_version'):
                response = self.cr50.FWMP_LOCKED_DBG
            else:
                response = self.cr50.FWMP_LOCKED_PROD
            self.cr50.send_command_get_output('lock disable', response)
        else:
            self.cr50.lock_disable()


    def cr50_check_fwmp_flag(self, fwmp_disabled_unlock):
        """Verify cr50 thinks the flag is set or cleared"""
        response = 'Console unlock%s allowed' % (' not' if fwmp_disabled_unlock
                                                 else '')
        self.cr50.send_command_get_output('sysrst pulse', [response])


    def cr50_check_lock_control(self, flags):
        """Verify cr50 lock enable/disable works as intended based on flags.

        If flags & self.FWMP_DEV_DISABLE_CCD_UNLOCK is true, lock disable should
        fail.

        This will only run during a test with access to the cr50  console

        @param flags: A string with the FWMP settings.
        """
        if not self.test_cr50_unlock:
            return

        fwmp_disabled_unlock = (self.FWMP_DEV_DISABLE_CCD_UNLOCK &
                               int(flags, 16))

        logging.info('Flags are set to %s ccd unlock is %s', flags,
                     'disabled' if fwmp_disabled_unlock else 'enabled')

        # Verify that the ccd disable flag is still set
        self.cr50_check_fwmp_flag(fwmp_disabled_unlock)

        # Enable the lock
        self.cr50.lock_enable()

        # Try to disable it
        self.cr50_try_unlock(fwmp_disabled_unlock)

        # Verify that the ccd disable flag is still set
        self.cr50_check_fwmp_flag(fwmp_disabled_unlock)


    def check_fwmp(self, flags, clear_tpm_owner):
        """Set the flags and verify ccd lock/unlock

        Args:
            flags: A string to used set the FWMP flags
            clear_tpm_owner: True if the TPM owner needs to be cleared before
                             setting the flags and verifying ccd lock/unlock
        """
        if clear_tpm_owner:
            logging.info('Clearing TPM owner')
            tpm_utils.ClearTPMOwnerRequest(self.host)

        logging.info('setting flags to %s', flags)
        autotest.Autotest(self.host).run_test('firmware_SetFWMP', flags=flags,
                fwmp_cleared=clear_tpm_owner, check_client_result=True)

        # Verify ccd lock/unlock with the current flags works as intended.
        self.cr50_check_lock_control(flags)


    def run_once(self):
        self.check_fwmp('0xaa00', True)
        # Verify that the flags can be changed on the same boot
        self.check_fwmp('0xbb00', False)

        # Verify setting FWMP_DEV_DISABLE_CCD_UNLOCK disables ccd
        self.check_fwmp(hex(self.FWMP_DEV_DISABLE_CCD_UNLOCK), True)

        # 0x41 is the flag setting when dev boot is disabled. Make sure that
        # nothing unexpected happens.
        self.check_fwmp('0x41', True)

        # Clear the TPM owner and verify lock can still be enabled/disabled when
        # the FWMP has not been created
        tpm_utils.ClearTPMOwnerRequest(self.host)
        self.cr50_check_lock_control('0')
