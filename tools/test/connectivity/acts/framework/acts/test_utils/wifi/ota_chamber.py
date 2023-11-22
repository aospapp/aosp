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

from acts import logger
from acts import utils


def create(configs):
    """Factory method for OTA chambers.

    Args:
        configs: list of dicts with chamber settings. settings must contain the
        following: type (string denoting type of chamber)
    """
    objs = []
    for config in configs:
        try:
            chamber_class = globals()[config['type']]
        except KeyError:
            raise KeyError('Invalid chamber configuration.')
        objs.append(chamber_class(config))
    return objs


def detroy(objs):
    return


class OtaChamber(object):
    """Base class implementation for OTA chamber.

    Base class provides functions whose implementation is shared by all
    chambers.
    """

    def set_orientation(angle):
        """Set orientation for turn table in OTA chamber.

        Args:
            angle: desired turn table orientation in degrees
        """
        raise NotImplementedError


class MockChamber(OtaChamber):
    """Class that implements mock chamber for test development and debug."""

    def __init__(self, config):
        self.config = config.copy()
        self.device_id = self.config['device_id']
        self.log = logger.create_tagged_trace_logger('OtaChamber|{}'.format(
            self.device_id))

    def set_orientation(self, orientation):
        self.log.info('Setting orientation to {} degrees.'.format(orientation))


class OctoboxChamber(OtaChamber):
    """Class that implements Octobox chamber."""

    def __init__(self, config):
        self.config = config.copy()
        self.device_id = self.config['device_id']
        self.log = logger.create_tagged_trace_logger('OtaChamber|{}'.format(
            self.device_id))
        self.TURNTABLE_FILE_PATH = '/usr/local/bin/fnPerformaxCmd'
        utils.exe_cmd('{} -d {} -i 0'.format(self.TURNTABLE_FILE_PATH,
                                             self.device_id))

    def set_orientation(self, orientation):
        self.log.info('Setting orientation to {} degrees.'.format(orientation))
        utils.exe_cmd('{} -d {} -p {}'.format(self.TURNTABLE_FILE_PATH,
                                              self.device_id, orientation))
