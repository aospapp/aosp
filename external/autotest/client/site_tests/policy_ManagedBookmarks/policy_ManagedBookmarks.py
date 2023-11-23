# Copyright 2015 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging

from autotest_lib.client.common_lib import error
from autotest_lib.client.cros.enterprise import enterprise_policy_base


class policy_ManagedBookmarks(enterprise_policy_base.EnterprisePolicyTest):
    """
    Test effect of ManagedBookmarks policy on Chrome OS behavior.

    This test verifies the behavior of Chrome OS for a range of valid values
    of the ManagedBookmarks user policy, as defined by three test cases:
    NotSet_NotShown, SingleBookmark_Shown, and MultiBookmarks_Shown.

    When not set, the policy value is undefined. This induces the default
    behavior of not showing the managed bookmarks folder, which is equivalent
    to what is seen by an un-managed user.

    When one or more bookmarks are specified by the policy, then the Managed
    Bookmarks folder is shown, and the specified bookmarks within it.

    """
    version = 1

    POLICY_NAME = 'ManagedBookmarks'
    BOOKMARKS = [{'name': 'Google',
                   'url': 'https://google.com/'},
                 {'name': 'YouTube',
                   'url': 'https://youtube.com/'},
                 {'name': 'Chromium',
                   'url': 'https://chromium.org/'}]

    # Dictionary of test case names and policy values.
    TEST_CASES = {
        'NotSet_NotShown': None,
        'SingleBookmark_Shown': BOOKMARKS[:1],
        'MultipleBookmarks_Shown': BOOKMARKS
    }


    def _get_managed_bookmarks(self):
        """
        Return a list of the managed bookmarks.

        @returns displayed_bookmarks: a list containing dictionaries of the
            managed bookmarks.
        """
        # Open Bookmark Manager.
        tab = self.navigate_to_url('chrome://bookmarks')

        # Nodes are all bookmarks and directories.
        nodes = tab.EvaluateJavaScript(
                    'bookmarks.StoreClient[1].getState().nodes')

        displayed_bookmarks = []
        for node in nodes.values():
            # The node with parentId 0 is the managed bookmarks directory.
            if (node.get('unmodifiable') == 'managed' and
                    node['parentId'] != '0'):
                bookmark = {'name': node['title'], 'url': node['url']}
                displayed_bookmarks.append(bookmark)

        return displayed_bookmarks


    def _test_managed_bookmarks(self, policy_value):
        """
        Verify CrOS enforces ManagedBookmarks policy.

        When ManagedBookmarks is not set, the UI shall not show the managed
        bookmarks folder nor its contents. When set to one or more bookmarks
        the UI shows the folder and its contents.

        @param policy_value: policy value for this case.

        @raises error.TestFail: If displayed managed bookmarks does not match
            the policy value.

        """
        managed = self._get_managed_bookmarks()

        if policy_value is None:
            if managed:
                raise error.TestFail('Managed bookmarks should not be set.')
        else:
            if sorted(managed) != sorted(policy_value):
                raise error.TestFail('Managed bookmarks (%s) '
                                     'do not match policy value (%s).'
                                     % (sorted(managed), sorted(policy_value)))


    def run_once(self, case):
        """
        Setup and run the test configured for the specified test case.

        @param case: Name of the test case to run.

        """
        case_value = self.TEST_CASES[case]
        self.setup_case(user_policies={self.POLICY_NAME: case_value})
        self._test_managed_bookmarks(case_value)
