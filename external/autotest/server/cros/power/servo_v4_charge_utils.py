# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Helper class for managing charging the DUT with Servo v4."""

import logging

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import retry

# Base delay time in seconds for Servo role change and PD negotiation.
_DELAY_SEC = .1
# Total delay time in minutes for Servo role change and PD negotiation.
_TIMEOUT_MIN = 1
# Exponential backoff for Servo role change and PD negotiation.
_BACKOFF = 2


class ServoV4ChargeManager(object):
    """A helper class for managing charging the DUT with Servo v4."""

    def __init__(self, host, servo):
        """Check for correct Servo setup.

        Make sure that Servo is v4 and can manage charging. Make sure that DUT
        responds to Servo charging commands. Restore Servo v4 power role after
        sanity check.

        @param host: CrosHost object representing the DUT.
        @param servo: a proxy for servod.
        """
        super(ServoV4ChargeManager, self).__init__()
        self._host = host
        self._servo = servo
        self._original_role = self._servo.get('servo_v4_role')

        self._verify_v4()
        if self._original_role == 'snk':
            self.start_charging()
            self.stop_charging()
        elif self._original_role == 'src':
            self.stop_charging()
            self.start_charging()
        else:
            raise error.TestNAError('Unrecognized Servo v4 power role: %s.',
                                    self._original_role)

    def stop_charging(self):
        """Cut off AC power supply to DUT with Servo."""
        self._change_role('snk')

    def start_charging(self):
        """Connect AC power supply to DUT with Servo."""
        self._change_role('src')

    def restore_original_setting(self):
        """Restore Servo to original charging setting."""
        self._change_role(self._original_role)

    def _verify_v4(self):
        """Verify that Servo is Servo v4."""
        if self._servo.get_servo_version().startswith('servo_v4'):
            servo_v4_version = self._servo.get('servo_v4_version')
            logging.info('Servo v4 version: %s', servo_v4_version)
        else:
            raise error.TestNAError('This test needs to run with Servo v4. '
                                    'Test skipped.')

    def _change_role(self, role):
        """Change Servo PD role and check if DUT responded accordingly.

        @param role: string 'src' or 'snk'. If 'src' connect DUT to AC power; if
                     'snk' disconnect DUT from AC power.
        """
        self._servo.set('servo_v4_role', role)

        @retry.retry(error.TestError, timeout_min=_TIMEOUT_MIN,
                     delay_sec=_DELAY_SEC, backoff=_BACKOFF)
        def check_servo_role(role):
            """Check if servo role is as expected, if not, retry."""
            if self._servo.get('servo_v4_role') != role:
                raise error.TestError('Servo v4 failed to set its PD role to '
                                      '%s.' % role)
        check_servo_role(role)

        connected = True if role == 'src' else False
        @retry.retry(error.TestError, timeout_min=_TIMEOUT_MIN,
                     delay_sec=_DELAY_SEC, backoff=_BACKOFF)
        def check_host_ac(connected):
            """Check if DUT AC power is as expected, if not, retry."""
            if self._host.is_ac_connected() != connected:
                raise error.TestError('DUT failed to %s AC power.' % (
                        'connect' if connected else 'disconnect'))
        check_host_ac(connected)
