# Copyright (c) 2011 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging, tempfile
from dbus.mainloop.glib import DBusGMainLoop

from autotest_lib.client.bin import test
from autotest_lib.client.common_lib import autotemp, error
from autotest_lib.client.common_lib.cros import policy, session_manager
from autotest_lib.client.cros import cros_ui, cryptohome, ownership


class login_OwnershipApi(test.test):
    """Tests to ensure that the Ownership API works for a local device owner.
    """
    version = 1

    _tempdir = None


    def initialize(self):
        super(login_OwnershipApi, self).initialize()
        policy.install_protobufs(self.autodir, self.job)
        self._bus_loop = DBusGMainLoop(set_as_default=True)

        # Clear existing ownership and inject known keys.
        cros_ui.stop()
        ownership.clear_ownership_files_no_restart()

        # Make device already owned by ownership.TESTUSER.
        cryptohome.mount_vault(ownership.TESTUSER,
                               ownership.TESTPASS,
                               create=True)
        ownership.use_known_ownerkeys(ownership.TESTUSER)

        self._tempdir = autotemp.tempdir(unique_id=self.__class__.__name__)
        cros_ui.start()


    def __generate_temp_filename(self, dir):
        """Generate a guaranteed-unique filename in dir."""
        just_for_name = tempfile.NamedTemporaryFile(dir=dir, delete=True)
        basename = just_for_name.name
        just_for_name.close()  # deletes file.
        return basename


    def run_once(self):
        pkey = ownership.known_privkey()
        pubkey = ownership.known_pubkey()
        sm = session_manager.connect(self._bus_loop)
        sm.StartSession(ownership.TESTUSER, '')

        poldata = policy.build_policy_data(owner=ownership.TESTUSER,
                                           guests=False,
                                           new_users=True,
                                           roaming=True,
                                           whitelist=(ownership.TESTUSER,
                                                      'a@b.c'))

        policy_string = policy.generate_policy(pkey, pubkey, poldata)
        policy.push_policy_and_verify(policy_string, sm)
        retrieved_policy = policy.get_policy(sm)
        if retrieved_policy is None: raise error.TestFail('Policy not found')
        policy.compare_policy_response(retrieved_policy,
                                       owner=ownership.TESTUSER,
                                       guests=False,
                                       new_users=True,
                                       roaming=True,
                                       whitelist=(ownership.TESTUSER, 'a@b.c'))
        try:
            # Sanity check against an incorrect policy
            policy.compare_policy_response(retrieved_policy,
                                           owner=ownership.TESTUSER,
                                           guests=True,
                                           whitelist=(ownership.TESTUSER,
                                                      'a@b.c'))
        except ownership.OwnershipError:
            pass
        else:
            raise error.TestFail('Did not detect bad policy')


    def cleanup(self):
        if self._tempdir: self._tempdir.clean()
        # Best effort to bounce the UI, which may be up or down.
        cros_ui.stop(allow_fail=True)
        cryptohome.unmount_vault(ownership.TESTUSER)
        cryptohome.remove_vault(ownership.TESTUSER)
        cros_ui.start(allow_fail=True, wait_for_login_prompt=False)
        super(login_OwnershipApi, self).cleanup()
