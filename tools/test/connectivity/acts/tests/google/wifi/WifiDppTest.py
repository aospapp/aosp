#!/usr/bin/env python3.4
#
#   Copyright 2018 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import binascii
import queue
import time

from acts import asserts
from acts import base_test
from acts import utils
from acts.test_decorators import test_tracker_info
from acts.test_utils.wifi import wifi_constants
from acts.test_utils.wifi import wifi_test_utils as wutils
from acts.test_utils.wifi.aware import aware_test_utils as autils

class WifiDppTest(base_test.BaseTestClass):
  """This class tests the DPP API surface.

     Attributes: The tests in this class require one DUT and one helper phone
     device.
     The tests in this class do not require a SIM.
  """

  DPP_TEST_TIMEOUT = 60
  DPP_TEST_SSID_PREFIX = "dpp_test_ssid_"
  DPP_TEST_SECURITY_SAE = "SAE"
  DPP_TEST_SECURITY_PSK_PASSPHRASE = "PSK_PASSPHRASE"
  DPP_TEST_SECURITY_PSK = "PSK"

  DPP_TEST_EVENT_DPP_CALLBACK = "onDppCallback"
  DPP_TEST_EVENT_DATA = "data"
  DPP_TEST_EVENT_ENROLLEE_SUCCESS = "onEnrolleeSuccess"
  DPP_TEST_EVENT_CONFIGURATOR_SUCCESS = "onConfiguratorSuccess"
  DPP_TEST_EVENT_PROGRESS = "onProgress"
  DPP_TEST_EVENT_FAILURE = "onFailure"
  DPP_TEST_MESSAGE_TYPE = "Type"
  DPP_TEST_MESSAGE_STATUS = "Status"
  DPP_TEST_MESSAGE_NETWORK_ID = "NetworkId"

  DPP_TEST_NETWORK_ROLE_STA = "sta"
  DPP_TEST_NETWORK_ROLE_AP = "ap"

  WPA_SUPPLICANT_SECURITY_SAE = "sae"
  WPA_SUPPLICANT_SECURITY_PSK = "psk"

  def setup_class(self):
    """ Sets up the required dependencies from the config file and configures the device for
        WifiService API tests.

        Returns:
          True is successfully configured the requirements for testig.
    """

    # Device 0 is under test. Device 1 performs the responder role
    self.dut = self.android_devices[0]
    self.helper_dev = self.android_devices[1]

    # Do a simple version of init - mainly just sync the time and enable
    # verbose logging.  We would also like to test with phones in less
    # constrained states (or add variations where we specifically
    # constrain).
    utils.require_sl4a((self.dut,))
    utils.sync_device_time(self.dut)

    # Enable verbose logging on the dut
    self.dut.droid.wifiEnableVerboseLogging(1)
    asserts.assert_true(self.dut.droid.wifiGetVerboseLoggingLevel() == 1,
                        "Failed to enable WiFi verbose logging on the dut.")

  def teardown_class(self):
    wutils.reset_wifi(self.dut)

  def on_fail(self, test_name, begin_time):
        self.dut.take_bug_report(test_name, begin_time)
        self.dut.cat_adb_log(test_name, begin_time)

  def create_and_save_wifi_network_config(self, security):
    """ Create a config with random SSID and password.

            Args:
               security: Security type: PSK or SAE

            Returns:
               A tuple with the config and networkId for the newly created and
               saved network.
    """
    config_ssid = self.DPP_TEST_SSID_PREFIX + utils.rand_ascii_str(8)
    config_password = utils.rand_ascii_str(8)
    self.dut.log.info(
        "creating config: %s %s %s" % (config_ssid, config_password, security))
    config = {
        wutils.WifiEnums.SSID_KEY: config_ssid,
        wutils.WifiEnums.PWD_KEY: config_password,
        wutils.WifiEnums.SECURITY: security
    }

    # Now save the config.
    network_id = self.dut.droid.wifiAddNetwork(config)
    self.dut.log.info("saved config: network_id = %d" % network_id)
    return network_id

  def check_network_config_saved(self, expected_ssid, security, network_id):
    """ Get the configured networks and check if the provided network ID is present.

            Args:
             expected_ssid: Expected SSID to match with received configuration.
             security: Security type to match, PSK or SAE

            Returns:
                True if the WifiConfig is present.
    """
    networks = self.dut.droid.wifiGetConfiguredNetworks()
    if not networks:
      return False

    # Normalize PSK and PSK Passphrase to PSK
    if security == self.DPP_TEST_SECURITY_PSK_PASSPHRASE:
      security = self.DPP_TEST_SECURITY_PSK

    # If the device doesn't support SAE, then the test fallbacks to PSK
    if not self.dut.droid.wifiIsWpa3SaeSupported() and \
              security == self.DPP_TEST_SECURITY_SAE:
      security = self.DPP_TEST_SECURITY_PSK

    for network in networks:
      if network_id == network['networkId'] and \
              security == network[wutils.WifiEnums.SECURITY] and \
              expected_ssid == network[wutils.WifiEnums.SSID_KEY]:
        self.log.info("Found SSID %s" % network[wutils.WifiEnums.SSID_KEY])
        return True
    return False

  def forget_network(self, network_id):
    """ Simple method to call wifiForgetNetwork and wait for confirmation callback.

            Returns:
                True if network was successfully deleted.
        """
    self.dut.log.info("Deleting config: networkId = %s" % network_id)
    self.dut.droid.wifiForgetNetwork(network_id)
    try:
      event = self.dut.ed.pop_event(wifi_constants.WIFI_FORGET_NW_SUCCESS, 10)
      return True
    except queue.Empty:
      self.dut.log.error("Failed to forget network")
      return False

  def gen_uri(self, device, info="DPP_TESTER", chan="81/1", mac=None):
    """Generate a URI on a device

            Args:
                device: Device object
                mac: MAC address to use
                info: Optional info to be embedded in URI
                chan: Optional channel info

            Returns:
             URI ID to be used later
    """

    # Clean up any previous URIs
    self.del_uri(device, "'*'")

    self.log.info("Generating a URI for the Responder")
    cmd = "wpa_cli DPP_BOOTSTRAP_GEN type=qrcode info=%s" % info

    if mac:
      cmd += " mac=%s" % mac

    if chan:
      cmd += " chan=%s" % chan

    result = device.adb.shell(cmd)

    if "FAIL" in result:
      asserts.fail("gen_uri: Failed to generate a URI. Command used: %s" % cmd)

    if not result.index("\n"):
      asserts.fail("gen_uri: Helper device not responding correctly, may need to restart it."
                   " Command used: %s" % cmd)

    result = result[result.index("\n") + 1:]
    device.log.info("Generated URI, id = %s" % result)

    return result

  def get_uri(self, device, uri_id):
    """Get a previously generated URI from a device

            Args:
                device: Device object
                uri_id: URI ID returned by gen_uri method

            Returns:
                URI string

        """
    self.log.info("Reading the contents of the URI of the Responder")
    cmd = "wpa_cli DPP_BOOTSTRAP_GET_URI %s" % uri_id
    result = device.adb.shell(cmd)

    if "FAIL" in result:
      asserts.fail("get_uri: Failed to read URI. Command used: %s" % cmd)

    result = result[result.index("\n") + 1:]
    device.log.info("URI contents = %s" % result)

    return result

  def del_uri(self, device, uri_id):
    """Delete a previously generated URI

          Args:
          device: Device object
          uri_id: URI ID returned by gen_uri method
    """
    self.log.info("Deleting the Responder URI")
    cmd = "wpa_cli DPP_BOOTSTRAP_REMOVE %s" % uri_id
    result = device.adb.shell(cmd)

    if "FAIL" in result:
      asserts.fail("del_uri: Failed to delete URI. Command used: %s" % cmd)
    device.log.info("Deleted URI, id = %s" % uri_id)

  def start_responder_configurator(self,
                                   device,
                                   freq=2412,
                                   net_role=DPP_TEST_NETWORK_ROLE_STA,
                                   security=DPP_TEST_SECURITY_SAE,
                                   invalid_config=False):
    """Start a responder on helper device

           Args:
               device: Device object
               freq: Frequency to listen on
               net_role: Network role to configure
               security: Security type: SAE or PSK
               invalid_config: Send invalid configuration (negative test)

            Returns:
                ssid: SSID name of the network to be configured

        """
    if not net_role or (net_role != self.DPP_TEST_NETWORK_ROLE_STA and
                        net_role != self.DPP_TEST_NETWORK_ROLE_AP):
      asserts.fail("start_responder: Must specify net_role sta or ap")

    self.log.info("Starting Responder in Configurator mode, frequency %sMHz" % freq)

    conf = "conf=%s-" % net_role

    use_psk = False

    if security == self.DPP_TEST_SECURITY_SAE:
      if not self.dut.droid.wifiIsWpa3SaeSupported():
        self.log.warning("SAE not supported on device! reverting to PSK")
        security = self.DPP_TEST_SECURITY_PSK_PASSPHRASE

    if security == self.DPP_TEST_SECURITY_SAE:
      conf += self.WPA_SUPPLICANT_SECURITY_SAE
    elif security == self.DPP_TEST_SECURITY_PSK_PASSPHRASE:
      conf += self.WPA_SUPPLICANT_SECURITY_PSK
    else:
      conf += self.WPA_SUPPLICANT_SECURITY_PSK
      use_psk = True

    ssid = self.DPP_TEST_SSID_PREFIX + utils.rand_ascii_str(8)
    self.log.debug("SSID = %s" % ssid)

    ssid_encoded = binascii.hexlify(ssid.encode()).decode()

    if use_psk:
      psk = utils.rand_ascii_str(16)
      if not invalid_config:
        psk_encoded = binascii.b2a_hex(psk.encode()).decode()
      else:
        # Use the psk as is without hex encoding, will make it invalid
        psk_encoded = psk
      self.log.debug("PSK = %s" % psk)
    else:
      password = utils.rand_ascii_str(8)
      if not invalid_config:
        password_encoded = binascii.b2a_hex(password.encode()).decode()
      else:
        # Use the password as is without hex encoding, will make it invalid
        password_encoded = password
      self.log.debug("Password = %s" % password)

    conf += " ssid=%s" % ssid_encoded

    if password:  # SAE password or PSK passphrase
      conf += " pass=%s" % password_encoded
    else:  # PSK
      conf += " psk=%s" % psk_encoded

    # Stop responder first
    self.stop_responder(device)

    cmd = "wpa_cli set dpp_configurator_params guard=1 %s" % conf
    device.log.debug("Command used: %s" % cmd)
    result = self.helper_dev.adb.shell(cmd)
    if "FAIL" in result:
      asserts.fail(
          "start_responder_configurator: Failure. Command used: %s" % cmd)

    cmd = "wpa_cli DPP_LISTEN %d role=configurator netrole=%s" % (freq,
                                                                  net_role)
    device.log.debug("Command used: %s" % cmd)
    result = self.helper_dev.adb.shell(cmd)
    if "FAIL" in result:
      asserts.fail(
          "start_responder_configurator: Failure. Command used: %s" % cmd)

    device.log.info("Started responder in configurator mode")
    return ssid

  def start_responder_enrollee(self,
                               device,
                               freq=2412,
                               net_role=DPP_TEST_NETWORK_ROLE_STA):
    """Start a responder-enrollee on helper device

           Args:
               device: Device object
               freq: Frequency to listen on
               net_role: Network role to request

            Returns:
                ssid: SSID name of the network to be configured

        """
    if not net_role or (net_role != self.DPP_TEST_NETWORK_ROLE_STA and
                        net_role != self.DPP_TEST_NETWORK_ROLE_AP):
      asserts.fail("start_responder: Must specify net_role sta or ap")

    # Stop responder first
    self.stop_responder(device)
    self.log.info("Starting Responder in Enrollee mode, frequency %sMHz" % freq)

    cmd = "wpa_cli DPP_LISTEN %d role=enrollee netrole=%s" % (freq, net_role)
    result = device.adb.shell(cmd)

    if "FAIL" in result:
      asserts.fail("start_responder_enrollee: Failure. Command used: %s" % cmd)

    device.log.info("Started responder in enrollee mode")

  def stop_responder(self, device):
    """Stop responder on helper device

       Args:
           device: Device object
    """
    result = device.adb.shell("wpa_cli DPP_STOP_LISTEN")
    if "FAIL" in result:
      asserts.fail("stop_responder: Failed to stop responder")
    device.adb.shell("wpa_cli set dpp_configurator_params")

    device.log.info("Stopped responder")

  def start_dpp_as_initiator_configurator(self,
                                          security,
                                          use_mac,
                                          responder_chan="81/1",
                                          responder_freq=2412,
                                          net_role=DPP_TEST_NETWORK_ROLE_STA,
                                          cause_timeout=False,
                                          fail_authentication=False,
                                          invalid_uri=False):
    """ Test Easy Connect (DPP) as initiator configurator.

                1. Enable wifi, if needed
                2. Create and save a random config.
                3. Generate a URI using the helper device
                4. Start DPP as responder-enrollee on helper device
                5. Start DPP as initiator configurator on dut
                6. Check if configurator sent successfully
                7. Delete the URI from helper device
                8. Remove the config.

        Args:
            security: Security type, a string "SAE" or "PSK"
            use_mac: A boolean indicating whether to use the device's MAC
              address (if True) or use a Broadcast (if False).
            responder_chan: Responder channel to specify in the URI
            responder_freq: Frequency that the Responder would actually listen on.
              Note: To succeed, there must be a correlation between responder_chan, which is what
              the URI advertises, and responder_freq which is the actual frequency. See:
              https://en.wikipedia.org/wiki/List_of_WLAN_channels
            net_role: Network role, a string "sta" or "ap"
            cause_timeout: Intentionally don't start the responder to cause a
              timeout
            fail_authentication: Fail authentication by corrupting the
              responder's key
            invalid_uri: Use garbage string instead of a URI
    """
    if not self.dut.droid.wifiIsEasyConnectSupported():
      self.log.warning("Easy Connect is not supported on device!")
      return

    wutils.wifi_toggle_state(self.dut, True)
    test_network_id = self.create_and_save_wifi_network_config(security)

    if use_mac:
      mac = autils.get_mac_addr(self.helper_dev, "wlan0")
    else:
      mac = None

    if invalid_uri:
      enrollee_uri = "dskjgnkdjfgnkdsjfgnsDFGDIFGKDSJFGFDbgjdsnbkjdfnkbgsdfgFDSGSDfgesouhgureho" \
                     "iu3ht98368903434089ut4958763094u0934ujg094j5oifegjfds"
    else:
      # Generate a URI with default info and channel
      uri_id = self.gen_uri(self.helper_dev, chan=responder_chan, mac=mac)

      # Get the URI. This is equal to scanning a QR code
      enrollee_uri = self.get_uri(self.helper_dev, uri_id)

      # Corrupt the responder key if required
      if fail_authentication:
        enrollee_uri = enrollee_uri[:80] + "DeAdBeeF" + enrollee_uri[88:]
        self.log.info("Corrupted enrollee URI: %s" % enrollee_uri)

    if not cause_timeout:
      # Start DPP as an enrolle-responder for STA on helper device
      self.start_responder_enrollee(self.helper_dev, freq=responder_freq, net_role=net_role)
    else:
      self.log.info("Not starting DPP responder on purpose")

    self.log.info("Starting DPP in Configurator-Initiator mode")

    # Start DPP as configurator-initiator on dut
    self.dut.droid.startEasyConnectAsConfiguratorInitiator(enrollee_uri,
                                                   test_network_id, net_role)

    start_time = time.time()
    while time.time() < start_time + self.DPP_TEST_TIMEOUT:
      dut_event = self.dut.ed.pop_event(self.DPP_TEST_EVENT_DPP_CALLBACK,
                                        self.DPP_TEST_TIMEOUT)
      if dut_event[self.DPP_TEST_EVENT_DATA][self.DPP_TEST_MESSAGE_TYPE] \
              == self.DPP_TEST_EVENT_ENROLLEE_SUCCESS:
        asserts.fail("DPP failure, unexpected result!")
        break
      if dut_event[self.DPP_TEST_EVENT_DATA][
          self
          .DPP_TEST_MESSAGE_TYPE] == self.DPP_TEST_EVENT_CONFIGURATOR_SUCCESS:
        if cause_timeout or fail_authentication or invalid_uri:
          asserts.fail(
              "Unexpected DPP success, status code: %s" %
              dut_event[self.DPP_TEST_EVENT_DATA][self.DPP_TEST_MESSAGE_STATUS])
        else:
          val = dut_event[self.DPP_TEST_EVENT_DATA][
              self.DPP_TEST_MESSAGE_STATUS]
          if val == 0:
            self.dut.log.info("DPP Configuration sent success")
        break
      if dut_event[self.DPP_TEST_EVENT_DATA][
          self.DPP_TEST_MESSAGE_TYPE] == self.DPP_TEST_EVENT_PROGRESS:
        self.dut.log.info("DPP progress event")
        val = dut_event[self.DPP_TEST_EVENT_DATA][self.DPP_TEST_MESSAGE_STATUS]
        if val == 0:
          self.dut.log.info("DPP Authentication success")
        elif val == 1:
          self.dut.log.info("DPP Response pending")
        continue
      if dut_event[self.DPP_TEST_EVENT_DATA][
          self.DPP_TEST_MESSAGE_TYPE] == self.DPP_TEST_EVENT_FAILURE:
        if cause_timeout or fail_authentication or invalid_uri:
          self.dut.log.info(
              "Error %s occurred, as expected" %
              dut_event[self.DPP_TEST_EVENT_DATA][self.DPP_TEST_MESSAGE_STATUS])
        else:
          asserts.fail(
              "DPP failure, status code: %s" %
              dut_event[self.DPP_TEST_EVENT_DATA][self.DPP_TEST_MESSAGE_STATUS])
        break

    # Clear all pending events.
    self.dut.ed.clear_all_events()

    # Stop responder
    self.stop_responder(self.helper_dev)

    if not invalid_uri:
      # Delete URI
      self.del_uri(self.helper_dev, uri_id)

    asserts.assert_true(
        self.forget_network(test_network_id),
        "Test network not deleted from configured networks.")

  def start_dpp_as_initiator_enrollee(self,
                                      security,
                                      use_mac,
                                      cause_timeout=False,
                                      invalid_config=False):
    """ Test Easy Connect (DPP) as initiator enrollee.

                1. Enable wifi, if needed
                2. Start DPP as responder-configurator on helper device
                3. Start DPP as initiator enrollee on dut
                4. Check if configuration received successfully
                5. Delete the URI from helper device
                6. Remove the config.

        Args:
            security: Security type, a string "SAE" or "PSK"
            use_mac: A boolean indicating whether to use the device's MAC
              address (if True) or use a Broadcast (if False).
            cause_timeout: Intentionally don't start the responder to cause a
              timeout
            invalid_config: Responder to intentionally send malformed
              configuration
    """
    if not self.dut.droid.wifiIsEasyConnectSupported():
      self.log.warning("Easy Connect is not supported on device!")
      return

    wutils.wifi_toggle_state(self.dut, True)

    if use_mac:
      mac = autils.get_mac_addr(self.helper_dev, "wlan0")
    else:
      mac = None

    # Generate a URI with default info and channel
    uri_id = self.gen_uri(self.helper_dev, mac=mac)

    # Get the URI. This is equal to scanning a QR code
    configurator_uri = self.get_uri(self.helper_dev, uri_id)

    if not cause_timeout:
      # Start DPP as an configurator-responder for STA on helper device
      ssid = self.start_responder_configurator(
          self.helper_dev, security=security, invalid_config=invalid_config)
    else:
      self.log.info(
          "Not starting a responder configurator on helper device, on purpose")
      ssid = self.DPP_TEST_SSID_PREFIX + utils.rand_ascii_str(8)

    self.log.info("Starting DPP in Enrollee-Initiator mode")

    # Start DPP as enrollee-initiator on dut
    self.dut.droid.startEasyConnectAsEnrolleeInitiator(configurator_uri)

    network_id = 0

    start_time = time.time()
    while time.time() < start_time + self.DPP_TEST_TIMEOUT:
      dut_event = self.dut.ed.pop_event(self.DPP_TEST_EVENT_DPP_CALLBACK,
                                        self.DPP_TEST_TIMEOUT)
      if dut_event[self.DPP_TEST_EVENT_DATA][
          self.DPP_TEST_MESSAGE_TYPE] == self.DPP_TEST_EVENT_ENROLLEE_SUCCESS:
        if cause_timeout or invalid_config:
          asserts.fail(
              "Unexpected DPP success, status code: %s" %
              dut_event[self.DPP_TEST_EVENT_DATA][self.DPP_TEST_MESSAGE_STATUS])
        else:
          self.dut.log.info("DPP Configuration received success")
          network_id = dut_event[self.DPP_TEST_EVENT_DATA][
              self.DPP_TEST_MESSAGE_NETWORK_ID]
          self.dut.log.info("NetworkID: %d" % network_id)
        break
      if dut_event[self.DPP_TEST_EVENT_DATA][
          self
          .DPP_TEST_MESSAGE_TYPE] == self.DPP_TEST_EVENT_CONFIGURATOR_SUCCESS:
        asserts.fail(
            "DPP failure, unexpected result: %s" %
            dut_event[self.DPP_TEST_EVENT_DATA][self.DPP_TEST_MESSAGE_STATUS])
        break
      if dut_event[self.DPP_TEST_EVENT_DATA][
          self.DPP_TEST_MESSAGE_TYPE] == self.DPP_TEST_EVENT_PROGRESS:
        self.dut.log.info("DPP progress event")
        val = dut_event[self.DPP_TEST_EVENT_DATA][self.DPP_TEST_MESSAGE_STATUS]
        if val == 0:
          self.dut.log.info("DPP Authentication success")
        elif val == 1:
          self.dut.log.info("DPP Response pending")
        continue
      if dut_event[self.DPP_TEST_EVENT_DATA][
          self.DPP_TEST_MESSAGE_TYPE] == self.DPP_TEST_EVENT_FAILURE:
        if cause_timeout or invalid_config:
          self.dut.log.info(
              "Error %s occurred, as expected" %
              dut_event[self.DPP_TEST_EVENT_DATA][self.DPP_TEST_MESSAGE_STATUS])
        else:
          asserts.fail(
              "DPP failure, status code: %s" %
              dut_event[self.DPP_TEST_EVENT_DATA][self.DPP_TEST_MESSAGE_STATUS])
        break
      asserts.fail("Unknown message received")

    # Clear all pending events.
    self.dut.ed.clear_all_events()

    # Stop responder
    self.stop_responder(self.helper_dev)

    # Delete URI
    self.del_uri(self.helper_dev, uri_id)

    if not (invalid_config or cause_timeout):
      # Check that the saved network is what we expect
      asserts.assert_true(
          self.check_network_config_saved(ssid, security, network_id),
          "Could not find the expected network: %s" % ssid)

      asserts.assert_true(
          self.forget_network(network_id),
          "Test network not deleted from configured networks.")

  """ Tests Begin """

  @test_tracker_info(uuid="30893d51-2069-4e1c-8917-c8a840f91b59")
  def test_dpp_as_initiator_configurator_with_psk_5G(self):
    asserts.skip_if(not self.dut.droid.wifiIs5GHzBandSupported() or
            not self.helper_dev.droid.wifiIs5GHzBandSupported(),
            "5G not supported on at least on test device")
    self.start_dpp_as_initiator_configurator(
      security=self.DPP_TEST_SECURITY_PSK, responder_chan="126/149", responder_freq=5745,
      use_mac=True)

  @test_tracker_info(uuid="54d1d19a-aece-459c-b819-9d4b1ae63f77")
  def test_dpp_as_initiator_configurator_with_psk_5G_broadcast(self):
    asserts.skip_if(not self.dut.droid.wifiIs5GHzBandSupported() or
                    not self.helper_dev.droid.wifiIs5GHzBandSupported(),
                    "5G not supported on at least on test device")
    self.start_dpp_as_initiator_configurator(
      security=self.DPP_TEST_SECURITY_PSK, responder_chan="126/149", responder_freq=5745,
      use_mac=False)

  @test_tracker_info(uuid="18270a69-300c-4f54-87fd-c19073a2854e ")
  def test_dpp_as_initiator_configurator_with_psk_no_chan_in_uri_listen_on_5745_broadcast(self):
    asserts.skip_if(not self.dut.droid.wifiIs5GHzBandSupported() or
                    not self.helper_dev.droid.wifiIs5GHzBandSupported(),
                    "5G not supported on at least on test device")
    self.start_dpp_as_initiator_configurator(
      security=self.DPP_TEST_SECURITY_PSK, responder_chan=None, responder_freq=5745, use_mac=False)

  @test_tracker_info(uuid="fbdd687c-954a-400b-9da3-2d17e28b0798")
  def test_dpp_as_initiator_configurator_with_psk_no_chan_in_uri_listen_on_5745(self):
    asserts.skip_if(not self.dut.droid.wifiIs5GHzBandSupported() or
                    not self.helper_dev.droid.wifiIs5GHzBandSupported(),
                    "5G not supported on at least on test device")
    self.start_dpp_as_initiator_configurator(
      security=self.DPP_TEST_SECURITY_PSK, responder_chan=None, responder_freq=5745, use_mac=True)

  @test_tracker_info(uuid="570f499f-ab12-4405-af14-c9ed36da2e01 ")
  def test_dpp_as_initiator_configurator_with_psk_no_chan_in_uri_listen_on_2462_broadcast(self):
    self.start_dpp_as_initiator_configurator(
      security=self.DPP_TEST_SECURITY_PSK, responder_chan=None, responder_freq=2462, use_mac=False)

  @test_tracker_info(uuid="e1f083e0-0878-4c49-8ac5-d7c6bba24625")
  def test_dpp_as_initiator_configurator_with_psk_no_chan_in_uri_listen_on_2462(self):
    self.start_dpp_as_initiator_configurator(
      security=self.DPP_TEST_SECURITY_PSK, responder_chan=None, responder_freq=2462, use_mac=True)

  @test_tracker_info(uuid="d2a526f5-4269-493d-bd79-4e6d1b7b00f0")
  def test_dpp_as_initiator_configurator_with_psk(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_PSK, use_mac=True)

  @test_tracker_info(uuid="6ead218c-222b-45b8-8aad-fe7d883ed631")
  def test_dpp_as_initiator_configurator_with_sae(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_SAE, use_mac=True)

  @test_tracker_info(uuid="1686adb5-1b3c-4e6d-a969-6b007bdd990d")
  def test_dpp_as_initiator_configurator_with_psk_passphrase(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE, use_mac=True)

  @test_tracker_info(uuid="3958feb5-1a0c-4487-9741-ac06f04c55a2")
  def test_dpp_as_initiator_configurator_with_sae_broadcast(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_SAE, use_mac=False)

  @test_tracker_info(uuid="fe6d66f5-73a1-46e9-8f49-73b8f332cc8c")
  def test_dpp_as_initiator_configurator_with_psk_passphrase_broadcast(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE, use_mac=False)

  @test_tracker_info(uuid="9edd372d-e2f1-4545-8d04-6a1636fcbc4b")
  def test_dpp_as_initiator_configurator_with_sae_for_ap(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_SAE,
        use_mac=True,
        net_role=self.DPP_TEST_NETWORK_ROLE_AP)

  @test_tracker_info(uuid="e9eec912-d665-4926-beac-859cb13dc17b")
  def test_dpp_as_initiator_configurator_with_psk_passphrase_for_ap(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE,
        use_mac=True,
        net_role=self.DPP_TEST_NETWORK_ROLE_AP)

  @test_tracker_info(uuid="8055694f-606f-41dd-9826-3ea1e9b007f8")
  def test_dpp_as_initiator_enrollee_with_sae(self):
    self.start_dpp_as_initiator_enrollee(
        security=self.DPP_TEST_SECURITY_SAE, use_mac=True)

  @test_tracker_info(uuid="c1e9f605-b5c0-4e53-8a08-1b0087a667fa")
  def test_dpp_as_initiator_enrollee_with_psk_passphrase(self):
    self.start_dpp_as_initiator_enrollee(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE, use_mac=True)

  @test_tracker_info(uuid="1d7f30ad-2f9a-427a-8059-651dc8827ae2")
  def test_dpp_as_initiator_enrollee_with_sae_broadcast(self):
    self.start_dpp_as_initiator_enrollee(
        security=self.DPP_TEST_SECURITY_SAE, use_mac=False)

  @test_tracker_info(uuid="0cfc2645-600e-4f2b-ab5c-fcee6d363a9a")
  def test_dpp_as_initiator_enrollee_with_psk_passphrase_broadcast(self):
    self.start_dpp_as_initiator_enrollee(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE, use_mac=False)

  @test_tracker_info(uuid="2e26b248-65dd-41f6-977b-e223d72b2de9")
  def test_start_dpp_as_initiator_enrollee_receive_invalid_config(self):
    self.start_dpp_as_initiator_enrollee(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE,
        use_mac=True,
        invalid_config=True)

  @test_tracker_info(uuid="ed189661-d1c1-4626-9f01-3b7bb8a417fe")
  def test_dpp_as_initiator_configurator_fail_authentication(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE,
        use_mac=True,
        fail_authentication=True)

  @test_tracker_info(uuid="5a8c6587-fbb4-4a27-9cba-af6f8935833a")
  def test_dpp_as_initiator_configurator_fail_unicast_timeout(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE,
        use_mac=True,
        cause_timeout=True)

  @test_tracker_info(uuid="b12353ac-1a04-4036-81a4-2d2d0c653dbb")
  def test_dpp_as_initiator_configurator_fail_broadcast_timeout(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE,
        use_mac=False,
        cause_timeout=True)

  @test_tracker_info(uuid="eeff91be-09ce-4a33-8b4f-ece40eb51c76")
  def test_dpp_as_initiator_configurator_invalid_uri(self):
    self.start_dpp_as_initiator_configurator(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE,
        use_mac=True,
        invalid_uri=True)

  @test_tracker_info(uuid="1fa25f58-0d0e-40bd-8714-ab78957514d9")
  def test_start_dpp_as_initiator_enrollee_fail_timeout(self):
    self.start_dpp_as_initiator_enrollee(
        security=self.DPP_TEST_SECURITY_PSK_PASSPHRASE,
        use_mac=True,
        cause_timeout=True)

  """ Tests End """
