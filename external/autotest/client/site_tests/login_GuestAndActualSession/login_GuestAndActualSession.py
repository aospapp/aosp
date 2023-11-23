# Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import gobject
from dbus.mainloop.glib import DBusGMainLoop


from autotest_lib.client.bin import test
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import policy, session_manager
from autotest_lib.client.cros import constants, cros_ui, cryptohome, ownership


class login_GuestAndActualSession(test.test):
    """Ensure that the session_manager correctly handles ownership when a guest
       signs in before a real user.
    """
    version = 1


    def initialize(self):
        super(login_GuestAndActualSession, self).initialize()
        policy.install_protobufs(self.autodir, self.job)
        # Ensure a clean beginning.
        ownership.restart_ui_to_clear_ownership_files()

        bus_loop = DBusGMainLoop(set_as_default=True)
        self._session_manager = session_manager.connect(bus_loop)
        self._listener = session_manager.OwnershipSignalListener(
                gobject.MainLoop())
        self._listener.listen_for_new_key_and_policy()


    def run_once(self):
        owner = 'first_user@nowhere.com'

        cryptohome.mount_guest()
        self._session_manager.StartSession(constants.GUEST_USER, '')
        cryptohome.ensure_clean_cryptohome_for(owner)
        self._session_manager.StartSession(owner, '')
        self._listener.wait_for_signals(desc='Device ownership complete.')

        # Ensure that the first real user got to be the owner.
        retrieved_policy = policy.get_policy(self._session_manager)
        if retrieved_policy is None: raise error.TestFail('Policy not found')
        policy.compare_policy_response(retrieved_policy, owner=owner)


    def cleanup(self):
        # Testing is done, so just stop the UI instead of calling StopSession.
        # The latter can fail if Chrome is hanging: https://crbug.com/876197
        cros_ui.stop(allow_fail=True)
        cros_ui.start(allow_fail=True, wait_for_login_prompt=False)
