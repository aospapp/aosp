#!/usr/bin/python
# Copyright 2017 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""
This is a utility to build a summary of the given directory. and save to a json
file.

Example usage:
    result_utils.py -p path

The content of the json file looks like:
{'default': {'/D': {'control': {'/S': 734},
                      'debug': {'/D': {'client.0.DEBUG': {'/S': 5698},
                                       'client.0.ERROR': {'/S': 254},
                                       'client.0.INFO': {'/S': 1020},
                                       'client.0.WARNING': {'/S': 242}},
                                '/S': 7214}
                      },
              '/S': 7948
            }
}
"""

import argparse
import copy
import glob
import json
import logging
import os
import time

import utils_lib


# Do NOT import autotest_lib modules here. This module can be executed without
# dependency on other autotest modules. This is to keep the logic of result
# trimming on the server side, instead of depending on the autotest client
# module.

DEFAULT_SUMMARY_FILENAME_FMT = 'dir_summary_%d.json'
# Minimum disk space should be available after saving the summary file.
MIN_FREE_DISK_BYTES = 10 * 1024 * 1024

# Autotest uses some state files to track process running state. The files are
# deleted from test results. Therefore, these files can be ignored.
FILES_TO_IGNORE = set([
    'control.autoserv.state'
])

def get_unique_dir_summary_file(path):
    """Get a unique file path to save the directory summary json string.

    @param path: The directory path to save the summary file to.
    """
    summary_file = DEFAULT_SUMMARY_FILENAME_FMT % time.time()
    # Make sure the summary file name is unique.
    file_name = os.path.join(path, summary_file)
    if os.path.exists(file_name):
        count = 1
        name, ext = os.path.splitext(summary_file)
        while os.path.exists(file_name):
            file_name = os.path.join(path, '%s_%s%s' % (name, count, ext))
            count += 1
    return file_name


def get_dir_summary(path, top_dir, all_dirs=set()):
    """Get the directory summary for the given path.

    @param path: The directory to collect summary.
    @param top_dir: The top directory to collect summary. This is to check if a
            directory is a subdir of the original directory to collect summary.
    @param all_dirs: A set of paths that have been collected. This is to prevent
            infinite recursive call caused by symlink.

    @return: A dictionary of the directory summary.
    """
    dir_info = {}
    dir_info[utils_lib.ORIGINAL_SIZE_BYTES] = 0
    summary = {os.path.basename(path): dir_info}

    if os.path.isfile(path):
        dir_info[utils_lib.ORIGINAL_SIZE_BYTES] = os.stat(path).st_size
    else:
        dir_info[utils_lib.DIRS] = {}
        real_path = os.path.realpath(path)
        # The assumption here is that results are copied back to drone by
        # copying the symlink, not the content, which is true with currently
        # used rsync in cros_host.get_file call.
        # Skip scanning the child folders if any of following condition is true:
        # 1. The directory is a symlink and link to a folder under `top_dir`.
        # 2. The directory was scanned already.
        if ((os.path.islink(path) and real_path.startswith(top_dir)) or
            real_path in all_dirs):
            return summary

        all_dirs.add(real_path)
        for f in sorted(os.listdir(path)):
            f_summary = get_dir_summary(os.path.join(path, f), top_dir,
                                        all_dirs)
            dir_info[utils_lib.DIRS][f] = f_summary[f]
            dir_info[utils_lib.ORIGINAL_SIZE_BYTES] += (
                    f_summary[f][utils_lib.ORIGINAL_SIZE_BYTES])

    return summary


def build_summary_json(path):
    """Build summary of files in the given path and return a json string.

    @param path: The directory to build summary.
    @return: A json string of the directory summary.
    @raise IOError: If the given path doesn't exist.
    """
    if not os.path.exists(path):
        raise IOError('Path %s does not exist.' % path)

    if not os.path.isdir(path):
        raise ValueError('The given path %s is a file. It must be a '
                         'directory.' % path)

    # Make sure the path ends with `/` so the root key of summary json is always
    # utils_lib.ROOT_DIR ('')
    if not path.endswith(os.sep):
        path = path + os.sep

    return get_dir_summary(path, top_dir=path)


def _update_sizes(entry):
    """Update a directory entry's sizes.

    Values of ORIGINAL_SIZE_BYTES, TRIMMED_SIZE_BYTES and COLLECTED_SIZE_BYTES
    are re-calculated based on the files under the directory. If the entry is a
    file, skip the updating.

    @param entry: A dict of directory entry in a summary.
    """
    if utils_lib.DIRS not in entry:
        return

    entry[utils_lib.ORIGINAL_SIZE_BYTES] = sum([
            entry[utils_lib.DIRS][s][utils_lib.ORIGINAL_SIZE_BYTES]
            for s in entry[utils_lib.DIRS]])
    # Before trimming is implemented, COLLECTED_SIZE_BYTES and
    # TRIMMED_SIZE_BYTES have the same value of ORIGINAL_SIZE_BYTES.
    entry[utils_lib.COLLECTED_SIZE_BYTES] = sum([
            entry[utils_lib.DIRS][s].get(
                utils_lib.COLLECTED_SIZE_BYTES,
                entry[utils_lib.DIRS][s].get(
                    utils_lib.TRIMMED_SIZE_BYTES,
                    entry[utils_lib.DIRS][s][utils_lib.ORIGINAL_SIZE_BYTES]))
            for s in entry[utils_lib.DIRS]])
    entry[utils_lib.TRIMMED_SIZE_BYTES] = sum([
            entry[utils_lib.DIRS][s].get(
                    utils_lib.TRIMMED_SIZE_BYTES,
                    entry[utils_lib.DIRS][s][utils_lib.ORIGINAL_SIZE_BYTES])
            for s in entry[utils_lib.DIRS]])


def _delete_missing_entries(summary_old, summary_new):
    """Delete files/directories only exists in old summary.

    When the new summary is final, i.e., it's built from the final result
    directory, files or directories missing are considered to be deleted and
    trimmed to size 0.

    @param summary_old: Old directory summary.
    @param summary_new: New directory summary.
    """
    for name in summary_old.keys():
        if name not in summary_new:
            if utils_lib.DIRS in summary_old[name]:
                # Trim sub-directories.
                _delete_missing_entries(summary_old[name][utils_lib.DIRS], {})
                _update_sizes(summary_old[name])
            elif name in FILES_TO_IGNORE:
                # Remove the file from the summary as it can be ignored.
                del summary_old[name]
            else:
                # Before setting the trimmed size to 0, update the collected
                # size if it's not set yet.
                if utils_lib.COLLECTED_SIZE_BYTES not in summary_old[name]:
                    trimmed_size = summary_old[name].get(
                            utils_lib.TRIMMED_SIZE_BYTES,
                            summary_old[name][utils_lib.ORIGINAL_SIZE_BYTES])
                    summary_old[name][utils_lib.COLLECTED_SIZE_BYTES] = (
                            trimmed_size)
                summary_old[name][utils_lib.TRIMMED_SIZE_BYTES] = 0
        elif utils_lib.DIRS in summary_old[name]:
            _delete_missing_entries(summary_old[name][utils_lib.DIRS],
                                    summary_new[name][utils_lib.DIRS])
            _update_sizes(summary_old[name])
    _update_sizes(summary_old)


def _merge(summary_old, summary_new, is_final=False):
    """Merge a new directory summary to an old one.

    Update the old directory summary with the new summary. Also calculate the
    total size of results collected from the client side.

    When merging with previously collected results, any results not existing in
    the new summary or files with size different from the new files collected
    are considered as extra results collected or overwritten by the new results.
    Therefore, the size of the collected result should include such files, and
    the COLLECTED_SIZE_BYTES can be larger than TRIMMED_SIZE_BYTES.
    As an example:
    summary_old: {'file1': {TRIMMED_SIZE_BYTES: 1000,
                            ORIGINAL_SIZE_BYTES: 1000,
                            COLLECTED_SIZE_BYTES: 1000}}
    This means a result `file1` of original size 1KB was collected with size of
    1KB byte.
    summary_new: {'file1': {TRIMMED_SIZE_BYTES: 1000,
                            ORIGINAL_SIZE_BYTES: 2000,
                            COLLECTED_SIZE_BYTES: 1000}}
    This means a result `file1` of 2KB was trimmed down to 1KB and was collected
    with size of 1KB byte.
    Note that the second result collection has an updated result `file1`
    (because of the different ORIGINAL_SIZE_BYTES), and it needs to be rsync-ed
    to the drone. Therefore, the merged summary will be:
    {'file1': {TRIMMED_SIZE_BYTES: 1000,
               ORIGINAL_SIZE_BYTES: 2000,
               COLLECTED_SIZE_BYTES: 2000}}
    Note that:
    * TRIMMED_SIZE_BYTES is still at 1KB, which reflects the actual size of the
      file be collected.
    * ORIGINAL_SIZE_BYTES is updated to 2KB, which is the size of the file in
      the new result `file1`.
    * COLLECTED_SIZE_BYTES is 2KB because rsync will copy `file1` twice as it's
      changed.

    @param summary_old: Old directory summary.
    @param summary_new: New directory summary.
    @param is_final: True if summary_new is built from the final result folder.
            Default is set to False.
    @return: A tuple of (bytes_diff, merged_summary):
            bytes_diff: The size of results collected based on the diff of the
                old summary and the new summary.
            merged_summary: Merged directory summary.
    """
    for name in summary_new:
        if not name in summary_old:
            # A file/dir exists in new client dir, but not in the old one, which
            # means that the file or a directory is newly collected.
            summary_old[name] = copy.deepcopy(summary_new[name])
        elif utils_lib.DIRS in summary_new[name]:
            # `name` is a directory in new summary, merge the directories of the
            # old and new summaries under `name`.

            if utils_lib.DIRS not in summary_old[name]:
                # If `name` is a file in old summary but a directory in new
                # summary, the file in the old summary will be overwritten by
                # the new directory by rsync. Therefore, force it to be an empty
                # directory in old summary, so that the new directory can be
                # merged.
                summary_old[name][utils_lib.ORIGINAL_SIZE_BYTES] = 0
                summary_old[name][utils_lib.TRIMMED_SIZE_BYTES] = 0
                summary_old[name][utils_lib.COLLECTED_SIZE_BYTES] = 0
                summary_old[name][utils_lib.DIRS] = {}

            _merge(summary_old[name][utils_lib.DIRS],
                   summary_new[name][utils_lib.DIRS], is_final)
        else:
            # `name` is a file. Compare the original size, if they are
            # different, the file was overwritten, so increment the
            # COLLECTED_SIZE_BYTES.

            if utils_lib.DIRS in summary_old[name]:
                # If `name` is a directory in old summary, but a file in the new
                # summary, rsync will fail to copy the file as it can't
                # overwrite an directory. Therefore, skip the merge.
                continue

            new_size = summary_new[name][utils_lib.ORIGINAL_SIZE_BYTES]
            old_size = summary_old[name][utils_lib.ORIGINAL_SIZE_BYTES]
            new_trimmed_size = summary_new[name].get(
                    utils_lib.TRIMMED_SIZE_BYTES,
                    summary_new[name][utils_lib.ORIGINAL_SIZE_BYTES])
            old_trimmed_size = summary_old[name].get(
                    utils_lib.TRIMMED_SIZE_BYTES,
                    summary_old[name][utils_lib.ORIGINAL_SIZE_BYTES])
            if new_size != old_size:
                if is_final and new_trimmed_size == old_trimmed_size:
                    # If the file is merged from the final result folder to an
                    # older summary, it's not considered to be trimmed if the
                    # size is not changed. The reason is that the file on the
                    # server side does not have the info of its original size.
                    continue

                # Before trimming is implemented, COLLECTED_SIZE_BYTES is the
                # value of ORIGINAL_SIZE_BYTES.
                new_collected_size = summary_new[name].get(
                        utils_lib.COLLECTED_SIZE_BYTES,
                        summary_new[name].get(
                            utils_lib.TRIMMED_SIZE_BYTES,
                            summary_new[name][utils_lib.ORIGINAL_SIZE_BYTES]))
                old_collected_size = summary_old[name].get(
                        utils_lib.COLLECTED_SIZE_BYTES,
                        summary_old[name].get(
                            utils_lib.TRIMMED_SIZE_BYTES,
                            summary_old[name][utils_lib.ORIGINAL_SIZE_BYTES]))

                summary_old[name][utils_lib.COLLECTED_SIZE_BYTES] = (
                        new_collected_size + old_collected_size)
                summary_old[name][utils_lib.TRIMMED_SIZE_BYTES] = (
                        summary_new[name].get(
                            utils_lib.TRIMMED_SIZE_BYTES,
                            summary_new[name][utils_lib.ORIGINAL_SIZE_BYTES]))
                summary_old[name][utils_lib.ORIGINAL_SIZE_BYTES] = new_size

        # Update COLLECTED_SIZE_BYTES and ORIGINAL_SIZE_BYTES based on the
        # merged directory summary.
        _update_sizes(summary_old[name])


def merge_summaries(path):
    """Merge all directory summaries in the given path.

    This function calculates the total size of result files being collected for
    the test device and the files generated on the drone. It also returns merged
    directory summary.

    @param path: A path to search for directory summaries.
    @return a tuple of (client_collected_bytes, merged_summary):
            client_collected_bytes: The total size of results collected from
                the DUT. The number can be larger than the total file size of
                the given path, as files can be overwritten or removed.
            merged_summary: The merged directory summary of the given path.
    """
    # Find all directory summary files and sort them by the time stamp in file
    # name.
    summary_files = glob.glob(os.path.join(path, 'dir_summary_*.json'))
    summary_files = sorted(summary_files, key=os.path.getmtime)

    all_summaries = []
    for summary_file in summary_files:
        with open(summary_file) as f:
            all_summaries.append(json.load(f))

    # Merge all summaries.
    merged_summary = (copy.deepcopy(all_summaries[0]) if len(all_summaries) > 0
                      else {})
    for summary in all_summaries[1:]:
        _merge(merged_summary, summary)
    # After all summaries from the test device (client side) are merged, we can
    # get the total size of result files being transfered from the test device.
    # If there is no directory summary collected, default client_collected_bytes
    # to 0.
    client_collected_bytes = 0
    if merged_summary:
        client_collected_bytes = (
            merged_summary[utils_lib.ROOT_DIR][utils_lib.COLLECTED_SIZE_BYTES])

    # Get the summary of current directory

    # Make sure the path ends with /, so the top directory in the summary will
    # be '', which is consistent with other summaries.
    if not path.endswith(os.sep):
        path += os.sep

    last_summary = get_dir_summary(path, top_dir=path)
    _merge(merged_summary, last_summary, is_final=True)
    _delete_missing_entries(merged_summary, last_summary)

    return client_collected_bytes, merged_summary


def main():
    """main script. """
    parser = argparse.ArgumentParser()
    parser.add_argument('-p', type=str, dest='path',
                        help='Path to build directory summary.')
    parser.add_argument('-m', type=int, dest='max_size_KB', default=0,
                        help='Maximum result size in KB. Set to 0 to disable '
                        'result throttling.')
    options = parser.parse_args()

    summary = build_summary_json(options.path)
    summary_json = json.dumps(summary)
    summary_file = get_unique_dir_summary_file(options.path)

    # Make sure there is enough free disk to write the file
    stat = os.statvfs(options.path)
    free_space = stat.f_frsize * stat.f_bavail
    if free_space - len(summary_json) < MIN_FREE_DISK_BYTES:
        raise IOError('Not enough disk space after saving the summary file. '
                      'Available free disk: %s bytes. Summary file size: %s '
                      'bytes.' % (free_space, len(summary_json)))

    with open(summary_file, 'w') as f:
        f.write(summary_json)
    logging.info('Directory summary of %s is saved to file %s.', options.path,
                 summary_file)


if __name__ == '__main__':
    main()
