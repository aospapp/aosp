# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import tpm_utils
from autotest_lib.server import autotest
from autotest_lib.server.cros.faft.cr50_test import Cr50Test


class firmware_Cr50FactoryResetVC(Cr50Test):
    """A test verifying factory mode vendor command."""
    version = 1

    FWMP_DEV_DISABLE_CCD_UNLOCK = (1 << 6)
    # Short wait to make sure cr50 has had enough time to update the ccd state
    SLEEP = 2
    BOOL_VALUES = (True, False)

    def initialize(self, host, cmdline_args, full_args):
        """Initialize servo check if cr50 exists."""
        super(firmware_Cr50FactoryResetVC, self).initialize(host, cmdline_args,
                full_args)
        self.host = host
        self.fast_open(enable_testlab=True)
        # Run factory mode disable to make sure everything is reset.
        self.host.run('gsctool -a -F disable', ignore_status=True)

        # If we can set wp to off and on, then we can control write protect
        try:
            self.set_wp(True)
            self.set_wp(False)
        except:
            raise error.TestNAError('Cannot fully test factory mode vendor '
                    'command without control of write protect')


    def wp_enabled(self):
        """Returns True if write protect is enabled."""
        rv = self.cr50.send_command_get_output('gpioget',
                ['(0|1)..BATT_PRES_L'])
        logging.info(rv)
        return not int(rv[0][1])


    def set_wp(self, enable):
        """Deassert BATT_PRES signal, so cr50 will think wp is off."""
        self.cr50.send_command('ccd testlab open')
        # TODO(mruthven): come up with servo rework, so we can control batt_pres
        # directly.
        #
        # for now build a dbg image and connect BATT_PRES_L to DOIM4 and set it
        # as an output
        self.cr50.send_command('gpioset BATT_PRES_L %d' % (0 if enable else 1))
        if (not self.wp_enabled()) != (not enable):
            raise error.TestError('Could not %s write protect' %
                    ('set' if enable else 'clear'))
        self.cr50.set_ccd_level('lock')


    def fwmp_ccd_lockout(self):
        """Returns True if FWMP is locking out CCD."""
        return 'fwmp_lock' in self.cr50.get_ccd_info()['TPM']


    def set_fwmp_lockout(self, enable):
        """Change the FWMP to enable or disable ccd.

        Args:
            enable: True if FWMP flags should lock out ccd.
        """
        logging.info('%sing FWMP ccd lockout', 'enabl' if enable else 'clear')
        if enable:
            flags = hex(self.FWMP_DEV_DISABLE_CCD_UNLOCK)
            logging.info('Setting FWMP flags to %s', flags)
            autotest.Autotest(self.host).run_test('firmware_SetFWMP',
                    flags=flags, fwmp_cleared=True, check_client_result=True)

        if (not self.fwmp_ccd_lockout()) != (not enable):
            raise error.TestError('Could not %s fwmp lockout' %
                    ('set' if enable else 'clear'))


    def has_ccd_password(self):
        """Returns True if the ccd password is set."""
        return 'set' in self.cr50.get_ccd_info()['Password']


    def setup_ccd_password(self, set_password):
        """Set the Cr50 CCD password.

        Args:
            set_password: if True set the password. The password is already
                    cleared, so if False just check the password is cleared
        """
        if set_password:
            self.cr50.send_command('ccd testlab open')
            # Set the ccd password
            self.set_ccd_password('ccd_dummy_pw')
        if self.has_ccd_password() != set_password:
            raise error.TestError('Could not %s password' %
                    ('set' if set_password else 'clear'))


    def factory_mode_enabled(self):
        """Returns True if factory mode is enabled."""
        caps = self.cr50.get_cap_dict()
        caps.pop('GscFullConsole')
        return self.cr50.get_cap_overview(caps)[0]


    def get_relevant_state(self):
        """Returns cr50 factory mode check state.

        If any item in state is True, that means ccd factory mode should be
        locked out.
        """
        state = []
        state.append(self.fwmp_ccd_lockout())
        state.append(self.wp_enabled())
        state.append(self.has_ccd_password())
        return state

    def get_state_message(self):
        """Convert relevant state into a useful log message."""
        fwmp, wp, password = self.get_relevant_state()
        return 'fwmp %s wp %s password %s' % ('set' if fwmp else 'cleared',
                'enabled' if wp else 'disabled',
                'set' if password else 'cleared')

    def factory_locked_out(self):
        """Returns True if any state preventing factory mode is True."""
        return True in self.get_relevant_state()


    def set_factory_mode(self, enable):
        """Use the vendor command to control factory mode.

        Args:
            enable: Enable factory mode if True. Disable it if False.
        """
        enable_fail = self.factory_locked_out() and enable
        time.sleep(self.SLEEP)
        logging.info('%sABLING FACTORY MODE', 'EN' if enable else 'DIS')
        if enable:
            logging.info('EXPECT: %s', 'failure' if enable_fail else 'success')
        cmd = 'enable' if enable else 'disable'

        result = self.host.run('gsctool -a -F %s' % cmd,
                ignore_status=(enable_fail or not enable))
        logging.debug(result)
        expect_enabled = enable and not enable_fail

        time.sleep(self.SLEEP)
        if self.factory_mode_enabled() != expect_enabled:
            raise error.TestFail('Unexpected factory mode %s result' % cmd)


    def run_once(self):
        """Verify FWMP disable with different flag values."""
        errors = []
        # Try enabling factory mode in each valid state. Cr50 checks write
        # protect, password, and fwmp before allowing fwmp to be enabled.
        for lockout_ccd_with_fwmp in self.BOOL_VALUES:
            for set_password in self.BOOL_VALUES:
                for enable_wp in self.BOOL_VALUES:
                    # make sure all of the ccd stuff is reset
                    self.cr50.send_command('ccd testlab open')
                    # Run ccd reset to make sure all ccd state is cleared
                    self.cr50.send_command('ccd reset')
                    # Clear the TPM owner, so we can set the ccd password and
                    # create the FWMP
                    tpm_utils.ClearTPMOwnerRequest(self.host,
                            wait_for_ready=True)
                    self.setup_ccd_password(set_password)
                    self.set_wp(enable_wp)
                    self.set_fwmp_lockout(lockout_ccd_with_fwmp)
                    self.cr50.set_ccd_level('lock')
                    logging.info('RUN: %s', self.get_state_message())

                    try:
                        self.set_factory_mode(True)
                        self.set_factory_mode(False)
                    except Exception, e:
                        message = 'FAILURE %r %r' % (self.get_state_message(),
                                e)
                        logging.info(message)
                        errors.append(message)
        if errors:
            raise error.TestFail(errors)
