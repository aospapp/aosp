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
r"""Delete args.

Defines the delete arg parser that holds delete specific args.
"""
import argparse

CMD_DELETE = "delete"


def GetDeleteArgParser(subparser):
    """Return the delete arg parser.

    Args:
       subparser: argparse.ArgumentParser that is attached to main acloud cmd.

    Returns:
        argparse.ArgumentParser with delete options defined.
    """
    delete_parser = subparser.add_parser(CMD_DELETE)
    delete_parser.required = False
    delete_parser.set_defaults(which=CMD_DELETE)
    delete_group = delete_parser.add_mutually_exclusive_group()
    delete_group.add_argument(
        "--instance-names",
        dest="instance_names",
        nargs="+",
        required=False,
        help="The names of the remote instances that need to delete, "
        "separated by spaces, e.g. --instance-names instance-1 instance-2")
    delete_group.add_argument(
        "--all",
        action="store_true",
        dest="all",
        required=False,
        help="If more than 1 AVD instance is found, delete them all.")
    delete_group.add_argument(
        "--local-instance",
        action="store_true",
        dest="local_instance",
        required=False,
        help="Only delete the local instance.")
    delete_group.add_argument(
        "--adb-port", "-p",
        type=int,
        dest="adb_port",
        required=False,
        help="Delete instance with specified adb-port.")

    # TODO(b/118439885): Old arg formats to support transition, delete when
    # transistion is done.
    delete_group.add_argument(
        "--instance_names",
        dest="instance_names",
        nargs="+",
        required=False,
        help=argparse.SUPPRESS)

    return delete_parser
