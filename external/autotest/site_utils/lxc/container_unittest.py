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
from autotest_lib.client.common_lib import error
from autotest_lib.site_utils import lxc
from autotest_lib.site_utils.lxc import constants
from autotest_lib.site_utils.lxc import unittest_http
from autotest_lib.site_utils.lxc import unittest_logging
from autotest_lib.site_utils.lxc import utils as lxc_utils
from autotest_lib.site_utils.lxc.unittest_container_bucket \
        import FastContainerBucket

options = None

class ContainerTests(unittest.TestCase):
    """Unit tests for the Container class."""

    @classmethod
    def setUpClass(cls):
        cls.test_dir = tempfile.mkdtemp(dir=lxc.DEFAULT_CONTAINER_PATH,
                                        prefix='container_unittest_')
        cls.shared_host_path = os.path.join(cls.test_dir, 'host')

        # Use a container bucket just to download and set up the base image.
        cls.bucket = FastContainerBucket(cls.test_dir, cls.shared_host_path)

        if cls.bucket.base_container is None:
            logging.debug('Base container not found - reinitializing')
            cls.bucket.setup_base()
        else:
            logging.debug('base container found')
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


    def testInit(self):
        """Verifies that containers initialize correctly."""
        # Make a container that just points to the base container.
        container = lxc.Container.createFromExistingDir(
            self.base_container.container_path,
            self.base_container.name)
        self.assertFalse(container.is_running())


    def testInitInvalid(self):
        """Verifies that invalid containers can still be instantiated,
        if not used.
        """
        with tempfile.NamedTemporaryFile(dir=self.test_dir) as tmpfile:
            name = os.path.basename(tmpfile.name)
            container = lxc.Container.createFromExistingDir(self.test_dir, name)
            with self.assertRaises(error.ContainerError):
                container.refresh_status()


    def testDefaultHostname(self):
        """Verifies that the zygote starts up with a default hostname that is
        the lxc container name."""
        test_name = 'testHostname'
        with self.createContainer(name=test_name) as container:
            container.start(wait_for_network=True)
            hostname = container.attach_run('hostname').stdout.strip()
            self.assertEqual(test_name, hostname)


    @unittest.skip('Setting the container hostname using lxc.utsname does not'
                   'work on goobuntu.')
    def testSetHostnameNotRunning(self):
        """Verifies that the hostname can be set on a stopped container."""
        with self.createContainer() as container:
            expected_hostname = 'my-new-hostname'
            container.set_hostname(expected_hostname)
            container.start(wait_for_network=True)
            hostname = container.attach_run('hostname').stdout.strip()
            self.assertEqual(expected_hostname, hostname)


    def testClone(self):
        """Verifies that cloning a container works as expected."""
        clone = lxc.Container.clone(src=self.base_container,
                                    new_name="testClone",
                                    snapshot=True)
        try:
            # Throws an exception if the container is not valid.
            clone.refresh_status()
        finally:
            clone.destroy()


    def testCloneWithoutCleanup(self):
        """Verifies that cloning a container to an existing name will fail as
        expected.
        """
        lxc.Container.clone(src=self.base_container,
                            new_name="testCloneWithoutCleanup",
                            snapshot=True)
        with self.assertRaises(error.ContainerError):
            lxc.Container.clone(src=self.base_container,
                                new_name="testCloneWithoutCleanup",
                                snapshot=True)


    def testCloneWithCleanup(self):
        """Verifies that cloning a container with cleanup works properly."""
        clone0 = lxc.Container.clone(src=self.base_container,
                                     new_name="testClone",
                                     snapshot=True)
        clone0.start(wait_for_network=False)
        tmpfile = clone0.attach_run('mktemp').stdout
        # Verify that our tmpfile exists
        clone0.attach_run('test -f %s' % tmpfile)

        # Clone another container in place of the existing container.
        clone1 = lxc.Container.clone(src=self.base_container,
                                     new_name="testClone",
                                     snapshot=True,
                                     cleanup=True)
        with self.assertRaises(error.CmdError):
            clone1.attach_run('test -f %s' % tmpfile)


    def testInstallSsp(self):
        """Verifies that installing the ssp in the container works."""
        # Hard-coded path to some golden data for this test.
        test_ssp = os.path.join(
                common.autotest_dir,
                'site_utils', 'lxc', 'test', 'test_ssp.tar.bz2')
        # Create a container, install the self-served ssp, then check that it is
        # installed into the container correctly.
        with self.createContainer() as container:
            with unittest_http.serve_locally(test_ssp) as url:
                container.install_ssp(url)
            container.start(wait_for_network=False)

            # The test ssp just contains a couple of text files, in known
            # locations.  Verify the location and content of those files in the
            # container.
            cat = lambda path: container.attach_run('cat %s' % path).stdout
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
        with self.createContainer() as container:
            container.install_control_file(tmpfile)
            container.start(wait_for_network=False)
            # Verify that the file is found in the container.
            container.attach_run(
                'test -f %s' % os.path.join(lxc.CONTROL_TEMP_PATH,
                                            os.path.basename(tmpfile)))


    @contextmanager
    def createContainer(self, name=None):
        """Creates a container from the base container, for testing.
        Use this to ensure that containers get properly cleaned up after each
        test.

        @param name: An optional name for the new container.
        """
        if name is None:
            name = self.id().split('.')[-1]
        container = self.bucket.create_from_base(name)
        try:
            yield container
        finally:
            container.destroy()


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
