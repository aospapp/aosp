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

import queue
import time

from acts import asserts
from acts.test_utils.net import connectivity_const as cconsts
from acts.test_utils.wifi.aware import aware_const as aconsts
from acts.test_utils.wifi.aware import aware_test_utils as autils
from acts.test_utils.wifi.aware.AwareBaseTest import AwareBaseTest


class DataPathStressTest(AwareBaseTest):

  # Number of iterations on create/destroy Attach sessions.
  ATTACH_ITERATIONS = 2

  # Number of iterations on create/destroy NDP in each discovery session.
  NDP_ITERATIONS = 20

  def __init__(self, controllers):
    AwareBaseTest.__init__(self, controllers)

  ################################################################

  def test_oob_ndp_stress(self):
    """Run NDP (NAN data-path) stress test creating and destroying Aware
    attach sessions, discovery sessions, and NDPs."""
    init_dut = self.android_devices[0]
    init_dut.pretty_name = 'Initiator'
    resp_dut = self.android_devices[1]
    resp_dut.pretty_name = 'Responder'

    ndp_init_setup_success = 0
    ndp_init_setup_failures = 0
    ndp_resp_setup_success = 0
    ndp_resp_setup_failures = 0

    for attach_iter in range(self.ATTACH_ITERATIONS):
      init_id = init_dut.droid.wifiAwareAttach(True)
      autils.wait_for_event(init_dut, aconsts.EVENT_CB_ON_ATTACHED)
      init_ident_event = autils.wait_for_event(
          init_dut, aconsts.EVENT_CB_ON_IDENTITY_CHANGED)
      init_mac = init_ident_event['data']['mac']
      time.sleep(self.device_startup_offset)
      resp_id = resp_dut.droid.wifiAwareAttach(True)
      autils.wait_for_event(resp_dut, aconsts.EVENT_CB_ON_ATTACHED)
      resp_ident_event = autils.wait_for_event(
          resp_dut, aconsts.EVENT_CB_ON_IDENTITY_CHANGED)
      resp_mac = resp_ident_event['data']['mac']

      # wait for for devices to synchronize with each other - there are no other
      # mechanisms to make sure this happens for OOB discovery (except retrying
      # to execute the data-path request)
      time.sleep(autils.WAIT_FOR_CLUSTER)

      for ndp_iteration in range(self.NDP_ITERATIONS):
        # Responder: request network
        resp_req_key = autils.request_network(
            resp_dut,
            resp_dut.droid.wifiAwareCreateNetworkSpecifierOob(
                resp_id, aconsts.DATA_PATH_RESPONDER, init_mac, None))

        # Initiator: request network
        init_req_key = autils.request_network(
            init_dut,
            init_dut.droid.wifiAwareCreateNetworkSpecifierOob(
                init_id, aconsts.DATA_PATH_INITIATOR, resp_mac, None))

        # Initiator: wait for network formation
        got_on_available = False
        got_on_link_props = False
        while not got_on_available or not got_on_link_props:
          try:
            nc_event = init_dut.ed.pop_event(cconsts.EVENT_NETWORK_CALLBACK,
                                             autils.EVENT_NDP_TIMEOUT)
            if nc_event['data'][
                cconsts.NETWORK_CB_KEY_EVENT] == cconsts.NETWORK_CB_AVAILABLE:
              got_on_available = True
            elif (nc_event['data'][cconsts.NETWORK_CB_KEY_EVENT] ==
                  cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED):
              got_on_link_props = True
          except queue.Empty:
            ndp_init_setup_failures = ndp_init_setup_failures + 1
            init_dut.log.info('[Initiator] Timed out while waiting for '
                              'EVENT_NETWORK_CALLBACK')
            break

        if got_on_available and got_on_link_props:
          ndp_init_setup_success = ndp_init_setup_success + 1

        # Responder: wait for network formation
        got_on_available = False
        got_on_link_props = False
        while not got_on_available or not got_on_link_props:
          try:
            nc_event = resp_dut.ed.pop_event(cconsts.EVENT_NETWORK_CALLBACK,
                                             autils.EVENT_NDP_TIMEOUT)
            if nc_event['data'][
                cconsts.NETWORK_CB_KEY_EVENT] == cconsts.NETWORK_CB_AVAILABLE:
              got_on_available = True
            elif (nc_event['data'][cconsts.NETWORK_CB_KEY_EVENT] ==
                  cconsts.NETWORK_CB_LINK_PROPERTIES_CHANGED):
              got_on_link_props = True
          except queue.Empty:
            ndp_resp_setup_failures = ndp_resp_setup_failures + 1
            init_dut.log.info('[Responder] Timed out while waiting for '
                              'EVENT_NETWORK_CALLBACK')
            break

        if got_on_available and got_on_link_props:
          ndp_resp_setup_success = ndp_resp_setup_success + 1

        # clean-up
        init_dut.droid.connectivityUnregisterNetworkCallback(init_req_key)
        resp_dut.droid.connectivityUnregisterNetworkCallback(resp_req_key)

      # clean-up at end of iteration
      init_dut.droid.wifiAwareDestroy(init_id)
      resp_dut.droid.wifiAwareDestroy(resp_id)

    results = {}
    results['ndp_init_setup_success'] = ndp_init_setup_success
    results['ndp_init_setup_failures'] = ndp_init_setup_failures
    results['ndp_resp_setup_success'] = ndp_resp_setup_success
    results['ndp_resp_setup_failures'] = ndp_resp_setup_failures
    asserts.assert_equal(
        ndp_init_setup_failures + ndp_resp_setup_failures,
        0,
        'test_oob_ndp_stress finished',
        extras=results)
    asserts.explicit_pass("test_oob_ndp_stress done", extras=results)
