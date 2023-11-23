# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import json
import os

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.enterprise import enterprise_policy_base


class login_SavePassword(enterprise_policy_base.EnterprisePolicyTest):
    """
    Test to make sure that during logon, the user's password is defined in the
    keyring if and only if the ${PASSWORD} variable is defined in the user's
    OpenNetworkConfiguration policy.

    """

    version = 1

    def initialize(self):
        """
        Initialize this test.

        """
        super(login_SavePassword, self).initialize()

    def run_once(self, onc_definition, expect_password):
        """
        Run the test.

        @param onc_definition: Filename containing an OpenNetworkConfiguration
                               definition.

        @param expect_password: True if the password is expected to be present
                                in the keyring, False otherwise.

        """
        with open(os.path.join(self.bindir, onc_definition)) as f:
            data = json.load(f)
        self.setup_case(user_policies={'OpenNetworkConfiguration': data})

        # Check the /proc/keys file to see if a password key is defined.
        password_file_cmd = 'cat /proc/keys | grep password:'
        try:
            output = utils.run(password_file_cmd).stdout
            # If there is a password key, check to see the password is the same.
            saved_password = utils.run("keyctl pipe 0x" +
                                       output.split(" ")[0]).stdout
            # self.password is set by the base class
            if expect_password and saved_password != self.password:
                raise error.TestFail(
                        'Password is not saved for ONC with ${PASSWORD} '
                        'variable. Expected: %s Saved: %s',
                        self.password, saved_password)
            elif not expect_password and saved_password:
                raise error.TestFail(
                        'Password is saved but ONC did not have ${PASSWORD} '
                        'variable')
        except error.CmdError:
            if expect_password:
                raise error.TestFail(
                        'Password is not saved for ONC with ${PASSWORD} '
                        'variable')
