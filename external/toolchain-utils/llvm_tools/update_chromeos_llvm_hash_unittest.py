#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Copyright 2019 The ChromiumOS Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Unit tests for updating LLVM hashes."""


import collections
import datetime
import os
from pathlib import Path
import subprocess
import sys
import unittest
import unittest.mock as mock

import chroot
import failure_modes
import get_llvm_hash
import git
import test_helpers
import update_chromeos_llvm_hash


# These are unittests; protected access is OK to a point.
# pylint: disable=protected-access


class UpdateLLVMHashTest(unittest.TestCase):
    """Test class for updating LLVM hashes of packages."""

    @mock.patch.object(os.path, "realpath")
    def testDefaultCrosRootFromCrOSCheckout(self, mock_llvm_tools):
        llvm_tools_path = (
            "/path/to/cros/src/third_party/toolchain-utils/llvm_tools"
        )
        mock_llvm_tools.return_value = llvm_tools_path
        self.assertEqual(
            update_chromeos_llvm_hash.defaultCrosRoot(), Path("/path/to/cros")
        )

    @mock.patch.object(os.path, "realpath")
    def testDefaultCrosRootFromOutsideCrOSCheckout(self, mock_llvm_tools):
        mock_llvm_tools.return_value = "~/toolchain-utils/llvm_tools"
        self.assertEqual(
            update_chromeos_llvm_hash.defaultCrosRoot(),
            Path.home() / "chromiumos",
        )

    # Simulate behavior of 'os.path.isfile()' when the ebuild path to a package
    # does not exist.
    @mock.patch.object(os.path, "isfile", return_value=False)
    def testFailedToUpdateLLVMHashForInvalidEbuildPath(self, mock_isfile):
        ebuild_path = "/some/path/to/package.ebuild"
        llvm_variant = update_chromeos_llvm_hash.LLVMVariant.current
        git_hash = "a123testhash1"
        svn_version = 1000

        # Verify the exception is raised when the ebuild path does not exist.
        with self.assertRaises(ValueError) as err:
            update_chromeos_llvm_hash.UpdateEbuildLLVMHash(
                ebuild_path, llvm_variant, git_hash, svn_version
            )

        self.assertEqual(
            str(err.exception), "Invalid ebuild path provided: %s" % ebuild_path
        )

        mock_isfile.assert_called_once()

    # Simulate 'os.path.isfile' behavior on a valid ebuild path.
    @mock.patch.object(os.path, "isfile", return_value=True)
    def testFailedToUpdateLLVMHash(self, mock_isfile):
        # Create a temporary file to simulate an ebuild file of a package.
        with test_helpers.CreateTemporaryJsonFile() as ebuild_file:
            with open(ebuild_file, "w") as f:
                f.write(
                    "\n".join(
                        [
                            "First line in the ebuild",
                            "Second line in the ebuild",
                            "Last line in the ebuild",
                        ]
                    )
                )

            llvm_variant = update_chromeos_llvm_hash.LLVMVariant.current
            git_hash = "a123testhash1"
            svn_version = 1000

            # Verify the exception is raised when the ebuild file does not have
            # 'LLVM_HASH'.
            with self.assertRaises(ValueError) as err:
                update_chromeos_llvm_hash.UpdateEbuildLLVMHash(
                    ebuild_file, llvm_variant, git_hash, svn_version
                )

            self.assertEqual(str(err.exception), "Failed to update LLVM_HASH")

            llvm_variant = update_chromeos_llvm_hash.LLVMVariant.next

        self.assertEqual(mock_isfile.call_count, 2)

    # Simulate 'os.path.isfile' behavior on a valid ebuild path.
    @mock.patch.object(os.path, "isfile", return_value=True)
    def testFailedToUpdateLLVMNextHash(self, mock_isfile):
        # Create a temporary file to simulate an ebuild file of a package.
        with test_helpers.CreateTemporaryJsonFile() as ebuild_file:
            with open(ebuild_file, "w") as f:
                f.write(
                    "\n".join(
                        [
                            "First line in the ebuild",
                            "Second line in the ebuild",
                            "Last line in the ebuild",
                        ]
                    )
                )

            llvm_variant = update_chromeos_llvm_hash.LLVMVariant.next
            git_hash = "a123testhash1"
            svn_version = 1000

            # Verify the exception is raised when the ebuild file does not have
            # 'LLVM_NEXT_HASH'.
            with self.assertRaises(ValueError) as err:
                update_chromeos_llvm_hash.UpdateEbuildLLVMHash(
                    ebuild_file, llvm_variant, git_hash, svn_version
                )

            self.assertEqual(
                str(err.exception), "Failed to update LLVM_NEXT_HASH"
            )

        self.assertEqual(mock_isfile.call_count, 2)

    @mock.patch.object(os.path, "isfile", return_value=True)
    @mock.patch.object(subprocess, "check_output", return_value=None)
    def testSuccessfullyStageTheEbuildForCommitForLLVMHashUpdate(
        self, mock_stage_commit_command, mock_isfile
    ):

        # Create a temporary file to simulate an ebuild file of a package.
        with test_helpers.CreateTemporaryJsonFile() as ebuild_file:
            # Updates LLVM_HASH to 'git_hash' and revision to
            # 'svn_version'.
            llvm_variant = update_chromeos_llvm_hash.LLVMVariant.current
            git_hash = "a123testhash1"
            svn_version = 1000

            with open(ebuild_file, "w") as f:
                f.write(
                    "\n".join(
                        [
                            "First line in the ebuild",
                            "Second line in the ebuild",
                            'LLVM_HASH="a12b34c56d78e90" # r500',
                            "Last line in the ebuild",
                        ]
                    )
                )

            update_chromeos_llvm_hash.UpdateEbuildLLVMHash(
                ebuild_file, llvm_variant, git_hash, svn_version
            )

            expected_file_contents = [
                "First line in the ebuild\n",
                "Second line in the ebuild\n",
                'LLVM_HASH="a123testhash1" # r1000\n',
                "Last line in the ebuild",
            ]

            # Verify the new file contents of the ebuild file match the expected file
            # contents.
            with open(ebuild_file) as new_file:
                file_contents_as_a_list = [cur_line for cur_line in new_file]
                self.assertListEqual(
                    file_contents_as_a_list, expected_file_contents
                )

        self.assertEqual(mock_isfile.call_count, 2)

        mock_stage_commit_command.assert_called_once()

    @mock.patch.object(os.path, "isfile", return_value=True)
    @mock.patch.object(subprocess, "check_output", return_value=None)
    def testSuccessfullyStageTheEbuildForCommitForLLVMNextHashUpdate(
        self, mock_stage_commit_command, mock_isfile
    ):

        # Create a temporary file to simulate an ebuild file of a package.
        with test_helpers.CreateTemporaryJsonFile() as ebuild_file:
            # Updates LLVM_NEXT_HASH to 'git_hash' and revision to
            # 'svn_version'.
            llvm_variant = update_chromeos_llvm_hash.LLVMVariant.next
            git_hash = "a123testhash1"
            svn_version = 1000

            with open(ebuild_file, "w") as f:
                f.write(
                    "\n".join(
                        [
                            "First line in the ebuild",
                            "Second line in the ebuild",
                            'LLVM_NEXT_HASH="a12b34c56d78e90" # r500',
                            "Last line in the ebuild",
                        ]
                    )
                )

            update_chromeos_llvm_hash.UpdateEbuildLLVMHash(
                ebuild_file, llvm_variant, git_hash, svn_version
            )

            expected_file_contents = [
                "First line in the ebuild\n",
                "Second line in the ebuild\n",
                'LLVM_NEXT_HASH="a123testhash1" # r1000\n',
                "Last line in the ebuild",
            ]

            # Verify the new file contents of the ebuild file match the expected file
            # contents.
            with open(ebuild_file) as new_file:
                file_contents_as_a_list = [cur_line for cur_line in new_file]
                self.assertListEqual(
                    file_contents_as_a_list, expected_file_contents
                )

        self.assertEqual(mock_isfile.call_count, 2)

        mock_stage_commit_command.assert_called_once()

    @mock.patch.object(get_llvm_hash, "GetLLVMMajorVersion")
    @mock.patch.object(os.path, "islink", return_value=False)
    def testFailedToUprevEbuildToVersionForInvalidSymlink(
        self, mock_islink, mock_llvm_version
    ):
        symlink_path = "/path/to/chroot/package/package.ebuild"
        svn_version = 1000
        git_hash = "badf00d"
        mock_llvm_version.return_value = "1234"

        # Verify the exception is raised when a invalid symbolic link is passed in.
        with self.assertRaises(ValueError) as err:
            update_chromeos_llvm_hash.UprevEbuildToVersion(
                symlink_path, svn_version, git_hash
            )

        self.assertEqual(
            str(err.exception), "Invalid symlink provided: %s" % symlink_path
        )

        mock_islink.assert_called_once()
        mock_llvm_version.assert_not_called()

    @mock.patch.object(os.path, "islink", return_value=False)
    def testFailedToUprevEbuildSymlinkForInvalidSymlink(self, mock_islink):
        symlink_path = "/path/to/chroot/package/package.ebuild"

        # Verify the exception is raised when a invalid symbolic link is passed in.
        with self.assertRaises(ValueError) as err:
            update_chromeos_llvm_hash.UprevEbuildSymlink(symlink_path)

        self.assertEqual(
            str(err.exception), "Invalid symlink provided: %s" % symlink_path
        )

        mock_islink.assert_called_once()

    @mock.patch.object(get_llvm_hash, "GetLLVMMajorVersion")
    # Simulate 'os.path.islink' when a symbolic link is passed in.
    @mock.patch.object(os.path, "islink", return_value=True)
    # Simulate 'os.path.realpath' when a symbolic link is passed in.
    @mock.patch.object(os.path, "realpath", return_value=True)
    def testFailedToUprevEbuildToVersion(
        self, mock_realpath, mock_islink, mock_llvm_version
    ):
        symlink_path = "/path/to/chroot/llvm/llvm_pre123_p.ebuild"
        mock_realpath.return_value = "/abs/path/to/llvm/llvm_pre123_p.ebuild"
        git_hash = "badf00d"
        mock_llvm_version.return_value = "1234"
        svn_version = 1000

        # Verify the exception is raised when the symlink does not match the
        # expected pattern
        with self.assertRaises(ValueError) as err:
            update_chromeos_llvm_hash.UprevEbuildToVersion(
                symlink_path, svn_version, git_hash
            )

        self.assertEqual(str(err.exception), "Failed to uprev the ebuild.")

        mock_llvm_version.assert_called_once_with(git_hash)
        mock_islink.assert_called_once_with(symlink_path)

    # Simulate 'os.path.islink' when a symbolic link is passed in.
    @mock.patch.object(os.path, "islink", return_value=True)
    def testFailedToUprevEbuildSymlink(self, mock_islink):
        symlink_path = "/path/to/chroot/llvm/llvm_pre123_p.ebuild"

        # Verify the exception is raised when the symlink does not match the
        # expected pattern
        with self.assertRaises(ValueError) as err:
            update_chromeos_llvm_hash.UprevEbuildSymlink(symlink_path)

        self.assertEqual(str(err.exception), "Failed to uprev the symlink.")

        mock_islink.assert_called_once_with(symlink_path)

    @mock.patch.object(get_llvm_hash, "GetLLVMMajorVersion")
    @mock.patch.object(os.path, "islink", return_value=True)
    @mock.patch.object(os.path, "realpath")
    @mock.patch.object(subprocess, "check_output", return_value=None)
    def testSuccessfullyUprevEbuildToVersionLLVM(
        self, mock_command_output, mock_realpath, mock_islink, mock_llvm_version
    ):
        symlink = "/path/to/llvm/llvm-12.0_pre3_p2-r10.ebuild"
        ebuild = "/abs/path/to/llvm/llvm-12.0_pre3_p2.ebuild"
        mock_realpath.return_value = ebuild
        git_hash = "badf00d"
        mock_llvm_version.return_value = "1234"
        svn_version = 1000

        update_chromeos_llvm_hash.UprevEbuildToVersion(
            symlink, svn_version, git_hash
        )

        mock_llvm_version.assert_called_once_with(git_hash)

        mock_islink.assert_called()

        mock_realpath.assert_called_once_with(symlink)

        mock_command_output.assert_called()

        # Verify commands
        symlink_dir = os.path.dirname(symlink)
        timestamp = datetime.datetime.today().strftime("%Y%m%d")
        new_ebuild = (
            "/abs/path/to/llvm/llvm-1234.0_pre1000_p%s.ebuild" % timestamp
        )
        new_symlink = new_ebuild[: -len(".ebuild")] + "-r1.ebuild"

        expected_cmd = ["git", "-C", symlink_dir, "mv", ebuild, new_ebuild]
        self.assertEqual(
            mock_command_output.call_args_list[0], mock.call(expected_cmd)
        )

        expected_cmd = ["ln", "-s", "-r", new_ebuild, new_symlink]
        self.assertEqual(
            mock_command_output.call_args_list[1], mock.call(expected_cmd)
        )

        expected_cmd = ["git", "-C", symlink_dir, "add", new_symlink]
        self.assertEqual(
            mock_command_output.call_args_list[2], mock.call(expected_cmd)
        )

        expected_cmd = ["git", "-C", symlink_dir, "rm", symlink]
        self.assertEqual(
            mock_command_output.call_args_list[3], mock.call(expected_cmd)
        )

    @mock.patch.object(
        chroot,
        "GetChrootEbuildPaths",
        return_value=["/chroot/path/test.ebuild"],
    )
    @mock.patch.object(subprocess, "check_output", return_value="")
    def testManifestUpdate(self, mock_subprocess, mock_ebuild_paths):
        manifest_packages = ["sys-devel/llvm"]
        chroot_path = "/path/to/chroot"
        update_chromeos_llvm_hash.UpdateManifests(
            manifest_packages, chroot_path
        )

        args = mock_subprocess.call_args[0][-1]
        manifest_cmd = [
            "cros_sdk",
            "--",
            "ebuild",
            "/chroot/path/test.ebuild",
            "manifest",
        ]
        self.assertEqual(args, manifest_cmd)
        mock_ebuild_paths.assert_called_once()

    @mock.patch.object(get_llvm_hash, "GetLLVMMajorVersion")
    @mock.patch.object(os.path, "islink", return_value=True)
    @mock.patch.object(os.path, "realpath")
    @mock.patch.object(subprocess, "check_output", return_value=None)
    def testSuccessfullyUprevEbuildToVersionNonLLVM(
        self, mock_command_output, mock_realpath, mock_islink, mock_llvm_version
    ):
        symlink = (
            "/abs/path/to/compiler-rt/compiler-rt-12.0_pre314159265-r4.ebuild"
        )
        ebuild = "/abs/path/to/compiler-rt/compiler-rt-12.0_pre314159265.ebuild"
        mock_realpath.return_value = ebuild
        mock_llvm_version.return_value = "1234"
        svn_version = 1000
        git_hash = "5678"

        update_chromeos_llvm_hash.UprevEbuildToVersion(
            symlink, svn_version, git_hash
        )

        mock_islink.assert_called()

        mock_realpath.assert_called_once_with(symlink)

        mock_llvm_version.assert_called_once_with(git_hash)

        mock_command_output.assert_called()

        # Verify commands
        symlink_dir = os.path.dirname(symlink)
        new_ebuild = (
            "/abs/path/to/compiler-rt/compiler-rt-1234.0_pre1000.ebuild"
        )
        new_symlink = new_ebuild[: -len(".ebuild")] + "-r1.ebuild"

        expected_cmd = ["git", "-C", symlink_dir, "mv", ebuild, new_ebuild]
        self.assertEqual(
            mock_command_output.call_args_list[0], mock.call(expected_cmd)
        )

        expected_cmd = ["ln", "-s", "-r", new_ebuild, new_symlink]
        self.assertEqual(
            mock_command_output.call_args_list[1], mock.call(expected_cmd)
        )

        expected_cmd = ["git", "-C", symlink_dir, "add", new_symlink]
        self.assertEqual(
            mock_command_output.call_args_list[2], mock.call(expected_cmd)
        )

        expected_cmd = ["git", "-C", symlink_dir, "rm", symlink]
        self.assertEqual(
            mock_command_output.call_args_list[3], mock.call(expected_cmd)
        )

    @mock.patch.object(os.path, "islink", return_value=True)
    @mock.patch.object(subprocess, "check_output", return_value=None)
    def testSuccessfullyUprevEbuildSymlink(
        self, mock_command_output, mock_islink
    ):
        symlink_to_uprev = "/symlink/to/package-r1.ebuild"

        update_chromeos_llvm_hash.UprevEbuildSymlink(symlink_to_uprev)

        mock_islink.assert_called_once_with(symlink_to_uprev)

        mock_command_output.assert_called_once()

    # Simulate behavior of 'os.path.isdir()' when the path to the repo is not a

    # directory.

    @mock.patch.object(chroot, "GetChrootEbuildPaths")
    @mock.patch.object(chroot, "ConvertChrootPathsToAbsolutePaths")
    def testExceptionRaisedWhenCreatingPathDictionaryFromPackages(
        self, mock_chroot_paths_to_symlinks, mock_get_chroot_paths
    ):

        chroot_path = "/some/path/to/chroot"

        package_name = "test-pckg/package"
        package_chroot_path = "/some/chroot/path/to/package-r1.ebuild"

        # Test function to simulate 'ConvertChrootPathsToAbsolutePaths' when a
        # symlink does not start with the prefix '/mnt/host/source'.
        def BadPrefixChrootPath(*args):
            assert len(args) == 2
            raise ValueError(
                "Invalid prefix for the chroot path: "
                "%s" % package_chroot_path
            )

        # Simulate 'GetChrootEbuildPaths' when valid packages are passed in.
        #
        # Returns a list of chroot paths.
        mock_get_chroot_paths.return_value = [package_chroot_path]

        # Use test function to simulate 'ConvertChrootPathsToAbsolutePaths'
        # behavior.
        mock_chroot_paths_to_symlinks.side_effect = BadPrefixChrootPath

        # Verify exception is raised when for an invalid prefix in the symlink.
        with self.assertRaises(ValueError) as err:
            update_chromeos_llvm_hash.CreatePathDictionaryFromPackages(
                chroot_path, [package_name]
            )

        self.assertEqual(
            str(err.exception),
            "Invalid prefix for the chroot path: " "%s" % package_chroot_path,
        )

        mock_get_chroot_paths.assert_called_once_with(
            chroot_path, [package_name]
        )

        mock_chroot_paths_to_symlinks.assert_called_once_with(
            chroot_path, [package_chroot_path]
        )

    @mock.patch.object(chroot, "GetChrootEbuildPaths")
    @mock.patch.object(chroot, "ConvertChrootPathsToAbsolutePaths")
    @mock.patch.object(
        update_chromeos_llvm_hash, "GetEbuildPathsFromSymLinkPaths"
    )
    def testSuccessfullyCreatedPathDictionaryFromPackages(
        self,
        mock_ebuild_paths_from_symlink_paths,
        mock_chroot_paths_to_symlinks,
        mock_get_chroot_paths,
    ):

        package_chroot_path = "/mnt/host/source/src/path/to/package-r1.ebuild"

        # Simulate 'GetChrootEbuildPaths' when returning a chroot path for a valid
        # package.
        #
        # Returns a list of chroot paths.
        mock_get_chroot_paths.return_value = [package_chroot_path]

        package_symlink_path = (
            "/some/path/to/chroot/src/path/to/package-r1.ebuild"
        )

        # Simulate 'ConvertChrootPathsToAbsolutePaths' when returning a symlink to
        # a chroot path that points to a package.
        #
        # Returns a list of symlink file paths.
        mock_chroot_paths_to_symlinks.return_value = [package_symlink_path]

        chroot_package_path = "/some/path/to/chroot/src/path/to/package.ebuild"

        # Simulate 'GetEbuildPathsFromSymlinkPaths' when returning a dictionary of
        # a symlink that points to an ebuild.
        #
        # Returns a dictionary of a symlink and ebuild file path pair
        # where the key is the absolute path to the symlink of the ebuild file
        # and the value is the absolute path to the ebuild file of the package.
        mock_ebuild_paths_from_symlink_paths.return_value = {
            package_symlink_path: chroot_package_path
        }

        chroot_path = "/some/path/to/chroot"
        package_name = "test-pckg/package"

        self.assertEqual(
            update_chromeos_llvm_hash.CreatePathDictionaryFromPackages(
                chroot_path, [package_name]
            ),
            {package_symlink_path: chroot_package_path},
        )

        mock_get_chroot_paths.assert_called_once_with(
            chroot_path, [package_name]
        )

        mock_chroot_paths_to_symlinks.assert_called_once_with(
            chroot_path, [package_chroot_path]
        )

        mock_ebuild_paths_from_symlink_paths.assert_called_once_with(
            [package_symlink_path]
        )

    @mock.patch.object(subprocess, "check_output", return_value=None)
    def testSuccessfullyRemovedPatchesFromFilesDir(self, mock_run_cmd):
        patches_to_remove_list = [
            "/abs/path/to/filesdir/cherry/fix_output.patch",
            "/abs/path/to/filesdir/display_results.patch",
        ]

        update_chromeos_llvm_hash.RemovePatchesFromFilesDir(
            patches_to_remove_list
        )

        self.assertEqual(mock_run_cmd.call_count, 2)

    @mock.patch.object(os.path, "isfile", return_value=False)
    def testInvalidPatchMetadataFileStagedForCommit(self, mock_isfile):
        patch_metadata_path = "/abs/path/to/filesdir/PATCHES"

        # Verify the exception is raised when the absolute path to the patch
        # metadata file does not exist or is not a file.
        with self.assertRaises(ValueError) as err:
            update_chromeos_llvm_hash.StagePatchMetadataFileForCommit(
                patch_metadata_path
            )

        self.assertEqual(
            str(err.exception),
            "Invalid patch metadata file provided: " "%s" % patch_metadata_path,
        )

        mock_isfile.assert_called_once()

    @mock.patch.object(os.path, "isfile", return_value=True)
    @mock.patch.object(subprocess, "check_output", return_value=None)
    def testSuccessfullyStagedPatchMetadataFileForCommit(self, mock_run_cmd, _):

        patch_metadata_path = "/abs/path/to/filesdir/PATCHES.json"

        update_chromeos_llvm_hash.StagePatchMetadataFileForCommit(
            patch_metadata_path
        )

        mock_run_cmd.assert_called_once()

    def testNoPatchResultsForCommit(self):
        package_1_patch_info_dict = {
            "applied_patches": ["display_results.patch"],
            "failed_patches": ["fixes_output.patch"],
            "non_applicable_patches": [],
            "disabled_patches": [],
            "removed_patches": [],
            "modified_metadata": None,
        }

        package_2_patch_info_dict = {
            "applied_patches": ["redirects_stdout.patch", "fix_display.patch"],
            "failed_patches": [],
            "non_applicable_patches": [],
            "disabled_patches": [],
            "removed_patches": [],
            "modified_metadata": None,
        }

        test_package_info_dict = {
            "test-packages/package1": package_1_patch_info_dict,
            "test-packages/package2": package_2_patch_info_dict,
        }

        test_commit_message = ["Updated packages"]

        self.assertListEqual(
            update_chromeos_llvm_hash.StagePackagesPatchResultsForCommit(
                test_package_info_dict, test_commit_message
            ),
            test_commit_message,
        )

    @mock.patch.object(
        update_chromeos_llvm_hash, "StagePatchMetadataFileForCommit"
    )
    @mock.patch.object(update_chromeos_llvm_hash, "RemovePatchesFromFilesDir")
    def testAddedPatchResultsForCommit(
        self, mock_remove_patches, mock_stage_patches_for_commit
    ):

        package_1_patch_info_dict = {
            "applied_patches": [],
            "failed_patches": [],
            "non_applicable_patches": [],
            "disabled_patches": ["fixes_output.patch"],
            "removed_patches": [],
            "modified_metadata": "/abs/path/to/filesdir/PATCHES.json",
        }

        package_2_patch_info_dict = {
            "applied_patches": ["fix_display.patch"],
            "failed_patches": [],
            "non_applicable_patches": [],
            "disabled_patches": [],
            "removed_patches": ["/abs/path/to/filesdir/redirect_stdout.patch"],
            "modified_metadata": "/abs/path/to/filesdir/PATCHES.json",
        }

        test_package_info_dict = {
            "test-packages/package1": package_1_patch_info_dict,
            "test-packages/package2": package_2_patch_info_dict,
        }

        test_commit_message = ["Updated packages"]

        expected_commit_messages = [
            "Updated packages",
            "\nFor the package test-packages/package1:",
            "The patch metadata file PATCHES.json was modified",
            "The following patches were disabled:",
            "fixes_output.patch",
            "\nFor the package test-packages/package2:",
            "The patch metadata file PATCHES.json was modified",
            "The following patches were removed:",
            "redirect_stdout.patch",
        ]

        self.assertListEqual(
            update_chromeos_llvm_hash.StagePackagesPatchResultsForCommit(
                test_package_info_dict, test_commit_message
            ),
            expected_commit_messages,
        )

        path_to_removed_patch = "/abs/path/to/filesdir/redirect_stdout.patch"

        mock_remove_patches.assert_called_once_with([path_to_removed_patch])

        self.assertEqual(mock_stage_patches_for_commit.call_count, 2)

    @mock.patch.object(get_llvm_hash, "GetLLVMMajorVersion")
    @mock.patch.object(
        update_chromeos_llvm_hash, "CreatePathDictionaryFromPackages"
    )
    @mock.patch.object(git, "CreateBranch")
    @mock.patch.object(update_chromeos_llvm_hash, "UpdateEbuildLLVMHash")
    @mock.patch.object(update_chromeos_llvm_hash, "UprevEbuildSymlink")
    @mock.patch.object(git, "UploadChanges")
    @mock.patch.object(git, "DeleteBranch")
    @mock.patch.object(os.path, "realpath")
    def testExceptionRaisedWhenUpdatingPackages(
        self,
        mock_realpath,
        mock_delete_repo,
        mock_upload_changes,
        mock_uprev_symlink,
        mock_update_llvm_next,
        mock_create_repo,
        mock_create_path_dict,
        mock_llvm_major_version,
    ):

        path_to_package_dir = "/some/path/to/chroot/src/path/to"
        abs_path_to_package = os.path.join(
            path_to_package_dir, "package.ebuild"
        )
        symlink_path_to_package = os.path.join(
            path_to_package_dir, "package-r1.ebuild"
        )

        mock_llvm_major_version.return_value = "1234"

        # Test function to simulate 'CreateBranch' when successfully created the
        # branch on a valid repo path.
        def SuccessfullyCreateBranchForChanges(_, branch):
            self.assertEqual(branch, "update-LLVM_NEXT_HASH-a123testhash4")

        # Test function to simulate 'UpdateEbuildLLVMHash' when successfully
        # updated the ebuild's 'LLVM_NEXT_HASH'.
        def SuccessfullyUpdatedLLVMHash(ebuild_path, _, git_hash, svn_version):
            self.assertEqual(ebuild_path, abs_path_to_package)
            self.assertEqual(git_hash, "a123testhash4")
            self.assertEqual(svn_version, 1000)

        # Test function to simulate 'UprevEbuildSymlink' when the symlink to the
        # ebuild does not have a revision number.
        def FailedToUprevEbuildSymlink(_):
            # Raises a 'ValueError' exception because the symlink did not have have a
            # revision number.
            raise ValueError("Failed to uprev the ebuild.")

        # Test function to fail on 'UploadChanges' if the function gets called
        # when an exception is raised.
        def ShouldNotExecuteUploadChanges(*args):
            # Test function should not be called (i.e. execution should resume in the
            # 'finally' block) because 'UprevEbuildSymlink' raised an
            # exception.
            assert len(args) == 3
            assert False, (
                'Failed to go to "finally" block '
                "after the exception was raised."
            )

        test_package_path_dict = {symlink_path_to_package: abs_path_to_package}

        # Simulate behavior of 'CreatePathDictionaryFromPackages()' when
        # successfully created a dictionary where the key is the absolute path to
        # the symlink of the package and value is the absolute path to the ebuild of
        # the package.
        mock_create_path_dict.return_value = test_package_path_dict

        # Use test function to simulate behavior.
        mock_create_repo.side_effect = SuccessfullyCreateBranchForChanges
        mock_update_llvm_next.side_effect = SuccessfullyUpdatedLLVMHash
        mock_uprev_symlink.side_effect = FailedToUprevEbuildSymlink
        mock_upload_changes.side_effect = ShouldNotExecuteUploadChanges
        mock_realpath.return_value = (
            "/abs/path/to/test-packages/package1.ebuild"
        )

        packages_to_update = ["test-packages/package1"]
        llvm_variant = update_chromeos_llvm_hash.LLVMVariant.next
        git_hash = "a123testhash4"
        svn_version = 1000
        chroot_path = Path("/some/path/to/chroot")
        git_hash_source = "google3"
        branch = "update-LLVM_NEXT_HASH-a123testhash4"
        extra_commit_msg = None

        # Verify exception is raised when an exception is thrown within
        # the 'try' block by UprevEbuildSymlink function.
        with self.assertRaises(ValueError) as err:
            update_chromeos_llvm_hash.UpdatePackages(
                packages=packages_to_update,
                manifest_packages=[],
                llvm_variant=llvm_variant,
                git_hash=git_hash,
                svn_version=svn_version,
                chroot_path=chroot_path,
                mode=failure_modes.FailureModes.FAIL,
                git_hash_source=git_hash_source,
                extra_commit_msg=extra_commit_msg,
            )

        self.assertEqual(str(err.exception), "Failed to uprev the ebuild.")

        mock_create_path_dict.assert_called_once_with(
            chroot_path, packages_to_update
        )

        mock_create_repo.assert_called_once_with(path_to_package_dir, branch)

        mock_update_llvm_next.assert_called_once_with(
            abs_path_to_package, llvm_variant, git_hash, svn_version
        )

        mock_uprev_symlink.assert_called_once_with(symlink_path_to_package)

        mock_upload_changes.assert_not_called()

        mock_delete_repo.assert_called_once_with(path_to_package_dir, branch)

    @mock.patch.object(update_chromeos_llvm_hash, "EnsurePackageMaskContains")
    @mock.patch.object(get_llvm_hash, "GetLLVMMajorVersion")
    @mock.patch.object(
        update_chromeos_llvm_hash, "CreatePathDictionaryFromPackages"
    )
    @mock.patch.object(git, "CreateBranch")
    @mock.patch.object(update_chromeos_llvm_hash, "UpdateEbuildLLVMHash")
    @mock.patch.object(update_chromeos_llvm_hash, "UprevEbuildSymlink")
    @mock.patch.object(git, "UploadChanges")
    @mock.patch.object(git, "DeleteBranch")
    @mock.patch.object(
        update_chromeos_llvm_hash, "UpdatePackagesPatchMetadataFile"
    )
    @mock.patch.object(
        update_chromeos_llvm_hash, "StagePatchMetadataFileForCommit"
    )
    def testSuccessfullyUpdatedPackages(
        self,
        mock_stage_patch_file,
        mock_update_package_metadata_file,
        mock_delete_repo,
        mock_upload_changes,
        mock_uprev_symlink,
        mock_update_llvm_next,
        mock_create_repo,
        mock_create_path_dict,
        mock_llvm_version,
        mock_mask_contains,
    ):

        path_to_package_dir = "/some/path/to/chroot/src/path/to"
        abs_path_to_package = os.path.join(
            path_to_package_dir, "package.ebuild"
        )
        symlink_path_to_package = os.path.join(
            path_to_package_dir, "package-r1.ebuild"
        )

        # Test function to simulate 'CreateBranch' when successfully created the
        # branch for the changes to be made to the ebuild files.
        def SuccessfullyCreateBranchForChanges(_, branch):
            self.assertEqual(branch, "update-LLVM_NEXT_HASH-a123testhash5")

        # Test function to simulate 'UploadChanges' after a successfull update of
        # 'LLVM_NEXT_HASH" of the ebuild file.
        def SuccessfullyUpdatedLLVMHash(ebuild_path, _, git_hash, svn_version):
            self.assertEqual(
                ebuild_path, "/some/path/to/chroot/src/path/to/package.ebuild"
            )
            self.assertEqual(git_hash, "a123testhash5")
            self.assertEqual(svn_version, 1000)

        # Test function to simulate 'UprevEbuildSymlink' when successfully
        # incremented the revision number by 1.
        def SuccessfullyUprevedEbuildSymlink(symlink_path):
            self.assertEqual(
                symlink_path,
                "/some/path/to/chroot/src/path/to/package-r1.ebuild",
            )

        # Test function to simulate 'UpdatePackagesPatchMetadataFile()' when the
        # patch results contains a disabled patch in 'disable_patches' mode.
        def RetrievedPatchResults(chroot_path, svn_version, packages, mode):

            self.assertEqual(chroot_path, Path("/some/path/to/chroot"))
            self.assertEqual(svn_version, 1000)
            self.assertListEqual(packages, ["path/to"])
            self.assertEqual(mode, failure_modes.FailureModes.DISABLE_PATCHES)

            patch_metadata_file = "PATCHES.json"
            PatchInfo = collections.namedtuple(
                "PatchInfo",
                [
                    "applied_patches",
                    "failed_patches",
                    "non_applicable_patches",
                    "disabled_patches",
                    "removed_patches",
                    "modified_metadata",
                ],
            )

            package_patch_info = PatchInfo(
                applied_patches=["fix_display.patch"],
                failed_patches=["fix_stdout.patch"],
                non_applicable_patches=[],
                disabled_patches=["fix_stdout.patch"],
                removed_patches=[],
                modified_metadata="/abs/path/to/filesdir/%s"
                % patch_metadata_file,
            )

            package_info_dict = {"path/to": package_patch_info._asdict()}

            # Returns a dictionary where the key is the package and the value is a
            # dictionary that contains information about the package's patch results
            # produced by the patch manager.
            return package_info_dict

        # Test function to simulate 'UploadChanges()' when successfully created a
        # commit for the changes made to the packages and their patches and
        # retrieved the change list of the commit.
        def SuccessfullyUploadedChanges(*args):
            assert len(args) == 3
            commit_url = "https://some_name/path/to/commit/+/12345"
            return git.CommitContents(url=commit_url, cl_number=12345)

        test_package_path_dict = {symlink_path_to_package: abs_path_to_package}

        # Simulate behavior of 'CreatePathDictionaryFromPackages()' when
        # successfully created a dictionary where the key is the absolute path to
        # the symlink of the package and value is the absolute path to the ebuild of
        # the package.
        mock_create_path_dict.return_value = test_package_path_dict

        # Use test function to simulate behavior.
        mock_create_repo.side_effect = SuccessfullyCreateBranchForChanges
        mock_update_llvm_next.side_effect = SuccessfullyUpdatedLLVMHash
        mock_uprev_symlink.side_effect = SuccessfullyUprevedEbuildSymlink
        mock_update_package_metadata_file.side_effect = RetrievedPatchResults
        mock_upload_changes.side_effect = SuccessfullyUploadedChanges
        mock_llvm_version.return_value = "1234"
        mock_mask_contains.reurn_value = None

        packages_to_update = ["test-packages/package1"]
        llvm_variant = update_chromeos_llvm_hash.LLVMVariant.next
        git_hash = "a123testhash5"
        svn_version = 1000
        chroot_path = Path("/some/path/to/chroot")
        git_hash_source = "tot"
        branch = "update-LLVM_NEXT_HASH-a123testhash5"
        extra_commit_msg = "\ncommit-message-end"

        change_list = update_chromeos_llvm_hash.UpdatePackages(
            packages=packages_to_update,
            manifest_packages=[],
            llvm_variant=llvm_variant,
            git_hash=git_hash,
            svn_version=svn_version,
            chroot_path=chroot_path,
            mode=failure_modes.FailureModes.DISABLE_PATCHES,
            git_hash_source=git_hash_source,
            extra_commit_msg=extra_commit_msg,
        )

        self.assertEqual(
            change_list.url, "https://some_name/path/to/commit/+/12345"
        )

        self.assertEqual(change_list.cl_number, 12345)

        mock_create_path_dict.assert_called_once_with(
            chroot_path, packages_to_update
        )

        mock_create_repo.assert_called_once_with(path_to_package_dir, branch)

        mock_update_llvm_next.assert_called_once_with(
            abs_path_to_package, llvm_variant, git_hash, svn_version
        )

        mock_uprev_symlink.assert_called_once_with(symlink_path_to_package)

        mock_mask_contains.assert_called_once_with(chroot_path, git_hash)

        expected_commit_messages = [
            "llvm-next/tot: upgrade to a123testhash5 (r1000)\n",
            "The following packages have been updated:",
            "path/to",
            "\nFor the package path/to:",
            "The patch metadata file PATCHES.json was modified",
            "The following patches were disabled:",
            "fix_stdout.patch",
            "\ncommit-message-end",
        ]

        mock_update_package_metadata_file.assert_called_once()

        mock_stage_patch_file.assert_called_once_with(
            "/abs/path/to/filesdir/PATCHES.json"
        )

        mock_upload_changes.assert_called_once_with(
            path_to_package_dir, branch, expected_commit_messages
        )

        mock_delete_repo.assert_called_once_with(path_to_package_dir, branch)

    @mock.patch.object(chroot, "VerifyOutsideChroot")
    @mock.patch.object(get_llvm_hash, "GetLLVMHashAndVersionFromSVNOption")
    @mock.patch.object(update_chromeos_llvm_hash, "UpdatePackages")
    def testMainDefaults(
        self, mock_update_packages, mock_gethash, mock_outside_chroot
    ):
        git_hash = "1234abcd"
        svn_version = 5678
        mock_gethash.return_value = (git_hash, svn_version)
        argv = [
            "./update_chromeos_llvm_hash_unittest.py",
            "--llvm_version",
            "google3",
        ]

        with mock.patch.object(sys, "argv", argv) as mock.argv:
            update_chromeos_llvm_hash.main()

        expected_packages = set(update_chromeos_llvm_hash.DEFAULT_PACKAGES)
        expected_manifest_packages = set(
            update_chromeos_llvm_hash.DEFAULT_MANIFEST_PACKAGES,
        )
        expected_llvm_variant = update_chromeos_llvm_hash.LLVMVariant.current
        expected_chroot = update_chromeos_llvm_hash.defaultCrosRoot()
        mock_update_packages.assert_called_once_with(
            packages=expected_packages,
            manifest_packages=expected_manifest_packages,
            llvm_variant=expected_llvm_variant,
            git_hash=git_hash,
            svn_version=svn_version,
            chroot_path=expected_chroot,
            mode=failure_modes.FailureModes.FAIL,
            git_hash_source="google3",
            extra_commit_msg=None,
        )
        mock_outside_chroot.assert_called()

    @mock.patch.object(chroot, "VerifyOutsideChroot")
    @mock.patch.object(get_llvm_hash, "GetLLVMHashAndVersionFromSVNOption")
    @mock.patch.object(update_chromeos_llvm_hash, "UpdatePackages")
    def testMainLlvmNext(
        self, mock_update_packages, mock_gethash, mock_outside_chroot
    ):
        git_hash = "1234abcd"
        svn_version = 5678
        mock_gethash.return_value = (git_hash, svn_version)
        argv = [
            "./update_chromeos_llvm_hash_unittest.py",
            "--llvm_version",
            "google3",
            "--is_llvm_next",
        ]

        with mock.patch.object(sys, "argv", argv) as mock.argv:
            update_chromeos_llvm_hash.main()

        expected_packages = set(update_chromeos_llvm_hash.DEFAULT_PACKAGES)
        expected_llvm_variant = update_chromeos_llvm_hash.LLVMVariant.next
        expected_chroot = update_chromeos_llvm_hash.defaultCrosRoot()
        # llvm-next upgrade does not update manifest by default.
        mock_update_packages.assert_called_once_with(
            packages=expected_packages,
            manifest_packages=set(),
            llvm_variant=expected_llvm_variant,
            git_hash=git_hash,
            svn_version=svn_version,
            chroot_path=expected_chroot,
            mode=failure_modes.FailureModes.FAIL,
            git_hash_source="google3",
            extra_commit_msg=None,
        )
        mock_outside_chroot.assert_called()

    @mock.patch.object(chroot, "VerifyOutsideChroot")
    @mock.patch.object(get_llvm_hash, "GetLLVMHashAndVersionFromSVNOption")
    @mock.patch.object(update_chromeos_llvm_hash, "UpdatePackages")
    def testMainAllArgs(
        self, mock_update_packages, mock_gethash, mock_outside_chroot
    ):
        packages_to_update = "test-packages/package1,test-libs/lib1"
        manifest_packages = "test-libs/lib1,test-libs/lib2"
        failure_mode = failure_modes.FailureModes.REMOVE_PATCHES
        chroot_path = Path("/some/path/to/chroot")
        llvm_ver = 435698
        git_hash = "1234abcd"
        svn_version = 5678
        mock_gethash.return_value = (git_hash, svn_version)

        argv = [
            "./update_chromeos_llvm_hash_unittest.py",
            "--llvm_version",
            str(llvm_ver),
            "--is_llvm_next",
            "--chroot_path",
            str(chroot_path),
            "--update_packages",
            packages_to_update,
            "--manifest_packages",
            manifest_packages,
            "--failure_mode",
            failure_mode.value,
            "--patch_metadata_file",
            "META.json",
        ]

        with mock.patch.object(sys, "argv", argv) as mock.argv:
            update_chromeos_llvm_hash.main()

        expected_packages = {"test-packages/package1", "test-libs/lib1"}
        expected_manifest_packages = {"test-libs/lib1", "test-libs/lib2"}
        expected_llvm_variant = update_chromeos_llvm_hash.LLVMVariant.next
        mock_update_packages.assert_called_once_with(
            packages=expected_packages,
            manifest_packages=expected_manifest_packages,
            llvm_variant=expected_llvm_variant,
            git_hash=git_hash,
            svn_version=svn_version,
            chroot_path=chroot_path,
            mode=failure_mode,
            git_hash_source=llvm_ver,
            extra_commit_msg=None,
        )
        mock_outside_chroot.assert_called()

    @mock.patch.object(subprocess, "check_output", return_value=None)
    @mock.patch.object(get_llvm_hash, "GetLLVMMajorVersion")
    def testEnsurePackageMaskContainsExisting(
        self, mock_llvm_version, mock_git_add
    ):
        chroot_path = "absolute/path/to/chroot"
        git_hash = "badf00d"
        mock_llvm_version.return_value = "1234"
        with mock.patch(
            "update_chromeos_llvm_hash.open",
            mock.mock_open(read_data="\n=sys-devel/llvm-1234.0_pre*\n"),
            create=True,
        ) as mock_file:
            update_chromeos_llvm_hash.EnsurePackageMaskContains(
                chroot_path, git_hash
            )
            handle = mock_file()
            handle.write.assert_not_called()
        mock_llvm_version.assert_called_once_with(git_hash)

        overlay_dir = (
            "absolute/path/to/chroot/src/third_party/chromiumos-overlay"
        )
        mask_path = overlay_dir + "/profiles/targets/chromeos/package.mask"
        mock_git_add.assert_called_once_with(
            ["git", "-C", overlay_dir, "add", mask_path]
        )

    @mock.patch.object(subprocess, "check_output", return_value=None)
    @mock.patch.object(get_llvm_hash, "GetLLVMMajorVersion")
    def testEnsurePackageMaskContainsNotExisting(
        self, mock_llvm_version, mock_git_add
    ):
        chroot_path = "absolute/path/to/chroot"
        git_hash = "badf00d"
        mock_llvm_version.return_value = "1234"
        with mock.patch(
            "update_chromeos_llvm_hash.open",
            mock.mock_open(read_data="nothing relevant"),
            create=True,
        ) as mock_file:
            update_chromeos_llvm_hash.EnsurePackageMaskContains(
                chroot_path, git_hash
            )
            handle = mock_file()
            handle.write.assert_called_once_with(
                "=sys-devel/llvm-1234.0_pre*\n"
            )
        mock_llvm_version.assert_called_once_with(git_hash)

        overlay_dir = (
            "absolute/path/to/chroot/src/third_party/chromiumos-overlay"
        )
        mask_path = overlay_dir + "/profiles/targets/chromeos/package.mask"
        mock_git_add.assert_called_once_with(
            ["git", "-C", overlay_dir, "add", mask_path]
        )


if __name__ == "__main__":
    unittest.main()
