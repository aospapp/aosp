#
# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from vts.runners.host import const


class ShellMirrorObject(object):
    '''The class that mirrors a shell on the native side.

    Attributes:
        _client: the TCP client instance.
        enabled: bool, whether RPC shell feature is enabled for the device.
    '''

    def __init__(self, client):
        self._client = client
        self.enabled = True

    def Execute(self, command, no_except=False):
        '''Execute remote shell commands on device.

        Args:
            command: string or a list of string, shell commands to execute on
                     device.
            no_except: bool, if set to True, no exception will be thrown and
                       error code will be -1 with error message on stderr.

        Returns:
            A dictionary containing shell command execution results
        '''
        if not self.enabled:
            # TODO(yuexima): use adb shell instead when RPC is disabled
            return {
                const.STDOUT: [""] * len(command),
                const.STDERR:
                ["VTS remote shell has been disabled."] * len(command),
                const.EXIT_CODE: [-2] * len(command)
            }
        return self._client.ExecuteShellCommand(command, no_except)

    def CleanUp(self):
        self._client.Disconnect()
