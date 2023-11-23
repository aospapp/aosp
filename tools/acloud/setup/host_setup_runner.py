#!/usr/bin/env python
#
# Copyright 2018 - The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
r"""host setup runner

A setup sub task runner to support setting up the local host for AVD local
instance.
"""

from __future__ import print_function

import getpass
import logging

from acloud.internal import constants
from acloud.internal.lib import utils
from acloud.setup import base_task_runner
from acloud.setup import setup_common

logger = logging.getLogger(__name__)

# Install cuttlefish-common will probably not work now.
# TODO: update this to pull from the proper repo.
_AVD_REQUIRED_PKGS = ["cuttlefish-common", "ssvnc",
                      # TODO(b/117613492): This is all qemu related, take this
                      # out once they are added back in as deps for
                      # cuttlefish-common.
                      "qemu-kvm", "qemu-system-common", "qemu-system-x86",
                      "qemu-utils", "libvirt-clients", "libvirt-daemon-system"]
_LIST_OF_MODULES = ["kvm_intel", "kvm"]
_UPDATE_APT_GET_CMD = "sudo apt-get update"


class AvdPkgInstaller(base_task_runner.BaseTaskRunner):
    """Subtask runner class for installing required packages."""

    WELCOME_MESSAGE_TITLE = "Install required package for host setup"
    WELCOME_MESSAGE = (
        "This step will walk you through the required packages installation for "
        "running Android cuttlefish devices and vnc on your host.")

    def ShouldRun(self):
        """Check if required packages are all installed.

        Returns:
            Boolean, True if required packages are not installed.
        """
        if not utils.IsSupportedPlatform():
            return False

        # Any required package is not installed or not up-to-date will need to
        # run installation task.
        for pkg_name in _AVD_REQUIRED_PKGS:
            if not setup_common.PackageInstalled(pkg_name):
                return True

        return False

    def _Run(self):
        """Install Cuttlefish-common package."""

        logger.info("Start to install required package: %s ",
                    _AVD_REQUIRED_PKGS)

        setup_common.CheckCmdOutput(_UPDATE_APT_GET_CMD, shell=True)
        for pkg in _AVD_REQUIRED_PKGS:
            setup_common.InstallPackage(pkg)

        logger.info("All required package are installed now.")


class CuttlefishHostSetup(base_task_runner.BaseTaskRunner):
    """Subtask class that setup host for cuttlefish."""

    WELCOME_MESSAGE_TITLE = "Host Enviornment Setup"
    WELCOME_MESSAGE = (
        "This step will help you to setup enviornment for running Android "
        "cuttlefish devices on your host. That includes adding user to kvm "
        "related groups and checking required linux modules."
    )

    def ShouldRun(self):
        """Check host user groups and modules.

         Returns:
             Boolean: False if user is in all required groups and all modules
                      are reloaded.
         """
        if not utils.IsSupportedPlatform():
            return False

        return not (utils.CheckUserInGroups(constants.LIST_CF_USER_GROUPS)
                    and self._CheckLoadedModules(_LIST_OF_MODULES))

    @staticmethod
    def _CheckLoadedModules(module_list):
        """Check if the modules are all in use.

        Args:
            module_list: The list of module name.
        Returns:
            True if all modules are in use.
        """
        logger.info("Checking if modules are loaded: %s", module_list)
        lsmod_output = setup_common.CheckCmdOutput("lsmod", print_cmd=False)
        current_modules = [r.split()[0] for r in lsmod_output.splitlines()]
        all_modules_present = True
        for module in module_list:
            if module not in current_modules:
                logger.info("missing module: %s", module)
                all_modules_present = False
        return all_modules_present

    def _Run(self):
        """Setup host environment for local cuttlefish instance support."""
        # TODO: provide --uid args to let user use prefered username
        username = getpass.getuser()
        setup_cmds = [
            "sudo rmmod kvm_intel",
            "sudo rmmod kvm",
            "sudo modprobe kvm",
            "sudo modprobe kvm_intel"]
        for group in constants.LIST_CF_USER_GROUPS:
            setup_cmds.append("sudo usermod -aG %s % s" % (group, username))

        print("Below commands will be run:")
        for setup_cmd in setup_cmds:
            print(setup_cmd)

        if self._ConfirmContinue():
            for setup_cmd in setup_cmds:
                setup_common.CheckCmdOutput(setup_cmd, shell=True)
            print("Host environment setup has done!")

    @staticmethod
    def _ConfirmContinue():
        """Ask user if they want to continue.

        Returns:
            True if user answer yes.
        """
        answer_client = utils.InteractWithQuestion(
            "\nPress 'y' to continue or anything else to do it myself[y/N]: ",
            utils.TextColors.WARNING)
        return answer_client in constants.USER_ANSWER_YES
