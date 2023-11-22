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
"""Script that runs perpetually, flushing contents of shared BigqueryLoggerQueue
to BigQuery on a specified schedule."""

import sys
import time

import acts.controllers.buds_lib.data_storage.bigquery.bigquery_logger_utils as utils


def start_scheduled_automatic_logging(queue, project_id, credentials_path):
    """Runs infinite while loop that flushes contents of queue to BigQuery
  on schedule determined by flush_period."""

    client = utils.BigqueryLoggerClient(project_id, credentials_path)

    while True:
        # TODO: check if connected to internet before attempting to push to BQ
        insert_iterator = queue.get_insert_iterator()
        for dataset_table_tuple, rows_list in insert_iterator:
            dataset_id, table_id = dataset_table_tuple
            client.flush(rows_list, dataset_id, table_id)

        time.sleep(queue.get_flush_period())


def main():
    """Pass shared BigqueryLoggerQueue to automatic logging method."""
    ip_address, port, authkey, project_id, credentials_path = sys.argv[1:]
    queue = utils.get_queue(ip_address, port, authkey)
    start_scheduled_automatic_logging(queue, project_id, credentials_path)


if __name__ == '__main__':
    main()
