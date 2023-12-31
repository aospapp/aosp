#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Copyright 2014 The ChromiumOS Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""The unittest of flags."""


import unittest

import test_flag


class FlagTestCase(unittest.TestCase):
    """The unittest class."""

    def test_test_flag(self):
        # Verify that test_flag.is_test exists, that it is a list,
        # and that it contains 1 element.
        self.assertTrue(isinstance(test_flag.is_test, list))
        self.assertEqual(len(test_flag.is_test), 1)

        # Verify that the getting the flag works and that the flag
        # contains False, its starting value.
        save_flag = test_flag.GetTestMode()
        self.assertFalse(save_flag)

        # Verify that setting the flat to True, then getting it, works.
        test_flag.SetTestMode(True)
        self.assertTrue(test_flag.GetTestMode())

        # Verify that setting the flag to False, then getting it, works.
        test_flag.SetTestMode(save_flag)
        self.assertFalse(test_flag.GetTestMode())

        # Verify that test_flag.is_test still exists, that it still is a
        # list, and that it still contains 1 element.
        self.assertTrue(isinstance(test_flag.is_test, list))
        self.assertEqual(len(test_flag.is_test), 1)


if __name__ == "__main__":
    unittest.main()
