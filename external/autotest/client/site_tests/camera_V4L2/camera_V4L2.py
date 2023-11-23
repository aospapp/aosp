# Copyright (c) 2010 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import glob
import logging
import ntpath
import os
import stat
from autotest_lib.client.bin import test, utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.video import device_capability


class camera_V4L2(test.test):
    version = 1
    preserve_srcdir = True
    v4l2_major_dev_num = 81
    v4l2_minor_dev_num_min = 0
    v4l2_minor_dev_num_max = 64

    def setup(self):
        # TODO(jiesun): make binary here when cross compile issue is resolved.
        os.chdir(self.srcdir)
        utils.make('clean')
        utils.make()

    def run_once(self, capability=None, test_list=None):
        if capability is not None:
            device_capability.DeviceCapability().ensure_capability(capability)
        # Enable USB camera HW timestamp
        path = "/sys/module/uvcvideo/parameters/hwtimestamps"
        if os.path.exists(path):
            utils.system("echo 1 > %s" % path)

        if test_list is None:
            test_list = "halv3" if self.should_test_halv3() else "default"
        self.test_list = test_list

        self.dut_board = utils.get_current_board()
        self.find_video_capture_devices()

        for device in self.v4l2_devices:
            self.usb_info = self.get_camera_device_usb_info(device)
            if not self.usb_info:
                continue
            self.run_v4l2_unittests(device)
            self.run_v4l2_capture_test(device)

    def should_test_halv3(self):
        has_v3 = os.path.exists('/usr/bin/cros_camera_service')
        has_v1 = os.path.exists('/usr/bin/arc_camera_service')
        return has_v3 and not has_v1

    def get_camera_device_usb_info(self, device):
        device_name = ntpath.basename(device)
        vid_path = "/sys/class/video4linux/%s/device/../idVendor" % device_name
        pid_path = "/sys/class/video4linux/%s/device/../idProduct" % device_name
        if not os.path.isfile(vid_path) or not os.path.isfile(pid_path):
            logging.info("Device %s is not a USB camera", device)
            return None

        with open(vid_path, 'r') as f_vid, open(pid_path, 'r') as f_pid:
            vid = f_vid.read()
            pid = f_pid.read()
        return vid.strip() + ":" + pid.strip()

    def is_v4l2_capture_device(self, device):
        executable = os.path.join(self.bindir, "media_v4l2_is_capture_device")
        cmd = "%s %s" % (executable, device)
        logging.info("Running %s", cmd)
        return utils.system(cmd, ignore_status=True) == 0

    def find_video_capture_devices(self):
        self.v4l2_devices = []
        for device in glob.glob("/dev/video*"):
            statinfo = os.stat(device)
            if (stat.S_ISCHR(statinfo.st_mode) and
                    os.major(statinfo.st_rdev) == self.v4l2_major_dev_num and
                    os.minor(statinfo.st_rdev) >=
                    self.v4l2_minor_dev_num_min and
                    os.minor(statinfo.st_rdev) < self.v4l2_minor_dev_num_max and
                    self.is_v4l2_capture_device(device)):
                self.v4l2_devices.append(device)
        logging.info("Detected devices: %s\n", self.v4l2_devices)
        if not self.v4l2_devices:
            raise error.TestFail("No V4L2 devices found!")

    def run_v4l2_unittests(self, device):
        options = ["--device=%s" % device, "--usb-info=%s" % self.usb_info]
        if self.test_list:
            options += ["--test-list=%s" % self.test_list]
        executable = os.path.join(self.bindir, "media_v4l2_unittest")
        cmd = "%s %s" % (executable, " ".join(options))
        logging.info("Running %s", cmd)
        stdout = utils.system_output(cmd, retain_output=True)

    def run_v4l2_capture_test(self, device):
        options = ["--device=%s" % device, "--usb-info=%s" % self.usb_info]
        if self.test_list:
            options += ["--test-list=%s" % self.test_list]

        # snappy old SKU cannot meet the requirement. Skip the test to avoid
        # alarm. Please see http://crbug.com/737874 for detail.
        if self.dut_board == 'snappy' and self.test_list == 'default':
            options += ["--gtest_filter='-*MaximumSupportedResolution*'"]

        executable = os.path.join(self.bindir, "media_v4l2_test")
        cmd = "%s %s" % (executable, " ".join(options))
        logging.info("Running %s", cmd)
        stdout = utils.system_output(cmd, retain_output=True)
