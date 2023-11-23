# Copyright 2014 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from autotest_lib.client.common_lib import error
from autotest_lib.client.cros import constants
from autotest_lib.server import autotest

POWER_DIR = '/var/lib/power_manager'
TMP_POWER_DIR = '/tmp/power_manager'
POWER_DEFAULTS = '/usr/share/power_manager/board_specific'

RESUME_CTRL_RETRIES = 3
RESUME_GRACE_PERIOD = 10
XMLRPC_BRINGUP_TIMEOUT_SECONDS = 60


class DarkResumeSuspend(object):
    """Context manager which exposes the dark resume-specific suspend
    functionality.

    This is required because using the RTC for a dark resume test will
    cause the system to wake up in dark resume and resuspend, which is
    not what we want. Instead, we suspend indefinitely, but make sure we
    don't leave the DUT asleep by always running code to wake it up via
    servo.
    """


    def __init__(self, proxy, host, suspend_for):
        """Set up for a dark-resume-ready suspend to be carried out using
        |proxy| and for the subsequent wakeup to be carried out using
        |host|.

        @param proxy: a dark resume xmlrpc server proxy object for the DUT
        @param host: a servo host connected to the DUT
        @param suspend_for : If not 0, sets a rtc alarm to wake the system after
            |suspend_for| secs.
        """
        self._client_proxy = proxy
        self._host = host
        self._suspend_for = suspend_for


    def __enter__(self):
        """Suspend the DUT."""
        logging.info('Suspending DUT (in background)...')
        self._client_proxy.suspend_bg_for_dark_resume(self._suspend_for)


    def __exit__(self, exception, value, traceback):
        """Wake up the DUT."""
        logging.info('Waking DUT from server.')
        _wake_dut(self._host)


class DarkResumeUtils(object):
    """Class containing common functionality for tests which exercise dark
    resume pathways. We set up powerd to allow dark resume and also configure
    the suspended devices so that the backchannel can stay up. We can also
    check for the number of dark resumes that have happened in a particular
    suspend request.
    """


    def __init__(self, host, duration=0):
        """Set up powerd preferences so we will properly go into dark resume,
        and still be able to communicate with the DUT.

        @param host: the DUT to set up dark resume for

        """
        self._host = host
        logging.info('Setting up dark resume preferences')

        # Make temporary directory, which will be used to hold
        # temporary preferences. We want to avoid writing into
        # /var/lib so we don't have to save any state.
        logging.debug('Creating temporary powerd prefs at %s', TMP_POWER_DIR)
        host.run('mkdir -p %s' % TMP_POWER_DIR)

        logging.debug('Enabling dark resume')
        host.run('echo 0 > %s/disable_dark_resume' % TMP_POWER_DIR)

        # bind the tmp directory to the power preference directory
        host.run('mount --bind %s %s' % (TMP_POWER_DIR, POWER_DIR))

        logging.debug('Restarting powerd with new settings')
        host.run('stop powerd; start powerd')

        logging.debug('Starting XMLRPC session to watch for dark resumes')
        self._client_proxy = self._get_xmlrpc_proxy()


    def teardown(self):
        """Clean up changes made by DarkResumeUtils."""

        logging.info('Tearing down dark resume preferences')

        logging.debug('Cleaning up temporary powerd bind mounts')
        self._host.run('umount %s' % POWER_DIR)

        logging.debug('Restarting powerd to revert to old settings')
        self._host.run('stop powerd; start powerd')


    def suspend(self, suspend_for=0):
        """
        Returns a DarkResumeSuspend context manager that allows safe
        suspending of the DUT.
        @param suspend_for : If not 0, sets a rtc alarm to wake the system after
            |suspend_for| secs.
        """
        return DarkResumeSuspend(self._client_proxy, self._host, suspend_for)


    def stop_resuspend_on_dark_resume(self, stop_resuspend=True):
        """
        If |stop_resuspend| is True, stops re-suspend on seeing a dark resume.
        """
        self._client_proxy.set_stop_resuspend(stop_resuspend)


    def count_dark_resumes(self):
        """Return the number of dark resumes that have occurred since the beginning
        of the test. This will wake up the DUT, so make sure to put it back to
        sleep if you need to keep it suspended for some reason.

        This method will raise an error if the DUT does not wake up.

        @return the number of dark resumes counted by this DarkResumeUtils

        """
        _wake_dut(self._host)

        return self._client_proxy.get_dark_resume_count()



    def host_has_lid(self):
        """Returns True if the DUT has a lid."""
        return self._client_proxy.has_lid()


    def _get_xmlrpc_proxy(self):
        """Get a dark resume XMLRPC proxy for the host this DarkResumeUtils is
        attached to.

        The returned object has no particular type.  Instead, when you call
        a method on the object, it marshalls the objects passed as arguments
        and uses them to make RPCs on the remote server.  Thus, you should
        read dark_resume_xmlrpc_server.py to find out what methods are supported.

        @return proxy object for remote XMLRPC server.

        """
        # Make sure the client library is on the device so that the proxy
        # code is there when we try to call it.
        client_at = autotest.Autotest(self._host)
        client_at.install()
        # Start up the XMLRPC proxy on the client
        proxy = self._host.rpc_server_tracker.xmlrpc_connect(
                constants.DARK_RESUME_XMLRPC_SERVER_COMMAND,
                constants.DARK_RESUME_XMLRPC_SERVER_PORT,
                command_name=
                    constants.DARK_RESUME_XMLRPC_SERVER_CLEANUP_PATTERN,
                ready_test_name=
                    constants.DARK_RESUME_XMLRPC_SERVER_READY_METHOD,
                timeout_seconds=XMLRPC_BRINGUP_TIMEOUT_SECONDS)
        return proxy


def _wake_dut(host):
    """
    Make sure |host| is up by pressing power button.

    @raises error.TestFail: If we cannot wake the |host| up. This means the
            DUT has to be woken up manually. Should not happen mostly.
    """
    woken = False
    for i in range(RESUME_CTRL_RETRIES):
        # Check before pressing the power button. Or you might suspend/shutdown
        # the system if already in S0.
        if host.wait_up(timeout=RESUME_GRACE_PERIOD):
            woken = True
            break
        logging.debug('Wake attempt #%d ', i+1)
        host.servo.power_short_press()

    if not woken:
        logging.warning('DUT did not wake -- trouble ahead')
        raise error.TestFail('DUT did not wake')
