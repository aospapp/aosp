#!/usr/bin/python
# -*- coding: utf-8 -*-
# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Tests for gs_cache_client."""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import itertools
import json
import requests
import unittest

import mock

import common
from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import gs_cache_client


# pylint: disable=unused-argument
class ApiTest(unittest.TestCase):
    """Test class for _GsCacheAPI."""
    def setUp(self):
        self.api = gs_cache_client._GsCacheAPI('localhost')

    def test_extract_via_ssh(self):
        """Test extracting via ssh."""
        gs_cache_client._USE_SSH_CONNECTION = True
        self.api._is_in_restricted_subnet = True
        with mock.patch('autotest_lib.client.common_lib.utils.run') as m:
            m.return_value.stdout = '{}'
            result = self.api.extract(
                    gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET, 'archive',
                    'file')
            self.assertEqual(result, {})

    def test_extract_via_http(self):
        """Test extracting via http."""
        with mock.patch('requests.get') as m:
            m.return_value = mock.MagicMock(ok=True, content='{}')
            result = self.api.extract(
                    gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET, 'archive',
                    'file')
            self.assertEqual(result, {})

    def test_extract_many_files_via_http(self):
        """Test extracting many files via http."""
        with mock.patch('requests.get') as m:
            m.return_value = mock.MagicMock(ok=True, content='{}')
            result = self.api.extract(
                    gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET, 'archive',
                    ['the_file'] * 1000)
            self.assertEqual(result, {})
            self.assertTrue(m.call_count > 1)

    @mock.patch('time.sleep')
    @mock.patch('time.time', side_effect=itertools.cycle([0, 400]))
    def test_extract_via_ssh_has_error(self, *args):
        """Test extracting via ssh when has errors."""
        gs_cache_client._USE_SSH_CONNECTION = True
        self.api._is_in_restricted_subnet = True
        with mock.patch('autotest_lib.client.common_lib.utils.run') as m:
            m.side_effect = error.CmdError('cmd', mock.MagicMock(exit_status=1))
            with self.assertRaises(gs_cache_client.CommunicationError):
                self.api.extract(gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET,
                                 'archive', 'file')

            m.side_effect = error.CmdError('cmd', mock.MagicMock(
                    exit_status=gs_cache_client._CURL_RC_CANNOT_CONNECT_TO_HOST)
            )
            with self.assertRaises(gs_cache_client.NoGsCacheServerError):
                self.api.extract(gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET,
                                 'archive', 'file')

            m.side_effect = None
            m.return_value.stdout = '...'
            with self.assertRaises(gs_cache_client.ResponseContentError):
                self.api.extract(gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET,
                                 'archive', 'file')

    @mock.patch('time.sleep')
    @mock.patch('time.time', side_effect=itertools.cycle([0, 400]))
    def test_extract_via_http_has_error(self, *args):
        """Test extracting via http when has errors."""
        with mock.patch('requests.get') as m:
            m.return_value = mock.MagicMock(ok=False)
            with self.assertRaises(gs_cache_client.CommunicationError):
                self.api.extract(gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET,
                                 'archive', 'file')

            m.return_value = mock.MagicMock(ok=True, content='...')
            with self.assertRaises(gs_cache_client.ResponseContentError):
                self.api.extract(gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET,
                                 'archive', 'file')

            m.side_effect = requests.ConnectionError('Gs Cache is not running.')
            with self.assertRaises(gs_cache_client.NoGsCacheServerError):
                self.api.extract(gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET,
                                 'archive', 'file')


class ClientTest(unittest.TestCase):
    """Test class for GsCacheClient."""
    def setUp(self):
        self.api = mock.MagicMock(server_netloc='api_netloc')
        self.dev_server = mock.MagicMock()
        self.client = gs_cache_client.GsCacheClient(self.dev_server, self.api)

    def test_list_suite_controls_in_map(self):
        """Test list controls of a suite in map file."""
        build = 'release/build'
        suite = 'suite'
        control_files = ['suite_control', 'control.1']

        map_file_name = 'autotest/test_suites/suite_to_control_file_map'
        self.api.extract.return_value = {
                map_file_name: json.dumps({suite: control_files})}
        self.client.list_suite_controls(build, suite)

        expected_calls = [
                mock.call(gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET,
                          build + '/test_suites.tar.bz2', [map_file_name]),
                mock.call(gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET,
                          build + '/control_files.tar',
                          ['autotest/' + c for c in control_files])
        ]
        self.assertListEqual(self.api.extract.call_args_list, expected_calls)

    def test_list_suite_controls_not_in_map(self):
        """Test list controls of a suite not in map file."""
        build = 'release/build'
        suite = 'suite'
        map_file_name = 'autotest/test_suites/suite_to_control_file_map'

        self.api.extract.return_value = {map_file_name: json.dumps({})}
        self.client.list_suite_controls(build, suite)

        expected_calls = [
                mock.call(gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET,
                          build + '/test_suites.tar.bz2', [map_file_name]),
                mock.call(gs_cache_client._CROS_IMAGE_ARCHIVE_BUCKET,
                          build + '/control_files.tar',
                          ['*/control', '*/control.*'])
        ]
        self.assertListEqual(self.api.extract.call_args_list, expected_calls)

    def test_fall_back_to_dev_server(self):
        """Test falling back to calls of dev_server."""
        self.client._list_suite_controls = mock.MagicMock(
                side_effect=gs_cache_client.CommunicationError()
        )
        self.client.list_suite_controls('build', 'suite')
        self.dev_server.list_suite_controls.assert_called()

    def test_fall_back_to_dev_server_on_content_error(self):
        """Test falling back to calls of dev_server on wrong content."""
        self.api.extract.return_value = {k: 'xx' for k in range(999)}
        self.client.list_suite_controls('build', 'suite')
        self.dev_server.list_suite_controls.assert_called()


if __name__ == '__main__':
    unittest.main()
