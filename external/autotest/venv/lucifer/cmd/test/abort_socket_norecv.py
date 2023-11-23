# Copyright 2018 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Open a socket without listening.

This is used for testing leasing.
"""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import logging
import socket
import sys
import time

from lucifer import loglib

logger = logging.getLogger(__name__)


def main(_args):
    """Main function

    @param args: list of command line args
    """
    loglib.configure_logging(name='abort_socket')
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
    sock.bind(sys.argv[1])
    # Minimum value is 256 on Linux (but on my machine it's 2304).
    # See also socket(7).
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 128)
    print('done')
    while True:
        time.sleep(10)



if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
