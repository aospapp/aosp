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
"""Client object for testing infrastructure to store information in BigQuery"""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

from acts.controllers.buds_lib.data_storage.bigquery.bigquery_logger_utils import add_row, BigqueryLoggerClient

PROJECT_ID = 'google.com:wearables-qa'
CREDENTIALS_PATH = '/google/data/ro/teams/wearables/test/automation/bigquery/wearables-service-key.json'


class BigqueryLogger:
    """Bigquery Logger specialized for automated test logging."""

    def __init__(self, dataset_id, table_id):
        """Initialization method for BigqueryLogger class."""
        # An array of InsertEntry objects to insert into the BigQuery table
        self.rows = []
        self.dataset_id = dataset_id
        self.table_id = table_id
        self.utils = BigqueryLoggerClient(
            project_id=PROJECT_ID,
            google_application_credentials_path=CREDENTIALS_PATH)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.utils.flush(self.rows, self.dataset_id, self.table_id)

    def clear(self):
        """Clear data structures"""
        self.rows = []

    def get_rows(self):
        """Getter method for self.rows()."""
        return self.rows

    def add_row(self, row_dict):
        print('Adding row...')
        return add_row(row_dict, self.rows)
