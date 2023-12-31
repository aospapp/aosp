# Lint as: python2, python3
# Copyright 2014 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import dbus
import logging
import six

import common

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import seven


DBUS_INTERFACE_OBJECT_MANAGER = 'org.freedesktop.DBus.ObjectManager'
DBUS_ERROR_SERVICEUNKNOWN = 'org.freedesktop.DBus.Error.ServiceUnknown'


def dbus2primitive(value):
    """Convert values from dbus types to python types.

    @param value: dbus object to convert to a primitive.

    """
    if isinstance(value, dbus.Boolean):
        return bool(value)
    elif isinstance(value, int):
        return int(value)
    elif isinstance(value, dbus.UInt16):
        return seven.ensure_long(value)
    elif isinstance(value, dbus.UInt32):
        return seven.ensure_long(value)
    elif isinstance(value, dbus.UInt64):
        return seven.ensure_long(value)
    elif isinstance(value, float):
        return float(value)
    elif isinstance(value, str):
        return str(value)
    elif isinstance(value, six.text_type):
        return str(value.encode('utf-8'))
    elif isinstance(value, list):
        return [dbus2primitive(x) for x in value]
    elif isinstance(value, tuple):
        return tuple([dbus2primitive(x) for x in value])
    elif isinstance(value, dict):
        return dict([(dbus2primitive(k), dbus2primitive(v))
                     for k, v in value.items()])
    else:
        logging.error('Failed to convert dbus object of class: %r',
                      value.__class__.__name__)
        return value


def get_objects_with_interface(service_name, object_manager_path,
                               dbus_interface, path_prefix=None,
                               bus=None):
    """Get objects that have a particular interface via a property manager.

    @param service_name: string remote service exposing the object manager
            to query (e.g. 'org.chromium.peerd').
    @param object_manager_path: string DBus path of object manager on remote
            service (e.g. '/org/chromium/peerd')
    @param dbus_interface: string interface of object we're interested in.
    @param path_prefix: string prefix of DBus path to filter for.  If not
            None, we'll return only objects in the remote service whose
            paths start with this prefix.
    @param bus: dbus.Bus object, defaults to dbus.SystemBus().  Note that
            normally, dbus.SystemBus() multiplexes a single DBus connection
            among its instances.
    @return dict that maps object paths to dicts of interface name to properties
            exposed by that interface.  This is similar to the structure
            returned by org.freedesktop.DBus.ObjectManaber.GetManagedObjects().

    """
    if bus is None:
        bus = dbus.SystemBus()
    object_manager = dbus.Interface(
            bus.get_object(service_name, object_manager_path),
            dbus_interface=DBUS_INTERFACE_OBJECT_MANAGER)
    objects = dbus2primitive(object_manager.GetManagedObjects())
    logging.debug('Saw objects %r', objects)
    # Filter by interface.
    objects = [(path, interfaces)
               for path, interfaces in six.iteritems(objects)
               if dbus_interface in interfaces]
    if path_prefix is not None:
        objects = [(path, interfaces)
                   for path, interfaces in objects
                   if path.startswith(path_prefix)]
    objects = dict(objects)
    logging.debug('Filtered objects: %r', objects)
    return objects

def get_dbus_object(bus, service_name, object_manager_path, timeout=None):
    """Keeps trying to get the a DBus object until a timeout expires.
    Useful if a test should wait for a system daemon to start up.

    @param bus: dbus.Bus object.
    @param service_name: string service to look up (e.g. 'org.chromium.peerd').
    @param object_manager_path: string DBus path of object manager on remote
            service (e.g. '/org/chromium/peerd')
    @param timeout: maximum time in seconds to wait for the bus object.
    @return The DBus object or None if the timeout expired.

    """

    def try_get_object():
        try:
            return bus.get_object(service_name, object_manager_path)
        except dbus.exceptions.DBusException as e:
            # Only handle DBUS_ERROR_SERVICEUNKNOWN, which is thrown when the
            # service is not running yet. Otherwise, rethrow.
            if e.get_dbus_name() == DBUS_ERROR_SERVICEUNKNOWN:
                return None
            raise

    return utils.poll_for_condition(
            condition=try_get_object,
            desc='Get bus object "%s" / "%s"' % (service_name,
                                                 object_manager_path),
            timeout=timeout or 0)
