# Copyright (c) 2014 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os

from autotest_lib.client.bin import test, utils
from autotest_lib.client.common_lib import error

ROOTFS_SIZE_2G = 2 * 1024 * 1024 * 1024
ROOTFS_SIZE_4G = 4 * 1024 * 1024 * 1024

class platform_PartitionCheck(test.test):
    """
    Verify partition size is correct.
    """
    version = 1

    def get_partition_size(self, device, partition):
        """
        Get the number of blocks in the partition.

        Args:
            @param device: string, name of the block device.
            @param partition: string, partition name

        Returns:
            int, number of blocks
        """

        part_file = os.path.join('/sys', 'block', device, partition, 'size')
        part_blocks = int(utils.read_one_line(part_file))
        return part_blocks

    def run_once(self):
        errors = []
        device = os.path.basename(utils.get_fixed_dst_drive())
        partitions = [utils.concat_partition(device, i) for i in (3, 5)]

        for p in partitions:
            pblocks = self.get_partition_size(device, p)
            # Linux always considers sectors to be 512 bytes long
            # independently of the devices real block size.
            psize = pblocks * 512;
            if psize != ROOTFS_SIZE_2G and psize != ROOTFS_SIZE_4G:
                errmsg = ('%s is %d bytes, expected %d or %d' %
                          (p, psize, ROOTFS_SIZE_2G, ROOTFS_SIZE_4G))
                logging.warning(errmsg)
                errors.append(errmsg)

        # If self.error is not zero, there were errors.
        if errors:
            raise error.TestFail('There were %d partition errors: %s' %
                                 (len(errors), ': '.join(errors)))
