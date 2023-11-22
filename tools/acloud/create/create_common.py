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
"""Common code used by acloud create methods/classes."""

from __future__ import print_function

import glob
import logging
import os
import tempfile
import time
import zipfile

from acloud import errors
from acloud.internal import constants
from acloud.internal.lib import utils

logger = logging.getLogger(__name__)

_ACLOUD_IMAGE_ZIP_POSTFIX = "-local-img-%s.zip"


def ParseHWPropertyArgs(dict_str, item_separator=",", key_value_separator=":"):
    """Helper function to initialize a dict object from string.

    e.g.
    cpu:2,dpi:240,resolution:1280x800
    -> {"cpu":"2", "dpi":"240", "resolution":"1280x800"}

    Args:
        dict_str: A String to be converted to dict object.
        item_separator: String character to separate items.
        key_value_separator: String character to separate key and value.

    Returns:
        Dict created from key:val pairs in dict_str.

    Raises:
        error.MalformedDictStringError: If dict_str is malformed.
    """
    hw_dict = {}
    if not dict_str:
        return hw_dict

    for item in dict_str.split(item_separator):
        if key_value_separator not in item:
            raise errors.MalformedDictStringError(
                "Expecting ':' in '%s' to make a key-val pair" % item)
        key, value = item.split(key_value_separator)
        if not value or not key:
            raise errors.MalformedDictStringError(
                "Missing key or value in %s, expecting form of 'a:b'" % item)
        hw_dict[key.strip()] = value.strip()

    return hw_dict


@utils.TimeExecute(function_description="Compressing images")
def ZipCFImageFiles(basedir):
    """Zip images from basedir.

    TODO(b/129376163):Use lzop for fast sparse image upload when host image
    support it.

    Args:
        basedir: String of local images path.

    Return:
        Strings of zipped image path.
    """
    tmp_folder = os.path.join(tempfile.gettempdir(),
                              constants.TEMP_ARTIFACTS_FOLDER)
    if not os.path.exists(tmp_folder):
        os.makedirs(tmp_folder)
    archive_name = "%s-local-%d.zip" % (os.environ.get(constants.ENV_BUILD_TARGET),
                                        int(time.time()))
    archive_file = os.path.join(tmp_folder, archive_name)
    if os.path.exists(archive_file):
        raise errors.ZipImageError("This file shouldn't exist, please delete: %s"
                                   % archive_file)

    zip_file = zipfile.ZipFile(archive_file, 'w', zipfile.ZIP_DEFLATED,
                               allowZip64=True)
    required_files = ([os.path.join(basedir, "android-info.txt")] +
                      glob.glob(os.path.join(basedir, "*.img")))
    logger.debug("archiving images: %s", required_files)

    for f in required_files:
        # Pass arcname arg to remove the directory structure.
        zip_file.write(f, arcname=os.path.basename(f))

    zip_file.close()
    logger.debug("zip images done:%s", archive_file)
    return archive_file
