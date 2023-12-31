#!/usr/bin/env python3
#
#   Copyright 2019 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import datetime

from acts.controllers.fuchsia_lib.base_lib import BaseLib


class FuchsiaLoggingLib(BaseLib):

    def __init__(self, addr: str) -> None:
        super().__init__(addr, "logging")

    def logE(self, message):
        """Log a message of level Error directly to the syslog.

        Args:
            message: The message to log.

        Returns:
            Dictionary, None if success, error if error.
        """
        test_cmd = "logging_facade.LogErr"
        test_args = {
            "message": '[%s] %s' % (datetime.datetime.now(), message),
        }

        return self.send_command(test_cmd, test_args)

    def logI(self, message):
        """Log a message of level Info directly to the syslog.

        Args:
            message: The message to log.

        Returns:
            Dictionary, None if success, error if error.
        """
        test_cmd = "logging_facade.LogInfo"
        test_args = {"message": '[%s] %s' % (datetime.datetime.now(), message)}

        return self.send_command(test_cmd, test_args)

    def logW(self, message):
        """Log a message of level Warning directly to the syslog.

        Args:
            message: The message to log.

        Returns:
            Dictionary, None if success, error if error.
        """
        test_cmd = "logging_facade.LogWarn"
        test_args = {"message": '[%s] %s' % (datetime.datetime.now(), message)}

        return self.send_command(test_cmd, test_args)
