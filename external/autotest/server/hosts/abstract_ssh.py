# Lint as: python2, python3
# Copyright (c) 2008 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import os, time, socket, shutil, glob, logging, tempfile, re
import shlex
import subprocess

from autotest_lib.client.bin.result_tools import runner as result_tools_runner
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib import utils
from autotest_lib.client.common_lib.cros.network import ping_runner
from autotest_lib.client.common_lib.global_config import global_config
from autotest_lib.server import autoserv_parser
from autotest_lib.server import utils, autotest
from autotest_lib.server.hosts import host_info
from autotest_lib.server.hosts import remote
from autotest_lib.server.hosts import rpc_server_tracker
from autotest_lib.server.hosts import ssh_multiplex
from autotest_lib.server.hosts.tls_client import exec_dut_command

import six
from six.moves import filter

try:
    from autotest_lib.utils.frozen_chromite.lib import metrics
except ImportError:
    metrics = utils.metrics_mock

# pylint: disable=C0111

get_value = global_config.get_config_value
enable_main_ssh = get_value('AUTOSERV',
                            'enable_main_ssh',
                            type=bool,
                            default=False)

ENABLE_EXEC_DUT_COMMAND = get_value('AUTOSERV',
                                    'enable_tls',
                                    type=bool,
                                    default=False)

# Number of seconds to use the cached up status.
_DEFAULT_UP_STATUS_EXPIRATION_SECONDS = 300
_DEFAULT_SSH_PORT = None

# Number of seconds to wait for the host to shut down in wait_down().
_DEFAULT_WAIT_DOWN_TIME_SECONDS = 120

# Number of seconds to wait for the host to boot up in wait_up().
_DEFAULT_WAIT_UP_TIME_SECONDS = 120

# Timeout in seconds for a single call of get_boot_id() in wait_down()
# and a single ssh ping in wait_up().
_DEFAULT_MAX_PING_TIMEOUT = 10

# The client symlink directory.
AUTOTEST_CLIENT_SYMLINK_END = 'client/autotest_lib'


class AbstractSSHHost(remote.RemoteHost):
    """
    This class represents a generic implementation of most of the
    framework necessary for controlling a host via ssh. It implements
    almost all of the abstract Host methods, except for the core
    Host.run method.
    """
    VERSION_PREFIX = ''
    # Timeout for main ssh connection setup, in seconds.
    DEFAULT_START_MAIN_SSH_TIMEOUT_S = 5

    def _initialize(self,
                    hostname,
                    user="root",
                    port=_DEFAULT_SSH_PORT,
                    password="",
                    is_client_install_supported=True,
                    afe_host=None,
                    host_info_store=None,
                    connection_pool=None,
                    *args,
                    **dargs):
        super(AbstractSSHHost, self)._initialize(hostname=hostname,
                                                 *args, **dargs)
        """
        @param hostname: The hostname of the host.
        @param user: The username to use when ssh'ing into the host.
        @param password: The password to use when ssh'ing into the host.
        @param port: The port to use for ssh.
        @param is_client_install_supported: Boolean to indicate if we can
                install autotest on the host.
        @param afe_host: The host object attained from the AFE (get_hosts).
        @param host_info_store: Optional host_info.CachingHostInfoStore object
                to obtain / update host information.
        @param connection_pool: ssh_multiplex.ConnectionPool instance to share
                the main ssh connection across control scripts.
        """
        self._track_class_usage()
        # IP address is retrieved only on demand. Otherwise the host
        # initialization will fail for host is not online.
        self._ip = None
        self.user = user
        self.port = port
        self.password = password
        self._is_client_install_supported = is_client_install_supported
        self._use_rsync = None
        self.known_hosts_file = tempfile.mkstemp()[1]
        self._rpc_server_tracker = rpc_server_tracker.RpcServerTracker(self);
        self._tls_exec_dut_command_client = None
        self._tls_unstable = False

        # Read the value of the use_icmp flag, setting to true if missing.
        args_string = autoserv_parser.autoserv_parser.options.args
        args_dict = utils.args_to_dict(
                args_string.split() if args_string is not None else '')
        value = args_dict.get('use_icmp', 'true').lower()
        if value == 'true':
            self._use_icmp = True
        elif value == 'false':
            self._use_icmp = False
        else:
            raise ValueError(
                    'use_icmp must be true or false: {}'.format(value))
        """
        Main SSH connection background job, socket temp directory and socket
        control path option. If main-SSH is enabled, these fields will be
        initialized by start_main_ssh when a new SSH connection is initiated.
        """
        self._connection_pool = connection_pool
        if connection_pool:
            self._main_ssh = connection_pool.get(hostname, user, port)
        else:
            self._main_ssh = ssh_multiplex.MainSsh(hostname, user, port)

        self._afe_host = afe_host or utils.EmptyAFEHost()
        self.host_info_store = (host_info_store or
                                host_info.InMemoryHostInfoStore())

        # The cached status of whether the DUT responded to ping.
        self._cached_up_status = None
        # The timestamp when the value of _cached_up_status is set.
        self._cached_up_status_updated = None


    @property
    def ip(self):
        """@return IP address of the host.
        """
        if not self._ip:
            self._ip = socket.getaddrinfo(self.hostname, None)[0][4][0]
        return self._ip


    @property
    def is_client_install_supported(self):
        """"
        Returns True if the host supports autotest client installs, False
        otherwise.
        """
        return self._is_client_install_supported

    def is_satlab(self):
        """Determine if the host is part of satlab

        TODO(otabek@): Remove or update to better logic to determime Satlab.

        @returns True if ths host is running under satlab otherwise False.
        """
        if not hasattr(self, '_is_satlab'):
            self._is_satlab = self.hostname.startswith('satlab-')
        return self._is_satlab

    @property
    def rpc_server_tracker(self):
        """"
        @return The RPC server tracker associated with this host.
        """
        return self._rpc_server_tracker


    @property
    def is_default_port(self):
        """Returns True if its port is default SSH port."""
        return self.port == _DEFAULT_SSH_PORT or self.port is None

    @property
    def host_port(self):
        """Returns hostname if port is default. Otherwise, hostname:port.
        """
        if self.is_default_port:
            return self.hostname
        else:
            return '%s:%d' % (self.hostname, self.port)

    @property
    def use_icmp(self):
        """Returns True if icmp pings are allowed."""
        return self._use_icmp


    # Though it doesn't use self here, it is not declared as staticmethod
    # because its subclass may use self to access member variables.
    def make_ssh_command(self, user="root", port=_DEFAULT_SSH_PORT, opts='',
                         hosts_file='/dev/null', connect_timeout=30,
                         alive_interval=300, alive_count_max=3,
                         connection_attempts=1):
        ssh_options = " ".join([
            opts,
            self.make_ssh_options(
                hosts_file=hosts_file, connect_timeout=connect_timeout,
                alive_interval=alive_interval, alive_count_max=alive_count_max,
                connection_attempts=connection_attempts)])
        return ("/usr/bin/ssh -a -x %s -l %s %s" %
                (ssh_options, user, "-p %d " % port if port else ""))


    @staticmethod
    def make_ssh_options(hosts_file='/dev/null', connect_timeout=30,
                         alive_interval=300, alive_count_max=3,
                         connection_attempts=1):
        """Composes SSH -o options."""
        assert isinstance(connect_timeout, six.integer_types)
        assert connect_timeout > 0 # can't disable the timeout

        options = [("StrictHostKeyChecking", "no"),
                   ("UserKnownHostsFile", hosts_file),
                   ("BatchMode", "yes"),
                   ("ConnectTimeout", str(connect_timeout)),
                   ("ServerAliveInterval", str(alive_interval)),
                   ("ServerAliveCountMax", str(alive_count_max)),
                   ("ConnectionAttempts", str(connection_attempts))]
        return " ".join("-o %s=%s" % kv for kv in options)


    def use_rsync(self):
        if self._use_rsync is not None:
            return self._use_rsync

        # Check if rsync is available on the remote host. If it's not,
        # don't try to use it for any future file transfers.
        self._use_rsync = self.check_rsync()
        if not self._use_rsync:
            logging.warning("rsync not available on remote host %s -- disabled",
                            self.host_port)
        return self._use_rsync


    def check_rsync(self):
        """
        Check if rsync is available on the remote host.
        """
        try:
            self.run("rsync --version", stdout_tee=None, stderr_tee=None)
        except error.AutoservRunError:
            return False
        return True


    def _encode_remote_paths(self, paths, escape=True, use_scp=False):
        """
        Given a list of file paths, encodes it as a single remote path, in
        the style used by rsync and scp.
        escape: add \\ to protect special characters.
        use_scp: encode for scp if true, rsync if false.
        """
        if escape:
            paths = [utils.scp_remote_escape(path) for path in paths]

        remote = self.hostname

        # rsync and scp require IPv6 brackets, even when there isn't any
        # trailing port number (ssh doesn't support IPv6 brackets).
        # In the Python >= 3.3 future, 'import ipaddress' will parse addresses.
        if re.search(r':.*:', remote):
            remote = '[%s]' % remote

        if use_scp:
            return '%s@%s:"%s"' % (self.user, remote, " ".join(paths))
        else:
            return '%s@%s:%s' % (
                    self.user, remote,
                    " :".join('"%s"' % p for p in paths))

    def _encode_local_paths(self, paths, escape=True):
        """
        Given a list of file paths, encodes it as a single local path.
        escape: add \\ to protect special characters.
        """
        if escape:
            paths = [utils.sh_escape(path) for path in paths]

        return " ".join('"%s"' % p for p in paths)


    def rsync_options(self, delete_dest=False, preserve_symlinks=False,
                      safe_symlinks=False, excludes=None):
        """Obtains rsync options for the remote."""
        ssh_cmd = self.make_ssh_command(user=self.user, port=self.port,
                                        opts=self._main_ssh.ssh_option,
                                        hosts_file=self.known_hosts_file)
        if delete_dest:
            delete_flag = "--delete"
        else:
            delete_flag = ""
        if safe_symlinks:
            symlink_flag = "-l --safe-links"
        elif preserve_symlinks:
            symlink_flag = "-l"
        else:
            symlink_flag = "-L"
        exclude_args = ''
        if excludes:
            exclude_args = ' '.join(
                    ["--exclude '%s'" % exclude for exclude in excludes])
        return "%s %s --timeout=1800 --rsh='%s' -az --no-o --no-g %s" % (
            symlink_flag, delete_flag, ssh_cmd, exclude_args)


    def _make_rsync_cmd(self, sources, dest, delete_dest,
                        preserve_symlinks, safe_symlinks, excludes=None):
        """
        Given a string of source paths and a destination path, produces the
        appropriate rsync command for copying them. Remote paths must be
        pre-encoded.
        """
        rsync_options = self.rsync_options(
            delete_dest=delete_dest, preserve_symlinks=preserve_symlinks,
            safe_symlinks=safe_symlinks, excludes=excludes)
        return 'rsync %s %s "%s"' % (rsync_options, sources, dest)


    def _make_ssh_cmd(self, cmd):
        """
        Create a base ssh command string for the host which can be used
        to run commands directly on the machine
        """
        base_cmd = self.make_ssh_command(user=self.user, port=self.port,
                                         opts=self._main_ssh.ssh_option,
                                         hosts_file=self.known_hosts_file)

        return '%s %s "%s"' % (base_cmd, self.hostname, utils.sh_escape(cmd))

    def _make_scp_cmd(self, sources, dest):
        """
        Given a string of source paths and a destination path, produces the
        appropriate scp command for encoding it. Remote paths must be
        pre-encoded.
        """
        command = ("scp -rq %s -o StrictHostKeyChecking=no "
                   "-o UserKnownHostsFile=%s %s%s '%s'")
        return command % (self._main_ssh.ssh_option, self.known_hosts_file,
                          "-P %d " % self.port if self.port else '', sources,
                          dest)


    def _make_rsync_compatible_globs(self, path, is_local):
        """
        Given an rsync-style path, returns a list of globbed paths
        that will hopefully provide equivalent behaviour for scp. Does not
        support the full range of rsync pattern matching behaviour, only that
        exposed in the get/send_file interface (trailing slashes).

        The is_local param is flag indicating if the paths should be
        interpreted as local or remote paths.
        """

        # non-trailing slash paths should just work
        if len(path) == 0 or path[-1] != "/":
            return [path]

        # make a function to test if a pattern matches any files
        if is_local:
            def glob_matches_files(path, pattern):
                return len(glob.glob(path + pattern)) > 0
        else:
            def glob_matches_files(path, pattern):
                result = self.run("ls \"%s\"%s" % (utils.sh_escape(path),
                                                   pattern),
                                  stdout_tee=None, ignore_status=True)
                return result.exit_status == 0

        # take a set of globs that cover all files, and see which are needed
        patterns = ["*", ".[!.]*"]
        patterns = [p for p in patterns if glob_matches_files(path, p)]

        # convert them into a set of paths suitable for the commandline
        if is_local:
            return ["\"%s\"%s" % (utils.sh_escape(path), pattern)
                    for pattern in patterns]
        else:
            return [utils.scp_remote_escape(path) + pattern
                    for pattern in patterns]


    def _make_rsync_compatible_source(self, source, is_local):
        """
        Applies the same logic as _make_rsync_compatible_globs, but
        applies it to an entire list of sources, producing a new list of
        sources, properly quoted.
        """
        return sum((self._make_rsync_compatible_globs(path, is_local)
                    for path in source), [])


    def _set_umask_perms(self, dest):
        """
        Given a destination file/dir (recursively) set the permissions on
        all the files and directories to the max allowed by running umask.
        """

        # now this looks strange but I haven't found a way in Python to _just_
        # get the umask, apparently the only option is to try to set it
        umask = os.umask(0)
        os.umask(umask)

        max_privs = 0o777 & ~umask

        def set_file_privs(filename):
            """Sets mode of |filename|.  Assumes |filename| exists."""
            file_stat = os.stat(filename)

            file_privs = max_privs
            # if the original file permissions do not have at least one
            # executable bit then do not set it anywhere
            if not file_stat.st_mode & 0o111:
                file_privs &= ~0o111

            os.chmod(filename, file_privs)

        # try a bottom-up walk so changes on directory permissions won't cut
        # our access to the files/directories inside it
        for root, dirs, files in os.walk(dest, topdown=False):
            # when setting the privileges we emulate the chmod "X" behaviour
            # that sets to execute only if it is a directory or any of the
            # owner/group/other already has execute right
            for dirname in dirs:
                os.chmod(os.path.join(root, dirname), max_privs)

            # Filter out broken symlinks as we go.
            for filename in filter(os.path.exists, files):
                set_file_privs(os.path.join(root, filename))


        # now set privs for the dest itself
        if os.path.isdir(dest):
            os.chmod(dest, max_privs)
        else:
            set_file_privs(dest)


    def get_file(self, source, dest, delete_dest=False, preserve_perm=True,
                 preserve_symlinks=False, retry=True, safe_symlinks=False,
                 try_rsync=True):
        """
        Copy files from the remote host to a local path.

        Directories will be copied recursively.
        If a source component is a directory with a trailing slash,
        the content of the directory will be copied, otherwise, the
        directory itself and its content will be copied. This
        behavior is similar to that of the program 'rsync'.

        Args:
                source: either
                        1) a single file or directory, as a string
                        2) a list of one or more (possibly mixed)
                                files or directories
                dest: a file or a directory (if source contains a
                        directory or more than one element, you must
                        supply a directory dest)
                delete_dest: if this is true, the command will also clear
                             out any old files at dest that are not in the
                             source
                preserve_perm: tells get_file() to try to preserve the sources
                               permissions on files and dirs
                preserve_symlinks: try to preserve symlinks instead of
                                   transforming them into files/dirs on copy
                safe_symlinks: same as preserve_symlinks, but discard links
                               that may point outside the copied tree
                try_rsync: set to False to skip directly to using scp
        Raises:
                AutoservRunError: the scp command failed
        """
        logging.debug('get_file. source: %s, dest: %s, delete_dest: %s,'
                      'preserve_perm: %s, preserve_symlinks:%s', source, dest,
                      delete_dest, preserve_perm, preserve_symlinks)

        # Start a main SSH connection if necessary.
        self.start_main_ssh()

        if isinstance(source, six.string_types):
            source = [source]
        dest = os.path.abspath(dest)

        # If rsync is disabled or fails, try scp.
        try_scp = True
        if try_rsync and self.use_rsync():
            logging.debug('Using Rsync.')
            try:
                remote_source = self._encode_remote_paths(source)
                local_dest = utils.sh_escape(dest)
                rsync = self._make_rsync_cmd(remote_source, local_dest,
                                             delete_dest, preserve_symlinks,
                                             safe_symlinks)
                utils.run(rsync)
                try_scp = False
            except error.CmdError as e:
                # retry on rsync exit values which may be caused by transient
                # network problems:
                #
                # rc 10: Error in socket I/O
                # rc 12: Error in rsync protocol data stream
                # rc 23: Partial transfer due to error
                # rc 255: Ssh error
                #
                # Note that rc 23 includes dangling symlinks.  In this case
                # retrying is useless, but not very damaging since rsync checks
                # for those before starting the transfer (scp does not).
                status = e.result_obj.exit_status
                if status in [10, 12, 23, 255] and retry:
                    logging.warning('rsync status %d, retrying', status)
                    self.get_file(source, dest, delete_dest, preserve_perm,
                                  preserve_symlinks, retry=False)
                    # The nested get_file() does all that's needed.
                    return
                else:
                    logging.warning("trying scp, rsync failed: %s (%d)",
                                     e, status)

        if try_scp:
            logging.debug('Trying scp.')
            # scp has no equivalent to --delete, just drop the entire dest dir
            if delete_dest and os.path.isdir(dest):
                shutil.rmtree(dest)
                os.mkdir(dest)

            remote_source = self._make_rsync_compatible_source(source, False)
            if remote_source:
                # _make_rsync_compatible_source() already did the escaping
                remote_source = self._encode_remote_paths(
                        remote_source, escape=False, use_scp=True)
                local_dest = utils.sh_escape(dest)
                scp = self._make_scp_cmd(remote_source, local_dest)
                try:
                    utils.run(scp)
                except error.CmdError as e:
                    logging.debug('scp failed: %s', e)
                    raise error.AutoservRunError(e.args[0], e.args[1])

        if not preserve_perm:
            # we have no way to tell scp to not try to preserve the
            # permissions so set them after copy instead.
            # for rsync we could use "--no-p --chmod=ugo=rwX" but those
            # options are only in very recent rsync versions
            self._set_umask_perms(dest)


    def send_file(self, source, dest, delete_dest=False,
                  preserve_symlinks=False, excludes=None):
        """
        Copy files from a local path to the remote host.

        Directories will be copied recursively.
        If a source component is a directory with a trailing slash,
        the content of the directory will be copied, otherwise, the
        directory itself and its content will be copied. This
        behavior is similar to that of the program 'rsync'.

        Args:
                source: either
                        1) a single file or directory, as a string
                        2) a list of one or more (possibly mixed)
                                files or directories
                dest: a file or a directory (if source contains a
                        directory or more than one element, you must
                        supply a directory dest)
                delete_dest: if this is true, the command will also clear
                             out any old files at dest that are not in the
                             source
                preserve_symlinks: controls if symlinks on the source will be
                    copied as such on the destination or transformed into the
                    referenced file/directory
                excludes: A list of file pattern that matches files not to be
                          sent. `send_file` will fail if exclude is set, since
                          local copy does not support --exclude, e.g., when
                          using scp to copy file.

        Raises:
                AutoservRunError: the scp command failed
        """
        logging.debug('send_file. source: %s, dest: %s, delete_dest: %s,'
                      'preserve_symlinks:%s', source, dest,
                      delete_dest, preserve_symlinks)
        # Start a main SSH connection if necessary.
        self.start_main_ssh()

        if isinstance(source, six.string_types):
            source = [source]

        client_symlink = _client_symlink(source)
        # The client symlink *must* be preserved, and should not be sent with
        # the main send_file in case scp is used, which does not support symlink
        if client_symlink:
            source.remove(client_symlink)

        local_sources = self._encode_local_paths(source)
        if not local_sources:
            raise error.TestError('source |%s| yielded an empty string' % (
                source))
        if local_sources.find('\x00') != -1:
            raise error.TestError('one or more sources include NUL char')

        self._send_file(
                dest=dest,
                source=source,
                local_sources=local_sources,
                delete_dest=delete_dest,
                excludes=excludes,
                preserve_symlinks=preserve_symlinks)

        # Send the client symlink after the rest of the autotest repo has been
        # sent.
        if client_symlink:
            self._send_client_symlink(dest=dest,
                                      source=[client_symlink],
                                      local_sources=client_symlink,
                                      delete_dest=delete_dest,
                                      excludes=excludes,
                                      preserve_symlinks=True)

    def _send_client_symlink(self, dest, source, local_sources, delete_dest,
                             excludes, preserve_symlinks):
        if self.use_rsync():
            if self._send_using_rsync(dest=dest,
                                      local_sources=local_sources,
                                      delete_dest=delete_dest,
                                      preserve_symlinks=preserve_symlinks,
                                      excludes=excludes):
                return
        # Manually create the symlink if rsync is not available, or fails.
        try:
            self.run('mkdir {f} && touch {f}/__init__.py && cd {f} && '
                     'ln -s ../ client'.format(
                             f=os.path.join(dest, 'autotest_lib')))
        except Exception as e:
            raise error.AutotestHostRunError(
                    "Could not create client symlink on host: %s" % e)

    def _send_file(self, dest, source, local_sources, delete_dest, excludes,
                   preserve_symlinks):
        """Send file(s), trying rsync first, then scp."""
        if self.use_rsync():
            rsync_success = self._send_using_rsync(
                    dest=dest,
                    local_sources=local_sources,
                    delete_dest=delete_dest,
                    preserve_symlinks=preserve_symlinks,
                    excludes=excludes)
            if rsync_success:
                return

        # Send using scp if you cannot via rsync, or rsync fails.
        self._send_using_scp(dest=dest,
                             source=source,
                             delete_dest=delete_dest,
                             excludes=excludes)

    def _send_using_rsync(self, dest, local_sources, delete_dest,
                          preserve_symlinks, excludes):
        """Send using rsync.

        Args:
            dest: a file or a directory (if source contains a
                    directory or more than one element, you must
                    supply a directory dest)
            local_sources: a string of files/dirs to send separated with spaces
            delete_dest: if this is true, the command will also clear
                         out any old files at dest that are not in the
                         source
            preserve_symlinks: controls if symlinks on the source will be
                copied as such on the destination or transformed into the
                referenced file/directory
            excludes: A list of file pattern that matches files not to be
                      sent. `send_file` will fail if exclude is set, since
                      local copy does not support --exclude, e.g., when
                      using scp to copy file.
        Returns:
            bool: True if the cmd succeeded, else False

        """
        logging.debug('Using Rsync.')
        remote_dest = self._encode_remote_paths([dest])
        try:
            rsync = self._make_rsync_cmd(local_sources,
                                         remote_dest,
                                         delete_dest,
                                         preserve_symlinks,
                                         False,
                                         excludes=excludes)
            utils.run(rsync)
            return True
        except error.CmdError as e:
            logging.warning("trying scp, rsync failed: %s", e)
        return False

    def _send_using_scp(self, dest, source, delete_dest, excludes):
        """Send using scp.

        Args:
                source: either
                        1) a single file or directory, as a string
                        2) a list of one or more (possibly mixed)
                                files or directories
                dest: a file or a directory (if source contains a
                        directory or more than one element, you must
                        supply a directory dest)
                delete_dest: if this is true, the command will also clear
                             out any old files at dest that are not in the
                             source
                excludes: A list of file pattern that matches files not to be
                          sent. `send_file` will fail if exclude is set, since
                          local copy does not support --exclude, e.g., when
                          using scp to copy file.

        Raises:
                AutoservRunError: the scp command failed
        """
        logging.debug('Trying scp.')
        if excludes:
            raise error.AutotestHostRunError(
                    '--exclude is not supported in scp, try to use rsync. '
                    'excludes: %s' % ','.join(excludes), None)

        # scp has no equivalent to --delete, just drop the entire dest dir
        if delete_dest:
            is_dir = self.run("ls -d %s/" % dest,
                              ignore_status=True).exit_status == 0
            if is_dir:
                cmd = "rm -rf %s && mkdir %s"
                cmd %= (dest, dest)
                self.run(cmd)

        remote_dest = self._encode_remote_paths([dest], use_scp=True)
        local_sources = self._make_rsync_compatible_source(source, True)
        if local_sources:
            sources = self._encode_local_paths(local_sources, escape=False)
            scp = self._make_scp_cmd(sources, remote_dest)
            try:
                utils.run(scp)
            except error.CmdError as e:
                logging.debug('scp failed: %s', e)
                raise error.AutoservRunError(e.args[0], e.args[1])
        else:
            logging.debug('skipping scp for empty source list')

    def verify_ssh_user_access(self):
        """Verify ssh access to this host.

        @returns False if ssh_ping fails due to Permissions error, True
                 otherwise.
        """
        try:
            self.ssh_ping()
        except (error.AutoservSshPermissionDeniedError,
                error.AutoservSshPingHostError):
            return False
        return True


    def ssh_ping(self, timeout=60, connect_timeout=None, base_cmd='true'):
        """
        Pings remote host via ssh.

        @param timeout: Command execution timeout in seconds.
                        Defaults to 60 seconds.
        @param connect_timeout: ssh connection timeout in seconds.
        @param base_cmd: The base command to run with the ssh ping.
                         Defaults to true.
        @raise AutoservSSHTimeout: If the ssh ping times out.
        @raise AutoservSshPermissionDeniedError: If ssh ping fails due to
                                                 permissions.
        @raise AutoservSshPingHostError: For other AutoservRunErrors.
        """
        ctimeout = min(timeout, connect_timeout or timeout)
        try:
            self.run(base_cmd, timeout=timeout, connect_timeout=ctimeout,
                     ssh_failure_retry_ok=True)
        except error.AutoservSSHTimeout:
            msg = "Host (ssh) verify timed out (timeout = %d)" % timeout
            raise error.AutoservSSHTimeout(msg)
        except error.AutoservSshPermissionDeniedError:
            #let AutoservSshPermissionDeniedError be visible to the callers
            raise
        except error.AutoservRunError as e:
            # convert the generic AutoservRunError into something more
            # specific for this context
            raise error.AutoservSshPingHostError(e.description + '\n' +
                                                 repr(e.result_obj))


    def is_up(self, timeout=60, connect_timeout=None, base_cmd='true'):
        """
        Check if the remote host is up by ssh-ing and running a base command.

        @param timeout: command execution timeout in seconds.
        @param connect_timeout: ssh connection timeout in seconds.
        @param base_cmd: a base command to run with ssh. The default is 'true'.
        @returns True if the remote host is up before the timeout expires,
                 False otherwise.
        """
        try:
            self.ssh_ping(timeout=timeout,
                          connect_timeout=connect_timeout,
                          base_cmd=base_cmd)
        except error.AutoservError:
            return False
        else:
            return True


    def is_up_fast(self, count=1):
        """Return True if the host can be pinged.

        @param count How many time try to ping before decide that host is not
                    reachable by ping.
        """
        if not self._use_icmp:
            stack = self._get_server_stack_state(lowest_frames=1,
                                                 highest_frames=7)
            logging.warning("is_up_fast called with icmp disabled from %s!",
                            stack)
            return True
        ping_config = ping_runner.PingConfig(self.hostname,
                                             count=1,
                                             ignore_result=True,
                                             ignore_status=True)

        # Run up to the amount specified, but also exit as soon as the first
        # reply is found.
        loops_remaining = count
        while loops_remaining > 0:
            loops_remaining -= 1
            if ping_runner.PingRunner().ping(ping_config).received > 0:
                return True
        return False


    def wait_up(self,
                timeout=_DEFAULT_WAIT_UP_TIME_SECONDS,
                host_is_down=False):
        """
        Wait until the remote host is up or the timeout expires.

        In fact, it will wait until an ssh connection to the remote
        host can be established, and getty is running.

        @param timeout time limit in seconds before returning even
            if the host is not up.
        @param host_is_down set to True if the host is known to be down before
            wait_up.

        @returns True if the host was found to be up before the timeout expires,
                 False otherwise
        """
        if host_is_down:
            # Since we expect the host to be down when this is called, if there is
            # an existing ssh main connection close it.
            self.close_main_ssh()
        current_time = int(time.time())
        end_time = current_time + timeout

        ssh_success_logged = False
        autoserv_error_logged = False
        while current_time < end_time:
            ping_timeout = min(_DEFAULT_MAX_PING_TIMEOUT,
                               end_time - current_time)
            if self.is_up(timeout=ping_timeout, connect_timeout=ping_timeout):
                if not ssh_success_logged:
                    logging.debug('Successfully pinged host %s',
                                  self.host_port)
                    wait_procs = self.get_wait_up_processes()
                    if wait_procs:
                        logging.debug('Waiting for processes: %s', wait_procs)
                    else:
                        logging.debug('No wait_up processes to wait for')
                    ssh_success_logged = True
                try:
                    if self.are_wait_up_processes_up():
                        logging.debug('Host %s is now up', self.host_port)
                        return True
                except error.AutoservError as e:
                    if not autoserv_error_logged:
                        logging.debug('Ignoring failure to reach %s: %s %s',
                                      self.host_port, e,
                                      '(and further similar failures)')
                        autoserv_error_logged = True
            time.sleep(1)
            current_time = int(time.time())

        logging.debug('Host %s is still down after waiting %d seconds',
                      self.host_port, int(timeout + time.time() - end_time))
        return False


    def wait_down(self, timeout=_DEFAULT_WAIT_DOWN_TIME_SECONDS,
                  warning_timer=None, old_boot_id=None,
                  max_ping_timeout=_DEFAULT_MAX_PING_TIMEOUT):
        """
        Wait until the remote host is down or the timeout expires.

        If old_boot_id is provided, waits until either the machine is
        unpingable or self.get_boot_id() returns a value different from
        old_boot_id. If the boot_id value has changed then the function
        returns True under the assumption that the machine has shut down
        and has now already come back up.

        If old_boot_id is None then until the machine becomes unreachable the
        method assumes the machine has not yet shut down.

        @param timeout Time limit in seconds before returning even if the host
            is still up.
        @param warning_timer Time limit in seconds that will generate a warning
            if the host is not down yet. Can be None for no warning.
        @param old_boot_id A string containing the result of self.get_boot_id()
            prior to the host being told to shut down. Can be None if this is
            not available.
        @param max_ping_timeout Maximum timeout in seconds for each
            self.get_boot_id() call. If this timeout is hit, it is assumed that
            the host went down and became unreachable.

        @returns True if the host was found to be down (max_ping_timeout timeout
            expired or boot_id changed if provided) and False if timeout
            expired.
        """
        #TODO: there is currently no way to distinguish between knowing
        #TODO: boot_id was unsupported and not knowing the boot_id.
        current_time = int(time.time())
        end_time = current_time + timeout

        if warning_timer:
            warn_time = current_time + warning_timer

        if old_boot_id is not None:
            logging.debug('Host %s pre-shutdown boot_id is %s',
                          self.host_port, old_boot_id)

        # Impose semi real-time deadline constraints, since some clients
        # (eg: watchdog timer tests) expect strict checking of time elapsed.
        # Each iteration of this loop is treated as though it atomically
        # completes within current_time, this is needed because if we used
        # inline time.time() calls instead then the following could happen:
        #
        # while time.time() < end_time:                     [23 < 30]
        #    some code.                                     [takes 10 secs]
        #    try:
        #        new_boot_id = self.get_boot_id(timeout=end_time - time.time())
        #                                                   [30 - 33]
        # The last step will lead to a return True, when in fact the machine
        # went down at 32 seconds (>30). Hence we need to pass get_boot_id
        # the same time that allowed us into that iteration of the loop.
        while current_time < end_time:
            ping_timeout = min(end_time - current_time, max_ping_timeout)
            try:
                new_boot_id = self.get_boot_id(timeout=ping_timeout)
            except error.AutoservError:
                logging.debug('Host %s is now unreachable over ssh, is down',
                              self.host_port)
                return True
            else:
                # if the machine is up but the boot_id value has changed from
                # old boot id, then we can assume the machine has gone down
                # and then already come back up
                if old_boot_id is not None and old_boot_id != new_boot_id:
                    logging.debug('Host %s now has boot_id %s and so must '
                                  'have rebooted', self.host_port, new_boot_id)
                    return True

            if warning_timer and current_time > warn_time:
                self.record("INFO", None, "shutdown",
                            "Shutdown took longer than %ds" % warning_timer)
                # Print the warning only once.
                warning_timer = None
                # If a machine is stuck switching runlevels
                # This may cause the machine to reboot.
                self.run('kill -HUP 1', ignore_status=True)

            time.sleep(1)
            current_time = int(time.time())

        return False


    # tunable constants for the verify & repair code
    AUTOTEST_GB_DISKSPACE_REQUIRED = get_value("SERVER",
                                               "gb_diskspace_required",
                                               type=float,
                                               default=20.0)


    def verify_connectivity(self):
        super(AbstractSSHHost, self).verify_connectivity()

        logging.info('Pinging host %s', self.host_port)
        self.ssh_ping()
        logging.info("Host (ssh) %s is alive", self.host_port)

        if self.is_shutting_down():
            raise error.AutoservHostIsShuttingDownError("Host is shutting down")


    def verify_software(self):
        super(AbstractSSHHost, self).verify_software()
        try:
            self.check_diskspace(autotest.Autotest.get_install_dir(self),
                                 self.AUTOTEST_GB_DISKSPACE_REQUIRED)
        except error.AutoservDiskFullHostError:
            # only want to raise if it's a space issue
            raise
        except (error.AutoservHostError, autotest.AutodirNotFoundError):
            logging.exception('autodir space check exception, this is probably '
                             'safe to ignore\n')

    def close(self):
        super(AbstractSSHHost, self).close()
        self.rpc_server_tracker.disconnect_all()
        if not self._connection_pool:
            self._main_ssh.close()
        if os.path.exists(self.known_hosts_file):
            os.remove(self.known_hosts_file)
        self.tls_exec_dut_command = None

    def close_main_ssh(self):
        """Stop the ssh main connection.

        Intended for situations when the host is known to be down and we don't
        need a ssh timeout to tell us it is down. For example, if you just
        instructed the host to shutdown or hibernate.
        """
        logging.debug("Stopping main ssh connection")
        self._main_ssh.close()

    def restart_main_ssh(self):
        """
        Stop and restart the ssh main connection.  This is meant as a last
        resort when ssh commands fail and we don't understand why.
        """
        logging.debug("Restarting main ssh connection")
        self._main_ssh.close()
        self._main_ssh.maybe_start(timeout=30)

    def start_main_ssh(self, timeout=DEFAULT_START_MAIN_SSH_TIMEOUT_S):
        """
        Called whenever a non-main SSH connection needs to be initiated (e.g.,
        by run, rsync, scp). If main SSH support is enabled and a main SSH
        connection is not active already, start a new one in the background.
        Also, cleanup any zombie main SSH connections (e.g., dead due to
        reboot).

        timeout: timeout in seconds (default 5) to wait for main ssh
                 connection to be established. If timeout is reached, a
                 warning message is logged, but no other action is taken.
        """
        if not enable_main_ssh:
            return
        self._main_ssh.maybe_start(timeout=timeout)

    @property
    def tls_unstable(self):
        # A single test will rebuild remote many times. Its safe to assume if
        # TLS unstable for one try, it will be for others. If we check each,
        # it adds ~60 seconds per test (if its dead).
        if os.getenv('TLS_UNSTABLE'):
            return bool(os.getenv('TLS_UNSTABLE'))
        if self._tls_unstable is not None:
            return self._tls_unstable

    @tls_unstable.setter
    def tls_unstable(self, v):
        if not isinstance(v, bool):
            raise error.AutoservError('tls_stable setting must be bool, got %s'
                                      % (type(v)))
        os.environ['TLS_UNSTABLE'] = str(v)
        self._tls_unstable = v

    @property
    def tls_exec_dut_command_client(self):
        # If client is already initialized, return that.
        if not ENABLE_EXEC_DUT_COMMAND:
            return None
        if self.tls_unstable:
            return None
        if self._tls_exec_dut_command_client is not None:
            return self._tls_exec_dut_command_client
        # If the TLS connection is alive, create a new client.
        if self.tls_connection is None:
            return None
        return exec_dut_command.TLSExecDutCommandClient(
            tlsconnection=self.tls_connection,
            hostname=self.hostname)

    def clear_known_hosts(self):
        """Clears out the temporary ssh known_hosts file.

        This is useful if the test SSHes to the machine, then reinstalls it,
        then SSHes to it again.  It can be called after the reinstall to
        reduce the spam in the logs.
        """
        logging.info("Clearing known hosts for host '%s', file '%s'.",
                     self.host_port, self.known_hosts_file)
        # Clear out the file by opening it for writing and then closing.
        fh = open(self.known_hosts_file, "w")
        fh.close()


    def collect_logs(self, remote_src_dir, local_dest_dir, ignore_errors=True):
        """Copy log directories from a host to a local directory.

        @param remote_src_dir: A destination directory on the host.
        @param local_dest_dir: A path to a local destination directory.
            If it doesn't exist it will be created.
        @param ignore_errors: If True, ignore exceptions.

        @raises OSError: If there were problems creating the local_dest_dir and
            ignore_errors is False.
        @raises AutoservRunError, AutotestRunError: If something goes wrong
            while copying the directories and ignore_errors is False.
        """
        if not self.check_cached_up_status():
            logging.warning('Host %s did not answer to ping, skip collecting '
                            'logs.', self.host_port)
            return

        locally_created_dest = False
        if (not os.path.exists(local_dest_dir)
                or not os.path.isdir(local_dest_dir)):
            try:
                os.makedirs(local_dest_dir)
                locally_created_dest = True
            except OSError as e:
                logging.warning('Unable to collect logs from host '
                                '%s: %s', self.host_port, e)
                if not ignore_errors:
                    raise
                return

        # Build test result directory summary
        try:
            result_tools_runner.run_on_client(self, remote_src_dir)
        except (error.AutotestRunError, error.AutoservRunError,
                error.AutoservSSHTimeout) as e:
            logging.exception(
                    'Non-critical failure: Failed to collect and throttle '
                    'results at %s from host %s', remote_src_dir,
                    self.host_port)

        try:
            self.get_file(remote_src_dir, local_dest_dir, safe_symlinks=True)
        except (error.AutotestRunError, error.AutoservRunError,
                error.AutoservSSHTimeout) as e:
            logging.warning('Collection of %s to local dir %s from host %s '
                            'failed: %s', remote_src_dir, local_dest_dir,
                            self.host_port, e)
            if locally_created_dest:
                shutil.rmtree(local_dest_dir, ignore_errors=ignore_errors)
            if not ignore_errors:
                raise

        # Clean up directory summary file on the client side.
        try:
            result_tools_runner.run_on_client(self, remote_src_dir,
                                              cleanup_only=True)
        except (error.AutotestRunError, error.AutoservRunError,
                error.AutoservSSHTimeout) as e:
            logging.exception(
                    'Non-critical failure: Failed to cleanup result summary '
                    'files at %s in host %s', remote_src_dir, self.hostname)


    def create_ssh_tunnel(self, port, local_port):
        """Create an ssh tunnel from local_port to port.

        This is used to forward a port securely through a tunnel process from
        the server to the DUT for RPC server connection.

        @param port: remote port on the host.
        @param local_port: local forwarding port.

        @return: the tunnel process.
        """
        tunnel_options = '-n -N -q -L %d:localhost:%d' % (local_port, port)
        ssh_cmd = self.make_ssh_command(opts=tunnel_options, port=self.port)
        tunnel_cmd = '%s %s' % (ssh_cmd, self.hostname)
        logging.debug('Full tunnel command: %s', tunnel_cmd)
        # Exec the ssh process directly here rather than using a shell.
        # Using a shell leaves a dangling ssh process, because we deliver
        # signals to the shell wrapping ssh, not the ssh process itself.
        args = shlex.split(tunnel_cmd)
        with open('/dev/null', 'w') as devnull:
            tunnel_proc = subprocess.Popen(args, stdout=devnull, stderr=devnull,
                                           close_fds=True)
        logging.debug('Started ssh tunnel, local = %d'
                      ' remote = %d, pid = %d',
                      local_port, port, tunnel_proc.pid)
        return tunnel_proc


    def disconnect_ssh_tunnel(self, tunnel_proc):
        """
        Disconnects a previously forwarded port from the server to the DUT for
        RPC server connection.

        @param tunnel_proc: a tunnel process returned from |create_ssh_tunnel|.
        """
        if tunnel_proc.poll() is None:
            tunnel_proc.terminate()
            logging.debug('Terminated tunnel, pid %d', tunnel_proc.pid)
        else:
            logging.debug('Tunnel pid %d terminated early, status %d',
                          tunnel_proc.pid, tunnel_proc.returncode)


    def get_os_type(self):
        """Returns the host OS descriptor (to be implemented in subclasses).

        @return A string describing the OS type.
        """
        raise NotImplementedError


    def check_cached_up_status(
            self, expiration_seconds=_DEFAULT_UP_STATUS_EXPIRATION_SECONDS):
        """Check if the DUT responded to ping in the past `expiration_seconds`.

        @param expiration_seconds: The number of seconds to keep the cached
                status of whether the DUT responded to ping.
        @return: True if the DUT has responded to ping during the past
                 `expiration_seconds`.
        """
        # Refresh the up status if any of following conditions is true:
        # * cached status is never set
        # * cached status is False, so the method can check if the host is up
        #   again.
        # * If the cached status is older than `expiration_seconds`
        # If we have icmp disabled, treat that as a cached ping.
        if not self._use_icmp:
            return True
        expire_time = time.time() - expiration_seconds
        if (self._cached_up_status_updated is None or
                not self._cached_up_status or
                self._cached_up_status_updated < expire_time):
            self._cached_up_status = self.is_up_fast()
            self._cached_up_status_updated = time.time()
        return self._cached_up_status


    def _track_class_usage(self):
        """Tracking which class was used.

        The idea to identify unused classes to be able clean them up.
        We skip names with dynamic created classes where the name is
        hostname of the device.
        """
        class_name = None
        if 'chrome' not in self.__class__.__name__:
            class_name = self.__class__.__name__
        else:
            for base in self.__class__.__bases__:
                if 'chrome' not in base.__name__:
                    class_name = base.__name__
                    break
        if class_name:
            data = {'host_class': class_name}
            metrics.Counter(
                'chromeos/autotest/used_hosts').increment(fields=data)

    def is_file_exists(self, file_path):
        """Check whether a given file is exist on the host.
        """
        result = self.run('test -f ' + file_path,
                          timeout=30,
                          ignore_status=True)
        return result.exit_status == 0


def _client_symlink(sources):
    """Return the client symlink if in sources."""
    for source in sources:
        if source.endswith(AUTOTEST_CLIENT_SYMLINK_END):
            return source
    return None
