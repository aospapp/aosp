#!/usr/bin/python
# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import argparse
import logging
import os
import shutil
import tempfile
import unittest

import common
from autotest_lib.site_utils import lxc
from autotest_lib.site_utils.lxc import unittest_logging


options = None
container_path = None

def setUpModule():
    """Creates a directory for running the unit tests. """
    global container_path
    container_path = tempfile.mkdtemp(
            dir=lxc.DEFAULT_CONTAINER_PATH,
            prefix='container_bucket_unittest_')


def tearDownModule():
    """Deletes the test directory. """
    shutil.rmtree(container_path)


class ContainerBucketTests(unittest.TestCase):
    """Unit tests for the ContainerBucket class."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.shared_host_path = os.path.realpath(os.path.join(self.tmpdir,
                                                              'host'))


    def tearDown(self):
        shutil.rmtree(self.tmpdir)


    def testHostDirCreationAndCleanup(self):
        """Verifies that the host dir is properly created and cleaned up when
        the container bucket is set up and destroyed.
        """
        bucket = lxc.ContainerBucket(container_path, self.shared_host_path)

        # Verify the host path in the container bucket.
        self.assertEqual(os.path.realpath(bucket.shared_host_path),
                         self.shared_host_path)

        # Set up, verify that the path is created.
        bucket.setup_base()
        self.assertTrue(os.path.isdir(self.shared_host_path))

        # Clean up, verify that the path is removed.
        bucket.destroy_all()
        self.assertFalse(os.path.isdir(self.shared_host_path))


    def testHostDirMissing(self):
        """Verifies that a missing host dir does not cause container bucket
        destruction to crash.
        """
        bucket = lxc.ContainerBucket(container_path, self.shared_host_path)

        # Verify that the host path does not exist.
        self.assertFalse(os.path.exists(self.shared_host_path))
        # Do not call startup, just call destroy.  This should not throw.
        bucket.destroy_all()


class ContainerBucketSetupBaseTests(unittest.TestCase):
    """Unit tests to verify the ContainerBucket setup_base method."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.shared_host_path = os.path.realpath(os.path.join(self.tmpdir,
                                                              'host'))
        self.bucket = lxc.ContainerBucket(container_path,
                                          self.shared_host_path)


    def tearDown(self):
        for container in self.bucket.get_all().values():
            container.stop()
        self.bucket.destroy_all()
        shutil.rmtree(self.tmpdir)


    # TODO(kenobi): Read moblab_config.ini to get the correct base version
    # instead of hard-coding it.
    def testSetupBase05(self):
        """Verifies that the code for installing the rootfs location into the
        lxc config, is working correctly.
        """
        # Set up the bucket, then start the base container, and verify it works.
        self.downloadAndStart('base_05')


    # TODO(kenobi): Read shadow_config.ini to get the correct base version
    # instead of hard-coding it.
    def testSetupBase09(self):
        """Verifies that the setup_base code works with the base_09 image. """
        self.downloadAndStart('base_09')


    def downloadAndStart(self, name):
        """Calls setup_base with the given base image name, then starts the
        container and verifies that it is running.

        @param name: The name of the base image to download and test with.
        """
        self.bucket.setup_base(name=name)
        base_container = self.bucket.get(name)
        base_container.start()
        self.assertTrue(base_container.is_running())

def parse_options():
    """Parse command line inputs."""
    parser = argparse.ArgumentParser()
    parser.add_argument('-v', '--verbose', action='store_true',
                        help='Print out ALL entries.')
    args, _unused = parser.parse_known_args()
    return args


if __name__ == '__main__':
    options = parse_options()

    log_level=(logging.DEBUG if options.verbose else logging.INFO)
    unittest_logging.setup(log_level)

    unittest.main()
