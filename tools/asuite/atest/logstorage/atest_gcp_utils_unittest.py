#!/usr/bin/env python3
#
# Copyright 2021, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Unittests for atest_gcp_utils."""

import os
import tempfile
import unittest

from unittest import mock

import constants

from logstorage import atest_gcp_utils

class AtestGcpUtilsUnittests(unittest.TestCase):
    """Unit tests for atest_gcp_utils.py"""

    @mock.patch.object(atest_gcp_utils, '_prepare_data')
    @mock.patch.object(atest_gcp_utils, 'request_consent_of_upload_test_result')
    def test_do_upload_flow(self, mock_request, mock_prepare):
        """test do_upload_flow method."""
        fake_extra_args = {}
        fake_creds = mock.Mock()
        fake_creds.token_response = {'access_token': 'fake_token'}
        mock_request.return_value = fake_creds
        fake_inv = {'invocationId': 'inv_id'}
        fake_workunit = {'id': 'workunit_id'}
        fake_local_build_id = 'L1234567'
        fake_build_target = 'build_target'
        mock_prepare.return_value = (fake_inv, fake_workunit,
                                     fake_local_build_id, fake_build_target)
        constants.TOKEN_FILE_PATH = tempfile.NamedTemporaryFile().name
        creds, inv = atest_gcp_utils.do_upload_flow(fake_extra_args)
        self.assertEqual(fake_creds, creds)
        self.assertEqual(fake_inv, inv)
        self.assertEqual(fake_extra_args[constants.INVOCATION_ID],
                         fake_inv['invocationId'])
        self.assertEqual(fake_extra_args[constants.WORKUNIT_ID],
                         fake_workunit['id'])
        self.assertEqual(fake_extra_args[constants.LOCAL_BUILD_ID],
                         fake_local_build_id)
        self.assertEqual(fake_extra_args[constants.BUILD_TARGET],
                         fake_build_target)

        mock_request.return_value = None
        creds, inv = atest_gcp_utils.do_upload_flow(fake_extra_args)
        self.assertEqual(None, creds)
        self.assertEqual(None, inv)

    @mock.patch.object(atest_gcp_utils.GCPHelper,
    'get_credential_with_auth_flow')
    @mock.patch('builtins.input')
    def test_request_consent_of_upload_test_result_yes(
        self, mock_input, mock_get_credential_with_auth_flow):
        """test request_consent_of_upload_test_result method."""
        constants.CREDENTIAL_FILE_NAME = 'cred_file'
        constants.GCP_ACCESS_TOKEN = 'access_token'
        tmp_folder = tempfile.mkdtemp()
        mock_input.return_value = 'Y'
        not_upload_file = os.path.join(tmp_folder,
                                       constants.DO_NOT_UPLOAD)

        atest_gcp_utils.request_consent_of_upload_test_result(tmp_folder, True)
        self.assertEqual(1, mock_get_credential_with_auth_flow.call_count)
        self.assertFalse(os.path.exists(not_upload_file))

        atest_gcp_utils.request_consent_of_upload_test_result(tmp_folder, True)
        self.assertEqual(2, mock_get_credential_with_auth_flow.call_count)
        self.assertFalse(os.path.exists(not_upload_file))

    @mock.patch.object(atest_gcp_utils.GCPHelper,
                       'get_credential_with_auth_flow')
    @mock.patch('builtins.input')
    def test_request_consent_of_upload_test_result_no(
        self, mock_input, mock_get_credential_with_auth_flow):
        """test request_consent_of_upload_test_result method."""
        mock_input.return_value = 'N'
        constants.CREDENTIAL_FILE_NAME = 'cred_file'
        constants.GCP_ACCESS_TOKEN = 'access_token'
        tmp_folder = tempfile.mkdtemp()
        not_upload_file = os.path.join(tmp_folder,
                                       constants.DO_NOT_UPLOAD)

        atest_gcp_utils.request_consent_of_upload_test_result(tmp_folder, True)
        self.assertTrue(os.path.exists(not_upload_file))
        self.assertEqual(0, mock_get_credential_with_auth_flow.call_count)
        atest_gcp_utils.request_consent_of_upload_test_result(tmp_folder, True)
        self.assertEqual(0, mock_get_credential_with_auth_flow.call_count)
