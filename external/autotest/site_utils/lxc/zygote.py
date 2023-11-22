# Copyright 2017 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os
import tempfile

import common
from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.site_utils.lxc import Container
from autotest_lib.site_utils.lxc import constants
from autotest_lib.site_utils.lxc import lxc
from autotest_lib.site_utils.lxc import utils as lxc_utils


class Zygote(Container):
    """A Container that implements post-bringup configuration.
    """

    def __init__(self, container_path, name, attribute_values, src=None,
                 snapshot=False, host_path=None):
        """Initialize an object of LXC container with given attribute values.

        @param container_path: Directory that stores the container.
        @param name: Name of the container.
        @param attribute_values: A dictionary of attribute values for the
                                 container.
        @param src: An optional source container.  If provided, the source
                    continer is cloned, and the new container will point to the
                    clone.
        @param snapshot: Whether or not to create a snapshot clone.  By default,
                         this is false.  If a snapshot is requested and creating
                         a snapshot clone fails, a full clone will be attempted.
        @param host_path: If set to None (the default), a host path will be
                          generated based on constants.DEFAULT_SHARED_HOST_PATH.
                          Otherwise, this can be used to override the host path
                          of the new container, for testing purposes.
        """
        super(Zygote, self).__init__(container_path, name, attribute_values,
                                     src, snapshot)

        # Initialize host dir and mount
        if host_path is None:
            self.host_path = os.path.join(
                    os.path.realpath(constants.DEFAULT_SHARED_HOST_PATH),
                    self.name)
        else:
            self.host_path = host_path

        if src is not None:
            # If creating a new zygote, initialize the host dir.
            if not lxc_utils.path_exists(self.host_path):
                utils.run('sudo mkdir %s' % self.host_path)
            self.mount_dir(self.host_path, constants.CONTAINER_AUTOTEST_DIR)


    def destroy(self, force=True):
        super(Zygote, self).destroy(force)
        if lxc_utils.path_exists(self.host_path):
            self._cleanup_host_mount()


    def set_hostname(self, hostname):
        """Sets the hostname within the container.

        @param hostname The new container hostname.
        """
        if self.is_running():
            self.attach_run('hostname %s' % (hostname))
            self.attach_run(constants.APPEND_CMD_FMT % {
                'content': '127.0.0.1 %s' % (hostname),
                'file': '/etc/hosts'})
        else:
            super(Zygote, self).set_hostname(hostname)


    def install_ssp(self, ssp_url):
        """Downloads and installs the given server package.

        @param ssp_url: The URL of the ssp to download and install.
        """
        # The host dir is mounted directly on /usr/local/autotest within the
        # container.  The SSP structure assumes it gets untarred into the
        # /usr/local directory of the container's rootfs.  In order to unpack
        # with the correct directory structure, create a tmpdir, mount the
        # container's host dir as ./autotest, and unpack the SSP.
        tmpdir = None
        autotest_tmp = None
        try:
            tmpdir = tempfile.mkdtemp(dir=self.container_path,
                                      prefix='%s.' % self.name,
                                      suffix='.tmp')
            autotest_tmp = os.path.join(tmpdir, 'autotest')
            os.mkdir(autotest_tmp)
            utils.run(
                    'sudo mount --bind %s %s' % (self.host_path, autotest_tmp))
            download_tmp = os.path.join(tmpdir,
                                        'autotest_server_package.tar.bz2')
            lxc.download_extract(ssp_url, download_tmp, tmpdir)
        finally:
            if autotest_tmp is not None:
                try:
                    utils.run('sudo umount %s' % autotest_tmp)
                except error.CmdError:
                    logging.exception('Failure while cleaning up SSP tmpdir.')
            if tmpdir is not None:
                utils.run('sudo rm -rf %s' % tmpdir)


    def install_control_file(self, control_file):
        """Installs the given control file.

        The given file will be moved into the container.

        @param control_file: Path to the control file to install.
        """
        # Compute the control temp path relative to the host mount.
        dst_path = os.path.join(
                self.host_path,
                os.path.relpath(constants.CONTROL_TEMP_PATH,
                                constants.CONTAINER_AUTOTEST_DIR))
        utils.run('sudo mkdir -p %s' % dst_path)
        utils.run('sudo mv %s %s' % (control_file, dst_path))


    def _cleanup_host_mount(self):
        """Unmount and remove the host dir for this container."""
        lxc_utils.cleanup_host_mount(self.host_path);
