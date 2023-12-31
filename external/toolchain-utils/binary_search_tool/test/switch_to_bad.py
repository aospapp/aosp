#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Copyright 2020 The ChromiumOS Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Switch part of the objects file in working set to (possible) bad ones."""


import sys

from binary_search_tool.test import common


def Main(argv):
    """Switch part of the objects file in working set to (possible) bad ones."""
    working_set = common.ReadWorkingSet()
    objects_file = common.ReadObjectsFile()
    object_index = common.ReadObjectIndex(argv[1])

    for oi in object_index:
        working_set[oi] = objects_file[oi]

    common.WriteWorkingSet(working_set)

    return 0


if __name__ == "__main__":
    retval = Main(sys.argv)
    sys.exit(retval)
