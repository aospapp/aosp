#!/usr/bin/python3
# pylint: disable-msg=C0111

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import json
import os
import six
from six.moves import range
import unittest

import common

from autotest_lib.client.common_lib import control_data, autotemp


ControlData = control_data.ControlData

CONTROL = """
AUTHOR = 'Author'
DEPENDENCIES = "console, power"
DOC = \"\"\"\
doc stuff\"\"\"
# EXPERIMENTAL should implicitly be False
NAME = 'nA' "mE"
RUN_VERIFY = False
SYNC_COUNT = 2
TIME='short'
TEST_CLASS=u'Kernel'
TEST_CATEGORY='Stress'
TEST_TYPE='client'
REQUIRE_SSP = False
ATTRIBUTES = "suite:smoke, suite:bvt"
SUITE = "suite-listed-only-in-suite-line"
"""

# Control data being wrapped into step* functions.
WRAPPED_CONTROL = """
def step_init():
    step0()

def step0():
    AUTHOR = 'Author'
    DEPENDENCIES = "console, power"
    DOC = \"\"\"\
    doc stuff\"\"\"
    # EXPERIMENTAL should implicitly be False
    NAME = 'nA' "mE"
    RUN_VERIFY = False
    SYNC_COUNT = 2
    TIME='short'
    TEST_CLASS=u'Kernel'
    TEST_CATEGORY='Stress'
    TEST_TYPE='client'
    REQUIRE_SSP = False
    ATTRIBUTES = "suite:smoke, suite:bvt"
    SUITE = "suite-listed-only-in-suite-line"
    MAX_RESULT_SIZE_KB = 20000

step_init()
"""


class ControlDataTestCase(unittest.TestCase):
    def setUp(self):
        self._required_vars = control_data.REQUIRED_VARS
        control_data.REQUIRED_VARS = set()


    def tearDown(self):
        control_data.REQUIRED_VARS = self._required_vars


    def test_suite_tag_parts(self):
        cd = ControlData({'suite': 'foo,bar'}, 'filename')
        self.assertEqual(set(cd.suite_tag_parts), {'foo', 'bar'})


    def test_suite_tag_parts_empty_for_non_suite(self):
        cd = ControlData({}, 'filename')
        self.assertEqual(cd.suite_tag_parts, [])



class ParseControlTest(unittest.TestCase):
    def setUp(self):
        self.control_tmp = autotemp.tempfile(unique_id='control_unit',
                                             text=True)
        os.write(self.control_tmp.fd, str.encode(CONTROL))


    def tearDown(self):
        self.control_tmp.clean()


    def test_parse_control(self):
        cd = control_data.parse_control(self.control_tmp.name, True)
        self.assertEqual(cd.author, "Author")
        self.assertEqual(cd.dependencies, set(['console', 'power']))
        self.assertEqual(cd.doc, "doc stuff")
        self.assertEqual(cd.experimental, False)
        self.assertEqual(cd.name, "nAmE")
        self.assertEqual(cd.run_verify, False)
        self.assertEqual(cd.sync_count, 2)
        self.assertEqual(cd.time, "short")
        self.assertEqual(cd.test_class, "kernel")
        self.assertEqual(cd.test_category, "stress")
        self.assertEqual(cd.test_type, "client")
        self.assertEqual(cd.require_ssp, False)
        self.assertEqual(cd.attributes, set(["suite:smoke", "suite:bvt"]))
        self.assertEqual(cd.suite, "bvt,smoke,suite-listed-only-in-suite-line")


class ParseWrappedControlTest(unittest.TestCase):
    """Test control data can be retrieved from wrapped step functions."""
    def setUp(self):
        self.control_tmp = autotemp.tempfile(unique_id='wrapped_control_unit',
                                             text=True)
        os.write(self.control_tmp.fd, str.encode(WRAPPED_CONTROL))


    def tearDown(self):
        self.control_tmp.clean()


    def test_parse_control(self):
        cd = control_data.parse_control(self.control_tmp.name, True)
        self.assertEqual(cd.author, "Author")
        self.assertEqual(cd.dependencies, set(['console', 'power']))
        self.assertEqual(cd.doc, "doc stuff")
        self.assertEqual(cd.experimental, False)
        self.assertEqual(cd.name, "nAmE")
        self.assertEqual(cd.run_verify, False)
        self.assertEqual(cd.sync_count, 2)
        self.assertEqual(cd.time, "short")
        self.assertEqual(cd.test_class, "kernel")
        self.assertEqual(cd.test_category, "stress")
        self.assertEqual(cd.test_type, "client")
        self.assertEqual(cd.require_ssp, False)
        self.assertEqual(cd.attributes, set(["suite:smoke", "suite:bvt"]))
        self.assertEqual(cd.suite, "bvt,smoke,suite-listed-only-in-suite-line")
        self.assertEqual(cd.max_result_size_KB, 20000)


class ParseControlFileBugTemplate(unittest.TestCase):
    def setUp(self):
        self.control_tmp = autotemp.tempfile(unique_id='control_unit',
                                             text=True)
        self.bug_template = {
            'owner': 'someone@something.org',
            'labels': ['a', 'b'],
            'status': None,
            'summary': None,
            'title': None,
            'cc': ['a@something, b@something'],
        }


    def tearDown(self):
        self.control_tmp.clean()


    def insert_bug_template(self, control_file_string):
        """Insert a bug template into the control file string.

        @param control_file_string: A string of the control file contents
            this test will run on.

        @return: The control file string with the BUG_TEMPLATE line.
        """
        bug_template_line = 'BUG_TEMPLATE = %s' % json.dumps(self.bug_template)
        return control_file_string + bug_template_line


    def verify_bug_template(self, new_bug_template):
        """Verify that the bug template given matches the original.

        @param new_bug_template: A bug template pulled off parsing the
            control file.

        @raises AssetionError: If a value under a give key in the bug template
            doesn't match the value in self.bug_template.
        @raises KeyError: If a key in either bug template is missing.
        """
        for key, value in six.iteritems(new_bug_template):
            self.assertEqual(value, self.bug_template[key])


    def test_bug_template_parsing(self):
        """Basic parsing test for a bug templates in a test control file."""
        os.write(self.control_tmp.fd,
                 str.encode(self.insert_bug_template(CONTROL)))
        cd = control_data.parse_control(self.control_tmp.name, True)
        self.verify_bug_template(cd.bug_template)


    def test_bug_template_list(self):
        """Test that lists in the bug template can handle other datatypes."""
        self.bug_template['labels'].append({'foo': 'bar'})
        os.write(self.control_tmp.fd,
                 str.encode(self.insert_bug_template(CONTROL)))
        cd = control_data.parse_control(self.control_tmp.name, True)
        self.verify_bug_template(cd.bug_template)


    def test_bad_template(self):
        """Test that a bad bug template doesn't result in a bad control data."""
        self.bug_template = 'foobarbug_template'
        os.write(self.control_tmp.fd,
                 str.encode(self.insert_bug_template(CONTROL)))
        cd = control_data.parse_control(self.control_tmp.name, True)
        self.assertFalse(hasattr(cd, 'bug_template'))


class SetMethodTests(unittest.TestCase):
    def setUp(self):
        self.required_vars = control_data.REQUIRED_VARS
        control_data.REQUIRED_VARS = set()


    def tearDown(self):
        control_data.REQUIRED_VARS = self.required_vars


    def test_bool(self):
        cd = ControlData({}, 'filename')
        cd._set_bool('foo', 'False')
        self.assertEqual(cd.foo, False)
        cd._set_bool('foo', True)
        self.assertEqual(cd.foo, True)
        cd._set_bool('foo', 'FALSE')
        self.assertEqual(cd.foo, False)
        cd._set_bool('foo', 'true')
        self.assertEqual(cd.foo, True)
        self.assertRaises(ValueError, cd._set_bool, 'foo', '')
        self.assertRaises(ValueError, cd._set_bool, 'foo', 1)
        self.assertRaises(ValueError, cd._set_bool, 'foo', [])
        self.assertRaises(ValueError, cd._set_bool, 'foo', None)


    def test_int(self):
        cd = ControlData({}, 'filename')
        cd._set_int('foo', 0)
        self.assertEqual(cd.foo, 0)
        cd._set_int('foo', '0')
        self.assertEqual(cd.foo, 0)
        cd._set_int('foo', '-1', min=-2, max=10)
        self.assertEqual(cd.foo, -1)
        self.assertRaises(ValueError, cd._set_int, 'foo', 0, min=1)
        self.assertRaises(ValueError, cd._set_int, 'foo', 1, max=0)
        self.assertRaises(ValueError, cd._set_int, 'foo', 'x')
        self.assertRaises(ValueError, cd._set_int, 'foo', '')
        self.assertRaises(TypeError, cd._set_int, 'foo', None)


    def test_set(self):
        cd = ControlData({}, 'filename')
        cd._set_set('foo', 'a')
        self.assertEqual(cd.foo, set(['a']))
        cd._set_set('foo', 'a,b,c')
        self.assertEqual(cd.foo, set(['a', 'b', 'c']))
        cd._set_set('foo', ' a , b , c     ')
        self.assertEqual(cd.foo, set(['a', 'b', 'c']))
        cd._set_set('foo', None)
        self.assertEqual(cd.foo, set(['None']))


    def test_string(self):
        cd = ControlData({}, 'filename')
        cd._set_string('foo', 'a')
        self.assertEqual(cd.foo, 'a')
        cd._set_string('foo', 'b')
        self.assertEqual(cd.foo, 'b')
        cd._set_string('foo', 'B')
        self.assertEqual(cd.foo, 'B')
        cd._set_string('foo', 1)
        self.assertEqual(cd.foo, '1')
        cd._set_string('foo', None)
        self.assertEqual(cd.foo, 'None')
        cd._set_string('foo', [])
        self.assertEqual(cd.foo, '[]')


    def test_option(self):
        options = ['a', 'b']
        cd = ControlData({}, 'filename')
        cd._set_option('foo', 'a', options)
        self.assertEqual(cd.foo, 'a')
        cd._set_option('foo', 'b', options)
        self.assertEqual(cd.foo, 'b')
        cd._set_option('foo', 'B', options)
        self.assertEqual(cd.foo, 'B')
        self.assertRaises(ValueError, cd._set_option,
                          'foo', 'x', options)
        self.assertRaises(ValueError, cd._set_option,
                          'foo', 1, options)
        self.assertRaises(ValueError, cd._set_option,
                          'foo', [], options)
        self.assertRaises(ValueError, cd._set_option,
                          'foo', None, options)


    def test_set_attributes(self):
        cd = ControlData({}, 'filename')
        cd.set_attributes('suite:bvt')
        self.assertEqual(cd.attributes, set(['suite:bvt']))


    def test_get_test_time_index(self):
        inputs = [time.upper() for time in
                  ControlData.TEST_TIME_LIST]
        time_min_index = [ControlData.get_test_time_index(time)
                          for time in inputs]
        expected_time_index = list(range(len(ControlData.TEST_TIME_LIST)))
        self.assertEqual(time_min_index, expected_time_index)


    def test_get_test_time_index_failure(self):
        def fail():
            """Test function to raise ControlVariableException exception
            for invalid TIME setting."""
            index = ControlData.get_test_time_index('some invalid TIME')

        self.assertRaises(control_data.ControlVariableException, fail)


# this is so the test can be run in standalone mode
if __name__ == '__main__':
    unittest.main()
