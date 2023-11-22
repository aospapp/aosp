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

from google.api_core.exceptions import NotFound
from google.cloud import bigquery
from mock import patch, Mock

import acts.controllers.buds_lib.data_storage.bigquery.bigquery_logger_utils as utils

_TIMESTAMP_STR_FORMAT = '%Y-%m-%d %H:%M:%S'


def test_make_storable():
    to_make_storable = ['one', 1, 1.0, True, [1]]
    targets = ['one', 1, 1.0, True, str([1])]
    assert [utils.make_storeable(item) for item in to_make_storable] == targets


def test_get_field_name():
    bad_names = [
        'all_lowercase', 'b@d<h4r^c7=r$', '5tarts_with_digit', '_underscore',
        '', 'hyphen-name'
    ]
    targets = [
        'ALL_LOWERCASE', 'BDH4RC7R', 'FIELD_5TARTS_WITH_DIGIT', '_UNDERSCORE',
        'FIELD', 'HYPHENNAME'
    ]
    assert [utils.get_field_name(item) for item in bad_names] == targets


def test_get_bigquery_type():
    items = ['one', '2017-11-03 12:30:00', 1, 1.0, True, utils]
    targets = ['STRING', 'TIMESTAMP', 'INTEGER', 'FLOAT', 'BOOLEAN', 'STRING']
    assert [utils.get_bigquery_type(item) for item in items] == targets


def test_add_row():
    row_list = []
    utils.add_row({'int': 500, 'list': [1, 2, 3], 'float': 5.0}, row_list)
    assert set(row_list[0].items()) == set({
        'INT': 500,
        'LIST': '[1, 2, 3]',
        'FLOAT': 5.0
    }.items())
    utils.add_row({'int': 12, 'time': '2011-12-13 10:00:00'}, row_list)
    assert set(row_list[1].items()) == set({
        'INT': 12,
        'TIME': '2011-12-13 10:00:00'
    }.items())
    utils.add_row({'1string': '1'}, row_list)
    assert set(row_list[2].items()) == set({'FIELD_1STRING': '1'}.items())


def test_change_field_name():
    row_list = [{
        'FIELD1': None,
        'FIELD2': 300,
        'FIELD3': True
    }, {
        'FIELD1': 'a string',
        'FIELD2': 300,
        'FIELD4': False
    }, {
        'FIELD1': 'another string',
        'FIELD3': True,
        'FIELD4': False
    }]
    num_replacements = utils.change_field_name('field1', 'new_name', row_list)
    assert num_replacements == 3
    assert set(row_list[0].items()) == set({
        'NEW_NAME': None,
        'FIELD2': 300,
        'FIELD3': True
    }.items())
    assert set(row_list[1].items()) == set({
        'NEW_NAME': 'a string',
        'FIELD2': 300,
        'FIELD4': False
    }.items())
    assert set(row_list[2].items()) == set({
        'NEW_NAME': 'another string',
        'FIELD3': True,
        'FIELD4': False
    }.items())
    num_replacements = utils.change_field_name('field2', 'new_name2', row_list)
    assert num_replacements == 2
    assert set(row_list[0].items()) == set({
        'NEW_NAME': None,
        'NEW_NAME2': 300,
        'FIELD3': True
    }.items())
    assert set(row_list[1].items()) == set({
        'NEW_NAME': 'a string',
        'NEW_NAME2': 300,
        'FIELD4': False
    }.items())
    assert set(row_list[2].items()) == set({
        'NEW_NAME': 'another string',
        'FIELD3': True,
        'FIELD4': False
    }.items())
    num_replacements = utils.change_field_name('field5', 'new_name3', row_list)
    assert num_replacements == 0
    assert set(row_list[0].items()) == set({
        'NEW_NAME': None,
        'NEW_NAME2': 300,
        'FIELD3': True
    }.items())
    assert set(row_list[1].items()) == set({
        'NEW_NAME': 'a string',
        'NEW_NAME2': 300,
        'FIELD4': False
    }.items())
    assert set(row_list[2].items()) == set({
        'NEW_NAME': 'another string',
        'FIELD3': True,
        'FIELD4': False
    }.items())


def test_get_schema_from_dict():
    dict = {'FIELD': 'STRING', 'IELD': 'BOOLEAN', 'ELD': 'TIMESTAMP'}
    target = [
        bigquery.SchemaField('ELD', 'TIMESTAMP', mode='nullable'),
        bigquery.SchemaField('FIELD', 'STRING', mode='nullable'),
        bigquery.SchemaField('IELD', 'BOOLEAN', mode='nullable')
    ]
    assert utils.get_schema_from_dict(dict) == target


def test_get_dict_from_schema():
    schema = [
        bigquery.SchemaField('a_float'.upper(), 'FLOAT'),
        bigquery.SchemaField('an_int'.upper(), 'INTEGER'),
        bigquery.SchemaField('a_string'.upper(), 'STRING'),
        bigquery.SchemaField('a_timestamp'.upper(), 'TIMESTAMP'),
        bigquery.SchemaField('a_boolean'.upper(), 'BOOLEAN'),
        bigquery.SchemaField('unknown'.upper(), 'STRING')
    ]

    dictionary = {
        'a_float'.upper(): 'FLOAT',
        'an_int'.upper(): 'INTEGER',
        'a_string'.upper(): 'STRING',
        'a_timestamp'.upper(): 'TIMESTAMP',
        'a_boolean'.upper(): 'BOOLEAN',
        'unknown'.upper(): 'STRING'
    }

    assert dictionary.items() == utils.get_dict_from_schema(schema).items()


def test_reconcile_schema_differences():
    schema_to_change = {
        'FIELD1': 'TIMESTAMP',
        'FIELD2': 'INTEGER',
        'FIELD3': 'FLOAT',
        'FIELD4': 'STRING',
        'FIELD5': 'BOOLEAN',
        'FIELD6': 'STRING'
    }
    schema_to_preserve = {
        'FIELD1': 'TIMESTAMP',
        'FIELD2': 'FLOAT',
        'FIELD3_FLOAT': 'TIMESTAMP',
        'FIELD3': 'BOOLEAN',
        'FIELD5': 'TIMESTAMP',
        'FIELD7': 'TIMESTAMP'
    }
    target_schema = {
        'FIELD1': 'TIMESTAMP',
        'FIELD2': 'FLOAT',
        'FIELD2_INTEGER': 'INTEGER',
        'FIELD3': 'BOOLEAN',
        'FIELD3_FLOAT': 'TIMESTAMP',
        'FIELD3_FLOAT1': 'FLOAT',
        'FIELD4': 'STRING',
        'FIELD5': 'TIMESTAMP',
        'FIELD5_BOOLEAN': 'BOOLEAN',
        'FIELD6': 'STRING',
        'FIELD7': 'TIMESTAMP'
    }
    assert utils.reconcile_schema_differences(
        schema_to_change,
        schema_to_preserve)[0].items() == target_schema.items()


def test_get_tuple_from_schema():
    schema = [
        bigquery.SchemaField('FIELD1', 'BOOLEAN', mode='nullable'),
        bigquery.SchemaField('FIELD2', 'INTEGER', mode='nullable'),
        bigquery.SchemaField('FIELD3', 'STRING', mode='nullable'),
        bigquery.SchemaField('FIELD4', 'TIMESTAMP', mode='nullable'),
        bigquery.SchemaField('FIELD5', 'FLOAT', mode='nullable')
    ]
    target = ('FIELD1', 'FIELD2', 'FIELD3', 'FIELD4', 'FIELD5')
    assert utils.get_tuple_from_schema(schema) == target


def test_get_schema_from_rows_list():
    row_list = [{
        'FIELD1': None,
        'FIELD2': 300,
        'FIELD3': True
    }, {
        'FIELD1': 'a string',
        'FIELD2': 300.0,
        'FIELD4': False
    }, {
        'FIELD1': 'another string',
        'FIELD3': True,
        'FIELD4': False
    }]
    schema = [
        bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'),
        bigquery.SchemaField('FIELD2', 'STRING', mode='nullable'),
        bigquery.SchemaField('FIELD3', 'BOOLEAN', mode='nullable'),
        bigquery.SchemaField('FIELD4', 'BOOLEAN', mode='nullable')
    ]
    assert utils.get_schema_from_rows_list(row_list) == schema


def test_get_formatted_rows():
    row_list = [{
        'FIELD1': None,
        'FIELD2': 300,
        'FIELD3': True
    }, {
        'FIELD1': 'a string',
        'FIELD2': 300.0,
        'FIELD4': False
    }, {
        'FIELD1': 'another string',
        'FIELD3': True,
        'FIELD4': False
    }]
    schema = (bigquery.SchemaField('FIELD5', 'TIMESTAMP', mode='nullable'),
              bigquery.SchemaField('FIELD4', 'BOOLEAN', mode='nullable'),
              bigquery.SchemaField('FIELD3.5', 'INTEGER', mode='nullable'),
              bigquery.SchemaField('FIELD3', 'BOOLEAN', mode='nullable'),
              bigquery.SchemaField('FIELD2', 'STRING', mode='nullable'),
              bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'))
    target = [(None, None, None, True, 300, None), (None, False, None, None,
                                                    300.0, 'a string'),
              (None, False, None, True, None, 'another string')]
    assert utils.get_formatted_rows(row_list, schema) == target


class Client:
    def get_dataset(self, name):
        if name == 'existing_dataset':
            return Dataset(name)
        else:
            raise NotFound('')

    def create_dataset(self, dataset):
        return dataset

    def dataset(self, name):
        return name

    def delete_dataset(self, dataset):
        return 'deleted dataset ' + dataset.name

    def get_table(self, name):
        if name == 'existing_table':
            return Table(name, [])
        else:
            raise NotFound('')

    def create_table(self, table):
        return table

    def update_table(self, table, properties):
        return Table(table.name + '_changed', table.schema)

    def delete_table(self, table):
        return 'deleted table ' + table.name

    def create_rows(self, table, rows):
        if table.name == 'bad_table':
            return ['errors']
        return []


class Dataset:
    def __init__(self, name):
        self.name = name

    def __eq__(self, other):
        return self.name == other.name

    def table(self, name):
        return name


class Table:
    def __init__(self, name, schema):
        self.name = name
        self.schema = schema

    def __eq__(self, other):
        return self.name == other.name and set(self.schema) == set(
            other.schema)

    def __str__(self):
        return 'NAME: %s\nSCHEMA: %s' % (self.name, str(self.schema))


@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Dataset')
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Client')
def test_create_dataset_already_exists(mock_client, mock_dataset):
    mock_client.return_value = Client()
    client = utils.BigqueryLoggerClient('', '')
    dataset = client.create_dataset('existing_dataset')
    assert dataset == Dataset('existing_dataset')


@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Dataset')
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Client')
def test_create_dataset_does_not_exist(mock_client, mock_dataset):
    mock_client.return_value = Client()
    mock_dataset.return_value = Dataset('new_dataset')
    client = utils.BigqueryLoggerClient('', '')
    dataset = client.create_dataset('new_dataset')
    assert dataset == Dataset('new_dataset')


@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Table')
@patch(
    'infra.data_storage.bigquery.bigquery_logger_utils.BigqueryLoggerClient.create_dataset'
)
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Client')
def test_create_table_already_exists(mock_client, mock_dataset, mock_table):
    mock_client.return_value = Client()
    mock_dataset.return_value = Dataset('existing_dataset')
    schema = {
        bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'),
        bigquery.SchemaField('FIELD2', 'BOOLEAN', mode='nullable'),
        bigquery.SchemaField('FIELD3', 'TIMESTAMP', mode='nullable')
    }
    mock_table.return_value = Table('existing_table', schema)
    client = utils.BigqueryLoggerClient('', '')
    table = client.create_table('existing_dataset', 'existing_table', schema)
    assert table == Table('existing_table', [])


@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Table')
@patch(
    'infra.data_storage.bigquery.bigquery_logger_utils.BigqueryLoggerClient.create_dataset'
)
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Client')
def test_create_table_does_not_exist(mock_client, mock_dataset, mock_table):
    mock_client.return_value = Client()
    mock_dataset.return_value = Dataset('existing_dataset')
    schema = {
        bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'),
        bigquery.SchemaField('FIELD2', 'BOOLEAN', mode='nullable'),
        bigquery.SchemaField('FIELD3', 'TIMESTAMP', mode='nullable')
    }
    mock_table.return_value = Table('new_table', schema)
    client = utils.BigqueryLoggerClient('', '')
    table = client.create_table('existing_dataset', 'new_table', schema)
    assert table == Table('new_table', schema)


@patch(
    'infra.data_storage.bigquery.bigquery_logger_utils.BigqueryLoggerClient.create_table'
)
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Client')
def test_update_table_schema(mock_client, mock_table):
    mock_client.return_value = Client()
    schema = {
        bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'),
        bigquery.SchemaField('FIELD2', 'BOOLEAN', mode='nullable'),
        bigquery.SchemaField('FIELD3', 'TIMESTAMP', mode='nullable')
    }
    mock_table.return_value = Table('existing_table', schema)
    new_schema = {
        bigquery.SchemaField('FIELD1', 'INTEGER', mode='nullable'),
        bigquery.SchemaField('FIELD2', 'BOOLEAN', mode='nullable'),
        bigquery.SchemaField('FIELD5', 'FLOAT', mode='nullable')
    }
    client = utils.BigqueryLoggerClient('', '')
    table, changed_fields = client.update_table_schema(
        'existing_dataset', 'existing_table', new_schema)
    print(table)
    assert table == Table(
        'existing_table_changed', {
            bigquery.SchemaField('FIELD1_INTEGER', 'INTEGER', mode='nullable'),
            bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'),
            bigquery.SchemaField('FIELD2', 'BOOLEAN', mode='nullable'),
            bigquery.SchemaField('FIELD3', 'TIMESTAMP', mode='nullable'),
            bigquery.SchemaField('FIELD5', 'FLOAT', mode='nullable')
        })
    assert set(changed_fields.items()) == set({
        'FIELD1': 'FIELD1_INTEGER'
    }.items())


@patch(
    'infra.data_storage.bigquery.bigquery_logger_utils.BigqueryLoggerClient.create_table'
)
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Client')
def test_update_table_schema_no_change(mock_client, mock_table):
    mock_client.return_value = Client()
    schema = {
        bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'),
        bigquery.SchemaField('FIELD2', 'BOOLEAN', mode='nullable'),
        bigquery.SchemaField('FIELD3', 'TIMESTAMP', mode='nullable')
    }
    mock_table.return_value = Table('existing_table', schema)
    new_schema = {
        bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'),
        bigquery.SchemaField('FIELD2', 'BOOLEAN', mode='nullable')
    }
    client = utils.BigqueryLoggerClient('', '')
    table, changed_fields = client.update_table_schema(
        'existing_dataset', 'existing_table', new_schema)
    print(table)
    assert table == Table(
        'existing_table', {
            bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'),
            bigquery.SchemaField('FIELD2', 'BOOLEAN', mode='nullable'),
            bigquery.SchemaField('FIELD3', 'TIMESTAMP', mode='nullable')
        })
    assert set(changed_fields.items()) == set({}.items())


@patch('infra.data_storage.bigquery.test_bigquery_utils.Client.delete_dataset')
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Dataset')
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Client')
def test_delete_dataset(mock_client, mock_dataset, mock_delete_dataset):
    mock_client.return_value = Client()
    ds = Dataset('existing_dataset')
    mock_dataset.return_value = ds
    client = utils.BigqueryLoggerClient('', '')
    client.delete('existing_dataset')
    mock_delete_dataset.assert_called_with(ds)


@patch('infra.data_storage.bigquery.test_bigquery_utils.Client.delete_table')
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Table')
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Dataset')
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Client')
def test_delete_dataset(mock_client, mock_dataset, mock_table,
                        mock_delete_table):
    mock_client.return_value = Client()
    schema = {
        bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'),
        bigquery.SchemaField('FIELD2', 'BOOLEAN', mode='nullable'),
        bigquery.SchemaField('FIELD3', 'TIMESTAMP', mode='nullable')
    }
    tb = Table('existing_table', schema)
    mock_table.return_value = tb
    client = utils.BigqueryLoggerClient('', '')
    client.delete('existing_dataset', 'existing_table')
    mock_delete_table.assert_called_with(tb)


@patch('infra.data_storage.bigquery.test_bigquery_utils.Client.create_rows')
@patch(
    'infra.data_storage.bigquery.test_bigquery_utils.utils.get_schema_from_rows_list'
)
@patch(
    'infra.data_storage.bigquery.test_bigquery_utils.utils.change_field_name')
@patch(
    'infra.data_storage.bigquery.test_bigquery_utils.utils.BigqueryLoggerClient.update_table_schema'
)
@patch('infra.data_storage.bigquery.bigquery_logger_utils.bigquery.Client')
def test_flush(mock_client, mock_update_table_schema, mock_change_field_name,
               mock_get_schema, mock_create_rows):
    mock_create_rows.return_value = []
    mock_client.return_value = Client()
    schema = {
        bigquery.SchemaField('FIELD1', 'STRING', mode='nullable'),
        bigquery.SchemaField('FIELD2', 'BOOLEAN', mode='nullable'),
        bigquery.SchemaField('FIELD3', 'TIMESTAMP', mode='nullable')
    }
    tb = Table('existing_table', schema)
    mock_update_table_schema.return_value = tb, {'FIELD1': 'NEW_NAME1'}
    row_list = [{
        'FIELD1': 1,
        'FIELD2': False,
        'FIELD3': 'result'
    }, {
        'FIELD1': 2,
        'FIELD2': True
    }, {
        'FIELD1': 3,
        'FIELD3': 'result'
    }]
    client = utils.BigqueryLoggerClient('', '')
    errors = client.flush(row_list, 'existing_dataset', 'existing_table')
    mock_change_field_name.assert_called_with('FIELD1', 'NEW_NAME1', row_list)
    mock_create_rows.assert_called_once()
    assert errors == []
