# Copyright (c) 2012 The Chromium Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""File containing class to build all available ap_configurators."""

import logging
import requests

from autotest_lib.client.common_lib.cros.network import ap_constants
from autotest_lib.server import site_utils
from autotest_lib.server.cros import ap_config
from autotest_lib.server.cros.ap_configurators import ap_cartridge
from autotest_lib.server.cros.ap_configurators import ap_spec
from autotest_lib.server.cros.dynamic_suite import frontend_wrappers

CHAOS_URL = 'https://chaos-188802.appspot.com'


class APConfiguratorFactory(object):
    """Class that instantiates all available APConfigurators.

    @attribute CONFIGURATOR_MAP: a dict of strings, mapping to model-specific
                                 APConfigurator objects.
    @attribute BANDS: a string, bands supported by an AP.
    @attribute MODES: a string, 802.11 modes supported by an AP.
    @attribute SECURITIES: a string, security methods supported by an AP.
    @attribute HOSTNAMES: a string, AP hostname.
    @attribute ap_list: a list of APConfigurator objects.
    @attribute ap_config: an APConfiguratorConfig object.
    """

    PREFIX='autotest_lib.server.cros.ap_configurators.'
    CONFIGURATOR_MAP = {
        'StaticAPConfigurator':
            [PREFIX + 'static_ap_configurator',
                'StaticAPConfigurator'],
    }

    BANDS = 'bands'
    MODES = 'modes'
    SECURITIES = 'securities'
    HOSTNAMES = 'hostnames'


    def __init__(self, ap_test_type, spec=None):
        webdriver_ready = False
        self.ap_list = []
        self.test_type = ap_test_type
        for ap in ap_config.get_ap_list(ap_test_type):
            module_name, configurator_class = \
                    self.CONFIGURATOR_MAP[ap.get_class()]
            module = __import__(module_name, fromlist=configurator_class)
            configurator = module.__dict__[configurator_class]
            self.ap_list.append(configurator(ap_config=ap))


    def _get_aps_by_visibility(self, visible=True):
        """Returns all configurators that support setting visibility.

        @param visibility = True if SSID should be visible; False otherwise.

        @returns aps: a set of APConfigurators"""
        if visible:
            return set(self.ap_list)

        return set(filter(lambda ap: ap.is_visibility_supported(),
                          self.ap_list))


    def _get_aps_by_mode(self, band, mode):
        """Returns all configurators that support a given 802.11 mode.

        @param band: an 802.11 band.
        @param mode: an 802.11 modes.

        @returns aps: a set of APConfigurators.
        """
        if not mode:
            return set(self.ap_list)

        aps = []
        for ap in self.ap_list:
            modes = ap.get_supported_modes()
            for d in modes:
                if d['band'] == band and mode in d['modes']:
                    aps.append(ap)
        return set(aps)


    def _get_aps_by_security(self, security):
        """Returns all configurators that support a given security mode.

        @param security: the security type

        @returns aps: a set of APConfigurators.
        """

        if not security:
            return set(self.ap_list)

        aps = []
        for ap in self.ap_list:
            if ap.is_security_mode_supported(security):
                aps.append(ap)
        return set(aps)


    def _get_aps_by_band(self, band, channel=None):
        """Returns all APs that support a given band.

        @param band: the band desired.

        @returns aps: a set of APConfigurators.
        """
        if not band:
            return set(self.ap_list)

        aps = []
        for ap in self.ap_list:
            bands_and_channels = ap.get_supported_bands()
            for d in bands_and_channels:
                if channel:
                    if d['band'] == band and channel in d['channels']:
                        aps.append(ap)
                elif d['band'] == band:
                    aps.append(ap)
        return set(aps)


    def get_aps_by_hostnames(self, hostnames, ap_list=None):
        """Returns specific APs by host name.

        @param hostnames: a list of strings, AP's wan_hostname defined in the AP
                          configuration file.
        @param ap_list: a list of APConfigurator objects.

        @return a list of APConfigurators.
        """
        if ap_list == None:
            ap_list = self.ap_list

        aps = []
        for ap in ap_list:
            if ap.host_name in hostnames:
                logging.info('Found AP by hostname %s', ap.host_name)
                aps.append(ap)

        return aps


    def _get_aps_by_configurator_type(self, configurator_type, ap_list):
        """Returns APs that match the given configurator type.

        @param configurator_type: the type of configurtor to return.
        @param ap_list: a list of APConfigurator objects.

        @return a list of APConfigurators.
        """
        aps = []
        for ap in ap_list:
            if ap.configurator_type == configurator_type:
                aps.append(ap)

        return aps


    def _get_aps_by_lab_location(self, want_chamber_aps, ap_list):
        """Returns APs that are inside or outside of the chaos/clique lab.

        @param want_chamber_aps: True to select only APs in the chaos/clique
        chamber. False to select APs outside of the chaos/clique chamber.
        @param ap_list: a list of APConfigurator objects.

        @return a list of APConfigurators
        """
        aps = []
        afe = frontend_wrappers.RetryingAFE(
                timeout_min=10, delay_sec=5, server=site_utils.get_global_afe_hostname())
        if self.test_type == ap_constants.AP_TEST_TYPE_CHAOS:
            ap_label = 'chaos_ap'
            lab_label = 'chaos_chamber'
        elif self.test_type == ap_constants.AP_TEST_TYPE_CLIQUE:
            ap_label = 'clique_ap'
            lab_label = 'clique_chamber'
        elif self.test_type == ap_constants.AP_TEST_TYPE_CASEY5:
            ap_label = 'casey_ap5'
            lab_label = 'casey_chamber5'
        elif self.test_type == ap_constants.AP_TEST_TYPE_CASEY7:
            ap_label = 'casey_ap7'
            lab_label = 'casey_chamber7'
        else:
            return None
        all_aps = set(afe.get_hostnames(label=ap_label))
        chamber_devices = set(afe.get_hostnames(label=lab_label))
        chamber_aps = all_aps.intersection(chamber_devices)
        for ap in ap_list:
            if want_chamber_aps and ap.host_name in chamber_aps:
                aps.append(ap)

            if not want_chamber_aps and ap.host_name not in chamber_aps:
                aps.append(ap)

        return aps

    def _get_ds_aps_by_lab_location(self, want_chamber_aps, ap_list):
        """Returns APs that are inside or outside of the chaos/clique lab.

        @param want_chamber_aps: True to select only APs in the chaos/clique
        chamber. False to select APs outside of the chaos/clique chamber.
        @param ap_list: a list of APConfigurator objects.

        @return a list of APConfigurators
        """
        aps = []
        if self.test_type == ap_constants.AP_TEST_TYPE_CHAOS:
            ap_label = 'chaos_ap'
            lab_label = 'chaos_chamber'
        elif self.test_type == ap_constants.AP_TEST_TYPE_CLIQUE:
            ap_label = 'clique_ap'
            lab_label = 'clique_chamber'
        elif self.test_type == ap_constants.AP_TEST_TYPE_CASEY5:
            ap_label = 'casey_ap5'
            lab_label = 'casey_chamber5'
        elif self.test_type == ap_constants.AP_TEST_TYPE_CASEY7:
            ap_label = 'casey_ap7'
            lab_label = 'casey_chamber7'
        else:
            return None

        chamber_aps = []

        # Request datastore for devices with requested labels.
        device_query = requests.put(CHAOS_URL + '/devices/location', \
                       json={"ap_label":ap_label, "lab_label":lab_label})

        # Add hostnames to chamber_aps list
        for device in device_query.json():
            chamber_aps.append(device['hostname'])

        for ap in ap_list:
            if want_chamber_aps and ap.host_name in chamber_aps:
                aps.append(ap)

            if not want_chamber_aps and ap.host_name not in chamber_aps:
                aps.append(ap)

        return aps


    def get_ap_configurators_by_spec(self, spec=None, pre_configure=False):
        """Returns available configurators meeting spec.

        @param spec: a validated ap_spec object
        @param pre_configure: boolean, True to set all of the configuration
                              options for the APConfigurator object using the
                              given ap_spec; False otherwise.  An ap_spec must
                              be passed for this to have any effect.
        @returns aps: a list of APConfigurator objects
        """
        if not spec:
            return self.ap_list

        # APSpec matching is exact.  With the exception of lab location, even
        # if a hostname is passed the capabilities of a given configurator
        # much match everything in the APSpec.  This helps to prevent failures
        # during the pre-scan phase.
        aps = self._get_aps_by_band(spec.band, channel=spec.channel)
        aps &= self._get_aps_by_mode(spec.band, spec.mode)
        aps &= self._get_aps_by_security(spec.security)
        aps &= self._get_aps_by_visibility(spec.visible)
        matching_aps = list(aps)
        # If APs hostnames are provided, assume the tester knows the location
        # of the AP and skip AFE calls.
        if spec.hostnames is None:
            matching_aps = self._get_aps_by_lab_location(spec.lab_ap,
                                                         matching_aps)
            # TODO(@rjahagir): Uncomment to use datastore methods.
            # matching_aps = self._get_ds_aps_by_lab_location(spec.lab_ap,
            #                                                 matching_aps)

        if spec.configurator_type != ap_spec.CONFIGURATOR_ANY:
            matching_aps = self._get_aps_by_configurator_type(
                           spec.configurator_type, matching_aps)
        if spec.hostnames is not None:
            matching_aps = self.get_aps_by_hostnames(spec.hostnames,
                                                     ap_list=matching_aps)
        if pre_configure:
            for ap in matching_aps:
                ap.set_using_ap_spec(spec)
        return matching_aps


    def turn_off_all_routers(self, broken_pdus):
        """Powers down all of the routers.

        @param broken_pdus: list of bad/offline PDUs.
        """
        ap_power_cartridge = ap_cartridge.APCartridge()
        for ap in self.ap_list:
            ap.power_down_router()
            ap_power_cartridge.push_configurator(ap)
        ap_power_cartridge.run_configurators(broken_pdus)
