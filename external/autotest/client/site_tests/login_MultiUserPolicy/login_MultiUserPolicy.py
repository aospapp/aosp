# Copyright (c) 2013 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import dbus
from dbus.mainloop.glib import DBusGMainLoop

from autotest_lib.client.bin import test
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import policy, session_manager
from autotest_lib.client.cros import cryptohome, ownership


class login_MultiUserPolicy(test.test):
    """Verifies that storing and retrieving user policy works with
       multiple profiles signed-in.
    """

    version = 1

    _user1 = 'user1@somewhere.com'
    _user2 = 'user2@somewhere.com'


    def initialize(self):
        super(login_MultiUserPolicy, self).initialize()
        policy.install_protobufs(self.autodir, self.job)
        self._bus_loop = DBusGMainLoop(set_as_default=True)

        # Clear the user's vault, to make sure the test starts without any
        # policy or key lingering around. At this stage the session isn't
        # started and there's no user signed in.
        ownership.restart_ui_to_clear_ownership_files()
        cryptohome_proxy = cryptohome.CryptohomeProxy(
            self._bus_loop, self.autodir, self.job)
        cryptohome_proxy.ensure_clean_cryptohome_for(self._user1)
        cryptohome_proxy.ensure_clean_cryptohome_for(self._user2)


    def run_once(self):
        sm = session_manager.connect(self._bus_loop)

        # Start a session for the first user, and verify that no policy exists
        # for that user yet.
        sm.StartSession(self._user1, '')
        policy_blob = sm.RetrievePolicyEx(
                session_manager.make_user_policy_descriptor(self._user1),
                byte_arrays=True)
        if policy_blob:
            raise error.TestError('session_manager already has user policy!')

        # Now store a policy. This is building a device policy protobuf, but
        # that's fine as far as the session_manager is concerned; it's the
        # outer PolicyFetchResponse that contains the public_key.
        public_key = ownership.known_pubkey()
        private_key = ownership.known_privkey()
        policy_data = policy.build_policy_data()
        policy_response = policy.generate_policy(private_key,
                                                 public_key,
                                                 policy_data)
        try:
            sm.StorePolicyEx(
                session_manager.make_user_policy_descriptor(self._user1),
                dbus.ByteArray(policy_response))
        except dbus.exceptions.DBusException as e:
            raise error.TestFail('Call to StorePolicyEx failed', e)

        # Storing policy for the second user fails before his session starts.
        try:
            sm.StorePolicyEx(
                session_manager.make_user_policy_descriptor(self._user2),
                dbus.ByteArray(policy_response))
        except dbus.exceptions.DBusException:
            pass
        else:
            raise error.TestFail('Storing policy should fail before the '
                                 'session is started')


        # Now start the second user's session, and verify that he has no
        # policy stored yet.
        sm.StartSession(self._user2, '')
        policy_blob = sm.RetrievePolicyEx(
                session_manager.make_user_policy_descriptor(self._user2),
                byte_arrays=True)
        if policy_blob:
            raise error.TestError('session_manager already has user policy!')

        # Storing works now.
        try:
            sm.StorePolicyEx(
                session_manager.make_user_policy_descriptor(self._user2),
                dbus.ByteArray(policy_response))
        except dbus.exceptions.DBusException as e:
            raise error.TestFail('Call to StorePolicyEx failed', e)

        # Verify that retrieving policy works too.
        policy_blob = sm.RetrievePolicyEx(
                session_manager.make_user_policy_descriptor(self._user2),
                byte_arrays=True)
        if not policy_blob:
            raise error.TestError('Failed to retrieve stored policy')
