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
import argparse

import sys

from acts import utils
from acts.config.config_entry_meta import ConfigEntryMeta
from acts.config.config_sources.config_source import ConfigSource


class CliConfigSource(ConfigSource):
    """A class that handles obtaining configs passed in through the CLI."""

    def gather_configs(self, config_entry_metas):
        """Initializes the list of inputs so that they can be queried.

        Args:
            config_entry_metas: A list of ConfigEntryMetas to fetch inputs for.

        Returns:
            A dict of {config_key: value}
        """
        parser = argparse.ArgumentParser(
            description='Specify tests to run. If nothing specified, '
                        'run all test cases found.')
        self._add_arguments(parser, config_entry_metas)
        args = sys.argv[1:]
        # Note: parse_args() can generate a SystemExit error if the arguments
        #       parsed are invalid or malformatted.
        namespace = parser.parse_args(args)
        return utils.dict_purge_key_if_value_is_none(dict(vars(namespace)))

    def _add_arguments(self, parser, config_entry_metas):
        for entry in config_entry_metas:
            if entry.cli_flags is not None:
                parser.add_argument(*entry.cli_flags,
                                    **self._get_cli_kwargs(entry))

    @classmethod
    def _get_cli_kwargs(cls, config_entry_meta):
        """Returns a dict of kwargs needed for ArgumentParser.add_argument().

        Args:
            config_entry_meta: The entry to generate add_argument() kwargs for.
        """
        kwarg_dict = {}
        for attr_name in ConfigEntryMeta.cli_kwarg_attributes:
            kwarg = ConfigEntryMeta.attr_to_cli_kwarg(attr_name)
            value = getattr(config_entry_meta, attr_name, None)
            kwarg_dict[kwarg] = value
        return utils.dict_purge_key_if_value_is_none(kwarg_dict)
