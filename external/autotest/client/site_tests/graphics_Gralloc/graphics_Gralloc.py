# Copyright 2016 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os
import re

from autotest_lib.client.bin import utils
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import arc
from autotest_lib.client.cros.graphics import graphics_utils

_SDCARD_EXEC = '/sdcard/gralloctest'
_EXEC_DIRECTORY = '/data/executables/'
# The tests still can be run individually, though we run with the 'all' option
# Run ./gralloctest in Android to get a list of options.
_ANDROID_EXEC = _EXEC_DIRECTORY + 'gralloctest'
_OPTION = 'all'

# GraphicsTest should go first as it will pass initialize/cleanup function
# to ArcTest. GraphicsTest initialize would not be called if ArcTest goes first
class graphics_Gralloc(graphics_utils.GraphicsTest, arc.ArcTest):
    """gralloc test."""
    version = 1

    def setup(self):
        os.chdir(self.srcdir)
        utils.make('clean')
        utils.make('all')

    def initialize(self):
        super(graphics_Gralloc, self).initialize(autotest_ext=True)

    def arc_setup(self):
        super(graphics_Gralloc, self).arc_setup()
        # Get the executable from CrOS and copy it to Android container. Due to
        # weird permission issues inside the container, we first have to copy
        # the test to /sdcard/, then move it to a /data/ subdirectory we create.
        # The permissions on the exectuable have to be modified as well.
        arc.adb_root()
        cmd = os.path.join(self.srcdir, 'gralloctest')
        arc.adb_cmd('-e push %s %s' % (cmd, _SDCARD_EXEC))
        arc._android_shell('mkdir -p %s' % (_EXEC_DIRECTORY))
        arc._android_shell('mv %s %s' % (_SDCARD_EXEC, _ANDROID_EXEC))
        arc._android_shell('chmod o+rwx %s' % (_ANDROID_EXEC))

    def arc_teardown(self):
        # Remove test contents from Android container.
        arc._android_shell('rm -rf %s' % (_EXEC_DIRECTORY))
        super(graphics_Gralloc, self).arc_teardown()

    def run_once(self):
        try:
            cmd = '%s %s' % (_ANDROID_EXEC, _OPTION)
            stdout = arc._android_shell(cmd)
        except Exception:
            logging.error('Exception running %s', cmd)
        # Look for the regular expression indicating failure.
        for line in stdout.splitlines():
            match = re.search(r'\[  FAILED  \]', stdout)
            if match:
                self.add_failures(line)
                logging.error(line)
            else:
                logging.debug(stdout)

        if self.get_failures():
            gpu_family = utils.get_gpu_family()
            raise error.TestFail('Failed: gralloc on %s in %s.' %
                                 (gpu_family, self.get_failures()))
