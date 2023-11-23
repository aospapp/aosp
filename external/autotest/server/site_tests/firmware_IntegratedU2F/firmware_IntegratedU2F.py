# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import time
import StringIO
import subprocess

from autotest_lib.client.common_lib import error, utils
from autotest_lib.client.common_lib.cros import tpm_utils
from autotest_lib.server import autotest
from autotest_lib.server.cros.faft.firmware_test import FirmwareTest


class firmware_IntegratedU2F(FirmwareTest):
    """Verify U2F using the on-board cr50 firmware works."""
    version = 1

    U2FTEST_PATH = '/usr/local/bin/U2FTest'
    G2FFORCE_PATH = '/var/lib/u2f/force/g2f.force'

    VID = '18D1'
    PID = '502C'
    SHORT_WAIT = 1

    def cleanup(self):
        """Remove g2f.force"""
        if self.start_u2fd:
            if self.u2fd_is_running():
                self.host.run('stop u2fd')
            if self.create_g2f_force:
                self.host.run('rm /var/lib/u2f/force/g2f.force')
            tpm_utils.ClearTPMOwnerRequest(self.host, wait_for_ready=True)

        super(firmware_IntegratedU2F, self).cleanup()


    def u2fd_is_running(self):
        """Returns True if u2fd is running on the host"""
        return 'running' in self.host.run('status u2fd').stdout


    def cryptohome_ready(self):
        """Return True if cryptohome is running."""
        return 'running' in self.host.run('status cryptohomed').stdout


    def owner_key_exists(self):
        """Return True if /var/lib/whitelist/owner.key exists."""
        logging.info('checking for owner key')
        return self.host.path_exists('/var/lib/whitelist/owner.key')


    def setup_u2fd(self):
        """Start u2fd on the host"""
        self.start_u2fd = not self.u2fd_is_running()
        if not self.start_u2fd:
            logging.info('u2fd is already running')
            return

        # Login
        tpm_utils.ClearTPMOwnerRequest(self.host, wait_for_ready=True)

        # Wait for cryptohome to show the TPM is ready before logging in.
        if not utils.wait_for_value(self.cryptohome_ready, True,
                                    timeout_sec=60):
            raise error.TestError('Crytpohome did not start')


        client_at = autotest.Autotest(self.host)
        client_at.run_test('login_LoginSuccess')

        # Wait for the owner key to exist before trying to start u2fd.
        if not utils.wait_for_value(self.owner_key_exists, True,
                                    timeout_sec=120):
            raise error.TestError('Device did not create owner key')

        self.create_g2f_force = not self.host.path_exists(self.G2FFORCE_PATH)
        if self.create_g2f_force:
            logging.info('Creating %s', self.G2FFORCE_PATH)
            self.host.run('touch %s' % self.G2FFORCE_PATH)

        # Start u2fd
        self.host.run('start u2fd')
        self.host.run('trunks_send --u2f_cert --crt=/tmp/cert0.crt')
        # Make sure it is still running
        if not self.u2fd_is_running():
            raise error.TestFail('could not start u2fd')
        logging.info('u2fd is running')


    def find_u2f_device(self):
        """Find the U2F device

        Returns:
            0 if the device hasn't been found. Non-zero if it has
        """
        self.device = ''
        path = '/sys/bus/hid/devices/*:%s:%s.*/hidraw' % (self.VID, self.PID)
        try:
            self.device = self.host.run('ls ' + path).stdout.strip()
        except error.AutoservRunError, e:
            logging.info('Could not find device')
        return len(self.device)


    def get_u2f_device(self):
        """Get the integrated u2f device."""
        start_time = time.time()
        utils.wait_for_value(self.find_u2f_device, max_threshold=1,
            timeout_sec=30)
        wait_time = int(time.time() - start_time)
        if wait_time:
            logging.info('Took %ss to find device', wait_time)
        return '/dev/' + self.device


    def check_u2ftest_and_press_power_button(self):
        """Check stdout and press the power button if prompted

        Returns:
            True if the process is still running.
        """
        time.sleep(self.SHORT_WAIT)
        self.output += self.get_u2ftest_output()
        logging.info(self.output)
        if 'Touch device and hit enter..' in self.output:
            # press the power button
            self.servo.power_short_press()
            logging.info('pressed power button')
            time.sleep(self.SHORT_WAIT)
            # send enter to the test process
            self.u2ftest_job.sp.stdin.write('\n')
            logging.info('hit enter')
            self.output = ''
        return self.u2ftest_job.sp.poll() is not None


    def get_u2ftest_output(self):
        """Read the new output"""
        self.u2ftest_job.process_output()
        self.stdout.seek(self.last_len)
        output = self.stdout.read().strip()
        self.last_len = self.stdout.len
        return output


    def run_once(self, host):
        """Run U2FTest"""
        self.host = host
        self.last_len = 0
        self.output = ''

        if not self.host.path_exists(self.U2FTEST_PATH):
            raise error.TestNAError('Device does not have U2FTest support')

        # Login and start u2fd
        self.setup_u2fd()
        device = self.get_u2f_device()

        # Run U2FTest with the U2F device
        u2ftest_cmd = utils.sh_escape('%s %s' % (self.U2FTEST_PATH, device))
        full_ssh_command = '%s "%s"' % (self.host.ssh_command(options='-tt'),
            u2ftest_cmd)
        self.stdout = StringIO.StringIO()
        # Start running U2FTest in the background.
        self.u2ftest_job = utils.BgJob(full_ssh_command,
                                       nickname='u2ftest',
                                       stdout_tee=self.stdout,
                                       stderr_tee=utils.TEE_TO_LOGS,
                                       stdin=subprocess.PIPE)
        if self.u2ftest_job == None:
            raise error.TestFail('could not start u2ftest')

        try:
            # This will timeout if the process doesn't complete in 10 seconds.
            utils.wait_for_value(self.check_u2ftest_and_press_power_button,
                expected_value=True)
        finally:
            self.close_u2ftest()


    def close_u2ftest(self):
        """Terminate the process and check the results."""
        exit_status = utils.nuke_subprocess(self.u2ftest_job.sp)

        stdout = self.stdout.getvalue().strip()
        if stdout:
            logging.debug('stdout of U2FTest:\n%s', stdout)
        if exit_status:
            logging.error('stderr of U2FTest:\n%s', self.output)
            raise error.TestError('U2FTest: %s' % self.output)
