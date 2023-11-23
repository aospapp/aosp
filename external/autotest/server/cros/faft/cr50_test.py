# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os
import pprint
import StringIO
import subprocess
import time

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error, utils
from autotest_lib.client.common_lib.cros import cr50_utils, tpm_utils
from autotest_lib.server.cros import debugd_dev_tools, gsutil_wrapper
from autotest_lib.server.cros.faft.firmware_test import FirmwareTest


class Cr50Test(FirmwareTest):
    """Base class that sets up helper objects/functions for cr50 tests."""
    version = 1

    CR50_GS_URL = 'gs://chromeos-localmirror-private/distfiles/chromeos-cr50-%s/'
    CR50_DEBUG_FILE = '*/cr50_dbg_%s.bin'
    CR50_PROD_FILE = 'cr50.%s.bin.prod'
    NONE = 0
    # Saved the original device state during init.
    INITIAL_STATE = 1 << 0
    # Saved the original image, the device image, and the debug image. These
    # images are needed to be able to restore the original image and board id.
    IMAGES = 1 << 1
    PP_SHORT_INTERVAL = 3
    CLEARED_FWMP_EXIT_STATUS = 1
    CLEARED_FWMP_ERROR_MSG = ('CRYPTOHOME_ERROR_FIRMWARE_MANAGEMENT_PARAMETERS'
                              '_INVALID')
    # Cr50 may have flash operation errors during the test. Here's an example
    # of one error message.
    # do_flash_op:245 errors 20 fsh_pe_control 40720004
    # The stuff after the ':' may change, but all flash operation errors
    # contain do_flash_op. do_flash_op is only ever printed if there is an
    # error during the flash operation. Just search for do_flash_op to simplify
    # the search string and make it applicable to all flash op errors.
    CR50_FLASH_OP_ERROR_MSG = 'do_flash_op'

    def initialize(self, host, cmdline_args, full_args,
            restore_cr50_state=False, cr50_dev_path='', provision_update=False):
        self._saved_state = self.NONE
        self._raise_error_on_mismatch = not restore_cr50_state
        self._provision_update = provision_update
        super(Cr50Test, self).initialize(host, cmdline_args)

        if not hasattr(self, 'cr50'):
            raise error.TestNAError('Test can only be run on devices with '
                                    'access to the Cr50 console')

        logging.info('Test Args: %r', full_args)

        self.can_set_ccd_level = (not self.cr50.using_ccd() or
            self.cr50.testlab_is_on())
        self.original_ccd_level = self.cr50.get_ccd_level()
        self.original_ccd_settings = self.cr50.get_cap_dict(
                info=self.cr50.CAP_SETTING)

        self.host = host
        tpm_utils.ClearTPMOwnerRequest(self.host, wait_for_ready=True)
        # Clear the FWMP, so it can't disable CCD.
        self.clear_fwmp()

        if self.can_set_ccd_level:
            # Lock cr50 so the console will be restricted
            self.cr50.set_ccd_level('lock')
        elif self.original_ccd_level != 'lock':
            raise error.TestNAError('Lock the console before running cr50 test')

        self._save_original_state()

        # Verify cr50 is still running the correct version
        cr50_qual_version = full_args.get('cr50_qual_version', '').strip()
        if cr50_qual_version:
            _, running_rw, running_bid = self.get_saved_cr50_original_version()
            expected_rw, expected_bid_sym = cr50_qual_version.split('/')
            expected_bid = cr50_utils.GetBoardIdInfoString(expected_bid_sym,
                                                           symbolic=False)
            logging.debug('Running %s %s Expect %s %s', running_rw, running_bid,
                          expected_rw, expected_bid)
            if running_rw != expected_rw or expected_bid != running_bid:
                raise error.TestError('Not running %s' % cr50_qual_version)

        # We successfully saved the device state
        self._saved_state |= self.INITIAL_STATE
        try:
            self._save_node_locked_dev_image(cr50_dev_path)
            self._save_original_images(full_args.get('release_path', ''))
            # We successfully saved the device images
            self._saved_state |= self.IMAGES
        except:
            if restore_cr50_state:
                raise


    def after_run_once(self):
        """Log which iteration just ran"""
        logging.info('successfully ran iteration %d', self.iteration)


    def _save_node_locked_dev_image(self, cr50_dev_path):
        """Save or download the node locked dev image.

        Args:
            cr50_dev_path: The path to the node locked cr50 image.
        """
        if os.path.isfile(cr50_dev_path):
            self._node_locked_cr50_image = cr50_dev_path
        else:
            devid = self.servo.get('cr50_devid')
            self._node_locked_cr50_image = self.download_cr50_debug_image(
                devid)[0]


    def _save_original_images(self, release_path):
        """Use the saved state to find all of the device images.

        This will download running cr50 image and the device image.

        Args:
            release_path: The release path given by test args
        """
        # Copy the prod and prepvt images from the DUT
        _, prod_rw, prod_bid = self._original_state['device_prod_ver']
        filename = 'prod_device_image_' + prod_rw
        self._device_prod_image = os.path.join(self.resultsdir,
                filename)
        self.host.get_file(cr50_utils.CR50_PROD,
                self._device_prod_image)

        if cr50_utils.HasPrepvtImage(self.host):
            _, prepvt_rw, prepvt_bid = self._original_state['device_prepvt_ver']
            filename = 'prepvt_device_image_' + prepvt_rw
            self._device_prepvt_image = os.path.join(self.resultsdir,
                    filename)
            self.host.get_file(cr50_utils.CR50_PREPVT,
                    self._device_prepvt_image)
            prepvt_bid = cr50_utils.GetBoardIdInfoString(prepvt_bid)
        else:
            self._device_prepvt_image = None
            prepvt_rw = None
            prepvt_bid = None

        if os.path.isfile(release_path):
            self._original_cr50_image = release_path
            logging.info('using supplied image')
            return
        # If the running cr50 image version matches the image on the DUT use
        # the DUT image as the original image. If the versions don't match
        # download the image from google storage
        _, running_rw, running_bid = self.get_saved_cr50_original_version()

        # Make sure prod_bid and running_bid are in the same format
        prod_bid = cr50_utils.GetBoardIdInfoString(prod_bid)
        running_bid = cr50_utils.GetBoardIdInfoString(running_bid)
        if running_rw == prod_rw and running_bid == prod_bid:
            logging.info('Using device cr50 prod image %s %s', prod_rw,
                    prod_bid)
            self._original_cr50_image = self._device_prod_image
        elif running_rw == prepvt_rw and running_bid == prepvt_bid:
            logging.info('Using device cr50 prepvt image %s %s', prepvt_rw,
                    prepvt_bid)
            self._original_cr50_image = self._device_prepvt_image
        else:
            logging.info('Downloading cr50 image %s %s', running_rw,
                    running_bid)
            self._original_cr50_image = self.download_cr50_release_image(
                running_rw, running_bid)[0]


    def _save_original_state(self):
        """Save the cr50 related state.

        Save the device's current cr50 version, cr50 board id, rlz, and image
        at /opt/google/cr50/firmware/cr50.bin.prod. These will be used to
        restore the state during cleanup.
        """
        self._original_state = self.get_cr50_device_state()


    def get_saved_cr50_original_version(self):
        """Return (ro ver, rw ver, bid)."""
        if ('running_ver' not in self._original_state or 'cr50_image_bid' not in
            self._original_state):
            raise error.TestError('No record of original cr50 image version')
        return (self._original_state['running_ver'][0],
                self._original_state['running_ver'][1],
                self._original_state['cr50_image_bid'])


    def get_saved_cr50_original_path(self):
        """Return the local path for the original cr50 image."""
        if not hasattr(self, '_original_cr50_image'):
            raise error.TestError('No record of original image')
        return self._original_cr50_image


    def has_saved_cr50_dev_path(self):
        """Returns true if we saved the node locked debug image."""
        return hasattr(self, '_node_locked_cr50_image')


    def get_saved_cr50_dev_path(self):
        """Return the local path for the cr50 dev image."""
        if not self.has_saved_cr50_dev_path():
            raise error.TestError('No record of debug image')
        return self._node_locked_cr50_image


    def _restore_original_image(self, chip_bid, chip_flags):
        """Restore the cr50 image and erase the state.

        Make 3 attempts to update to the original image. Use a rollback from
        the DBG image to erase the state that can only be erased by a DBG image.
        Set the chip board id during rollback

        Args:
            chip_bid: the integer representation of chip board id or None if the
                      board id should be erased
            chip_flags: the integer representation of chip board id flags or
                        None if the board id should be erased
        """
        for i in range(3):
            try:
                # Update to the node-locked DBG image so we can erase all of
                # the state we are trying to reset
                self.cr50_update(self._node_locked_cr50_image)

                # Rollback to the original cr50 image.
                self.cr50_update(self._original_cr50_image, rollback=True,
                                 chip_bid=chip_bid, chip_flags=chip_flags)
                break
            except Exception, e:
                logging.warning('Failed to restore original image attempt %d: '
                                '%r', i, e)


    def rootfs_verification_disable(self):
        """Remove rootfs verification."""
        if not self._rootfs_verification_is_disabled():
            logging.debug('Removing rootfs verification.')
            self.rootfs_tool.enable()


    def _rootfs_verification_is_disabled(self):
        """Returns true if rootfs verification is enabled."""
        # Clear the TPM owner before trying to check rootfs verification
        tpm_utils.ClearTPMOwnerRequest(self.host, wait_for_ready=True)
        self.rootfs_tool = debugd_dev_tools.RootfsVerificationTool()
        self.rootfs_tool.initialize(self.host)
        # rootfs_tool.is_enabled is True, that means rootfs verification is
        # disabled.
        return self.rootfs_tool.is_enabled()


    def _restore_original_state(self):
        """Restore the original cr50 related device state."""
        if not (self._saved_state & self.IMAGES):
            logging.warning('Did not save the original images. Cannot restore '
                            'state')
            return
        # Remove the prepvt image if the test installed one.
        if (not self._original_state['has_prepvt'] and
            cr50_utils.HasPrepvtImage(self.host)):
            self.host.run('rm %s' % cr50_utils.CR50_PREPVT)
        # If rootfs verification has been disabled, copy the cr50 device image
        # back onto the DUT.
        if self._rootfs_verification_is_disabled():
            cr50_utils.InstallImage(self.host, self._device_prod_image,
                    cr50_utils.CR50_PROD)
            # Install the prepvt image if there was one.
            if self._device_prepvt_image:
                cr50_utils.InstallImage(self.host, self._device_prepvt_image,
                        cr50_utils.CR50_PREPVT)

        chip_bid_info = self._original_state['chip_bid']
        bid_is_erased = chip_bid_info == cr50_utils.ERASED_CHIP_BID
        chip_bid = None if bid_is_erased else chip_bid_info[0]
        chip_flags = None if bid_is_erased else chip_bid_info[2]
        # Update to the original image and erase the board id
        self._restore_original_image(chip_bid, chip_flags)

        # Set the RLZ code
        cr50_utils.SetRLZ(self.host, self._original_state['rlz'])

        # Verify everything is still the same
        mismatch = self._check_original_state()
        if mismatch:
            raise error.TestError('Could not restore state: %s' % mismatch)

        logging.info('Successfully restored the original cr50 state')


    def get_cr50_device_state(self):
        """Get a dict with the current device cr50 information.

        The state dict will include the platform brand, rlz code, chip board id,
        the running cr50 image version, the running cr50 image board id, and the
        device cr50 image version.
        """
        state = {}
        state['mosys platform brand'] = self.host.run('mosys platform brand',
            ignore_status=True).stdout.strip()
        state['device_prod_ver'] = cr50_utils.GetBinVersion(self.host,
                cr50_utils.CR50_PROD)
        state['has_prepvt'] = cr50_utils.HasPrepvtImage(self.host)
        if state['has_prepvt']:
            state['device_prepvt_ver'] = cr50_utils.GetBinVersion(self.host,
                    cr50_utils.CR50_PREPVT)
        else:
            state['device_prepvt_ver'] = None
        state['rlz'] = cr50_utils.GetRLZ(self.host)
        state['chip_bid'] = cr50_utils.GetChipBoardId(self.host)
        state['chip_bid_str'] = '%08x:%08x:%08x' % state['chip_bid']
        state['running_ver'] = cr50_utils.GetRunningVersion(self.host)
        state['cr50_image_bid'] = self.cr50.get_active_board_id_str()

        logging.debug('Current Cr50 state:\n%s', pprint.pformat(state))
        return state


    def _check_original_state(self):
        """Compare the current cr50 state to the original state.

        Returns:
            A dictionary with the state that is wrong as the key and
            the new and old state as the value
        """
        if not (self._saved_state & self.INITIAL_STATE):
            logging.warning('Did not save the original state. Cannot verify it '
                            'matches')
            return
        # Make sure the /var/cache/cr50* state is up to date.
        cr50_utils.ClearUpdateStateAndReboot(self.host)

        mismatch = {}
        new_state = self.get_cr50_device_state()

        for k, new_val in new_state.iteritems():
            original_val = self._original_state[k]
            if new_val != original_val:
                mismatch[k] = 'old: %s, new: %s' % (original_val, new_val)

        if mismatch:
            logging.warning('State Mismatch:\n%s', pprint.pformat(mismatch))
        else:
            logging.info('The device is in the original state')
        return mismatch


    def _reset_ccd_settings(self):
        """Reset the ccd lock and capability states."""
        # Clear the password if one was set.
        if self.cr50.get_ccd_info()['Password'] != 'none':
            self.servo.set_nocheck('cr50_testlab', 'open')
            self.cr50.send_command('ccd reset')
            if self.cr50.get_ccd_info()['Password'] != 'none':
                raise error.TestFail('Could not clear password')

        current_settings = self.cr50.get_cap_dict(info=self.cr50.CAP_SETTING)
        if self.original_ccd_settings != current_settings:
            if not self.can_set_ccd_level:
                raise error.TestError("CCD state has changed, but we can't "
                        "restore it")
            self.fast_open(True)
            self.cr50.set_caps(self.original_ccd_settings)

        # First try using testlab open to open the device
        if self.cr50.testlab_is_on() and self.original_ccd_level == 'open':
            self.servo.set_nocheck('cr50_testlab', 'open')
        if self.original_ccd_level != self.cr50.get_ccd_level():
            self.cr50.set_ccd_level(self.original_ccd_level)



    def cleanup(self):
        """Attempt to cleanup the cr50 state. Then run firmware cleanup"""
        try:
            self._restore_cr50_state()
        finally:
            super(Cr50Test, self).cleanup()

        # Check the logs captured during firmware_test cleanup for cr50 errors.
        self._get_cr50_stats_from_uart_capture()


    def _get_cr50_stats_from_uart_capture(self):
        """Check cr50 uart output for errors.

        Use the logs captured during firmware_test cleanup to check for cr50
        errors. Flash operation issues aren't obvious unless you check the logs.
        All flash op errors print do_flash_op and it isn't printed during normal
        operation. Open the cr50 uart file and count the number of times this is
        printed. Log the number of errors.
        """
        if not hasattr(self, 'cr50_uart_file'):
            logging.info('There is not a cr50 uart file')
            return

        flash_error_count = 0
        with open(self.cr50_uart_file, 'r') as f:
            for line in f:
                if self.CR50_FLASH_OP_ERROR_MSG in line:
                    flash_error_count += 1

        # Log any flash operation errors.
        logging.info('do_flash_op count: %d', flash_error_count)


    def _restore_cr50_state(self):
        """Restore cr50 state, so the device can be used for further testing"""
        state_mismatch = self._check_original_state()
        if state_mismatch and not self._provision_update:
            self._restore_original_state()
            if self._raise_error_on_mismatch:
                raise error.TestError('Unexpected state mismatch during '
                                      'cleanup %s' % state_mismatch)

        # Try to open cr50 and enable testlab mode if it isn't enabled.
        try:
            self.fast_open(True)
        except:
            # Even if we can't open cr50, do our best to reset the rest of the
            # system state. Log a warning here.
            logging.warning('Unable to Open cr50', exc_info=True)
        # Reset the password as the first thing in cleanup. It is important that
        # if some other part of cleanup fails, the password has at least been
        # reset.
        self.cr50.send_command('rddkeepalive disable')
        self.cr50.send_command('ccd reset')
        self.cr50.send_command('wp follow_batt_pres atboot')

        # Reboot cr50 if the console is accessible. This will reset most state.
        if self.cr50.get_cap('GscFullConsole')[self.cr50.CAP_IS_ACCESSIBLE]:
            self.cr50.reboot()

        # Reenable servo v4 CCD
        self.cr50.ccd_enable()

        # reboot to normal mode if the device is in dev mode.
        self.enter_mode_after_checking_tpm_state('normal')

        tpm_utils.ClearTPMOwnerRequest(self.host, wait_for_ready=True)
        self.clear_fwmp()

        # Restore the ccd privilege level
        if hasattr(self, 'original_ccd_level'):
            self._reset_ccd_settings()


    def find_cr50_gs_image(self, filename, image_type=None):
        """Find the cr50 gs image name.

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


    def download_cr50_gs_image(self, filename, image_bid='', bucket=None,
                               image_type=None):
        """Get the image from gs and save it in the autotest dir.

        Args:
            filename: The cr50 image basename
            image_bid: the board id info list or string. It will be added to the
                       filename.
            bucket: The gs bucket name
            image_type: 'debug' or 'release'. This will be used to determine
                        the bucket if the bucket is not given.
        Returns:
            A tuple with the local path and version
        """
        # Add the image bid string to the filename
        if image_bid:
            bid_str = cr50_utils.GetBoardIdInfoString(image_bid,
                                                       symbolic=True)
            filename += '.' + bid_str.replace(':', '_')

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
        ver = cr50_utils.GetBinVersion(self.host, src)

        # Compare the image board id to the downloaded image to make sure we got
        # the right file
        downloaded_bid = cr50_utils.GetBoardIdInfoString(ver[2], symbolic=True)
        if image_bid and bid_str != downloaded_bid:
            raise error.TestError('Could not download image with matching '
                                  'board id wanted %s got %s' % (bid_str,
                                  downloaded_bid))
        return dest, ver


    def download_cr50_debug_image(self, devid, image_bid=''):
        """download the cr50 debug file.

        Get the file with the matching devid and image board id info

        Args:
            devid: the cr50_devid string '${DEVID0} ${DEVID1}'
            image_bid: the image board id info string or list
        Returns:
            A tuple with the debug image local path and version
        """
        # Debug images are node locked with the devid. Add the devid to the
        # filename
        filename = self.CR50_DEBUG_FILE % (devid.replace(' ', '_'))

        # Download the image
        dest, ver = self.download_cr50_gs_image(filename, image_bid=image_bid,
                                                image_type='debug')

        return dest, ver


    def download_cr50_release_image(self, image_rw, image_bid=''):
        """download the cr50 release file.

        Get the file with the matching version and image board id info

        Args:
            image_rw: the rw version string
            image_bid: the image board id info string or list
        Returns:
            A tuple with the release image local path and version
        """
        # Release images can be found using the rw version
        filename = self.CR50_PROD_FILE % image_rw

        # Download the image
        dest, ver = self.download_cr50_gs_image(filename, image_bid=image_bid,
                                                image_type='release')

        # Compare the rw version and board id info to make sure the right image
        # was found
        if image_rw != ver[1]:
            raise error.TestError('Could not download image with matching '
                                  'rw version')
        return dest, ver


    def _cr50_verify_update(self, expected_rw, expect_rollback):
        """Verify the expected version is running on cr50.

        Args:
            expected_rw: The RW version string we expect to be running
            expect_rollback: True if cr50 should have rolled back during the
                             update

        Raises:
            TestFail if there is any unexpected update state
        """
        errors = []
        running_rw = self.cr50.get_version()
        if expected_rw != running_rw:
            errors.append('running %s not %s' % (running_rw, expected_rw))

        if expect_rollback != self.cr50.rolledback():
            errors.append('%srollback detected' %
                          'no ' if expect_rollback else '')
        if len(errors):
            raise error.TestFail('cr50_update failed: %s' % ', '.join(errors))
        logging.info('RUNNING %s after %s', expected_rw,
                     'rollback' if expect_rollback else 'update')


    def _cr50_run_update(self, path):
        """Install the image at path onto cr50.

        Args:
            path: the location of the image to update to

        Returns:
            the rw version of the image
        """
        tmp_dest = '/tmp/' + os.path.basename(path)

        dest, image_ver = cr50_utils.InstallImage(self.host, path, tmp_dest)
        cr50_utils.GSCTool(self.host, ['-a', dest])
        return image_ver[1]


    def cr50_update(self, path, rollback=False, erase_nvmem=False,
                    expect_rollback=False, chip_bid=None, chip_flags=None):
        """Attempt to update to the given image.

        If rollback is True, we assume that cr50 is already running an image
        that can rollback.

        Args:
            path: the location of the update image
            rollback: True if we need to force cr50 to rollback to update to
                      the given image
            erase_nvmem: True if we need to erase nvmem during rollback
            expect_rollback: True if cr50 should rollback on its own
            chip_bid: the integer representation of chip board id or None if the
                      board id should be erased during rollback
            chip_flags: the integer representation of chip board id flags or
                        None if the board id should be erased during rollback

        Raises:
            TestFail if the update failed
        """
        original_rw = self.cr50.get_version()

        # Cr50 is going to reject an update if it hasn't been up for more than
        # 60 seconds. Wait until that passes before trying to run the update.
        self.cr50.wait_until_update_is_allowed()

        image_rw = self._cr50_run_update(path)

        # Running the update may cause cr50 to reboot. Wait for that before
        # sending more commands. The reboot should happen quickly. Wait a
        # maximum of 10 seconds.
        self.cr50.wait_for_reboot(timeout=10)

        if erase_nvmem and rollback:
            self.cr50.erase_nvmem()

        if rollback:
            self.cr50.rollback(chip_bid=chip_bid, chip_flags=chip_flags)

        expected_rw = original_rw if expect_rollback else image_rw
        # If we expect a rollback, the version should remain unchanged
        self._cr50_verify_update(expected_rw, rollback or expect_rollback)


    def ccd_open_from_ap(self):
        """Start the open process and press the power button."""
        self._ccd_open_last_len = 0

        self._ccd_open_stdout = StringIO.StringIO()

        ccd_open_cmd = utils.sh_escape('gsctool -a -o')
        full_ssh_cmd = '%s "%s"' % (self.host.ssh_command(options='-tt'),
            ccd_open_cmd)
        # Start running the Cr50 Open process in the background.
        self._ccd_open_job = utils.BgJob(full_ssh_cmd,
                                         nickname='ccd_open',
                                         stdout_tee=self._ccd_open_stdout,
                                         stderr_tee=utils.TEE_TO_LOGS)

        if self._ccd_open_job == None:
            raise error.TestFail('could not start ccd open')

        try:
            # Wait for the first gsctool power button prompt before starting the
            # open process.
            logging.info(self._get_ccd_open_output())
            # Cr50 starts out by requesting 5 quick presses then 4 longer
            # power button presses. Run the quick presses without looking at the
            # command output, because getting the output can take some time. For
            # the presses that require a 1 minute wait check the output between
            # presses, so we can catch errors
            #
            # run quick presses for 30 seconds. It may take a couple of seconds
            # for open to start. 10 seconds should be enough. 30 is just used
            # because it will definitely be enough, and this process takes 300
            # seconds, so doing quick presses for 30 seconds won't matter.
            end_time = time.time() + 30
            while time.time() < end_time:
                self.servo.power_short_press()
                logging.info('short int power button press')
                time.sleep(self.PP_SHORT_INTERVAL)
            # Poll the output and press the power button for the longer presses.
            utils.wait_for_value(self._check_open_and_press_power_button,
                expected_value=True, timeout_sec=self.cr50.PP_LONG)
        except Exception, e:
            logging.info(e)
            raise
        finally:
            self._close_ccd_open_job()
        logging.info(self.cr50.get_ccd_info())


    def _check_open_and_press_power_button(self):
        """Check stdout and press the power button if prompted.

        Returns:
            True if the process is still running.
        """
        logging.info(self._get_ccd_open_output())
        self.servo.power_short_press()
        logging.info('long int power button press')
        # Give cr50 some time to complete the open process. After the last
        # power button press cr50 erases nvmem and resets the dut before setting
        # the state to open. Wait a bit so we don't check the ccd state in the
        # middle of this reset process. Power button requests happen once a
        # minute, so waiting 10 seconds isn't a big deal.
        time.sleep(10)
        return (self._ccd_open_job.sp.poll() is not None or 'Open' in
                self.cr50.get_ccd_info()['State'])


    def _get_ccd_open_output(self):
        """Read the new output."""
        self._ccd_open_job.process_output()
        self._ccd_open_stdout.seek(self._ccd_open_last_len)
        output = self._ccd_open_stdout.read()
        self._ccd_open_last_len = self._ccd_open_stdout.len
        return output


    def _close_ccd_open_job(self):
        """Terminate the process and check the results."""
        exit_status = utils.nuke_subprocess(self._ccd_open_job.sp)
        stdout = self._ccd_open_stdout.getvalue().strip()
        delattr(self, '_ccd_open_job')
        if stdout:
            logging.info('stdout of ccd open:\n%s', stdout)
        if exit_status:
            logging.info('exit status: %d', exit_status)
        if 'Error' in stdout:
            raise error.TestFail('ccd open Error %s' %
                                 stdout.split('Error')[-1])
        if self.cr50.OPEN != self.cr50.get_ccd_level():
            raise error.TestFail('unable to open cr50: %s' % stdout)
        else:
            logging.info('Opened Cr50')


    def fast_open(self, enable_testlab=False):
        """Try to use testlab open. If that fails, do regular ap open.

        Args:
            enable_testlab: If True, enable testlab mode after cr50 is open.
        """
        # Try to use testlab open first, so we don't have to wait for the
        # physical presence check.
        self.cr50.send_command('ccd testlab open')
        if self.cr50.get_ccd_level() == 'open':
            return

        # Use the console to open cr50 without entering dev mode if possible. It
        # takes longer and relies on more systems to enter dev mode and ssh into
        # the AP. Skip the steps that aren't required.
        if not self.cr50.get_cap('OpenNoDevMode')[self.cr50.CAP_IS_ACCESSIBLE]:
            self.enter_mode_after_checking_tpm_state('dev')

        if self.cr50.get_cap('OpenFromUSB')[self.cr50.CAP_IS_ACCESSIBLE]:
            self.cr50.set_ccd_level(self.cr50.OPEN)
        else:
            self.ccd_open_from_ap()

        if enable_testlab:
            self.cr50.set_ccd_testlab('on')

        # Make sure the device is in normal mode. After opening cr50, the TPM
        # should be cleared and the device should automatically reset to normal
        # mode. Just check to be consistent. It's possible capabilitiy settings
        # are set to skip wiping the TPM.
        self.enter_mode_after_checking_tpm_state('normal')


    def run_gsctool_cmd_with_password(self, password, cmd, name, expect_error):
        """Run a gsctool command and input the password

        Args:
            password: The cr50 password string
            cmd: The gsctool command
            name: The name to give the job
            expect_error: True if the command should fail
        """
        set_pwd_cmd = utils.sh_escape(cmd)
        full_ssh_command = '%s "%s"' % (self.host.ssh_command(options='-tt'),
            set_pwd_cmd)
        stdout = StringIO.StringIO()
        # Start running the gsctool Command in the background.
        gsctool_job = utils.BgJob(full_ssh_command,
                                  nickname='%s_with_password' % name,
                                  stdout_tee=stdout,
                                  stderr_tee=utils.TEE_TO_LOGS,
                                  stdin=subprocess.PIPE)
        if gsctool_job == None:
            raise error.TestFail('could not start gsctool command %r', cmd)

        try:
            # Wait for enter prompt
            gsctool_job.process_output()
            logging.info(stdout.getvalue().strip())
            # Enter the password
            gsctool_job.sp.stdin.write(password + '\n')

            # Wait for re-enter prompt
            gsctool_job.process_output()
            logging.info(stdout.getvalue().strip())
            # Re-enter the password
            gsctool_job.sp.stdin.write(password + '\n')
            time.sleep(self.cr50.CONSERVATIVE_CCD_WAIT)
            gsctool_job.process_output()
        finally:
            exit_status = utils.nuke_subprocess(gsctool_job.sp)
            output = stdout.getvalue().strip()
            logging.info('%s stdout: %s', name, output)
            logging.info('%s exit status: %s', name, exit_status)
            if exit_status:
                message = ('gsctool %s failed using %r: %s %s' %
                           (name, password, exit_status, output))
                if expect_error:
                    logging.info(message)
                else:
                    raise error.TestFail(message)
            elif expect_error:
                raise error.TestFail('%s with %r did not fail when expected' %
                                     (name, password))


    def set_ccd_password(self, password, expect_error=False):
        """Set the ccd password"""
        # If for some reason the test sets a password and is interrupted before
        # we can clear it, we want testlab mode to be enabled, so it's possible
        # to clear the password without knowing it.
        if not self.cr50.testlab_is_on():
            raise error.TestError('Will not set password unless testlab mode '
                                  'is enabled.')
        self.run_gsctool_cmd_with_password(
                password, 'gsctool -a -P', 'set_password', expect_error)


    def ccd_unlock_from_ap(self, password=None, expect_error=False):
        """Unlock cr50"""
        if not password:
            self.host.run('gsctool -a -U')
            return
        self.run_gsctool_cmd_with_password(
                password, 'gsctool -a -U', 'unlock', expect_error)


    def enter_mode_after_checking_tpm_state(self, mode):
        """Reboot to mode if cr50 doesn't already match the state"""
        # If the device is already in the correct mode, don't do anything
        if (mode == 'dev') == self.cr50.in_dev_mode():
            logging.info('already in %r mode', mode)
            return

        self.switcher.reboot_to_mode(to_mode=mode)

        if (mode == 'dev') != self.cr50.in_dev_mode():
            raise error.TestError('Unable to enter %r mode' % mode)


    def _fwmp_is_cleared(self):
        """Return True if the FWMP has been created"""
        res = self.host.run('cryptohome '
                            '--action=get_firmware_management_parameters',
                            ignore_status=True)
        if res.exit_status and res.exit_status != self.CLEARED_FWMP_EXIT_STATUS:
            raise error.TestError('Could not run cryptohome command %r' % res)
        return self.CLEARED_FWMP_ERROR_MSG in res.stdout


    def clear_fwmp(self):
        """Clear the FWMP"""
        if self._fwmp_is_cleared():
            return
        status = self.host.run('cryptohome --action=tpm_status').stdout
        logging.debug(status)
        if 'TPM Owned: true' not in status:
            self.host.run('cryptohome --action=tpm_take_ownership')
            self.host.run('cryptohome --action=tpm_wait_ownership')
        self.host.run('cryptohome '
                      '--action=remove_firmware_management_parameters')

    def tpm_is_responsive(self):
        """Check TPM responsiveness by running tpm_version."""
        result = self.host.run('tpm_version', ignore_status=True)
        logging.debug(result.stdout.strip())
        return not result.exit_status
