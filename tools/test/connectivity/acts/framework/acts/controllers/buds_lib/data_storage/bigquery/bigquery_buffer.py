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
"""Simple buffer interface that sends rows to specified tables in wearables-qa project in BigQuery."""
import acts.controllers.buds_lib.data_storage.bigquery.bigquery_logger_utils as bq_utils
import os
import time
import yaml

CONFIG = 'config.yml'
PATH_TO_CONFIG = os.path.join(os.path.dirname(__file__), CONFIG)

queue = None


class BigqueryBufferError(Exception):
    """To be thrown if data storage queue malfunctions or cannot be reached"""


class BigQueryProcessManager:
    def __init__(self, config_path):
        self.config_path = config_path
        self.ip_address = None
        self.port = None
        self.load_config()

    def load_config(self):
        config = yaml.load(open(self.config_path, 'r'))
        new_ip_address = config['ip_address']
        new_port = config['port']
        new_queue_size = config['queue_size']
        new_authkey = config['authkey']
        if new_ip_address == self.ip_address and new_port == self.port:
            if new_authkey != self.authkey or new_queue_size != self.queue_size:
                raise BigqueryBufferError(
                    'To change queue size or server authkey, choose an unused port for a new server.'
                )
        self.project_id = config['project_id']
        self.credentials_path = config['credentials_path']
        self.queue_size = config['queue_size']
        self.ip_address = config['ip_address']
        self.port = config['port']
        self.authkey = config['authkey']
        self.flush_period = config['flush_period']

    def start_subprocesses(self):
        old_server_pid, old_queue = None, None

        if not self.server_pid():
            try:
                # check if a BigqueryLoggerQueue currently exists but with different args
                old_server_pid, old_queue = bq_utils.get_current_queue_and_server_pid(
                )
            except TypeError:
                pass

            # Start server to initialize new shared BigqueryLoggerQueue
            bq_utils.start_queue_server(
                queue_size=self.queue_size,
                ip_address=self.ip_address,
                port=self.port,
                authkey=self.authkey)
            time.sleep(5)

        # Retrieve proxy object for new shared BigqueryLoggerQueue
        global queue
        queue = bq_utils.get_queue(
            ip_address=self.ip_address, port=self.port, authkey=self.authkey)

        if queue:

            if old_queue and old_server_pid:  # If and older queue exists, transfer its items to new one
                while not old_queue.empty():
                    queue.put(old_queue.get())
                bq_utils.kill_pid(old_server_pid)

            # noinspection PyUnresolvedReferences
            queue.set_flush_period(self.flush_period)

            # noinspection PyUnresolvedReferences
            if not self.automatic_logger_pid():
                bq_utils.kill_current_scheduled_automatic_logger()

                bq_utils.start_scheduled_automatic_logger(
                    ip_address=self.ip_address,
                    port=self.port,
                    authkey=self.authkey,
                    project_id=self.project_id,
                    credentials_path=self.credentials_path)

        if self.server_pid() and self.automatic_logger_pid():
            return True

        return False

    def automatic_logger_pid(self):
        return bq_utils.get_scheduled_automatic_logger_pid(
            ip_address=self.ip_address,
            port=self.port,
            authkey=self.authkey,
            project_id=self.project_id,
            credentials_path=self.credentials_path)

    def server_pid(self):
        return bq_utils.get_logger_server_pid(
            queue_size=self.queue_size,
            ip_address=self.ip_address,
            port=self.port,
            authkey=self.authkey)


process_manager = BigQueryProcessManager(PATH_TO_CONFIG)


def log(dataset_id, table_id, row_dict):
    """Sends a row dict to be flushed to a table in BigQuery.

  Arguments:
    dataset_id: dataset in which table resides.
    table_id: table to update with row.
    row_dict: dictionary for field: value pairs to send to table.
  """
    global queue

    try:
        process_manager.load_config()
    except BigqueryBufferError as e:
        print(e.message)
        subprocesses_started = True
    else:
        subprocesses_started = process_manager.start_subprocesses()

    if not subprocesses_started:
        raise BigqueryBufferError('Could not start subprocesses')
    if queue:
        try:
            # noinspection PyUnresolvedReferences
            queue.add_row(dataset_id, table_id, row_dict)
        except EOFError:
            raise BigqueryBufferError(
                'Could not push data to storage queue (EOFError)')
    else:
        raise BigqueryBufferError('No data queue exists to push data to...')
