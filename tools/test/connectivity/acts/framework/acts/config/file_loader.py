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
from acts import utils


class ConfigFileParser(object):
    """A strategy base class for parsing config files."""

    def parse(self, file_path):
        """Parses the file at the given path."""
        raise NotImplementedError


class JsonFileParser(ConfigFileParser):
    """A strategy class for parsing JSON files."""

    def parse(self, file_path):
        """Parses a JSON file into a config dict."""
        # Suppress any error log message, but still raise an error upon failure.
        return utils.load_config(file_path, log_errors=False)


class ConfigFileLoader(object):
    """A class that loads a config file, agnostic of config file type."""
    _file_parsers = [
        JsonFileParser()
    ]

    @classmethod
    def load_config_from_file(cls, file_path):
        exception_list = []
        for file_parser in cls._file_parsers:
            try:
                return file_parser.parse(file_path)
            except Exception as e:
                exception_list.append(e)
        raise ValueError(
            'Unable to find a suitable file parser for %s. Each file parser '
            'failed with the below errors:\n %s' %
            (file_path,
             ''.join(['%s: %s\n' % (type(cls._file_parsers[i]),
                                    exception_list[i])
                      for i in range(len(exception_list))])))
