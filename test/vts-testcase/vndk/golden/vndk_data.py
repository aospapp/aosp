#
# Copyright (C) 2017 The Android Open Source Project
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

import collections
import logging
import os

# The tags in VNDK list:
# Low-level NDK libraries that can be used by framework and vendor modules.
LL_NDK = "LLNDK"

# LL-NDK dependencies that vendor modules cannot directly access.
LL_NDK_PRIVATE = "LLNDK-private"

# Same-process HAL implementation in vendor partition.
SP_HAL = "SP-HAL"

# Framework libraries that can be used by vendor modules except same-process HAL
# and its dependencies in vendor partition.
VNDK = "VNDK-core"

# VNDK dependencies that vendor modules cannot directly access.
VNDK_PRIVATE = "VNDK-core-private"

# Same-process HAL dependencies in framework.
VNDK_SP = "VNDK-SP"

# VNDK-SP dependencies that vendor modules cannot directly access.
VNDK_SP_PRIVATE = "VNDK-SP-private"

# The ABI dump directories. 64-bit comes before 32-bit in order to sequentially
# search for longest prefix.
_ABI_NAMES = ("arm64", "arm", "mips64", "mips", "x86_64", "x86")

# The data directory.
_GOLDEN_DIR = os.path.join("vts", "testcases", "vndk", "golden")

# Regular expression prefix for library name patterns.
_REGEX_PREFIX = "[regex]"

def LoadDefaultVndkVersion(data_file_path):
    """Loads the name of the data directory for devices with no VNDK version.

    Args:
        data_file_path: The path to VTS data directory.

    Returns:
        A string, the directory name.
        None if fails to load the name.
    """
    try:
        with open(os.path.join(data_file_path, _GOLDEN_DIR,
                               "platform_vndk_version.txt"), "r") as f:
            return f.read().strip()
    except IOError:
        logging.error("Cannot load default VNDK version.")
        return None


def GetAbiDumpDirectory(data_file_path, version, binder_bitness, abi_name,
                        abi_bitness):
    """Returns the VNDK dump directory on host.

    Args:
        data_file_path: The path to VTS data directory.
        version: A string, the VNDK version.
        binder_bitness: A string or an integer, 32 or 64.
        abi_name: A string, the ABI of the library dump.
        abi_bitness: A string or an integer, 32 or 64.

    Returns:
        A string, the path to the dump directory.
        None if there is no directory for the version and ABI.
    """
    try:
        abi_dir = next(x for x in _ABI_NAMES if abi_name.startswith(x))
    except StopIteration:
        logging.warning("Unknown ABI %s.", abi_name)
        return None

    version_dir = (version if version else
                   LoadDefaultVndkVersion(data_file_path))
    if not version_dir:
        return None

    dump_dir = os.path.join(
        data_file_path, _GOLDEN_DIR, version_dir,
        "binder64" if str(binder_bitness) == "64" else "binder32",
        abi_dir, "lib64" if str(abi_bitness) == "64" else "lib")

    if not os.path.isdir(dump_dir):
        logging.warning("%s is not a directory.", dump_dir)
        return None

    return dump_dir


def _LoadVndkLibraryListsFile(vndk_lists, tags, vndk_lib_list_path):
    """Load VNDK libraries from the file to the specified tuple.

    Args:
        vndk_lists: The output tuple of lists containing library names.
        tags: Strings, the tags of the libraries to find.
        vndk_lib_list_path: The path to load the VNDK library list.
    """

    lib_sets = collections.defaultdict(set)

    # Load VNDK tags from the list.
    with open(vndk_lib_list_path) as vndk_lib_list_file:
        for line in vndk_lib_list_file:
            # Ignore comments.
            if line.startswith('#'):
                continue

            # Split columns.
            cells = line.split(': ', 1)
            if len(cells) < 2:
                continue
            tag = cells[0]
            lib_name = cells[1].strip()

            lib_sets[tag].add(lib_name)

    # Compute VNDK-core-private and VNDK-SP-private.
    private = lib_sets.get('VNDK-private', set())

    lib_sets[LL_NDK_PRIVATE].update(lib_sets[LL_NDK] & private)
    lib_sets[VNDK_PRIVATE].update(lib_sets[VNDK] & private)
    lib_sets[VNDK_SP_PRIVATE].update(lib_sets[VNDK_SP] & private)

    lib_sets[LL_NDK].difference_update(private)
    lib_sets[VNDK].difference_update(private)
    lib_sets[VNDK_SP].difference_update(private)

    # Update the output entries.
    for index, tag in enumerate(tags):
        for lib_name in lib_sets.get(tag, tuple()):
            if lib_name.startswith(_REGEX_PREFIX):
                lib_name = lib_name[len(_REGEX_PREFIX):]
            vndk_lists[index].append(lib_name)


def LoadVndkLibraryLists(data_file_path, version, *tags):
    """Find the VNDK libraries with specific tags.

    Args:
        data_file_path: The path to VTS data directory.
        version: A string, the VNDK version.
        *tags: Strings, the tags of the libraries to find.

    Returns:
        A tuple of lists containing library names. Each list corresponds to
        one tag in the argument. For SP-HAL, the returned names are regular
        expressions.
        None if the spreadsheet for the version is not found.
    """
    version_dir = (version if version else
                   LoadDefaultVndkVersion(data_file_path))
    if not version_dir:
        return None

    vndk_lib_list_path = os.path.join(
        data_file_path, _GOLDEN_DIR, version_dir, "vndk-lib-list.txt")
    if not os.path.isfile(vndk_lib_list_path):
        logging.warning("Cannot load %s.", vndk_lib_list_path)
        return None

    vndk_lib_extra_list_path = os.path.join(
        data_file_path, _GOLDEN_DIR, version_dir, "vndk-lib-extra-list.txt")
    if not os.path.isfile(vndk_lib_extra_list_path):
        logging.warning("Cannot load %s.", vndk_lib_extra_list_path)
        return None

    vndk_lists = tuple([] for x in tags)

    _LoadVndkLibraryListsFile(vndk_lists, tags, vndk_lib_list_path)
    _LoadVndkLibraryListsFile(vndk_lists, tags, vndk_lib_extra_list_path)
    return vndk_lists
