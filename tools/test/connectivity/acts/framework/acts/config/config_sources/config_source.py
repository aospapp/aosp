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


class ConfigSource(object):
    """The base class for getting configuration data from any source."""

    def gather_configs(self, config_args_list):
        """Initializes the list of inputs so that they can be queried.

        Args:
            config_args_list: A list of ConfigEntryMetas to fetch inputs for.

        Returns:
            A configuration dict.
        """
        raise NotImplementedError()
