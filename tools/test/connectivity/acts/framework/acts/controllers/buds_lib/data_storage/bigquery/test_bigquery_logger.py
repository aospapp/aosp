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
"""Tests for bigquery_logger."""

import acts.controllers.buds_lib.data_storage.bigquery.bigquery_logger

logger = bigquery_logger.BigqueryLogger(dataset_id='test', table_id='test')


def test_with_block():
    with bigquery_logger.BigqueryLogger('with_block_test',
                                        'test_table') as log:
        log.add_row({'NEW': 'nice', 'FIELD6': 3.0, 'noodle': 3})
        log.add_row({'FIELD2': 12, 'FIELD3': True, 'SUPERNEW': 'stroong'})
