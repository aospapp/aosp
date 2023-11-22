#!/usr/bin/python
# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import argparse
import logging
import os
import tempfile
import shutil
import sys
import unittest
from contextlib import contextmanager

import common
from autotest_lib.client.bin import utils
from autotest_lib.site_utils import lxc
from autotest_lib.site_utils.lxc import constants
from autotest_lib.site_utils.lxc import unittest_http
from autotest_lib.site_utils.lxc import unittest_logging
from autotest_lib.site_utils.lxc import utils as lxc_utils
from autotest_lib.site_utils.lxc.unittest_container_bucket \
        import FastContainerBucket


options = None

@unittest.skipIf(lxc.IS_MOBLAB, 'Zygotes are not supported on moblab.')
class ZygoteTests(unittest.TestCase):
    """Unit tests for the Zygote class."""

    @classmethod
    def setUpClass(cls):
        cls.test_dir = tempfile.mkdtemp(dir=lxc.DEFAULT_CONTAINER_PATH,
                                        prefix='zygote_unittest_')
        cls.shared_host_path = os.path.join(cls.test_dir, 'host')

        # Use a container bucket just to download and set up the base image.
        cls.bucket = FastContainerBucket(cls.test_dir, cls.shared_host_path)

        if cls.bucket.base_container is None:
            logging.debug('Base container not found - reinitializing')
            cls.bucket.setup_base()

        cls.base_container = cls.bucket.base_container
        assert(cls.base_container is not None)


    @classmethod
    def tearDownClass(cls):
        cls.base_container = None
        if not options.skip_cleanup:
            cls.bucket.destroy_all()
            shutil.rmtree(cls.test_dir)

    def tearDown(self):
        # Ensure host dirs from each test are completely destroyed.
        for host_dir in os.listdir(self.shared_host_path):
            host_dir = os.path.realpath(os.path.join(self.shared_host_path,
                                                     host_dir))
            lxc_utils.cleanup_host_mount(host_dir);


    def testCleanup(self):
        """Verifies that the zygote cleans up after itself."""
        with self.createZygote() as zygote:
            host_path = zygote.host_path

            self.assertTrue(os.path.isdir(host_path))

            # Start/stop the zygote to exercise the host mounts.
            zygote.start(wait_for_network=False)
            zygote.stop()

        # After the zygote is destroyed, verify that the host path is cleaned
        # up.
        self.assertFalse(os.path.isdir(host_path))


    def testCleanupWithUnboundHostDir(self):
        """Verifies that cleanup works when the host dir is unbound."""
        with self.createZygote() as zygote:
            host_path = zygote.host_path

            self.assertTrue(os.path.isdir(host_path))
            # Don't start the zygote, so the host mount is not bound.

        # After the zygote is destroyed, verify that the host path is cleaned
        # up.
        self.assertFalse(os.path.isdir(host_path))


    def testCleanupWithNoHostDir(self):
        """Verifies that cleanup works when the host dir is missing."""
        with self.createZygote() as zygote:
            host_path = zygote.host_path

            utils.run('sudo rmdir %s' % zygote.host_path)
            self.assertFalse(os.path.isdir(host_path))
        # Zygote destruction should yield no errors if the host path is
        # missing.


    def testSetHostnameRunning(self):
        """Verifies that the hostname can be set on a running container."""
        with self.createZygote() as zygote:
            expected_hostname = 'my-new-hostname'
            zygote.start(wait_for_network=True)
            zygote.set_hostname(expected_hostname)
            hostname = zygote.attach_run('hostname -f').stdout.strip()
            self.assertEqual(expected_hostname, hostname)


    def testHostDir(self):
        """Verifies that the host dir on the container is created, and correctly
        bind-mounted."""
        with self.createZygote() as zygote:
            self.assertIsNotNone(zygote.host_path)
            self.assertTrue(os.path.isdir(zygote.host_path))

            zygote.start(wait_for_network=False)

            self.verifyBindMount(
                zygote,
                container_path=lxc.CONTAINER_AUTOTEST_DIR,
                host_path=zygote.host_path)


    def testHostDirExists(self):
        """Verifies that the host dir is just mounted if it already exists."""
        # Pre-create the host dir and put a file in it.
        test_host_path = os.path.join(self.shared_host_path,
                                      'testHostDirExists')
        test_filename = 'test_file'
        test_host_file = os.path.join(test_host_path, test_filename)
        test_string = 'jackdaws love my big sphinx of quartz.'
        os.mkdir(test_host_path)
        with open(test_host_file, 'w+') as f:
            f.write(test_string)

        # Sanity check
        self.assertTrue(lxc_utils.path_exists(test_host_file))

        with self.createZygote(host_path=test_host_path) as zygote:
            zygote.start(wait_for_network=False)

            self.verifyBindMount(
                zygote,
                container_path=lxc.CONTAINER_AUTOTEST_DIR,
                host_path=zygote.host_path)

            # Verify that the old directory contents was preserved.
            cmd = 'cat %s' % os.path.join(lxc.CONTAINER_AUTOTEST_DIR,
                                          test_filename)
            test_output = zygote.attach_run(cmd).stdout.strip()
            self.assertEqual(test_string, test_output)


    def testInstallSsp(self):
        """Verifies that installing the ssp in the container works."""
        # Hard-coded path to some golden data for this test.
        test_ssp = os.path.join(
                common.autotest_dir,
                'site_utils', 'lxc', 'test', 'test_ssp.tar.bz2')
        # Create a container, install the self-served ssp, then check that it is
        # installed into the container correctly.
        with self.createZygote() as zygote:
            # Note: start the zygote first, then install the SSP.  This mimics
            # the way things would work in the production environment.
            zygote.start(wait_for_network=False)
            with unittest_http.serve_locally(test_ssp) as url:
                zygote.install_ssp(url)

            # The test ssp just contains a couple of text files, in known
            # locations.  Verify the location and content of those files in the
            # container.
            cat = lambda path: zygote.attach_run('cat %s' % path).stdout
            test0 = cat(os.path.join(constants.CONTAINER_AUTOTEST_DIR,
                                     'test.0'))
            test1 = cat(os.path.join(constants.CONTAINER_AUTOTEST_DIR,
                                     'dir0', 'test.1'))
            self.assertEquals('the five boxing wizards jumped quickly',
                              test0)
            self.assertEquals('the quick brown fox jumps over the lazy dog',
                              test1)


    def testInstallControlFile(self):
        """Verifies that installing a control file in the container works."""
        _unused, tmpfile = tempfile.mkstemp()
        with self.createZygote() as zygote:
            # Note: start the zygote first.  This mimics the way things would
            # work in the production environment.
            zygote.start(wait_for_network=False)
            zygote.install_control_file(tmpfile)
            # Verify that the file is found in the zygote.
            zygote.attach_run(
                'test -f %s' % os.path.join(lxc.CONTROL_TEMP_PATH,
                                            os.path.basename(tmpfile)))


    @contextmanager
    def createZygote(self,
                     name = None,
                     attribute_values = None,
                     snapshot = True,
                     host_path = None):
        """Clones a zygote from the test base container.
        Use this to ensure that zygotes got properly cleaned up after each test.

        @param container_path: The LXC path for the new container.
        @param host_path: The host path for the new container.
        @param name: The name of the new container.
        @param attribute_values: Any attribute values for the new container.
        @param snapshot: Whether to create a snapshot clone.
        """
        if name is None:
            name = self.id().split('.')[-1]
        if host_path is None:
            host_path = os.path.join(self.shared_host_path, name)
        if attribute_values is None:
            attribute_values = {}
        zygote = lxc.Zygote(self.test_dir,
                            name,
                            attribute_values,
                            self.base_container,
                            snapshot,
                            host_path)
        try:
            yield zygote
        finally:
            if not options.skip_cleanup:
                zygote.destroy()


    def verifyBindMount(self, container, container_path, host_path):
        """Verifies that a given path in a container is bind-mounted to a given
        path in the host system.

        @param container: The Container instance to be tested.
        @param container_path: The path in the container to compare.
        @param host_path: The path in the host system to compare.
        """
        container_inode = (container.attach_run('ls -id %s' % container_path)
                           .stdout.split()[0])
        host_inode = utils.run('ls -id %s' % host_path).stdout.split()[0]
        # Compare the container and host inodes - they should match.
        self.assertEqual(container_inode, host_inode)


def parse_options():
    """Parse command line inputs.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument('-v', '--verbose', action='store_true',
                        help='Print out ALL entries.')
    parser.add_argument('--skip_cleanup', action='store_true',
                        help='Skip deleting test containers.')
    args, argv = parser.parse_known_args()

    # Hack: python unittest also processes args.  Construct an argv to pass to
    # it, that filters out the options it won't recognize.
    if args.verbose:
        argv.insert(0, '-v')
    argv.insert(0, sys.argv[0])

    return args, argv


if __name__ == '__main__':
    options, unittest_argv = parse_options()

    log_level=(logging.DEBUG if options.verbose else logging.INFO)
    unittest_logging.setup(log_level)

    unittest.main(argv=unittest_argv)
