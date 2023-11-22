# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import functools
import logging
import pprint
import re
import time

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import cr50_utils
from autotest_lib.server.cros.servo import chrome_ec


def servo_v4_command(func):
    """Decorator for methods only relevant to tests running with servo v4."""
    @functools.wraps(func)
    def wrapper(instance, *args, **kwargs):
        """Ignore servo v4 functions it's not being used."""
        if instance.using_servo_v4():
            return func(instance, *args, **kwargs)
        logging.info("not using servo v4. ignoring %s", func.func_name)
    return wrapper


class ChromeCr50(chrome_ec.ChromeConsole):
    """Manages control of a Chrome Cr50.

    We control the Chrome Cr50 via the console of a Servo board. Chrome Cr50
    provides many interfaces to set and get its behavior via console commands.
    This class is to abstract these interfaces.
    """
    OPEN = 'open'
    UNLOCK = 'unlock'
    LOCK = 'lock'
    # The amount of time you need to show physical presence.
    PP_SHORT = 15
    PP_LONG = 300
    CCD_PASSWORD_RATE_LIMIT = 3
    IDLE_COUNT = 'count: (\d+)\s'
    SHORT_WAIT = 3
    # The version has four groups: the partition, the header version, debug
    # descriptor and then version string.
    # There are two partitions A and B. The active partition is marked with a
    # '*'. If it is a debug image '/DBG' is added to the version string. If the
    # image has been corrupted, the version information will be replaced with
    # 'Error'.
    # So the output may look something like this.
    #   RW_A:    0.0.21/cr50_v1.1.6133-fd788b
    #   RW_B:  * 0.0.22/DBG/cr50_v1.1.6138-b9f0b1d
    # Or like this if the region was corrupted.
    #   RW_A:  * 0.0.21/cr50_v1.1.6133-fd788b
    #   RW_B:    Error
    VERSION_FORMAT = '\nRW_(A|B): +%s +(\d+\.\d+\.\d+|Error)(/DBG)?(\S+)?\s'
    INACTIVE_VERSION = VERSION_FORMAT % ''
    ACTIVE_VERSION = VERSION_FORMAT % '\*'
    # Following lines of the version output may print the image board id
    # information. eg.
    # BID A:   5a5a4146:ffffffff:00007f00 Yes
    # BID B:   00000000:00000000:00000000 Yes
    # Use the first group from ACTIVE_VERSION to match the active board id
    # partition.
    BID_ERROR = 'read_board_id: failed'
    BID_FORMAT = ':\s+[a-f0-9:]+ '
    ACTIVE_BID = r'%s.*(\1%s|%s.*>)' % (ACTIVE_VERSION, BID_FORMAT,
            BID_ERROR)
    WAKE_CHAR = '\n\n'
    WAKE_RESPONSE = ['(>|Console is enabled)']
    START_UNLOCK_TIMEOUT = 20
    GETTIME = ['= (\S+)']
    FWMP_LOCKED_PROD = ["Managed device console can't be unlocked"]
    FWMP_LOCKED_DBG = ['Ignoring FWMP unlock setting']
    MAX_RETRY_COUNT = 5
    START_STR = ['(.*Console is enabled;)']
    REBOOT_DELAY_WITH_CCD = 60
    REBOOT_DELAY_WITH_FLEX = 3
    ON_STRINGS = ['enable', 'enabled', 'on']
    CONSERVATIVE_CCD_WAIT = 10
    CCD_SHORT_PRESSES = 5
    CAP_IS_ACCESSIBLE = 0
    CAP_SETTING = 1
    CAP_REQ = 2
    GET_CAP_TRIES = 3


    def __init__(self, servo):
        super(ChromeCr50, self).__init__(servo, 'cr50_uart')


    def wake_cr50(self):
        """Wake up cr50 by sending some linebreaks and wait for the response"""
        logging.debug(super(ChromeCr50, self).send_command_get_output(
                self.WAKE_CHAR, self.WAKE_RESPONSE))


    def send_command(self, commands):
        """Send command through UART.

        Cr50 will drop characters input to the UART when it resumes from sleep.
        If servo is not using ccd, send some dummy characters before sending the
        real command to make sure cr50 is awake.
        """
        if not self.using_ccd():
            self.wake_cr50()
        super(ChromeCr50, self).send_command(commands)


    def set_cap(self, cap, setting):
        """Set the capability to setting"""
        self.set_caps({ cap : setting })


    def set_caps(self, cap_dict):
        """Use cap_dict to set all the cap values

        Set all of the capabilities in cap_dict to the correct config.

        Args:
            cap_dict: A dictionary with the capability as key and the desired
                setting as values
        """
        for cap, config in cap_dict.iteritems():
            self.send_command('ccd set %s %s' % (cap, config))
        current_cap_settings = self.get_cap_dict(info=self.CAP_SETTING)
        for cap, config in cap_dict.iteritems():
            if (current_cap_settings[cap].lower() !=
                config.lower()):
                raise error.TestFail('Failed to set %s to %s' % (cap, config))


    def get_cap_overview(self, cap_dict):
        """Returns a tuple (in factory mode, is reset)

        If all capabilities are set to Default, ccd has been reset to default.
        If all capabilities are set to Always, ccd is in factory mode.
        """
        in_factory_mode = True
        is_reset = True
        for cap, cap_info in cap_dict.iteritems():
            cap_setting = cap_info[self.CAP_SETTING]
            if cap_setting != 'Always':
                in_factory_mode = False
            if cap_setting != 'Default':
                is_reset = False
        return in_factory_mode, is_reset


    def wp_is_reset(self):
        """Returns True if wp is reset to follow batt pres at all times"""
        follow_batt_pres, _, follow_batt_pres_atboot, _ = self.get_wp_state()
        return follow_batt_pres and follow_batt_pres_atboot


    def get_wp_state(self):
        """Returns a tuple of the current write protect state.

        The atboot setting cannot really be determined now if it is set to
        follow battery presence. It is likely to remain the same after reboot,
        but who knows. If the third element of the tuple is True, the last
        element will not be that useful

        Returns:
            (True if current state is to follow batt presence,
             True if write protect is enabled,
             True if current state is to follow batt presence atboot,
             True if write protect is enabled atboot)
        """
        rv = self.send_command_get_output('wp',
                ['Flash WP: (forced )?(enabled|disabled).*at boot: (forced )?'
                 '(follow|enabled|disabled)'])[0]
        _, forced, enabled, _, atboot = rv
        logging.debug(rv)
        return (not forced, enabled =='enabled',
                atboot == 'follow', atboot == 'enabled')


    def in_dev_mode(self):
        """Return True if cr50 thinks the device is in dev mode"""
        return 'dev_mode' in self.get_ccd_info()['TPM']


    def get_ccd_info(self):
        """Get the current ccd state.

        Take the output of 'ccd' and convert it to a dictionary.

        Returns:
            A dictionary with the ccd state name as the key and setting as
            value.
        """
        info = {}
        original_timeout = float(self._servo.get('cr50_uart_timeout'))
        # Change the console timeout to 10s, it may take longer than 3s to read
        # ccd info
        self._servo.set_nocheck('cr50_uart_timeout', self.CONSERVATIVE_CCD_WAIT)
        try:
            rv = self.send_command_get_output('ccd', ["ccd.*>"])[0]
        finally:
            self._servo.set_nocheck('cr50_uart_timeout', original_timeout)
        for line in rv.splitlines():
            # CCD information is separated with an :
            #   State: Opened
            # Extract the state name and the value.
            line = line.strip()
            if ':' not in line or '[' in line:
                continue
            key, value = line.split(':')
            info[key.strip()] = value.strip()
        logging.info('Current CCD settings:\n%s', pprint.pformat(info))
        return info


    def get_cap(self, cap):
        """Returns the capabilitiy from the capability dictionary"""
        return self.get_cap_dict()[cap]


    def _get_ccd_cap_string(self):
        """Return a string with the current capability settings.

        The ccd information is pretty long. Servo micro sometimes drops
        characters. Run the command a couple of times. Return the capapability
        string that matches a previous run.

        Raises:
            TestError if the test could not retrieve consistent capability
            information.
        """
        past_results = []
        for i in range(self.GET_CAP_TRIES):
            rv = self.send_safe_command_get_output('ccd',
                    ["Capabilities:\s+[\da-f]+\s(.*)TPM:"])[0][1]
            logging.debug(rv)
            if rv in past_results:
                return rv
            past_results.append(rv)
        logging.debug(past_results)
        raise error.TestError('Could not get consistent capability information')


    def get_cap_dict(self, info=None):
        """Get the current ccd capability settings.

        The capability may be using the 'Default' setting. That doesn't say much
        about the ccd state required to use the capability. Return all ccd
        information in the cap_dict
        [is accessible, setting, requirement]

        Args:
            info: Only fill the cap_dict with the requested information:
                  CAP_IS_ACCESSIBLE, CAP_SETTING, or CAP_REQ

        Returns:
            A dictionary with the capability as the key a list of the current
            settings as the value [is_accessible, setting, requirement]
        """
        caps = {}
        cap_info_str = self._get_ccd_cap_string()
        # There are two capability formats. Extract the setting and the
        # requirement from both formats
        #  UartGscRxECTx   Y 3=IfOpened
        #  or
        #  UartGscRxECTx   Y 0=Default (Always)
        cap_settings = re.findall('(\S+) +(Y|-).*=(\w+)( \((\S+)\))?',
                                  cap_info_str)
        for cap, accessible, setting, _, required in cap_settings:
            cap_info = [accessible == 'Y', setting, required]
            # If there's only 1 value after =, then the setting is the
            # requirement.
            if not required:
                cap_info[self.CAP_REQ] = setting
            if info is not None:
                caps[cap] = cap_info[info]
            else:
                caps[cap] = cap_info
        logging.debug(pprint.pformat(caps))
        return caps


    def send_command_get_output(self, command, regexp_list):
        """Send command through UART and wait for response.

        Cr50 will drop characters input to the UART when it resumes from sleep.
        If servo is not using ccd, send some dummy characters before sending the
        real command to make sure cr50 is awake.
        """
        if not self.using_ccd():
            self.wake_cr50()

        # We have started prepending '\n' to separate cr50 console junk from
        # the real command. If someone is just searching for .*>, then they will
        # only get the output from the first '\n' we added. Raise an error to
        # change the test to look for something more specific ex command.*>.
        # cr50 will print the command in the output, so that is an easy way to
        # modify '.*>' to match the real command output.
        if '.*>' in regexp_list:
            raise error.TestError('Send more specific regexp %r %r' % (command,
                    regexp_list))

        # prepend \n to separate the command from any junk that may have been
        # sent to the cr50 uart.
        command = '\n' + command
        return super(ChromeCr50, self).send_command_get_output(command,
                                                               regexp_list)


    def send_safe_command_get_output(self, command, regexp_list):
        """Restrict the console channels while sending console commands"""
        self.send_command('chan save')
        self.send_command('chan 0')
        try:
            rv = self.send_command_get_output(command, regexp_list)
        finally:
            self.send_command('chan restore')
        return rv


    def send_command_retry_get_output(self, command, regexp_list, tries=3):
        """Retry sending a command if we can't find the output.

        Cr50 may print irrelevant output while printing command output. It may
        prevent the regex from matching. Send command and get the output. If it
        fails try again.

        If it fails every time, raise an error.

        Don't use this to set something that should only be set once.
        """
        # TODO(b/80319784): once chan is unrestricted, use it to restrict what
        # output cr50 prints while we are sending commands.
        for i in range(tries):
            try:
                return self.send_command_get_output(command, regexp_list)
            except error.TestFail, e:
                logging.info('Failed to get %r output: %r', command, str(e))
                if i == tries - 1:
                    # Raise the last error, if we never successfully returned
                    # the command output
                    logging.info('Could not get %r output after %d tries',
                                 command, tries)
                    raise


    def get_deep_sleep_count(self):
        """Get the deep sleep count from the idle task"""
        result = self.send_command_retry_get_output('idle', [self.IDLE_COUNT])
        return int(result[0][1])


    def clear_deep_sleep_count(self):
        """Clear the deep sleep count"""
        result = self.send_command_retry_get_output('idle c', [self.IDLE_COUNT])
        if int(result[0][1]):
            raise error.TestFail("Could not clear deep sleep count")


    def get_board_properties(self):
        """Get information from the version command"""
        rv = self.send_command_retry_get_output('brdprop',
                ['properties = (\S+)\s'])
        return int(rv[0][1], 16)


    def has_command(self, cmd):
        """Returns 1 if cr50 has the command 0 if it doesn't"""
        try:
            self.send_command_retry_get_output('help', [cmd])
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
        """Reboot Cr50 and wait for cr50 to reset"""
        self.wait_for_reboot(cmd='reboot')


    def _uart_wait_for_reboot(self, cmd='\n', timeout=60):
        """Use uart to wait for cr50 to reboot.

        If a command is given run it and wait for cr50 to reboot. Monitor
        the cr50 uart to detect the reset. Wait up to timeout seconds
        for the reset.

        Args:
            cmd: the command to run to reset cr50.
            timeout: seconds to wait to detect the reboot.
        """
        original_timeout = float(self._servo.get('cr50_uart_timeout'))
        # Change the console timeout to timeout, so we wait at least that long
        # for cr50 to print the start string.
        self._servo.set_nocheck('cr50_uart_timeout', timeout)
        try:
            self.send_command_get_output(cmd, self.START_STR)
            logging.debug('Detected cr50 reboot')
        except error.TestFail, e:
            logging.debug('Failed to detect cr50 reboot')
        # Reset the timeout.
        self._servo.set_nocheck('cr50_uart_timeout', original_timeout)


    def wait_for_reboot(self, cmd='\n', timeout=60):
        """Wait for cr50 to reboot

        Run the cr50 reset command. Wait for cr50 to reset and reenable ccd if
        necessary.

        Args:
            cmd: the command to run to reset cr50.
            timeout: seconds to wait to detect the reboot.
        """
        if self.using_ccd():
            self.send_command(cmd)
            # Cr50 USB is reset when it reboots. Wait for the CCD connection to
            # go down to detect the reboot.
            self.wait_for_ccd_disable(timeout, raise_error=False)
            self.ccd_enable()
        else:
            self._uart_wait_for_reboot(cmd, timeout)


    def rollback(self, eraseflashinfo=True, chip_bid=None, chip_flags=None):
        """Set the reset counter high enough to force a rollback then reboot

        Set the new board id before rolling back if one is given.

        Args:
            eraseflashinfo: True if eraseflashinfo should be run before rollback
            chip_bid: the integer representation of chip board id or None if the
                      board id should be erased during rollback
            chip_flags: the integer representation of chip board id flags or
                        None if the board id should be erased during rollback
        """
        if (not self.has_command('rollback') or not
            self.has_command('eraseflashinfo')):
            raise error.TestError("need image with 'rollback' and "
                "'eraseflashinfo'")

        inactive_partition = self.get_inactive_version_info()[0]
        # Set the board id if both the board id and flags have been given.
        set_bid = chip_bid and chip_flags

        # Erase the infomap
        if eraseflashinfo or set_bid:
            self.send_command('eraseflashinfo')

        # Update the board id after it has been erased
        if set_bid:
            self.send_command('bid 0x%x 0x%x' % (chip_bid, chip_flags))

        self.wait_for_reboot(cmd='rollback')

        running_partition = self.get_active_version_info()[0]
        if inactive_partition != running_partition:
            raise error.TestError("Failed to rollback to inactive image")


    def rolledback(self):
        """Returns true if cr50 just rolled back"""
        return 'Rollback detected' in self.send_safe_command_get_output(
                'sysinfo', ['sysinfo.*>'])[0]


    def get_version_info(self, regexp):
        """Get information from the version command"""
        return self.send_command_retry_get_output('ver', [regexp])[0][1::]


    def get_inactive_version_info(self):
        """Get the active partition, version, and hash"""
        return self.get_version_info(self.INACTIVE_VERSION)


    def get_active_version_info(self):
        """Get the active partition, version, and hash"""
        return self.get_version_info(self.ACTIVE_VERSION)


    def using_prod_rw_keys(self):
        """Returns True if the RW keyid is prod"""
        rv = self.send_safe_command_retry_get_output('sysinfo',
                ['RW keyid:.*\(([a-z]+)\)'])
        logging.info(rv)
        return rv[0][1] == 'prod'


    def get_active_board_id_str(self):
        """Get the running image board id.

        Returns:
            The board id string or None if the image does not support board id
            or the image is not board id locked.
        """
        # Getting the board id from the version console command is only
        # supported in board id locked images .22 and above. Any image that is
        # board id locked will have support for getting the image board id.
        #
        # If board id is not supported on the device, return None. This is
        # still expected on all current non board id locked release images.
        try:
            version_info = self.get_version_info(self.ACTIVE_BID)
        except error.TestFail, e:
            logging.info(str(e))
            logging.info('Cannot use the version to get the board id')
            return None

        if self.BID_ERROR in version_info[4]:
            raise error.TestError(version_info)
        bid = version_info[4].split()[1]
        return cr50_utils.GetBoardIdInfoString(bid)


    def get_version(self):
        """Get the RW version"""
        return self.get_active_version_info()[1].strip()


    def using_servo_v4(self):
        """Returns true if the console is being served using servo v4"""
        return 'servo_v4' in self._servo.get_servo_version()


    def using_ccd(self):
        """Returns true if the console is being served using CCD"""
        return 'ccd_cr50' in self._servo.get_servo_version()


    def ccd_is_enabled(self):
        """Return True if ccd is enabled.

        If the test is running through ccd, return the ccd_state value. If
        a flex cable is being used, use the CCD_MODE_L gpio setting to determine
        if Cr50 has ccd enabled.

        Returns:
            'off' or 'on' based on whether the cr50 console is working.
        """
        if self.using_ccd():
            return self._servo.get('ccd_state') == 'on'
        else:
            result = self.send_command_retry_get_output('gpioget',
                    ['(0|1)[ \S]*CCD_MODE_L'])
            return not bool(int(result[0][1]))


    @servo_v4_command
    def wait_for_stable_ccd_state(self, state, timeout, raise_error):
        """Wait up to timeout seconds for CCD to be 'on' or 'off'

        Verify ccd is off or on and remains in that state for 3 seconds.

        Args:
            state: a string either 'on' or 'off'.
            timeout: time in seconds to wait
            raise_error: Raise TestFail if the value is state is not reached.

        Raises:
            TestFail if ccd never reaches the specified state
        """
        wait_for_enable = state == 'on'
        logging.info("Wait until ccd is %s", 'on' if wait_for_enable else 'off')
        enabled = utils.wait_for_value(self.ccd_is_enabled, wait_for_enable,
                                       timeout_sec=timeout)
        if enabled != wait_for_enable:
            error_msg = ("timed out before detecting ccd '%s'" %
                         ('on' if wait_for_enable else 'off'))
            if raise_error:
                raise error.TestFail(error_msg)
            logging.warning(error_msg)
        else:
            # Make sure the state doesn't change.
            enabled = utils.wait_for_value(self.ccd_is_enabled, not enabled,
                                           timeout_sec=self.SHORT_WAIT)
            if enabled != wait_for_enable:
                error_msg = ("CCD switched %r after briefly being %r" %
                             ('on' if enabled else 'off', state))
                if raise_error:
                    raise error.TestFail(error_msg)
                logging.info(error_msg)
        logging.info("ccd is %r", 'on' if enabled else 'off')


    @servo_v4_command
    def wait_for_ccd_disable(self, timeout=60, raise_error=True):
        """Wait for the cr50 console to stop working"""
        self.wait_for_stable_ccd_state('off', timeout, raise_error)


    @servo_v4_command
    def wait_for_ccd_enable(self, timeout=60, raise_error=False):
        """Wait for the cr50 console to start working"""
        self.wait_for_stable_ccd_state('on', timeout, raise_error)


    @servo_v4_command
    def ccd_disable(self, raise_error=True):
        """Change the values of the CC lines to disable CCD"""
        logging.info("disable ccd")
        self._servo.set_nocheck('servo_v4_dts_mode', 'off')
        self.wait_for_ccd_disable(raise_error=raise_error)


    @servo_v4_command
    def ccd_enable(self, raise_error=False):
        """Reenable CCD and reset servo interfaces"""
        logging.info("reenable ccd")
        self._servo.set_nocheck('servo_v4_dts_mode', 'on')
        # If the test is actually running with ccd, wait for USB communication
        # to come up after reset.
        if self.using_ccd():
            time.sleep(self._servo.USB_DETECTION_DELAY)
        self.wait_for_ccd_enable(raise_error=raise_error)


    def _level_change_req_pp(self, level):
        """Returns True if setting the level will require physical presence"""
        testlab_pp = level != 'testlab open' and 'testlab' in level
        # If the level is open and the ccd capabilities say physical presence
        # is required, then physical presence will be required.
        open_pp = (level == 'open' and
                   not self.get_cap('OpenNoLongPP')[self.CAP_IS_ACCESSIBLE])
        return testlab_pp or open_pp


    def _state_to_bool(self, state):
        """Converts the state string to True or False"""
        # TODO(mruthven): compare to 'on' once servo is up to date in the lab
        return state.lower() in self.ON_STRINGS


    def testlab_is_on(self):
        """Returns True of testlab mode is on"""
        return self._state_to_bool(self._servo.get('cr50_testlab'))


    def set_ccd_testlab(self, state):
        """Set the testlab mode

        Args:
            state: the desired testlab mode string: 'on' or 'off'

        Raises:
            TestFail if testlab mode was not changed
        """
        if self.using_ccd():
            raise error.TestError('Cannot set testlab mode with CCD. Use flex '
                    'cable instead.')

        request_on = self._state_to_bool(state)
        testlab_on = self.testlab_is_on()
        request_str = 'on' if request_on else 'off'

        if testlab_on == request_on:
            logging.info('ccd testlab already set to %s', request_str)
            return

        original_level = self.get_ccd_level()

        # We can only change the testlab mode when the device is open. If
        # testlab mode is already enabled, we can go directly to open using 'ccd
        # testlab open'. This will save 5 minutes, because we can skip the
        # physical presence check.
        if testlab_on:
            self.send_command('ccd testlab open')
        else:
            self.set_ccd_level('open')

        # Set testlab mode
        rv = self.send_command_get_output('ccd testlab %s' % request_str,
                ['ccd.*>'])[0]
        if 'Access Denied' in rv:
            raise error.TestFail("'ccd %s' %s" % (request_str, rv))

        # Press the power button once a second for 15 seconds.
        self.run_pp(self.PP_SHORT)

        self.set_ccd_level(original_level)

        if request_on != self.testlab_is_on():
            raise error.TestFail('Failed to set ccd testlab to %s' % state)


    def get_ccd_level(self):
        """Returns the current ccd privilege level"""
        return self._servo.get('cr50_ccd_level').lower()


    def set_ccd_level(self, level, password=''):
        """Set the Cr50 CCD privilege level.

        Args:
            level: a string of the ccd privilege level: 'open', 'lock', or
                   'unlock'.
            password: send the ccd command with password. This will still
                    require the same physical presence.

        Raises:
            TestFail if the level couldn't be set
        ."""
        # TODO(mruthven): add support for CCD password
        level = level.lower()

        if level == self.get_ccd_level():
            logging.info('CCD privilege level is already %s', level)
            return

        if 'testlab' in level:
            raise error.TestError("Can't change testlab mode using "
                "ccd_set_level")

        testlab_on = self._state_to_bool(self._servo.get('cr50_testlab'))
        req_pp = self._level_change_req_pp(level)
        has_pp = not self.using_ccd()
        dbg_en = 'DBG' in self._servo.get('cr50_version')

        if req_pp and not has_pp:
            raise error.TestError("Can't change privilege level to '%s' "
                "without physical presence." % level)

        if not testlab_on and not has_pp:
            raise error.TestError("Wont change privilege level without "
                "physical presence or testlab mode enabled")

        original_timeout = float(self._servo.get('cr50_uart_timeout'))
        # Change the console timeout to CONSERVATIVE_CCD_WAIT, running 'ccd' may
        # take more than 3 seconds.
        self._servo.set_nocheck('cr50_uart_timeout', self.CONSERVATIVE_CCD_WAIT)
        # Start the unlock process.

        if level == 'open' or level == 'unlock':
            logging.info('waiting %d seconds, the minimum time between'
                         ' ccd password attempts',
                         self.CCD_PASSWORD_RATE_LIMIT)
            time.sleep(self.CCD_PASSWORD_RATE_LIMIT)

        try:
            cmd = 'ccd %s%s' % (level, (' ' + password) if password else '')
            rv = self.send_command_get_output(cmd, [cmd + '(.*)>'])[0][1]
        finally:
            self._servo.set('cr50_uart_timeout', original_timeout)
        logging.info(rv)
        if 'ccd_open denied: fwmp' in rv:
            raise error.TestFail('FWMP disabled %r: %s' % (cmd, rv))
        if 'Access Denied' in rv:
            raise error.TestFail("%r %s" % (cmd, rv))
        if 'Busy' in rv:
            raise error.TestFail("cr50 is too busy to run %r: %s" % (cmd, rv))

        # Press the power button once a second, if we need physical presence.
        if req_pp:
            # DBG images have shorter unlock processes
            self.run_pp(self.PP_SHORT if dbg_en else self.PP_LONG)

        if level != self.get_ccd_level():
            raise error.TestFail('Could not set privilege level to %s' % level)

        logging.info('Successfully set CCD privelege level to %s', level)


    def run_pp(self, unlock_timeout):
        """Press the power button a for unlock_timeout seconds.

        This will press the power button many more times than it needs to be
        pressed. Cr50 doesn't care if you press it too often. It just cares that
        you press the power button at least once within the detect interval.

        For privilege level changes you need to press the power button 5 times
        in the short interval and then 4 times within the long interval.
        Short Interval
        100msec < power button press < 5 seconds
        Long Interval
        60s < power button press < 300s

        For testlab enable/disable you must press the power button 5 times
        spaced between 100msec and 5 seconds apart.
        """
        end_time = time.time() + unlock_timeout

        logging.info('Pressing power button for %ds to unlock the console.',
                     unlock_timeout)
        logging.info('The process should end at %s', time.ctime(end_time))

        # Press the power button once a second to unlock the console.
        while time.time() < end_time:
            self._servo.power_short_press()
            time.sleep(1)


    def gettime(self):
        """Get the current cr50 system time"""
        result = self.send_command_retry_get_output('gettime', [' = (.*) s'])
        return float(result[0][1])


    def servo_v4_supports_dts_mode(self):
        """Returns True if cr50 registers changes in servo v4 dts mode."""
        # This is to test that Cr50 actually recognizes the change in ccd state
        # We cant do that with tests using ccd, because the cr50 communication
        # goes down once ccd is enabled.
        if 'servo_v4_with_servo_micro' != self._servo.get_servo_version():
            return False

        ccd_start = 'on' if self.ccd_is_enabled() else 'off'
        dts_start = self._servo.get('servo_v4_dts_mode')
        try:
            # Verify both ccd enable and disable
            self.ccd_disable(raise_error=True)
            self.ccd_enable(raise_error=True)
            rv = True
        except Exception, e:
            logging.info(e)
            rv = False
        self._servo.set_nocheck('servo_v4_dts_mode', dts_start)
        self.wait_for_stable_ccd_state(ccd_start, 60, True)
        logging.info('Test setup does%s support servo DTS mode',
                '' if rv else 'n\'t')
        return rv


    def wait_until_update_is_allowed(self):
        """Wait until cr50 will be able to accept an update.

        Cr50 rejects any attempt to update if it has been less than 60 seconds
        since it last recovered from deep sleep or came up from reboot. This
        will wait until cr50 gettime shows a time greater than 60.
        """
        if self.get_active_version_info()[2]:
            logging.info("Running DBG image. Don't need to wait for update.")
            return
        cr50_time = self.gettime()
        if cr50_time < 60:
            sleep_time = 61 - cr50_time
            logging.info('Cr50 has been up for %ds waiting %ds before update',
                         cr50_time, sleep_time)
            time.sleep(sleep_time)

    def tpm_is_enabled(self):
        """Query the current TPM mode.

        Returns  True if TPM is enabled,
                 False otherwise.
        """
        result = self.send_command_get_output('sysinfo',
                ['(?i)TPM\s+MODE:\s+(enabled|disabled)'])[0][1]
        logging.debug(result)

        return result.lower() == 'enabled'

    def keyladder_is_enabled(self):
        """Get the status of H1 Key Ladder.

        Returns True if H1 Key Ladder is enabled.
                False otherwise.
        """
        result = self.send_command_get_output('sysinfo',
                ['(?i)Key\s+Ladder:\s+(enabled|disabled)'])[0][1]
        logging.debug(result)

        return result.lower() == 'enabled'
