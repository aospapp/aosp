#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Copyright 2019 The ChromiumOS Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Updates the LLVM hash and uprevs the build of the specified packages.

For each package, a temporary repo is created and the changes are uploaded
for review.
"""

import argparse
import datetime
import enum
import os
from pathlib import Path
import re
import subprocess
from typing import Dict, Iterable

import chroot
import failure_modes
import get_llvm_hash
import git
import patch_utils
import subprocess_helpers


DEFAULT_PACKAGES = [
    "dev-util/lldb-server",
    "sys-devel/llvm",
    "sys-libs/compiler-rt",
    "sys-libs/libcxx",
    "sys-libs/llvm-libunwind",
]

DEFAULT_MANIFEST_PACKAGES = ["sys-devel/llvm"]


# Specify which LLVM hash to update
class LLVMVariant(enum.Enum):
    """Represent the LLVM hash in an ebuild file to update."""

    current = "LLVM_HASH"
    next = "LLVM_NEXT_HASH"


# If set to `True`, then the contents of `stdout` after executing a command will
# be displayed to the terminal.
verbose = False


def defaultCrosRoot() -> Path:
    """Get default location of chroot_path.

    The logic assumes that the cros_root is ~/chromiumos, unless llvm_tools is
    inside of a CrOS checkout, in which case that checkout should be used.

    Returns:
      The best guess location for the cros checkout.
    """
    llvm_tools_path = os.path.realpath(os.path.dirname(__file__))
    if llvm_tools_path.endswith("src/third_party/toolchain-utils/llvm_tools"):
        return Path(llvm_tools_path).parent.parent.parent.parent
    return Path.home() / "chromiumos"


def GetCommandLineArgs():
    """Parses the command line for the optional command line arguments.

    Returns:
      The log level to use when retrieving the LLVM hash or google3 LLVM version,
      the chroot path to use for executing chroot commands,
      a list of a package or packages to update their LLVM next hash,
      and the LLVM version to use when retrieving the LLVM hash.
    """

    # Create parser and add optional command-line arguments.
    parser = argparse.ArgumentParser(
        description="Updates the build's hash for llvm-next."
    )

    # Add argument for a specific chroot path.
    parser.add_argument(
        "--chroot_path",
        type=Path,
        default=defaultCrosRoot(),
        help="the path to the chroot (default: %(default)s)",
    )

    # Add argument for specific builds to uprev and update their llvm-next hash.
    parser.add_argument(
        "--update_packages",
        default=",".join(DEFAULT_PACKAGES),
        help="Comma-separated ebuilds to update llvm-next hash for "
        "(default: %(default)s)",
    )

    parser.add_argument(
        "--manifest_packages",
        default="",
        help="Comma-separated ebuilds to update manifests for "
        "(default: %(default)s)",
    )

    # Add argument for whether to display command contents to `stdout`.
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="display contents of a command to the terminal "
        "(default: %(default)s)",
    )

    # Add argument for the LLVM hash to update
    parser.add_argument(
        "--is_llvm_next",
        action="store_true",
        help="which llvm hash to update. If specified, update LLVM_NEXT_HASH. "
        "Otherwise, update LLVM_HASH",
    )

    # Add argument for the LLVM version to use.
    parser.add_argument(
        "--llvm_version",
        type=get_llvm_hash.IsSvnOption,
        required=True,
        help="which git hash to use. Either a svn revision, or one "
        f"of {sorted(get_llvm_hash.KNOWN_HASH_SOURCES)}",
    )

    # Add argument for the mode of the patch management when handling patches.
    parser.add_argument(
        "--failure_mode",
        default=failure_modes.FailureModes.FAIL.value,
        choices=[
            failure_modes.FailureModes.FAIL.value,
            failure_modes.FailureModes.CONTINUE.value,
            failure_modes.FailureModes.DISABLE_PATCHES.value,
            failure_modes.FailureModes.REMOVE_PATCHES.value,
        ],
        help="the mode of the patch manager when handling failed patches "
        "(default: %(default)s)",
    )

    # Add argument for the patch metadata file.
    parser.add_argument(
        "--patch_metadata_file",
        default="PATCHES.json",
        help="the .json file that has all the patches and their "
        "metadata if applicable (default: PATCHES.json inside $FILESDIR)",
    )

    # Parse the command line.
    args_output = parser.parse_args()

    # FIXME: We shouldn't be using globals here, but until we fix it, make pylint
    # stop complaining about it.
    # pylint: disable=global-statement
    global verbose

    verbose = args_output.verbose

    return args_output


def GetEbuildPathsFromSymLinkPaths(symlinks):
    """Reads the symlink(s) to get the ebuild path(s) to the package(s).

    Args:
      symlinks: A list of absolute path symlink/symlinks that point
      to the package's ebuild.

    Returns:
      A dictionary where the key is the absolute path of the symlink and the value
      is the absolute path to the ebuild that was read from the symlink.

    Raises:
      ValueError: Invalid symlink(s) were provided.
    """

    # A dictionary that holds:
    #   key: absolute symlink path
    #   value: absolute ebuild path
    resolved_paths = {}

    # Iterate through each symlink.
    #
    # For each symlink, check that it is a valid symlink,
    # and then construct the ebuild path, and
    # then add the ebuild path to the dict.
    for cur_symlink in symlinks:
        if not os.path.islink(cur_symlink):
            raise ValueError(f"Invalid symlink provided: {cur_symlink}")

        # Construct the absolute path to the ebuild.
        ebuild_path = os.path.realpath(cur_symlink)

        if cur_symlink not in resolved_paths:
            resolved_paths[cur_symlink] = ebuild_path

    return resolved_paths


def UpdateEbuildLLVMHash(ebuild_path, llvm_variant, git_hash, svn_version):
    """Updates the LLVM hash in the ebuild.

    The build changes are staged for commit in the temporary repo.

    Args:
      ebuild_path: The absolute path to the ebuild.
      llvm_variant: Which LLVM hash to update.
      git_hash: The new git hash.
      svn_version: The SVN-style revision number of git_hash.

    Raises:
      ValueError: Invalid ebuild path provided or failed to stage the commit
      of the changes or failed to update the LLVM hash.
    """

    # Iterate through each ebuild.
    #
    # For each ebuild, read the file in
    # advance and then create a temporary file
    # that gets updated with the new LLVM hash
    # and revision number and then the ebuild file
    # gets updated to the temporary file.

    if not os.path.isfile(ebuild_path):
        raise ValueError(f"Invalid ebuild path provided: {ebuild_path}")

    temp_ebuild_file = f"{ebuild_path}.temp"

    with open(ebuild_path) as ebuild_file:
        # write updates to a temporary file in case of interrupts
        with open(temp_ebuild_file, "w") as temp_file:
            for cur_line in ReplaceLLVMHash(
                ebuild_file, llvm_variant, git_hash, svn_version
            ):
                temp_file.write(cur_line)
    os.rename(temp_ebuild_file, ebuild_path)

    # Get the path to the parent directory.
    parent_dir = os.path.dirname(ebuild_path)

    # Stage the changes.
    subprocess.check_output(["git", "-C", parent_dir, "add", ebuild_path])


def ReplaceLLVMHash(ebuild_lines, llvm_variant, git_hash, svn_version):
    """Updates the LLVM git hash.

    Args:
      ebuild_lines: The contents of the ebuild file.
      llvm_variant: The LLVM hash to update.
      git_hash: The new git hash.
      svn_version: The SVN-style revision number of git_hash.

    Yields:
      lines of the modified ebuild file
    """
    is_updated = False
    llvm_regex = re.compile(
        "^" + re.escape(llvm_variant.value) + '="[a-z0-9]+"'
    )
    for cur_line in ebuild_lines:
        if not is_updated and llvm_regex.search(cur_line):
            # Update the git hash and revision number.
            cur_line = f'{llvm_variant.value}="{git_hash}" # r{svn_version}\n'

            is_updated = True

        yield cur_line

    if not is_updated:
        raise ValueError(f"Failed to update {llvm_variant.value}")


def UprevEbuildSymlink(symlink):
    """Uprevs the symlink's revision number.

    Increases the revision number by 1 and stages the change in
    the temporary repo.

    Args:
      symlink: The absolute path of an ebuild symlink.

    Raises:
      ValueError: Failed to uprev the symlink or failed to stage the changes.
    """

    if not os.path.islink(symlink):
        raise ValueError(f"Invalid symlink provided: {symlink}")

    new_symlink, is_changed = re.subn(
        r"r([0-9]+).ebuild",
        lambda match: "r%s.ebuild" % str(int(match.group(1)) + 1),
        symlink,
        count=1,
    )

    if not is_changed:
        raise ValueError("Failed to uprev the symlink.")

    # rename the symlink
    subprocess.check_output(
        ["git", "-C", os.path.dirname(symlink), "mv", symlink, new_symlink]
    )


def UprevEbuildToVersion(symlink, svn_version, git_hash):
    """Uprevs the ebuild's revision number.

    Increases the revision number by 1 and stages the change in
    the temporary repo.

    Args:
      symlink: The absolute path of an ebuild symlink.
      svn_version: The SVN-style revision number of git_hash.
      git_hash: The new git hash.

    Raises:
      ValueError: Failed to uprev the ebuild or failed to stage the changes.
      AssertionError: No llvm version provided for an LLVM uprev
    """

    if not os.path.islink(symlink):
        raise ValueError(f"Invalid symlink provided: {symlink}")

    ebuild = os.path.realpath(symlink)
    llvm_major_version = get_llvm_hash.GetLLVMMajorVersion(git_hash)
    # llvm
    package = os.path.basename(os.path.dirname(symlink))
    if not package:
        raise ValueError("Tried to uprev an unknown package")
    if package == "llvm":
        new_ebuild, is_changed = re.subn(
            r"(\d+)\.(\d+)_pre([0-9]+)_p([0-9]+)",
            "%s.\\2_pre%s_p%s"
            % (
                llvm_major_version,
                svn_version,
                datetime.datetime.today().strftime("%Y%m%d"),
            ),
            ebuild,
            count=1,
        )
    # any other package
    else:
        new_ebuild, is_changed = re.subn(
            r"(\d+)\.(\d+)_pre([0-9]+)",
            "%s.\\2_pre%s" % (llvm_major_version, svn_version),
            ebuild,
            count=1,
        )

    if not is_changed:  # failed to increment the revision number
        raise ValueError("Failed to uprev the ebuild.")

    symlink_dir = os.path.dirname(symlink)

    # Rename the ebuild
    subprocess.check_output(
        ["git", "-C", symlink_dir, "mv", ebuild, new_ebuild]
    )

    # Create a symlink of the renamed ebuild
    new_symlink = new_ebuild[: -len(".ebuild")] + "-r1.ebuild"
    subprocess.check_output(["ln", "-s", "-r", new_ebuild, new_symlink])

    if not os.path.islink(new_symlink):
        raise ValueError(
            f'Invalid symlink name: {new_ebuild[:-len(".ebuild")]}'
        )

    subprocess.check_output(["git", "-C", symlink_dir, "add", new_symlink])

    # Remove the old symlink
    subprocess.check_output(["git", "-C", symlink_dir, "rm", symlink])


def CreatePathDictionaryFromPackages(chroot_path, update_packages):
    """Creates a symlink and ebuild path pair dictionary from the packages.

    Args:
      chroot_path: The absolute path to the chroot.
      update_packages: The filtered packages to be updated.

    Returns:
      A dictionary where the key is the absolute path to the symlink
      of the package and the value is the absolute path to the ebuild of
      the package.
    """

    # Construct a list containing the chroot file paths of the package(s).
    chroot_file_paths = chroot.GetChrootEbuildPaths(
        chroot_path, update_packages
    )

    # Construct a list containing the symlink(s) of the package(s).
    symlink_file_paths = chroot.ConvertChrootPathsToAbsolutePaths(
        chroot_path, chroot_file_paths
    )

    # Create a dictionary where the key is the absolute path of the symlink to
    # the package and the value is the absolute path to the ebuild of the package.
    return GetEbuildPathsFromSymLinkPaths(symlink_file_paths)


def RemovePatchesFromFilesDir(patches):
    """Removes the patches from $FILESDIR of a package.

    Args:
      patches: A list of absolute paths of patches to remove

    Raises:
      ValueError: Failed to remove a patch in $FILESDIR.
    """

    for patch in patches:
        subprocess.check_output(
            ["git", "-C", os.path.dirname(patch), "rm", "-f", patch]
        )


def StagePatchMetadataFileForCommit(patch_metadata_file_path):
    """Stages the updated patch metadata file for commit.

    Args:
      patch_metadata_file_path: The absolute path to the patch metadata file.

    Raises:
      ValueError: Failed to stage the patch metadata file for commit or invalid
      patch metadata file.
    """

    if not os.path.isfile(patch_metadata_file_path):
        raise ValueError(
            f"Invalid patch metadata file provided: {patch_metadata_file_path}"
        )

    # Cmd to stage the patch metadata file for commit.
    subprocess.check_output(
        [
            "git",
            "-C",
            os.path.dirname(patch_metadata_file_path),
            "add",
            patch_metadata_file_path,
        ]
    )


def StagePackagesPatchResultsForCommit(package_info_dict, commit_messages):
    """Stages the patch results of the packages to the commit message.

    Args:
      package_info_dict: A dictionary where the key is the package name and the
      value is a dictionary that contains information about the patches of the
      package (key).
      commit_messages: The commit message that has the updated ebuilds and
      upreving information.

    Returns:
      commit_messages with new additions
    """

    # For each package, check if any patches for that package have
    # changed, if so, add which patches have changed to the commit
    # message.
    for package_name, patch_info_dict in package_info_dict.items():
        if (
            patch_info_dict["disabled_patches"]
            or patch_info_dict["removed_patches"]
            or patch_info_dict["modified_metadata"]
        ):
            cur_package_header = f"\nFor the package {package_name}:"
            commit_messages.append(cur_package_header)

        # Add to the commit message that the patch metadata file was modified.
        if patch_info_dict["modified_metadata"]:
            patch_metadata_path = patch_info_dict["modified_metadata"]
            metadata_file_name = os.path.basename(patch_metadata_path)
            commit_messages.append(
                f"The patch metadata file {metadata_file_name} was modified"
            )

            StagePatchMetadataFileForCommit(patch_metadata_path)

        # Add each disabled patch to the commit message.
        if patch_info_dict["disabled_patches"]:
            commit_messages.append("The following patches were disabled:")

            for patch_path in patch_info_dict["disabled_patches"]:
                commit_messages.append(os.path.basename(patch_path))

        # Add each removed patch to the commit message.
        if patch_info_dict["removed_patches"]:
            commit_messages.append("The following patches were removed:")

            for patch_path in patch_info_dict["removed_patches"]:
                commit_messages.append(os.path.basename(patch_path))

            RemovePatchesFromFilesDir(patch_info_dict["removed_patches"])

    return commit_messages


def UpdateManifests(packages: Iterable[str], chroot_path: Path):
    """Updates manifest files for packages.

    Args:
      packages: A list of packages to update manifests for.
      chroot_path: The absolute path to the chroot.

    Raises:
      CalledProcessError: ebuild failed to update manifest.
    """
    manifest_ebuilds = chroot.GetChrootEbuildPaths(chroot_path, packages)
    for ebuild_path in manifest_ebuilds:
        subprocess_helpers.ChrootRunCommand(
            chroot_path, ["ebuild", ebuild_path, "manifest"]
        )


def UpdatePackages(
    packages: Iterable[str],
    manifest_packages: Iterable[str],
    llvm_variant,
    git_hash,
    svn_version,
    chroot_path: Path,
    mode,
    git_hash_source,
    extra_commit_msg,
):
    """Updates an LLVM hash and uprevs the ebuild of the packages.

    A temporary repo is created for the changes. The changes are
    then uploaded for review.

    Args:
      packages: A list of all the packages that are going to be updated.
      manifest_packages: A list of packages to update manifests for.
      llvm_variant: The LLVM hash to update.
      git_hash: The new git hash.
      svn_version: The SVN-style revision number of git_hash.
      chroot_path: The absolute path to the chroot.
      mode: The mode of the patch manager when handling an applicable patch
      that failed to apply.
        Ex. 'FailureModes.FAIL'
      git_hash_source: The source of which git hash to use based off of.
        Ex. 'google3', 'tot', or <version> such as 365123
      extra_commit_msg: extra test to append to the commit message.

    Returns:
      A nametuple that has two (key, value) pairs, where the first pair is the
      Gerrit commit URL and the second pair is the change list number.
    """

    # Construct a dictionary where the key is the absolute path of the symlink to
    # the package and the value is the absolute path to the ebuild of the package.
    paths_dict = CreatePathDictionaryFromPackages(chroot_path, packages)

    repo_path = os.path.dirname(next(iter(paths_dict.values())))

    branch = "update-" + llvm_variant.value + "-" + git_hash

    git.CreateBranch(repo_path, branch)

    try:
        commit_message_header = "llvm"
        if llvm_variant == LLVMVariant.next:
            commit_message_header = "llvm-next"
        if git_hash_source in get_llvm_hash.KNOWN_HASH_SOURCES:
            commit_message_header += (
                f"/{git_hash_source}: upgrade to {git_hash} (r{svn_version})"
            )
        else:
            commit_message_header += f": upgrade to {git_hash} (r{svn_version})"

        commit_lines = [
            commit_message_header + "\n",
            "The following packages have been updated:",
        ]

        # Holds the list of packages that are updating.
        packages = []

        # Iterate through the dictionary.
        #
        # For each iteration:
        # 1) Update the ebuild's LLVM hash.
        # 2) Uprev the ebuild (symlink).
        # 3) Add the modified package to the commit message.
        for symlink_path, ebuild_path in paths_dict.items():
            path_to_ebuild_dir = os.path.dirname(ebuild_path)

            UpdateEbuildLLVMHash(
                ebuild_path, llvm_variant, git_hash, svn_version
            )

            if llvm_variant == LLVMVariant.current:
                UprevEbuildToVersion(symlink_path, svn_version, git_hash)
            else:
                UprevEbuildSymlink(symlink_path)

            cur_dir_name = os.path.basename(path_to_ebuild_dir)
            parent_dir_name = os.path.basename(
                os.path.dirname(path_to_ebuild_dir)
            )

            packages.append(f"{parent_dir_name}/{cur_dir_name}")
            commit_lines.append(f"{parent_dir_name}/{cur_dir_name}")

        if manifest_packages:
            UpdateManifests(manifest_packages, chroot_path)
            commit_lines.append("Updated manifest for:")
            commit_lines.extend(manifest_packages)

        EnsurePackageMaskContains(chroot_path, git_hash)

        # Handle the patches for each package.
        package_info_dict = UpdatePackagesPatchMetadataFile(
            chroot_path, svn_version, packages, mode
        )

        # Update the commit message if changes were made to a package's patches.
        commit_lines = StagePackagesPatchResultsForCommit(
            package_info_dict, commit_lines
        )

        if extra_commit_msg:
            commit_lines.append(extra_commit_msg)

        change_list = git.UploadChanges(repo_path, branch, commit_lines)

    finally:
        git.DeleteBranch(repo_path, branch)

    return change_list


def EnsurePackageMaskContains(chroot_path, git_hash):
    """Adds the major version of llvm to package.mask if it's not already present.

    Args:
      chroot_path: The absolute path to the chroot.
      git_hash: The new git hash.

    Raises:
      FileExistsError: package.mask not found in ../../chromiumos-overlay
    """

    llvm_major_version = get_llvm_hash.GetLLVMMajorVersion(git_hash)

    overlay_dir = os.path.join(
        chroot_path, "src/third_party/chromiumos-overlay"
    )
    mask_path = os.path.join(
        overlay_dir, "profiles/targets/chromeos/package.mask"
    )
    with open(mask_path, "r+") as mask_file:
        mask_contents = mask_file.read()
        expected_line = f"=sys-devel/llvm-{llvm_major_version}.0_pre*\n"
        if expected_line not in mask_contents:
            mask_file.write(expected_line)

    subprocess.check_output(["git", "-C", overlay_dir, "add", mask_path])


def UpdatePackagesPatchMetadataFile(
    chroot_path: Path,
    svn_version: int,
    packages: Iterable[str],
    mode: failure_modes.FailureModes,
) -> Dict[str, patch_utils.PatchInfo]:
    """Updates the packages metadata file.

    Args:
      chroot_path: The absolute path to the chroot.
      svn_version: The version to use for patch management.
      packages: All the packages to update their patch metadata file.
      mode: The mode for the patch manager to use when an applicable patch
      fails to apply.
        Ex: 'FailureModes.FAIL'

    Returns:
      A dictionary where the key is the package name and the value is a dictionary
      that has information on the patches.
    """

    # A dictionary where the key is the package name and the value is a dictionary
    # that has information on the patches.
    package_info = {}

    llvm_hash = get_llvm_hash.LLVMHash()

    with llvm_hash.CreateTempDirectory() as temp_dir:
        with get_llvm_hash.CreateTempLLVMRepo(temp_dir) as dirname:
            # Ensure that 'svn_version' exists in the chromiumum mirror of LLVM by
            # finding its corresponding git hash.
            git_hash = get_llvm_hash.GetGitHashFrom(dirname, svn_version)
            move_head_cmd = ["git", "-C", dirname, "checkout", git_hash, "-q"]
            subprocess.run(move_head_cmd, stdout=subprocess.DEVNULL, check=True)

            for cur_package in packages:
                # Get the absolute path to $FILESDIR of the package.
                chroot_ebuild_str = subprocess_helpers.ChrootRunCommand(
                    chroot_path, ["equery", "w", cur_package]
                ).strip()
                if not chroot_ebuild_str:
                    raise RuntimeError(
                        f"could not find ebuild for {cur_package}"
                    )
                chroot_ebuild_path = Path(
                    chroot.ConvertChrootPathsToAbsolutePaths(
                        chroot_path, [chroot_ebuild_str]
                    )[0]
                )
                patches_json_fp = (
                    chroot_ebuild_path.parent / "files" / "PATCHES.json"
                )
                if not patches_json_fp.is_file():
                    raise RuntimeError(
                        f"patches file {patches_json_fp} is not a file"
                    )

                src_path = Path(dirname)
                with patch_utils.git_clean_context(src_path):
                    if (
                        mode == failure_modes.FailureModes.FAIL
                        or mode == failure_modes.FailureModes.CONTINUE
                    ):
                        patches_info = patch_utils.apply_all_from_json(
                            svn_version=svn_version,
                            llvm_src_dir=src_path,
                            patches_json_fp=patches_json_fp,
                            continue_on_failure=mode
                            == failure_modes.FailureModes.CONTINUE,
                        )
                    elif mode == failure_modes.FailureModes.REMOVE_PATCHES:
                        patches_info = patch_utils.remove_old_patches(
                            svn_version, src_path, patches_json_fp
                        )
                    elif mode == failure_modes.FailureModes.DISABLE_PATCHES:
                        patches_info = patch_utils.update_version_ranges(
                            svn_version, src_path, patches_json_fp
                        )

                package_info[cur_package] = patches_info._asdict()

    return package_info


def main():
    """Updates the LLVM next hash for each package.

    Raises:
      AssertionError: The script was run inside the chroot.
    """

    chroot.VerifyOutsideChroot()

    args_output = GetCommandLineArgs()

    llvm_variant = LLVMVariant.current
    if args_output.is_llvm_next:
        llvm_variant = LLVMVariant.next

    git_hash_source = args_output.llvm_version

    git_hash, svn_version = get_llvm_hash.GetLLVMHashAndVersionFromSVNOption(
        git_hash_source
    )

    # Filter out empty strings. For example "".split{",") returns [""].
    packages = set(p for p in args_output.update_packages.split(",") if p)
    manifest_packages = set(
        p for p in args_output.manifest_packages.split(",") if p
    )
    if not manifest_packages and not args_output.is_llvm_next:
        # Set default manifest packages only for the current llvm.
        manifest_packages = set(DEFAULT_MANIFEST_PACKAGES)
    change_list = UpdatePackages(
        packages=packages,
        manifest_packages=manifest_packages,
        llvm_variant=llvm_variant,
        git_hash=git_hash,
        svn_version=svn_version,
        chroot_path=args_output.chroot_path,
        mode=failure_modes.FailureModes(args_output.failure_mode),
        git_hash_source=git_hash_source,
        extra_commit_msg=None,
    )

    print(f"Successfully updated packages to {git_hash} ({svn_version})")
    print(f"Gerrit URL: {change_list.url}")
    print(f"Change list number: {change_list.cl_number}")


if __name__ == "__main__":
    main()
