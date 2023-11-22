# Copyright 2015 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os
import re
import time

import common
from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.site_utils.lxc import constants
from autotest_lib.site_utils.lxc import lxc
from autotest_lib.site_utils.lxc import utils as lxc_utils

try:
    from chromite.lib import metrics
except ImportError:
    metrics = utils.metrics_mock


class Container(object):
    """A wrapper class of an LXC container.

    The wrapper class provides methods to interact with a container, e.g.,
    start, stop, destroy, run a command. It also has attributes of the
    container, including:
    name: Name of the container.
    state: State of the container, e.g., ABORTING, RUNNING, STARTING, STOPPED,
           or STOPPING.

    lxc-ls can also collect other attributes of a container including:
    ipv4: IP address for IPv4.
    ipv6: IP address for IPv6.
    autostart: If the container will autostart at system boot.
    pid: Process ID of the container.
    memory: Memory used by the container, as a string, e.g., "6.2MB"
    ram: Physical ram used by the container, as a string, e.g., "6.2MB"
    swap: swap used by the container, as a string, e.g., "1.0MB"

    For performance reason, such info is not collected for now.

    The attributes available are defined in ATTRIBUTES constant.
    """

    def __init__(self, container_path, name, attribute_values, src=None,
                 snapshot=False):
        """Initialize an object of LXC container with given attribute values.

        @param container_path: Directory that stores the container.
        @param name: Name of the container.
        @param attribute_values: A dictionary of attribute values for the
                                 container.
        @param src: An optional source container.  If provided, the source
                    continer is cloned, and the new container will point to the
                    clone.
        @param snapshot: If a source container was specified, this argument
                         specifies whether or not to create a snapshot clone.
                         The default is to attempt to create a snapshot.
                         If a snapshot is requested and creating the snapshot
                         fails, a full clone will be attempted.
        """
        self.container_path = os.path.realpath(container_path)
        # Path to the rootfs of the container. This will be initialized when
        # property rootfs is retrieved.
        self._rootfs = None
        self.name = name
        for attribute, value in attribute_values.iteritems():
            setattr(self, attribute, value)

        # Clone the container
        if src is not None:
            # Clone the source container to initialize this one.
            lxc_utils.clone(src.container_path, src.name, self.container_path,
                            self.name, snapshot)


    @classmethod
    def createFromExistingDir(cls, lxc_path, name, **kwargs):
        """Creates a new container instance for an lxc container that already
        exists on disk.

        @param lxc_path: The LXC path for the container.
        @param name: The container name.

        @raise error.ContainerError: If the container doesn't already exist.

        @return: The new container.
        """
        return cls(lxc_path, name, kwargs)


    @classmethod
    def clone(cls, src, new_name, new_path=None, snapshot=False, cleanup=False):
        """Creates a clone of this container.

        @param src: The original container.
        @param new_name: Name for the cloned container.
        @param new_path: LXC path for the cloned container (optional; if not
                specified, the new container is created in the same directory as
                the source container).
        @param snapshot: Whether to snapshot, or create a full clone.
        @param cleanup: If a container with the given name and path already
                exist, clean it up first.
        """
        if new_path is None:
            new_path = src.container_path

        # If a container exists at this location, clean it up first
        container_folder = os.path.join(new_path, new_name)
        if lxc_utils.path_exists(container_folder):
            if not cleanup:
                raise error.ContainerError('Container %s already exists.' %
                                           new_name)
            container = Container.createFromExistingDir(new_path, new_name)
            try:
                container.destroy()
            except error.CmdError as e:
                # The container could be created in a incompleted state. Delete
                # the container folder instead.
                logging.warn('Failed to destroy container %s, error: %s',
                             new_name, e)
                utils.run('sudo rm -rf "%s"' % container_folder)

        # Create and return the new container.
        return cls(new_path, new_name, {}, src, snapshot)


    def refresh_status(self):
        """Refresh the status information of the container.
        """
        containers = lxc.get_container_info(self.container_path, name=self.name)
        if not containers:
            raise error.ContainerError(
                    'No container found in directory %s with name of %s.' %
                    (self.container_path, self.name))
        attribute_values = containers[0]
        for attribute, value in attribute_values.iteritems():
            setattr(self, attribute, value)


    @property
    def rootfs(self):
        """Path to the rootfs of the container.

        This property returns the path to the rootfs of the container, that is,
        the folder where the container stores its local files. It reads the
        attribute lxc.rootfs from the config file of the container, e.g.,
            lxc.rootfs = /usr/local/autotest/containers/t4/rootfs
        If the container is created with snapshot, the rootfs is a chain of
        folders, separated by `:` and ordered by how the snapshot is created,
        e.g.,
            lxc.rootfs = overlayfs:/usr/local/autotest/containers/base/rootfs:
            /usr/local/autotest/containers/t4_s/delta0
        This function returns the last folder in the chain, in above example,
        that is `/usr/local/autotest/containers/t4_s/delta0`

        Files in the rootfs will be accessible directly within container. For
        example, a folder in host "[rootfs]/usr/local/file1", can be accessed
        inside container by path "/usr/local/file1". Note that symlink in the
        host can not across host/container boundary, instead, directory mount
        should be used, refer to function mount_dir.

        @return: Path to the rootfs of the container.
        """
        if not self._rootfs:
            cmd = ('sudo lxc-info -P %s -n %s -c lxc.rootfs' %
                   (self.container_path, self.name))
            lxc_rootfs_config = utils.run(cmd).stdout.strip()
            match = re.match('lxc.rootfs = (.*)', lxc_rootfs_config)
            if not match:
                raise error.ContainerError(
                        'Failed to locate rootfs for container %s. lxc.rootfs '
                        'in the container config file is %s' %
                        (self.name, lxc_rootfs_config))
            lxc_rootfs = match.group(1)
            cloned_from_snapshot = ':' in lxc_rootfs
            if cloned_from_snapshot:
                self._rootfs = lxc_rootfs.split(':')[-1]
            else:
                self._rootfs = lxc_rootfs
        return self._rootfs


    def attach_run(self, command, bash=True):
        """Attach to a given container and run the given command.

        @param command: Command to run in the container.
        @param bash: Run the command through bash -c "command". This allows
                     pipes to be used in command. Default is set to True.

        @return: The output of the command.

        @raise error.CmdError: If container does not exist, or not running.
        """
        cmd = 'sudo lxc-attach -P %s -n %s' % (self.container_path, self.name)
        if bash and not command.startswith('bash -c'):
            command = 'bash -c "%s"' % utils.sh_escape(command)
        cmd += ' -- %s' % command
        # TODO(dshi): crbug.com/459344 Set sudo to default to False when test
        # container can be unprivileged container.
        return utils.run(cmd)


    def is_network_up(self):
        """Check if network is up in the container by curl base container url.

        @return: True if the network is up, otherwise False.
        """
        try:
            self.attach_run('curl --head %s' % constants.CONTAINER_BASE_URL)
            return True
        except error.CmdError as e:
            logging.debug(e)
            return False


    @metrics.SecondsTimerDecorator(
        '%s/container_start_duration' % constants.STATS_KEY)
    def start(self, wait_for_network=True):
        """Start the container.

        @param wait_for_network: True to wait for network to be up. Default is
                                 set to True.

        @raise ContainerError: If container does not exist, or fails to start.
        """
        cmd = 'sudo lxc-start -P %s -n %s -d' % (self.container_path, self.name)
        output = utils.run(cmd).stdout
        if not self.is_running():
            raise error.ContainerError(
                    'Container %s failed to start. lxc command output:\n%s' %
                    (os.path.join(self.container_path, self.name),
                     output))

        if wait_for_network:
            logging.debug('Wait for network to be up.')
            start_time = time.time()
            utils.poll_for_condition(
                condition=self.is_network_up,
                timeout=constants.NETWORK_INIT_TIMEOUT,
                sleep_interval=constants.NETWORK_INIT_CHECK_INTERVAL)
            logging.debug('Network is up after %.2f seconds.',
                          time.time() - start_time)


    @metrics.SecondsTimerDecorator(
        '%s/container_stop_duration' % constants.STATS_KEY)
    def stop(self):
        """Stop the container.

        @raise ContainerError: If container does not exist, or fails to start.
        """
        cmd = 'sudo lxc-stop -P %s -n %s' % (self.container_path, self.name)
        output = utils.run(cmd).stdout
        self.refresh_status()
        if self.state != 'STOPPED':
            raise error.ContainerError(
                    'Container %s failed to be stopped. lxc command output:\n'
                    '%s' % (os.path.join(self.container_path, self.name),
                            output))


    @metrics.SecondsTimerDecorator(
        '%s/container_destroy_duration' % constants.STATS_KEY)
    def destroy(self, force=True):
        """Destroy the container.

        @param force: Set to True to force to destroy the container even if it's
                      running. This is faster than stop a container first then
                      try to destroy it. Default is set to True.

        @raise ContainerError: If container does not exist or failed to destroy
                               the container.
        """
        cmd = 'sudo lxc-destroy -P %s -n %s' % (self.container_path,
                                                self.name)
        if force:
            cmd += ' -f'
        utils.run(cmd)


    def mount_dir(self, source, destination, readonly=False):
        """Mount a directory in host to a directory in the container.

        @param source: Directory in host to be mounted.
        @param destination: Directory in container to mount the source directory
        @param readonly: Set to True to make a readonly mount, default is False.
        """
        # Destination path in container must be relative.
        destination = destination.lstrip('/')
        # Create directory in container for mount.
        utils.run('sudo mkdir -p %s' % os.path.join(self.rootfs, destination))
        config_file = os.path.join(self.container_path, self.name, 'config')
        mount = constants.MOUNT_FMT % {'source': source,
                                       'destination': destination,
                                       'readonly': ',ro' if readonly else ''}
        utils.run(
            constants.APPEND_CMD_FMT % {'content': mount, 'file': config_file})


    def verify_autotest_setup(self, job_folder):
        """Verify autotest code is set up properly in the container.

        @param job_folder: Name of the job result folder.

        @raise ContainerError: If autotest code is not set up properly.
        """
        # Test autotest code is setup by verifying a list of
        # (directory, minimum file count)
        directories_to_check = [
                (constants.CONTAINER_AUTOTEST_DIR, 3),
                (constants.RESULT_DIR_FMT % job_folder, 0),
                (constants.CONTAINER_SITE_PACKAGES_PATH, 3)]
        for directory, count in directories_to_check:
            result = self.attach_run(command=(constants.COUNT_FILE_CMD %
                                              {'dir': directory})).stdout
            logging.debug('%s entries in %s.', int(result), directory)
            if int(result) < count:
                raise error.ContainerError('%s is not properly set up.' %
                                           directory)
        # lxc-attach and run command does not run in shell, thus .bashrc is not
        # loaded. Following command creates a symlink in /usr/bin/ for gsutil
        # if it's installed.
        # TODO(dshi): Remove this code after lab container is updated with
        # gsutil installed in /usr/bin/
        self.attach_run('test -f /root/gsutil/gsutil && '
                        'ln -s /root/gsutil/gsutil /usr/bin/gsutil || true')


    def modify_import_order(self):
        """Swap the python import order of lib and local/lib.

        In Moblab, the host's python modules located in
        /usr/lib64/python2.7/site-packages is mounted to following folder inside
        container: /usr/local/lib/python2.7/dist-packages/. The modules include
        an old version of requests module, which is used in autotest
        site-packages. For test, the module is only used in
        dev_server/symbolicate_dump for requests.call and requests.codes.OK.
        When pip is installed inside the container, it installs requests module
        with version of 2.2.1 in /usr/lib/python2.7/dist-packages/. The version
        is newer than the one used in autotest site-packages, but not the latest
        either.
        According to /usr/lib/python2.7/site.py, modules in /usr/local/lib are
        imported before the ones in /usr/lib. That leads to pip to use the older
        version of requests (0.11.2), and it will fail. On the other hand,
        requests module 2.2.1 can't be installed in CrOS (refer to CL:265759),
        and higher version of requests module can't work with pip.
        The only fix to resolve this is to switch the import order, so modules
        in /usr/lib can be imported before /usr/local/lib.
        """
        site_module = '/usr/lib/python2.7/site.py'
        self.attach_run("sed -i ':a;N;$!ba;s/\"local\/lib\",\\n/"
                        "\"lib_placeholder\",\\n/g' %s" % site_module)
        self.attach_run("sed -i ':a;N;$!ba;s/\"lib\",\\n/"
                        "\"local\/lib\",\\n/g' %s" % site_module)
        self.attach_run('sed -i "s/lib_placeholder/lib/g" %s' %
                        site_module)


    def is_running(self):
        """Returns whether or not this container is currently running."""
        self.refresh_status()
        return self.state == 'RUNNING'


    def set_hostname(self, hostname):
        """Sets the hostname within the container.  This needs to be called
        prior to starting the container.
        """
        config_file = os.path.join(self.container_path, self.name, 'config')
        lxc_utsname_setting = (
                'lxc.utsname = ' +
                constants.CONTAINER_UTSNAME_FORMAT % hostname)
        utils.run(
            constants.APPEND_CMD_FMT % {'content': lxc_utsname_setting,
                                        'file': config_file})


    def install_ssp(self, ssp_url):
        """Downloads and installs the given server package.

        @param ssp_url: The URL of the ssp to download and install.
        """
        usr_local_path = os.path.join(self.rootfs, 'usr', 'local')
        autotest_pkg_path = os.path.join(usr_local_path,
                                         'autotest_server_package.tar.bz2')
        # sudo is required so os.makedirs may not work.
        utils.run('sudo mkdir -p %s'% usr_local_path)

        lxc.download_extract(ssp_url, autotest_pkg_path, usr_local_path)


    def install_control_file(self, control_file):
        """Installs the given control file.
        The given file will be moved into the container.

        @param control_file: Path to the control file to install.
        """
        dst_path = os.path.join(self.rootfs,
                                constants.CONTROL_TEMP_PATH.lstrip(os.path.sep))
        utils.run('sudo mkdir -p %s' % dst_path)
        utils.run('sudo mv %s %s' % (control_file, dst_path))
