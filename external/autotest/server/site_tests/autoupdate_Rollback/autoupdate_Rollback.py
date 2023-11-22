# Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from autotest_lib.client.common_lib import error
from autotest_lib.server import test
from autotest_lib.server.cros import autoupdate_utils

POWERWASH_COMMAND = 'safe fast keepimg'
POWERWASH_MARKER_FILE = '/mnt/stateful_partition/factory_install_reset'
STATEFUL_MARKER_FILE = '/mnt/stateful_partition/autoupdate_Rollback_flag'

class autoupdate_Rollback(test.test):
    """Test that updates the machine and performs rollback."""
    version = 1

    def _powerwash(self, host):
        """
        Powerwashes DUT.

        @param host: The DUT we are testing on.

        """
        logging.info('Powerwashing device before rollback.')
        host.run('echo car > %s' % STATEFUL_MARKER_FILE)
        host.run("echo '%s' > %s" % (POWERWASH_COMMAND, POWERWASH_MARKER_FILE))
        host.reboot()
        marker = host.run('[ -e %s ]' % STATEFUL_MARKER_FILE,
                          ignore_status=True, ignore_timeout=True)
        if marker is None or marker.exit_status == 0:
            raise error.TestFail("Powerwash cycle didn't remove the marker "
                                 "file on the stateful partition.")


    def run_once(self, host, job_repo_url=None,
                 powerwash_before_rollback=False):
        """Runs the test.

        @param host: A host object representing the DUT.
        @param job_repo_url: URL to get the image.

        @raise error.TestError if anything went wrong with setting up the test;
               error.TestFail if any part of the test has failed.

        """
        updater = autoupdate_utils.get_updater_from_repo_url(host, job_repo_url)

        initial_kernel, updated_kernel = updater.get_kernel_state()
        logging.info('Initial device state: active kernel %s, '
                     'inactive kernel %s.', initial_kernel, updated_kernel)

        logging.info('Performing an update.')
        updater.update_image()
        host.reboot()

        # We should be booting from the new partition.
        error_message = 'Failed to set up test by updating DUT.'
        updater.verify_boot_expectations(updated_kernel, error_message)

        if powerwash_before_rollback:
            self._powerwash(host)

        logging.info('Update verified, initiating rollback.')
        # Powerwash is tested separately from rollback.
        updater.rollback_rootfs(powerwash=False)
        host.reboot()

        # We should be back on our initial partition.
        error_message = ('Autoupdate reported that rollback succeeded but we '
                         'did not boot into the correct partition.')
        updater.verify_boot_expectations(initial_kernel, error_message)
        logging.info('We successfully rolled back to initial kernel.')
