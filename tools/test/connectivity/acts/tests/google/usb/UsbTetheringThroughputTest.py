#!/usr/bin/env python3
#
#   Copyright 2019 - The Android Open Source Project
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

import re
import time

from acts import base_test
from acts.libs.proc import job
from acts import signals
from acts.test_decorators import test_tracker_info

# Time it takes for the usb tethering IP to show up in ifconfig.
IFCONFIG_SETTLE_TIME = 5
USB_CHARGE_MODE = 'svc usb setFunctions'
USB_TETHERING_MODE = 'svc usb setFunctions rndis'
DEVICE_IP_ADDRESS = 'ip address'

class UsbTetheringThroughputTest(base_test.BaseTestClass):
    """Tests for usb tethering throughput test.

    Test Bed Requirement:
          * One Android device.
          * Sim card with data call.
    """

    def setup_class(self):
        """ Setup devices for usb tethering """
        self.dut = self.android_devices[0]
        self.ip_server = self.iperf_servers[0]
        self.port_num = self.ip_server.port
        req_params = [
            'iperf_criteria',
        ]
        self.unpack_userparams(req_param_names=req_params)

    def setup_test(self):
        self.dut.droid.wakeLockAcquireBright()
        self.dut.droid.wakeUpNow()
        self.dut.unlock_screen()

    def teardown_test(self):
        self.dut.droid.wakeLockRelease()
        self.dut.droid.goToSleepNow()
        self.dut.stop_services()
        #Set usb function back to charge mode.
        self.dut.adb.shell(USB_CHARGE_MODE)
        self.dut.adb.wait_for_device()
        self.dut.start_services()
        self.ip_server.stop()

    def on_fail(self, test_name, begin_time):
        self.dut.take_bug_report(test_name, begin_time)
        self.dut.cat_adb_log(test_name, begin_time)

    def enable_usb_tethering(self):
        """Stop SL4A service and enable usb tethering.

        Logic steps are
        1. Stop SL4A service.
        2. Enable usb tethering.
        3. Restart SL4A service.
        4. Check usb tethering enabled.

        Raises:
            Signals.TestFailure is raised by not find rndis string.
        """
        self.dut.stop_services()
        self.dut.adb.shell(USB_TETHERING_MODE, ignore_status=True)
        self.dut.adb.wait_for_device()
        self.dut.start_services()
        if 'rndis' not in self.dut.adb.shell(DEVICE_IP_ADDRESS):
            raise signals.TestFailure('Unable to enable USB tethering.')

    def get_pc_tethering_ip(self):
        """Check usb tethering IP from PC.

        Returns:
            Usb tethering IP from PC.

        Raises:
            Signals.TestFailure is raised by not find usb tethering IP.
        """
        time.sleep(IFCONFIG_SETTLE_TIME)
        check_usb_tethering = job.run('ifconfig').stdout
        matches = re.findall('inet addr:(\d+.\d+.42.\d+)', check_usb_tethering)
        if not matches:
            raise signals.TestFailure(
                'Unable to find tethering IP. The device may not be tethered.')
        else:
            return matches[0]

    def get_dut_tethering_ip(self):
        """Check usb tethering IP from Android device.

        Returns:
            Usb tethering IP from Android device.

        Raises:
            Signals.TestFailure is raised by not find usb tethering IP.
        """
        time.sleep(IFCONFIG_SETTLE_TIME)
        check_usb_tethering = self.dut.adb.shell('ifconfig')
        matches = re.findall('addr:(\d+.\d+.42.\d+)', check_usb_tethering)
        if not matches:
            raise signals.TestFailure(
                'Unable to find tethering IP. The device may not be tethered.')
        else:
            return matches[0]

    def pc_can_ping(self, count=3):
        """Run ping traffic from PC side.

        Logic steps are
        1. PC ping the usb server ip.
        2. Check the packet loss rate.

        Args:
            count : count for ping test.

        Returns:
            True: If no packet loss.
            False: Otherwise.
        """
        ip = self.get_dut_tethering_ip()
        ping = job.run(
            'ping -c {} {}'.format(count, ip), ignore_status=True).stdout
        self.log.info(ping)
        return '0% packet loss' in ping

    def dut_can_ping(self, count=3):
        """Run ping traffic from Android device side.

        Logic steps are
        1. Android device ping the 8.8.8.8.
        2. Check the packet loss rate.

        Args:
            count : count for ping test.

        Returns:
            True: If no packet loss.
            False: Otherwise.
        """
        ip = '8.8.8.8'
        ping = self.dut.adb.shell(
            'ping -c {} {}'.format(count, ip), ignore_status=True)
        self.log.info(ping)
        return '0% packet loss' in ping

    def run_iperf_client(self, dut_ip, extra_params=''):
        """Run iperf client command after iperf server enabled.

        Args:
            dut_ip: string which contains the ip address.
            extra_params: params to be added to the iperf client.

        Returns:
            Success: True if the iperf execute. False otherwise.
            Rate: throughput data.
        """
        self.log.info('Run iperf process.')
        cmd = "iperf3 -c {} {} -p {}".format(dut_ip, extra_params,
                                             self.port_num)
        self.log.info(cmd)
        try:
            result = self.dut.adb.shell(cmd, ignore_status=True)
            self.log.info(result)
        except:
            self.log.error('Fail to execute iperf client.')
            return False, 0
        rate = re.findall('(\d+.\d+) Mbits/sec.*receiver', result)
        return True, rate[0]

    @test_tracker_info(uuid="e7e0dfdc-3d1c-4642-a468-27326c49e4cb")
    def test_tethering_ping(self):
        """Enable usb tethering then executing ping test.

        Steps:
        1. Stop SL4A service.
        2. Enable usb tethering.
        3. Restart SL4A service.
        4. Execute ping test from PC and Android Device.
        5. Check the ping lost rate.
        """
        self.enable_usb_tethering()
        if self.pc_can_ping() and self.dut_can_ping():
            raise signals.TestPass('Ping test is passed. Network is reachable.')
        raise signals.TestFailure(
            'Ping test failed. Maybe network is unreachable.')

    @test_tracker_info(uuid="8263c880-8a7e-4a68-b47f-e7caba3e9968")
    def test_usb_tethering_iperf(self):
        """Enable usb tethering then executing iperf test.

        Steps:
        1. Stop SL4A service.
        2. Enable usb tethering.
        3. Restart SL4A service.
        4. Execute iperf test for usb tethering and get the throughput result.
        5. Check the iperf throughput result.
        """
        self.enable_usb_tethering()
        self.ip_server.start()
        pc_ip = self.get_pc_tethering_ip()
        tx_success, tx_rate = self.run_iperf_client(pc_ip, '-t5 -i1 -w2M')
        rx_success, rx_rate = self.run_iperf_client(pc_ip, '-t5 -i1 -w2M -R')
        self.log.info('Iperf rx result: ' + rx_rate + ' Mbits/sec')
        self.log.info('Iperf tx result: ' + tx_rate + ' Mbits/sec')
        self.ip_server.stop()
        if not tx_success or float(tx_rate) < self.iperf_criteria:
            raise signals.TestFailure(
                'Iperf tx test is {} Mbits/sec, '
                'the throughput result failed.'.format(tx_rate))
        if not rx_success or float(rx_rate) < self.iperf_criteria:
            raise signals.TestFailure(
                'Iperf rx test is {} Mbits/sec, '
                'the throughput result failed.'.format(rx_rate))
