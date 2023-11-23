#!/usr/bin/env python3
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

# pylint: disable=invalid-name
# pylint: disable=line-too-long

import hashlib
import os
import subprocess
import sys
import tempfile
import unittest

from io import StringIO
from pathlib import Path
from unittest import mock

# pylint: disable=import-error
from pyfakefs import fake_filesystem_unittest

from atest import atest_arg_parser
from atest import atest_error
from atest import atest_utils
from atest import constants
from atest import unittest_utils
from atest import unittest_constants

from atest.test_finders import test_info
from atest.atest_enum import FilterType

TEST_MODULE_NAME_A = 'ModuleNameA'
TEST_RUNNER_A = 'FakeTestRunnerA'
TEST_BUILD_TARGET_A = set(['bt1', 'bt2'])
TEST_DATA_A = {'test_data_a_1': 'a1',
               'test_data_a_2': 'a2'}
TEST_SUITE_A = 'FakeSuiteA'
TEST_MODULE_CLASS_A = 'FAKE_MODULE_CLASS_A'
TEST_INSTALL_LOC_A = set(['host', 'device'])
TEST_FINDER_A = 'MODULE'
TEST_INFO_A = test_info.TestInfo(TEST_MODULE_NAME_A, TEST_RUNNER_A,
                                 TEST_BUILD_TARGET_A, TEST_DATA_A,
                                 TEST_SUITE_A, TEST_MODULE_CLASS_A,
                                 TEST_INSTALL_LOC_A)
TEST_INFO_A.test_finder = TEST_FINDER_A
TEST_ZIP_DATA_DIR = 'zip_files'
TEST_SINGLE_ZIP_NAME = 'single_file.zip'
TEST_MULTI_ZIP_NAME = 'multi_file.zip'

REPO_INFO_OUTPUT = '''Manifest branch: test_branch
Manifest merge branch: refs/heads/test_branch
Manifest groups: all,-notdefault
----------------------------
'''

# pylint: disable=protected-access
# pylint: disable=too-many-public-methods
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

    def test_is_test_mapping_none_test_mapping_args(self):
        """Test method is_test_mapping."""
        parser = atest_arg_parser.AtestArgParser()
        parser.add_atest_args()
        non_tm_args = ['--host-unit-test-only', '--smart-testing-local']

        for argument in non_tm_args:
            args = parser.parse_args([argument])
            self.assertFalse(
                atest_utils.is_test_mapping(args),
                'Option %s indicates NOT a test_mapping!' % argument)

    def test_is_test_mapping_test_mapping_args(self):
        """Test method is_test_mapping."""
        parser = atest_arg_parser.AtestArgParser()
        parser.add_atest_args()
        tm_args = ['--test-mapping', '--include-subdirs']

        for argument in tm_args:
            args = parser.parse_args([argument])
            self.assertTrue(
                atest_utils.is_test_mapping(args),
                'Option %s indicates a test_mapping!' % argument)

    def test_is_test_mapping_implicit_test_mapping(self):
        """Test method is_test_mapping."""
        parser = atest_arg_parser.AtestArgParser()
        parser.add_atest_args()

        args = parser.parse_args(['--test', '--build', ':postsubmit'])
        self.assertTrue(
            atest_utils.is_test_mapping(args),
            'Option %s indicates a test_mapping!' % args)

    def test_is_test_mapping_with_testname(self):
        """Test method is_test_mapping."""
        parser = atest_arg_parser.AtestArgParser()
        parser.add_atest_args()
        irrelevant_args = ['--test', ':postsubmit', 'testname']

        args = parser.parse_args(irrelevant_args)
        self.assertFalse(
            atest_utils.is_test_mapping(args),
            'Option %s indicates a test_mapping!' % args)

    def test_is_test_mapping_false(self):
        """Test method is_test_mapping."""
        parser = atest_arg_parser.AtestArgParser()
        parser.add_atest_args()
        args = parser.parse_args(['--test', '--build', 'hello_atest'])

        self.assertFalse(
            atest_utils.is_test_mapping(args))

    def test_has_colors(self):
        """Test method _has_colors."""
        # stream is file I/O
        stream = open('/tmp/test_has_colors.txt', 'wb')
        self.assertFalse(atest_utils._has_colors(stream))
        stream.close()

        # stream is not a tty(terminal).
        stream = mock.Mock()
        stream.isatty.return_value = False
        self.assertFalse(atest_utils._has_colors(stream))

        # stream is a tty(terminal).
        stream = mock.Mock()
        stream.isatty.return_value = True
        self.assertTrue(atest_utils._has_colors(stream))


    @mock.patch('atest.atest_utils._has_colors')
    def test_colorize(self, mock_has_colors):
        """Test method colorize."""
        original_str = "test string"
        green_no = 2

        # _has_colors() return False.
        mock_has_colors.return_value = False
        converted_str = atest_utils.colorize(original_str, green_no,
                                             bp_color=constants.RED)
        self.assertEqual(original_str, converted_str)

        # Green text with red background.
        mock_has_colors.return_value = True
        converted_str = atest_utils.colorize(original_str, green_no,
                                             bp_color=constants.RED)
        green_highlight_string = '\x1b[1;32;41m%s\x1b[0m' % original_str
        self.assertEqual(green_highlight_string, converted_str)

        # Green text, no background.
        mock_has_colors.return_value = True
        converted_str = atest_utils.colorize(original_str, green_no)
        green_no_highlight_string = '\x1b[1;32m%s\x1b[0m' % original_str
        self.assertEqual(green_no_highlight_string, converted_str)


    @mock.patch('atest.atest_utils._has_colors')
    def test_colorful_print(self, mock_has_colors):
        """Test method colorful_print."""
        testing_str = "color_print_test"
        green_no = 2

        # _has_colors() return False.
        mock_has_colors.return_value = False
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.colorful_print(testing_str, green_no,
                                   bp_color=constants.RED,
                                   auto_wrap=False)
        sys.stdout = sys.__stdout__
        uncolored_string = testing_str
        self.assertEqual(capture_output.getvalue(), uncolored_string)

        # Green text with red background, but no wrap.
        mock_has_colors.return_value = True
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.colorful_print(testing_str, green_no,
                                   bp_color=constants.RED,
                                   auto_wrap=False)
        sys.stdout = sys.__stdout__
        green_highlight_no_wrap_string = '\x1b[1;32;41m%s\x1b[0m' % testing_str
        self.assertEqual(capture_output.getvalue(),
                         green_highlight_no_wrap_string)

        # Green text, no background, no wrap.
        mock_has_colors.return_value = True
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.colorful_print(testing_str, green_no,
                                   auto_wrap=False)
        sys.stdout = sys.__stdout__
        green_no_high_no_wrap_string = '\x1b[1;32m%s\x1b[0m' % testing_str
        self.assertEqual(capture_output.getvalue(),
                         green_no_high_no_wrap_string)

        # Green text with red background and wrap.
        mock_has_colors.return_value = True
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.colorful_print(testing_str, green_no,
                                   bp_color=constants.RED,
                                   auto_wrap=True)
        sys.stdout = sys.__stdout__
        green_highlight_wrap_string = '\x1b[1;32;41m%s\x1b[0m\n' % testing_str
        self.assertEqual(capture_output.getvalue(), green_highlight_wrap_string)

        # Green text with wrap, but no background.
        mock_has_colors.return_value = True
        capture_output = StringIO()
        sys.stdout = capture_output
        atest_utils.colorful_print(testing_str, green_no,
                                   auto_wrap=True)
        sys.stdout = sys.__stdout__
        green_wrap_no_highlight_string = '\x1b[1;32m%s\x1b[0m\n' % testing_str
        self.assertEqual(capture_output.getvalue(),
                         green_wrap_no_highlight_string)

    @mock.patch('builtins.input')
    @mock.patch('json.load')
    def test_update_test_runner_cmd(self, mock_json_load_data, mock_input):
        """Test method handle_test_runner_cmd without enable do_verification."""
        former_cmd_str = 'Former cmds ='
        write_result_str = 'Save result mapping to test_result'
        tmp_file = tempfile.NamedTemporaryFile()
        input_cmd = 'atest_args'
        runner_cmds = ['cmd1', 'cmd2']
        capture_output = StringIO()
        sys.stdout = capture_output
        # Previous data is empty. Should not enter strtobool.
        # If entered, exception will be raised cause test fail.
        mock_json_load_data.return_value = {}
        atest_utils.handle_test_runner_cmd(input_cmd,
                                           runner_cmds,
                                           do_verification=False,
                                           result_path=tmp_file.name)
        sys.stdout = sys.__stdout__
        self.assertEqual(capture_output.getvalue().find(former_cmd_str), -1)
        # Previous data is the same as the new input. Should not enter strtobool.
        # If entered, exception will be raised cause test fail
        capture_output = StringIO()
        sys.stdout = capture_output
        mock_json_load_data.return_value = {input_cmd:runner_cmds}
        atest_utils.handle_test_runner_cmd(input_cmd,
                                           runner_cmds,
                                           do_verification=False,
                                           result_path=tmp_file.name)
        sys.stdout = sys.__stdout__
        self.assertEqual(capture_output.getvalue().find(former_cmd_str), -1)
        self.assertEqual(capture_output.getvalue().find(write_result_str), -1)
        # Previous data has different cmds. Should enter strtobool not update,
        # should not find write_result_str.
        prev_cmds = ['cmd1']
        mock_input.return_value = 'n'
        capture_output = StringIO()
        sys.stdout = capture_output
        mock_json_load_data.return_value = {input_cmd:prev_cmds}
        atest_utils.handle_test_runner_cmd(input_cmd,
                                           runner_cmds,
                                           do_verification=False,
                                           result_path=tmp_file.name)
        sys.stdout = sys.__stdout__
        self.assertEqual(capture_output.getvalue().find(write_result_str), -1)

    @mock.patch('json.load')
    def test_verify_test_runner_cmd(self, mock_json_load_data):
        """Test method handle_test_runner_cmd without enable update_result."""
        tmp_file = tempfile.NamedTemporaryFile()
        input_cmd = 'atest_args'
        runner_cmds = ['cmd1', 'cmd2']
        # Previous data is the same as the new input. Should not raise exception.
        mock_json_load_data.return_value = {input_cmd:runner_cmds}
        atest_utils.handle_test_runner_cmd(input_cmd,
                                           runner_cmds,
                                           do_verification=True,
                                           result_path=tmp_file.name)
        # Previous data has different cmds. Should enter strtobool and hit
        # exception.
        prev_cmds = ['cmd1']
        mock_json_load_data.return_value = {input_cmd:prev_cmds}
        self.assertRaises(atest_error.DryRunVerificationError,
                          atest_utils.handle_test_runner_cmd,
                          input_cmd,
                          runner_cmds,
                          do_verification=True,
                          result_path=tmp_file.name)

    def test_get_test_info_cache_path(self):
        """Test method get_test_info_cache_path."""
        input_file_name = 'mytest_name'
        cache_root = '/a/b/c'
        expect_hashed_name = ('%s.cache' % hashlib.md5(str(input_file_name).
                                                       encode()).hexdigest())
        self.assertEqual(os.path.join(cache_root, expect_hashed_name),
                         atest_utils.get_test_info_cache_path(input_file_name,
                                                              cache_root))

    def test_get_and_load_cache(self):
        """Test method update_test_info_cache and load_test_info_cache."""
        test_reference = 'myTestRefA'
        test_cache_dir = tempfile.mkdtemp()
        atest_utils.update_test_info_cache(test_reference, [TEST_INFO_A],
                                           test_cache_dir)
        unittest_utils.assert_equal_testinfo_sets(
            self, set([TEST_INFO_A]),
            atest_utils.load_test_info_cache(test_reference, test_cache_dir))

    @mock.patch('os.getcwd')
    def test_get_build_cmd(self, mock_cwd):
        """Test method get_build_cmd."""
        build_top = '/home/a/b/c'
        rel_path = 'd/e'
        mock_cwd.return_value = os.path.join(build_top, rel_path)
        # TODO: (b/264015241) Stop mocking build variables.
        os_environ_mock = {constants.ANDROID_BUILD_TOP: build_top}
        with mock.patch.dict('os.environ', os_environ_mock, clear=True):
            expected_cmd = ['../../build/soong/soong_ui.bash', '--make-mode']
            self.assertEqual(expected_cmd, atest_utils.get_build_cmd())

    @mock.patch('subprocess.check_output')
    def test_get_modified_files(self, mock_co):
        """Test method get_modified_files"""
        mock_co.side_effect = [
            x.encode('utf-8') for x in ['/a/b/',
                                        '\n',
                                        'test_fp1.java\nc/test_fp2.java']]
        self.assertEqual({'/a/b/test_fp1.java', '/a/b/c/test_fp2.java'},
                         atest_utils.get_modified_files(''))
        mock_co.side_effect = [
            x.encode('utf-8') for x in ['/a/b/',
                                        'test_fp4',
                                        '/test_fp3.java']]
        self.assertEqual({'/a/b/test_fp4', '/a/b/test_fp3.java'},
                         atest_utils.get_modified_files(''))

    def test_delimiter(self):
        """Test method delimiter"""
        self.assertEqual('\n===\n\n', atest_utils.delimiter('=', 3, 1, 2))

    def test_has_python_module(self):
        """Test method has_python_module"""
        self.assertFalse(atest_utils.has_python_module('M_M'))
        self.assertTrue(atest_utils.has_python_module('os'))

    @mock.patch.object(atest_utils, 'matched_tf_error_log', return_value=True)
    def test_read_zip_single_text(self, _matched):
        """Test method extract_zip_text include only one text file."""
        zip_path = os.path.join(unittest_constants.TEST_DATA_DIR,
                                TEST_ZIP_DATA_DIR, TEST_SINGLE_ZIP_NAME)
        expect_content = '\nfile1_line1\nfile1_line2\n'
        self.assertEqual(expect_content, atest_utils.extract_zip_text(zip_path))

    @mock.patch.object(atest_utils, 'matched_tf_error_log', return_value=True)
    def test_read_zip_multi_text(self, _matched):
        """Test method extract_zip_text include multiple text files."""
        zip_path = os.path.join(unittest_constants.TEST_DATA_DIR,
                                TEST_ZIP_DATA_DIR, TEST_MULTI_ZIP_NAME)
        expect_content = ('\nfile1_line1\nfile1_line2\n\nfile2_line1\n'
                          'file2_line2\n')
        self.assertEqual(expect_content, atest_utils.extract_zip_text(zip_path))

    def test_matched_tf_error_log(self):
        """Test method extract_zip_text include multiple text files."""
        matched_content = '05-25 17:37:04 E/XXXXX YYYYY'
        not_matched_content = '05-25 17:37:04 I/XXXXX YYYYY'
        # Test matched content
        self.assertEqual(True,
                         atest_utils.matched_tf_error_log(matched_content))
        # Test not matched content
        self.assertEqual(False,
                         atest_utils.matched_tf_error_log(not_matched_content))

    @mock.patch('os.chmod')
    @mock.patch('shutil.copy2')
    @mock.patch('atest.atest_utils.has_valid_cert')
    @mock.patch('subprocess.check_output')
    @mock.patch('os.path.exists')
    def test_get_flakes(self, mock_path_exists, mock_output, mock_valid_cert,
                        _cpc, _cm):
        """Test method get_flakes."""
        # Test par file does not exist.
        mock_path_exists.return_value = False
        self.assertEqual(None, atest_utils.get_flakes())
        # Test par file exists.
        mock_path_exists.return_value = True
        mock_output.return_value = (b'flake_percent:0.10001\n'
                                    b'postsubmit_flakes_per_week:12.0')
        mock_valid_cert.return_value = True
        expected_flake_info = {'flake_percent':'0.10001',
                               'postsubmit_flakes_per_week':'12.0'}
        self.assertEqual(expected_flake_info,
                         atest_utils.get_flakes())
        # Test no valid cert
        mock_valid_cert.return_value = False
        self.assertEqual(None,
                         atest_utils.get_flakes())

    @mock.patch('subprocess.check_call')
    def test_has_valid_cert(self, mock_call):
        """Test method has_valid_cert."""
        # raise subprocess.CalledProcessError
        mock_call.raiseError.side_effect = subprocess.CalledProcessError
        self.assertFalse(atest_utils.has_valid_cert())
        with mock.patch("atest.constants.CERT_STATUS_CMD", ''):
            self.assertFalse(atest_utils.has_valid_cert())
        with mock.patch("atest.constants.CERT_STATUS_CMD", 'CMD'):
            # has valid cert
            mock_call.return_value = 0
            self.assertTrue(atest_utils.has_valid_cert())
            # no valid cert
            mock_call.return_value = 4
            self.assertFalse(atest_utils.has_valid_cert())

    # pylint: disable=no-member
    def test_read_test_record_proto(self):
        """Test method read_test_record."""
        test_record_file_path = os.path.join(
            unittest_constants.TEST_DATA_DIR,
            "test_record.proto.testonly")
        test_record = atest_utils.read_test_record(test_record_file_path)
        self.assertEqual(
            test_record.children[0].inline_test_record.test_record_id,
            'x86 hello_world_test')

    def test_load_json_safely_file_inexistent(self):
        """Test method load_json_safely if file does not exist."""
        json_file_path = Path(
            unittest_constants.TEST_DATA_DIR).joinpath("not_exist.json")
        self.assertEqual({}, atest_utils.load_json_safely(json_file_path))

    def test_load_json_safely_valid_json_format(self):
        """Test method load_json_safely if file exists and format is valid."""
        json_file_path = Path(
            unittest_constants.TEST_DATA_DIR).joinpath("module-info.json")
        content = atest_utils.load_json_safely(json_file_path)
        self.assertEqual('MainModule1', content.get('MainModule1').get('module_name'))
        self.assertEqual([], content.get('MainModule2').get('test_mainline_modules'))

    def test_load_json_safely_invalid_json_format(self):
        """Test method load_json_safely if file exist but content is invalid."""
        json_file_path = Path(
            unittest_constants.TEST_DATA_DIR).joinpath("not-valid-module-info.json")
        self.assertEqual({}, atest_utils.load_json_safely(json_file_path))

    @mock.patch('os.getenv')
    def test_get_manifest_branch(self, mock_env):
        """Test method get_manifest_branch"""
        build_top = tempfile.TemporaryDirectory()
        mock_env.return_value = build_top.name
        repo_dir = Path(build_top.name).joinpath('.repo')
        portal_xml = repo_dir.joinpath('manifest.xml')
        manifest_dir = repo_dir.joinpath('manifests')
        target_xml = manifest_dir.joinpath('Default.xml')
        repo_dir.mkdir()
        manifest_dir.mkdir()
        content_portal = '<manifest><include name="Default.xml" /></manifest>'
        content_manifest = '''<manifest>
            <remote name="aosp" fetch=".." review="https://android-review.googlesource.com/" />
            <default revision="MONSTER-dev" remote="aosp" sync-j="4" />
        </manifest>'''

        # 1. The manifest.xml(portal) contains 'include' directive: 'Default.xml'.
        # Search revision in .repo/manifests/Default.xml.
        with open(portal_xml, 'w') as cache:
            cache.write(content_portal)
        with open(target_xml, 'w') as cache:
            cache.write(content_manifest)
        self.assertEqual("MONSTER-dev", atest_utils.get_manifest_branch())
        self.assertEqual("aosp-MONSTER-dev", atest_utils.get_manifest_branch(True))
        os.remove(target_xml)
        os.remove(portal_xml)

        # 2. The manifest.xml contains neither 'include' nor 'revision' directive,
        # keep searching revision in .repo/manifests/default.xml by default.
        with open(portal_xml, 'w') as cache:
            cache.write('<manifest></manifest>')
        default_xml = manifest_dir.joinpath('default.xml')
        with open(default_xml, 'w') as cache:
            cache.write(content_manifest)
        self.assertEqual("MONSTER-dev", atest_utils.get_manifest_branch())
        os.remove(default_xml)
        os.remove(portal_xml)

        # 3. revision was directly defined in 'manifest.xml'.
        with open(portal_xml, 'w') as cache:
            cache.write(content_manifest)
        self.assertEqual("MONSTER-dev", atest_utils.get_manifest_branch())
        os.remove(portal_xml)

        # 4. Return None if the included xml does not exist.
        with open(portal_xml, 'w') as cache:
            cache.write(content_portal)
        self.assertEqual('', atest_utils.get_manifest_branch())
        os.remove(portal_xml)

    def test_has_wildcard(self):
        """Test method of has_wildcard"""
        self.assertFalse(atest_utils.has_wildcard('test1'))
        self.assertFalse(atest_utils.has_wildcard(['test1']))
        self.assertTrue(atest_utils.has_wildcard('test1?'))
        self.assertTrue(atest_utils.has_wildcard(['test1', 'b*', 'a?b*']))

    # pylint: disable=anomalous-backslash-in-string
    def test_quote(self):
        """Test method of quote()"""
        target_str = r'TEST_(F|P)[0-9].*\w$'
        expected_str = '\'TEST_(F|P)[0-9].*\w$\''
        self.assertEqual(atest_utils.quote(target_str), expected_str)
        self.assertEqual(atest_utils.quote('TEST_P224'), 'TEST_P224')

    @mock.patch('builtins.input', return_value='')
    def test_prompt_with_yn_result(self, mock_input):
        """Test method of prompt_with_yn_result"""
        msg = 'Do you want to continue?'
        mock_input.return_value = ''
        self.assertTrue(atest_utils.prompt_with_yn_result(msg, True))
        self.assertFalse(atest_utils.prompt_with_yn_result(msg, False))
        mock_input.return_value = 'y'
        self.assertTrue(atest_utils.prompt_with_yn_result(msg, True))
        mock_input.return_value = 'nO'
        self.assertFalse(atest_utils.prompt_with_yn_result(msg, True))

    def test_get_android_junit_config_filters(self):
        """Test method of get_android_junit_config_filters"""
        no_filter_test_config = os.path.join(
            unittest_constants.TEST_DATA_DIR,
            "filter_configs", "no_filter.cfg")
        self.assertEqual({},
                         atest_utils.get_android_junit_config_filters(
                             no_filter_test_config))

        filtered_test_config = os.path.join(
            unittest_constants.TEST_DATA_DIR,
            'filter_configs', 'filter.cfg')
        filter_dict = atest_utils.get_android_junit_config_filters(
            filtered_test_config)
        include_annotations = filter_dict.get(constants.INCLUDE_ANNOTATION)
        include_annotations.sort()
        self.assertEqual(
            ['include1', 'include2'],
            include_annotations)
        exclude_annotation = filter_dict.get(constants.EXCLUDE_ANNOTATION)
        exclude_annotation.sort()
        self.assertEqual(
            ['exclude1', 'exclude2'],
            exclude_annotation)

    def test_md5sum(self):
        """Test method of md5sum"""
        exist_string = os.path.join(unittest_constants.TEST_DATA_DIR,
                                    unittest_constants.JSON_FILE)
        inexist_string = os.path.join(unittest_constants.TEST_DATA_DIR,
                                      unittest_constants.CLASS_NAME)
        self.assertEqual(
            atest_utils.md5sum(exist_string), '062160df00c20b1ee4d916b7baf71346')
        self.assertEqual(
            atest_utils.md5sum(inexist_string), '')

    def test_check_md5(self):
        """Test method of check_md5"""
        file1 = os.path.join(unittest_constants.TEST_DATA_DIR,
                            unittest_constants.JSON_FILE)
        checksum_file = '/tmp/_tmp_module-info.json'
        atest_utils.save_md5([file1], '/tmp/_tmp_module-info.json')
        self.assertTrue(atest_utils.check_md5(checksum_file))
        os.remove(checksum_file)
        self.assertFalse(atest_utils.check_md5(checksum_file))
        self.assertTrue(atest_utils.check_md5(checksum_file, missing_ok=True))

    def test_get_config_parameter(self):
        """Test method of get_config_parameter"""
        parameter_config = os.path.join(
            unittest_constants.TEST_DATA_DIR,
            "parameter_config", "parameter.cfg")
        no_parameter_config = os.path.join(
            unittest_constants.TEST_DATA_DIR,
            "parameter_config", "no_parameter.cfg")

        # Test parameter empty value
        self.assertEqual(set(),
                         atest_utils.get_config_parameter(
                             no_parameter_config))

        # Test parameter empty value
        self.assertEqual({'value_1', 'value_2', 'value_3', 'value_4'},
                         atest_utils.get_config_parameter(
                             parameter_config))

    def test_get_config_device(self):
        """Test method of get_config_device"""
        device_config = os.path.join(
            unittest_constants.TEST_DATA_DIR,
            "parameter_config", "multiple_device.cfg")
        self.assertEqual({'device_1', 'device_2'},
                         atest_utils.get_config_device(device_config))

    def test_get_mainline_param(self):
        """Test method of get_mainline_param"""
        mainline_param_config = os.path.join(
            unittest_constants.TEST_DATA_DIR,
            "parameter_config", "mainline_param.cfg")
        self.assertEqual({'foo1.apex', 'foo2.apk+foo3.apk'},
                         atest_utils.get_mainline_param(
                             mainline_param_config))
        no_mainline_param_config = os.path.join(
            unittest_constants.TEST_DATA_DIR,
            "parameter_config", "parameter.cfg")
        self.assertEqual(set(),
                         atest_utils.get_mainline_param(
                             no_mainline_param_config))

    def test_get_full_annotation_class_name(self):
        """Test method of get_full_annotation_class_name."""
        app_mode_full = 'android.platform.test.annotations.AppModeFull'
        presubmit = 'android.platform.test.annotations.Presubmit'
        module_info = {'srcs': [os.path.join(unittest_constants.TEST_DATA_DIR,
                                'annotation_testing',
                                'Annotation.src')]}
        # get annotation class from keyword
        self.assertEqual(
            atest_utils.get_full_annotation_class_name(module_info, 'presubmit'),
            presubmit)
        # get annotation class from an accurate fqcn keyword.
        self.assertEqual(
            atest_utils.get_full_annotation_class_name(module_info, presubmit),
            presubmit)
        # accept fqcn keyword in lowercase.
        self.assertEqual(
            atest_utils.get_full_annotation_class_name(module_info, 'android.platform.test.annotations.presubmit'),
            presubmit)
        # unable to get annotation class from keyword.
        self.assertNotEqual(
            atest_utils.get_full_annotation_class_name(module_info, 'appleModefull'), app_mode_full)
        # do not support partial-correct keyword.
        self.assertNotEqual(
            atest_utils.get_full_annotation_class_name(module_info, 'android.platform.test.annotations.pres'), presubmit)

    def test_has_mixed_type_filters_one_module_with_one_type_return_false(self):
        """Test method of has_mixed_type_filters"""
        filter_1 = test_info.TestFilter('CLASS', frozenset(['METHOD']))
        test_data_1 = {constants.TI_FILTER: [filter_1]}
        test_info_1 = test_info.TestInfo('MODULE', 'RUNNER',
                                set(), test_data_1,
                                'SUITE', '',
                                set())
        self.assertFalse(atest_utils.has_mixed_type_filters([test_info_1]))

    def test_has_mixed_type_filters_one_module_with_mixed_types_return_true(self):
        """Test method of has_mixed_type_filters"""
        filter_1 = test_info.TestFilter('CLASS', frozenset(['METHOD']))
        filter_2 = test_info.TestFilter('CLASS', frozenset(['METHOD*']))
        test_data_2 = {constants.TI_FILTER: [filter_1, filter_2]}
        test_info_2 = test_info.TestInfo('MODULE', 'RUNNER',
                                set(), test_data_2,
                                'SUITE', '',
                                set())
        self.assertTrue(atest_utils.has_mixed_type_filters([test_info_2]))

    def test_has_mixed_type_filters_two_module_with_mixed_types_return_false(self):
        """Test method of has_mixed_type_filters"""
        filter_1 = test_info.TestFilter('CLASS', frozenset(['METHOD']))
        test_data_1 = {constants.TI_FILTER: [filter_1]}
        test_info_1 = test_info.TestInfo('MODULE', 'RUNNER',
                                set(), test_data_1,
                                'SUITE', '',
                                set())
        filter_3 = test_info.TestFilter('CLASS', frozenset(['METHOD*']))
        test_data_3 = {constants.TI_FILTER: [filter_3]}
        test_info_3 = test_info.TestInfo('MODULE3', 'RUNNER',
                                set(), test_data_3,
                                'SUITE', '',
                                set())
        self.assertFalse(atest_utils.has_mixed_type_filters(
            [test_info_1, test_info_3]))

    def test_get_filter_types(self):
        """Test method of get_filter_types."""
        filters = set(['CLASS#METHOD'])
        expect_types = set([FilterType.REGULAR_FILTER.value])
        self.assertEqual(atest_utils.get_filter_types(filters), expect_types)

        filters = set(['CLASS#METHOD*'])
        expect_types = set([FilterType.WILDCARD_FILTER.value])
        self.assertEqual(atest_utils.get_filter_types(filters), expect_types)

        filters = set(['CLASS#METHOD', 'CLASS#METHOD*'])
        expect_types = set([FilterType.WILDCARD_FILTER.value,
                          FilterType.REGULAR_FILTER.value])
        self.assertEqual(atest_utils.get_filter_types(filters), expect_types)

        filters = set(['CLASS#METHOD?', 'CLASS#METHOD*'])
        expect_types = set([FilterType.WILDCARD_FILTER.value])
        self.assertEqual(atest_utils.get_filter_types(filters), expect_types)

    def test_get_bp_content(self):
        """Method get_bp_content."""
        # 1. "manifest" and "instrumentation_for" are defined.
        content = '''android_test    {
                // comment
                instrumentation_for: "AmSlam", // comment
                manifest: "AndroidManifest-test.xml",
                name: "AmSlamTests",
        }'''
        expected_result = {"AmSlamTests":
                           {"target_module": "AmSlam", "manifest": "AndroidManifest-test.xml"}}
        temp_dir = tempfile.TemporaryDirectory()
        tmpbp = Path(temp_dir.name).joinpath('Android.bp')
        with open(tmpbp, 'w') as cache:
            cache.write(content)
        self.assertEqual(atest_utils.get_bp_content(tmpbp, 'android_test'),
                         expected_result)
        temp_dir.cleanup()

        # 2. Only name is defined, will give default manifest and null target_module.
        content = '''android_app    {
                // comment
                name: "AmSlam",
                srcs: ["src1.java", "src2.java"]
        }'''
        expected_result = {"AmSlam":
                           {"target_module": "", "manifest": "AndroidManifest.xml"}}
        temp_dir = tempfile.TemporaryDirectory()
        tmpbp = Path(temp_dir.name).joinpath('Android.bp')
        with open(tmpbp, 'w') as cache:
            cache.write(content)
        self.assertEqual(atest_utils.get_bp_content(tmpbp, 'android_app'),
                         expected_result)
        temp_dir.cleanup()

        # 3. Not even an Android.bp.
        content = '''LOCAL_PATH := $(call my-dir)
                # comment
                include $(call all-subdir-makefiles)
                LOCAL_MODULE := atest_foo_test
        }'''
        temp_dir = tempfile.TemporaryDirectory()
        tmpbp = Path(temp_dir.name).joinpath('Android.mk')
        with open(tmpbp, 'w') as cache:
            cache.write(content)
        self.assertEqual(atest_utils.get_bp_content(tmpbp, 'android_app'), {})
        temp_dir.cleanup()

    def test_get_manifest_info(self):
        """test get_manifest_info method."""
        # An instrumentation test:
        test_xml = os.path.join(unittest_constants.TEST_DATA_DIR,
                                'foo/bar/AmSlam/test/AndroidManifest.xml')
        expected = {
            'package': 'com.android.settings.tests.unit',
            'target_package': 'c0m.andr0id.settingS',
            'persistent': False
        }
        self.assertEqual(expected, atest_utils.get_manifest_info(test_xml))

        # A target module:
        target_xml = os.path.join(unittest_constants.TEST_DATA_DIR,
                                  'foo/bar/AmSlam/AndroidManifest.xml')
        expected = {
            'package': 'c0m.andr0id.settingS',
            'target_package': '',
            'persistent': False
        }
        self.assertEqual(expected, atest_utils.get_manifest_info(target_xml))

# pylint: disable=missing-function-docstring
class AutoShardUnittests(fake_filesystem_unittest.TestCase):
    """Tests for auto shard functions"""
    def setUp(self):
        self.setUpPyfakefs()

    def test_get_local_auto_shardable_tests(self):
        """test get local auto shardable list"""
        shardable_tests_file = Path(atest_utils.get_misc_dir()).joinpath(
        '.atest/auto_shard/local_auto_shardable_tests')

        self.fs.create_file(shardable_tests_file, contents='abc\ndef')

        long_duration_tests = atest_utils.get_local_auto_shardable_tests()

        expected_list = ['abc', 'def']
        self.assertEqual(long_duration_tests , expected_list)

    def test_update_shardable_tests_with_time_less_than_600(self):
        """test update local auto shardable list"""
        shardable_tests_file = Path(atest_utils.get_misc_dir()).joinpath(
        '.atest/auto_shard/local_auto_shardable_tests')

        self.fs.create_file(shardable_tests_file, contents='')

        atest_utils.update_shardable_tests('test1', 10)

        with open(shardable_tests_file) as f:
            self.assertEqual('', f.read())

    def test_update_shardable_tests_with_time_larger_than_600(self):
        """test update local auto shardable list"""
        shardable_tests_file = Path(atest_utils.get_misc_dir()).joinpath(
        '.atest/auto_shard/local_auto_shardable_tests')

        self.fs.create_file(shardable_tests_file, contents='')

        atest_utils.update_shardable_tests('test2', 1000)

        with open(shardable_tests_file) as f:
            self.assertEqual('test2', f.read())

    def test_update_shardable_tests_with_time_larger_than_600_twice(self):
        """test update local auto shardable list"""
        shardable_tests_file = Path(atest_utils.get_misc_dir()).joinpath(
        '.atest/auto_shard/local_auto_shardable_tests')
        # access the fake_filesystem object via fake_fs
        self.fs.create_file(shardable_tests_file, contents='')

        atest_utils.update_shardable_tests('test3', 1000)
        atest_utils.update_shardable_tests('test3', 601)

        with open(shardable_tests_file) as f:
            self.assertEqual('test3', f.read())


if __name__ == "__main__":
    unittest.main()
