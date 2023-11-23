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
"""Queue wrapper object to be shared across all tests using the bigquery_buffer module."""

from multiprocessing import Queue

DEFAULT_SIZE = 30000


class BigqueryLoggerQueue:
    """Organizes and stores all BigQuery table row updates sent to it."""

    def __init__(self, size=DEFAULT_SIZE):
        self.queue = Queue(maxsize=size)
        self.flush_period = 1

    def add_row(self, dataset_id, table_id, row):
        """Store row to be added with all other rows to be added to passed table.

    Arguments:
      dataset_id: the dataset in which table_id resides.
      table_id: the id of the table to update.
      row: a dictionary of field: value pairs representing the row to add.
    """

        self.queue.put(((dataset_id, table_id), row))

    def get_insert_iterator(self):
        """Organize queue into iterator of ((dataset_id, table_id), rows_list) tuples.
    Takes state of queue upon invocation, ignoring items put in queue after.

    Returns:
      insert_iterator: an iterator of pairs dataset/table ids and the lists
      of rows to insert into those tables.
    """

        insert_dict = {}
        num_entries_to_insert = self.queue.qsize()

        for i in xrange(num_entries_to_insert):
            if not self.queue.empty():
                dataset_table_tuple, row_dict = self.queue.get()
                if dataset_table_tuple not in insert_dict.keys():
                    insert_dict[dataset_table_tuple] = []
                insert_dict[dataset_table_tuple].append(row_dict)

        return insert_dict.items()

    def put(self, row_tuple):
        self.queue.put(row_tuple)

    def get(self):
        return self.queue.get()

    def empty(self):
        return self.queue.empty()

    def get_flush_period(self):
        return self.flush_period

    def set_flush_period(self, period):
        self.flush_period = int(period)
