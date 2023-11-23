# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Module to check updates from Git upstream."""

import base_updater
import git_utils
# pylint: disable=import-error
import metadata_pb2  # type: ignore
import updater_utils


class GitUpdater(base_updater.Updater):
    """Updater for Git upstream."""
    UPSTREAM_REMOTE_NAME: str = "update_origin"

    def is_supported_url(self) -> bool:
        return git_utils.is_valid_url(self._proj_path, self._old_url.value)

    @staticmethod
    def _is_likely_android_remote(url: str) -> bool:
        """Returns True if the URL is likely to be the project's Android remote."""
        # There isn't a strict rule for finding the correct remote for upstream-master,
        # so we have to guess. Be careful to filter out things that look almost right
        # but aren't. Here's an example of a project that has a lot of false positives:
        # 
        # aosp    /usr/local/google/home/danalbert/src/mirrors/android/refs/aosp/toolchain/rr.git (fetch)
        # aosp    persistent-https://android.git.corp.google.com/toolchain/rr (push)
        # origin  https://github.com/DanAlbert/rr.git (fetch)
        # origin  https://github.com/DanAlbert/rr.git (push)
        # unmirrored      persistent-https://android.git.corp.google.com/toolchain/rr (fetch)
        # unmirrored      persistent-https://android.git.corp.google.com/toolchain/rr (push)
        # update_origin   https://github.com/rr-debugger/rr (fetch)
        # update_origin   https://github.com/rr-debugger/rr (push)
        # upstream        https://github.com/rr-debugger/rr.git (fetch)
        # upstream        https://github.com/rr-debugger/rr.git (push)
        #
        # unmirrored is the correct remote here. It's not a local path, and contains
        # either /platform/external/ or /toolchain/ (the two common roots for third-
        # party Android imports).
        if '://' not in url:
            # Skip anything that's likely a local GoB mirror.
            return False
        if '/platform/external/' in url:
            return True
        if '/toolchain/' in url:
            return True
        return False

    def _setup_remote(self) -> None:
        remotes = git_utils.list_remotes(self._proj_path)
        current_remote_url = None
        android_remote_name: str | None = None
        for name, url in remotes.items():
            if name == self.UPSTREAM_REMOTE_NAME:
                current_remote_url = url

            if self._is_likely_android_remote(url):
                android_remote_name = name

        if android_remote_name is None:
            remotes_formatted = "\n".join(f"{k} {v}" for k, v in remotes.items())
            raise RuntimeError(
                f"Could not determine android remote for {self._proj_path}. Tried:\n"
                f"{remotes_formatted}")

        if current_remote_url is not None and current_remote_url != self._old_url.value:
            git_utils.remove_remote(self._proj_path, self.UPSTREAM_REMOTE_NAME)
            current_remote_url = None

        if current_remote_url is None:
            git_utils.add_remote(self._proj_path, self.UPSTREAM_REMOTE_NAME,
                                 self._old_url.value)

        git_utils.fetch(self._proj_path,
                        [self.UPSTREAM_REMOTE_NAME, android_remote_name])

    def check(self) -> None:
        """Checks upstream and returns whether a new version is available."""
        self._setup_remote()
        if git_utils.is_commit(self._old_ver):
            # Update to remote head.
            self._check_head()
        else:
            # Update to latest version tag.
            self._check_tag()

    def _check_tag(self) -> None:
        tags = git_utils.list_remote_tags(self._proj_path,
                                          self.UPSTREAM_REMOTE_NAME)
        self._new_ver = updater_utils.get_latest_version(self._old_ver, tags)

    def _check_head(self) -> None:
        branch = git_utils.detect_default_branch(self._proj_path,
                                                 self.UPSTREAM_REMOTE_NAME)
        self._new_ver = git_utils.get_sha_for_branch(
            self._proj_path, self.UPSTREAM_REMOTE_NAME + '/' + branch)

    def update(self, skip_post_update: bool) -> None:
        """Updates the package.
        Has to call check() before this function.
        """
        print(f"Running `git merge {self._new_ver}`...")
        git_utils.merge(self._proj_path, self._new_ver)
        if not skip_post_update:
            updater_utils.run_post_update(self._proj_path, self._proj_path)