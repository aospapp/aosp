#!/usr/bin/python3.4
#
#   Copyright 2017 - The Android Open Source Project
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

import time

from acts import asserts
from acts.test_decorators import test_tracker_info
from acts.test_utils.net import connectivity_const as cconsts
from acts.test_utils.wifi.aware import aware_const as aconsts
from acts.test_utils.wifi.aware import aware_test_utils as autils
from acts.test_utils.wifi.aware.AwareBaseTest import AwareBaseTest


class DataPathTest(AwareBaseTest):
  """Set of tests for Wi-Fi Aware data-path."""

  # configuration parameters used by tests
  ENCR_TYPE_OPEN = 0
  ENCR_TYPE_PASSPHRASE = 1
  ENCR_TYPE_PMK = 2

  PASSPHRASE = "This is some random passphrase - very very secure!!"
  PASSPHRASE_MIN = "01234567"
  PASSPHRASE_MAX = "012345678901234567890123456789012345678901234567890123456789012"
  PMK = "ODU0YjE3YzdmNDJiNWI4NTQ2NDJjNDI3M2VkZTQyZGU="
  PASSPHRASE2 = "This is some random passphrase - very very secure - but diff!!"
  PMK2 = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI="

  PING_MSG = "ping"

  # message re-transmit counter (increases reliability in open-environment)
  # Note: reliability of message transmission is tested elsewhere
  MSG_RETX_COUNT = 5  # hard-coded max value, internal API

  # number of second to 'reasonably' wait to make sure that devices synchronize
  # with each other - useful for OOB test cases, where the OOB discovery would
  # take some time
  WAIT_FOR_CLUSTER = 5

  def __init__(self, controllers):
    AwareBaseTest.__init__(self, controllers)

  def create_config(self, dtype):
    """Create a base configuration based on input parameters.

    Args:
      dtype: Publish or Subscribe discovery type

    Returns:
      Discovery configuration object.
    """
    config = {}
    config[aconsts.DISCOVERY_KEY_DISCOVERY_TYPE] = dtype
    config[aconsts.DISCOVERY_KEY_SERVICE_NAME] = "GoogleTestServiceDataPath"
    return config

  def request_network(self, dut, ns):
    """Request a Wi-Fi Aware network.

    Args:
      dut: Device
      ns: Network specifier
    Returns: the request key
    """
    network_req = {"TransportType": 5, "NetworkSpecifier": ns}
    return dut.droid.connectivityRequestWifiAwareNetwork(network_req)

  def set_up_discovery(self, ptype, stype, get_peer_id):
    """Set up discovery sessions and wait for service discovery.

    Args:
      ptype: Publish discovery type
      stype: Subscribe discovery type
      get_peer_id: Send a message across to get the peer's id
    """
    p_dut = self.android_devices[0]
    p_dut.pretty_name = "Publisher"
    s_dut = self.android_devices[1]
    s_dut.pretty_name = "Subscriber"

    # Publisher+Subscriber: attach and wait for confirmation
    p_id = p_dut.droid.wifiAwareAttach()
    autils.wait_for_event(p_dut, aconsts.EVENT_CB_ON_ATTACHED)
    time.sleep(self.device_startup_offset)
    s_id = s_dut.droid.wifiAwareAttach()
    autils.wait_for_event(s_dut, aconsts.EVENT_CB_ON_ATTACHED)

    # Publisher: start publish and wait for confirmation
    p_disc_id = p_dut.droid.wifiAwarePublish(p_id, self.create_config(ptype))
    autils.wait_for_event(p_dut, aconsts.SESSION_CB_ON_PUBLISH_STARTED)

    # Subscriber: start subscribe and wait for confirmation
    s_disc_id = s_dut.droid.wifiAwareSubscribe(s_id, self.create_config(stype))
    autils.wait_for_event(s_dut, aconsts.SESSION_CB_ON_SUBSCRIBE_STARTED)

    # Subscriber: wait for service discovery
    discovery_event = autils.wait_for_event(
        s_dut, aconsts.SESSION_CB_ON_SERVICE_DISCOVERED)
    peer_id_on_sub = discovery_event["data"][aconsts.SESSION_CB_KEY_PEER_ID]

    peer_id_on_pub = None
    if get_peer_id: # only need message to receive peer ID
      # Subscriber: send message to peer (Publisher - so it knows our address)
      s_dut.droid.wifiAwareSendMessage(s_disc_id, peer_id_on_sub,
                                       self.get_next_msg_id(), self.PING_MSG,
                                       self.MSG_RETX_COUNT)
      autils.wait_for_event(s_dut, aconsts.SESSION_CB_ON_MESSAGE_SENT)

      # Publisher: wait for received message
      pub_rx_msg_event = autils.wait_for_event(
          p_dut, aconsts.SESSION_CB_ON_MESSAGE_RECEIVED)
      peer_id_on_pub = pub_rx_msg_event["data"][aconsts.SESSION_CB_KEY_PEER_ID]

    return (p_dut, s_dut, p_id, s_id, p_disc_id, s_disc_id, peer_id_on_sub,
            peer_id_on_pub)

  def run_ib_data_path_test(self,
      ptype,
      stype,
      encr_type,
      use_peer_id,
      passphrase_to_use=None):
    """Runs the in-band data-path tests.

    Args:
      ptype: Publish discovery type
      stype: Subscribe discovery type
      encr_type: Encryption type, one of ENCR_TYPE_*
      use_peer_id: On Responder (publisher): True to use peer ID, False to
                   accept any request
      passphrase_to_use: The passphrase to use if encr_type=ENCR_TYPE_PASSPHRASE
                         If None then use self.PASSPHRASE
    """
    (p_dut, s_dut, p_id, s_id, p_disc_id, s_disc_id, peer_id_on_sub,
     peer_id_on_pub) = self.set_up_discovery(ptype, stype, use_peer_id)

    passphrase = None
    pmk = None
    if encr_type == self.ENCR_TYPE_PASSPHRASE:
      passphrase = self.PASSPHRASE if passphrase_to_use == None else passphrase_to_use
    elif encr_type == self.ENCR_TYPE_PMK:
      pmk = self.PMK

    # Publisher: request network
    p_req_key = self.request_network(
        p_dut,
        p_dut.droid.wifiAwareCreateNetworkSpecifier(p_disc_id, peer_id_on_pub if
        use_peer_id else None, passphrase, pmk))

    # Subscriber: request network
    s_req_key = self.request_network(
        s_dut,
        s_dut.droid.wifiAwareCreateNetworkSpecifier(s_disc_id, peer_id_on_sub,
                                                    passphrase, pmk))

    # Publisher & Subscriber: wait for network formation
    p_net_event = autils.wait_for_event_with_keys(
        p_dut, cconsts.EVENT_NETWORK_CALLBACK,
        autils.EVENT_NDP_TIMEOUT,
        (cconsts.NETWORK_CB_KEY_EVENT,
         cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED),
        (cconsts.NETWORK_CB_KEY_ID, p_req_key))
    s_net_event = autils.wait_for_event_with_keys(
        s_dut, cconsts.EVENT_NETWORK_CALLBACK,
        autils.EVENT_NDP_TIMEOUT,
        (cconsts.NETWORK_CB_KEY_EVENT,
         cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED),
        (cconsts.NETWORK_CB_KEY_ID, s_req_key))

    p_aware_if = p_net_event["data"][cconsts.NETWORK_CB_KEY_INTERFACE_NAME]
    s_aware_if = s_net_event["data"][cconsts.NETWORK_CB_KEY_INTERFACE_NAME]
    self.log.info("Interface names: p=%s, s=%s", p_aware_if, s_aware_if)

    p_ipv6 = p_dut.droid.connectivityGetLinkLocalIpv6Address(p_aware_if).split(
        "%")[0]
    s_ipv6 = s_dut.droid.connectivityGetLinkLocalIpv6Address(s_aware_if).split(
        "%")[0]
    self.log.info("Interface addresses (IPv6): p=%s, s=%s", p_ipv6, s_ipv6)

    # TODO: possibly send messages back and forth, prefer to use netcat/nc

    # terminate sessions and wait for ON_LOST callbacks
    p_dut.droid.wifiAwareDestroy(p_id)
    s_dut.droid.wifiAwareDestroy(s_id)

    autils.wait_for_event_with_keys(
        p_dut, cconsts.EVENT_NETWORK_CALLBACK, autils.EVENT_NDP_TIMEOUT,
        (cconsts.NETWORK_CB_KEY_EVENT,
         cconsts.NETWORK_CB_LOST), (cconsts.NETWORK_CB_KEY_ID, p_req_key))
    autils.wait_for_event_with_keys(
        s_dut, cconsts.EVENT_NETWORK_CALLBACK, autils.EVENT_NDP_TIMEOUT,
        (cconsts.NETWORK_CB_KEY_EVENT,
         cconsts.NETWORK_CB_LOST), (cconsts.NETWORK_CB_KEY_ID, s_req_key))

    # clean-up
    p_dut.droid.connectivityUnregisterNetworkCallback(p_req_key)
    s_dut.droid.connectivityUnregisterNetworkCallback(s_req_key)

  def run_oob_data_path_test(self, encr_type, use_peer_id):
    """Runs the out-of-band data-path tests.

    Args:
      encr_type: Encryption type, one of ENCR_TYPE_*
      use_peer_id: On Responder: True to use peer ID, False to accept any
                   request
    """
    init_dut = self.android_devices[0]
    init_dut.pretty_name = "Initiator"
    resp_dut = self.android_devices[1]
    resp_dut.pretty_name = "Responder"

    # Initiator+Responder: attach and wait for confirmation & identity
    init_id = init_dut.droid.wifiAwareAttach(True)
    autils.wait_for_event(init_dut, aconsts.EVENT_CB_ON_ATTACHED)
    init_ident_event = autils.wait_for_event(
        init_dut, aconsts.EVENT_CB_ON_IDENTITY_CHANGED)
    init_mac = init_ident_event["data"]["mac"]
    time.sleep(self.device_startup_offset)
    resp_id = resp_dut.droid.wifiAwareAttach(True)
    autils.wait_for_event(resp_dut, aconsts.EVENT_CB_ON_ATTACHED)
    resp_ident_event = autils.wait_for_event(
        resp_dut, aconsts.EVENT_CB_ON_IDENTITY_CHANGED)
    resp_mac = resp_ident_event["data"]["mac"]

    # wait for for devices to synchronize with each other - there are no other
    # mechanisms to make sure this happens for OOB discovery (except retrying
    # to execute the data-path request)
    time.sleep(self.WAIT_FOR_CLUSTER)

    passphrase = None
    pmk = None
    if encr_type == self.ENCR_TYPE_PASSPHRASE:
      passphrase = self.PASSPHRASE
    elif encr_type == self.ENCR_TYPE_PMK:
      pmk = self.PMK

    # Responder: request network
    resp_req_key = self.request_network(
        resp_dut,
        resp_dut.droid.wifiAwareCreateNetworkSpecifierOob(
            resp_id, aconsts.DATA_PATH_RESPONDER, init_mac
            if use_peer_id else None, passphrase, pmk))

    # Initiator: request network
    init_req_key = self.request_network(
        init_dut,
        init_dut.droid.wifiAwareCreateNetworkSpecifierOob(
            init_id, aconsts.DATA_PATH_INITIATOR, resp_mac, passphrase, pmk))

    # Initiator & Responder: wait for network formation
    init_net_event = autils.wait_for_event_with_keys(
        init_dut, cconsts.EVENT_NETWORK_CALLBACK,
        autils.EVENT_NDP_TIMEOUT,
        (cconsts.NETWORK_CB_KEY_EVENT,
         cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED),
        (cconsts.NETWORK_CB_KEY_ID, init_req_key))
    resp_net_event = autils.wait_for_event_with_keys(
        resp_dut, cconsts.EVENT_NETWORK_CALLBACK,
        autils.EVENT_NDP_TIMEOUT,
        (cconsts.NETWORK_CB_KEY_EVENT,
         cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED),
        (cconsts.NETWORK_CB_KEY_ID, resp_req_key))

    init_aware_if = init_net_event["data"][
      cconsts.NETWORK_CB_KEY_INTERFACE_NAME]
    resp_aware_if = resp_net_event["data"][
      cconsts.NETWORK_CB_KEY_INTERFACE_NAME]
    self.log.info("Interface names: I=%s, R=%s", init_aware_if, resp_aware_if)

    init_ipv6 = init_dut.droid.connectivityGetLinkLocalIpv6Address(
        init_aware_if).split("%")[0]
    resp_ipv6 = resp_dut.droid.connectivityGetLinkLocalIpv6Address(
        resp_aware_if).split("%")[0]
    self.log.info("Interface addresses (IPv6): I=%s, R=%s", init_ipv6,
                  resp_ipv6)

    # TODO: possibly send messages back and forth, prefer to use netcat/nc

    # terminate sessions and wait for ON_LOST callbacks
    init_dut.droid.wifiAwareDestroy(init_id)
    resp_dut.droid.wifiAwareDestroy(resp_id)

    autils.wait_for_event_with_keys(
        init_dut, cconsts.EVENT_NETWORK_CALLBACK, autils.EVENT_NDP_TIMEOUT,
        (cconsts.NETWORK_CB_KEY_EVENT,
         cconsts.NETWORK_CB_LOST), (cconsts.NETWORK_CB_KEY_ID, init_req_key))
    autils.wait_for_event_with_keys(
        resp_dut, cconsts.EVENT_NETWORK_CALLBACK, autils.EVENT_NDP_TIMEOUT,
        (cconsts.NETWORK_CB_KEY_EVENT,
         cconsts.NETWORK_CB_LOST), (cconsts.NETWORK_CB_KEY_ID, resp_req_key))

    # clean-up
    resp_dut.droid.connectivityUnregisterNetworkCallback(resp_req_key)
    init_dut.droid.connectivityUnregisterNetworkCallback(init_req_key)

  def run_mismatched_ib_data_path_test(self, pub_mismatch, sub_mismatch):
    """Runs the negative in-band data-path tests: mismatched peer ID.

    Args:
      pub_mismatch: Mismatch the publisher's ID
      sub_mismatch: Mismatch the subscriber's ID
    """
    (p_dut, s_dut, p_id, s_id, p_disc_id, s_disc_id,
     peer_id_on_sub, peer_id_on_pub) = self.set_up_discovery(
         aconsts.PUBLISH_TYPE_UNSOLICITED, aconsts.SUBSCRIBE_TYPE_PASSIVE, True)

    if pub_mismatch:
      peer_id_on_pub = peer_id_on_pub -1
    if sub_mismatch:
      peer_id_on_sub = peer_id_on_sub - 1

    # Publisher: request network
    p_req_key = self.request_network(
        p_dut,
        p_dut.droid.wifiAwareCreateNetworkSpecifier(p_disc_id, peer_id_on_pub,
                                                    None))

    # Subscriber: request network
    s_req_key = self.request_network(
        s_dut,
        s_dut.droid.wifiAwareCreateNetworkSpecifier(s_disc_id, peer_id_on_sub,
                                                    None))

    # Publisher & Subscriber: fail on network formation
    time.sleep(autils.EVENT_NDP_TIMEOUT)
    autils.fail_on_event_with_keys(p_dut, cconsts.EVENT_NETWORK_CALLBACK, 0,
                                   (cconsts.NETWORK_CB_KEY_ID, p_req_key))
    autils.fail_on_event_with_keys(s_dut, cconsts.EVENT_NETWORK_CALLBACK, 0,
                                   (cconsts.NETWORK_CB_KEY_ID, s_req_key))

    # clean-up
    p_dut.droid.connectivityUnregisterNetworkCallback(p_req_key)
    s_dut.droid.connectivityUnregisterNetworkCallback(s_req_key)

  def run_mismatched_oob_data_path_test(self,
      init_mismatch_mac=False,
      resp_mismatch_mac=False,
      init_encr_type=ENCR_TYPE_OPEN,
      resp_encr_type=ENCR_TYPE_OPEN):
    """Runs the negative out-of-band data-path tests: mismatched information
    between Responder and Initiator.

    Args:
      init_mismatch_mac: True to mismatch the Initiator MAC address
      resp_mismatch_mac: True to mismatch the Responder MAC address
      init_encr_type: Encryption type of Initiator - ENCR_TYPE_*
      resp_encr_type: Encryption type of Responder - ENCR_TYPE_*
    """
    init_dut = self.android_devices[0]
    init_dut.pretty_name = "Initiator"
    resp_dut = self.android_devices[1]
    resp_dut.pretty_name = "Responder"

    # Initiator+Responder: attach and wait for confirmation & identity
    init_id = init_dut.droid.wifiAwareAttach(True)
    autils.wait_for_event(init_dut, aconsts.EVENT_CB_ON_ATTACHED)
    init_ident_event = autils.wait_for_event(
        init_dut, aconsts.EVENT_CB_ON_IDENTITY_CHANGED)
    init_mac = init_ident_event["data"]["mac"]
    time.sleep(self.device_startup_offset)
    resp_id = resp_dut.droid.wifiAwareAttach(True)
    autils.wait_for_event(resp_dut, aconsts.EVENT_CB_ON_ATTACHED)
    resp_ident_event = autils.wait_for_event(
        resp_dut, aconsts.EVENT_CB_ON_IDENTITY_CHANGED)
    resp_mac = resp_ident_event["data"]["mac"]

    if init_mismatch_mac: # assumes legit ones don't start with "00"
      init_mac = "00" + init_mac[2:]
    if resp_mismatch_mac:
      resp_mac = "00" + resp_mac[2:]

    # wait for for devices to synchronize with each other - there are no other
    # mechanisms to make sure this happens for OOB discovery (except retrying
    # to execute the data-path request)
    time.sleep(self.WAIT_FOR_CLUSTER)

    # set up separate keys: even if types are the same we want a mismatch
    init_passphrase = None
    init_pmk = None
    if init_encr_type == self.ENCR_TYPE_PASSPHRASE:
      init_passphrase = self.PASSPHRASE
    elif init_encr_type == self.ENCR_TYPE_PMK:
      init_pmk = self.PMK

    resp_passphrase = None
    resp_pmk = None
    if resp_encr_type == self.ENCR_TYPE_PASSPHRASE:
      resp_passphrase = self.PASSPHRASE2
    elif resp_encr_type == self.ENCR_TYPE_PMK:
      resp_pmk = self.PMK2

    # Responder: request network
    resp_req_key = self.request_network(
        resp_dut,
        resp_dut.droid.wifiAwareCreateNetworkSpecifierOob(
            resp_id, aconsts.DATA_PATH_RESPONDER, init_mac, resp_passphrase,
            resp_pmk))

    # Initiator: request network
    init_req_key = self.request_network(
        init_dut,
        init_dut.droid.wifiAwareCreateNetworkSpecifierOob(
            init_id, aconsts.DATA_PATH_INITIATOR, resp_mac, init_passphrase,
            init_pmk))

    # Initiator & Responder: fail on network formation
    time.sleep(autils.EVENT_NDP_TIMEOUT)
    autils.fail_on_event_with_keys(init_dut, cconsts.EVENT_NETWORK_CALLBACK, 0,
                                   (cconsts.NETWORK_CB_KEY_ID, init_req_key))
    autils.fail_on_event_with_keys(resp_dut, cconsts.EVENT_NETWORK_CALLBACK, 0,
                                   (cconsts.NETWORK_CB_KEY_ID, resp_req_key))

    # clean-up
    resp_dut.droid.connectivityUnregisterNetworkCallback(resp_req_key)
    init_dut.droid.connectivityUnregisterNetworkCallback(init_req_key)


  #######################################
  # Positive In-Band (IB) tests key:
  #
  # names is: test_ib_<pub_type>_<sub_type>_<encr_type>_<peer_spec>
  # where:
  #
  # pub_type: Type of publish discovery session: unsolicited or solicited.
  # sub_type: Type of subscribe discovery session: passive or active.
  # encr_type: Encription type: open, passphrase
  # peer_spec: Peer specification method: any or specific
  #
  # Note: In-Band means using Wi-Fi Aware for discovery and referring to the
  # peer using the Aware-provided peer handle (as opposed to a MAC address).
  #######################################

  @test_tracker_info(uuid="fa30bedc-d1de-4440-bf25-ec00d10555af")
  def test_ib_unsolicited_passive_open_specific(self):
    """Data-path: in-band, unsolicited/passive, open encryption, specific peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_UNSOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_PASSIVE,
        encr_type=self.ENCR_TYPE_OPEN,
        use_peer_id=True)

  @test_tracker_info(uuid="57fc9d53-32ae-470f-a8b1-2fe37893687d")
  def test_ib_unsolicited_passive_open_any(self):
    """Data-path: in-band, unsolicited/passive, open encryption, any peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_UNSOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_PASSIVE,
        encr_type=self.ENCR_TYPE_OPEN,
        use_peer_id=False)

  @test_tracker_info(uuid="93b2a23d-8579-448a-936c-7812929464cf")
  def test_ib_unsolicited_passive_passphrase_specific(self):
    """Data-path: in-band, unsolicited/passive, passphrase, specific peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_UNSOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_PASSIVE,
        encr_type=self.ENCR_TYPE_PASSPHRASE,
        use_peer_id=True)

  @test_tracker_info(uuid="1736126f-a0ff-4712-acc4-f89b4eef5716")
  def test_ib_unsolicited_passive_passphrase_any(self):
    """Data-path: in-band, unsolicited/passive, passphrase, any peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_UNSOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_PASSIVE,
        encr_type=self.ENCR_TYPE_PASSPHRASE,
        use_peer_id=False)

  @test_tracker_info(uuid="b9353d5b-3f77-46bf-bfd9-65d56a7c939a")
  def test_ib_unsolicited_passive_pmk_specific(self):
    """Data-path: in-band, unsolicited/passive, PMK, specific peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_UNSOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_PASSIVE,
        encr_type=self.ENCR_TYPE_PMK,
        use_peer_id=True)

  @test_tracker_info(uuid="06f3b2ab-4a10-4398-83a4-6a23851b1662")
  def test_ib_unsolicited_passive_pmk_any(self):
    """Data-path: in-band, unsolicited/passive, PMK, any peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_UNSOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_PASSIVE,
        encr_type=self.ENCR_TYPE_PMK,
        use_peer_id=False)

  @test_tracker_info(uuid="0ed7d8b3-a69e-46ba-aeb7-13e507ecf290")
  def test_ib_solicited_active_open_specific(self):
    """Data-path: in-band, solicited/active, open encryption, specific peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_SOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_ACTIVE,
        encr_type=self.ENCR_TYPE_OPEN,
        use_peer_id=True)

  @test_tracker_info(uuid="c7ba6d28-5ef6-45d9-95d5-583ad6d981f3")
  def test_ib_solicited_active_open_any(self):
    """Data-path: in-band, solicited/active, open encryption, any peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_SOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_ACTIVE,
        encr_type=self.ENCR_TYPE_OPEN,
        use_peer_id=False)

  @test_tracker_info(uuid="388cea99-0e2e-49ea-b00e-f3e56b6236e5")
  def test_ib_solicited_active_passphrase_specific(self):
    """Data-path: in-band, solicited/active, passphrase, specific peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_SOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_ACTIVE,
        encr_type=self.ENCR_TYPE_PASSPHRASE,
        use_peer_id=True)

  @test_tracker_info(uuid="fcd3e28a-5eab-4169-8a0c-dc7204dcdc13")
  def test_ib_solicited_active_passphrase_any(self):
    """Data-path: in-band, solicited/active, passphrase, any peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_SOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_ACTIVE,
        encr_type=self.ENCR_TYPE_PASSPHRASE,
        use_peer_id=False)

  @test_tracker_info(uuid="9d4eaad7-ba53-4a06-8ce0-e308daea3309")
  def test_ib_solicited_active_pmk_specific(self):
    """Data-path: in-band, solicited/active, PMK, specific peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_SOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_ACTIVE,
        encr_type=self.ENCR_TYPE_PMK,
        use_peer_id=True)

  @test_tracker_info(uuid="129d850e-c312-4137-a67b-05ae95fe66cc")
  def test_ib_solicited_active_pmk_any(self):
    """Data-path: in-band, solicited/active, PMK, any peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_ib_data_path_test(
        ptype=aconsts.PUBLISH_TYPE_SOLICITED,
        stype=aconsts.SUBSCRIBE_TYPE_ACTIVE,
        encr_type=self.ENCR_TYPE_PMK,
        use_peer_id=False)

  #######################################
  # Positive Out-of-Band (OOB) tests key:
  #
  # names is: test_oob_<encr_type>_<peer_spec>
  # where:
  #
  # encr_type: Encription type: open, passphrase
  # peer_spec: Peer specification method: any or specific
  #
  # Note: Out-of-Band means using a non-Wi-Fi Aware mechanism for discovery and
  # exchange of MAC addresses and then Wi-Fi Aware for data-path.
  #######################################

  @test_tracker_info(uuid="7db17d8c-1dce-4084-b695-215bbcfe7d41")
  def test_oob_open_specific(self):
    """Data-path: out-of-band, open encryption, specific peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_oob_data_path_test(
        encr_type=self.ENCR_TYPE_OPEN,
        use_peer_id=True)

  @test_tracker_info(uuid="ad416d89-cb95-4a07-8d29-ee213117450b")
  def test_oob_open_any(self):
    """Data-path: out-of-band, open encryption, any peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_oob_data_path_test(
        encr_type=self.ENCR_TYPE_OPEN,
        use_peer_id=False)

  @test_tracker_info(uuid="74937a3a-d524-43e2-8979-4449271cab52")
  def test_oob_passphrase_specific(self):
    """Data-path: out-of-band, passphrase, specific peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_oob_data_path_test(
        encr_type=self.ENCR_TYPE_PASSPHRASE,
        use_peer_id=True)

  @test_tracker_info(uuid="afcbdc7e-d3a9-465b-b1da-ce2e42e3941e")
  def test_oob_passphrase_any(self):
    """Data-path: out-of-band, passphrase, any peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_oob_data_path_test(
        encr_type=self.ENCR_TYPE_PASSPHRASE,
        use_peer_id=False)

  @test_tracker_info(uuid="0d095031-160a-4537-aab5-41b6ad5d55f8")
  def test_oob_pmk_specific(self):
    """Data-path: out-of-band, PMK, specific peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_oob_data_path_test(
        encr_type=self.ENCR_TYPE_PMK,
        use_peer_id=True)

  @test_tracker_info(uuid="e45477bd-66cc-4eb7-88dd-4518c8aa2a74")
  def test_oob_pmk_any(self):
    """Data-path: out-of-band, PMK, any peer

    Verifies end-to-end discovery + data-path creation.
    """
    self.run_oob_data_path_test(
        encr_type=self.ENCR_TYPE_PMK,
        use_peer_id=False)

  ##############################################################

  @test_tracker_info(uuid="1c2c9805-dc1e-43b5-a1b8-315e8c9a4337")
  def test_passphrase_min(self):
    """Data-path: minimum passphrase length

    Use in-band, unsolicited/passive, any peer combination
    """
    self.run_ib_data_path_test(ptype=aconsts.PUBLISH_TYPE_UNSOLICITED,
                               stype=aconsts.SUBSCRIBE_TYPE_PASSIVE,
                               encr_type=self.ENCR_TYPE_PASSPHRASE,
                               use_peer_id=False,
                               passphrase_to_use=self.PASSPHRASE_MIN)

  @test_tracker_info(uuid="e696e2b9-87a9-4521-b337-61b9efaa2057")
  def test_passphrase_max(self):
    """Data-path: maximum passphrase length

    Use in-band, unsolicited/passive, any peer combination
    """
    self.run_ib_data_path_test(ptype=aconsts.PUBLISH_TYPE_UNSOLICITED,
                               stype=aconsts.SUBSCRIBE_TYPE_PASSIVE,
                               encr_type=self.ENCR_TYPE_PASSPHRASE,
                               use_peer_id=False,
                               passphrase_to_use=self.PASSPHRASE_MAX)

  @test_tracker_info(uuid="533cd44c-ff30-4283-ac28-f71fd7b4f02d")
  def test_negative_mismatch_publisher_peer_id(self):
    """Data-path: failure when publisher peer ID is mismatched"""
    self.run_mismatched_ib_data_path_test(pub_mismatch=True, sub_mismatch=False)

  @test_tracker_info(uuid="682f275e-722a-4f8b-85e7-0dcea9d25532")
  def test_negative_mismatch_subscriber_peer_id(self):
    """Data-path: failure when subscriber peer ID is mismatched"""
    self.run_mismatched_ib_data_path_test(pub_mismatch=False, sub_mismatch=True)

  @test_tracker_info(uuid="7fa82796-7fc9-4d9e-bbbb-84b751788943")
  def test_negative_mismatch_init_mac(self):
    """Data-path: failure when Initiator MAC address mismatch"""
    self.run_mismatched_oob_data_path_test(
        init_mismatch_mac=True,
        resp_mismatch_mac=False)

  @test_tracker_info(uuid="edeae959-4644-44f9-8d41-bdeb5216954e")
  def test_negative_mismatch_resp_mac(self):
    """Data-path: failure when Responder MAC address mismatch"""
    self.run_mismatched_oob_data_path_test(
        init_mismatch_mac=False,
        resp_mismatch_mac=True)

  @test_tracker_info(uuid="91f46949-c47f-49f9-a90f-6fae699613a7")
  def test_negative_mismatch_passphrase(self):
    """Data-path: failure when passphrases mismatch"""
    self.run_mismatched_oob_data_path_test(
        init_encr_type=self.ENCR_TYPE_PASSPHRASE,
        resp_encr_type=self.ENCR_TYPE_PASSPHRASE)

  @test_tracker_info(uuid="01c49c2e-dc92-4a27-bb47-c4fc67617c23")
  def test_negative_mismatch_pmk(self):
    """Data-path: failure when PMK mismatch"""
    self.run_mismatched_oob_data_path_test(
        init_encr_type=self.ENCR_TYPE_PMK,
        resp_encr_type=self.ENCR_TYPE_PMK)

  @test_tracker_info(uuid="4d651797-5fbb-408e-a4b6-a6e1944136da")
  def test_negative_mismatch_open_passphrase(self):
    """Data-path: failure when initiator is open, and responder passphrase"""
    self.run_mismatched_oob_data_path_test(
        init_encr_type=self.ENCR_TYPE_OPEN,
        resp_encr_type=self.ENCR_TYPE_PASSPHRASE)

  @test_tracker_info(uuid="1ae697f4-5987-4187-aeef-1e22d07d4a7c")
  def test_negative_mismatch_open_pmk(self):
    """Data-path: failure when initiator is open, and responder PMK"""
    self.run_mismatched_oob_data_path_test(
        init_encr_type=self.ENCR_TYPE_OPEN,
        resp_encr_type=self.ENCR_TYPE_PMK)

  @test_tracker_info(uuid="f027b1cc-0e7a-4075-b880-5e64b288afbd")
  def test_negative_mismatch_pmk_passphrase(self):
    """Data-path: failure when initiator is pmk, and responder passphrase"""
    self.run_mismatched_oob_data_path_test(
        init_encr_type=self.ENCR_TYPE_PMK,
        resp_encr_type=self.ENCR_TYPE_PASSPHRASE)

  @test_tracker_info(uuid="0819bbd4-72ae-49c4-bd46-5448db2b0a06")
  def test_negative_mismatch_passphrase_open(self):
    """Data-path: failure when initiator is passphrase, and responder open"""
    self.run_mismatched_oob_data_path_test(
        init_encr_type=self.ENCR_TYPE_PASSPHRASE,
        resp_encr_type=self.ENCR_TYPE_OPEN)

  @test_tracker_info(uuid="7ef24f62-8e6b-4732-88a3-80a43584dda4")
  def test_negative_mismatch_pmk_open(self):
    """Data-path: failure when initiator is PMK, and responder open"""
    self.run_mismatched_oob_data_path_test(
        init_encr_type=self.ENCR_TYPE_PMK,
        resp_encr_type=self.ENCR_TYPE_OPEN)

  @test_tracker_info(uuid="7b9c9efc-1c06-465e-8a5e-d6a22ac1da97")
  def test_negative_mismatch_passphrase_pmk(self):
    """Data-path: failure when initiator is passphrase, and responder pmk"""
    self.run_mismatched_oob_data_path_test(
        init_encr_type=self.ENCR_TYPE_PASSPHRASE,
        resp_encr_type=self.ENCR_TYPE_OPEN)


  ##########################################################################

  def wait_for_request_responses(self, dut, req_keys, aware_ifs):
    """Wait for network request confirmation for all request keys.

    Args:
      dut: Device under test
      req_keys: (in) A list of the network requests
      aware_ifs: (out) A list into which to append the network interface
    """
    num_events = 0
    while num_events != len(req_keys):
      event = autils.wait_for_event(dut, cconsts.EVENT_NETWORK_CALLBACK)
      if (event["data"][cconsts.NETWORK_CB_KEY_EVENT] ==
          cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED):
        if event["data"][cconsts.NETWORK_CB_KEY_ID] in req_keys:
          num_events = num_events + 1
          aware_ifs.append(event["data"][cconsts.NETWORK_CB_KEY_INTERFACE_NAME])
        else:
          self.log.info("Received an unexpected connectivity, the revoked "
                        "network request probably went through -- %s", event)

  @test_tracker_info(uuid="2e325e2b-d552-4890-b470-20b40284395d")
  def test_multiple_identical_networks(self):
    """Validate that creating multiple networks between 2 devices, each network
    with identical configuration is supported over a single NDP.

    Verify that the interface and IPv6 address is the same for all networks.
    """
    init_dut = self.android_devices[0]
    init_dut.pretty_name = "Initiator"
    resp_dut = self.android_devices[1]
    resp_dut.pretty_name = "Responder"

    N = 2 # first iteration (must be 2 to give us a chance to cancel the first)
    M = 5 # second iteration

    init_ids = []
    resp_ids = []

    # Initiator+Responder: attach and wait for confirmation & identity
    # create 10 sessions to be used in the different (but identical) NDPs
    for i in range(N + M):
      id, init_mac = autils.attach_with_identity(init_dut)
      init_ids.append(id)
      id, resp_mac = autils.attach_with_identity(resp_dut)
      resp_ids.append(id)

    # wait for for devices to synchronize with each other - there are no other
    # mechanisms to make sure this happens for OOB discovery (except retrying
    # to execute the data-path request)
    time.sleep(autils.WAIT_FOR_CLUSTER)

    resp_req_keys = []
    init_req_keys = []
    resp_aware_ifs = []
    init_aware_ifs = []

    # issue N quick requests for identical NDPs - without waiting for result
    # tests whether pre-setup multiple NDP procedure
    for i in range(N):
      # Responder: request network
      resp_req_keys.append(autils.request_network(
          resp_dut,
          resp_dut.droid.wifiAwareCreateNetworkSpecifierOob(
              resp_ids[i], aconsts.DATA_PATH_RESPONDER, init_mac, None)))

      # Initiator: request network
      init_req_keys.append(autils.request_network(
          init_dut,
          init_dut.droid.wifiAwareCreateNetworkSpecifierOob(
              init_ids[i], aconsts.DATA_PATH_INITIATOR, resp_mac, None)))

    # remove the first request (hopefully before completed) testing that NDP
    # is still created
    resp_dut.droid.connectivityUnregisterNetworkCallback(resp_req_keys[0])
    resp_req_keys.remove(resp_req_keys[0])
    init_dut.droid.connectivityUnregisterNetworkCallback(init_req_keys[0])
    init_req_keys.remove(init_req_keys[0])

    # wait for network formation for all initial requests
    self.wait_for_request_responses(resp_dut, resp_req_keys, resp_aware_ifs)
    self.wait_for_request_responses(init_dut, init_req_keys, init_aware_ifs)

    # issue N more requests for the same NDPs - tests post-setup multiple NDP
    for i in range(M):
      # Responder: request network
      resp_req_keys.append(autils.request_network(
          resp_dut,
          resp_dut.droid.wifiAwareCreateNetworkSpecifierOob(
              resp_ids[N + i], aconsts.DATA_PATH_RESPONDER, init_mac, None)))

      # Initiator: request network
      init_req_keys.append(autils.request_network(
          init_dut,
          init_dut.droid.wifiAwareCreateNetworkSpecifierOob(
              init_ids[N + i], aconsts.DATA_PATH_INITIATOR, resp_mac, None)))

    # wait for network formation for all subsequent requests
    self.wait_for_request_responses(resp_dut, resp_req_keys[N - 1:],
                                    resp_aware_ifs)
    self.wait_for_request_responses(init_dut, init_req_keys[N - 1:],
                                    init_aware_ifs)

    # determine whether all interfaces are identical (single NDP) - can't really
    # test the IPv6 address since it is not part of the callback event - it is
    # simply obtained from the system (so we'll always get the same for the same
    # interface)
    init_aware_ifs = list(set(init_aware_ifs))
    resp_aware_ifs = list(set(resp_aware_ifs))

    self.log.info("Interface names: I=%s, R=%s", init_aware_ifs, resp_aware_ifs)
    self.log.info("Initiator requests: %s", init_req_keys)
    self.log.info("Responder requests: %s", resp_req_keys)

    asserts.assert_equal(
        len(init_aware_ifs), 1, "Multiple initiator interfaces")
    asserts.assert_equal(
        len(resp_aware_ifs), 1, "Multiple responder interfaces")

    self.log.info("Interface IPv6 (using ifconfig): I=%s, R=%s",
                  autils.get_ipv6_addr(init_dut, init_aware_ifs[0]),
                  autils.get_ipv6_addr(resp_dut, resp_aware_ifs[0]))

    for i in range(init_dut.aware_capabilities[aconsts.CAP_MAX_NDI_INTERFACES]):
      if_name = "%s%d" % (aconsts.AWARE_NDI_PREFIX, i)
      init_ipv6 = autils.get_ipv6_addr(init_dut, if_name)
      resp_ipv6 = autils.get_ipv6_addr(resp_dut, if_name)

      asserts.assert_equal(
          init_ipv6 is None, if_name not in init_aware_ifs,
          "Initiator interface %s in unexpected state" % if_name)
      asserts.assert_equal(
          resp_ipv6 is None, if_name not in resp_aware_ifs,
          "Responder interface %s in unexpected state" % if_name)

    # release requests
    for resp_req_key in resp_req_keys:
      resp_dut.droid.connectivityUnregisterNetworkCallback(resp_req_key)
    for init_req_key in init_req_keys:
      init_dut.droid.connectivityUnregisterNetworkCallback(init_req_key)

  ########################################################################

  def run_multiple_ndi(self, sec_configs):
    """Validate that the device can create and use multiple NDIs.

    The security configuration can be:
    - None: open
    - String: passphrase
    - otherwise: PMK (byte array)

    Args:
      sec_configs: list of security configurations
    """
    init_dut = self.android_devices[0]
    init_dut.pretty_name = "Initiator"
    resp_dut = self.android_devices[1]
    resp_dut.pretty_name = "Responder"

    asserts.skip_if(init_dut.aware_capabilities[aconsts.CAP_MAX_NDI_INTERFACES]
                    < len(sec_configs) or
                    resp_dut.aware_capabilities[aconsts.CAP_MAX_NDI_INTERFACES]
                    < len(sec_configs),
                    "Initiator or Responder do not support multiple NDIs")

    init_id, init_mac = autils.attach_with_identity(init_dut)
    resp_id, resp_mac = autils.attach_with_identity(resp_dut)

    # wait for for devices to synchronize with each other - there are no other
    # mechanisms to make sure this happens for OOB discovery (except retrying
    # to execute the data-path request)
    time.sleep(autils.WAIT_FOR_CLUSTER)

    resp_req_keys = []
    init_req_keys = []
    resp_aware_ifs = []
    init_aware_ifs = []

    for sec in sec_configs:
      # Responder: request network
      resp_req_key = autils.request_network(resp_dut,
                                            autils.get_network_specifier(
                                                resp_dut, resp_id,
                                                aconsts.DATA_PATH_RESPONDER,
                                                init_mac, sec))
      resp_req_keys.append(resp_req_key)

      # Initiator: request network
      init_req_key = autils.request_network(init_dut,
                                            autils.get_network_specifier(
                                                init_dut, init_id,
                                                aconsts.DATA_PATH_INITIATOR,
                                                resp_mac, sec))
      init_req_keys.append(init_req_key)

      # Wait for network
      init_net_event = autils.wait_for_event_with_keys(
          init_dut, cconsts.EVENT_NETWORK_CALLBACK, autils.EVENT_TIMEOUT,
          (cconsts.NETWORK_CB_KEY_EVENT,
           cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED),
          (cconsts.NETWORK_CB_KEY_ID, init_req_key))
      resp_net_event = autils.wait_for_event_with_keys(
          resp_dut, cconsts.EVENT_NETWORK_CALLBACK, autils.EVENT_TIMEOUT,
          (cconsts.NETWORK_CB_KEY_EVENT,
           cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED),
          (cconsts.NETWORK_CB_KEY_ID, resp_req_key))

      resp_aware_ifs.append(
          resp_net_event["data"][cconsts.NETWORK_CB_KEY_INTERFACE_NAME])
      init_aware_ifs.append(
          init_net_event["data"][cconsts.NETWORK_CB_KEY_INTERFACE_NAME])

    # check that we are using 2 NDIs
    init_aware_ifs = list(set(init_aware_ifs))
    resp_aware_ifs = list(set(resp_aware_ifs))

    self.log.info("Interface names: I=%s, R=%s", init_aware_ifs, resp_aware_ifs)
    self.log.info("Initiator requests: %s", init_req_keys)
    self.log.info("Responder requests: %s", resp_req_keys)

    asserts.assert_equal(
        len(init_aware_ifs), len(sec_configs), "Multiple initiator interfaces")
    asserts.assert_equal(
        len(resp_aware_ifs), len(sec_configs), "Multiple responder interfaces")

    for i in range(len(sec_configs)):
      if_name = "%s%d" % (aconsts.AWARE_NDI_PREFIX, i)
      init_ipv6 = autils.get_ipv6_addr(init_dut, if_name)
      resp_ipv6 = autils.get_ipv6_addr(resp_dut, if_name)

      asserts.assert_equal(
          init_ipv6 is None, if_name not in init_aware_ifs,
          "Initiator interface %s in unexpected state" % if_name)
      asserts.assert_equal(
          resp_ipv6 is None, if_name not in resp_aware_ifs,
          "Responder interface %s in unexpected state" % if_name)

    # release requests
    for resp_req_key in resp_req_keys:
      resp_dut.droid.connectivityUnregisterNetworkCallback(resp_req_key)
    for init_req_key in init_req_keys:
      init_dut.droid.connectivityUnregisterNetworkCallback(init_req_key)

  @test_tracker_info(uuid="2d728163-11cc-46ba-a973-c8e1e71397fc")
  def test_multiple_ndi_open_passphrase(self):
    """Verify that can between 2 DUTs can create 2 NDPs with different security
    configuration (one open, one using passphrase). The result should use two
    different NDIs"""
    self.run_multiple_ndi([None, self.PASSPHRASE])

  @test_tracker_info(uuid="5f2c32aa-20b2-41f0-8b1e-d0b68df73ada")
  def test_multiple_ndi_open_pmk(self):
    """Verify that can between 2 DUTs can create 2 NDPs with different security
    configuration (one open, one using pmk). The result should use two
    different NDIs"""
    self.run_multiple_ndi([None, self.PMK])

  @test_tracker_info(uuid="34467659-bcfb-40cd-ba25-7e50560fca63")
  def test_multiple_ndi_passphrase_pmk(self):
    """Verify that can between 2 DUTs can create 2 NDPs with different security
    configuration (one using passphrase, one using pmk). The result should use
    two different NDIs"""
    self.run_multiple_ndi([self.PASSPHRASE, self.PMK])

  @test_tracker_info(uuid="d9194ce6-45b6-41b1-9cc8-ada79968966d")
  def test_multiple_ndi_passphrases(self):
    """Verify that can between 2 DUTs can create 2 NDPs with different security
    configuration (using different passphrases). The result should use two
    different NDIs"""
    self.run_multiple_ndi([self.PASSPHRASE, self.PASSPHRASE2])

  @test_tracker_info(uuid="879df795-62d2-40d4-a862-bd46d8f7e67f")
  def test_multiple_ndi_pmks(self):
    """Verify that can between 2 DUTs can create 2 NDPs with different security
    configuration (using different PMKS). The result should use two different
    NDIs"""
    self.run_multiple_ndi([self.PMK, self.PMK2])
