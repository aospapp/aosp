# Copyright 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib import utils

from autotest_lib.client.common_lib.cros import arc
from autotest_lib.client.cros.enterprise import enterprise_policy_base


class policy_ArcVideoCaptureAllowed(
        enterprise_policy_base.EnterprisePolicyTest):
    """
    Test effect of the ArcVideoCaptureAllowed ChromeOS policy on ARC.

    This test will launch the ARC container via the ArcEnabled policy, then
    will check the behavior of the passthrough policy VideoCaptureAllowed.

    When the policy is set to False, Video Capture is not allowed. To test
    this, we will attemp to launch the ARC Camera, and check the logs to see
    if the Camera was launched or not.

    """
    version = 1

    def _test_Arc_cam_status(self, case):
        """
        Test if the Arc Camera has been opened, or not.

        @param case: bool, value of the VideoCaptureAllowed policy.

        """

        #  The Camera can take a few seconds to respond, wait for it.
        utils.poll_for_condition(
            lambda: self.did_cam_app_respond(),
            exception=error.TestFail('Camera APP did not respond.'),
            timeout=10,
            sleep_interval=1,
            desc='Wait for Camera to respond.')

        #  Once the Camera is open, get the status from logcat.
        cam_device_resp, disabled_resp = self._check_cam_status()

        if case or case is None:
            if 'opened successfully' not in cam_device_resp or disabled_resp:
                raise error.TestFail(
                    'Camera did not launch when it should have.')
        else:
            if ('opened successfully' in cam_device_resp or
                'disabled by policy' not in disabled_resp):
                raise error.TestFail(
                    'Camera did launch when it should not have.')

    def _launch_Arc_Cam(self):
        """Grant the Camera location permission, and launch the Camera app."""
        arc.adb_shell('pm grant com.google.android.GoogleCameraArc android.permission.ACCESS_COARSE_LOCATION')
        arc.adb_shell('am start -a android.media.action.IMAGE_CAPTURE')

    def _check_cam_status(self):
        """Returns the specified section from loggcat."""
        return [arc.adb_shell("logcat -d | grep 'Camera device'"),
                arc.adb_shell("logcat -d | grep 'CAMERA_DISABLED'")]

    def did_cam_app_respond(self):
        """
        Check if the Camera app has responded to the start command via
        data in the logs being populated.

        @return: True/False, if the Camera has responded to the start command.

        """

        cam_logs = self._check_cam_status()
        if cam_logs[0] or cam_logs[1]:
            return True
        return False

    def run_once(self, case):
        """
        Setup and run the test configured for the specified test case.

        @param case: Name of the test case to run.

        """
        pol = {'ArcEnabled': True,
               'VideoCaptureAllowed': case}

        self.setup_case(user_policies=pol,
                        arc_mode='enabled',
                        use_clouddpc_test=False)
        self._launch_Arc_Cam()
        self._test_Arc_cam_status(case)
