# Lint as: python2, python3
# Copyright 2007 Google Inc. Released under the GPL v2
#pylint: disable-msg=C0111

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import glob
import logging
import os
import re
import sys
import tempfile
import time
import traceback

import common
from autotest_lib.client.bin.result_tools import runner as result_tools_runner
from autotest_lib.client.common_lib import autotemp
from autotest_lib.client.common_lib import base_job
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib import global_config
from autotest_lib.client.common_lib import packages
from autotest_lib.client.common_lib import utils as client_utils
from autotest_lib.server import installable_object
from autotest_lib.server import utils
from autotest_lib.server import utils as server_utils
from autotest_lib.server.cros.dynamic_suite.constants import JOB_REPO_URL
import six
from six.moves import map


try:
    from autotest_lib.utils.frozen_chromite.lib import metrics
except ImportError:
    metrics = client_utils.metrics_mock


# This is assumed to be the value by tests, do not change it.
OFFLOAD_ENVVAR = "SYNCHRONOUS_OFFLOAD_DIR"

AUTOTEST_SVN = 'svn://test.kernel.org/autotest/trunk/client'
AUTOTEST_HTTP = 'http://test.kernel.org/svn/autotest/trunk/client'

_CONFIG = global_config.global_config
AUTOSERV_PREBUILD = _CONFIG.get_config_value(
        'AUTOSERV', 'enable_server_prebuild', type=bool, default=False)

# Match on a line like this:
# FAIL test_name  test_name timestamp=1 localtime=Nov 15 12:43:10 <fail_msg>
_FAIL_STATUS_RE = re.compile(
    r'\s*FAIL.*localtime=.*\s*.*\s*[0-9]+:[0-9]+:[0-9]+\s*(?P<fail_msg>.*)')

LOG_BUFFER_SIZE_BYTES = 64


def _set_py_version():
    """As of ~R102 (aka when this merges), DUTs only have Python 3."""
    return '--py_version=3'


class AutodirNotFoundError(Exception):
    """No Autotest installation could be found."""


class AutotestFailure(Exception):
    """Gereric exception class for failures during a test run."""


class AutotestAbort(AutotestFailure):
    """
    AutotestAborts are thrown when the DUT seems fine,
    and the test doesn't give us an explicit reason for
    failure; In this case we have no choice but to abort.
    """


class AutotestDeviceError(AutotestFailure):
    """
    Exceptions that inherit from AutotestDeviceError
    are thrown when we can determine the current
    state of the DUT and conclude that it probably
    lead to the test failing; these exceptions lead
    to failures instead of aborts.
    """


class AutotestDeviceNotPingable(AutotestDeviceError):
    """Error for when a DUT becomes unpingable."""


class AutotestDeviceNotSSHable(AutotestDeviceError):
    """Error for when a DUT is pingable but not SSHable."""


class AutotestDeviceRebooted(AutotestDeviceError):
    """Error for when a DUT rebooted unexpectedly."""


class Autotest(installable_object.InstallableObject):
    """
    This class represents the Autotest program.

    Autotest is used to run tests automatically and collect the results.
    It also supports profilers.

    Implementation details:
    This is a leaf class in an abstract class hierarchy, it must
    implement the unimplemented methods in parent classes.
    """

    def __init__(self, host=None):
        self.host = host
        self.got = False
        self.installed = False
        self.serverdir = utils.get_server_dir()
        super(Autotest, self).__init__()


    install_in_tmpdir = False
    @classmethod
    def set_install_in_tmpdir(cls, flag):
        """ Sets a flag that controls whether or not Autotest should by
        default be installed in a "standard" directory (e.g.
        /home/autotest, /usr/local/autotest) or a temporary directory. """
        cls.install_in_tmpdir = flag


    @classmethod
    def get_client_autodir_paths(cls, host):
        return global_config.global_config.get_config_value(
                'AUTOSERV', 'client_autodir_paths', type=list)


    @classmethod
    def get_installed_autodir(cls, host):
        """
        Find where the Autotest client is installed on the host.
        @returns an absolute path to an installed Autotest client root.
        @raises AutodirNotFoundError if no Autotest installation can be found.
        """
        autodir = host.get_autodir()
        if autodir:
            logging.debug('Using existing host autodir: %s', autodir)
            return autodir

        for path in Autotest.get_client_autodir_paths(host):
            try:
                autotest_binary = os.path.join(path, 'bin', 'autotest')
                host.run('test -x %s' % utils.sh_escape(autotest_binary))
                host.run('test -w %s' % utils.sh_escape(path))
                logging.debug('Found existing autodir at %s', path)
                return path
            except error.GenericHostRunError:
                logging.debug('%s does not exist on %s', autotest_binary,
                              host.hostname)
        raise AutodirNotFoundError


    @classmethod
    def get_install_dir(cls, host):
        """
        Determines the location where autotest should be installed on
        host. If self.install_in_tmpdir is set, it will return a unique
        temporary directory that autotest can be installed in. Otherwise, looks
        for an existing installation to use; if none is found, looks for a
        usable directory in the global config client_autodir_paths.
        """
        try:
            install_dir = cls.get_installed_autodir(host)
        except AutodirNotFoundError:
            install_dir = cls._find_installable_dir(host)

        if cls.install_in_tmpdir:
            return host.get_tmp_dir(parent=install_dir)
        return install_dir


    @classmethod
    def _find_installable_dir(cls, host):
        client_autodir_paths = cls.get_client_autodir_paths(host)
        for path in client_autodir_paths:
            try:
                host.run('mkdir -p %s' % utils.sh_escape(path))
                host.run('test -w %s' % utils.sh_escape(path))
                return path
            except error.AutoservRunError:
                logging.debug('Failed to create %s', path)
        metrics.Counter(
            'chromeos/autotest/errors/no_autotest_install_path').increment(
                fields={'dut_host_name': host.hostname})
        raise error.AutoservInstallError(
                'Unable to find a place to install Autotest; tried %s' %
                ', '.join(client_autodir_paths))


    def get_fetch_location(self):
        """Generate list of locations where autotest can look for packages.

        Hosts are tagged with an attribute containing the URL from which
        to source packages when running a test on that host.

        @returns the list of candidate locations to check for packages.
        """
        c = global_config.global_config
        repos = c.get_config_value("PACKAGES", 'fetch_location', type=list,
                                   default=[])
        repos.reverse()

        if not server_utils.is_inside_chroot():
            # Only try to get fetch location from host attribute if the
            # test is not running inside chroot.
            #
            # Look for the repo url via the host attribute. If we are
            # not running with a full AFE autoserv will fall back to
            # serving packages itself from whatever source version it is
            # sync'd to rather than using the proper artifacts for the
            # build on the host.
            found_repo = self._get_fetch_location_from_host_attribute()
            if found_repo is not None:
                # Add our new repo to the end, the package manager will
                # later reverse the list of repositories resulting in ours
                # being first
                repos.append(found_repo)

        return repos


    def _get_fetch_location_from_host_attribute(self):
        """Get repo to use for packages from host attribute, if possible.

        Hosts are tagged with an attribute containing the URL
        from which to source packages when running a test on that host.
        If self.host is set, attempt to look this attribute in the host info.

        @returns value of the 'job_repo_url' host attribute, if present.
        """
        if not self.host:
            return None

        try:
            info = self.host.host_info_store.get()
        except Exception as e:
            # TODO(pprabhu): We really want to catch host_info.StoreError here,
            # but we can't import host_info from this module.
            #   - autotest_lib.hosts.host_info pulls in (naturally)
            #   autotest_lib.hosts.__init__
            #   - This pulls in all the host classes ever defined
            #   - That includes abstract_ssh, which depends on autotest
            logging.warning('Failed to obtain host info: %r', e)
            logging.warning('Skipping autotest fetch location based on %s',
                            JOB_REPO_URL)
            return None

        job_repo_url = info.attributes.get(JOB_REPO_URL, '')
        if not job_repo_url:
            logging.warning("No %s for %s", JOB_REPO_URL, self.host)
            return None

        logging.info('Got job repo url from host attributes: %s',
                        job_repo_url)
        return job_repo_url


    def install(self, host=None, autodir=None, use_packaging=True):
        """Install autotest.  If |host| is not None, stores it in |self.host|.

        @param host A Host instance on which autotest will be installed
        @param autodir Location on the remote host to install to
        @param use_packaging Enable install modes that use the packaging system.

        """
        if host:
            self.host = host
        self._install(host=host, autodir=autodir, use_packaging=use_packaging)


    def install_full_client(self, host=None, autodir=None):
        self._install(host=host, autodir=autodir, use_autoserv=False,
                      use_packaging=False)


    def install_no_autoserv(self, host=None, autodir=None):
        self._install(host=host, autodir=autodir, use_autoserv=False)


    def _install_using_packaging(self, host, autodir):
        repos = self.get_fetch_location()
        if not repos:
            raise error.PackageInstallError("No repos to install an "
                                            "autotest client from")
        # Make sure devserver has the autotest package staged
        host.verify_job_repo_url()
        pkgmgr = packages.PackageManager(autodir, hostname=host.hostname,
                                         repo_urls=repos,
                                         do_locking=False,
                                         run_function=host.run,
                                         run_function_dargs=dict(timeout=600))
        # The packages dir is used to store all the packages that
        # are fetched on that client. (for the tests,deps etc.
        # too apart from the client)
        pkg_dir = os.path.join(autodir, 'packages')
        # clean up the autodir except for the packages and result_tools
        # directory.
        host.run('cd %s && ls | grep -v "^packages$" | grep -v "^result_tools$"'
                 ' | xargs rm -rf && rm -rf .[!.]*' % autodir)
        pkgmgr.install_pkg('autotest', 'client', pkg_dir, autodir,
                           preserve_install_dir=True)
        self.installed = True


    def _install_using_send_file(self, host, autodir):
        dirs_to_exclude = set(["tests", "site_tests", "deps", "profilers",
                               "packages"])
        light_files = [os.path.join(self.source_material, f)
                       for f in os.listdir(self.source_material)
                       if f not in dirs_to_exclude]
        host.send_file(light_files, autodir, delete_dest=True)

        # create empty dirs for all the stuff we excluded
        commands = []
        for path in dirs_to_exclude:
            abs_path = os.path.join(autodir, path)
            abs_path = utils.sh_escape(abs_path)
            commands.append("mkdir -p '%s'" % abs_path)
            commands.append("touch '%s'/__init__.py" % abs_path)
        host.run(';'.join(commands))


    def _install(self, host=None, autodir=None, use_autoserv=True,
                 use_packaging=True):
        """
        Install autotest.  If get() was not called previously, an
        attempt will be made to install from the autotest svn
        repository.

        @param host A Host instance on which autotest will be installed
        @param autodir Location on the remote host to install to
        @param use_autoserv Enable install modes that depend on the client
            running with the autoserv harness
        @param use_packaging Enable install modes that use the packaging system

        @exception AutoservError if a tarball was not specified and
            the target host does not have svn installed in its path
        """
        if not host:
            host = self.host
        if not self.got:
            self.get()
        host.wait_up(timeout=30)
        host.setup()
        # B/203609358 someting is removing telemetry. Adding this to check the
        # status of the folder as early as possible.
        logging.info("Installing autotest on %s", host.hostname)

        # set up the autotest directory on the remote machine
        if not autodir:
            autodir = self.get_install_dir(host)
        logging.info('Using installation dir %s', autodir)
        host.set_autodir(autodir)
        host.run('mkdir -p %s' % utils.sh_escape(autodir))

        # make sure there are no files in $AUTODIR/results
        results_path = os.path.join(autodir, 'results')
        host.run('rm -rf %s/*' % utils.sh_escape(results_path),
                 ignore_status=True)

        # Fetch the autotest client from the nearest repository
        if use_packaging:
            try:
                self._install_using_packaging(host, autodir)
                logging.info("Installation of autotest completed using the "
                             "packaging system.")
                return
            except (error.PackageInstallError, error.AutoservRunError,
                    global_config.ConfigError) as e:
                logging.info("Could not install autotest using the packaging "
                             "system: %s. Trying other methods", e)
        else:
            # Delete the package checksum file to force dut updating local
            # packages.
            command = ('rm -f "%s"' %
                       (os.path.join(autodir, packages.CHECKSUM_FILE)))
            host.run(command)

        # try to install from file or directory
        if self.source_material:
            c = global_config.global_config
            supports_autoserv_packaging = c.get_config_value(
                "PACKAGES", "serve_packages_from_autoserv", type=bool)
            # Copy autotest recursively
            if supports_autoserv_packaging and use_autoserv:
                self._install_using_send_file(host, autodir)
            else:
                host.send_file(self.source_material, autodir, delete_dest=True)
            logging.info("Installation of autotest completed from %s",
                         self.source_material)
            self.installed = True
        else:
            # if that fails try to install using svn
            if utils.run('which svn').exit_status:
                raise error.AutoservError(
                        'svn not found on target machine: %s' %
                        host.hostname)
            try:
                host.run('svn checkout %s %s' % (AUTOTEST_SVN, autodir))
            except error.AutoservRunError as e:
                host.run('svn checkout %s %s' % (AUTOTEST_HTTP, autodir))
            logging.info("Installation of autotest completed using SVN.")
            self.installed = True

        # TODO(milleral): http://crbug.com/258161
        # Send over the most recent global_config.ini after installation if one
        # is available.
        # This code is a bit duplicated from
        # _Run._create_client_config_file, but oh well.
        if self.installed and self.source_material:
            self._send_shadow_config()

        # sync the disk, to avoid getting 0-byte files if a test resets the DUT
        host.run(os.path.join(autodir, 'bin', 'fs_sync.py'),
                 ignore_status=True)

    def _send_shadow_config(self):
        logging.info('Installing updated global_config.ini.')
        destination = os.path.join(self.host.get_autodir(),
                                   'global_config.ini')
        with tempfile.NamedTemporaryFile(mode='w') as client_config:
            config = global_config.global_config
            client_section = config.get_section_values('CLIENT')
            client_section.write(client_config)
            client_config.flush()
            self.host.send_file(client_config.name, destination)


    def uninstall(self, host=None):
        """
        Uninstall (i.e. delete) autotest. Removes the autotest client install
        from the specified host.

        @params host a Host instance from which the client will be removed
        """
        if not self.installed:
            return
        if not host:
            host = self.host
        autodir = host.get_autodir()
        if not autodir:
            return

        # perform the actual uninstall
        host.run("rm -rf %s" % utils.sh_escape(autodir), ignore_status=True)
        host.set_autodir(None)
        self.installed = False


    def get(self, location=None):
        if not location:
            location = os.path.join(self.serverdir, '../client')
            location = os.path.abspath(location)
        installable_object.InstallableObject.get(self, location)
        self.got = True


    def run(self, control_file, results_dir='.', host=None, timeout=None,
            tag=None, parallel_flag=False, background=False,
            client_disconnect_timeout=None, use_packaging=True):
        """
        Run an autotest job on the remote machine.

        @param control_file: An open file-like-obj of the control file.
        @param results_dir: A str path where the results should be stored
                on the local filesystem.
        @param host: A Host instance on which the control file should
                be run.
        @param timeout: Maximum number of seconds to wait for the run or None.
        @param tag: Tag name for the client side instance of autotest.
        @param parallel_flag: Flag set when multiple jobs are run at the
                same time.
        @param background: Indicates that the client should be launched as
                a background job; the code calling run will be responsible
                for monitoring the client and collecting the results.
        @param client_disconnect_timeout: Seconds to wait for the remote host
                to come back after a reboot. Defaults to the host setting for
                DEFAULT_REBOOT_TIMEOUT.

        @raises AutotestRunError: If there is a problem executing
                the control file.
        """
        host = self._get_host_and_setup(host, use_packaging=use_packaging)
        logging.debug('Autotest job starts on remote host: %s',
                      host.hostname)
        results_dir = os.path.abspath(results_dir)

        if client_disconnect_timeout is None:
            client_disconnect_timeout = host.DEFAULT_REBOOT_TIMEOUT

        if tag:
            results_dir = os.path.join(results_dir, tag)

        atrun = _Run(host, results_dir, tag, parallel_flag, background)
        self._do_run(control_file, results_dir, host, atrun, timeout,
                     client_disconnect_timeout, use_packaging=use_packaging)


    def _get_host_and_setup(self, host, use_packaging=True):
        if not host:
            host = self.host
        if not self.installed:
            self.install(host, use_packaging=use_packaging)

        host.wait_up(timeout=30)
        return host


    def _do_run(self, control_file, results_dir, host, atrun, timeout,
                client_disconnect_timeout, use_packaging=True):
        try:
            atrun.verify_machine()
        except:
            logging.error("Verify failed on %s. Reinstalling autotest",
                          host.hostname)
            self.install(host)
            atrun.verify_machine()
        debug = os.path.join(results_dir, 'debug')
        try:
            os.makedirs(debug)
        except Exception:
            pass

        delete_file_list = [atrun.remote_control_file,
                            atrun.remote_control_file + '.state',
                            atrun.manual_control_file,
                            atrun.manual_control_file + '.state']
        cmd = ';'.join('rm -f ' + control for control in delete_file_list)
        host.run(cmd, ignore_status=True)

        tmppath = utils.get(control_file, local_copy=True)

        # build up the initialization prologue for the control file
        prologue_lines = []

        # Add the additional user arguments
        prologue_lines.append("args = %r\n" % self.job.args)

        # If the packaging system is being used, add the repository list.
        repos = None
        try:
            if use_packaging:
                repos = self.get_fetch_location()
                prologue_lines.append('job.add_repository(%s)\n' % repos)
            else:
                logging.debug('use_packaging is set to False, do not add any '
                              'repository.')
        except global_config.ConfigError as e:
            # If repos is defined packaging is enabled so log the error
            if repos:
                logging.error(e)

        # on full-size installs, turn on any profilers the server is using
        if not atrun.background:
            running_profilers = six.iteritems(host.job.profilers.add_log)
            for profiler, (args, dargs) in running_profilers:
                call_args = [repr(profiler)]
                call_args += [repr(arg) for arg in args]
                call_args += ["%s=%r" % item for item in six.iteritems(dargs)]
                prologue_lines.append("job.profilers.add(%s)\n"
                                      % ", ".join(call_args))
        cfile = "".join(prologue_lines)

        cfile += open(tmppath).read()
        open(tmppath, "w").write(cfile)

        # Create and copy state file to remote_control_file + '.state'
        state_file = host.job.preprocess_client_state()
        host.send_file(state_file, atrun.remote_control_file + '.init.state')
        os.remove(state_file)

        # Copy control_file to remote_control_file on the host
        host.send_file(tmppath, atrun.remote_control_file)
        if os.path.abspath(tmppath) != os.path.abspath(control_file):
            os.remove(tmppath)

        atrun.execute_control(
                timeout=timeout,
                client_disconnect_timeout=client_disconnect_timeout)


    @staticmethod
    def extract_test_failure_msg(failure_status_line):
        """Extract the test failure message from the status line.

        @param failure_status_line:  String of test failure status line, it will
            look like:
          FAIL <test name>  <test name> timestamp=<ts> localtime=<lt> <reason>

        @returns String of the reason, return empty string if we can't regex out
            reason.
        """
        fail_msg = ''
        match = _FAIL_STATUS_RE.match(failure_status_line)
        if match:
            fail_msg = match.group('fail_msg')
        return fail_msg


    @classmethod
    def _check_client_test_result(cls, host, test_name):
        """
        Check result of client test.
        Autotest will store results in the file name status.
        We check that second to last line in that file begins with 'END GOOD'

        @raises TestFail: If client test does not pass.
        """
        client_result_dir = '%s/results/default' % host.autodir
        command = 'tail -2 %s/status | head -1' % client_result_dir
        status = host.run(command).stdout.strip()
        logging.info(status)
        if status[:8] != 'END GOOD':
            test_fail_status_line_cmd = (
                    'grep "^\s*FAIL\s*%s" %s/status | tail -n 1' %
                    (test_name, client_result_dir))
            test_fail_msg = cls.extract_test_failure_msg(
                    host.run(test_fail_status_line_cmd).stdout.strip())
            test_fail_msg_reason = ('' if not test_fail_msg
                                    else ' (reason: %s)' % test_fail_msg)
            test_fail_status = '%s client test did not pass%s.' % (
                    test_name, test_fail_msg_reason)
            raise error.TestFail(test_fail_status)


    def run_timed_test(self, test_name, results_dir='.', host=None,
                       timeout=None, parallel_flag=False, background=False,
                       client_disconnect_timeout=None, *args, **dargs):
        """
        Assemble a tiny little control file to just run one test,
        and run it as an autotest client-side test
        """
        if not host:
            host = self.host
        if not self.installed:
            self.install(host)

        opts = ["%s=%s" % (o[0], repr(o[1])) for o in dargs.items()]
        cmd = ", ".join([repr(test_name)] + list(map(repr, args)) + opts)
        control = "job.run_test(%s)\n" % cmd
        self.run(control, results_dir, host, timeout=timeout,
                 parallel_flag=parallel_flag, background=background,
                 client_disconnect_timeout=client_disconnect_timeout)

        if dargs.get('check_client_result', False):
            self._check_client_test_result(host, test_name)


    def run_test(self,
                 test_name,
                 results_dir='.',
                 host=None,
                 parallel_flag=False,
                 background=False,
                 client_disconnect_timeout=None,
                 timeout=None,
                 *args,
                 **dargs):
        self.run_timed_test(
                test_name,
                results_dir,
                host,
                timeout=timeout,
                parallel_flag=parallel_flag,
                background=background,
                client_disconnect_timeout=client_disconnect_timeout,
                *args,
                **dargs)


    def run_static_method(self, module, method, results_dir='.', host=None,
                          *args):
        """Runs a non-instance method with |args| from |module| on the client.

        This method runs a static/class/module autotest method on the client.
        For example:
          run_static_method("autotest_lib.client.cros.cros_ui", "reboot")

        Will run autotest_lib.client.cros.cros_ui.reboot() on the client.

        @param module: module name as you would refer to it when importing in a
            control file. e.g. autotest_lib.client.common_lib.module_name.
        @param method: the method you want to call.
        @param results_dir: A str path where the results should be stored
            on the local filesystem.
        @param host: A Host instance on which the control file should
            be run.
        @param args: args to pass to the method.
        """
        control = "\n".join(["import %s" % module,
                             "%s.%s(%s)\n" % (module, method,
                                              ','.join(map(repr, args)))])
        self.run(control, results_dir=results_dir, host=host)


class _Run(object):
    """
    Represents a run of autotest control file.  This class maintains
    all the state necessary as an autotest control file is executed.

    It is not intended to be used directly, rather control files
    should be run using the run method in Autotest.
    """
    def __init__(self, host, results_dir, tag, parallel_flag, background):
        self.host = host
        self.results_dir = results_dir
        self.tag = tag
        self.parallel_flag = parallel_flag
        self.background = background
        self.autodir = Autotest.get_installed_autodir(self.host)
        control = os.path.join(self.autodir, 'control')
        if tag:
            control += '.' + tag
        self.manual_control_file = control
        self.remote_control_file = control + '.autoserv'
        self.config_file = os.path.join(self.autodir, 'global_config.ini')


    def verify_machine(self):
        binary = os.path.join(self.autodir, 'bin/autotest')
        at_check = "test -e {} && echo True || echo False".format(binary)
        if not self.parallel_flag:
            tmpdir = os.path.join(self.autodir, 'tmp')
            download = os.path.join(self.autodir, 'tests/download')
            at_check += "; umount {}; umount {}".format(tmpdir, download)
        # Check if the test dir is missing.
        if "False" in str(self.host.run(at_check, ignore_status=True).stdout):
            raise error.AutoservInstallError(
                "Autotest does not appear to be installed")



    def get_base_cmd_args(self, section):
        args = ['--verbose']
        if section > 0:
            args.append('-c')
        if self.tag:
            args.append('-t %s' % self.tag)
        if self.host.job.use_external_logging():
            args.append('-l')
        if self.host.hostname:
            args.append('--hostname=%s' % self.host.hostname)
        args.append('--user=%s' % self.host.job.user)

        args.append(self.remote_control_file)
        return args


    def get_background_cmd(self, section):
        cmd = [
                'nohup',
                os.path.join(self.autodir, 'bin/autotest_client'),
                _set_py_version()
        ]
        cmd += self.get_base_cmd_args(section)
        cmd += ['>/dev/null', '2>/dev/null', '&']
        return ' '.join(cmd)


    def get_daemon_cmd(self, section, monitor_dir):
        cmd = [
                'nohup',
                os.path.join(self.autodir, 'bin/autotestd'), monitor_dir,
                '-H autoserv',
                _set_py_version()
        ]
        cmd += self.get_base_cmd_args(section)
        cmd += ['>/dev/null', '2>/dev/null', '&']
        return ' '.join(cmd)


    def get_monitor_cmd(self, monitor_dir, stdout_read, stderr_read):
        cmd = [
                os.path.join(self.autodir, 'bin', 'autotestd_monitor'),
                monitor_dir,
                str(stdout_read),
                str(stderr_read),
                _set_py_version()
        ]
        return ' '.join(cmd)


    def get_client_log(self):
        """Find what the "next" client.* prefix should be

        @returns A string of the form client.INTEGER that should be prefixed
            to all client debug log files.
        """
        max_digit = -1
        debug_dir = os.path.join(self.results_dir, 'debug')
        client_logs = glob.glob(os.path.join(debug_dir, 'client.*.*'))
        for log in client_logs:
            _, number, _ = log.split('.', 2)
            if number.isdigit():
                max_digit = max(max_digit, int(number))
        return 'client.%d' % (max_digit + 1)


    def copy_client_config_file(self, client_log_prefix=None):
        """
        Create and copy the client config file based on the server config.

        @param client_log_prefix: Optional prefix to prepend to log files.
        """
        client_config_file = self._create_client_config_file(client_log_prefix)
        self.host.send_file(client_config_file, self.config_file)
        os.remove(client_config_file)


    def _create_client_config_file(self, client_log_prefix=None):
        """
        Create a temporary file with the [CLIENT] section configuration values
        taken from the server global_config.ini.

        @param client_log_prefix: Optional prefix to prepend to log files.

        @return: Path of the temporary file generated.
        """
        config = global_config.global_config.get_section_values('CLIENT')
        if client_log_prefix:
            config.set('CLIENT', 'default_logging_name', client_log_prefix)
        return self._create_aux_file(config.write)


    def _create_aux_file(self, func, *args):
        """
        Creates a temporary file and writes content to it according to a
        content creation function. The file object is appended to *args, which
        is then passed to the content creation function

        @param func: Function that will be used to write content to the
                temporary file.
        @param *args: List of parameters that func takes.
        @return: Path to the temporary file that was created.
        """
        fd, path = tempfile.mkstemp(dir=self.host.job.tmpdir)
        aux_file = os.fdopen(fd, "w")
        try:
            list_args = list(args)
            list_args.append(aux_file)
            func(*list_args)
        finally:
            aux_file.close()
        return path


    @staticmethod
    def is_client_job_finished(last_line):
        return bool(re.match(r'^\t*END .*\t[\w.-]+\t[\w.-]+\t.*$', last_line))


    @staticmethod
    def is_client_job_rebooting(last_line):
        return bool(re.match(r'^\t*GOOD\t[\w.-]+\treboot\.start.*$', last_line))


    # Roughly ordered list from concrete to less specific reboot causes.
    _failure_reasons = [
        # Try to find possible reasons leading towards failure.
        ('ethernet recovery methods have failed. Rebooting.',
         'dead ethernet dongle crbug/1031035'),
        # GPU hangs are not always recovered from.
        ('[drm:amdgpu_job_timedout] \*ERROR\* ring gfx timeout',
         'drm ring gfx timeout'),
        ('[drm:do_aquire_global_lock] \*ERROR(.*)hw_done or flip_done timed',
         'drm hw/flip timeout'),
        ('[drm:i915_hangcheck_hung] \*ERROR\* Hangcheck(.*)GPU hung',
         'drm GPU hung'),
        # TODO(ihf): try to get a better magic signature for kernel crashes.
        ('BUG: unable to handle kernel paging request', 'kernel paging'),
        ('Kernel panic - not syncing: Out of memory', 'kernel out of memory'),
        ('Kernel panic - not syncing', 'kernel panic'),
        # Fish for user mode killing OOM messages. Shows unstable system.
        ('out_of_memory', 'process out of memory'),
        # Reboot was bad enough to have truncated the logs.
        ('crash_reporter(.*)Stored kcrash', 'kcrash'),
        ('crash_reporter(.*)Last shutdown was not clean', 'not clean'),
    ]

    def _diagnose_reboot(self):
        """
        Runs diagnostic check on a rebooted DUT.

        TODO(ihf): if this analysis is useful consider moving the code to the
                   DUT into a script and call it from here. This is more
                   powerful and might be cleaner to grow in functionality. But
                   it may also be less robust if stateful is damaged during the
                   reboot.

        @returns msg describing reboot reason.
        """
        reasons = []
        for (message, bucket) in self._failure_reasons:
            # Use -a option for grep to avoid "binary file" warning to stdout.
            # The grep -v is added to not match itself in the log (across jobs).
            # Using grep is slightly problematic as it finds any reason, not
            # just the most recent reason (since 2 boots ago), so it may guess
            # wrong. Multiple reboots are unusual in the lab setting though and
            # it is better to have a reasonable guess than no reason at all.
            found = self.host.run(
                "grep -aE '" + message + "' /var/log/messages | grep -av grep",
                ignore_status=True
            ).stdout
            if found and found.strip():
                reasons.append(bucket)
        signature = 'reason unknown'
        if reasons:
            # Concatenate possible reasons found to obtain a magic signature.
            signature = ', '.join(reasons)
        return ('DUT rebooted during the test run. (%s)\n' % signature)


    def _diagnose_dut(self, old_boot_id=None):
        """
        Run diagnostic checks on a DUT.

        1. ping: A dead host will not respond to pings.
        2. ssh (happens with 3.): DUT hangs usually fail in authentication
            but respond to pings.
        3. Check if a reboot occured: A healthy but unexpected reboot leaves the
            host running with a new boot id.

        This method will always raise an exception from the AutotestFailure
        family and should only get called when the reason for a test failing
        is ambiguous.

        @raises AutotestDeviceNotPingable: If the DUT doesn't respond to ping.
        @raises AutotestDeviceNotSSHable: If we cannot SSH into the DUT.
        @raises AutotestDeviceRebooted: If the boot id changed.
        @raises AutotestAbort: If none of the above exceptions were raised.
            Since we have no recourse we must abort at this stage.
        """
        msg = 'Autotest client terminated unexpectedly: '
        if utils.ping(self.host.hostname, tries=1, deadline=1) != 0:
            msg += 'DUT is no longer pingable, it may have rebooted or hung.\n'
            raise AutotestDeviceNotPingable(msg)

        if old_boot_id:
            try:
                new_boot_id = self.host.get_boot_id(timeout=60)
            except Exception as e:
                msg += ('DUT is pingable but not SSHable, it most likely'
                        ' sporadically rebooted during testing. %s\n' % str(e))
                raise AutotestDeviceNotSSHable(msg)
            else:
                if new_boot_id != old_boot_id:
                    msg += self._diagnose_reboot()
                    raise AutotestDeviceRebooted(msg)

            msg += ('DUT is pingable, SSHable and did NOT restart '
                    'un-expectedly. We probably lost connectivity during the '
                    'test.')
        else:
            msg += ('DUT is pingable, could not determine if an un-expected '
                    'reboot occured during the test.')

        raise AutotestAbort(msg)


    def log_unexpected_abort(self, stderr_redirector, old_boot_id=None):
        """
        Logs that something unexpected happened, then tries to diagnose the
        failure. The purpose of this function is only to close out the status
        log with the appropriate error message, not to critically terminate
        the program.

        @param stderr_redirector: log stream.
        @param old_boot_id: boot id used to infer if a reboot occured.
        """
        stderr_redirector.flush_all_buffers()
        try:
            self._diagnose_dut(old_boot_id)
        except AutotestFailure as e:
            self.host.job.record('END ABORT', None, None, str(e))


    def _execute_in_background(self, section, timeout):
        full_cmd = self.get_background_cmd(section)
        devnull = open(os.devnull, "w")

        self.copy_client_config_file(self.get_client_log())

        self.host.job.push_execution_context(self.results_dir)
        try:
            result = self.host.run(full_cmd, ignore_status=True,
                                   timeout=timeout,
                                   stdout_tee=devnull,
                                   stderr_tee=devnull)
        finally:
            self.host.job.pop_execution_context()

        return result


    @staticmethod
    def _strip_stderr_prologue(stderr, monitor_cmd):
        """Strips the 'standard' prologue that get pre-pended to every
        remote command and returns the text that was actually written to
        stderr by the remote command.

        This will always strip atleast the first line ('standard' prologue),
        and strip any extra messages prior. The following are common 'extra'
        messages which could appear.

        1.) Any warnings. For example, on CrOS version R90, any script running
            in python2 result in the following warning in the stderr:
            "warning: Python 2.7 is deprecated and will be removed from CrOS by
            end of 2021. All users must migrate ASAP"
        2.) The actual command used to launch autotestd_monitor (monitor_cmd)

        Additionally there is a NOTE line that could be present needing also to
        be stripped.
        """
        stderr_lines = stderr.split("\n")
        if not stderr_lines:
            return ""

        # If no warnings/monitor_cmd, strip only the first line
        skipn = 1
        for i, line in enumerate(stderr_lines):
            if monitor_cmd in line:
                # add *2* (1 for the index, 1 for the 'standard prolouge'
                # which follows this line).
                skipn = i + 2
                break

        stderr_lines = stderr_lines[skipn:]

        if stderr_lines[0].startswith("NOTE: autotestd_monitor"):
            del stderr_lines[0]
        return "\n".join(stderr_lines)


    def _execute_daemon(self, section, timeout, stderr_redirector,
                        client_disconnect_timeout):
        monitor_dir = self.host.get_tmp_dir()
        daemon_cmd = self.get_daemon_cmd(section, monitor_dir)

        # grab the location for the server-side client log file
        client_log_prefix = self.get_client_log()
        client_log_path = os.path.join(self.results_dir, 'debug',
                                       client_log_prefix + '.log')
        client_log = open(client_log_path, 'w', LOG_BUFFER_SIZE_BYTES)
        self.copy_client_config_file(client_log_prefix)

        stdout_read = stderr_read = 0
        self.host.job.push_execution_context(self.results_dir)
        try:
            self.host.run(daemon_cmd, ignore_status=True, timeout=timeout)
            disconnect_warnings = []
            while True:
                monitor_cmd = self.get_monitor_cmd(monitor_dir, stdout_read,
                                                   stderr_read)
                try:
                    result = self.host.run(monitor_cmd, ignore_status=True,
                                           timeout=timeout,
                                           stdout_tee=client_log,
                                           stderr_tee=stderr_redirector)
                except error.AutoservRunError as e:
                    result = e.result_obj
                    result.exit_status = None
                    disconnect_warnings.append(e.description)

                    stderr_redirector.log_warning(
                        "Autotest client was disconnected: %s" % e.description,
                        "NETWORK")
                except error.AutoservSSHTimeout:
                    result = utils.CmdResult(monitor_cmd, "", "", None, 0)
                    stderr_redirector.log_warning(
                        "Attempt to connect to Autotest client timed out",
                        "NETWORK")

                stdout_read += len(result.stdout)
                stderr_read += len(
                        self._strip_stderr_prologue(result.stderr,
                                                    monitor_cmd))

                if result.exit_status is not None:
                    # TODO (crosbug.com/38224)- sbasi: Remove extra logging.
                    logging.debug('Result exit status is %d.',
                                  result.exit_status)
                    return result
                elif not self.host.wait_up(client_disconnect_timeout):
                    raise error.AutoservSSHTimeout(
                        "client was disconnected, reconnect timed out")
        finally:
            client_log.close()
            self.host.job.pop_execution_context()


    def execute_section(self, section, timeout, stderr_redirector,
                        client_disconnect_timeout, boot_id=None):
        # TODO(crbug.com/684311) The claim is that section is never more than 0
        # in pratice. After validating for a week or so, delete all support of
        # multiple sections.
        metrics.Counter('chromeos/autotest/autotest/sections').increment(
                fields={'is_first_section': (section == 0)})
        logging.info("Executing %s/bin/autotest %s/control phase %d",
                     self.autodir, self.autodir, section)

        if self.background:
            result = self._execute_in_background(section, timeout)
        else:
            result = self._execute_daemon(section, timeout, stderr_redirector,
                                          client_disconnect_timeout)

        last_line = stderr_redirector.last_line

        # check if we failed hard enough to warrant an exception
        if result.exit_status == 1:
            err = error.AutotestRunError("client job was aborted")
        elif not self.background and not result.stderr:
            err = error.AutotestRunError(
                "execute_section %s failed to return anything\n"
                "stdout:%s\n" % (section, result.stdout))
        else:
            err = None

        # log something if the client failed AND never finished logging
        if err and not self.is_client_job_finished(last_line):
            self.log_unexpected_abort(stderr_redirector, old_boot_id=boot_id)

        if err:
            raise err
        else:
            return stderr_redirector.last_line


    def _wait_for_reboot(self, old_boot_id):
        logging.info("Client is rebooting")
        logging.info("Waiting for client to halt")
        if not self.host.wait_down(self.host.WAIT_DOWN_REBOOT_TIMEOUT,
                                   old_boot_id=old_boot_id):
            err = "%s failed to shutdown after %d"
            err %= (self.host.hostname, self.host.WAIT_DOWN_REBOOT_TIMEOUT)
            raise error.AutotestRunError(err)
        logging.info("Client down, waiting for restart")
        if not self.host.wait_up(self.host.DEFAULT_REBOOT_TIMEOUT):
            # since reboot failed
            # hardreset the machine once if possible
            # before failing this control file
            warning = "%s did not come back up, hard resetting"
            warning %= self.host.hostname
            logging.warning(warning)
            try:
                self.host.hardreset(wait=False)
            except (AttributeError, error.AutoservUnsupportedError):
                warning = "Hard reset unsupported on %s"
                warning %= self.host.hostname
                logging.warning(warning)
            raise error.AutotestRunError("%s failed to boot after %ds" %
                                         (self.host.hostname,
                                          self.host.DEFAULT_REBOOT_TIMEOUT))
        self.host.reboot_followup()


    def execute_control(self, timeout=None, client_disconnect_timeout=None):
        if not self.background:
            collector = log_collector(self.host, self.tag, self.results_dir)
            hostname = self.host.hostname
            remote_results = collector.client_results_dir
            local_results = collector.server_results_dir
            self.host.job.add_client_log(hostname, remote_results,
                                         local_results)
            job_record_context = self.host.job.get_record_context()

        section = 0
        start_time = time.time()

        logger = client_logger(self.host, self.tag, self.results_dir)
        try:
            while not timeout or time.time() < start_time + timeout:
                if timeout:
                    section_timeout = start_time + timeout - time.time()
                else:
                    section_timeout = None
                boot_id = self.host.get_boot_id()
                last = self.execute_section(section, section_timeout,
                                            logger, client_disconnect_timeout,
                                            boot_id=boot_id)
                if self.background:
                    return
                section += 1
                if self.is_client_job_finished(last):
                    logging.info("Client complete")
                    return
                elif self.is_client_job_rebooting(last):
                    try:
                        self._wait_for_reboot(boot_id)
                    except error.AutotestRunError as e:
                        self.host.job.record("ABORT", None, "reboot", str(e))
                        self.host.job.record("END ABORT", None, None, str(e))
                        raise
                    continue

                # If a test fails without probable cause we try to bucket it's
                # failure into one of 2 categories. If we can determine the
                # current state of the device and it is suspicious, we close the
                # status lines indicating a failure. If we either cannot
                # determine the state of the device, or it appears totally
                # healthy, we give up and abort.
                try:
                    self._diagnose_dut(boot_id)
                except AutotestDeviceError as e:
                    # The status lines of the test are pretty much tailed to
                    # our log, with indentation, from the client job on the DUT.
                    # So if the DUT goes down unexpectedly we'll end up with a
                    # malformed status log unless we manually unwind the status
                    # stack. Ideally we would want to write a nice wrapper like
                    # server_job methods run_reboot, run_group but they expect
                    # reboots and we don't.
                    self.host.job.record('FAIL', None, None, str(e))
                    self.host.job.record('END FAIL', None, None)
                    self.host.job.record('END GOOD', None, None)
                    self.host.job.failed_with_device_error = True
                    return
                except AutotestAbort as e:
                    self.host.job.record('ABORT', None, None, str(e))
                    self.host.job.record('END ABORT', None, None)

                    # give the client machine a chance to recover from a crash
                    self.host.wait_up(
                        self.host.HOURS_TO_WAIT_FOR_RECOVERY * 3600)
                    logging.debug('Unexpected final status message from '
                                  'client %s: %s', self.host.hostname, last)
                    # The line 'last' may have sensitive phrases, like
                    # 'END GOOD', which breaks the tko parser. So the error
                    # message will exclude it, since it will be recorded to
                    # status.log.
                    msg = ("Aborting - unexpected final status message from "
                           "client on %s\n") % self.host.hostname
                    raise error.AutotestRunError(msg)
        finally:
            # B/203609358 someting is removing telemetry. Adding this to check the
            # status of the folder as late as possible.
            logging.debug('Autotest job finishes running. Below is the '
                          'post-processing operations.')
            logger.close()
            if not self.background:
                collector.collect_client_job_results()
                collector.remove_redundant_client_logs()
                state_file = os.path.basename(self.remote_control_file
                                              + '.state')
                state_path = os.path.join(self.results_dir, state_file)
                self.host.job.postprocess_client_state(state_path)
                self.host.job.remove_client_log(hostname, remote_results,
                                                local_results)
                job_record_context.restore()

            logging.debug('Autotest job finishes.')

        # should only get here if we timed out
        assert timeout
        raise error.AutotestTimeoutError()


class log_collector(object):
    def __init__(self, host, client_tag, results_dir):
        self.host = host
        if not client_tag:
            client_tag = "default"
        self.client_results_dir = os.path.join(host.get_autodir(), "results",
                                               client_tag)
        self.server_results_dir = results_dir


    def collect_client_job_results(self):
        """ A method that collects all the current results of a running
        client job into the results dir. By default does nothing as no
        client job is running, but when running a client job you can override
        this with something that will actually do something. """
        # make an effort to wait for the machine to come up
        try:
            self.host.wait_up(timeout=30)
        except error.AutoservError:
            # don't worry about any errors, we'll try and
            # get the results anyway
            pass

        # Copy all dirs in default to results_dir
        try:
            # Build test result directory summary
            result_tools_runner.run_on_client(
                    self.host, self.client_results_dir)

            with metrics.SecondsTimer(
                    'chromeos/autotest/job/log_collection_duration',
                    fields={'dut_host_name': self.host.hostname}):
                self.host.get_file(
                        self.client_results_dir + '/',
                        self.server_results_dir,
                        preserve_symlinks=True)
        except Exception:
            # well, don't stop running just because we couldn't get logs
            e_msg = "Unexpected error copying test result logs, continuing ..."
            logging.error(e_msg)
            traceback.print_exc(file=sys.stdout)


    def remove_redundant_client_logs(self):
        """Remove client.*.log files in favour of client.*.DEBUG files."""
        debug_dir = os.path.join(self.server_results_dir, 'debug')
        debug_files = [f for f in os.listdir(debug_dir)
                       if re.search(r'^client\.\d+\.DEBUG$', f)]
        for debug_file in debug_files:
            log_file = debug_file.replace('DEBUG', 'log')
            log_file = os.path.join(debug_dir, log_file)
            if os.path.exists(log_file):
                os.remove(log_file)


# a file-like object for catching stderr from an autotest client and
# extracting status logs from it
class client_logger(object):
    """Partial file object to write to both stdout and
    the status log file.  We only implement those methods
    utils.run() actually calls.
    """
    status_parser = re.compile(r"^AUTOTEST_STATUS:([^:]*):(.*)$")
    test_complete_parser = re.compile(r"^AUTOTEST_TEST_COMPLETE:(.*)$")
    fetch_package_parser = re.compile(
        r"^AUTOTEST_FETCH_PACKAGE:([^:]*):([^:]*):(.*)$")
    extract_indent = re.compile(r"^(\t*).*$")
    extract_timestamp = re.compile(r".*\ttimestamp=(\d+)\t.*$")

    def __init__(self, host, tag, server_results_dir):
        self.host = host
        self.job = host.job
        self.log_collector = log_collector(host, tag, server_results_dir)
        self.leftover = ""
        self.last_line = ""
        self.logs = {}


    def _process_log_dict(self, log_dict):
        log_list = log_dict.pop("logs", [])
        for key in sorted(six.iterkeys(log_dict)):
            log_list += self._process_log_dict(log_dict.pop(key))
        return log_list


    def _process_logs(self):
        """Go through the accumulated logs in self.log and print them
        out to stdout and the status log. Note that this processes
        logs in an ordering where:

        1) logs to different tags are never interleaved
        2) logs to x.y come before logs to x.y.z for all z
        3) logs to x.y come before x.z whenever y < z

        Note that this will in general not be the same as the
        chronological ordering of the logs. However, if a chronological
        ordering is desired that one can be reconstructed from the
        status log by looking at timestamp lines."""
        log_list = self._process_log_dict(self.logs)
        for entry in log_list:
            self.job.record_entry(entry, log_in_subdir=False)
        if log_list:
            self.last_line = log_list[-1].render()


    def _process_quoted_line(self, tag, line):
        """Process a line quoted with an AUTOTEST_STATUS flag. If the
        tag is blank then we want to push out all the data we've been
        building up in self.logs, and then the newest line. If the
        tag is not blank, then push the line into the logs for handling
        later."""
        entry = base_job.status_log_entry.parse(line)
        if entry is None:
            return  # the line contains no status lines
        if tag == "":
            self._process_logs()
            self.job.record_entry(entry, log_in_subdir=False)
            self.last_line = line
        else:
            tag_parts = [int(x) for x in tag.split(".")]
            log_dict = self.logs
            for part in tag_parts:
                log_dict = log_dict.setdefault(part, {})
            log_list = log_dict.setdefault("logs", [])
            log_list.append(entry)


    def _process_info_line(self, line):
        """Check if line is an INFO line, and if it is, interpret any control
        messages (e.g. enabling/disabling warnings) that it may contain."""
        match = re.search(r"^\t*INFO\t----\t----(.*)\t[^\t]*$", line)
        if not match:
            return   # not an INFO line
        for field in match.group(1).split('\t'):
            if field.startswith("warnings.enable="):
                func = self.job.warning_manager.enable_warnings
            elif field.startswith("warnings.disable="):
                func = self.job.warning_manager.disable_warnings
            else:
                continue
            warning_type = field.split("=", 1)[1]
            func(warning_type)


    def _process_line(self, line):
        """Write out a line of data to the appropriate stream.

        Returns the package checksum file if it exists.

        Status lines sent by autotest will be prepended with
        "AUTOTEST_STATUS", and all other lines are ssh error messages.
        """
        logging.debug(line)
        fetch_package_match = self.fetch_package_parser.search(line)
        if fetch_package_match:
            pkg_name, dest_path, fifo_path = fetch_package_match.groups()
            serve_packages = _CONFIG.get_config_value(
                "PACKAGES", "serve_packages_from_autoserv", type=bool)
            if serve_packages and pkg_name == 'packages.checksum':
                try:
                    checksum_file = os.path.join(
                        self.job.pkgmgr.pkgmgr_dir, 'packages', pkg_name)
                    if os.path.exists(checksum_file):
                        self.host.send_file(checksum_file, dest_path)
                except error.AutoservRunError:
                    msg = "Package checksum file not found, continuing anyway"
                    logging.exception(msg)

                try:
                    # When fetching a package, the client expects to be
                    # notified when the fetching is complete. Autotest
                    # does this pushing a B to a fifo queue to the client.
                    self.host.run("echo B > %s" % fifo_path)
                except error.AutoservRunError:
                    msg = "Checksum installation failed, continuing anyway"
                    logging.exception(msg)
                finally:
                    return

        status_match = self.status_parser.search(line)
        test_complete_match = self.test_complete_parser.search(line)
        fetch_package_match = self.fetch_package_parser.search(line)
        if status_match:
            tag, line = status_match.groups()
            self._process_info_line(line)
            self._process_quoted_line(tag, line)
        elif test_complete_match:
            self._process_logs()
            fifo_path, = test_complete_match.groups()
            try:
                self.log_collector.collect_client_job_results()
                self.host.run("echo A > %s" % fifo_path)
            except Exception:
                msg = "Post-test log collection failed, continuing anyway"
                logging.exception(msg)
        elif fetch_package_match:
            pkg_name, dest_path, fifo_path = fetch_package_match.groups()
            serve_packages = global_config.global_config.get_config_value(
                "PACKAGES", "serve_packages_from_autoserv", type=bool)
            if serve_packages and pkg_name.endswith(".tar.bz2"):
                try:
                    self._send_tarball(pkg_name, dest_path)
                except Exception:
                    msg = "Package tarball creation failed, continuing anyway"
                    logging.exception(msg)
            try:
                self.host.run("echo B > %s" % fifo_path)
            except Exception:
                msg = "Package tarball installation failed, continuing anyway"
                logging.exception(msg)
        else:
            logging.info(line)


    def _send_tarball(self, pkg_name, remote_dest):
        """Uses tarballs in package manager by default."""
        try:
            server_package = os.path.join(self.job.pkgmgr.pkgmgr_dir,
                                          'packages', pkg_name)
            if os.path.exists(server_package):
                self.host.send_file(server_package, remote_dest)
                return

        except error.AutoservRunError:
            msg = ("Package %s could not be sent from the package cache." %
                   pkg_name)
            logging.exception(msg)

        name, pkg_type = self.job.pkgmgr.parse_tarball_name(pkg_name)
        src_dirs = []
        if pkg_type == 'test':
            for test_dir in ['site_tests', 'tests']:
                src_dir = os.path.join(self.job.clientdir, test_dir, name)
                if os.path.exists(src_dir):
                    src_dirs += [src_dir]
                    break
        elif pkg_type == 'profiler':
            src_dirs += [os.path.join(self.job.clientdir, 'profilers', name)]
        elif pkg_type == 'dep':
            src_dirs += [os.path.join(self.job.clientdir, 'deps', name)]
        elif pkg_type == 'client':
            return  # you must already have a client to hit this anyway
        else:
            return  # no other types are supported

        # iterate over src_dirs until we find one that exists, then tar it
        for src_dir in src_dirs:
            if os.path.exists(src_dir):
                try:
                    logging.info('Bundling %s into %s', src_dir, pkg_name)
                    temp_dir = autotemp.tempdir(unique_id='autoserv-packager',
                                                dir=self.job.tmpdir)
                    tarball_path = self.job.pkgmgr.tar_package(
                        pkg_name, src_dir, temp_dir.name, " .")
                    self.host.send_file(tarball_path, remote_dest)
                finally:
                    temp_dir.clean()
                return


    def log_warning(self, msg, warning_type):
        """Injects a WARN message into the current status logging stream."""
        timestamp = int(time.time())
        if self.job.warning_manager.is_valid(timestamp, warning_type):
            self.job.record('WARN', None, None, msg)


    def write(self, data):
        # now start processing the existing buffer and the new data
        data = self.leftover + data
        lines = data.split('\n')
        processed_lines = 0
        try:
            # process all the buffered data except the last line
            # ignore the last line since we may not have all of it yet
            for line in lines[:-1]:
                self._process_line(line)
                processed_lines += 1
        finally:
            # save any unprocessed lines for future processing
            self.leftover = '\n'.join(lines[processed_lines:])


    def flush(self):
        sys.stdout.flush()


    def flush_all_buffers(self):
        if self.leftover:
            self._process_line(self.leftover)
            self.leftover = ""
        self._process_logs()
        self.flush()


    def close(self):
        self.flush_all_buffers()
