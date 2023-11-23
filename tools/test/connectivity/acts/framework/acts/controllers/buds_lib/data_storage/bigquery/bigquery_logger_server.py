#!/usr/bin/env python3
#
# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.
"""Script to start running server that manages a shared BigqueryLoggerQueue."""

import sys
from multiprocessing.managers import BaseManager

from acts.controllers.buds_lib.data_storage.bigquery.bigquery_logger_queue import BigqueryLoggerQueue


def start_queue_server(queue_size, ip_address, port, authkey):
    queue = BigqueryLoggerQueue(size=int(queue_size))
    BaseManager.register('get_queue', callable=lambda: queue)
    m = BaseManager(address=(ip_address, int(port)), authkey=authkey)
    s = m.get_server()

    print('starting server...')
    s.serve_forever()


def main():
    queue_size, ip_address, port, authkey = sys.argv[1:]
    start_queue_server(queue_size, ip_address, port, authkey)


if __name__ == '__main__':
    main()
