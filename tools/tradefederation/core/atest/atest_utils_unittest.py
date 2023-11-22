#!/usr/bin/env python
#
# Copyright 2018, The Android Open Source Project
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

"""Unittests for atest_utils."""

import subprocess
import sys
import unittest
import mock

import atest_utils

if sys.version_info[0] == 2:
    from StringIO import StringIO
else:
    from io import StringIO

#pylint: disable=protected-access
class AtestUtilsUnittests(unittest.TestCase):
    """Unit tests for atest_utils.py"""

    def test_capture_fail_section_has_fail_section(self):
        """Test capture_fail_section when has fail section."""
        test_list = ['AAAAAA', 'FAILED: Error1', '^\n', 'Error2\n',
                     '[  6% 191/2997] BBBBBB\n', 'CCCCC',
                     '[  20% 322/2997] DDDDDD\n', 'EEEEE']
        want_list = ['FAILED: Error1', '^\n', 'Error2\n']
        self.assertEqual(want_list,
                         atest_utils._capture_fail_section(test_list))

    def test_capture_fail_section_no_fail_section(self):
        """Test capture_fail_section when no fail section."""
        test_list = ['[ 6% 191/2997] XXXXX', 'YYYYY: ZZZZZ']
        want_list = []
        self.assertEqual(want_list,
                         atest_utils._capture_fail_section(test_list))

    def test_is_test_mapping(self):
        """Test method is_test_mapping."""
        tm_option_attributes = [
            'test_mapping',
            'include_subdirs'
        ]
        for attr_to_test in tm_option_attributes:
            args = mock.Mock()
            for attr in tm_option_attributes:
                setattr(args, attr, attr == attr_to_test)
            args.tests = []
            self.assertTrue(
                atest_utils.is_test_mapping(args),
                'Failed to validate option %s' % attr_to_test)

        args = mock.Mock()
        for attr in tm_option_attributes:
            setattr(args, attr, False)
        args.tests = [':group_name']
        self.assertTrue(atest_utils.is_test_mapping(args))

        args = mock.Mock()
        for attr in tm_option_attributes:
            setattr(args, attr, False)
        args.tests = [':test1', 'test2']
        self.assertFalse(atest_utils.is_test_mapping(args))

        args = mock.Mock()
        for attr in tm_option_attributes:
            setattr(args, attr, False)
        args.tests = ['test2']
        self.assertFalse(atest_utils.is_test_mapping(args))

    @mock.patch('curses.tigetnum')
    def test_has_colors(self, mock_curses_tigetnum):
        """Test method _has_colors."""
        # stream is file I/O
        stream = open('/tmp/test_has_colors.txt', 'wb')
        self.assertFalse(atest_utils._has_colors(stream))
        stream.close()

        # stream is not a tty(terminal).
        stream = mock.Mock()
        stream.isatty.return_value = False
        self.assertFalse(atest_utils._has_colors(stream))

        # stream is a tty(terminal) and colors < 2.
        stream = mock.Mock()
        stream.isatty.return_value = True
        mock_curses_tigetnum.return_value = 1
        self.assertFalse(atest_utils._has_colors(stream))

        # stream is a tty(terminal) and colors > 2.
        stream = mock.Mock()
        stream.isatty.return_value = True
        mock_curses_tigetnum.return_value = 256
        self.assertTrue(atest_utils._has_colors(stream))


    @mock.patch('atest_utils._has_colors')
    def test_colorize(self, mock_has_colors):
        """Test method colorize."""
        original_str = "test string"
        green_no = 2

        # _has_colors() return False.
        mock_has_colors.return_value = False
        converted_str = atest_utils.colorize(original_str, green_no,
                                             highlight=True)
        self.assertEqual(original_str, converted_str)

        # Green with highlight.
        mock_has_colors.return_value = True
        converted_str = atest_utils.colorize(original_str, green_no,
                                             highlight=True)
        green_highlight_string = '\x1b[1;42m%s\x1b[0m' % original_str
        self.assertEqual(green_highlight_string, converted_str)

        # Green, no highlight.
        mock_has_colors.return_value = True
        converted_str = atest_utils.colorize(original_str, green_no,
                                             highlight=False)
        green_no_highlight_string = '\x1b[1;32m%s\x1b[0m' % original_str
        self.assertEqual(green_no_highlight_string, converted_str)


    @mock.patch('atest_utils._has_colors')
    def test_colorful_print(self, mock_has_colors):
        """Test method colorful_print."""
        testing_str = "color_print_test"
        green_no = 2

        # _has_colors() return False.
        mock_has_colors.return_value = False
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.colorful_print(testing_str, green_no, highlight=True,
                                   auto_wrap=False)
        sys.stdout = sys.__stdout__
        uncolored_string = testing_str
        self.assertEqual(capture_output.getvalue(), uncolored_string)

        # Green with highlight, but no wrap.
        mock_has_colors.return_value = True
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.colorful_print(testing_str, green_no, highlight=True,
                                   auto_wrap=False)
        sys.stdout = sys.__stdout__
        green_highlight_no_wrap_string = '\x1b[1;42m%s\x1b[0m' % testing_str
        self.assertEqual(capture_output.getvalue(),
                         green_highlight_no_wrap_string)

        # Green, no highlight, no wrap.
        mock_has_colors.return_value = True
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.colorful_print(testing_str, green_no, highlight=False,
                                   auto_wrap=False)
        sys.stdout = sys.__stdout__
        green_no_high_no_wrap_string = '\x1b[1;32m%s\x1b[0m' % testing_str
        self.assertEqual(capture_output.getvalue(),
                         green_no_high_no_wrap_string)

        # Green with highlight and wrap.
        mock_has_colors.return_value = True
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.colorful_print(testing_str, green_no, highlight=True,
                                   auto_wrap=True)
        sys.stdout = sys.__stdout__
        green_highlight_wrap_string = '\x1b[1;42m%s\x1b[0m\n' % testing_str
        self.assertEqual(capture_output.getvalue(), green_highlight_wrap_string)

        # Green with wrap, but no highlight.
        mock_has_colors.return_value = True
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.colorful_print(testing_str, green_no, highlight=False,
                                   auto_wrap=True)
        sys.stdout = sys.__stdout__
        green_wrap_no_highlight_string = '\x1b[1;32m%s\x1b[0m\n' % testing_str
        self.assertEqual(capture_output.getvalue(),
                         green_wrap_no_highlight_string)

    @mock.patch('subprocess.check_output')
    def test_is_external_run(self, mock_output):
        """Test method is_external_run."""
        mock_output.return_value = ''
        self.assertTrue(atest_utils.is_external_run())
        mock_output.return_value = 'test@other.com'
        self.assertTrue(atest_utils.is_external_run())
        mock_output.return_value = 'test@google.com'
        self.assertFalse(atest_utils.is_external_run())
        mock_output.side_effect = OSError()
        self.assertTrue(atest_utils.is_external_run())
        mock_output.side_effect = subprocess.CalledProcessError(1, 'cmd')
        self.assertTrue(atest_utils.is_external_run())

    @mock.patch('atest_utils.is_external_run')
    def test_print_data_collection_notice(self, mock_is_external_run):
        """Test method print_data_collection_notice."""

        # is_external_run return False.
        mock_is_external_run.return_value = True
        notice_str = ('\n==================\nNotice:\n'
                      '  We collect anonymous usage statistics'
                      ' in accordance with our'
                      ' Content Licenses (https://source.android.com/setup/start/licenses),'
                      ' Contributor License Agreement (https://opensource.google.com/docs/cla/),'
                      ' Privacy Policy (https://policies.google.com/privacy) and'
                      ' Terms of Service (https://policies.google.com/terms).'
                      '\n==================\n\n')
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.print_data_collection_notice()
        sys.stdout = sys.__stdout__
        uncolored_string = notice_str
        self.assertEqual(capture_output.getvalue(), uncolored_string)

        # is_external_run return False.
        mock_is_external_run.return_value = False
        notice_str = ('\n==================\nNotice:\n'
                      '  We collect usage statistics'
                      ' in accordance with our'
                      ' Content Licenses (https://source.android.com/setup/start/licenses),'
                      ' Contributor License Agreement (https://cla.developers.google.com/),'
                      ' Privacy Policy (https://policies.google.com/privacy) and'
                      ' Terms of Service (https://policies.google.com/terms).'
                      '\n==================\n\n')
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.print_data_collection_notice()
        sys.stdout = sys.__stdout__
        uncolored_string = notice_str
        self.assertEqual(capture_output.getvalue(), uncolored_string)


if __name__ == "__main__":
    unittest.main()
