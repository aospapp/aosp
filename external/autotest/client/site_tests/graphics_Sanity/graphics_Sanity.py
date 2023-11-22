# Copyright (c) 2010 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import os
import logging

from autotest_lib.client.bin import test, utils
from autotest_lib.client.cros import service_stopper
from autotest_lib.client.cros.graphics import graphics_utils

# to run this test manually on a test target
# ssh root@machine
# cd /usr/local/autotest/deps/glbench
# stop ui
# ./windowmanagertest --screenshot1_sec 2 --screenshot2_sec 1 --cooldown_sec 1 \
#    --screenshot1_cmd \
#        "/usr/local/autotest/bin/screenshot.py screenshot1_generated.png" \
#    --screenshot2_cmd \
#        "/usr/local/autotest/bin/screenshot.py screenshot2_generated.png"
# start ui

class graphics_Sanity(graphics_utils.GraphicsTest):
    """
    This test is meant to be used as a quick sanity check for GL/GLES.
    """
    version = 1

    # None-init vars used by cleanup() here, in case setup() fails
    _services = None


    def setup(self):
        self.job.setup_dep(['glbench'])


    def initialize(self):
        super(graphics_Sanity, self).initialize()
        # If UI is running, we must stop it and restore later.
        self._services = service_stopper.ServiceStopper(['ui'])
        self._services.stop_services()


    def cleanup(self):
        super(graphics_Sanity, self).cleanup()
        if self._services:
          self._services.restore_services()


    def run_once(self):
        """
        Draws a texture with a soft ellipse twice and captures each image.
        Compares the output fuzzily against reference images.
        """
        if graphics_utils.get_display_resolution() is None:
            logging.warning('Skipping test because there is no screen')
            return
        self.add_failures('graphics_Sanity')

        dep = 'glbench'
        dep_dir = os.path.join(self.autodir, 'deps', dep)
        self.job.install_pkg(dep, 'dep', dep_dir)

        screenshot1_reference = os.path.join(self.bindir,
                                             "screenshot1_reference.png")
        screenshot1_generated = os.path.join(self.resultsdir,
                                             "screenshot1_generated.png")
        screenshot1_resized = os.path.join(self.resultsdir,
                                           "screenshot1_generated_resized.png")
        screenshot2_reference = os.path.join(self.bindir,
                                             "screenshot2_reference.png")
        screenshot2_generated = os.path.join(self.resultsdir,
                                             "screenshot2_generated.png")
        screenshot2_resized = os.path.join(self.resultsdir,
                                           "screenshot2_generated_resized.png")

        exefile = os.path.join(self.autodir, 'deps/glbench/windowmanagertest')

        # Delay before screenshot: 1 second has caused failures.
        options = ' --screenshot1_sec 2'
        options += ' --screenshot2_sec 1'
        options += ' --cooldown_sec 1'
        # perceptualdiff can handle only 8 bit images.
        screenshot_cmd = ' "/usr/local/autotest/bin/screenshot.py %s"'
        options += ' --screenshot1_cmd' + screenshot_cmd % screenshot1_generated
        options += ' --screenshot2_cmd' + screenshot_cmd % screenshot2_generated

        cmd = exefile + ' ' + options
        utils.run(cmd,
                  stdout_tee=utils.TEE_TO_LOGS,
                  stderr_tee=utils.TEE_TO_LOGS)

        convert_cmd = ("convert -channel RGB -colorspace RGB -depth 8"
                       " -resize '100x100!' %s %s")
        utils.system(convert_cmd % (screenshot1_generated, screenshot1_resized))
        utils.system(convert_cmd % (screenshot2_generated, screenshot2_resized))
        os.remove(screenshot1_generated)
        os.remove(screenshot2_generated)

        diff_cmd = 'perceptualdiff -verbose %s %s'
        utils.system(diff_cmd % (screenshot1_reference, screenshot1_resized))
        utils.system(diff_cmd % (screenshot2_reference, screenshot2_resized))

        self.remove_failures('graphics_Sanity')
