#!/usr/bin/env python3.4
#
#   Copyright 2017 - Google
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

import base64
import json
import queue
import re
import statistics
import time
from acts import asserts

from acts.test_utils.net import connectivity_const as cconsts
from acts.test_utils.wifi.aware import aware_const as aconsts

# arbitrary timeout for events
EVENT_TIMEOUT = 10

# semi-arbitrary timeout for network formation events. Based on framework
# timeout for NDP (NAN data-path) negotiation to be completed.
EVENT_NDP_TIMEOUT = 20

# number of second to 'reasonably' wait to make sure that devices synchronize
# with each other - useful for OOB test cases, where the OOB discovery would
# take some time
WAIT_FOR_CLUSTER = 5


def decorate_event(event_name, id):
  return '%s_%d' % (event_name, id)


def wait_for_event(ad, event_name, timeout=EVENT_TIMEOUT):
  """Wait for the specified event or timeout.

  Args:
    ad: The android device
    event_name: The event to wait on
    timeout: Number of seconds to wait
  Returns:
    The event (if available)
  """
  prefix = ''
  if hasattr(ad, 'pretty_name'):
    prefix = '[%s] ' % ad.pretty_name
  try:
    event = ad.ed.pop_event(event_name, timeout)
    ad.log.info('%s%s: %s', prefix, event_name, event['data'])
    return event
  except queue.Empty:
    ad.log.info('%sTimed out while waiting for %s', prefix, event_name)
    asserts.fail(event_name)

def wait_for_event_with_keys(ad, event_name, timeout=EVENT_TIMEOUT, *keyvalues):
  """Wait for the specified event contain the key/value pairs or timeout

  Args:
    ad: The android device
    event_name: The event to wait on
    timeout: Number of seconds to wait
    keyvalues: (kay, value) pairs
  Returns:
    The event (if available)
  """
  def filter_callbacks(event, keyvalues):
    for keyvalue in keyvalues:
      key, value = keyvalue
      if event['data'][key] != value:
        return False
    return True

  prefix = ''
  if hasattr(ad, 'pretty_name'):
    prefix = '[%s] ' % ad.pretty_name
  try:
    event = ad.ed.wait_for_event(event_name, filter_callbacks, timeout,
                                 keyvalues)
    ad.log.info('%s%s: %s', prefix, event_name, event['data'])
    return event
  except queue.Empty:
    ad.log.info('%sTimed out while waiting for %s (%s)', prefix, event_name,
                keyvalues)
    asserts.fail(event_name)

def fail_on_event(ad, event_name, timeout=EVENT_TIMEOUT):
  """Wait for a timeout period and looks for the specified event - fails if it
  is observed.

  Args:
    ad: The android device
    event_name: The event to wait for (and fail on its appearance)
  """
  prefix = ''
  if hasattr(ad, 'pretty_name'):
    prefix = '[%s] ' % ad.pretty_name
  try:
    event = ad.ed.pop_event(event_name, timeout)
    ad.log.info('%sReceived unwanted %s: %s', prefix, event_name, event['data'])
    asserts.fail(event_name, extras=event)
  except queue.Empty:
    ad.log.info('%s%s not seen (as expected)', prefix, event_name)
    return

def fail_on_event_with_keys(ad, event_name, timeout=EVENT_TIMEOUT, *keyvalues):
  """Wait for a timeout period and looks for the specified event which contains
  the key/value pairs - fails if it is observed.

  Args:
    ad: The android device
    event_name: The event to wait on
    timeout: Number of seconds to wait
    keyvalues: (kay, value) pairs
  """
  def filter_callbacks(event, keyvalues):
    for keyvalue in keyvalues:
      key, value = keyvalue
      if event['data'][key] != value:
        return False
    return True

  prefix = ''
  if hasattr(ad, 'pretty_name'):
    prefix = '[%s] ' % ad.pretty_name
  try:
    event = ad.ed.wait_for_event(event_name, filter_callbacks, timeout,
                                 keyvalues)
    ad.log.info('%sReceived unwanted %s: %s', prefix, event_name, event['data'])
    asserts.fail(event_name, extras=event)
  except queue.Empty:
    ad.log.info('%s%s (%s) not seen (as expected)', prefix, event_name,
                keyvalues)
    return

def verify_no_more_events(ad, timeout=EVENT_TIMEOUT):
  """Verify that there are no more events in the queue.
  """
  prefix = ''
  if hasattr(ad, 'pretty_name'):
    prefix = '[%s] ' % ad.pretty_name
  should_fail = False
  try:
    while True:
      event = ad.ed.pop_events('.*', timeout, freq=0)
      ad.log.info('%sQueue contains %s', prefix, event)
      should_fail = True
  except queue.Empty:
    if should_fail:
      asserts.fail('%sEvent queue not empty' % prefix)
    ad.log.info('%sNo events in the queue (as expected)', prefix)
    return


def encode_list(list_of_objects):
  """Converts the list of strings or bytearrays to a list of b64 encoded
  bytearrays.

  A None object is treated as a zero-length bytearray.

  Args:
    list_of_objects: A list of strings or bytearray objects
  Returns: A list of the same objects, converted to bytes and b64 encoded.
  """
  encoded_list = []
  for obj in list_of_objects:
    if obj is None:
      obj = bytes()
    if isinstance(obj, str):
      encoded_list.append(base64.b64encode(bytes(obj, 'utf-8')).decode('utf-8'))
    else:
      encoded_list.append(base64.b64encode(obj).decode('utf-8'))
  return encoded_list


def decode_list(list_of_b64_strings):
  """Converts the list of b64 encoded strings to a list of bytearray.

  Args:
    list_of_b64_strings: list of strings, each of which is b64 encoded array
  Returns: a list of bytearrays.
  """
  decoded_list = []
  for str in list_of_b64_strings:
    decoded_list.append(base64.b64decode(str))
  return decoded_list


def construct_max_match_filter(max_size):
  """Constructs a maximum size match filter that fits into the 'max_size' bytes.

  Match filters are a set of LVs (Length, Value pairs) where L is 1 byte. The
  maximum size match filter will contain max_size/2 LVs with all Vs (except
  possibly the last one) of 1 byte, the last V may be 2 bytes for odd max_size.

  Args:
    max_size: Maximum size of the match filter.
  Returns: an array of bytearrays.
  """
  mf_list = []
  num_lvs = max_size // 2
  for i in range(num_lvs - 1):
    mf_list.append(bytes([i]))
  if (max_size % 2 == 0):
    mf_list.append(bytes([255]))
  else:
    mf_list.append(bytes([254, 255]))
  return mf_list


def assert_equal_strings(first, second, msg=None, extras=None):
  """Assert equality of the string operands - where None is treated as equal to
  an empty string (''), otherwise fail the test.

  Error message is "first != second" by default. Additional explanation can
  be supplied in the message.

  Args:
      first, seconds: The strings that are evaluated for equality.
      msg: A string that adds additional info about the failure.
      extras: An optional field for extra information to be included in
              test result.
  """
  if first == None:
    first = ''
  if second == None:
    second = ''
  asserts.assert_equal(first, second, msg, extras)


def get_aware_capabilities(ad):
  """Get the Wi-Fi Aware capabilities from the specified device. The
  capabilities are a dictionary keyed by aware_const.CAP_* keys.

  Args:
    ad: the Android device
  Returns: the capability dictionary.
  """
  return json.loads(ad.adb.shell('cmd wifiaware state_mgr get_capabilities'))

def get_wifi_mac_address(ad):
  """Get the Wi-Fi interface MAC address as a upper-case string of hex digits
  without any separators (e.g. ':').

  Args:
    ad: Device on which to run.
  """
  return ad.droid.wifiGetConnectionInfo()['mac_address'].upper().replace(
      ':', '')

def validate_forbidden_callbacks(ad, limited_cb=None):
  """Validate that the specified callbacks have not been called more then permitted.

  In addition to the input configuration also validates that forbidden callbacks
  have never been called.

  Args:
    ad: Device on which to run.
    limited_cb: Dictionary of CB_EV_* ids and maximum permitted calls (0
                meaning never).
  """
  cb_data = json.loads(ad.adb.shell('cmd wifiaware native_cb get_cb_count'))

  if limited_cb is None:
    limited_cb = {}
  # add callbacks which should never be called
  limited_cb[aconsts.CB_EV_MATCH_EXPIRED] = 0

  fail = False
  for cb_event in limited_cb.keys():
    if cb_event in cb_data:
      if cb_data[cb_event] > limited_cb[cb_event]:
        fail = True
        ad.log.info(
            'Callback %s observed %d times: more then permitted %d times',
            cb_event, cb_data[cb_event], limited_cb[cb_event])

  asserts.assert_false(fail, 'Forbidden callbacks observed', extras=cb_data)

def extract_stats(ad, data, results, key_prefix, log_prefix):
  """Extract statistics from the data, store in the results dictionary, and
  output to the info log.

  Args:
    ad: Android device (for logging)
    data: A list containing the data to be analyzed.
    results: A dictionary into which to place the statistics.
    key_prefix: A string prefix to use for the dict keys storing the
                extracted stats.
    log_prefix: A string prefix to use for the info log.
    include_data: If True includes the raw data in the dictionary,
                  otherwise just the stats.
  """
  num_samples = len(data)
  results['%snum_samples' % key_prefix] = num_samples

  if not data:
    return

  data_min = min(data)
  data_max = max(data)
  data_mean = statistics.mean(data)

  results['%smin' % key_prefix] = data_min
  results['%smax' % key_prefix] = data_max
  results['%smean' % key_prefix] = data_mean
  results['%sraw_data' % key_prefix] = data

  if num_samples > 1:
    data_stdev = statistics.stdev(data)
    results['%sstdev' % key_prefix] = data_stdev
    ad.log.info('%s: num_samples=%d, min=%.2f, max=%.2f, mean=%.2f, stdev=%.2f',
                log_prefix, num_samples, data_min, data_max, data_mean,
                data_stdev)
  else:
    ad.log.info('%s: num_samples=%d, min=%.2f, max=%.2f, mean=%.2f', log_prefix,
                num_samples, data_min, data_max, data_mean)

def get_mac_addr(device, interface):
  """Get the MAC address of the specified interface. Uses ifconfig and parses
  its output. Normalizes string to remove ':' and upper case.

  Args:
    device: Device on which to query the interface MAC address.
    interface: Name of the interface for which to obtain the MAC address.
  """
  out = device.adb.shell("ifconfig %s" % interface)
  res = re.match(".* HWaddr (\S+).*", out , re.S)
  asserts.assert_true(
      res,
      'Unable to obtain MAC address for interface %s' % interface,
      extras=out)
  return res.group(1).upper().replace(':', '')

def get_ipv6_addr(device, interface):
  """Get the IPv6 address of the specified interface. Uses ifconfig and parses
  its output. Returns a None if the interface does not have an IPv6 address
  (indicating it is not UP).

  Args:
    device: Device on which to query the interface IPv6 address.
    interface: Name of the interface for which to obtain the IPv6 address.
  """
  out = device.adb.shell("ifconfig %s" % interface)
  res = re.match(".*inet6 addr: (\S+)/.*", out , re.S)
  if not res:
    return None
  return res.group(1)

#########################################################
# Aware primitives
#########################################################

def request_network(dut, ns):
  """Request a Wi-Fi Aware network.

  Args:
    dut: Device
    ns: Network specifier
  Returns: the request key
  """
  network_req = {"TransportType": 5, "NetworkSpecifier": ns}
  return dut.droid.connectivityRequestWifiAwareNetwork(network_req)

def get_network_specifier(dut, id, dev_type, peer_mac, sec):
  """Create a network specifier for the device based on the security
  configuration.

  Args:
    dut: device
    id: session ID
    dev_type: device type - Initiator or Responder
    peer_mac: the discovery MAC address of the peer
    sec: security configuration
  """
  if sec is None:
    return dut.droid.wifiAwareCreateNetworkSpecifierOob(
        id, dev_type, peer_mac)
  if isinstance(sec, str):
    return dut.droid.wifiAwareCreateNetworkSpecifierOob(
        id, dev_type, peer_mac, sec)
  return dut.droid.wifiAwareCreateNetworkSpecifierOob(
      id, dev_type, peer_mac, None, sec)

def configure_dw(device, is_default, is_24_band, value):
  """Use the command-line API to configure the DW (discovery window) setting

  Args:
    device: Device on which to perform configuration
    is_default: True for the default setting, False for the non-interactive
                setting
    is_24_band: True for 2.4GHz band, False for 5GHz band
    value: An integer 0 to 5
  """
  variable = 'dw_%s_%sghz' % ('default' if is_default else 'on_inactive', '24'
                              if is_24_band else '5')
  device.adb.shell("cmd wifiaware native_api set %s %d" % (variable, value))

def config_dw_high_power(device):
  """Configure device's discovery window (DW) values to high power mode -
  whether device is in interactive or non-interactive modes"""
  configure_dw(
      device, is_default=True, is_24_band=True, value=aconsts.DW_24_INTERACTIVE)
  configure_dw(
      device, is_default=True, is_24_band=False, value=aconsts.DW_5_INTERACTIVE)
  configure_dw(
      device,
      is_default=False,
      is_24_band=True,
      value=aconsts.DW_24_INTERACTIVE)
  configure_dw(
      device,
      is_default=False,
      is_24_band=False,
      value=aconsts.DW_5_INTERACTIVE)

def config_dw_low_power(device):
  """Configure device's discovery window (DW) values to low power mode - whether
  device is in interactive or non-interactive modes"""
  configure_dw(
      device,
      is_default=True,
      is_24_band=True,
      value=aconsts.DW_24_NON_INTERACTIVE)
  configure_dw(
      device,
      is_default=True,
      is_24_band=False,
      value=aconsts.DW_5_NON_INTERACTIVE)
  configure_dw(
      device,
      is_default=False,
      is_24_band=True,
      value=aconsts.DW_24_NON_INTERACTIVE)
  configure_dw(
      device,
      is_default=False,
      is_24_band=False,
      value=aconsts.DW_5_NON_INTERACTIVE)

def config_dw_all_modes(device, dw_24ghz, dw_5ghz):
  """Configure device's discovery window (DW) values to the specified values -
  whether the device is in interactive or non-interactive mode.

  Args:
    dw_24ghz: DW interval in the 2.4GHz band.
    dw_5ghz: DW interval in the 5GHz band.
  """
  configure_dw(device, is_default=True, is_24_band=True, value=dw_24ghz)
  configure_dw(device, is_default=True, is_24_band=False, value=dw_5ghz)
  configure_dw(device, is_default=False, is_24_band=True, value=dw_24ghz)
  configure_dw(device, is_default=False, is_24_band=False, value=dw_5ghz)

def create_discovery_config(service_name,
                          d_type,
                          ssi=None,
                          match_filter=None,
                          match_filter_list=None,
                          ttl=0,
                          term_cb_enable=True):
  """Create a publish discovery configuration based on input parameters.

  Args:
    service_name: Service name - required
    d_type: Discovery type (publish or subscribe constants)
    ssi: Supplemental information - defaults to None
    match_filter, match_filter_list: The match_filter, only one mechanism can
                                     be used to specify. Defaults to None.
    ttl: Time-to-live - defaults to 0 (i.e. non-self terminating)
    term_cb_enable: True (default) to enable callback on termination, False
                    means that no callback is called when session terminates.
  Returns:
    publish discovery configuration object.
  """
  config = {}
  config[aconsts.DISCOVERY_KEY_SERVICE_NAME] = service_name
  config[aconsts.DISCOVERY_KEY_DISCOVERY_TYPE] = d_type
  if ssi is not None:
    config[aconsts.DISCOVERY_KEY_SSI] = ssi
  if match_filter is not None:
    config[aconsts.DISCOVERY_KEY_MATCH_FILTER] = match_filter
  if match_filter_list is not None:
    config[aconsts.DISCOVERY_KEY_MATCH_FILTER_LIST] = match_filter_list
  config[aconsts.DISCOVERY_KEY_TTL] = ttl
  config[aconsts.DISCOVERY_KEY_TERM_CB_ENABLED] = term_cb_enable
  return config

def attach_with_identity(dut):
  """Start an Aware session (attach) and wait for confirmation and identity
  information (mac address).

  Args:
    dut: Device under test
  Returns:
    id: Aware session ID.
    mac: Discovery MAC address of this device.
  """
  id = dut.droid.wifiAwareAttach(True)
  wait_for_event(dut, aconsts.EVENT_CB_ON_ATTACHED)
  event = wait_for_event(dut, aconsts.EVENT_CB_ON_IDENTITY_CHANGED)
  mac = event["data"]["mac"]

  return id, mac

def create_discovery_pair(p_dut,
                          s_dut,
                          p_config,
                          s_config,
                          device_startup_offset,
                          msg_id=None):
  """Creates a discovery session (publish and subscribe), and waits for
  service discovery - at that point the sessions are connected and ready for
  further messaging of data-path setup.

  Args:
    p_dut: Device to use as publisher.
    s_dut: Device to use as subscriber.
    p_config: Publish configuration.
    s_config: Subscribe configuration.
    device_startup_offset: Number of seconds to offset the enabling of NAN on
                           the two devices.
    msg_id: Controls whether a message is sent from Subscriber to Publisher
            (so that publisher has the sub's peer ID). If None then not sent,
            otherwise should be an int for the message id.
  Returns: variable size list of:
    p_id: Publisher attach session id
    s_id: Subscriber attach session id
    p_disc_id: Publisher discovery session id
    s_disc_id: Subscriber discovery session id
    peer_id_on_sub: Peer ID of the Publisher as seen on the Subscriber
    peer_id_on_pub: Peer ID of the Subscriber as seen on the Publisher. Only
                    included if |msg_id| is not None.
  """
  p_dut.pretty_name = 'Publisher'
  s_dut.pretty_name = 'Subscriber'

  # Publisher+Subscriber: attach and wait for confirmation
  p_id = p_dut.droid.wifiAwareAttach()
  wait_for_event(p_dut, aconsts.EVENT_CB_ON_ATTACHED)
  time.sleep(device_startup_offset)
  s_id = s_dut.droid.wifiAwareAttach()
  wait_for_event(s_dut, aconsts.EVENT_CB_ON_ATTACHED)

  # Publisher: start publish and wait for confirmation
  p_disc_id = p_dut.droid.wifiAwarePublish(p_id, p_config)
  wait_for_event(p_dut, aconsts.SESSION_CB_ON_PUBLISH_STARTED)

  # Subscriber: start subscribe and wait for confirmation
  s_disc_id = s_dut.droid.wifiAwareSubscribe(s_id, s_config)
  wait_for_event(s_dut, aconsts.SESSION_CB_ON_SUBSCRIBE_STARTED)

  # Subscriber: wait for service discovery
  discovery_event = wait_for_event(s_dut,
                                   aconsts.SESSION_CB_ON_SERVICE_DISCOVERED)
  peer_id_on_sub = discovery_event['data'][aconsts.SESSION_CB_KEY_PEER_ID]

  # Optionally send a message from Subscriber to Publisher
  if msg_id is not None:
    ping_msg = 'PING'

    # Subscriber: send message to peer (Publisher)
    s_dut.droid.wifiAwareSendMessage(s_disc_id, peer_id_on_sub, msg_id,
                                     ping_msg, aconsts.MAX_TX_RETRIES)
    sub_tx_msg_event = wait_for_event(s_dut, aconsts.SESSION_CB_ON_MESSAGE_SENT)
    asserts.assert_equal(
        msg_id, sub_tx_msg_event['data'][aconsts.SESSION_CB_KEY_MESSAGE_ID],
        'Subscriber -> Publisher message ID corrupted')

    # Publisher: wait for received message
    pub_rx_msg_event = wait_for_event(p_dut,
                                      aconsts.SESSION_CB_ON_MESSAGE_RECEIVED)
    peer_id_on_pub = pub_rx_msg_event['data'][aconsts.SESSION_CB_KEY_PEER_ID]
    asserts.assert_equal(
        ping_msg,
        pub_rx_msg_event['data'][aconsts.SESSION_CB_KEY_MESSAGE_AS_STRING],
        'Subscriber -> Publisher message corrupted')
    return p_id, s_id, p_disc_id, s_disc_id, peer_id_on_sub, peer_id_on_pub

  return p_id, s_id, p_disc_id, s_disc_id, peer_id_on_sub

def create_ib_ndp(p_dut, s_dut, p_config, s_config, device_startup_offset):
  """Create an NDP (using in-band discovery)

  Args:
    p_dut: Device to use as publisher.
    s_dut: Device to use as subscriber.
    p_config: Publish configuration.
    s_config: Subscribe configuration.
    device_startup_offset: Number of seconds to offset the enabling of NAN on
                           the two devices.
  """
  (p_id, s_id, p_disc_id, s_disc_id, peer_id_on_sub,
   peer_id_on_pub) = create_discovery_pair(
       p_dut, s_dut, p_config, s_config, device_startup_offset, msg_id=9999)

  # Publisher: request network
  p_req_key = request_network(p_dut,
                              p_dut.droid.wifiAwareCreateNetworkSpecifier(
                                  p_disc_id, peer_id_on_pub, None))

  # Subscriber: request network
  s_req_key = request_network(s_dut,
                              s_dut.droid.wifiAwareCreateNetworkSpecifier(
                                  s_disc_id, peer_id_on_sub, None))

  # Publisher & Subscriber: wait for network formation
  p_net_event = wait_for_event_with_keys(
      p_dut, cconsts.EVENT_NETWORK_CALLBACK, EVENT_TIMEOUT,
      (cconsts.NETWORK_CB_KEY_EVENT,
       cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED), (cconsts.NETWORK_CB_KEY_ID,
                                                     p_req_key))
  s_net_event = wait_for_event_with_keys(
      s_dut, cconsts.EVENT_NETWORK_CALLBACK, EVENT_TIMEOUT,
      (cconsts.NETWORK_CB_KEY_EVENT,
       cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED), (cconsts.NETWORK_CB_KEY_ID,
                                                     s_req_key))

  p_aware_if = p_net_event["data"][cconsts.NETWORK_CB_KEY_INTERFACE_NAME]
  s_aware_if = s_net_event["data"][cconsts.NETWORK_CB_KEY_INTERFACE_NAME]

  p_ipv6 = p_dut.droid.connectivityGetLinkLocalIpv6Address(p_aware_if).split(
      "%")[0]
  s_ipv6 = s_dut.droid.connectivityGetLinkLocalIpv6Address(s_aware_if).split(
      "%")[0]

  return p_req_key, s_req_key, p_aware_if, s_aware_if, p_ipv6, s_ipv6

def create_oob_ndp_on_sessions(init_dut, resp_dut, init_id, init_mac, resp_id,
                               resp_mac):
  """Create an NDP on top of existing Aware sessions (using OOB discovery)

  Args:
    init_dut: Initiator device
    resp_dut: Responder device
    init_id: Initiator attach session id
    init_mac: Initiator discovery MAC address
    resp_id: Responder attach session id
    resp_mac: Responder discovery MAC address
  Returns:
    init_req_key: Initiator network request
    resp_req_key: Responder network request
    init_aware_if: Initiator Aware data interface
    resp_aware_if: Responder Aware data interface
    init_ipv6: Initiator IPv6 address
    resp_ipv6: Responder IPv6 address
  """
  # Responder: request network
  resp_req_key = request_network(
      resp_dut,
      resp_dut.droid.wifiAwareCreateNetworkSpecifierOob(
          resp_id, aconsts.DATA_PATH_RESPONDER, init_mac, None))

  # Initiator: request network
  init_req_key = request_network(
      init_dut,
      init_dut.droid.wifiAwareCreateNetworkSpecifierOob(
          init_id, aconsts.DATA_PATH_INITIATOR, resp_mac, None))

  # Initiator & Responder: wait for network formation
  init_net_event = wait_for_event_with_keys(
      init_dut, cconsts.EVENT_NETWORK_CALLBACK, EVENT_TIMEOUT,
      (cconsts.NETWORK_CB_KEY_EVENT,
       cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED), (cconsts.NETWORK_CB_KEY_ID,
                                                     init_req_key))
  resp_net_event = wait_for_event_with_keys(
      resp_dut, cconsts.EVENT_NETWORK_CALLBACK, EVENT_TIMEOUT,
      (cconsts.NETWORK_CB_KEY_EVENT,
       cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED), (cconsts.NETWORK_CB_KEY_ID,
                                                     resp_req_key))

  init_aware_if = init_net_event['data'][cconsts.NETWORK_CB_KEY_INTERFACE_NAME]
  resp_aware_if = resp_net_event['data'][cconsts.NETWORK_CB_KEY_INTERFACE_NAME]

  init_ipv6 = init_dut.droid.connectivityGetLinkLocalIpv6Address(
      init_aware_if).split('%')[0]
  resp_ipv6 = resp_dut.droid.connectivityGetLinkLocalIpv6Address(
      resp_aware_if).split('%')[0]

  return (init_req_key, resp_req_key, init_aware_if, resp_aware_if, init_ipv6,
          resp_ipv6)

def create_oob_ndp(init_dut, resp_dut):
  """Create an NDP (using OOB discovery)

  Args:
    init_dut: Initiator device
    resp_dut: Responder device
  """
  init_dut.pretty_name = 'Initiator'
  resp_dut.pretty_name = 'Responder'

  # Initiator+Responder: attach and wait for confirmation & identity
  init_id = init_dut.droid.wifiAwareAttach(True)
  wait_for_event(init_dut, aconsts.EVENT_CB_ON_ATTACHED)
  init_ident_event = wait_for_event(init_dut,
                                    aconsts.EVENT_CB_ON_IDENTITY_CHANGED)
  init_mac = init_ident_event['data']['mac']
  resp_id = resp_dut.droid.wifiAwareAttach(True)
  wait_for_event(resp_dut, aconsts.EVENT_CB_ON_ATTACHED)
  resp_ident_event = wait_for_event(resp_dut,
                                    aconsts.EVENT_CB_ON_IDENTITY_CHANGED)
  resp_mac = resp_ident_event['data']['mac']

  # wait for for devices to synchronize with each other - there are no other
  # mechanisms to make sure this happens for OOB discovery (except retrying
  # to execute the data-path request)
  time.sleep(WAIT_FOR_CLUSTER)

  (init_req_key, resp_req_key, init_aware_if,
   resp_aware_if, init_ipv6, resp_ipv6) = create_oob_ndp_on_sessions(
       init_dut, resp_dut, init_id, init_mac, resp_id, resp_mac)

  return (init_req_key, resp_req_key, init_aware_if, resp_aware_if, init_ipv6,
          resp_ipv6)
