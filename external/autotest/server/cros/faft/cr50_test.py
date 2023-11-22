# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os
import time

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import cr50_utils
from autotest_lib.server.cros.faft.firmware_test import FirmwareTest
from autotest_lib.server.cros import gsutil_wrapper


class Cr50Test(FirmwareTest):
    """
    Base class that sets up helper objects/functions for cr50 tests.
    """
    version = 1

    CR50_GS_URL = 'gs://chromeos-localmirror-private/distfiles/chromeos-cr50-%s/'
    CR50_DEBUG_FILE = 'cr50_dbg_%s.bin'
    CR50_PROD_FILE = 'cr50.%s.bin.prod'

    def initialize(self, host, cmdline_args):
        super(Cr50Test, self).initialize(host, cmdline_args)

        if not hasattr(self, 'cr50'):
            raise error.TestNAError('Test can only be run on devices with '
                                    'access to the Cr50 console')

        self._original_cr50_ver = self.cr50.get_version()
        self.host = host


    def cleanup(self):
        """Make sure cr50 is running the same image"""
        running_ver = self.cr50.get_version()
        if (hasattr(self, '_original_cr50_ver') and
            running_ver != self._original_cr50_ver):
            raise error.TestError('Running %s not the original cr50 version '
                                  '%s' % (running_ver,
                                  self._original_cr50_ver))

        super(Cr50Test, self).cleanup()


    def find_cr50_gs_image(self, filename, image_type=None):
        """Find the cr50 gs image name

        Args:
            filename: the cr50 filename to match to
            image_type: release or debug. If it is not specified we will search
                        both the release and debug directories
        Returns:
            a tuple of the gsutil bucket, filename
        """
        gs_url = self.CR50_GS_URL % (image_type if image_type else '*')
        gs_filename = os.path.join(gs_url, filename)
        bucket, gs_filename = utils.gs_ls(gs_filename)[0].rsplit('/', 1)
        return bucket, gs_filename


    def download_cr50_gs_image(self, filename, bucket=None, image_type=None):
        """Get the image from gs and save it in the autotest dir

        Returns:
            the local path
        """
        if not bucket:
            bucket, filename = self.find_cr50_gs_image(filename, image_type)

        remote_temp_dir = '/tmp/'
        src = os.path.join(remote_temp_dir, filename)
        dest = os.path.join(self.resultsdir, filename)

        # Copy the image to the dut
        gsutil_wrapper.copy_private_bucket(host=self.host,
                                           bucket=bucket,
                                           filename=filename,
                                           destination=remote_temp_dir)

        self.host.get_file(src, dest)
        return dest


    def download_cr50_debug_image(self, devid, board_id_info=None):
        """download the cr50 debug file

        Get the file with the matching devid and board id info

        Args:
            devid: the cr50_devid string '${DEVID0} ${DEVID1}'
            board_id_info: a list of [board id, board id mask, board id
                                      flags]
        Returns:
            the local path to the debug image
        """
        filename = self.CR50_DEBUG_FILE % (devid.replace(' ', '_'))
        if board_id_info:
            filename += '.' + '.'.join(board_id_info)
        return self.download_cr50_gs_image(filename, image_type='debug')


    def download_cr50_release_image(self, rw_ver, board_id_info=None):
        """download the cr50 release file

        Get the file with the matching version and board id info

        Args:
            rw_ver: the rw version string
            board_id_info: a list of strings [board id, board id mask, board id
                          flags]
        Returns:
            the local path to the release image
        """
        filename = self.CR50_PROD_FILE % rw_ver
        if board_id_info:
            filename += '.' + '.'.join(board_id_info)
        return self.download_cr50_gs_image(filename, image_type='release')


    def _cr50_verify_update(self, expected_ver, expect_rollback):
        """Verify the expected version is running on cr50

        Args:
            expect_ver: The RW version string we expect to be running
            expect_rollback: True if cr50 should have rolled back during the
                             update

        Raises:
            TestFail if there is any unexpected update state
        """
        running_ver = self.cr50.get_version()
        if expected_ver != running_ver:
            raise error.TestFail('Unexpected update ver running %s not %s' %
                                 (running_ver, expected_ver))

        if expect_rollback != self.cr50.rolledback():
            raise error.TestFail('Unexpected rollback behavior: %srollback '
                                 'detected' % 'no ' if expect_rollback else '')

        logging.info('RUNNING %s after %s', expected_ver,
                     'rollback' if expect_rollback else 'update')


    def _cr50_run_update(self, path):
        """Install the image at path onto cr50

        Args:
            path: the location of the image to update to

        Returns:
            the rw version of the image
        """
        tmp_dest = '/tmp/' + os.path.basename(path)

        dest, image_ver = cr50_utils.InstallImage(self.host, path, tmp_dest)
        cr50_utils.UsbUpdater(self.host, ['-s', dest])
        return image_ver[1]


    def cr50_update(self, path, rollback=False, erase_nvmem=False,
                    expect_rollback=False):
        """Attempt to update to the given image.

        If rollback is True, we assume that cr50 is already running an image
        that can rollback.

        Args:
            path: the location of the update image
            rollback: True if we need to force cr50 to rollback to update to
                      the given image
            erase_nvmem: True if we need to erase nvmem during rollback
            expect_rollback: True if cr50 should rollback on its own

        Raises:
            TestFail if the update failed
        """
        original_ver = self.cr50.get_version()

        rw_ver = self._cr50_run_update(path)

        # Running the update may cause cr50 to reboot. Wait for that before
        # sending more commands
        self.cr50.wait_for_reboot()

        if erase_nvmem and rollback:
            self.cr50.erase_nvmem()

        # Don't erase flashinfo during rollback. That would mess with the board
        # id
        if rollback:
            self.cr50.rollback()

        expected_ver = original_ver if expect_rollback else rw_ver
        # If we expect a rollback, the version should remain unchanged
        self._cr50_verify_update(expected_ver, rollback or expect_rollback)
