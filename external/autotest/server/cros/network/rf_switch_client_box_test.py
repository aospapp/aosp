"""Tests for rf_switch_client_box."""

import mock
import unittest

from autotest_lib.server import frontend
from autotest_lib.server.cros.network import rf_switch_client_box


class RfSwitchClientBoxTest(unittest.TestCase):
    """Tests for the RfSwitchClientBox."""


    def setUp(self):
        """Initial set up for the tests."""
        self.patcher = mock.patch('autotest_lib.server.frontend.Host')
        self.client_box_host = self.patcher.start()
        self.client_box_host.hostname = 'chromeos9-clientbox1'
        self.client_box_host.labels = [
                'rf_switch_1', 'client_box_1', 'rf_switch_client']


    def tearDown(self):
        """End patchers."""
        self.patcher.stop()


    @mock.patch('autotest_lib.server.frontend.AFE')
    @mock.patch('autotest_lib.server.frontend.Host')
    def testGetDevices(self, mock_host, mock_afe):
        """Test to get all devices from a Client Box."""
        dut_host = mock_host()
        dut_host.hostname = 'chromeos9-device1'
        dut_host.labels =  ['rf_switch_1', 'client_box_1', 'rf_switch_dut']
        # Add a device to the Client Box and verify.
        afe_instance = mock_afe()
        afe_instance.get_hosts.return_value = [self.client_box_host, dut_host]
        client_box = rf_switch_client_box.ClientBox(self.client_box_host)
        devices = client_box.get_devices()
        self.assertEquals(len(devices), 1)
        device = devices[0]
        self.assertEquals(device.hostname, 'chromeos9-device1')


    @mock.patch('autotest_lib.server.frontend.AFE')
    def testNoDevicesInClientbox(self, mock_afe):
        """Test for no devices in the Client Box."""
        afe_instance = mock_afe()
        afe_instance.get_hosts.return_value = [self.client_box_host]
        client_box = rf_switch_client_box.ClientBox(self.client_box_host)
        devices = client_box.get_devices()
        self.assertEquals(len(devices), 0)



if __name__ == '__main__':
  unittest.main()
