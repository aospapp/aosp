# Copyright (c) 2012 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import dbus, os, sys
# AU tests use ToT client code, but ToT -3 client version.
try:
    from gi.repository import GObject
except ImportError:
    import gobject as GObject

import common
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import session_manager
from autotest_lib.client.cros import ownership

"""Utility class for tests that generate, push and fetch policies.

As the python bindings for the protobufs used in policies are built as a part
of tests that use them, callers must pass in their location at call time."""


def install_protobufs(autodir, job):
    """Installs policy protobuf dependencies and set import path.

    After calling this, you can simply import any policy pb2.py file directly,
    e.g. import chrome_device_policy_pb2.

    @param autodir: Autotest directory (usually the caller's self.autodir).
    @param job: Job instance (usually the caller's self.job).
    """
    # TODO(crbug.com/807950): Change the installation process so that policy
    #                         proto imports can be moved to the top.
    dep = 'policy_protos'
    dep_dir = os.path.join(autodir, 'deps', dep)
    job.install_pkg(dep, 'dep', dep_dir)
    sys.path.append(dep_dir)


def compare_policy_response(policy_response, owner=None, guests=None,
                            new_users=None, roaming=None):
    """Check the contents of |policy_response| against given args.

    Deserializes |policy_response| into a PolicyFetchResponse protobuf,
    with an embedded (serialized) PolicyData protobuf that embeds a
    (serialized) ChromeDeviceSettingsProto, and checks to see if this
    protobuf turducken contains the information passed in.

    @param policy_response: string serialization of a PolicyData protobuf.
    @param owner: string representing the owner's name/account.
    @param guests: boolean indicating whether guests should be allowed.
    @param new_users: boolean indicating if user pods are on login screen.
    @param roaming: boolean indicating whether data roaming is enabled.

    @return True if |policy_response| has all the provided data, else False.
    """
    import chrome_device_policy_pb2
    import device_management_backend_pb2

    response_proto = device_management_backend_pb2.PolicyFetchResponse()
    response_proto.ParseFromString(policy_response)
    ownership.assert_has_policy_data(response_proto)

    data_proto = device_management_backend_pb2.PolicyData()
    data_proto.ParseFromString(response_proto.policy_data)
    ownership.assert_has_device_settings(data_proto)
    if owner: ownership.assert_username(data_proto, owner)

    settings = chrome_device_policy_pb2.ChromeDeviceSettingsProto()
    settings.ParseFromString(data_proto.policy_value)
    if guests: ownership.assert_guest_setting(settings, guests)
    if new_users: ownership.assert_show_users(settings, new_users)
    if roaming: ownership.assert_roaming(settings, roaming)


def build_policy_data():
    """Generate and serialize a populated device policy protobuffer.

    Creates a PolicyData protobuf, with an embedded
    ChromeDeviceSettingsProto, containing the information passed in.

    @return serialization of the PolicyData proto that we build.
    """
    import chrome_device_policy_pb2
    import device_management_backend_pb2

    data_proto = device_management_backend_pb2.PolicyData()
    data_proto.policy_type = ownership.POLICY_TYPE

    settings = chrome_device_policy_pb2.ChromeDeviceSettingsProto()

    data_proto.policy_value = settings.SerializeToString()
    return data_proto.SerializeToString()


def generate_policy(key, pubkey, policy, old_key=None):
    """Generate and serialize a populated, signed device policy protobuffer.

    Creates a protobuf containing the device policy |policy|, signed with
    |key|.  Also includes the public key |pubkey|, signed with |old_key|
    if provided.  If not, |pubkey| is signed with |key|.  The protobuf
    is serialized to a string and returned.

    @param key: new policy signing key.
    @param pubkey: new public key to be signed and embedded in generated
                   PolicyFetchResponse.
    @param policy: policy data to be embedded in generated PolicyFetchResponse.
    @param old_key: if provided, this implies the generated PolicyFetchRespone
                    is intended to represent a key rotation.  pubkey will be
                    signed with this key before embedding.

    @return serialization of the PolicyFetchResponse proto that we build.
    """
    import device_management_backend_pb2

    if old_key == None:
        old_key = key
    policy_proto = device_management_backend_pb2.PolicyFetchResponse()
    policy_proto.policy_data = policy
    policy_proto.policy_data_signature = ownership.sign(key, policy)
    policy_proto.new_public_key = pubkey
    policy_proto.new_public_key_signature = ownership.sign(old_key, pubkey)
    return policy_proto.SerializeToString()


def push_policy_and_verify(policy_string, sm):
    """Push a device policy to the session manager over DBus.

    The serialized device policy |policy_string| is sent to the session
    manager with the StorePolicyEx DBus call.  Success of the store is
    validated by fetching the policy again and comparing.

    @param policy_string: serialized policy to push to the session manager.
    @param sm: a connected SessionManagerInterface.

    @raises error.TestFail if policy push failed.
    """
    listener = session_manager.OwnershipSignalListener(GObject.MainLoop())
    listener.listen_for_new_policy()
    descriptor = session_manager.make_device_policy_descriptor()
    sm.StorePolicyEx(descriptor,
                     dbus.ByteArray(policy_string), byte_arrays=True)
    listener.wait_for_signals(desc='Policy push.')

    retrieved_policy = sm.RetrievePolicyEx(descriptor, byte_arrays=True)
    if retrieved_policy != policy_string:
        raise error.TestFail('Policy should not be %s' % retrieved_policy)


def get_policy(sm):
    """Get a device policy from the session manager over DBus.

    Provided mainly for symmetry with push_policy_and_verify().

    @param sm: a connected SessionManagerInterface.

    @return Serialized PolicyFetchResponse.
    """
    return sm.RetrievePolicyEx(session_manager.make_device_policy_descriptor(),
                               byte_arrays=True)
