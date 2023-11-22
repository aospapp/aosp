#!/usr/bin/env python3
#
#   Copyright 2018 - The Android Open Source Project
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
import logging

from acts.keys import Config


class ConfigWrapper(object):
    """A wrapper tool around the finalized Configuration.

    The purpose of this class is to give ACTS the ability to gather metrics on
    how its configuration is being used, which in turn will allow old values
    to be deprecated and refine workflows to match user needs.
    """
    def __init__(self, dictionary):
        self._dictionary = dictionary

    def __getitem__(self, key):
        if key == Config.key_config_path:
            logging.warning(
                'The config key "%s" is pending deprecation. For resolving '
                'files in the same directory as your config, please use a '
                'key-value entry in your config that contains an absolute '
                'path.' % Config.key_config_path)
        return self._dictionary[key]

    def __contains__(self, key):
        return key in self._dictionary

    def __setitem__(self, key, item):
        logging.warning('Setting additional keys in the base configuration is '
                        'pending deprecation.')
        self._dictionary[key] = item

    def __delitem__(self, key):
        logging.warning('Deleting keys from the base configuration is '
                        'pending deprecation.')
        del self._dictionary[key]

    def items(self):
        return self._dictionary.items()

    def __str__(self):
        return str(self._dictionary)
