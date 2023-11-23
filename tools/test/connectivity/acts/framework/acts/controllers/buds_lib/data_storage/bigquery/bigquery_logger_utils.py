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

import logging
from datetime import datetime
import inspect
import os
import socket
import string
import subprocess
import time
import yaml
from multiprocessing.managers import BaseManager

from google.api_core.exceptions import NotFound
from google.cloud import bigquery

_TIMESTAMP_STR_FORMAT = '%Y-%m-%d %H:%M:%S'
_AUTOMATIC_LOGGER_SCRIPT = 'bigquery_scheduled_automatic_client.py'
_SERVER_SCRIPT = 'bigquery_logger_server.py'


def load_config(config_file_path):
    with open(config_file_path, 'r') as f:
        config = yaml.load(f)
    return config


class BigQueryLoggerUtilsError(Exception):
    """Exception class for bigquery logger utils module"""


#################################
# Data transformation and preparation methods
#################################


def make_storeable(value):
    """Casts non primitive data types to string.

  Certain data types such as list can cause unexpected behavior with BigQuery.

  Arguments:
    value: an object to store in a BigQuery table.
  Returns:
    value or str(value): string version of passed value, if necessary.
  """
    if (isinstance(value, int) or isinstance(value, float)
            or isinstance(value, str) or isinstance(value, bool)):
        return value
    elif isinstance(value, datetime):
        return value.strftime(_TIMESTAMP_STR_FORMAT)
    return str(value)


def get_field_name(dirty_string):
    """Converts field name to a BigQuery acceptable field name.

  Arguments:
    dirty_string: the string to convert to a standardized field name.
  Returns:
    field_name: the field name as a string.
  """
    valid_chars = '_ %s%s' % (string.ascii_letters, string.digits)
    field_name = ''.join(c for c in dirty_string.upper() if c in valid_chars)
    field_name = field_name.strip().replace(' ', '_')
    if not field_name:
        field_name = 'FIELD'
    elif field_name[0] not in string.ascii_letters + '_':
        field_name = 'FIELD_' + field_name
    return field_name


def get_bigquery_type(value):
    """Returns BigQuery recognizable datatype string from value.

  Arguments:
    value: the item you want to store in BigQuery
  Returns:
    field_type: the BigQuery data type for the field to store your value.
  """
    # Dict for converting Python types to BigQuery recognizable schema fields
    field_name = {
        'STR': 'STRING',
        'INT': 'INTEGER',
        'FLOAT': 'FLOAT',
        'BOOL': 'BOOLEAN'
    }

    # Default field type is STRING
    field_type = 'STRING'
    if isinstance(value, str):
        try:
            # Try to infer whether datatype is a timestamp by converting it to
            # a timestamp object using the string format
            time.strptime(value, _TIMESTAMP_STR_FORMAT)
            field_type = 'TIMESTAMP'
        except ValueError:
            pass
    else:
        type_string = type(value).__name__
        try:
            field_type = field_name[type_string.upper()]
        except KeyError:
            logging.error('Datatype %s not recognized. Reverting to STRING.',
                          type_string)
    return field_type


def add_row(dictionary, row_list_to_update):
    # Convert dictionary key names to BigQuery field names
    to_add = {
        get_field_name(key): make_storeable(value)
        for key, value in dictionary.items()
    }

    row_list_to_update.append(to_add)


def change_field_name(old_name, new_name, row_list_to_update):
    """Changes field name in row_list_to_update in place.

  Arguments:
    old_name: the old field name, to be replaced.
    new_name: the new name to replace the old one.
    row_list_to_update: the list of row dictionaries to update the field name for
  Returns:
    num_replacements: how many rows were affected by this change.
  """
    old_name = get_field_name(old_name)
    new_name = get_field_name(new_name)
    num_replacements = 0
    for row in row_list_to_update:
        if old_name in row.keys():
            # Update all items in the rows with the new field name
            row[new_name] = row[old_name]
            del row[old_name]
            num_replacements += 1
    return num_replacements


def get_tuple_from_schema(schema):
    """Returns a tuple of all field names in the passed schema"""
    return tuple(field.name for field in schema)


def get_dict_from_schema(schema):
    """Turns a BigQuery schema array into a more flexible dictionary.

  Arguments:
    schema: the schema array to be converted.
  Returns:
    dictionary: a dictionary from the schema. Maps field names to field types.
  """
    dictionary = {
        schema_field.name: schema_field.field_type
        for schema_field in schema
    }
    return dictionary


def reconcile_schema_differences(schema_to_change_dict,
                                 schema_to_preserve_dict):
    """Returns a schema dict combining two schema dicts.

  If there are conflicts between the schemas, for example if they share a
  field name but those field names don't share the same type value, that field
  name in one of the schema dicts will have to change to be added to the
  combined schema.
  Arguments:
    schema_to_change_dict: a dict representing the schema that will be changed
    if a conflict arises.
    schema_to_preserve_dict: a dict representing the schema whose fields will
    remain unchanged.
  Returns:
    new_schema_dict: a dict representing the combined schemas
    changed_fields: a dict mapping old field names to their new field names,
    if they were changed, in schema_to_change_dict.
  """
    new_schema_dict = schema_to_preserve_dict.copy()
    changed_fields = {}
    for field_name, field_type in schema_to_change_dict.items():
        if field_name in schema_to_preserve_dict.keys():

            # Field name already exists in remote table, but it might not accept the
            # same value type the user is passing this time around
            if schema_to_preserve_dict[field_name] == field_type:

                # Same data type for fields, no need to do anything
                continue
            else:

                # We need to create a new field with a unique name to store this
                # different data type. Automatically makes new name:
                # FIELD_NAME_FIELD_TYPE, ex. 'RESULT_BOOLEAN'
                new_field_name = '%s_%s' % (field_name, field_type)

                # On the off chance that this new field name is also already taken, we
                # start appending numbers to it to make it unique. This should be an
                # extreme edge case, hence the inelegance.
                count = 1
                merged_schemas = schema_to_preserve_dict.copy()
                merged_schemas.update(schema_to_change_dict)
                if new_field_name in merged_schemas.keys(
                ) and merged_schemas[new_field_name] != field_type:
                    new_field_name += str(count)
                while new_field_name in merged_schemas.keys(
                ) and merged_schemas[new_field_name] != field_type:
                    count += 1
                    new_field_name = new_field_name[:-1] + str(count)

                # Update the actual rows in our logger as well as self.schema_dict to
                # reflect the new field name.
                changed_fields[field_name] = new_field_name

                new_schema_dict[new_field_name] = field_type

        else:
            new_schema_dict[field_name] = field_type

    return new_schema_dict, changed_fields


#################################
# BigQuery request data preparation methods
#################################


def get_schema_from_dict(dictionary):
    """Turns dictionary into a schema formatted for BigQuery requests.

  Arguments:
    dictionary: the dictionary to convert into a schema array.
  Returns:
    schema: an array of SchemaField objects specifying name and type, listed alphabetically.
  """
    schema = []
    for key in sorted(dictionary):
        schema.append(
            bigquery.SchemaField(key, dictionary[key], mode='nullable'))
    return schema


def get_schema_from_rows_list(rows_list):
    """Deduces the BigQuery table schema represented by a list of row dictionaries.

  Arguments:
    rows_list: the list of row dictionaries to create a schema from.
  Returns:
    schema: a formatted BigQuery table schema with the fields in alphabetical order."""
    schema = {}
    for row in rows_list:
        # Create new field names and corresponding types in self.schema_dict in case
        # the schema of the remote table needs to be updated.
        for key, value in row.items():
            value_type = get_bigquery_type(value)
            if key in schema.keys():
                # We have another row with the same field name. Most of the time their
                # types should match and we can just skip adding it to the fields to
                # update

                if value_type != schema[key]:
                    # Their types don't match. Merge the fields and change the type to
                    # string
                    schema[key] = 'STRING'

                    row[key] = str(row[key])
            else:
                schema[key] = value_type

    return get_schema_from_dict(schema)


def get_formatted_rows(rows_list, schema):
    """Returns an InsertEntry object for adding to BQ insert request.

  Arguments:
    rows_list: a list of row dictionaries to turn into tuples of values corresponding to the schema fields.
    schema: a tuple representing the column names in the table.
  Returns:
    rows: an array of tuples with the elements ordered corresponding to the order of the column names in schema.
  """
    rows = []
    schema_tuple = get_tuple_from_schema(schema)
    for row in rows_list:
        row_tuple = tuple(
            row[key] if key in row.keys() else None for key in schema_tuple)
        rows.append(row_tuple)
    return rows


#################################
#  BigQuery client class
#################################


class BigqueryLoggerClient:
    """Client class for interacting with and preparing data for BigQuery"""

    def __init__(self, project_id, google_application_credentials_path):
        os.environ[
            'GOOGLE_APPLICATION_CREDENTIALS'] = google_application_credentials_path
        self.client = bigquery.Client(project_id)

    #################################
    # BigQuery request methods
    #################################

    def create_dataset(self, dataset_id):
        """Creates a new dataset if it doesn't exist.

    Arguments:
      dataset_id: the name of the dataset you want to create.
    Returns:
      dataset: the resulting dataset object.
    """
        dataset_ref = self.client.dataset(dataset_id)
        dataset = bigquery.Dataset(dataset_ref)
        try:
            dataset = self.client.get_dataset(dataset_ref)
        except Exception as err:
            self.client.create_dataset(dataset)
        return dataset

    def create_table(self, dataset_id, table_id, schema):
        """Creates a new table if it doesn't exist.

    Arguments:
      dataset_id: the name of the dataset that will contain the table you want
      to create.
      table_id: the name of the table you want to create.
      schema: a schema array for the table to be created.
    Returns:
      table: the resulting table object
    """
        dataset = self.create_dataset(dataset_id)
        table_ref = dataset.table(table_id)
        table = bigquery.Table(table_ref, schema=schema)
        try:
            table = self.client.get_table(table_ref)
        except NotFound:
            self.client.create_table(table)
        return table

    def update_table_schema(self, dataset_id, table_id, new_schema):
        """Updates the schema for the given remote table.

    Uses fields specified in self.schema_dict. This method will never remove
    fields, to avoid loss of data.

    Arguments:
      dataset_id: the dataset containing the table to modify.
      table_id: the table to modify.
      new_schema: a new schema to update the remote table's schema with.
    Returns:
      table: the updated table object.
      changed_fields: a dictionary mapping any changed field names to their new name strings.
    """
        table = self.create_table(dataset_id, table_id, new_schema)
        remote_schema = table.schema
        remote_schema_dict = get_dict_from_schema(remote_schema)
        new_schema_dict = get_dict_from_schema(new_schema)

        updated_schema_dict, changed_fields = reconcile_schema_differences(
            new_schema_dict, remote_schema_dict)

        if updated_schema_dict.items() != remote_schema_dict.items():
            table.schema = get_schema_from_dict(updated_schema_dict)
            table = self.client.update_table(
                table=table, properties=['schema'])

        return table, changed_fields

    def delete(self, dataset_id, table_id=None):
        """Deletes specified table in specified dataset.

    Arguments:
      dataset_id: the name of the dataset to be deleted or the dataset that
      contains the table to be deleted.
      table_id: the name of the table to be deleted.
    """
        dataset_ref = self.client.dataset(dataset_id)
        dataset = bigquery.Dataset(dataset_ref)
        try:
            if table_id:
                table_ref = dataset.table(table_id)
                table = bigquery.Table(table_ref)
                self.client.delete_table(table)
            else:
                self.client.delete_dataset(dataset)
        except NotFound:
            pass

    def flush(self, rows_list, dataset_id, table_id, retries=5):
        """Inserts key value store of data into the specified table.

    Arguments:
      rows_list: a list of row dictionaries to send to BigQuery
      dataset_id: dataset name to store table in.
      table_id: table name to store info in.
      retries: how many times to retry insert upon failure
    Returns:
      erros: any errors resulting from the insert operation.
    Raises:
      DataNotStoredError: if data is not stored because of insertErrors in
      query response or timeout.
    """
        correctly_formatted_rows_list = []

        for row in rows_list:
            add_row(row, correctly_formatted_rows_list)

        local_schema = get_schema_from_rows_list(correctly_formatted_rows_list)
        table, changed_fields = self.update_table_schema(
            dataset_id, table_id, local_schema)

        if changed_fields:
            print('Changed Fields: ' + str(changed_fields))
            for old_name, new_name in changed_fields.items():
                change_field_name(old_name, new_name,
                                  correctly_formatted_rows_list)

        schema = table.schema

        values = get_formatted_rows(correctly_formatted_rows_list, schema)
        errors = self.client.create_rows(table, values)
        if errors:
            for retry in range(retries):
                print('Retry ' + str(retry + 1))
                time.sleep(30)
                errors = self.client.create_rows(table, values)
                if not errors:
                    break

        if errors:
            print(errors)
        return errors


####################
# Subprocess and helper methods to help with automated logger
####################


def start_queue_server(queue_size, ip_address, port, authkey):
    """Starts a subprocess bigquery_logger_server.py.
  Subprocess creates a server to handle the shared job queue.

  Arguments:
    queue_size: maximum number of items this queue can hold
    ip_address: ip address of the machine on which to start queue management server
    port: port on which to reach queue management server
    authkey: password to be used by clients trying to access server
  Returns:
    process: the result of Popen on the subprocess.
  """

    # If ip_address is empty string (signifying local machine) we need to have '' in the command so it is counted
    # as an actual argument to bigquery_logger_server
    ip_address = ip_address or '\'\''
    command = ' '.join([
        _SERVER_SCRIPT,
        str(queue_size),
        str(ip_address),
        str(port),
        str(authkey)
    ])
    # Create error log file for user to check
    error_log_name = os.path.join(
        os.path.dirname(__file__), 'queue_server_err.log')
    error_log = open(error_log_name, 'w+')
    process = subprocess.Popen(
        command,
        shell=True,
        stderr=error_log,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE)
    return process


def start_scheduled_automatic_logger(ip_address, port, authkey, project_id,
                                     credentials_path):
    """Starts a subprocess bigquery_scheduled_automatic_logger.
  Subprocess accesses the queue managed by the server at ip_address:port
  and periodically sends items in queue to the BigQuery project identified by project_id.

  Arguments:
    ip_address: ip_address of the machine on which the server managing the shared queue to pull from is located
    port: port on which the server managing the shared queue to pull from can be reached
    authkey: password needed to access server
    project_id: name of BigQuery project to send data to
    credentials_path: path to directory where Google Service Account credentials for this BigQuery
      project are stored
  Returns:
    process: the result of Popen on the subprocess.
  """

    # If ip_address is empty string (signifying local machine) we need to have '' in the command so it is counted
    # as an actual argument to bigquery_scheduled_automatic_logger
    ip_address = ip_address or '\'\''
    print('starting scheduled automatic logger...')
    command = ' '.join([
        _AUTOMATIC_LOGGER_SCRIPT,
        str(ip_address),
        str(port),
        str(authkey),
        str(project_id),
        str(credentials_path)
    ])
    # Create error log file for user to check
    error_log_name = os.path.join(
        os.path.dirname(__file__), 'scheduled_automatic_logger_err.log')
    error_log = open(error_log_name, 'w+')
    process = subprocess.Popen(
        command,
        shell=True,
        stderr=error_log,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE)
    return process


def get_queue(ip_address, port, authkey):
    """Returns a proxy object for shared queue.
  Shared queue is created and managed in start_server().

  Arguments:
    ip_address: ip_address of the machine on which the server managing the shared queue to proxy is located
    port: port on which the server managing the shared queue to proxy can be reached
    authkey: password needed to access server
  Returns:
    queue: the BigqueryLoggerQueue object that organizers and holds all BigQuery
      inserts sent to server."""
    BaseManager.register('get_queue')
    m = BaseManager(address=(ip_address, int(port)), authkey=authkey)
    try:
        m.connect()
        return m.get_queue()
    except socket.error:
        raise BigQueryLoggerUtilsError('Cannot connect to data storage queue.')


def get_current_scheduled_automatic_logger():
    """Returns process id and args of running scheduled automatic logger"""

    processes = get_processes(_AUTOMATIC_LOGGER_SCRIPT)

    pid = 0
    args = {}
    if processes:
        process = processes[0]
        pid = process[0]
        process_argspec = inspect.getargspec(start_scheduled_automatic_logger)
        process_arg_names = process_argspec.args
        process_argv = process[-1 * len(process_arg_names):]
        args = dict(zip(process_arg_names, process_argv))

    return pid, args


def get_current_logger_server():
    """Returns process id and args of running logger servers"""

    processes = get_processes(_SERVER_SCRIPT)

    pid = 0
    args = {}
    if processes:
        process = processes[0]
        pid = process[0]
        process_argspec = inspect.getargspec(start_queue_server)
        process_arg_names = process_argspec.args
        process_argv = process[-1 * len(process_arg_names):]
        args = dict(zip(process_arg_names, process_argv))

    return pid, args


def get_current_queue_and_server_pid():
    """Kills the current running queue server process.

  Returns:
    queue: the queue that the server used to serve.
  """

    pid, args = get_current_logger_server()
    get_queue_args = inspect.getargspec(get_queue).args
    if pid:
        try:
            kwargs = {arg_name: args[arg_name] for arg_name in get_queue_args}
        except KeyError:
            raise BigQueryLoggerUtilsError(
                'Param names in get_queue %s must be subset of param names for start_queue_server %s'
                % (get_queue_args, args.keys()))
        else:
            # Retrieve reference to current
            queue = get_queue(**kwargs)
            return pid, queue


def kill_current_scheduled_automatic_logger():
    pid, _ = get_current_scheduled_automatic_logger()
    if pid:
        kill_pid(pid)


def get_scheduled_automatic_logger_pid(ip_address, port, authkey, project_id,
                                       credentials_path):
    """Returns the process id of a bigquery_scheduled_automatic_logger instance for a given set of configs.

  Arguments:
    ip_address: ip_address of the machine on which the server managing the shared queue to pull from is located
    port: port on which the server managing the shared queue to pull from can be reached
    authkey: password needed to access server
    project_id: name of BigQuery project to send data to
    credentials_path: path to directory where Google Service Account credentials for this BigQuery
      project are stored
  Returns:
    pid: process id of process if found. Else 0
  """

    pids = get_pids(_AUTOMATIC_LOGGER_SCRIPT, ip_address, port, authkey,
                    project_id, os.path.expanduser(credentials_path))

    pid = 0
    if pids:
        pid = pids[0]
    return pid


def get_logger_server_pid(queue_size, ip_address, port, authkey):
    """Returns the process id of a bigquery_logger_service instance for a given set of configs.

  Arguments:
    queue_size: the size of the shared data queue
    ip_address: ip_address of the machine on which the server managing the shared queue to pull from is located
    port: port on which the server managing the shared queue to pull from can be reached
    authkey: password needed to access server
  Returns:
    pid: process id of process if found. Else 0
  """

    pids = get_pids(_SERVER_SCRIPT, queue_size, ip_address, port, authkey)
    pid = 0
    if pids:
        pid = pids[0]
    return pid


def get_pids(*argv):
    """Gets process ids based on arguments to concatenate and grep

  Arguments:
    *argv: any number of arguments to be joined and grepped
  Returns:
    pids: process ids of process if found.
  """
    processes = get_processes(*argv)
    pids = [process[0] for process in processes]

    return pids


def get_processes(*argv):
    """Returns process grepped by a set of arguments.

  Arguments:
    *argv: any number of arguments to be joined and grepped
  Returns:
    processes: processes returned by grep, as a list of lists.
  """
    expression = ' '.join([str(arg) for arg in argv])
    processes = []
    try:
        results = subprocess.check_output(
            'pgrep -af \"%s\"' % expression, shell=True)
        for result in results.split('\n'):
            items = result.split(' ')
            if 'pgrep' not in items:
                processes.append(items)
    except subprocess.CalledProcessError:
        pass

    return processes


def kill_pid(pid):
    """To only be used on _SERVER_SCRIPT or _AUTOMATIC_LOGGER_SCRIPT"""

    result = subprocess.check_output('kill -9 %s' % str(pid), shell=True)
    return result
