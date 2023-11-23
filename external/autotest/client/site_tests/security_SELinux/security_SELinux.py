# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os
import stat
import xattr

from autotest_lib.client.bin import test, utils
from autotest_lib.client.common_lib import error

def _get_process_context(pid=None):
    """Returns the SELinux context for a process."""
    if pid is None:
        pid = 'self'
    with open('/proc/{}/attr/current'.format(pid)) as f:
        return f.read().rstrip('\0')

def _get_file_label(path, nofollow=False):
    """Returns the SELinux label for a file."""
    return xattr.getxattr(path, 'security.selinux', nofollow).rstrip('\0')

def _assert_true(actual, msg='Unexpected false condition'):
    """Raises an error if the condition isn't true."""
    if not actual:
        raise error.TestFail(msg)

def _assert_false(actual, msg='Unexpected true condition'):
    """Raises an error if the condition isn't true."""
    if actual:
        raise error.TestFail(msg)

def _assert_prefix(expected_prefix, actual, msg='value'):
    """Raises an error if two values aren't equal."""
    if not actual.startswith(expected_prefix):
        raise error.TestFail('Unexpected {}: Expected prefix: {}, Actual: {}'
                             .format(msg, expected_prefix, actual))

def _assert_eq(expected, actual, msg='value'):
    """Raises an error if two values aren't equal."""
    if expected != actual:
        raise error.TestFail('Unexpected {}: Expected: {}, Actual: {}'
                             .format(msg, expected, actual))

def _assert_in(expected_set, actual, msg='value'):
    """Raises an error if a value is not contained in a set."""
    if actual not in expected_set:
        raise error.TestFail('Unexpected {}: Expected one of: {}, Actual: {}'
                             .format(msg, expected_set, actual))

def _check_file_labels_recursively(
    top_dir, expected, nofollow=True, relevant=None, check_top_dir=True,
    prefix_match=False):
    """Check if all files in |top_dir| have the expected label."""
    if check_top_dir:
        label = _get_file_label(top_dir, nofollow)
        if prefix_match:
          _assert_prefix(expected, label, 'label for %s' % top_dir)
        else:
          _assert_eq(expected, label, 'label for %s' % top_dir)
    for root, dirs, files in os.walk(top_dir):
        for name in dirs + files:
            path = os.path.join(root, name)
            if relevant is not None and not relevant(path):
              continue
            label = _get_file_label(path, nofollow)
            if prefix_match:
              _assert_prefix(expected, label, 'label for %s' % path)
            else:
              _assert_eq(expected, label, 'label for %s' % path)

def _log_writable_files(tree_to_check):
    """Logs all writable files in |tree_to_check|."""
    for root, _, files in os.walk(tree_to_check):
        for name in files:
            file_path = os.path.join(root, name)
            if os.lstat(file_path).st_mode & (
                stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH):
                logging.info('%s is writable', file_path)

def _log_files(tree_to_check):
    """Logs all files and directories in |tree_to_check|."""
    for root, dirs, files in os.walk(tree_to_check):
        for name in files:
            file_path = os.path.join(root, name)
            file_label = _get_file_label(file_path, nofollow=True)
            logging.info('%s has %s', file_path, file_label)
        for name in dirs:
            dir_path = os.path.join(root, name)
            dir_label = _get_file_label(dir_path, nofollow=True)
            logging.info('%s (directory) has %s', dir_path, dir_label)

def _get_drm_render_sys_devices():
    """Returns a list of DRM render nodes under /sys/devices."""
    trees = []
    drm_class_tree = '/sys/class/drm'
    drm_render_prefix = 'renderD'
    # Iterate over all devices in the class
    for entry in os.listdir(drm_class_tree):
        # Pick only render nodes
        if not entry.startswith(drm_render_prefix):
            continue

        entry_tree = os.path.join(drm_class_tree, entry)
        device_tree = os.path.join(entry_tree, 'device')
        device_link = os.readlink(device_tree)
        # The symlink seems to be relative, so we need to resolve it by
        # concatenating it to the base directory and getting realpath of
        # that.
        #
        # Note that even if the symlink is absolute, os.path.join()
        # would handle it correctly, as it detects absolute components
        # being joined.
        device_tree = os.path.join(entry_tree, device_link)
        real_device_tree = os.path.realpath(device_tree)
        trees.append(real_device_tree)
    return trees

CHROME_SSH_CONTEXT = 'u:r:cros_ssh_session:s0'

class security_SELinux(test.test):
    """Tests that various SELinux context and labels are correct. Note that
    tests of the browser and SELinux should go into autotest-chrome and tests
    of ARC++ and SELinux should go into cheets_SELinuxTest.
    """

    version = 1

    def _check_selinux_enforcing(self):
        """Test that SELinux is enforcing."""
        r = utils.run('getenforce',
                      stdout_tee=utils.TEE_TO_LOGS,
                      stderr_tee=utils.TEE_TO_LOGS,
                      ignore_status=True)
        if r.exit_status !=  0 or len(r.stderr) > 0:
            raise error.TestFail(r.stderr)
        _assert_eq('Enforcing', r.stdout.strip())

    def _check_test_context(self):
        """Test that this test is running under the cros_ssh_session context."""
        _assert_eq(CHROME_SSH_CONTEXT,
                   _get_process_context(),
                   'context for current process')

    def _check_init_label(self):
        """Test that /sbin/init is labeled chromeos_init_exec."""
        _assert_eq('u:object_r:chromeos_init_exec:s0',
                   _get_file_label('/sbin/init'),
                   'label for /sbin/init')

    def _check_cras_label(self):
        """Test that /run/cras directory is labeled properly."""
        _check_file_labels_recursively('/run/cras', 'u:object_r:cras_socket:s0')

    def _check_sys_devices_system_cpu_labels(self):
        """Test that files in /sys/devices/system/cpu are labeled properly."""
        sys_tree_to_check = '/sys/devices/system/cpu'

        def is_writable_file(path):
            """Checks if path is writable."""
            st = os.lstat(path)
            if not stat.S_ISREG(st.st_mode):
                return False  # not a file
            return st.st_mode & (stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH)

        try:
            _check_file_labels_recursively(
                sys_tree_to_check,
                'u:object_r:sysfs_devices_system_cpu:s0',
                relevant=lambda f: not is_writable_file(f))
            # Writable files must have 'sysfs' label (b/34814534)
            _check_file_labels_recursively(sys_tree_to_check,
                                           'u:object_r:sysfs:s0',
                                           relevant=is_writable_file,
                                           check_top_dir=False)
        except:
            _log_writable_files(sys_tree_to_check)
            raise

    def _check_sys_fs_cgroup_labels(self):
        """Test that files in /sys/fs/cgroup are labeled properly."""
        sys_tree_to_check = '/sys/fs/cgroup'
        # First, check the label for the root of the tree. |sys_tree_to_check|
        # is actually a tmpfs mounted on the directory.
        file_label = _get_file_label(sys_tree_to_check)
        _assert_eq('u:object_r:tmpfs:s0',
                   file_label, 'label for %s' % sys_tree_to_check)
        # Then, check each file and directory in the tree.
        try:
            _check_file_labels_recursively(sys_tree_to_check,
                                           'u:object_r:cgroup:s0',
                                           check_top_dir=False)
        except:
            _log_files(sys_tree_to_check)
            raise

    def _check_sys_fs_pstore_labels(self):
        """Test that files in /sys/fs/pstore are labeled properly."""
        sys_tree_to_check = '/sys/fs/pstore'
        # First, check the label for the root of the tree.
        file_label = _get_file_label(sys_tree_to_check)
        _assert_eq('u:object_r:pstorefs:s0',
                   file_label, 'label for %s' % sys_tree_to_check)

    def _check_sys_fs_selinux_labels(self):
        """Test that files in /sys/fs/selinux are labeled properly."""
        sys_tree_to_check = '/sys/fs/selinux'

        def is_null_file(path):
            """SELinux filesystem has an analog of /dev/null that we want to
            ignore.
            """
            return path == os.path.join(sys_tree_to_check, 'null')

        try:
            _check_file_labels_recursively(
                sys_tree_to_check,
                'u:object_r:selinuxfs:s0',
                relevant=lambda f: not is_null_file(f))
            _check_file_labels_recursively(sys_tree_to_check,
                                           'u:object_r:null_device:s0',
                                           relevant=is_null_file,
                                           check_top_dir=False)
        except:
            _log_files(sys_tree_to_check)
            raise

    def _check_sys_kernel_config_labels(self):
        """Test that files in /sys/kernel/config are labeled properly."""
        sys_tree_to_check = '/sys/kernel/config'
        if not os.path.exists(sys_tree_to_check):
            return
        # First, check the label for the root of the tree.
        file_label = _get_file_label(sys_tree_to_check)
        _assert_eq('u:object_r:configfs:s0',
                   file_label, 'label for %s' % sys_tree_to_check)

    def _check_sys_kernel_debug_labels(self):
        """Test /sys/kernel/debug labels."""
        def _check_labels(relative_path, expected_label):
            absolute_path = os.path.join('/', relative_path)
            live_label = _get_file_label(absolute_path)
            _assert_eq(expected_label, live_label,
                       "context for Host's %s" % absolute_path)

        # Check debugfs
        _check_labels('sys/kernel/debug',
                      'u:object_r:debugfs:s0')
        # Check some debugfs/tracing files
        for debugfs_file in ['tracing', 'tracing/tracing_on']:
            _check_labels('sys/kernel/debug/%s' % debugfs_file,
                          'u:object_r:debugfs_tracing:s0')

        # Check debugfs/tracing/trace_marker
        _check_labels('sys/kernel/debug/tracing/trace_marker',
                      'u:object_r:debugfs_trace_marker:s0')

    def _check_wayland_sock_label(self):
        """Test that the Wayland socket is labeled properly."""
        label = _get_file_label('/run/chrome/wayland-0')
        _assert_eq('u:object_r:wayland_socket:s0', label,
                   'label for Wayland socket')

    def run_once(self):
        """All the tests in this unit are run from here."""
        # Check if SELinux is enforced on the DUT.
        self._check_selinux_enforcing()
        # Check process contexts.
        self._check_test_context()
        # Check files.
        self._check_init_label()
        # Check container-side files from the init mount namespace.
        self._check_cras_label()
        self._check_sys_devices_system_cpu_labels()
        self._check_sys_fs_cgroup_labels()
        self._check_sys_fs_pstore_labels()
        self._check_sys_fs_selinux_labels()
        self._check_sys_kernel_config_labels()
        self._check_sys_kernel_debug_labels()
        self._check_wayland_sock_label()
