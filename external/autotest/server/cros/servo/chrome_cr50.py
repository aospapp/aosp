# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import functools
import logging
import time

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.server.cros.servo import chrome_ec


def ccd_command(func):
    """Decorator for methods only relevant to devices using CCD."""
    @functools.wraps(func)
    def wrapper(instance, *args, **kwargs):
        if instance.using_ccd():
            return func(instance, *args, **kwargs)
        logging.info("not using ccd. ignoring %s", func.func_name)
    return wrapper


class ChromeCr50(chrome_ec.ChromeConsole):
    """Manages control of a Chrome Cr50.

    We control the Chrome Cr50 via the console of a Servo board. Chrome Cr50
    provides many interfaces to set and get its behavior via console commands.
    This class is to abstract these interfaces.
    """
    IDLE_COUNT = 'count: (\d+)'
    VERSION_FORMAT = '\d+\.\d+\.\d+'
    VERSION_ERROR = 'Error'
    INACTIVE = '\nRW_(A|B): +(%s|%s)(/DBG|)?' % (VERSION_FORMAT, VERSION_ERROR)
    ACTIVE = '\nRW_(A|B): +\* +(%s)(/DBG|)?' % (VERSION_FORMAT)
    WAKE_CHAR = '\n'
    START_UNLOCK_TIMEOUT = 20
    GETTIME = ['= (\S+)']
    UNLOCK = ['Unlock sequence starting. Continue until (\S+)']
    FWMP_LOCKED_PROD = ["Managed device console can't be unlocked"]
    FWMP_LOCKED_DBG = ['Ignoring FWMP unlock setting']
    MAX_RETRY_COUNT = 5
    START_STR = ['(.*Console is enabled;)']


    def __init__(self, servo):
        super(ChromeCr50, self).__init__(servo, "cr50_console")


    def send_command(self, commands):
        """Send command through UART.

        Cr50 will drop characters input to the UART when it resumes from sleep.
        If servo is not using ccd, send some dummy characters before sending the
        real command to make sure cr50 is awake.
        """
        if not self.using_ccd():
            super(ChromeCr50, self).send_command(self.WAKE_CHAR)
        super(ChromeCr50, self).send_command(commands)


    def send_command_get_output(self, command, regexp_list):
        """Send command through UART and wait for response.

        Cr50 will drop characters input to the UART when it resumes from sleep.
        If servo is not using ccd, send some dummy characters before sending the
        real command to make sure cr50 is awake.
        """
        if not self.using_ccd():
            super(ChromeCr50, self).send_command(self.WAKE_CHAR)
        return super(ChromeCr50, self).send_command_get_output(command,
                                                               regexp_list)


    def get_deep_sleep_count(self):
        """Get the deep sleep count from the idle task"""
        result = self.send_command_get_output('idle', [self.IDLE_COUNT])
        return int(result[0][1])


    def clear_deep_sleep_count(self):
        """Clear the deep sleep count"""
        result = self.send_command_get_output('idle c', [self.IDLE_COUNT])
        if int(result[0][1]):
            raise error.TestFail("Could not clear deep sleep count")


    def has_command(self, cmd):
        """Returns 1 if cr50 has the command 0 if it doesn't"""
        try:
            self.send_command_get_output('help', [cmd])
        except:
            logging.info("Image does not include '%s' command", cmd)
            return 0
        return 1


    def erase_nvmem(self):
        """Use flasherase to erase both nvmem sections"""
        if not self.has_command('flasherase'):
            raise error.TestError("need image with 'flasherase'")

        self.send_command('flasherase 0x7d000 0x3000')
        self.send_command('flasherase 0x3d000 0x3000')


    def reboot(self):
        """Reboot Cr50 and wait for CCD to be enabled"""
        self.send_command('reboot')
        self.wait_for_reboot()


    def wait_for_reboot(self, timeout=60):
        """Wait for cr50 to reboot"""
        if self.using_ccd():
            # Cr50 USB is reset when it reboots. Wait for the CCD connection to
            # go down to detect the reboot.
            self.wait_for_ccd_disable(timeout, raise_error=False)
            self.ccd_enable()
        else:
            # Look for the boot string declaring the console is ready. If we
            # don't find it, its ok. The command will timeout after 3 seconds
            # which is longer than the time it takes for cr50 to reboot.
            try:
                rv = self.send_command_get_output('\n\n', self.START_STR)
                logging.debug(rv[0][0])
            except:
                pass


    def rollback(self, eraseflashinfo=True):
        """Set the reset counter high enough to force a rollback then reboot"""
        if not self.has_command('rw') or not self.has_command('eraseflashinfo'):
            raise error.TestError("need image with 'rw' and 'eraseflashinfo'")

        inactive_partition = self.get_inactive_version_info()[0]
        # Increase the reset count to above the rollback threshold
        self.send_command('rw 0x40000128 1')
        self.send_command('rw 0x4000012c %d' % (self.MAX_RETRY_COUNT + 2))

        if eraseflashinfo:
            self.send_command('eraseflashinfo')

        self.reboot()

        running_partition = self.get_active_version_info()[0]
        if inactive_partition != running_partition:
            raise error.TestError("Failed to rollback to inactive image")


    def rolledback(self):
        """Returns true if cr50 just rolled back"""
        return int(self._servo.get('cr50_reset_count')) > self.MAX_RETRY_COUNT


    def get_version_info(self, regexp):
        """Get information from the version command"""
        return self.send_command_get_output('ver', [regexp])[0][1::]


    def get_inactive_version_info(self):
        """Get the active partition, version, and hash"""
        return self.get_version_info(self.INACTIVE)


    def get_active_version_info(self):
        """Get the active partition, version, and hash"""
        return self.get_version_info(self.ACTIVE)


    def get_version(self):
        """Get the RW version"""
        return self.get_active_version_info()[1].strip()


    def using_servo_v4(self):
        """Returns true if the console is being served using servo v4"""
        return 'servo_v4' in self._servo.get_servo_version()


    def using_ccd(self):
        """Returns true if the console is being served using CCD"""
        return 'ccd_cr50' in self._servo.get_servo_version()


    @ccd_command
    def get_ccd_state(self):
        """Get the CCD state from servo

        Returns:
            'off' or 'on' based on whether the cr50 console is working.
        """
        return self._servo.get('ccd_state')


    @ccd_command
    def wait_for_ccd_state(self, state, timeout, raise_error=True):
        """Wait up to timeout seconds for CCD to be 'on' or 'off'
        Args:
            state: a string either 'on' or 'off'.
            timeout: time in seconds to wait
            raise_error: Raise TestFail if the value is state is not reached.

        Raises
            TestFail if ccd never reaches the specified state
        """
        logging.info("Wait until ccd is '%s'", state)
        value = utils.wait_for_value(self.get_ccd_state, state,
                                     timeout_sec=timeout)
        if value != state:
            error_msg = "timed out before detecting ccd '%s'" % state
            if raise_error:
                raise error.TestFail(error_msg)
            logging.warning(error_msg)
        logging.info("ccd is '%s'", value)


    @ccd_command
    def wait_for_ccd_disable(self, timeout=60, raise_error=True):
        """Wait for the cr50 console to stop working"""
        self.wait_for_ccd_state('off', timeout, raise_error)


    @ccd_command
    def wait_for_ccd_enable(self, timeout=60):
        """Wait for the cr50 console to start working"""
        self.wait_for_ccd_state('on', timeout)


    def ccd_disable(self):
        """Change the values of the CC lines to disable CCD"""
        if self.using_servo_v4():
            logging.info("disable ccd")
            self._servo.set_nocheck('servo_v4_dts_mode', 'off')
            self.wait_for_ccd_disable()


    @ccd_command
    def ccd_enable(self):
        """Reenable CCD and reset servo interfaces"""
        logging.info("reenable ccd")
        self._servo.set_nocheck('servo_v4_ccd_mode', 'ccd')
        self._servo.set_nocheck('servo_v4_dts_mode', 'on')
        self._servo.set_nocheck('power_state', 'ccd_reset')
        self.wait_for_ccd_enable()


    def lock_enable(self):
        """Enable the lock on cr50"""
        # Lock enable can be run, but we won't be able to use the power button
        # to disable the lock. Let's not allow the console lock to be enabled
        # if it can't be disabled without some change to the test setup
        if self.using_ccd():
            raise error.TestError("Cannot run 'lock enable' using CCD.")
        self.send_command_get_output('lock enable',
                                     ['The restricted console lock is enabled'])


    def _attempt_unlock(self):
        """Try to unlock the console.

        Raises:
            TestError if the unlock process fails.
        """
        # Get the current time.
        rv = self.send_command_get_output('gettime', self.GETTIME)
        current_time = float(rv[0][1])

        # Start the unlock process.
        rv = self.send_command_get_output('lock disable', self.UNLOCK)
        unlock_finished = float(rv[0][1])

        # Calculate the unlock timeout. There is a 10s countdown to start the
        # unlock process, so unlock_timeout will be around 10s longer than
        # necessary.
        unlock_timeout = int(unlock_finished - current_time)
        end_time = time.time() + unlock_timeout

        logging.info('Pressing power button for %ds to unlock the console.',
                     unlock_timeout)
        logging.info('The process should end at %s', time.ctime(end_time))

        # Press the power button once a second to unlock the console.
        while time.time() < end_time:
            self._servo.power_short_press()
            time.sleep(1)

        if self._servo.get('ccd_lock') != 'off':
            raise error.TestError('Could not disable lock')


    def lock_disable(self):
        """Increase the console timeout and try disabling the lock."""
        # We cannot press the power button using ccd.
        if self.using_ccd():
            raise error.TestError("Cannot run 'lock disable' using CCD.")

        # The unlock process takes a while to start. Increase the cr50 console
        # timeout so we can get the entire output of the 'lock disable' start
        # process
        original_timeout = self._servo.get('cr50_console_timeout')
        self._servo.set_nocheck('cr50_console_timeout',
                                self.START_UNLOCK_TIMEOUT)

        try:
            # Try to disable the lock
            self._attempt_unlock()
        finally:
            # Reset the cr50_console timeout
            self._servo.set_nocheck('cr50_console_timeout', original_timeout)

        logging.info('Successfully disabled the lock')
