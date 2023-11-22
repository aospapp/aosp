#!/usr/bin/env python3
#
#   Copyright 2018 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the 'License');
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an 'AS IS' BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.


class ConfigEntryMeta(object):
    """An object that holds metadata about a type of Configuration Argument.

    Attributes:
        cli_flags: The CLI flags (e.g. -lp or --logpath for the ACTS logpath).
        cli_metavar: The metavar for the CLI. See argparse.ArgumentParser.
        cli_nargs: The number of expected CLI args. See argparse.ArgumentParser.
        cli_required: True if the entry must be present in the CLI.
        cli_action: The CLI action, if any. See argparse.ArgumentParser.
        cli_const: The const value for the CLI.
        cli_default: The default value for the CLI.
        type: The type to expect, or None. Used for validation.
        help: The user documentation for this configuration value.
        acts_config_key: The key in the ACTS config this value corresponds to.
        env_var_name: The environment variable that may store this config value.
    """

    """The prefix, if any, to use cli_kwargs in parser.add_argument()."""
    cli_prefix = 'cli_'

    """The attributes corresponding to ArgumentParser.add_argument() kwargs."""
    cli_kwarg_attributes = [
        'cli_metavar',
        'cli_nargs',
        'cli_required',
        'cli_action',
        'cli_const',
        'cli_default',
        'cli_dest',
        'type',
        'help',
    ]

    @classmethod
    def attr_to_cli_kwarg(cls, attribute):
        """Converts an attribute name to the corresponding add_argument() kwarg.

        Args:
            attribute: The attribute name, preferably one found in
                       cli_kwarg_attributes.

        Returns:
            The kwarg name.
        """
        kwarg = attribute
        if attribute.startswith(ConfigEntryMeta.cli_prefix):
            kwarg = attribute[len(ConfigEntryMeta.cli_prefix):]
        return kwarg

    def __init__(self, cli_flags=None, cli_metavar=None, cli_nargs=None,
                 cli_required=False, cli_action=None, cli_const=None,
                 cli_default=None, type=None, help=None, acts_config_key=None,
                 env_var_name=None):
        """Initializes the ConfigEntryMeta object.

        Args:
            cli_flags: The CLI flags. Can either be a list or a single string.
                       Accessing the cli_flags from this object will always
                       return a list.
            cli_metavar: The metavar for the CLI. See
                         https://docs.python.org/3/library/argparse.html#metavar
            cli_nargs: The number of expected CLI args. See
                       https://docs.python.org/3/library/argparse.html#nargs
            cli_const: The const value for the CLI. See
                       https://docs.python.org/3/library/argparse.html#const
            cli_default: The const value for the CLI. See
                         https://docs.python.org/3/library/argparse.html#default
            cli_required: True if the CLI must have this argument. See
                https://docs.python.org/3/library/argparse.html#required
            cli_action: The CLI action, if any. See
                        https://docs.python.org/3/library/argparse.html#action
            type: The type to expect, or None. Used for validation.
            help: The user documentation for this configuration value.
            acts_config_key: The key in the ACTS config this value corresponds
                             to.
            env_var_name: The environment variable that may store this config
                          value.
        """
        if ConfigEntryMeta.__get_type(cli_flags) is str:
            self.cli_flags = [cli_flags]
        else:
            self.cli_flags = cli_flags
        self.cli_metavar = cli_metavar
        self.cli_nargs = cli_nargs
        self.cli_required = cli_required
        self.cli_action = cli_action
        self.cli_const = cli_const
        self.cli_default = cli_default
        self.type = type
        self.help = help
        self.acts_config_key = acts_config_key
        self.cli_dest = acts_config_key
        self.env_var_name = env_var_name

    @staticmethod
    def __get_type(obj):
        """A wrapper function to use the type() keyword when shadowed."""
        return type(obj)
