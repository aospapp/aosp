# Copyright 2015 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""This module provides some utilities used by LXC and its tools.
"""

import common
from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros.network import interface


def path_exists(path):
    """Check if path exists.

    If the process is not running with root user, os.path.exists may fail to
    check if a path owned by root user exists. This function uses command
    `test -e` to check if path exists.

    @param path: Path to check if it exists.

    @return: True if path exists, otherwise False.
    """
    try:
        utils.run('sudo test -e "%s"' % path)
        return True
    except error.CmdError:
        return False


def get_host_ip():
    """Get the IP address of the host running containers on lxcbr*.

    This function gets the IP address on network interface lxcbr*. The
    assumption is that lxc uses the network interface started with "lxcbr".

    @return: IP address of the host running containers.
    """
    # The kernel publishes symlinks to various network devices in /sys.
    result = utils.run('ls /sys/class/net', ignore_status=True)
    # filter out empty strings
    interface_names = [x for x in result.stdout.split() if x]

    lxc_network = None
    for name in interface_names:
        if name.startswith('lxcbr'):
            lxc_network = name
            break
    if not lxc_network:
        raise error.ContainerError('Failed to find network interface used by '
                                   'lxc. All existing interfaces are: %s' %
                                   interface_names)
    netif = interface.Interface(lxc_network)
    return netif.ipv4_address

def clone(lxc_path, src_name, new_path, dst_name, snapshot):
    """Clones a container.

    @param lxc_path: The LXC path of the source container.
    @param src_name: The name of the source container.
    @param new_path: The LXC path of the destination container.
    @param dst_name: The name of the destination container.
    @param snapshot: Whether or not to create a snapshot clone.
    """
    snapshot_arg = '-s' if snapshot else ''
    # overlayfs is the default clone backend storage. However it is not
    # supported in Ganeti yet. Use aufs as the alternative.
    aufs_arg = '-B aufs' if utils.is_vm() and snapshot else ''
    cmd = (('sudo lxc-clone --lxcpath {lxcpath} --newpath {newpath} '
            '--orig {orig} --new {new} {snapshot} {backing}')
           .format(
               lxcpath = lxc_path,
               newpath = new_path,
               orig = src_name,
               new = dst_name,
               snapshot = snapshot_arg,
               backing = aufs_arg
           ))
    utils.run(cmd)


def cleanup_host_mount(host_dir):
    """Unmounts and removes the given host dir.

    @param host_dir: The host dir to unmount and remove.
    """
    try:
        utils.run('sudo umount "%s"' % host_dir)
    except error.CmdError:
        # Ignore errors.  Most likely this occurred because the host dir
        # was already unmounted.
        pass
    utils.run('sudo rm -r "%s"' % host_dir)
