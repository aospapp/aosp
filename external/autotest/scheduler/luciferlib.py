# Copyright 2017 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Library providing an API to lucifer."""

import os
import logging
import pipes
import socket
import subprocess

import common
from autotest_lib.client.bin import local_host
from autotest_lib.client.common_lib import global_config
from autotest_lib.scheduler.drone_manager import PidfileId
from autotest_lib.server.hosts import ssh_host
from autotest_lib.frontend.afe import models

_config = global_config.global_config
_SECTION = 'LUCIFER'

# TODO(crbug.com/748234): Move these to shadow_config.ini
# See also drones.AUTOTEST_INSTALL_DIR
_ENV = '/usr/bin/env'
_AUTOTEST_DIR = '/usr/local/autotest'
_JOB_REPORTER_PATH = os.path.join(_AUTOTEST_DIR, 'bin', 'job_reporter')

logger = logging.getLogger(__name__)


def is_lucifer_enabled():
    """Return True if lucifer is enabled in the config."""
    return True


def is_enabled_for(level):
    """Return True if lucifer is enabled for the given level.

    @param level: string, e.g. 'PARSING', 'GATHERING'
    """
    if not is_lucifer_enabled():
        return False
    config_level = (_config.get_config_value(_SECTION, 'lucifer_level')
                    .upper())
    return level.upper() == config_level


def is_lucifer_owned(job):
    """Return True if job is already sent to lucifer.

    @param job: frontend.afe.models.Job instance
    """
    assert isinstance(job, models.Job)
    return hasattr(job, 'jobhandoff')


def is_lucifer_owned_by_id(job_id):
    """Return True if job is already sent to lucifer."""
    return models.JobHandoff.objects.filter(job_id=job_id).exists()


def is_split_job(hqe_id):
    """Return True if HQE is part of a job with HQEs in a different group.

    For examples if the given HQE have execution_subdir=foo and the job
    has an HQE with execution_subdir=bar, then return True.  The only
    situation where this happens is if provisioning in a multi-DUT job
    fails, the HQEs will each be in their own group.

    See https://bugs.chromium.org/p/chromium/issues/detail?id=811877

    @param hqe_id: HQE id
    """
    hqe = models.HostQueueEntry.objects.get(id=hqe_id)
    hqes = hqe.job.hostqueueentry_set.all()
    try:
        _get_consistent_execution_path(hqes)
    except ExecutionPathError:
        return True
    return False


# TODO(crbug.com/748234): This is temporary to enable toggling
# lucifer rollouts with an option.
def spawn_starting_job_handler(manager, job):
    """Spawn job_reporter to handle a job.

    Pass all arguments by keyword.

    @param manager: scheduler.drone_manager.DroneManager instance
    @param job: Job instance
    @returns: Drone instance
    """
    manager = _DroneManager(manager)
    drone = manager.pick_drone_to_use()
    results_dir = _results_dir(manager, job)
    args = [
            _JOB_REPORTER_PATH,

            # General configuration
            '--jobdir', _get_jobdir(),
            '--lucifer-path', _get_lucifer_path(),

            # Job specific
            '--lucifer-level', 'STARTING',
            '--job-id', str(job.id),
            '--results-dir', results_dir,

            # STARTING specific
            '--execution-tag', _working_directory(job),
    ]
    if _get_gcp_creds():
        args = [
                'GOOGLE_APPLICATION_CREDENTIALS=%s'
                % pipes.quote(_get_gcp_creds()),
        ] + args
    drone.spawn(_ENV, args,
                output_file=_prepare_output_file(drone, results_dir))
    drone.add_active_processes(1)
    manager.reorder_drone_queue()
    manager.register_pidfile_processes(
            os.path.join(results_dir, '.autoserv_execute'), 1)
    return drone


# TODO(crbug.com/748234): This is temporary to enable toggling
# lucifer rollouts with an option.
def spawn_parsing_job_handler(manager, job, autoserv_exit, pidfile_id=None):
    """Spawn job_reporter to handle a job.

    Pass all arguments by keyword.

    @param manager: scheduler.drone_manager.DroneManager instance
    @param job: Job instance
    @param autoserv_exit: autoserv exit status
    @param pidfile_id: PidfileId instance
    @returns: Drone instance
    """
    manager = _DroneManager(manager)
    if pidfile_id is None:
        drone = manager.pick_drone_to_use()
    else:
        drone = manager.get_drone_for_pidfile(pidfile_id)
    results_dir = _results_dir(manager, job)
    args = [
            _JOB_REPORTER_PATH,

            # General configuration
            '--jobdir', _get_jobdir(),
            '--lucifer-path', _get_lucifer_path(),

            # Job specific
            '--job-id', str(job.id),
            '--lucifer-level', 'STARTING',
            '--parsing-only',
            '--results-dir', results_dir,
    ]
    if _get_gcp_creds():
        args = [
                'GOOGLE_APPLICATION_CREDENTIALS=%s'
                % pipes.quote(_get_gcp_creds()),
        ] + args
    drone.spawn(_ENV, args,
                output_file=_prepare_output_file(drone, results_dir))
    drone.add_active_processes(1)
    manager.reorder_drone_queue()
    manager.register_pidfile_processes(
            os.path.join(results_dir, '.autoserv_execute'), 1)
    return drone


_LUCIFER_DIR = 'lucifer'


def _prepare_output_file(drone, results_dir):
    logdir = os.path.join(results_dir, _LUCIFER_DIR)
    drone.run('mkdir', ['-p', logdir])
    return os.path.join(logdir, 'job_reporter_output.log')


def _get_jobdir():
    return _config.get_config_value(_SECTION, 'jobdir')


def _get_lucifer_path():
    return os.path.join(_get_binaries_path(), 'lucifer')


def _get_binaries_path():
    """Get binaries dir path from config.."""
    return _config.get_config_value(_SECTION, 'binaries_path')


def _get_gcp_creds():
    """Return path to GCP service account credentials.

    This is the empty string by default, if no credentials will be used.
    """
    return _config.get_config_value(_SECTION, 'gcp_creds', default='')


class _DroneManager(object):
    """Simplified drone API."""

    def __init__(self, old_manager):
        """Initialize instance.

        @param old_manager: old style DroneManager
        """
        self._manager = old_manager

    def get_num_tests_failed(self, pidfile_id):
        """Return the number of tests failed for autoserv by pidfile.

        @param pidfile_id: PidfileId instance.
        @returns: int (-1 if missing)
        """
        state = self._manager.get_pidfile_contents(pidfile_id)
        if state.num_tests_failed is None:
            return -1
        return state.num_tests_failed

    def get_drone_for_pidfile(self, pidfile_id):
        """Return a drone to use from a pidfile.

        @param pidfile_id: PidfileId instance.
        """
        return _wrap_drone(self._manager.get_drone_for_pidfile_id(pidfile_id))

    def pick_drone_to_use(self, num_processes=1):
        """Return a drone to use.

        Various options can be passed to optimize drone selection.

        @param num_processes: number of processes the drone is intended
            to run
        """
        old_drone = self._manager.pick_drone_to_use(
                num_processes=num_processes,
        )
        return _wrap_drone(old_drone)

    def absolute_path(self, path):
        """Return absolute path for drone results.

        The returned path might be remote.
        """
        return self._manager.absolute_path(path)

    def register_pidfile_processes(self, path, count):
        """Register a pidfile with the given number of processes.

        This should be done to allow the drone manager to check the
        number of processes still alive.  This may be used to select
        drones based on the number of active processes as a proxy for
        load.

        The exact semantics depends on the drone manager implementation;
        implementation specific comments follow:

        Pidfiles are kept in memory to track process count.  Pidfiles
        are rediscovered when the scheduler restarts.  Thus, errors in
        pidfile tracking can be fixed by restarting the scheduler.xo
        """
        pidfile_id = PidfileId(path)
        self._manager.register_pidfile(pidfile_id)
        self._manager._registered_pidfile_info[pidfile_id].num_processes = count

    def reorder_drone_queue(self):
        """Reorder drone queue according to modified process counts.

        Call this after Drone.add_active_processes().
        """
        self._manager.reorder_drone_queue()


def _wrap_drone(old_drone):
    """Wrap an old style drone."""
    host = old_drone._host
    if isinstance(host, local_host.LocalHost):
        return LocalDrone()
    elif isinstance(host, ssh_host.SSHHost):
        return RemoteDrone(old_drone)
    else:
        raise TypeError('Drone has an unknown host type')


def _results_dir(manager, job):
    """Return results dir for a job.

    Path may be on a remote host.
    """
    return manager.absolute_path(_working_directory(job))


def _working_directory(job):
    return _get_consistent_execution_path(job.hostqueueentry_set.all())


def _get_consistent_execution_path(execution_entries):
    first_execution_path = execution_entries[0].execution_path()
    for execution_entry in execution_entries[1:]:
        if execution_entry.execution_path() != first_execution_path:
            raise ExecutionPathError(
                    '%s (%s) != %s (%s)'
                    % (execution_entry.execution_path(),
                       execution_entry,
                       first_execution_path,
                       execution_entries[0]))
    return first_execution_path


class ExecutionPathError(Exception):
    """Raised by _get_consistent_execution_path()."""


class Drone(object):
    """Simplified drone API."""

    def hostname(self):
        """Return the hostname of the drone."""

    def run(self, path, args):
        """Run a command synchronously.

        path must be an absolute path.  path may be on a remote machine.
        args is a list of arguments.

        The process may or may not have its own session.  The process
        should be short-lived.  It should not try to obtain a
        controlling terminal.

        The new process will have stdin, stdout, and stderr opened to
        /dev/null.

        This method intentionally has a very restrictive API.  It should
        be used to perform setup local to the drone, when the drone may
        be a remote machine.
        """

    def spawn(self, path, args, output_file):
        """Spawn an independent process.

        path must be an absolute path.  path may be on a remote machine.
        args is a list of arguments.

        The process is spawned in its own session.  It should not try to
        obtain a controlling terminal.

        The new process will have stdin opened to /dev/null and stdout,
        stderr opened to output_file.

        output_file is a pathname, but how it is interpreted is
        implementation defined, e.g., it may be a remote file.
        """

    def add_active_processes(self, count):
        """Track additional number of active processes.

        This may be used to select drones based on the number of active
        processes as a proxy for load.

        _DroneManager.register_pidfile_processes() and
        _DroneManager.reorder_drone_queue() should also be called.

        The exact semantics depends on the drone manager implementation;
        implementation specific comments follow:

        Process count is used as a proxy for workload, and one process
        equals the workload of one autoserv or one job.  This count is
        recalculated during each scheduler tick, using pidfiles tracked
        by the drone manager (so the count added by this function only
        applies for one tick).
        """


class LocalDrone(Drone):
    """Local implementation of Drone."""

    def hostname(self):
        return socket.gethostname()

    def run(self, path, args):
        with open(os.devnull, 'r+b') as null:
            subprocess.call([path] + args, stdin=null,
                            stdout=null, stderr=null)

    def spawn(self, path, args, output_file):
        _spawn(path, [path] + args, output_file)


class RemoteDrone(Drone):
    """Remote implementation of Drone through SSH."""

    def __init__(self, drone):
        host = drone._host
        if not isinstance(host, ssh_host.SSHHost):
            raise TypeError('RemoteDrone must be passed a drone with SSHHost')
        self._drone = drone
        self._host = drone._host

    def hostname(self):
        return self._host.hostname

    def run(self, path, args):
        cmd_parts = [path] + args
        safe_cmd = ' '.join(pipes.quote(part) for part in cmd_parts)
        self._host.run('%(cmd)s <%(null)s >%(null)s 2>&1'
                       % {'cmd': safe_cmd, 'null': os.devnull})

    def spawn(self, path, args, output_file):
        cmd_parts = [path] + args
        safe_cmd = ' '.join(pipes.quote(part) for part in cmd_parts)
        safe_file = pipes.quote(output_file)
        # SSH creates a session for each command, so we do not have to
        # do it.
        self._host.run('%(cmd)s <%(null)s >>%(file)s 2>&1 &'
                       % {'cmd': safe_cmd,
                          'file': safe_file,
                          'null': os.devnull})

    def add_active_processes(self, count):
        self._drone.active_processes += count


def _spawn(path, argv, output_file):
    """Spawn a new process in its own session.

    path must be an absolute path.  The first item in argv should be
    path.

    In the calling process, this function returns on success.
    The forked process puts itself in its own session and execs.

    The new process will have stdin opened to /dev/null and stdout,
    stderr opened to output_file.
    """
    logger.info('Spawning %r, %r, %r', path, argv, output_file)
    assert all(isinstance(arg, basestring) for arg in argv)
    pid = os.fork()
    if pid:
        os.waitpid(pid, 0)
        return
    # Double fork to reparent to init since monitor_db does not reap.
    if os.fork():
        os._exit(os.EX_OK)
    os.setsid()
    null_fd = os.open(os.devnull, os.O_RDONLY)
    os.dup2(null_fd, 0)
    os.close(null_fd)
    out_fd = os.open(output_file, os.O_WRONLY | os.O_APPEND | os.O_CREAT)
    os.dup2(out_fd, 1)
    os.dup2(out_fd, 2)
    os.close(out_fd)
    os.execv(path, argv)
