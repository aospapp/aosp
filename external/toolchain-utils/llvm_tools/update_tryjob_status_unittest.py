#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Copyright 2019 The ChromiumOS Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Tests when updating a tryjob's status."""


import json
import os
import subprocess
import unittest
import unittest.mock as mock

from test_helpers import CreateTemporaryJsonFile
from test_helpers import WritePrettyJsonFile
import update_tryjob_status
from update_tryjob_status import CustomScriptStatus
from update_tryjob_status import TryjobStatus


class UpdateTryjobStatusTest(unittest.TestCase):
    """Unittests for updating a tryjob's 'status'."""

    def testFoundTryjobIndex(self):
        test_tryjobs = [
            {
                "rev": 123,
                "url": "https://some_url_to_CL.com",
                "cl": "https://some_link_to_tryjob.com",
                "status": "good",
                "buildbucket_id": 91835,
            },
            {
                "rev": 1000,
                "url": "https://some_url_to_CL.com",
                "cl": "https://some_link_to_tryjob.com",
                "status": "pending",
                "buildbucket_id": 10931,
            },
        ]

        expected_index = 0

        revision_to_find = 123

        self.assertEqual(
            update_tryjob_status.FindTryjobIndex(
                revision_to_find, test_tryjobs
            ),
            expected_index,
        )

    def testNotFindTryjobIndex(self):
        test_tryjobs = [
            {
                "rev": 500,
                "url": "https://some_url_to_CL.com",
                "cl": "https://some_link_to_tryjob.com",
                "status": "bad",
                "buildbucket_id": 390,
            },
            {
                "rev": 10,
                "url": "https://some_url_to_CL.com",
                "cl": "https://some_link_to_tryjob.com",
                "status": "skip",
                "buildbucket_id": 10,
            },
        ]

        revision_to_find = 250

        self.assertIsNone(
            update_tryjob_status.FindTryjobIndex(revision_to_find, test_tryjobs)
        )

    @mock.patch.object(subprocess, "Popen")
    # Simulate the behavior of `os.rename()` when successfully renamed a file.
    @mock.patch.object(os, "rename", return_value=None)
    # Simulate the behavior of `os.path.basename()` when successfully retrieved
    # the basename of the temp .JSON file.
    @mock.patch.object(os.path, "basename", return_value="tmpFile.json")
    def testInvalidExitCodeByCustomScript(
        self, mock_basename, mock_rename_file, mock_exec_custom_script
    ):

        error_message_by_custom_script = "Failed to parse .JSON file"

        # Simulate the behavior of 'subprocess.Popen()' when executing the custom
        # script.
        #
        # `Popen.communicate()` returns a tuple of `stdout` and `stderr`.
        mock_exec_custom_script.return_value.communicate.return_value = (
            None,
            error_message_by_custom_script,
        )

        # Exit code of 1 is not in the mapping, so an exception will be raised.
        custom_script_exit_code = 1

        mock_exec_custom_script.return_value.returncode = (
            custom_script_exit_code
        )

        tryjob_contents = {
            "status": "good",
            "rev": 1234,
            "url": "https://some_url_to_CL.com",
            "link": "https://some_url_to_tryjob.com",
        }

        custom_script_path = "/abs/path/to/script.py"
        status_file_path = "/abs/path/to/status_file.json"

        name_json_file = os.path.join(
            os.path.dirname(status_file_path), "tmpFile.json"
        )

        expected_error_message = (
            "Custom script %s exit code %d did not match "
            'any of the expected exit codes: %s for "good", '
            '%d for "bad", or %d for "skip".\nPlease check '
            "%s for information about the tryjob: %s"
            % (
                custom_script_path,
                custom_script_exit_code,
                CustomScriptStatus.GOOD.value,
                CustomScriptStatus.BAD.value,
                CustomScriptStatus.SKIP.value,
                name_json_file,
                error_message_by_custom_script,
            )
        )

        # Verify the exception is raised when the exit code by the custom script
        # does not match any of the exit codes in the mapping of
        # `custom_script_exit_value_mapping`.
        with self.assertRaises(ValueError) as err:
            update_tryjob_status.GetCustomScriptResult(
                custom_script_path, status_file_path, tryjob_contents
            )

        self.assertEqual(str(err.exception), expected_error_message)

        mock_exec_custom_script.assert_called_once()

        mock_rename_file.assert_called_once()

        mock_basename.assert_called_once()

    @mock.patch.object(subprocess, "Popen")
    # Simulate the behavior of `os.rename()` when successfully renamed a file.
    @mock.patch.object(os, "rename", return_value=None)
    # Simulate the behavior of `os.path.basename()` when successfully retrieved
    # the basename of the temp .JSON file.
    @mock.patch.object(os.path, "basename", return_value="tmpFile.json")
    def testValidExitCodeByCustomScript(
        self, mock_basename, mock_rename_file, mock_exec_custom_script
    ):

        # Simulate the behavior of 'subprocess.Popen()' when executing the custom
        # script.
        #
        # `Popen.communicate()` returns a tuple of `stdout` and `stderr`.
        mock_exec_custom_script.return_value.communicate.return_value = (
            None,
            None,
        )

        mock_exec_custom_script.return_value.returncode = (
            CustomScriptStatus.GOOD.value
        )

        tryjob_contents = {
            "status": "good",
            "rev": 1234,
            "url": "https://some_url_to_CL.com",
            "link": "https://some_url_to_tryjob.com",
        }

        custom_script_path = "/abs/path/to/script.py"
        status_file_path = "/abs/path/to/status_file.json"

        self.assertEqual(
            update_tryjob_status.GetCustomScriptResult(
                custom_script_path, status_file_path, tryjob_contents
            ),
            TryjobStatus.GOOD.value,
        )

        mock_exec_custom_script.assert_called_once()

        mock_rename_file.assert_not_called()

        mock_basename.assert_not_called()

    def testNoTryjobsInStatusFileWhenUpdatingTryjobStatus(self):
        bisect_test_contents = {"start": 369410, "end": 369420, "jobs": []}

        # Create a temporary .JSON file to simulate a .JSON file that has bisection
        # contents.
        with CreateTemporaryJsonFile() as temp_json_file:
            with open(temp_json_file, "w") as f:
                WritePrettyJsonFile(bisect_test_contents, f)

            revision_to_update = 369412

            custom_script = None

            # Verify the exception is raised when the `status_file` does not have any
            # `jobs` (empty).
            with self.assertRaises(SystemExit) as err:
                update_tryjob_status.UpdateTryjobStatus(
                    revision_to_update,
                    TryjobStatus.GOOD,
                    temp_json_file,
                    custom_script,
                )

            self.assertEqual(
                str(err.exception), "No tryjobs in %s" % temp_json_file
            )

    # Simulate the behavior of `FindTryjobIndex()` when the tryjob does not exist
    # in the status file.
    @mock.patch.object(
        update_tryjob_status, "FindTryjobIndex", return_value=None
    )
    def testNotFindTryjobIndexWhenUpdatingTryjobStatus(
        self, mock_find_tryjob_index
    ):

        bisect_test_contents = {
            "start": 369410,
            "end": 369420,
            "jobs": [{"rev": 369411, "status": "pending"}],
        }

        # Create a temporary .JSON file to simulate a .JSON file that has bisection
        # contents.
        with CreateTemporaryJsonFile() as temp_json_file:
            with open(temp_json_file, "w") as f:
                WritePrettyJsonFile(bisect_test_contents, f)

            revision_to_update = 369416

            custom_script = None

            # Verify the exception is raised when the `status_file` does not have any
            # `jobs` (empty).
            with self.assertRaises(ValueError) as err:
                update_tryjob_status.UpdateTryjobStatus(
                    revision_to_update,
                    TryjobStatus.SKIP,
                    temp_json_file,
                    custom_script,
                )

            self.assertEqual(
                str(err.exception),
                "Unable to find tryjob for %d in %s"
                % (revision_to_update, temp_json_file),
            )

        mock_find_tryjob_index.assert_called_once()

    # Simulate the behavior of `FindTryjobIndex()` when the tryjob exists in the
    # status file.
    @mock.patch.object(update_tryjob_status, "FindTryjobIndex", return_value=0)
    def testSuccessfullyUpdatedTryjobStatusToGood(self, mock_find_tryjob_index):
        bisect_test_contents = {
            "start": 369410,
            "end": 369420,
            "jobs": [{"rev": 369411, "status": "pending"}],
        }

        # Create a temporary .JSON file to simulate a .JSON file that has bisection
        # contents.
        with CreateTemporaryJsonFile() as temp_json_file:
            with open(temp_json_file, "w") as f:
                WritePrettyJsonFile(bisect_test_contents, f)

            revision_to_update = 369411

            # Index of the tryjob that is going to have its 'status' value updated.
            tryjob_index = 0

            custom_script = None

            update_tryjob_status.UpdateTryjobStatus(
                revision_to_update,
                TryjobStatus.GOOD,
                temp_json_file,
                custom_script,
            )

            # Verify that the tryjob's 'status' has been updated in the status file.
            with open(temp_json_file) as status_file:
                bisect_contents = json.load(status_file)

                self.assertEqual(
                    bisect_contents["jobs"][tryjob_index]["status"],
                    TryjobStatus.GOOD.value,
                )

        mock_find_tryjob_index.assert_called_once()

    # Simulate the behavior of `FindTryjobIndex()` when the tryjob exists in the
    # status file.
    @mock.patch.object(update_tryjob_status, "FindTryjobIndex", return_value=0)
    def testSuccessfullyUpdatedTryjobStatusToBad(self, mock_find_tryjob_index):
        bisect_test_contents = {
            "start": 369410,
            "end": 369420,
            "jobs": [{"rev": 369411, "status": "pending"}],
        }

        # Create a temporary .JSON file to simulate a .JSON file that has bisection
        # contents.
        with CreateTemporaryJsonFile() as temp_json_file:
            with open(temp_json_file, "w") as f:
                WritePrettyJsonFile(bisect_test_contents, f)

            revision_to_update = 369411

            # Index of the tryjob that is going to have its 'status' value updated.
            tryjob_index = 0

            custom_script = None

            update_tryjob_status.UpdateTryjobStatus(
                revision_to_update,
                TryjobStatus.BAD,
                temp_json_file,
                custom_script,
            )

            # Verify that the tryjob's 'status' has been updated in the status file.
            with open(temp_json_file) as status_file:
                bisect_contents = json.load(status_file)

                self.assertEqual(
                    bisect_contents["jobs"][tryjob_index]["status"],
                    TryjobStatus.BAD.value,
                )

        mock_find_tryjob_index.assert_called_once()

    # Simulate the behavior of `FindTryjobIndex()` when the tryjob exists in the
    # status file.
    @mock.patch.object(update_tryjob_status, "FindTryjobIndex", return_value=0)
    def testSuccessfullyUpdatedTryjobStatusToPending(
        self, mock_find_tryjob_index
    ):
        bisect_test_contents = {
            "start": 369410,
            "end": 369420,
            "jobs": [{"rev": 369411, "status": "skip"}],
        }

        # Create a temporary .JSON file to simulate a .JSON file that has bisection
        # contents.
        with CreateTemporaryJsonFile() as temp_json_file:
            with open(temp_json_file, "w") as f:
                WritePrettyJsonFile(bisect_test_contents, f)

            revision_to_update = 369411

            # Index of the tryjob that is going to have its 'status' value updated.
            tryjob_index = 0

            custom_script = None

            update_tryjob_status.UpdateTryjobStatus(
                revision_to_update,
                update_tryjob_status.TryjobStatus.SKIP,
                temp_json_file,
                custom_script,
            )

            # Verify that the tryjob's 'status' has been updated in the status file.
            with open(temp_json_file) as status_file:
                bisect_contents = json.load(status_file)

                self.assertEqual(
                    bisect_contents["jobs"][tryjob_index]["status"],
                    update_tryjob_status.TryjobStatus.SKIP.value,
                )

        mock_find_tryjob_index.assert_called_once()

    # Simulate the behavior of `FindTryjobIndex()` when the tryjob exists in the
    # status file.
    @mock.patch.object(update_tryjob_status, "FindTryjobIndex", return_value=0)
    def testSuccessfullyUpdatedTryjobStatusToSkip(self, mock_find_tryjob_index):
        bisect_test_contents = {
            "start": 369410,
            "end": 369420,
            "jobs": [
                {
                    "rev": 369411,
                    "status": "pending",
                }
            ],
        }

        # Create a temporary .JSON file to simulate a .JSON file that has bisection
        # contents.
        with CreateTemporaryJsonFile() as temp_json_file:
            with open(temp_json_file, "w") as f:
                WritePrettyJsonFile(bisect_test_contents, f)

            revision_to_update = 369411

            # Index of the tryjob that is going to have its 'status' value updated.
            tryjob_index = 0

            custom_script = None

            update_tryjob_status.UpdateTryjobStatus(
                revision_to_update,
                update_tryjob_status.TryjobStatus.PENDING,
                temp_json_file,
                custom_script,
            )

            # Verify that the tryjob's 'status' has been updated in the status file.
            with open(temp_json_file) as status_file:
                bisect_contents = json.load(status_file)

                self.assertEqual(
                    bisect_contents["jobs"][tryjob_index]["status"],
                    update_tryjob_status.TryjobStatus.PENDING.value,
                )

        mock_find_tryjob_index.assert_called_once()

    @mock.patch.object(update_tryjob_status, "FindTryjobIndex", return_value=0)
    @mock.patch.object(
        update_tryjob_status,
        "GetCustomScriptResult",
        return_value=TryjobStatus.SKIP.value,
    )
    def testUpdatedTryjobStatusToAutoPassedWithCustomScript(
        self, mock_get_custom_script_result, mock_find_tryjob_index
    ):
        bisect_test_contents = {
            "start": 369410,
            "end": 369420,
            "jobs": [
                {"rev": 369411, "status": "pending", "buildbucket_id": 1200}
            ],
        }

        # Create a temporary .JSON file to simulate a .JSON file that has bisection
        # contents.
        with CreateTemporaryJsonFile() as temp_json_file:
            with open(temp_json_file, "w") as f:
                WritePrettyJsonFile(bisect_test_contents, f)

            revision_to_update = 369411

            # Index of the tryjob that is going to have its 'status' value updated.
            tryjob_index = 0

            custom_script_path = "/abs/path/to/custom_script.py"

            update_tryjob_status.UpdateTryjobStatus(
                revision_to_update,
                update_tryjob_status.TryjobStatus.CUSTOM_SCRIPT,
                temp_json_file,
                custom_script_path,
            )

            # Verify that the tryjob's 'status' has been updated in the status file.
            with open(temp_json_file) as status_file:
                bisect_contents = json.load(status_file)

                self.assertEqual(
                    bisect_contents["jobs"][tryjob_index]["status"],
                    update_tryjob_status.TryjobStatus.SKIP.value,
                )

        mock_get_custom_script_result.assert_called_once()

        mock_find_tryjob_index.assert_called_once()

    # Simulate the behavior of `FindTryjobIndex()` when the tryjob exists in the
    # status file.
    @mock.patch.object(update_tryjob_status, "FindTryjobIndex", return_value=0)
    def testSetStatusDoesNotExistWhenUpdatingTryjobStatus(
        self, mock_find_tryjob_index
    ):

        bisect_test_contents = {
            "start": 369410,
            "end": 369420,
            "jobs": [
                {"rev": 369411, "status": "pending", "buildbucket_id": 1200}
            ],
        }

        # Create a temporary .JSON file to simulate a .JSON file that has bisection
        # contents.
        with CreateTemporaryJsonFile() as temp_json_file:
            with open(temp_json_file, "w") as f:
                WritePrettyJsonFile(bisect_test_contents, f)

            revision_to_update = 369411

            nonexistent_update_status = "revert_status"

            custom_script = None

            # Verify the exception is raised when the `set_status` command line
            # argument does not exist in the mapping.
            with self.assertRaises(ValueError) as err:
                update_tryjob_status.UpdateTryjobStatus(
                    revision_to_update,
                    nonexistent_update_status,
                    temp_json_file,
                    custom_script,
                )

            self.assertEqual(
                str(err.exception),
                'Invalid "set_status" option provided: revert_status',
            )

        mock_find_tryjob_index.assert_called_once()


if __name__ == "__main__":
    unittest.main()
