#  Copyright (C) 2020 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
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
"""
Utility functions for atest.
"""
from __future__ import print_function

import os
import logging
import uuid
try:
    import httplib2
except ModuleNotFoundError as e:
    logging.debug('Import error due to %s', e)

from pathlib import Path
import constants

from logstorage import logstorage_utils
try:
    # pylint: disable=import-error
    from oauth2client import client as oauth2_client
    from oauth2client.contrib import multistore_file
    from oauth2client import tools as oauth2_tools
except ModuleNotFoundError as e:
    logging.debug('Import error due to %s', e)

import atest_utils

class RunFlowFlags():
    """Flags for oauth2client.tools.run_flow."""
    def __init__(self, browser_auth):
        self.auth_host_port = [8080, 8090]
        self.auth_host_name = "localhost"
        self.logging_level = "ERROR"
        self.noauth_local_webserver = not browser_auth


class GCPHelper():
    """GCP bucket helper class."""
    def __init__(self, client_id=None, client_secret=None,
                 user_agent=None, scope=constants.SCOPE_BUILD_API_SCOPE):
        """Init stuff for GCPHelper class.
        Args:
            client_id: String, client id from the cloud project.
            client_secret: String, client secret for the client_id.
            user_agent: The user agent for the credential.
            scope: String, scopes separated by space.
        """
        self.client_id = client_id
        self.client_secret = client_secret
        self.user_agent = user_agent
        self.scope = scope

    def get_refreshed_credential_from_file(self, creds_file_path):
        """Get refreshed credential from file.
        Args:
            creds_file_path: Credential file path.
        Returns:
            An oauth2client.OAuth2Credentials instance.
        """
        credential = self.get_credential_from_file(creds_file_path)
        if credential:
            try:
                credential.refresh(httplib2.Http())
            except oauth2_client.AccessTokenRefreshError as e:
                logging.debug('Token refresh error: %s', e)
            if not credential.invalid:
                return credential
        logging.debug('Cannot get credential.')
        return None

    def get_credential_from_file(self, creds_file_path):
        """Get credential from file.
        Args:
            creds_file_path: Credential file path.
        Returns:
            An oauth2client.OAuth2Credentials instance.
        """
        storage = multistore_file.get_credential_storage(
            filename=os.path.abspath(creds_file_path),
            client_id=self.client_id,
            user_agent=self.user_agent,
            scope=self.scope)
        return storage.get()

    def get_credential_with_auth_flow(self, creds_file_path):
        """Get Credential object from file.
        Get credential object from file. Run oauth flow if haven't authorized
        before.

        Args:
            creds_file_path: Credential file path.
        Returns:
            An oauth2client.OAuth2Credentials instance.
        """
        credentials = self.get_refreshed_credential_from_file(creds_file_path)
        if not credentials:
            storage = multistore_file.get_credential_storage(
                filename=os.path.abspath(creds_file_path),
                client_id=self.client_id,
                user_agent=self.user_agent,
                scope=self.scope)
            return self._run_auth_flow(storage)
        return credentials

    def _run_auth_flow(self, storage):
        """Get user oauth2 credentials.

        Args:
            storage: GCP storage object.
        Returns:
            An oauth2client.OAuth2Credentials instance.
        """
        flags = RunFlowFlags(browser_auth=False)
        flow = oauth2_client.OAuth2WebServerFlow(
            client_id=self.client_id,
            client_secret=self.client_secret,
            scope=self.scope,
            user_agent=self.user_agent)
        credentials = oauth2_tools.run_flow(
            flow=flow, storage=storage, flags=flags)
        return credentials


def do_upload_flow(extra_args):
    """Run upload flow.

    Asking user's decision and do the related steps.

    Args:
        extra_args: Dict of extra args to add to test run.
    Return:
        tuple(invocation, workunit)
    """
    config_folder = os.path.join(atest_utils.get_misc_dir(), '.atest')
    creds = request_consent_of_upload_test_result(
        config_folder,
        extra_args.get(constants.REQUEST_UPLOAD_RESULT, None))
    if creds:
        inv, workunit, local_build_id, build_target = _prepare_data(creds)
        extra_args[constants.INVOCATION_ID] = inv['invocationId']
        extra_args[constants.WORKUNIT_ID] = workunit['id']
        extra_args[constants.LOCAL_BUILD_ID] = local_build_id
        extra_args[constants.BUILD_TARGET] = build_target
        if not os.path.exists(os.path.dirname(constants.TOKEN_FILE_PATH)):
            os.makedirs(os.path.dirname(constants.TOKEN_FILE_PATH))
        with open(constants.TOKEN_FILE_PATH, 'w') as token_file:
            token_file.write(creds.token_response['access_token'])
        return creds, inv
    return None, None

def request_consent_of_upload_test_result(config_folder,
                                            request_to_upload_result):
    """Request the consent of upload test results at the first time.

    Args:
        config_folder: The directory path to put config file.
        request_to_upload_result: Prompt message for user determine.
    Return:
        The credential object.
    """
    if not os.path.exists(config_folder):
        os.makedirs(config_folder)
    not_upload_file = os.path.join(config_folder,
                                    constants.DO_NOT_UPLOAD)
    # Do nothing if there are no related config or DO_NOT_UPLOAD exists.
    if (not constants.CREDENTIAL_FILE_NAME or
            not constants.TOKEN_FILE_PATH):
        return None

    creds_f = os.path.join(config_folder, constants.CREDENTIAL_FILE_NAME)
    yn_result = False
    if request_to_upload_result:
        yn_result = atest_utils.prompt_with_yn_result(
            constants.UPLOAD_TEST_RESULT_MSG, False)
        if yn_result:
            if os.path.exists(not_upload_file):
                os.remove(not_upload_file)
        else:
            if os.path.exists(creds_f):
                os.remove(creds_f)

    # If the credential file exists or the user says “Yes”, ATest will
    # try to get the credential from the file, else will create a
    # DO_NOT_UPLOAD to keep the user's decision.
    if not os.path.exists(not_upload_file):
        if os.path.exists(creds_f) or yn_result:
            return GCPHelper(
                client_id=constants.CLIENT_ID,
                client_secret=constants.CLIENT_SECRET,
                user_agent='atest').get_credential_with_auth_flow(creds_f)

    Path(not_upload_file).touch()
    atest_utils.colorful_print(
        'WARNING: In order to allow upload local test results to AnTS, it '
        'is recommended you add the option --request-upload-result.',
        constants.YELLOW)
    return None

def _prepare_data(creds):
    """Prepare data for build api using.

    Args:
        creds: The credential object.
    Return:
        invocation and workunit object.
        build id and build target of local build.
    """
    try:
        logging.disable(logging.INFO)
        external_id = str(uuid.uuid4())
        client = logstorage_utils.BuildClient(creds)
        branch = _get_branch(client)
        target = _get_target(branch, client)
        build_record = client.insert_local_build(external_id,
                                                    target,
                                                    branch)
        client.insert_build_attempts(build_record)
        invocation = client.insert_invocation(build_record)
        workunit = client.insert_work_unit(invocation)
        return invocation, workunit, build_record['buildId'], target
    finally:
        logging.disable(logging.NOTSET)

def _get_branch(build_client):
    """Get source code tree branch.

    Args:
        build_client: The build client object.
    Return:
        "git_master" in internal git, "aosp-master" otherwise.
    """
    default_branch = ('git_master'
                        if constants.CREDENTIAL_FILE_NAME else 'aosp-master')
    local_branch = atest_utils.get_manifest_branch()
    branches = [b['name'] for b in build_client.list_branch()['branches']]
    return local_branch if local_branch in branches else default_branch

def _get_target(branch, build_client):
    """Get local build selected target.

    Args:
        branch: The branch want to check.
        build_client: The build client object.
    Return:
        The matched build target, "aosp_x86-userdebug" otherwise.
    """
    default_target = 'aosp_x86-userdebug'
    local_target = atest_utils.get_build_target()
    targets = [t['target']
                for t in build_client.list_target(branch)['targets']]
    return local_target if local_target in targets else default_target
