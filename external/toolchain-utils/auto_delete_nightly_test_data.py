#!/usr/bin/env python3
# -*- coding: utf-8 -*-
#
# Copyright 2019 The ChromiumOS Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""A crontab script to delete night test data."""

__author__ = "shenhan@google.com (Han Shen)"

import argparse
import datetime
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import time
import traceback
from typing import Callable

from cros_utils import command_executer
from cros_utils import constants
from cros_utils import misc


DIR_BY_WEEKDAY = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
NIGHTLY_TESTS_WORKSPACE = os.path.join(
    constants.CROSTC_WORKSPACE, "nightly-tests"
)


def CleanNumberedDir(s, dry_run=False):
    """Deleted directories under each dated_dir."""
    chromeos_dirs = [
        os.path.join(s, x)
        for x in os.listdir(s)
        if misc.IsChromeOsTree(os.path.join(s, x))
    ]
    ce = command_executer.GetCommandExecuter(log_level="none")
    all_succeeded = True
    for cd in chromeos_dirs:
        if misc.DeleteChromeOsTree(cd, dry_run=dry_run):
            print(f"Successfully removed chromeos tree {cd!r}.")
        else:
            all_succeeded = False
            print(f"Failed to remove chromeos tree {cd!r}, please check.")

    if not all_succeeded:
        print("Failed to delete at least one chromeos tree, please check.")
        return False

    ## Now delete the numbered dir Before forcibly removing the directory, just
    ## check 's' to make sure it matches the expected pattern.  A valid dir to be
    ## removed must be '/usr/local/google/crostc/(SUN|MON|TUE...|SAT)'.
    valid_dir_pattern = (
        "^" + NIGHTLY_TESTS_WORKSPACE + "/(" + "|".join(DIR_BY_WEEKDAY) + ")"
    )
    if not re.search(valid_dir_pattern, s):
        print(
            f"Trying to delete an invalid dir {s!r} (must match "
            f"{valid_dir_pattern!r}), please check."
        )
        return False

    cmd = f"rm -fr {s}"
    if dry_run:
        print(cmd)
    else:
        if (
            ce.RunCommand(cmd, print_to_console=False, terminated_timeout=480)
            == 0
        ):
            print(f"Successfully removed {s!r}.")
        else:
            all_succeeded = False
            print(f"Failed to remove {s!r}, please check.")
    return all_succeeded


def CleanDatedDir(dated_dir, dry_run=False):
    # List subdirs under dir
    subdirs = [
        os.path.join(dated_dir, x)
        for x in os.listdir(dated_dir)
        if os.path.isdir(os.path.join(dated_dir, x))
    ]
    all_succeeded = True
    for s in subdirs:
        if not CleanNumberedDir(s, dry_run):
            all_succeeded = False
    return all_succeeded


def ProcessArguments(argv):
    """Process arguments."""
    parser = argparse.ArgumentParser(
        description="Automatically delete nightly test data directories.",
        usage="auto_delete_nightly_test_data.py options",
    )
    parser.add_argument(
        "-d",
        "--dry_run",
        dest="dry_run",
        default=False,
        action="store_true",
        help="Only print command line, do not execute anything.",
    )
    parser.add_argument(
        "--days_to_preserve",
        dest="days_to_preserve",
        default=3,
        help=(
            "Specify the number of days (not including today),"
            " test data generated on these days will *NOT* be "
            "deleted. Defaults to 3."
        ),
    )
    options = parser.parse_args(argv)
    return options


def RemoveAllSubdirsMatchingPredicate(
    base_dir: Path,
    days_to_preserve: int,
    dry_run: bool,
    is_name_removal_worthy: Callable[[str], bool],
) -> int:
    """Removes all subdirs of base_dir that match the given predicate."""
    secs_to_preserve = 60 * 60 * 24 * days_to_preserve
    now = time.time()
    remove_older_than_time = now - secs_to_preserve

    try:
        dir_entries = list(base_dir.iterdir())
    except FileNotFoundError as e:
        # We get this if the directory itself doesn't exist. Since we're cleaning
        # tempdirs, that's as good as a success. Further, the prior approach here
        # was using the `find` binary, which exits successfully if nothing is
        # found.
        print(f"Error enumerating {base_dir}'s contents; skipping removal: {e}")
        return 0

    had_errors = False
    for file in dir_entries:
        if not is_name_removal_worthy(file.name):
            continue

        try:
            # Take the stat here and use that later, so we only need to check for a
            # nonexistent file once.
            st = file.stat()
        except FileNotFoundError:
            # This was deleted while were checking; ignore it.
            continue

        if not stat.S_ISDIR(st.st_mode):
            continue

        if secs_to_preserve and st.st_atime >= remove_older_than_time:
            continue

        if dry_run:
            print(f"Would remove {file}")
            continue

        this_iteration_had_errors = False

        def OnError(_func, path_name, excinfo):
            nonlocal this_iteration_had_errors
            this_iteration_had_errors = True
            print(f"Failed removing path at {path_name}; traceback:")
            traceback.print_exception(*excinfo)

        shutil.rmtree(file, onerror=OnError)

        # Some errors can be other processes racing with us to delete things. Don't
        # count those as an error which we complain loudly about.
        if this_iteration_had_errors:
            if file.exists():
                had_errors = True
            else:
                print(
                    f"Discarding removal errors for {file}; dir was still removed."
                )

    return 1 if had_errors else 0


def IsChromeOsTmpDeletionCandidate(file_name: str):
    """Returns whether the given basename can be deleted from a chroot's /tmp."""
    name_prefixes = (
        "test_that_",
        "cros-update",
        "CrAU_temp_data",
    )
    if any(file_name.startswith(x) for x in name_prefixes):
        return True
    # Remove files that look like `tmpABCDEFGHI`.
    return len(file_name) == 9 and file_name.startswith("tmp")


def CleanChromeOsTmpFiles(
    chroot_tmp: str, days_to_preserve: int, dry_run: bool
) -> int:
    # Clean chroot/tmp/test_that_* and chroot/tmp/tmpxxxxxx, that were last
    # accessed more than specified time ago.
    return RemoveAllSubdirsMatchingPredicate(
        Path(chroot_tmp),
        days_to_preserve,
        dry_run,
        IsChromeOsTmpDeletionCandidate,
    )


def CleanChromeOsImageFiles(
    chroot_tmp, subdir_suffix, days_to_preserve, dry_run
):
    # Clean files that were last accessed more than the specified time.
    seconds_delta = days_to_preserve * 24 * 3600
    now = time.time()
    errors = 0

    for tmp_dir in os.listdir(chroot_tmp):
        # Directory under /tmp
        tmp_dir = os.path.join(chroot_tmp, tmp_dir)
        if tmp_dir.endswith(subdir_suffix):
            # Tmp directory which ends with subdir_suffix.
            for subdir in os.listdir(tmp_dir):
                # Subdirectories targeted for deletion.
                subdir_path = os.path.join(tmp_dir, subdir)
                if now - os.path.getatime(subdir_path) > seconds_delta:
                    if dry_run:
                        print(f"Will run:\nshutil.rmtree({subdir_path!r})")
                    else:
                        try:
                            shutil.rmtree(subdir_path)
                            print(
                                "Successfully cleaned chromeos image autotest directories "
                                f"from {subdir_path!r}."
                            )
                        except OSError:
                            print(
                                "Some image autotest directories were not removed from "
                                f'"{subdir_path}".'
                            )
                            errors += 1

    return errors


def CleanChromeOsTmpAndImages(days_to_preserve=1, dry_run=False):
    """Delete temporaries, images under crostc/chromeos."""
    chromeos_chroot_tmp = os.path.join(
        constants.CROSTC_WORKSPACE, "chromeos", "chroot", "tmp"
    )
    # Clean files in tmp directory
    rv = CleanChromeOsTmpFiles(chromeos_chroot_tmp, days_to_preserve, dry_run)
    # Clean image files in *-tryjob directories
    rv += CleanChromeOsImageFiles(
        chromeos_chroot_tmp, "-tryjob", days_to_preserve, dry_run
    )
    # Clean image files in *-release directories
    rv += CleanChromeOsImageFiles(
        chromeos_chroot_tmp, "-release", days_to_preserve, dry_run
    )
    # Clean image files in *-pfq directories
    rv += CleanChromeOsImageFiles(
        chromeos_chroot_tmp, "-pfq", days_to_preserve, dry_run
    )
    # Clean image files in *-llvm-next-nightly directories
    rv += CleanChromeOsImageFiles(
        chromeos_chroot_tmp, "-llvm-next-nightly", days_to_preserve, dry_run
    )

    return rv


def CleanOldCLs(days_to_preserve="1", dry_run=False):
    """Abandon old CLs created by automation tooling."""
    ce = command_executer.GetCommandExecuter()
    chromeos_root = os.path.join(constants.CROSTC_WORKSPACE, "chromeos")
    # Find Old CLs.
    old_cls_cmd = (
        'gerrit --raw search "owner:me status:open age:%sd"' % days_to_preserve
    )
    _, cls, _ = ce.ChrootRunCommandWOutput(
        chromeos_root, old_cls_cmd, print_to_console=False
    )
    # Convert any whitespaces to spaces.
    cls = " ".join(cls.split())
    if not cls:
        return 0

    abandon_cls_cmd = "gerrit abandon %s" % cls
    if dry_run:
        print("Going to execute: %s" % abandon_cls_cmd)
        return 0

    return ce.ChrootRunCommand(
        chromeos_root, abandon_cls_cmd, print_to_console=False
    )


def CleanChromeTelemetryTmpFiles(dry_run: bool) -> int:
    tmp_dir = (
        Path(constants.CROSTC_WORKSPACE)
        / "chromeos"
        / ".cache"
        / "distfiles"
        / "chrome-src-internal"
        / "src"
        / "tmp"
    )
    return RemoveAllSubdirsMatchingPredicate(
        tmp_dir,
        days_to_preserve=0,
        dry_run=dry_run,
        is_name_removal_worthy=lambda x: x.startswith("tmp")
        and x.endswith("telemetry_Crosperf"),
    )


def Main(argv):
    """Delete nightly test data directories, tmps and test images."""
    options = ProcessArguments(argv)
    # Function 'isoweekday' returns 1(Monday) - 7 (Sunday).
    d = datetime.datetime.today().isoweekday()
    # We go back 1 week, delete from that day till we are
    # options.days_to_preserve away from today.
    s = d - 7
    e = d - int(options.days_to_preserve)
    rv = 0
    for i in range(s + 1, e):
        if i <= 0:
            ## Wrap around if index is negative.  6 is from i + 7 - 1, because
            ## DIR_BY_WEEKDAY starts from 0, while isoweekday is from 1-7.
            dated_dir = DIR_BY_WEEKDAY[i + 6]
        else:
            dated_dir = DIR_BY_WEEKDAY[i - 1]

        rv += (
            0
            if CleanDatedDir(
                os.path.join(NIGHTLY_TESTS_WORKSPACE, dated_dir),
                options.dry_run,
            )
            else 1
        )

    ## Clean temporaries, images under crostc/chromeos
    rv2 = CleanChromeOsTmpAndImages(
        int(options.days_to_preserve), options.dry_run
    )

    # Clean CLs that are not updated in last 2 weeks.
    rv3 = CleanOldCLs("14", options.dry_run)

    # Clean telemetry temporaries from chrome source tree inside chroot.
    rv4 = CleanChromeTelemetryTmpFiles(options.dry_run)

    return rv + rv2 + rv3 + rv4


if __name__ == "__main__":
    retval = Main(sys.argv[1:])
    sys.exit(retval)
