# Copyright 2018 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import logging
import os
import time

from autotest_lib.client.common_lib import error
from autotest_lib.client.common_lib.cros import chromedriver
from autotest_lib.client.cros.graphics import graphics_utils
from autotest_lib.client.cros.bluetooth import bluetooth_device_xmlrpc_server


class bluetooth_TurnOnOffUI(graphics_utils.GraphicsTest):

    """Go to settings and turn on BT On/Off"""
    version = 1
    SETTINGS_UI_TAG = "settings-ui"
    SHADOW_ROOT_JS = 'return arguments[0].shadowRoot'
    SETTINGS_MAIN_CSS = 'settings-main#main'
    SETTINGS_BASE_PAGE_CSS = 'settings-basic-page'
    SETTINGS_BT_PAGE = "settings-bluetooth-page"
    SETTINGS_URL = "chrome://settings"
    ENABLE_BT_CSS = 'cr-toggle#enableBluetooth'
    DELAY_BW_TOGGLE_ON_OFF = 5

    def initialize(self):
        """Autotest initialize function"""
        self.xmlrpc_delegate = bluetooth_device_xmlrpc_server.BluetoothDeviceXmlRpcDelegate()
        super(bluetooth_TurnOnOffUI, self).initialize(raise_error_on_hang=True)

    def cleanup(self):
        """Autotest cleanup function"""
        if self._GSC:
            keyvals = self._GSC.get_memory_difference_keyvals()
            for key, val in keyvals.iteritems():
                self.output_perf_value(
                    description=key,
                    value=val,
                    units='bytes',
                    higher_is_better=False)
            self.write_perf_keyval(keyvals)
        super(bluetooth_TurnOnOffUI, self).cleanup()
        # If test fails then script will collect the screen shot to know at
        # which instance failure occurred.
        if not self.success:
            graphics_utils.take_screenshot(os.path.join(self.debugdir),
                                           "chrome")

    def open_settings(self, driver):
        """Open Settings

        @param driver: chrome driver object
        @return: Returns settings page web element
        """
        logging.info("Opening settings.")
        driver.get(self.SETTINGS_URL)
        logging.debug("Current tab is %s", driver.title)
        return self.settings_frame(driver)

    def settings_frame(self, driver):
        """Finds basic settings page web element

        @param driver: chrome driver object
        @return: Returns settings page web element
        """
        settings_ui = driver.find_element_by_tag_name(self.SETTINGS_UI_TAG)
        settings_ui_shroot = driver.execute_script(self.SHADOW_ROOT_JS,
                                                   settings_ui)
        settings_main = settings_ui_shroot.find_element_by_css_selector(
            self.SETTINGS_MAIN_CSS)
        settings_basic_page = driver.execute_script(self.SHADOW_ROOT_JS,
                                                    settings_main). \
            find_element_by_css_selector(self.SETTINGS_BASE_PAGE_CSS)
        return driver.execute_script(self.SHADOW_ROOT_JS, settings_basic_page)

    def bluetooth_page(self, driver):
        """BT settings page

        @param driver: chrome driver object
        @return: Bluetooth page web element
        """
        settings = self.open_settings(driver)
        basic_page = settings.find_element_by_id('basicPage')
        bt_device_page = basic_page.find_element_by_tag_name(
            self.SETTINGS_BT_PAGE)
        driver.execute_script('return arguments[0].scrollIntoView()',
                              bt_device_page)
        return driver.execute_script(self.SHADOW_ROOT_JS, bt_device_page)

    def is_bluetooth_enabled(self):
        """Returns True if bluetoothd is powered on, otherwise False"""

        return self.xmlrpc_delegate._is_powered_on()

    def click_bluetooth_button(self, driver, bt_page):
        """Click on the bluetooth on/off button using javascript

        @param: driver:Chromedriver object
        @param: bt_page:Bluetooth page web element
        """
        button = bt_page.find_element_by_css_selector(self.ENABLE_BT_CSS)
        driver.execute_script("arguments[0].click();", button)

    def turn_on_bluetooth(self, bt_page, driver):
        """Turn on BT through UI

        @param: bt_page:Bluetooth page web element
        """
        if self.is_bluetooth_enabled():
            logging.info('Bluetooth is turned on already..')
        else:
            self.click_bluetooth_button(driver, bt_page)
            time.sleep(self.DELAY_BW_TOGGLE_ON_OFF)
            if self.is_bluetooth_enabled():
                logging.info('Turned on BT successfully..')
            else:
                raise error.TestFail('BT is not turned on..')

    def turn_off_bluetooth(self, bt_page, driver):
        """Turn off BT through UI

        @param: bt_page:Bluetooth page web element
        """
        if not self.is_bluetooth_enabled():
            logging.info('Bluetooth is turned off already within time.')
        else:
            self.click_bluetooth_button(driver, bt_page)
            time.sleep(self.DELAY_BW_TOGGLE_ON_OFF)
            if not self.is_bluetooth_enabled():
                logging.info('Turned off BT successfully..')
            else:
                raise error.TestFail('Bluetooth is not turned off within time')

    def run_once(self, iteration_count=3):
        """Turn on/off bluetooth through UI

        @param iteration_count: Number of iterations to toggle on/off

        """
        self.success = False
        with chromedriver.chromedriver() as chromedriver_instance:
            driver = chromedriver_instance.driver
            bt_page = self.bluetooth_page(driver)
            self.turn_off_bluetooth(bt_page, driver)
            for iteration in xrange(1, iteration_count + 1):
                logging.info("**** Turn on/off BT iteration: %d ****",
                             iteration)
                self.turn_on_bluetooth(bt_page, driver)
                self.turn_off_bluetooth(bt_page, driver)
        self.success = True
