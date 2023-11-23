# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Definition of CrOS suite exceptions in skylab."""


class DroneEnvironmentError(Exception):
    """Raised on incorrect setup of the drone executing skylab_suite."""


class InValidPropertyError(Exception):
    """Raised if a suite's property is not valid."""


class NoAvailableDUTsError(Exception):
    """Raised if there's no available DUTs for provision suite."""
    def __init__(self, board, pool, available_num, required_num):
        self.board = board
        self.pool = pool
        self.available_num = available_num
        self.required_num = required_num
        super(NoAvailableDUTsError, self).__init__(
                board, pool, available_num, required_num)

    def __str__(self):
        return ('The available number of DUTs for board %s and pool %s is %d ,'
                'which is less than %d, the required number.' % (
                        self.board, self.pool, self.available_num,
                        self.required_num))
