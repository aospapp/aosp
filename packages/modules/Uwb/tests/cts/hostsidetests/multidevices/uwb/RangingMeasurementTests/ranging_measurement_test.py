"""Tests for Ranging Measurement."""

import logging
import statistics
import time
from typing import List

from mobly import asserts
from mobly import config_parser
from mobly import test_runner

from lib import uwb_base_test
from lib import uwb_ranging_decorator
from lib import uwb_ranging_params
from test_utils import uwb_test_utils

MEASUREMENT_WAIT_TIME= 30
MAX_VARIANCE = 5
MEASUREMENTS_COUNT = 1000
MEASUREMENTS_SPREAD_LOWER_BOUND_INDEX = 25
MEASUREMENTS_SPREAD_UPPER_BOUND_INDEX = 975
DISTANCE_MEASUREMENTS_MAX_SPREAD_CM = 30
DISTANCE_MEASUREMENTS_MEDIAN_LOWER_BOUND_CM = 75
DISTANCE_MEASUREMENTS_MEDIAN_UPPER_BOUND_CM = 125


_TEST_CASES = {
    "test_rssi_measurement_device_tracker_profile",
    "test_rssi_measurement_nearby_share_profile",
    "test_distance_measurement_accuracy",
}

class RangingMeasurementTest(uwb_base_test.UwbBaseTest):
  """Measurement tests for Ranging APIs.

  Attributes:
    android_devices: list of android device objects.
  """

  def __init__(self, configs: config_parser.TestRunConfig):
      """Init method for the test class.

      Args:
        configs: A config_parser.TestRunConfig object.
      """
      super().__init__(configs)
      self.tests = _TEST_CASES

  def setup_class(self):
    super().setup_class()
    self.uwb_devices = [
        uwb_ranging_decorator.UwbRangingDecorator(ad)
        for ad in self.android_devices
    ]
    self.initiator, self.responder = self.uwb_devices
    self.device_addresses = self.user_params.get("device_addresses",
                                                 [[1, 2], [3, 4]])
    self.initiator_addr, self.responder_addr = self.device_addresses

  def teardown_test(self):
    super().teardown_test()
    for uwb_dut in self.uwb_devices:
      uwb_dut.clear_ranging_session_callback_events()
    self.responder.stop_ranging()
    self.initiator.stop_ranging()
    self.responder.close_ranging()
    self.initiator.close_ranging()

  ### Helper Methods ###

  def _get_rssi(self, initiator: uwb_ranging_decorator.UwbRangingDecorator,
                responder: uwb_ranging_decorator.UwbRangingDecorator,
                initiator_addr: List[int], peer_addr: List[int]) -> int:
    """Returns median RSSI measurements and checks they are similar.

    Args:
      initiator: uwb device object.
      responder: uwb device object.
      initiator_addr: address of initiator.
      peer_addr: address of uwb device.
    Returns:
      rssi_median_diff: difference of median RSSI measurements.
    """
    initiator_rssi_measurements = []
    responder_rssi_measurements = []
    # get rssi measurements for each device for 30 seconds
    start_time = time.time()
    while time.time() - start_time < MEASUREMENT_WAIT_TIME:
      try:
        initiator.verify_callback_received("ReportReceived")
        rssi = initiator.get_rssi_measurement(peer_addr)
        initiator_rssi_measurements.append(rssi)
        rssi = responder.get_rssi_measurement(initiator_addr)
        responder_rssi_measurements.append(rssi)
      except ValueError:
        logging.warning("Failed to get RSSI measurement.")

    # verify rssi measurements != 0 on initiator and responder
    asserts.assert_true(
        initiator_rssi_measurements != 0,
        "Failed to get valid RSSI measurement.",
    )
    asserts.assert_true(
        responder_rssi_measurements != 0,
        "Failed to get valid RSSI measurement.",
    )

    # get median for each device.
    initiator_median_rssi = statistics.median(initiator_rssi_measurements)
    responder_median_rssi = statistics.median(responder_rssi_measurements)
    logging.info("Median Initiator RSSI: %sdB", initiator_median_rssi)
    logging.info("Median Responder RSSI: %sdB", responder_median_rssi)

    # check diff between median distances
    rssi_median_diff = abs(initiator_median_rssi - responder_median_rssi)
    logging.info("RSSI difference between peers: %sdB", rssi_median_diff)
    asserts.assert_true(
        rssi_median_diff <= MAX_VARIANCE,
        (
            f"RSSI measurement is off by {rssi_median_diff}dB. Variance max is"
            f" {MAX_VARIANCE}dB."
        ),
    )
    return rssi_median_diff

  def _verify_peer_found(
      self, initiator: uwb_ranging_decorator.UwbRangingDecorator,
      responder: uwb_ranging_decorator.UwbRangingDecorator,
      initiator_params: uwb_ranging_params.UwbRangingParams,
      responder_params: uwb_ranging_params.UwbRangingParams,
      initiator_addr: List[int], peer_addr: List[int]):
    """Verifies peer is found on both devices first.

    Args:
      initiator: uwb device object.
      responder: uwb device object.
      initiator_params: ranging params for initiator.
      responder_params: ranging params for responder.
      initiator_addr: address of initiator.
      peer_addr: address of uwb device.
    """
    # open and start ranging on initiator and responder.
    initiator.open_fira_ranging(initiator_params)
    responder.open_fira_ranging(responder_params)
    initiator.start_fira_ranging()
    responder.start_fira_ranging()

    # verify peer found on initiator and responder.
    uwb_test_utils.verify_peer_found(initiator, peer_addr)
    uwb_test_utils.verify_peer_found(responder, initiator_addr)
    self._get_rssi(initiator, responder, initiator_addr, peer_addr)

  def _check_distance_accuracy(self, initiator: uwb_ranging_decorator.UwbRangingDecorator,
                               responder_addr: List[int]):
      """Verifies the distance measurement is within the required ranges.

      Args:
        initiator: uwb device object.
        responder_addr: address of uwb device.
      """
      distance_measurements = []
      valid_measurement_count = 0
      while valid_measurement_count < MEASUREMENTS_COUNT:
        try:
          initiator.verify_callback_received("ReportReceived")
          distance = initiator.get_distance_measurement(responder_addr)
          distance_measurements.append(distance * 100)
          valid_measurement_count += 1
        except ValueError:
          logging.warning("Failed to get distance measurement.")
      distance_measurements.sort()
      median_distance_cm = statistics.median(distance_measurements)
      logging.info("Median distance between peers: %s cm", median_distance_cm)
      distance_spread_lower_bound_cm = distance_measurements[
          MEASUREMENTS_SPREAD_LOWER_BOUND_INDEX - 1]
      distance_spread_upper_bound_cm = distance_measurements[
          MEASUREMENTS_SPREAD_UPPER_BOUND_INDEX - 1]
      distance_spread_cm = distance_spread_upper_bound_cm - distance_spread_lower_bound_cm
      logging.info("Distance spread of the required boundaries : %s cm", distance_spread_cm)
      asserts.assert_true(
          distance_spread_cm < DISTANCE_MEASUREMENTS_MAX_SPREAD_CM,
          "Distance spread %s cm is greater than the required limit of %s cm, "
          "check tests/cts/hostsidetests/multidevices/uwb/README.md for calibration requirements" %
          (distance_spread_cm, DISTANCE_MEASUREMENTS_MAX_SPREAD_CM))
      asserts.assert_true(
          DISTANCE_MEASUREMENTS_MEDIAN_LOWER_BOUND_CM < median_distance_cm <
          DISTANCE_MEASUREMENTS_MEDIAN_UPPER_BOUND_CM,
          "Median distance %s cm is out of the required range of [%s cm, %s cm], "
          "check tests/cts/hostsidetests/multidevices/uwb/README.md for calibration requirements" %
          (median_distance_cm, DISTANCE_MEASUREMENTS_MEDIAN_LOWER_BOUND_CM,
           DISTANCE_MEASUREMENTS_MEDIAN_UPPER_BOUND_CM))

  def _verify_distance_measurement_accuracy(
          self, initiator: uwb_ranging_decorator.UwbRangingDecorator,
          responder: uwb_ranging_decorator.UwbRangingDecorator,
          initiator_params: uwb_ranging_params.UwbRangingParams,
          responder_params: uwb_ranging_params.UwbRangingParams,
          initiator_addr: List[int], responder_addr: List[int]):
      """Verifies the accuracy of distance measurement.

      Args:
        initiator: uwb device object.
        responder: uwb device object.
        initiator_params: ranging params for initiator.
        responder_params: ranging params for responder.
        initiator_addr: address of initiator.
        responder_addr: address of uwb device.
      """
      # open and start ranging on initiator and responder.
      initiator.open_fira_ranging(initiator_params)
      responder.open_fira_ranging(responder_params)
      initiator.start_fira_ranging()
      responder.start_fira_ranging()

      # verify accuracy of distance measurement
      uwb_test_utils.verify_peer_found(initiator, responder_addr)
      uwb_test_utils.verify_peer_found(responder, initiator_addr)
      self._check_distance_accuracy(initiator, responder_addr)

  ### Test Cases ###

  def test_rssi_measurement_device_tracker_profile(self):
    """Checks RSSI measurements with device tracker profile default values."""
    initiator_params = uwb_ranging_params.UwbRangingParams(
        device_role=uwb_ranging_params.FiraParamEnums.DEVICE_ROLE_INITIATOR,
        device_type=uwb_ranging_params.FiraParamEnums.DEVICE_TYPE_CONTROLLER,
        device_address=self.initiator_addr,
        destination_addresses=[self.responder_addr],
        multi_node_mode=uwb_ranging_params.FiraParamEnums
        .MULTI_NODE_MODE_UNICAST,
        initiation_time_ms=100,
        ranging_interval_ms=240,
        slots_per_ranging_round=6,
        in_band_termination_attempt_count=3,
    )
    responder_params = uwb_ranging_params.UwbRangingParams(
        device_role=uwb_ranging_params.FiraParamEnums.DEVICE_ROLE_RESPONDER,
        device_type=uwb_ranging_params.FiraParamEnums.DEVICE_TYPE_CONTROLEE,
        device_address=self.responder_addr,
        destination_addresses=[self.initiator_addr],
        multi_node_mode=uwb_ranging_params.FiraParamEnums
        .MULTI_NODE_MODE_UNICAST,
        initiation_time_ms=100,
        ranging_interval_ms=240,
        slots_per_ranging_round=6,
        in_band_termination_attempt_count=3,
    )
    self._verify_peer_found(self.initiator, self.responder, initiator_params,
                            responder_params, self.initiator_addr,
                            self.responder_addr)

  def test_rssi_measurement_nearby_share_profile(self):
    """Checks RSSI measurements for device nearby share with default profile."""
    initiator_params = uwb_ranging_params.UwbRangingParams(
        device_role=uwb_ranging_params.FiraParamEnums.DEVICE_ROLE_INITIATOR,
        device_type=uwb_ranging_params.FiraParamEnums.DEVICE_TYPE_CONTROLLER,
        device_address=self.initiator_addr,
        destination_addresses=[self.responder_addr],
        initiation_time_ms=100,
        ranging_interval_ms=200,
        slots_per_ranging_round=20,
        in_band_termination_attempt_count=3,
    )
    responder_params = uwb_ranging_params.UwbRangingParams(
        device_role=uwb_ranging_params.FiraParamEnums.DEVICE_ROLE_RESPONDER,
        device_type=uwb_ranging_params.FiraParamEnums.DEVICE_TYPE_CONTROLEE,
        device_address=self.responder_addr,
        destination_addresses=[self.initiator_addr],
        initiation_time_ms=100,
        ranging_interval_ms=200,
        slots_per_ranging_round=20,
        in_band_termination_attempt_count=3,
    )
    self._verify_peer_found(self.initiator, self.responder, initiator_params,
                            responder_params, self.initiator_addr,
                            self.responder_addr)

  def test_distance_measurement_accuracy(self):
    """Checks accuracy of distance measurements.
       @CddTest = 7.4.9/C-1-6,C-1-7
    """
    initiator_params = uwb_ranging_params.UwbRangingParams(
        device_role=uwb_ranging_params.FiraParamEnums.DEVICE_ROLE_INITIATOR,
        device_type=uwb_ranging_params.FiraParamEnums.DEVICE_TYPE_CONTROLLER,
        device_address=self.initiator_addr,
        destination_addresses=[self.responder_addr],
        initiation_time_ms=100,
        ranging_interval_ms=200,
        slots_per_ranging_round=20,
        in_band_termination_attempt_count=3,
    )
    responder_params = uwb_ranging_params.UwbRangingParams(
        device_role=uwb_ranging_params.FiraParamEnums.DEVICE_ROLE_RESPONDER,
        device_type=uwb_ranging_params.FiraParamEnums.DEVICE_TYPE_CONTROLEE,
        device_address=self.responder_addr,
        destination_addresses=[self.initiator_addr],
        initiation_time_ms=100,
        ranging_interval_ms=200,
        slots_per_ranging_round=20,
        in_band_termination_attempt_count=3,
    )
    self._verify_distance_measurement_accuracy(self.initiator, self.responder, initiator_params,
                                               responder_params, self.initiator_addr,
                                               self.responder_addr)


if __name__ == "__main__":
  if '--' in sys.argv:
    index = sys.argv.index('--')
    sys.argv = sys.argv[:1] + sys.argv[index + 1:]
  test_runner.main()
