# Copyright 2015 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.enterprise import enterprise_policy_base


class policy_EditBookmarksEnabled(enterprise_policy_base.EnterprisePolicyTest):
    """
    Test effect of EditBookmarksEnabled policy on Chrome OS behavior.

    This test verifies the behavior of Chrome OS for all valid values of the
    EditBookmarksEnabled user policy: True, False, and not set. 'Not set'
    means that the policy value is undefined. This should induce the default
    behavior, equivalent to what is seen by an un-managed user.

    When set True or not set, bookmarks can be added, removed, or modified.
    When set False, bookmarks cannot be added, removed, or modified, though
    existing bookmarks (if any) are still available.

    """
    version = 1

    POLICY_NAME = 'EditBookmarksEnabled'

    # Dictionary of named test cases and policy data.
    TEST_CASES = {
        'True_Enable': True,
        'False_Disable': False,
        'NotSet_Enable': None
    }

    def is_edit_bookmark_disabled(self):
        """
        Check whether bookmarks can be edited.

        Checks the value of the 'globalCanEdit_' boolean, which controls whether
        the user can add, edit, or delete bookmarks. Its value should mirror
        the edit bookmark policy.

        @returns: True if globalCanEdit_ is disabled.
        """
        tab = self.navigate_to_url('chrome://bookmarks')

        is_disabled = tab.EvaluateJavaScript(
                'var bcm = bookmarks.CommandManager.getInstance(); '
                '!bcm.globalCanEdit_;')
        logging.info('editing bookmarks is disabled: %s' % is_disabled)
        tab.Close()

        return is_disabled

    def _test_edit_bookmarks_enabled(self, policy_value):
        """
        Verify CrOS enforces EditBookmarksEnabled policy.

        When EditBookmarksEnabled is true or not set, the UI allows the user
        to add bookmarks. When false, the UI does not allow the user to add
        bookmarks.

        Warning: When the 'Bookmark Editing' setting on the CPanel User
        Settings page is set to 'Enable bookmark editing', then the
        EditBookmarksEnabled policy on the client will be not set. Thus, to
        verify the 'Enable bookmark editing' choice from a production or
        staging DMS, use case=NotSet_Enable.

        @param policy_value: policy value for this case.

        """
        edit_bookmark_is_disabled = self.is_edit_bookmark_disabled()
        if policy_value or policy_value is None:
            if edit_bookmark_is_disabled:
                raise error.TestFail('Edit Bookmark should be enabled.')
        else:
            if not edit_bookmark_is_disabled:
                raise error.TestFail('Edit Bookmark should be disabled.')

    def run_once(self, case):
        """
        Set up and run the test configured for the specified test case.

        @param case: Name of the test case to run.

        """
        case_value = self.TEST_CASES[case]
        self.setup_case(user_policies={self.POLICY_NAME: case_value})
        self._test_edit_bookmarks_enabled(case_value)
