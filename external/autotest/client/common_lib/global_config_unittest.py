#!/usr/bin/python3

import collections
import six
import unittest
from unittest.mock import patch

import common

from autotest_lib.client.common_lib import autotemp
from autotest_lib.client.common_lib import global_config
from autotest_lib.client.common_lib import lsbrelease_utils


global_config_ini_contents = """
[SECTION_A]
value_1: 6.0
value_2: hello
value_3: true
value_4: FALSE
value_5: tRuE
value_6: falsE

[SECTION_B]
value_1: -5
value_2: 2.3
value_3: 0
value_4: 7

[SECTION_C]
value_1: nobody@localhost

[SECTION_D]
value_1: 1

[SECTION_E]
value_1: 1
value_2: 2
value_a: A
random: 1
wireless_ssid_1.2.3.4/24: ssid_1
wireless_ssid_4.3.2.1/16: ssid_2

[SECTION_F]
value_7: %sexample
value_8: %%sexample
value_9: %%\(example\)
value_10: %\(branch\)s
value_11: %%\(branch\)s

"""


moblab_config_ini_contents = """
[SECTION_C]
value_1: moblab@remotehost

[SECTION_D]
value_1: 2
"""

shadow_config_ini_contents = """
[SECTION_C]
value_1: somebody@remotehost
"""


def create_config_files():
    """Create config files to be used for test."""
    global_temp = autotemp.tempfile("global", ".ini", text=True)
    with open(global_temp.name, 'w') as gt:
        gt.write(global_config_ini_contents)

    moblab_temp = autotemp.tempfile("moblab", ".ini", text=True)
    with open(moblab_temp.name, 'w') as mt:
        mt.write(moblab_config_ini_contents)

    shadow_temp = autotemp.tempfile("shadow", ".ini", text=True)
    with open(shadow_temp.name, 'w') as st:
        st.write(shadow_config_ini_contents)

    return (global_temp, shadow_temp, moblab_temp)


class global_config_test(unittest.TestCase):
    """Test class"""
    # grab the singelton
    conf = global_config.global_config

    def setUp(self):
        """Setup config files for test."""
        super(global_config_test, self).setUp()
        # set the config files to our test files
        (self.global_temp, self.shadow_temp,
                self.moblab_temp) = create_config_files()

        self.conf.set_config_files(self.global_temp.name, self.shadow_temp.name,
                                   self.moblab_temp.name)


    def tearDown(self):
        """Cleanup and reset config settings."""
        self.shadow_temp.clean()
        self.moblab_temp.clean()
        self.global_temp.clean()
        self.conf.set_config_files(global_config.DEFAULT_CONFIG_FILE,
                                   global_config.DEFAULT_SHADOW_FILE,
                                   global_config.DEFAULT_MOBLAB_FILE)


    def test_float(self):
        """Test converting float value."""
        val = self.conf.get_config_value("SECTION_A", "value_1", float)
        self.assertEquals(type(val), float)
        self.assertEquals(val, 6.0)


    def test_int(self):
        """Test converting int value."""
        val = self.conf.get_config_value("SECTION_B", "value_1", int)
        self.assertEquals(type(val), int)
        self.assertTrue(val < 0)
        val = self.conf.get_config_value("SECTION_B", "value_3", int)
        self.assertEquals(val, 0)
        val = self.conf.get_config_value("SECTION_B", "value_4", int)
        self.assertTrue(val > 0)


    def test_string(self):
        """Test converting string value."""
        val = self.conf.get_config_value("SECTION_A", "value_2")
        self.assertTrue(isinstance(val, six.string_types))
        self.assertEquals(val, "hello")


    def setIsMoblab(self, value):
        """Set lsbrelease_utils.is_moblab result.

        @param value: Value to have lsbrelease_utils.is_moblab to return.
        """
        patcher = patch.object(lsbrelease_utils, 'is_moblab')
        is_moblab = patcher.start()
        self.addCleanup(patcher.stop)
        is_moblab.return_value = value

    def test_override_non_moblab(self):
        """Test value overriding works in non-moblab setup."""
        self.setIsMoblab(False)

        self.conf.reset_config_values()

        # Confirm shadow config overrides global config.
        val = self.conf.get_config_value("SECTION_C", "value_1")
        self.assertEquals(val, "somebody@remotehost")

        # Confirm moblab config should be ignored in non-moblab environment..
        val = self.conf.get_config_value("SECTION_D", "value_1")
        self.assertEquals(val, "1")


    def test_override_moblab(self):
        """Test value overriding works in moblab setup."""
        self.setIsMoblab(True)

        self.conf.reset_config_values()

        # Confirm shadow config overrides both moblab and global config.
        val = self.conf.get_config_value("SECTION_C", "value_1")
        self.assertEquals(val, "somebody@remotehost")

        # Confirm moblab config should override global config in moblab.
        val = self.conf.get_config_value("SECTION_D", "value_1")
        self.assertEquals(val, "2")


    def test_exception(self):
        """Test exception to be raised on invalid config value."""
        error = 0
        try:
            val = self.conf.get_config_value("SECTION_B", "value_2", int)
        except:
            error = 1
        self.assertEquals(error, 1)


    def test_boolean(self):
        """Test converting boolean value."""
        val = self.conf.get_config_value("SECTION_A", "value_3", bool)
        self.assertEquals(val, True)
        val = self.conf.get_config_value("SECTION_A", "value_4", bool)
        self.assertEquals(val, False)
        val = self.conf.get_config_value("SECTION_A", "value_5", bool)
        self.assertEquals(val, True)
        val = self.conf.get_config_value("SECTION_A", "value_6", bool)
        self.assertEquals(val, False)


    def test_special(self):
        """Test converting special instances of '%, %%, %s, %%(), %()'."""
        val7 = self.conf.get_config_value("SECTION_F", "value_7")
        val8 = self.conf.get_config_value("SECTION_F", "value_8")
        val9 = self.conf.get_config_value("SECTION_F", "value_9")
        val10 = self.conf.get_config_value("SECTION_F", "value_10")
        val11 = self.conf.get_config_value("SECTION_F", "value_11")

        # This is the same parsing done within dev_server and other libs...
        val10 = (val10.replace('\\', '') % {'branch': 'test_str'})
        val11 = (val11.replace('\\', '') % {'branch': 'test_str'})

        self.assertEquals(val7, '%sexample')
        self.assertEquals(val8, '%sexample')
        self.assertEquals(val9, '%\(example\)')
        self.assertEquals(val10, 'test_str')
        self.assertEquals(val11, 'test_str')

    def test_defaults(self):
        """Test default value works."""
        val = self.conf.get_config_value("MISSING", "foo", float, 3.6)
        self.assertEquals(val, 3.6)
        val = self.conf.get_config_value("SECTION_A", "novalue", str, "default")
        self.assertEquals(val, "default")


    def test_fallback_key(self):
        """Test fallback value works."""
        val = self.conf.get_config_value_with_fallback(
                "SECTION_A", "value_3", "value_4", bool)
        self.assertEquals(val, True)

        val = self.conf.get_config_value_with_fallback(
                "SECTION_A", "not_existing", "value_4", bool)
        self.assertEquals(val, False)

        val = self.conf.get_config_value_with_fallback(
                "SECTION_A", "not_existing", "value_4",
                fallback_section='SECTION_B')
        self.assertEquals(val, '7')

        self.assertRaises(
                Exception, self.conf.get_config_value_with_fallback,
                "SECTION_A", "not_existing", "also_not_existing", bool)


    def test_get_config_value_regex(self):
        """Test get_config_value_regex works."""
        configs = self.conf.get_config_value_regex('SECTION_E', 'value_\d+',
                                                   int)
        self.assertEquals(configs, {'value_1': 1, 'value_2': 2})
        configs = self.conf.get_config_value_regex('SECTION_E', 'value_.*')
        self.assertEquals(configs, {'value_1': '1', 'value_2': '2',
                                    'value_a': 'A'})
        configs = self.conf.get_config_value_regex('SECTION_E',
                                                   'wireless_ssid_.*')
        self.assertEquals(configs, {'wireless_ssid_1.2.3.4/24': 'ssid_1',
                                    'wireless_ssid_4.3.2.1/16': 'ssid_2'})


    def test_get_section_as_dict(self):
        """Test get_section_as_dict."""
        got = self.conf.get_section_as_dict('SECTION_D')
        self.assertEqual(got, collections.OrderedDict([('value_1', '1')]))


    def test_get_section_as_dict_missing(self):
        """Test get_section_as_dict with missing section."""
        got = self.conf.get_section_as_dict('FOOBAR')
        self.assertEqual(got, collections.OrderedDict())


# this is so the test can be run in standalone mode
if __name__ == '__main__':
    """Main"""
    unittest.main()
