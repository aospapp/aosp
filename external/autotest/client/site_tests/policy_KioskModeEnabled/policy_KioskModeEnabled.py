# Copyright 2019 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.enterprise import enterprise_policy_base
from autotest_lib.client.common_lib import utils

KIOSK_MODE = 'Starting kiosk mode...'


class policy_KioskModeEnabled(
        enterprise_policy_base.EnterprisePolicyTest):
    """Test for verifying that the DUT entered kiosk mode."""
    version = 1


    def run_once(self):
        """Entry point of this test."""

        # ID of the kiosk app to start.
        kId = 'afhcomalholahplbjhnmahkoekoijban'

        self.DEVICE_POLICIES = {
        'DeviceLocalAccounts':[
            {'account_id': kId, 'kiosk_app':{'app_id': kId}, 'type': 1}],
        'DeviceLocalAccountAutoLoginId':kId
        }

        self.setup_case(
            device_policies=self.DEVICE_POLICIES,
            enroll=True,
            kiosk_mode=True,
            auto_login=False)
        running_apps = utils.system_output(
            'cat /var/log/messages | grep kiosk')
        # Currently this is the best way I can think of to check if DUT entered
        # kiosk mode. This isn't ideal but it's better than what we have now.
        # TODO(rzakarian): Find another way to verify that kiosk mode is up.
        # crbug.com/934500.
        if KIOSK_MODE not in running_apps:
            raise error.TestFail(
                'DUT did not enter kiosk mode. and it should have.')
