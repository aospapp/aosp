#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Copyright 2020 The ChromiumOS Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Change portions of the object files to good.

The "portion" is defined by the file (which is passed as the only argument to
this script) content. Every line in the file is an object index, which will be
set to good (mark as 0).

This switch script is made for the noincremental-prune test. This makes sure
that, after pruning starts (>1 bad item is found), that the number of args sent
to the switch scripts is equals to the actual number of items (i.e. checking
that noincremental always holds).

Warning: This switch script assumes the --file_args option
"""


import shutil
import sys

from binary_search_tool.test import common


def Main(argv):
    working_set = common.ReadWorkingSet()
    object_index = common.ReadObjectIndex(argv[1])

    for oi in object_index:
        working_set[int(oi)] = 0

    shutil.copy(argv[1], "./noinc_prune_good")

    common.WriteWorkingSet(working_set)

    return 0


if __name__ == "__main__":
    retval = Main(sys.argv)
    sys.exit(retval)
