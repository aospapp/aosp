# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import os

import common
from autotest_lib.site_utils import lxc
from autotest_lib.site_utils.lxc import constants
from autotest_lib.site_utils.lxc import utils as lxc_utils


class FastContainerBucket(lxc.ContainerBucket):
    """A fast container bucket for testing.

    If a base container image already exists in the default location on the
    local machine, this container just makes a snapshot of it for testing,
    rather than re-downloading and installing a fresh base continer.
    """
    def __init__(self, lxc_path, host_path):
        self.fast_setup = False
        try:
            if lxc_utils.path_exists(
                    os.path.join(constants.DEFAULT_CONTAINER_PATH,
                                 constants.BASE)):
                lxc_path = os.path.realpath(lxc_path)
                if not lxc_utils.path_exists(lxc_path):
                    os.makedirs(lxc_path)

                # Clone the base container (snapshot for speed) to make a base
                # container for the unit test.
                base = lxc.Container.createFromExistingDir(
                        constants.DEFAULT_CONTAINER_PATH, constants.BASE)
                lxc.Container.clone(src=base,
                                    new_name=constants.BASE,
                                    new_path=lxc_path,
                                    snapshot=True,
                                    cleanup=False)
                self.fast_setup = True
        finally:
            super(FastContainerBucket, self).__init__(lxc_path, host_path)
            if self.base_container is not None:
                self._setup_shared_host_path()


    def setup_base(self, *args, **kwargs):
        """Runs setup_base if fast setup did not work."""
        if not self.fast_setup:
            super(FastContainerBucket, self).setup_base(*args, **kwargs)
