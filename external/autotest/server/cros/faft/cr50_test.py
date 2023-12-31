# Lint as: python2, python3
# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from __future__ import print_function

import logging
import os
import pprint
import six
import time

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error, utils
from autotest_lib.client.common_lib.cros import cr50_utils, tpm_utils
from autotest_lib.server.cros import filesystem_util, gsutil_wrapper
from autotest_lib.server.cros.faft.firmware_test import FirmwareTest


class Cr50Test(FirmwareTest):
    """Base class that sets up helper objects/functions for cr50 tests."""
    version = 1

    RELEASE_POOLS = ['faft-cr50-experimental', 'faft-cr50']
    RESPONSE_TIMEOUT = 180
    GS_PRIVATE = 'gs://chromeos-localmirror-private/distfiles/'
    # Prod signed test images are stored in the private cr50 directory.
    GS_PRIVATE_PROD = GS_PRIVATE + 'cr50/'
    # Node locked test images are in this private debug directory.
    GS_PRIVATE_DBG = GS_PRIVATE + 'chromeos-cr50-debug-0.0.11/'
    GS_PUBLIC = 'gs://chromeos-localmirror/distfiles/'
    CR50_PROD_FILE = 'cr50.r0.0.1*.w%s%s.tbz2'
    CR50_DEBUG_FILE = '*/cr50.dbg.%s.bin.*%s'
    CR50_ERASEFLASHINFO_FILE = (
            '*/cr50_Unknown_NodeLocked-%s_cr50-accessory-mp.bin')
    CR50_QUAL_VERSION_FILE = 'chromeos-cr50-QUAL_VERSION'
    NONE = 0
    # Saved the original device state during init.
    INITIAL_IMAGE_STATE = 1 << 0
    # Saved the original image, the device image, and the debug image. These
    # images are needed to be able to restore the original image and board id.
    DEVICE_IMAGES = 1 << 1
    DBG_IMAGE = 1 << 2
    ERASEFLASHINFO_IMAGE = 1 << 3
    # Different attributes of the device state require the test to download
    # different images. STATE_IMAGE_RESTORES is a dictionary of the state each
    # image type can restore.
    STATE_IMAGE_RESTORES = {
            DEVICE_IMAGES: ['prod_version', 'prepvt_version'],
            DBG_IMAGE: ['running_image_ver', 'running_image_bid', 'chip_bid'],
            ERASEFLASHINFO_IMAGE: ['chip_bid'],
    }
    PP_SHORT_INTERVAL = 3

    def initialize(self,
                   host,
                   cmdline_args,
                   full_args,
                   restore_cr50_image=False,
                   restore_cr50_board_id=False,
                   provision_update=False):
        self._saved_state = self.NONE
        self._raise_error_on_mismatch = not restore_cr50_image
        self._provision_update = provision_update
        self.tot_test_run = full_args.get('tot_test_run', '').lower() == 'true'
        super(Cr50Test, self).initialize(host, cmdline_args)

        if not hasattr(self, 'cr50'):
            raise error.TestNAError('Test can only be run on devices with '
                                    'access to the Cr50 console')
        # TODO(b/149948314): remove when dual-v4 is sorted out.
        if 'ccd' in self.servo.get_servo_version():
            self.servo.disable_ccd_watchdog_for_test()

        logging.info('Test Args: %r', full_args)

        self._devid = self.cr50.get_devid()
        self.can_set_ccd_level = (not self.servo.main_device_is_ccd()
                                  or self.cr50.testlab_is_on())
        self.original_ccd_level = self.cr50.get_ccd_level()
        self.original_ccd_settings = self.cr50.get_cap_dict(
                info=self.cr50.CAP_SETTING)

        self.host = host
        # SSH commands should complete within 3 minutes. Change the default, so
        # it doesn't take half an hour for commands to timeout when the DUT is
        # down.
        self.host.set_default_run_timeout(180)
        tpm_utils.ClearTPMOwnerRequest(self.host, wait_for_ready=True)
        # Clear the FWMP, so it can't disable CCD.
        self.clear_fwmp()

        # TODO(b/218492933) : find better way to disable rddkeepalive
        # Disable rddkeepalive, so the test can disable ccd.
        self.cr50.send_command('ccd testlab open')
        self.cr50.send_command('rddkeepalive disable')
        # faft-cr50 locks and reopens ccd. This will restrict some capabilities
        # c2d2 uses to control the duts. Set the capabilities to Always, so
        # individiual tests don't need to care that much.
        self.cr50.enable_servo_control_caps()

        if self.can_set_ccd_level:
            # Lock cr50 so the console will be restricted
            self.cr50.set_ccd_level('lock')
        elif self.original_ccd_level != 'lock':
            raise error.TestNAError(
                    'Lock the console before running cr50 test')

        self._save_original_state(full_args.get('release_path', ''))

        # Try and download all images necessary to restore cr50 state.
        try:
            self._save_dbg_image(full_args.get('cr50_dbg_image_path', ''))
            self._saved_state |= self.DBG_IMAGE
        except Exception as e:
            logging.warning('Error saving DBG image: %s', str(e))
            if restore_cr50_image:
                raise error.TestNAError('Need DBG image: %s' % str(e))

        try:
            self._save_eraseflashinfo_image(
                    full_args.get('cr50_eraseflashinfo_image_path', ''))
            if self.cr50.uses_board_property('BOARD_EC_CR50_COMM_SUPPORT'):
                raise error.TestError('Board cannot boot EFI image')
            self._saved_state |= self.ERASEFLASHINFO_IMAGE
        except Exception as e:
            logging.warning('Error saving eraseflashinfo image: %s', str(e))
            if restore_cr50_board_id:
                raise error.TestNAError(
                        'Need eraseflashinfo image: %s' % str(e))

        # TODO(b/143888583): remove qual update during init once new design to
        # to provision cr50 updates is in place.
        # Make sure the release image is running before starting the test.
        is_release_qual = full_args.get('is_release_qual',
                                        '').lower() == 'true'
        if is_release_qual or self.running_cr50_release_suite():
            release_ver_arg = full_args.get('release_ver', '')
            release_path_arg = full_args.get('release_path', '')
            self.ensure_qual_image_is_running(release_ver_arg,
                                              release_path_arg)

    def running_cr50_release_suite(self):
        """Return True if the DUT is in a release pool."""
        for pool in self.host.host_info_store.get().pools:
            # TODO(b/149109740): remove once the pool values are verified.
            # Change to run with faft-cr50 and faft-cr50-experimental suites.
            logging.info('Checking pool: %s', pool)
            if pool in self.RELEASE_POOLS:
                logging.info('Running a release test.')
                return True
        return False

    def ensure_qual_image_is_running(self, qual_ver_str, qual_path):
        """Update to the qualification image if it's not running.

        qual_ver_str and path are command line args that may be supplied to
        specify a local version or path. If neither are supplied, the version
        from gs will be used to determine what version cr50 should run.

        qual_ver_str and qual_path should not be supplied together. If they are,
        the path will be used. It's not a big deal as long as they agree with
        each other.

        @param qual_ver_str: qualification version string or None.
        @param qual_path: local path to the qualification image or None.
        """
        # Get the local image information.
        if qual_path:
            dest, qual_ver = cr50_utils.InstallImage(self.host, qual_path,
                                                     '/tmp/qual_cr50.bin')
            self.host.run('rm ' + dest)
            qual_bid_str = (cr50_utils.GetBoardIdInfoString(
                    qual_ver[2], False) if qual_ver[2] else '')
            qual_ver_str = '%s/%s' % (qual_ver[1], qual_bid_str)

        # Determine the qualification version from.
        if not qual_ver_str:
            gsurl = os.path.join(self.GS_PRIVATE, self.CR50_QUAL_VERSION_FILE)
            dut_path = self.download_cr50_gs_file(gsurl, False)[1]
            qual_ver_str = self.host.run('cat ' + dut_path).stdout.strip()

        # Download the qualification image based on the version.
        if not qual_path:
            rw, bid = qual_ver_str.split('/')
            qual_path, qual_ver = self.download_cr50_release_image(rw, bid)

        logging.info('Cr50 Qual Version: %s', qual_ver_str)
        logging.info('Cr50 Qual Path: %s', qual_path)
        qual_chip_bid = cr50_utils.GetChipBIDFromImageBID(
                qual_ver[2], self.get_device_brand())
        logging.info('Cr50 Qual Chip BID: %s', qual_chip_bid)

        # Replace only the prod or prepvt image based on the major version.
        if int(qual_ver[1].split('.')[1]) % 2:
            prod_ver = qual_ver
            prepvt_ver = self._original_image_state['prepvt_version']
            prod_path = qual_path
            prepvt_path = self._device_prepvt_image
        else:
            prod_ver = self._original_image_state['prod_version']
            prepvt_ver = qual_ver
            prod_path = self._device_prod_image
            prepvt_path = qual_path

        # Generate a dictionary with all of the expected state.
        qual_state = {}
        qual_state['prod_version'] = prod_ver
        qual_state['prepvt_version'] = prepvt_ver
        qual_state['chip_bid'] = qual_chip_bid
        qual_state['running_image_bid'] = qual_ver[2]
        # The test can't rollback RO. The newest RO should be running at the end
        # of the test. max_ro will be none if the versions are the same. Use the
        # running_ro in that case.
        running_ro = self.get_saved_cr50_original_version()[0]
        max_ro = cr50_utils.GetNewestVersion(running_ro, qual_ver[0])
        qual_state['running_image_ver'] = (max_ro or running_ro, qual_ver[1],
                                           None)
        mismatch = self._check_running_image_and_board_id(qual_state)
        if not mismatch:
            logging.info('Running qual image. No update needed.')
            return
        logging.info('Cr50 qual update required.')
        self.make_rootfs_writable()
        self._update_device_images_and_running_cr50_firmware(
                qual_state, qual_path, prod_path, prepvt_path)
        logging.info("Recording qual device state as 'original' device state")
        self._save_original_state(qual_path)

    def make_rootfs_writable(self):
        """Make rootfs writeable. Recover the dut if necessary."""
        path = None
        try:
            filesystem_util.make_rootfs_writable(self.host)
            return
        except error.AutoservRunError as e:
            if 'cannot remount' not in e.result_obj.stderr:
                raise
            path = e.result_obj.stderr.partition(
                    'cannot remount')[2].split()[0]
        # This shouldn't be possible.
        if not path:
            raise error.TestError('Need path to repair filesystem')
        logging.info('repair %s', path)
        # Repair the block. Assume yes to all questions. The exit status will be
        # 3, so ignore errors. make_rootfs_writable will fail if something
        # actually went wrong.
        self.host.run('e2fsck -y %s' % path, ignore_status=True)
        self.host.reboot()
        filesystem_util.make_rootfs_writable(self.host)

    def _saved_cr50_state(self, state):
        """Returns True if the test has saved the given state

        @param state: a integer representing the state to check.
        """
        return state & self._saved_state

    def after_run_once(self):
        """Log which iteration just ran"""
        logging.info('successfully ran iteration %d', self.iteration)
        self._try_to_bring_dut_up()

    def _save_dbg_image(self, cr50_dbg_image_path):
        """Save or download the node locked dev image.

        @param cr50_dbg_image_path: The path to the node locked cr50 image.
        """
        if os.path.isfile(cr50_dbg_image_path):
            self._dbg_image_path = cr50_dbg_image_path
        else:
            self._dbg_image_path = self.download_cr50_debug_image()[0]

    def _save_eraseflashinfo_image(self, cr50_eraseflashinfo_image_path):
        """Save or download the node locked eraseflashinfo image.

        @param cr50_eraseflashinfo_image_path: The path to the node locked cr50
                                               image.
        """
        if os.path.isfile(cr50_eraseflashinfo_image_path):
            self._eraseflashinfo_image_path = cr50_eraseflashinfo_image_path
        else:
            self._eraseflashinfo_image_path = (
                    self.download_cr50_eraseflashinfo_image()[0])

    def _save_device_image(self, ext):
        """Download the .prod or .prepvt device image and get the version.

        @param ext: The Cr50 file extension: prod or prepvt.
        @returns (local_path, rw_version, bid_string) or (None, None, None) if
                 the file doesn't exist on the DUT.
        """
        version = self._original_image_state[ext + '_version']
        if not version:
            return None, None, None
        _, rw_ver, bid = version
        rw_filename = 'cr50.device.bin.%s.%s' % (ext, rw_ver)
        local_path = os.path.join(self.resultsdir, rw_filename)
        dut_path = cr50_utils.GetDevicePath(ext)
        self.host.get_file(dut_path, local_path)
        bid = cr50_utils.GetBoardIdInfoString(bid)
        return local_path, rw_ver, bid

    def _save_original_images(self, release_path):
        """Use the saved state to find all of the device images.

        This will download running cr50 image and the device image.

        @param release_path: The release path given by test args
        """
        local_path, prod_rw, prod_bid = self._save_device_image('prod')
        self._device_prod_image = local_path

        local_path, prepvt_rw, prepvt_bid = self._save_device_image('prepvt')
        self._device_prepvt_image = local_path

        if os.path.isfile(release_path):
            self._original_cr50_image = release_path
            logging.info('using supplied image')
            return
        if self.tot_test_run:
            self._original_cr50_image = self.download_cr50_tot_image()
            return

        # If the running cr50 image version matches the image on the DUT use
        # the DUT image as the original image. If the versions don't match
        # download the image from google storage
        _, running_rw, running_bid = self.get_saved_cr50_original_version()

        # Convert the running board id to the same format as the prod and
        # prepvt board ids.
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

    def _save_original_state(self, release_path):
        """Save the cr50 related state.

        Save the device's current cr50 version, cr50 board id, the running cr50
        image, the prepvt, and prod cr50 images. These will be used to restore
        the cr50 state during cleanup.

        @param release_path: the optional command line arg of path for the local
                             cr50 image.
        """
        self._saved_state &= ~self.INITIAL_IMAGE_STATE
        self._original_image_state = self.get_image_and_bid_state()
        # We successfully saved the device state
        self._saved_state |= self.INITIAL_IMAGE_STATE
        self._saved_state &= ~self.DEVICE_IMAGES
        try:
            self._save_original_images(release_path)
            self._saved_state |= self.DEVICE_IMAGES
        except Exception as e:
            logging.warning('Error saving ChromeOS image cr50 firmware: %s',
                            str(e))

    def get_saved_cr50_original_version(self):
        """Return (ro ver, rw ver, bid)."""
        if ('running_image_ver' not in self._original_image_state
                    or 'running_image_bid' not in self._original_image_state):
            raise error.TestError('No record of original cr50 image version')
        return (self._original_image_state['running_image_ver'][0],
                self._original_image_state['running_image_ver'][1],
                self._original_image_state['running_image_bid'])

    def get_saved_cr50_original_path(self):
        """Return the local path for the original cr50 image."""
        if not hasattr(self, '_original_cr50_image'):
            raise error.TestError('No record of original image')
        return self._original_cr50_image

    def has_saved_dbg_image_path(self):
        """Returns true if we saved the node locked debug image."""
        return hasattr(self, '_dbg_image_path')

    def get_saved_dbg_image_path(self):
        """Return the local path for the cr50 dev image."""
        if not self.has_saved_dbg_image_path():
            raise error.TestError('No record of debug image')
        return self._dbg_image_path

    def get_saved_eraseflashinfo_image_path(self):
        """Return the local path for the cr50 eraseflashinfo image."""
        if not hasattr(self, '_eraseflashinfo_image_path'):
            raise error.TestError('No record of eraseflashinfo image')
        return self._eraseflashinfo_image_path

    def get_device_brand(self):
        """Returns the 4 character device brand."""
        return self._original_image_state['cros_config / brand-code']

    def _retry_cr50_update(self, image, retries, rollback):
        """Try to update to the given image retries amount of times.

        @param image: The image path.
        @param retries: The number of times to try to update.
        @param rollback: Run rollback after the update.
        @raises TestFail if the update failed.
        """
        for i in range(retries):
            try:
                return self.cr50_update(image, rollback=rollback)
            except Exception as e:
                logging.warning('Failed to update to %s attempt %d: %s',
                                os.path.basename(image), i, str(e))
                logging.info('Sleeping 60 seconds')
                time.sleep(60)
        raise error.TestError(
                'Failed to update to %s' % os.path.basename(image))

    def run_update_to_eraseflashinfo(self):
        """Erase flashinfo using the eraseflashinfo image.

        Update to the DBG image, rollback to the eraseflashinfo, and run
        eraseflashinfo.
        """
        self._retry_cr50_update(self._dbg_image_path, 3, False)
        self._retry_cr50_update(self._eraseflashinfo_image_path, 3, True)
        if not self.cr50.eraseflashinfo():
            raise error.TestError('Unable to erase the board id')

    def eraseflashinfo_and_restore_image(self, image=''):
        """eraseflashinfo and update to the given the image.

        @param image: the image to end on. Use the original test image if no
                      image is given.
        """
        image = image if image else self.get_saved_cr50_original_path()
        self.run_update_to_eraseflashinfo()
        self.cr50_update(image)

    def update_cr50_image_and_board_id(self, image_path, bid):
        """Set the chip board id and updating the cr50 image.

        Make 3 attempts to update to the original image. Use a rollback from
        the DBG image to erase the state that can only be erased by a DBG image.
        Set the chip board id during rollback.

        @param image_path: path of the image to update to.
        @param bid: the board id to set.
        """
        current_bid = cr50_utils.GetChipBoardId(self.host)
        bid_mismatch = current_bid != bid
        set_bid = bid_mismatch and bid != cr50_utils.ERASED_CHIP_BID
        bid_is_erased = current_bid == cr50_utils.ERASED_CHIP_BID
        eraseflashinfo = bid_mismatch and not bid_is_erased

        if (eraseflashinfo
                    and not self._saved_cr50_state(self.ERASEFLASHINFO_IMAGE)):
            raise error.TestFail('Did not save eraseflashinfo image')

        # Remove prepvt and prod iamges, so they don't interfere with the test
        # rolling back and updating to images that my be older than the images
        # on the device.
        if filesystem_util.is_rootfs_writable(self.host):
            self.host.run('rm %s' % cr50_utils.CR50_PREPVT, ignore_status=True)
            self.host.run('rm %s' % cr50_utils.CR50_PROD, ignore_status=True)

        if eraseflashinfo:
            self.run_update_to_eraseflashinfo()

        self._retry_cr50_update(self._dbg_image_path, 3, False)

        chip_bid = bid[0]
        chip_flags = bid[2]
        if set_bid:
            self.cr50.set_board_id(chip_bid, chip_flags)

        self._retry_cr50_update(image_path, 3, True)

    def _discharging_factory_mode_cleanup(self):
        """Try to get the dut back into charging mode.

        Shutdown the DUT, fake disconnect AC, and then turn on the DUT to
        try to recover the EC.

        When Cr50 enters factory mode on Wilco, the EC disables charging.
        Try to run the sequence to get the Wilco EC out of the factory mode
        state, so it reenables charging.
        """
        if self.faft_config.chrome_ec:
            return
        charge_state = self.host.get_power_supply_info()['Battery']['state']
        logging.info('Charge state: %r', charge_state)
        if 'Discharging' not in charge_state:
            logging.info('Charge state is ok')
            return

        if not self.servo.is_servo_v4_type_c():
            raise error.TestError(
                    'Cannot recover charging without Type C servo')
        # Disconnect the charger and reset the dut to recover charging.
        logging.info('Recovering charging')
        self.faft_client.system.run_shell_command('poweroff')
        time.sleep(self.cr50.SHORT_WAIT)
        self.servo.set_nocheck('servo_v4_uart_cmd', 'fakedisconnect 100 20000')
        time.sleep(self.cr50.SHORT_WAIT)
        self._try_to_bring_dut_up()
        charge_state = self.host.get_power_supply_info()['Battery']['state']
        logging.info('Charge state: %r', charge_state)
        if 'Discharging' in charge_state:
            logging.warning('DUT still discharging')

    def _cleanup_required(self, state_mismatch, image_type):
        """Return True if the update can fix something in the mismatched state.

        @param state_mismatch: a dictionary of the mismatched state.
        @param image_type: The integer representing the type of image
        """
        state_image_restores = set(self.STATE_IMAGE_RESTORES[image_type])
        restore = state_image_restores.intersection(state_mismatch.keys())
        if restore and not self._saved_cr50_state(image_type):
            raise error.TestError(
                    'Did not save images to restore %s' % (', '.join(restore)))
        return not not restore

    def _get_image_information(self, ext):
        """Get the image information for the .prod or .prepvt image.

        @param ext: The extension string prod or prepvt
        @param returns: The image version or None if the image doesn't exist.
        """
        dut_path = cr50_utils.GetDevicePath(ext)
        file_exists = self.host.path_exists(dut_path)
        if file_exists:
            return cr50_utils.GetBinVersion(self.host, dut_path)
        return None

    def get_image_and_bid_state(self):
        """Get a dict with the current device cr50 information.

        The state dict will include the platform brand, chip board id, the
        running cr50 image version, the running cr50 image board id, and the
        device cr50 image version.
        """
        state = {}
        state['cros_config / brand-code'] = self.host.run(
                'cros_config / brand-code', ignore_status=True).stdout.strip()
        state['prod_version'] = self._get_image_information('prod')
        state['prepvt_version'] = self._get_image_information('prepvt')
        state['chip_bid'] = cr50_utils.GetChipBoardId(self.host)
        state['chip_bid_str'] = '%08x:%08x:%08x' % state['chip_bid']
        state['running_image_ver'] = cr50_utils.GetRunningVersion(self.host)
        state['running_image_bid'] = self.cr50.get_active_board_id_str()

        logging.debug('Current Cr50 state:\n%s', pprint.pformat(state))
        return state

    def _check_running_image_and_board_id(self, expected_state):
        """Compare the current image and board id to the given state.

        @param expected_state: A dictionary of the state to compare to.
        @return: A dictionary with the state that is wrong as the key and the
                 expected and current state as the value.
        """
        if not (self._saved_state & self.INITIAL_IMAGE_STATE):
            logging.warning(
                    'Did not save the original state. Cannot verify it '
                    'matches')
            return
        # Make sure the /var/cache/cr50* state is up to date.
        cr50_utils.ClearUpdateStateAndReboot(self.host)

        mismatch = {}
        state = self.get_image_and_bid_state()

        for k, expected_val in six.iteritems(expected_state):
            val = state[k]
            if val != expected_val:
                mismatch[k] = 'expected: %s, current: %s' % (expected_val, val)

        if mismatch:
            logging.warning('State Mismatch:\n%s', pprint.pformat(mismatch))
        return mismatch

    def _check_original_image_state(self):
        """Compare the current cr50 state to the original state.

        @return: A dictionary with the state that is wrong as the key and the
                 new and old state as the value
        """
        mismatch = self._check_running_image_and_board_id(
                self._original_image_state)
        if not mismatch:
            logging.info('The device is in the original state')
        return mismatch

    def _reset_ccd_settings(self):
        """Reset the ccd lock and capability states."""
        if not self.cr50.ccd_is_reset():
            # Try to open cr50 and enable testlab mode if it isn't enabled.
            try:
                self.fast_ccd_open(True)
            except:
                # Even if we can't open cr50, do our best to reset the rest of
                # the system state. Log a warning here.
                logging.warning('Unable to Open cr50', exc_info=True)
            self.cr50.ccd_reset(servo_en=False)
            if not self.cr50.ccd_is_reset():
                raise error.TestFail('Could not reset ccd')

        current_settings = self.cr50.get_cap_dict(info=self.cr50.CAP_SETTING)
        if self.original_ccd_settings != current_settings:
            if not self.can_set_ccd_level:
                raise error.TestError("CCD state has changed, but we can't "
                                      "restore it")
            self.fast_ccd_open(True)
            self.cr50.set_caps(self.original_ccd_settings)

        # First try using testlab open to open the device
        if self.original_ccd_level == 'open':
            self.fast_ccd_open(True)
        elif self.original_ccd_level != self.cr50.get_ccd_level():
            self.cr50.set_ccd_level(self.original_ccd_level)

    def fast_ccd_open(self,
                      enable_testlab=False,
                      reset_ccd=True,
                      dev_mode=False):
        """Check for watchdog resets after opening ccd.

        Args:
            enable_testlab: If True, enable testlab mode after cr50 is open.
            reset_ccd: If True, reset ccd after open.
            dev_mode: True if the device should be in dev mode after ccd is
                      is opened.
        """
        try:
            super(Cr50Test, self).fast_ccd_open(enable_testlab, reset_ccd,
                                                dev_mode)
        except Exception as e:
            # Check for cr50 watchdog resets.
            self.cr50.check_for_console_errors('Fast ccd open')
            raise

    def cleanup(self):
        """Attempt to cleanup the cr50 state. Then run firmware cleanup"""
        try:
            # Reset the password as the first thing in cleanup. It is important
            # that if some other part of cleanup fails, the password has at
            # least been reset.
            # DO NOT PUT ANYTHING BEFORE THIS.
            self._try_quick_ccd_cleanup()

            self.servo.enable_main_servo_device()

            self._try_to_bring_dut_up()
            self._restore_cr50_state()

            # Make sure the sarien EC isn't stuck in factory mode.
            self._discharging_factory_mode_cleanup()
        finally:
            super(Cr50Test, self).cleanup()

        # Check the logs captured during firmware_test cleanup for cr50 errors.
        self.cr50.check_for_console_errors('Test Cleanup')
        self.servo.allow_ccd_watchdog_for_test()

    def _update_device_images_and_running_cr50_firmware(
            self, state, release_path, prod_path, prepvt_path):
        """Update cr50, set the board id, and copy firmware to the DUT.

        @param state: A dictionary with the expected running version, board id,
                      device cr50 firmware versions.
        @param release_path: The image to update cr50 to
        @param prod_path: The path to the .prod image
        @param prepvt_path: The path to the .prepvt image
        @raises TestError: if setting any state failed
        """
        mismatch = self._check_running_image_and_board_id(state)
        if not mismatch:
            logging.info('Nothing to do.')
            return

        # Use the DBG image to restore the original image.
        if self._cleanup_required(mismatch, self.DBG_IMAGE):
            self.update_cr50_image_and_board_id(release_path,
                                                state['chip_bid'])

        self._try_to_bring_dut_up()
        new_mismatch = self._check_running_image_and_board_id(state)
        # Copy the original .prod and .prepvt images back onto the DUT.
        if (self._cleanup_required(new_mismatch, self.DEVICE_IMAGES)
                    and filesystem_util.is_rootfs_writable(self.host)):
            # Copy the .prod file onto the DUT.
            if prod_path and 'prod_version' in new_mismatch:
                cr50_utils.InstallImage(self.host, prod_path,
                                        cr50_utils.CR50_PROD)
            # Copy the .prepvt file onto the DUT.
            if prepvt_path and 'prepvt_version' in new_mismatch:
                cr50_utils.InstallImage(self.host, prepvt_path,
                                        cr50_utils.CR50_PREPVT)

        final_mismatch = self._check_running_image_and_board_id(state)
        if final_mismatch:
            raise error.TestError(
                    'Could not update cr50 image state: %s' % final_mismatch)
        logging.info('Successfully updated all device cr50 firmware state.')

    def _restore_device_images_and_running_cr50_firmware(self):
        """Restore the images on the device and the running cr50 image."""
        if self._provision_update:
            return
        mismatch = self._check_original_image_state()
        if not mismatch:
            return
        self._update_device_images_and_running_cr50_firmware(
                self._original_image_state,
                self.get_saved_cr50_original_path(), self._device_prod_image,
                self._device_prepvt_image)

        if self._raise_error_on_mismatch and mismatch:
            raise error.TestError('Unexpected state mismatch during '
                                  'cleanup %s' % mismatch)

    def _try_quick_ccd_cleanup(self):
        """Try to clear all ccd state."""
        # This is just a first pass at cleanup. Don't raise any errors.
        try:
            self.cr50.ccd_enable()
        except Exception as e:
            logging.warning('Ignored exception enabling ccd %r', str(e))
        self.cr50.send_command('ccd testlab open')
        self.cr50.send_command('rddkeepalive disable')
        self.cr50.ccd_reset()
        self.cr50.send_command('wp follow_batt_pres atboot')

    def _restore_ccd_settings(self):
        """Restore the original ccd state."""
        self._try_quick_ccd_cleanup()

        # Reboot cr50 if the console is accessible. This will reset most state.
        if self.cr50.get_cap('GscFullConsole')[self.cr50.CAP_IS_ACCESSIBLE]:
            self.cr50.reboot()

        # Reenable servo v4 CCD
        self.cr50.ccd_enable()

        # reboot to normal mode if the device is in dev mode.
        self.enter_mode_after_checking_cr50_state('normal')

        self._try_to_bring_dut_up()
        tpm_utils.ClearTPMOwnerRequest(self.host, wait_for_ready=True)
        self.clear_fwmp()

        # Restore the ccd privilege level
        self._reset_ccd_settings()

    def _restore_cr50_state(self):
        """Restore cr50 state, so the device can be used for further testing.

        Restore the cr50 image and board id first. Then CCD, because flashing
        dev signed images completely clears the CCD state.
        """
        try:
            self._restore_device_images_and_running_cr50_firmware()
        except Exception as e:
            logging.warning('Issue restoring Cr50 image: %s', str(e))
            raise
        finally:
            self._restore_ccd_settings()

    def find_cr50_gs_image(self, gsurl):
        """Find the cr50 gs image name.

        @param gsurl: the cr50 image location
        @return: a list of the gsutil bucket, filename or None if the file
                 can't be found
        """
        try:
            return utils.gs_ls(gsurl)[0].rsplit('/', 1)
        except error.CmdError:
            logging.info('%s does not exist', gsurl)
            return None

    def _extract_cr50_image(self, archive, fn):
        """Extract the filename from the given archive
        Aargs:
            archive: the archive location on the host
            fn: the file to extract

        Returns:
            The location of the extracted file
        """
        remote_dir = os.path.dirname(archive)
        result = self.host.run('tar xfv %s -C %s' % (archive, remote_dir))
        for line in result.stdout.splitlines():
            if os.path.basename(line) == fn:
                return os.path.join(remote_dir, line)
        raise error.TestFail('%s was not extracted from %s' % (fn, archive))

    def download_cr50_gs_file(self, gsurl, extract_fn):
        """Download and extract the file at gsurl.

        @param gsurl: The gs url for the cr50 image
        @param extract_fn: The name of the file to extract from the cr50 image
                        tarball. Don't extract anything if extract_fn is None.
        @return: a tuple (local path, host path)
        """
        file_info = self.find_cr50_gs_image(gsurl)
        if not file_info:
            raise error.TestFail('Could not find %s' % gsurl)
        bucket, fn = file_info

        remote_temp_dir = '/tmp/'
        src = os.path.join(remote_temp_dir, fn)
        dest = os.path.join(self.resultsdir, fn)

        # Copy the image to the dut
        gsutil_wrapper.copy_private_bucket(
                host=self.host,
                bucket=bucket,
                filename=fn,
                destination=remote_temp_dir)
        if extract_fn:
            src = self._extract_cr50_image(src, extract_fn)
            logging.info('extracted %s', src)
            # Remove .tbz2 from the local path.
            dest = os.path.splitext(dest)[0]

        self.host.get_file(src, dest)
        return dest, src

    def download_cr50_gs_image(self, gsurl, extract_fn, image_bid):
        """Get the image from gs and save it in the autotest dir.

        @param gsurl: The gs url for the cr50 image
        @param extract_fn: The name of the file to extract from the cr50 image
                        tarball. Don't extract anything if extract_fn is None.
        @param image_bid: the image symbolic board id
        @return: A tuple with the local path and version
        """
        dest, src = self.download_cr50_gs_file(gsurl, extract_fn)
        ver = cr50_utils.GetBinVersion(self.host, src)

        # Compare the image board id to the downloaded image to make sure we got
        # the right file
        downloaded_bid = cr50_utils.GetBoardIdInfoString(ver[2], symbolic=True)
        if image_bid and image_bid != downloaded_bid:
            raise error.TestError(
                    'Could not download image with matching '
                    'board id wanted %s got %s' % (image_bid, downloaded_bid))
        return dest, ver

    def download_cr50_eraseflashinfo_image(self):
        """download the cr50 image that allows erasing flashinfo.

        Get the file with the matching devid.

        @return: A tuple with the debug image local path and version
        """
        devid = self._devid.replace(' ', '-').replace('0x', '')
        gsurl = os.path.join(self.GS_PRIVATE_DBG,
                             self.CR50_ERASEFLASHINFO_FILE % devid)
        return self.download_cr50_gs_image(gsurl, None, None)

    def download_cr50_debug_image(self, devid='', image_bid=''):
        """download the cr50 debug file.

        Get the file with the matching devid and image board id info

        @param image_bid: the image board id info string or list
        @return: A tuple with the debug image local path and version
        """
        bid_ext = ''
        # Add the image bid string to the filename
        if image_bid:
            image_bid = cr50_utils.GetBoardIdInfoString(
                    image_bid, symbolic=True)
            bid_ext = '.' + image_bid.replace(':', '_')

        devid = devid if devid else self._devid
        dbg_file = self.CR50_DEBUG_FILE % (devid.replace(' ', '_'), bid_ext)
        gsurl = os.path.join(self.GS_PRIVATE_DBG, dbg_file)
        return self.download_cr50_gs_image(gsurl, None, image_bid)

    def download_cr50_tot_image(self):
        """download the cr50 TOT image.

        @return: the local path to the TOT image.
        """
        # TODO(mruthven): use logic from provision_Cr50TOT
        raise error.TestNAError('Could not download TOT image')

    def _find_release_image_gsurl(self, fn):
        """Find the gs url for the release image"""
        for gsbucket in [self.GS_PUBLIC, self.GS_PRIVATE_PROD]:
            gsurl = os.path.join(gsbucket, fn)
            if self.find_cr50_gs_image(gsurl):
                return gsurl
        raise error.TestFail('%s is not on google storage' % fn)

    def download_cr50_release_image(self, image_rw, image_bid=''):
        """download the cr50 release file.

        Get the file with the matching version and image board id info

        @param image_rw: the rw version string
        @param image_bid: the image board id info string or list
        @return: A tuple with the release image local path and version
        """
        bid_ext = ''
        # Add the image bid string to the gsurl
        if image_bid:
            image_bid = cr50_utils.GetBoardIdInfoString(
                    image_bid, symbolic=True)
            bid_ext = '_' + image_bid.replace(':', '_')
        release_fn = self.CR50_PROD_FILE % (image_rw, bid_ext)
        gsurl = self._find_release_image_gsurl(release_fn)

        # Release images can be found using the rw version
        # Download the image
        dest, ver = self.download_cr50_gs_image(gsurl, 'cr50.bin.prod',
                                                image_bid)

        # Compare the rw version and board id info to make sure the right image
        # was found
        if image_rw != ver[1]:
            raise error.TestError('Could not download image with matching '
                                  'rw version')
        return dest, ver

    def _cr50_verify_update(self, expected_rw, expect_rollback):
        """Verify the expected version is running on cr50.

        @param expected_rw: The RW version string we expect to be running
        @param expect_rollback: True if cr50 should have rolled back during the
                                update
        @raise TestFail: if there is any unexpected update state
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

        @param path: the location of the image to update to
        @return: the rw version of the image
        """
        tmp_dest = '/tmp/' + os.path.basename(path)

        # Make sure the dut is sshable before installing the image.
        self._try_to_bring_dut_up()

        dest, image_ver = cr50_utils.InstallImage(self.host, path, tmp_dest)
        # Use the -p option to make sure the DUT does a clean reboot.
        cr50_utils.GSCTool(self.host, ['-a', dest, '-p'])
        # Reboot the DUT to finish the cr50 update.
        self.host.reboot(wait=False)
        return image_ver[1]

    def cr50_update(self, path, rollback=False, expect_rollback=False):
        """Attempt to update to the given image.

        If rollback is True, we assume that cr50 is already running an image
        that can rollback.

        @param path: the location of the update image
        @param rollback: True if we need to force cr50 to rollback to update to
                         the given image
        @param expect_rollback: True if cr50 should rollback on its own
        @raise TestFail: if the update failed
        """
        original_rw = self.cr50.get_version()

        # Cr50 is going to reject an update if it hasn't been up for more than
        # 60 seconds. Wait until that passes before trying to run the update.
        self.cr50.wait_until_update_is_allowed()

        image_rw = self._cr50_run_update(path)

        # Running the update may cause cr50 to reboot. Wait for that before
        # sending more commands. The reboot should happen quickly.
        self.cr50.wait_for_reboot(
                timeout=self.faft_config.gsc_update_wait_for_reboot)

        if rollback:
            self.cr50.rollback()

        expected_rw = original_rw if expect_rollback else image_rw
        # If we expect a rollback, the version should remain unchanged
        self._cr50_verify_update(expected_rw, rollback or expect_rollback)

    def run_gsctool_cmd_with_password(self, password, cmd, name, expect_error):
        """Run a gsctool command and input the password

        @param password: The cr50 password string
        @param cmd: The gsctool command
        @param name: The name to give the job
        @param expect_error: True if the command should fail
        """
        logging.info('Running: %s', cmd)
        logging.info('Password: %s', password)
        # Make sure the test waits long enough to avoid ccd rate limiting.
        time.sleep(self.cr50.CCD_PASSWORD_RATE_LIMIT)
        full_cmd = "echo -e '%s\n%s\n' | %s" % (password, password, cmd)
        result = self.host.run(full_cmd, ignore_status=expect_error)
        if result.exit_status:
            message = ('gsctool %s failed using %r: %s %s' %
                       (name, password, result.exit_status, result.stderr))
            if expect_error:
                logging.info(message)
            else:
                raise error.TestFail(message)
        elif expect_error:
            raise error.TestFail('%s with %r did not fail when expected' %
                                 (name, password))
        else:
            logging.info('ran %s password command: %r', name, result.stdout)

    def set_ccd_password(self, password, expect_error=False):
        """Set the ccd password"""
        # Testlab mode can't be enabled if there is no power button, so we
        # shouldn't allow setting the password.
        if not self.faft_config.has_powerbutton:
            raise error.TestError('No power button')

        # If for some reason the test sets a password and is interrupted before
        # we can clear it, we want testlab mode to be enabled, so it's possible
        # to clear the password without knowing it.
        if not self.cr50.testlab_is_on():
            raise error.TestError('Will not set password unless testlab mode '
                                  'is enabled.')
        try:
            self.run_gsctool_cmd_with_password(password, 'gsctool -a -P',
                                               'set_password', expect_error)
        finally:
            logging.info('Cr50 password is %s',
                         'cleared' if self.cr50.password_is_reset() else 'set')

    def ccd_unlock_from_ap(self, password=None, expect_error=False):
        """Unlock cr50"""
        if not password:
            self.host.run('gsctool -a -U')
            return
        self.run_gsctool_cmd_with_password(password, 'gsctool -a -U', 'unlock',
                                           expect_error)

    def tpm_is_responsive(self):
        """Check TPM responsiveness by running tpm_version."""
        result = self.host.run('tpm_version', ignore_status=True)
        logging.debug(result.stdout.strip())
        return not result.exit_status
