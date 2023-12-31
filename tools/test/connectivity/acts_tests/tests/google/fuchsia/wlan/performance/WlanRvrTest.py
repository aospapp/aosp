#!/usr/bin/env python3
#
#   Copyright 2019 - The Android secure Source Project
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
import os
import time

from acts import asserts
from acts import context
from acts.controllers.access_point import setup_ap
from acts.controllers.ap_lib import hostapd_constants
from acts.controllers.ap_lib.radvd import Radvd
from acts.controllers.ap_lib import radvd_constants
from acts.controllers.ap_lib.radvd_config import RadvdConfig
from acts.controllers.ap_lib.hostapd_security import Security
from acts.controllers.attenuator import get_attenuators_for_device
from acts.controllers.iperf_server import IPerfResult
from acts_contrib.test_utils.wifi.WifiBaseTest import WifiBaseTest
from acts_contrib.test_utils.abstract_devices.wlan_device import create_wlan_device
from acts.utils import rand_ascii_str

from bokeh.plotting import ColumnDataSource
from bokeh.plotting import figure
from bokeh.plotting import output_file
from bokeh.plotting import save

AP_11ABG_PROFILE_NAME = 'whirlwind_11ag_legacy'
REPORTING_SPEED_UNITS = 'Mbps'

RVR_GRAPH_SUMMARY_FILE = 'rvr_summary.html'

DAD_TIMEOUT_SEC = 30


def create_rvr_graph(test_name, graph_path, graph_data):
    """Creates the RvR graphs
    Args:
        test_name: The name of test that was run.  This is the title of the
            graph
        graph_path: Where to put the graph html file.
        graph_data: A dictionary of the data to be graphed.
    Returns:
        A list of bokeh graph objects.
        """
    output_file('%srvr_throughput_vs_attn_%s.html' % (graph_path, test_name),
                title=test_name)
    throughput_vs_attn_data = ColumnDataSource(data=dict(
        relative_attn=graph_data['throughput_vs_attn']['relative_attn'],
        throughput=graph_data['throughput_vs_attn']['throughput']))
    TOOLTIPS = [("Attenuation", "@relative_attn"),
                ("Throughput", "@throughput")]
    throughput_vs_attn_graph = figure(
        title="Throughput vs Relative Attenuation (Test Case: %s)" % test_name,
        x_axis_label=graph_data['throughput_vs_attn']['x_label'],
        y_axis_label=graph_data['throughput_vs_attn']['y_label'],
        x_range=graph_data['throughput_vs_attn']['relative_attn'],
        tooltips=TOOLTIPS)
    throughput_vs_attn_graph.sizing_mode = 'stretch_width'
    throughput_vs_attn_graph.title.align = 'center'
    throughput_vs_attn_graph.line('relative_attn',
                                  'throughput',
                                  source=throughput_vs_attn_data,
                                  line_width=2)
    throughput_vs_attn_graph.circle('relative_attn',
                                    'throughput',
                                    source=throughput_vs_attn_data,
                                    size=10)
    save([throughput_vs_attn_graph])
    return [throughput_vs_attn_graph]


def write_csv_rvr_data(test_name, csv_path, csv_data):
    """Writes the CSV data for the RvR test
    Args:
        test_name: The name of test that was run.
        csv_path: Where to put the csv file.
        csv_data: A dictionary of the data to be put in the csv file.
    """
    csv_file_name = '%srvr_throughput_vs_attn_%s.csv' % (csv_path, test_name)
    throughput = csv_data['throughput_vs_attn']['throughput']
    relative_attn = csv_data['throughput_vs_attn']['relative_attn']
    with open(csv_file_name, 'w+') as csv_fileId:
        csv_fileId.write('%s,%s\n' %
                         (csv_data['throughput_vs_attn']['x_label'],
                          csv_data['throughput_vs_attn']['y_label']))
        for csv_loop_counter in range(0, len(relative_attn)):
            csv_fileId.write('%s,%s\n' % (int(relative_attn[csv_loop_counter]),
                                          throughput[csv_loop_counter]))


class WlanRvrTest(WifiBaseTest):
    """Tests running WLAN RvR.

    Test Bed Requirement:
    * One Android device or Fuchsia device
    * One Access Point
    * One attenuator
    * One Linux iPerf Server
    """

    def __init__(self, controllers):
        super().__init__(controllers)
        self.rvr_graph_summary = []

    def setup_class(self):
        super().setup_class()
        if 'dut' in self.user_params:
            if self.user_params['dut'] == 'fuchsia_devices':
                self.dut = create_wlan_device(self.fuchsia_devices[0])
            elif self.user_params['dut'] == 'android_devices':
                self.dut = create_wlan_device(self.android_devices[0])
            else:
                raise ValueError('Invalid DUT specified in config. (%s)' %
                                 self.user_params['dut'])
        else:
            # Default is an android device, just like the other tests
            self.dut = create_wlan_device(self.android_devices[0])

        self.starting_attn = (self.user_params['rvr_settings'].get(
            'starting_attn', 0))

        self.ending_attn = (self.user_params['rvr_settings'].get(
            'ending_attn', 95))

        self.step_size_in_db = (self.user_params['rvr_settings'].get(
            'step_size_in_db', 1))

        self.dwell_time_in_secs = (self.user_params['rvr_settings'].get(
            'dwell_time_in_secs', 10))

        self.reverse_rvr_after_forward = bool(
            (self.user_params['rvr_settings'].get('reverse_rvr_after_forward',
                                                  None)))

        self.iperf_flags = (self.user_params['rvr_settings'].get(
            'iperf_flags', '-i 1'))

        self.iperf_flags = '%s -t %s -J' % (self.iperf_flags,
                                            self.dwell_time_in_secs)

        self.debug_loop_count = (self.user_params['rvr_settings'].get(
            'debug_loop_count', 1))

        self.debug_pre_traffic_cmd = (self.user_params['rvr_settings'].get(
            'debug_pre_traffic_cmd', None))

        self.debug_post_traffic_cmd = (self.user_params['rvr_settings'].get(
            'debug_post_traffic_cmd', None))

        self.router_adv_daemon = None

        if self.ending_attn == 'auto':
            self.use_auto_end = True
            self.ending_attn = 100
            if self.step_size_in_db > 2:
                asserts.fail('When using an ending attenuation of \'auto\' '
                             'please use a value < 2db.  Larger jumps will '
                             'break the test reporting.')

        self.access_point = self.access_points[0]
        self.attenuators_2g = get_attenuators_for_device(
            self.controller_configs['AccessPoint'][0]['Attenuator'],
            self.attenuators, 'attenuator_ports_wifi_2g')
        self.attenuators_5g = get_attenuators_for_device(
            self.controller_configs['AccessPoint'][0]['Attenuator'],
            self.attenuators, 'attenuator_ports_wifi_5g')

        self.iperf_server = self.iperf_servers[0]

        if hasattr(self, "iperf_clients") and self.iperf_clients:
            self.dut_iperf_client = self.iperf_clients[0]
        else:
            self.dut_iperf_client = self.dut.create_iperf_client()

        self.access_point.stop_all_aps()

    def setup_test(self):
        if self.iperf_server:
            self.iperf_server.start()
        if hasattr(self, "android_devices"):
            for ad in self.android_devices:
                ad.droid.wakeLockAcquireBright()
                ad.droid.wakeUpNow()
        self.dut.wifi_toggle_state(True)

    def teardown_test(self):
        self.cleanup_tests()

    def teardown_class(self):
        if self.router_adv_daemon:
            self.router_adv_daemon.stop()
        try:
            output_path = context.get_current_context().get_base_output_path()
            test_class_name = context.get_current_context().test_class_name

            output_file(f'{output_path}/{test_class_name}/rvr_summary.html',
                        title='RvR Sumamry')
            save(list(self.rvr_graph_summary))
        except Exception as e:
            self.log.error(f'Unable to generate RvR summary file: {e}')

        super().teardown_class()

    def on_fail(self, test_name, begin_time):
        super().on_fail(test_name, begin_time)
        self.cleanup_tests()

    def cleanup_tests(self):
        """Cleans up all the dangling pieces of the tests, for example, the
        iperf server, radvd, all the currently running APs, and the various
        clients running during the tests.
        """

        if self.router_adv_daemon:
            output_path = context.get_current_context().get_base_output_path()
            full_output_path = os.path.join(output_path, "radvd_log.txt")
            radvd_log_file = open(full_output_path, 'w')
            radvd_log_file.write(self.router_adv_daemon.pull_logs())
            radvd_log_file.close()
            self.router_adv_daemon.stop()
        if hasattr(self, "android_devices"):
            for ad in self.android_devices:
                ad.droid.wakeLockRelease()
                ad.droid.goToSleepNow()
        if self.iperf_server:
            self.iperf_server.stop()
        self.dut.turn_location_off_and_scan_toggle_off()
        self.dut.disconnect()
        self.dut.reset_wifi()
        self.download_ap_logs()
        self.access_point.stop_all_aps()

    def _wait_for_ipv4_addrs(self):
        """Wait for an IPv4 addresses to become available on the DUT and iperf
        server.

        Returns:
           A string containing the private IPv4 address of the iperf server.

        Raises:
            TestFailure: If unable to acquire a IPv4 address.
        """
        ip_address_checker_counter = 0
        ip_address_checker_max_attempts = 3
        while ip_address_checker_counter < ip_address_checker_max_attempts:
            self.iperf_server.renew_test_interface_ip_address()
            iperf_server_ip_addresses = (
                self.iperf_server.get_interface_ip_addresses(
                    self.iperf_server.test_interface))
            dut_ip_addresses = self.dut.device.get_interface_ip_addresses(
                self.dut_iperf_client.test_interface)

            self.log.info(
                'IPerf server IP info: {}'.format(iperf_server_ip_addresses))
            self.log.info('DUT IP info: {}'.format(dut_ip_addresses))

            if not iperf_server_ip_addresses['ipv4_private']:
                self.log.warn('Unable to get the iperf server IPv4 '
                              'address. Retrying...')
                ip_address_checker_counter += 1
                time.sleep(1)
                continue

            if dut_ip_addresses['ipv4_private']:
                return iperf_server_ip_addresses['ipv4_private'][0]

            self.log.warn('Unable to get the DUT IPv4 address starting at '
                          'attenuation "{}". Retrying...'.format(
                              self.starting_attn))
            ip_address_checker_counter += 1
            time.sleep(1)

        asserts.fail(
            'IPv4 addresses are not available on both the DUT and iperf server.'
        )

    # TODO (b/258264565): Merge with fuchsia_device wait_for_ipv6_addr.
    def _wait_for_dad(self, device, test_interface):
        """Wait for Duplicate Address Detection to resolve so that an
        private-local IPv6 address is available for test.

        Args:
            device: implementor of get_interface_ip_addresses
            test_interface: name of interface that DAD is operating on

        Returns:
            A string containing the private-local IPv6 address of the device.

        Raises:
            TestFailure: If unable to acquire an IPv6 address.
        """
        now = time.time()
        start = now
        elapsed = now - start

        while elapsed < DAD_TIMEOUT_SEC:
            addrs = device.get_interface_ip_addresses(test_interface)
            now = time.time()
            elapsed = now - start
            if addrs['ipv6_private_local']:
                # DAD has completed
                addr = addrs['ipv6_private_local'][0]
                self.log.info('DAD resolved with "{}" after {}s'.format(
                    addr, elapsed))
                return addr
            time.sleep(1)
        else:
            asserts.fail(
                'Unable to acquire a private-local IPv6 address for testing '
                'after {}s'.format(elapsed))

    def run_rvr(self,
                ssid,
                security_mode=None,
                password=None,
                band='2g',
                traffic_dir='tx',
                ip_version=4):
        """Setups and runs the RvR test

        Args:
            ssid: The SSID for the client to associate to.
            password: Password for the network, if necessary.
            band: 2g or 5g
            traffic_dir: rx or tx, bi is not supported by iperf3
            ip_version: 4 or 6

        Returns:
            The bokeh graph data.
        """
        throughput = []
        relative_attn = []
        if band == '2g':
            rvr_attenuators = self.attenuators_2g
        elif band == '5g':
            rvr_attenuators = self.attenuators_5g
        else:
            raise ValueError('Invalid WLAN band specified: %s' % band)
        if ip_version == 6:
            self.router_adv_daemon = Radvd(
                self.access_point.ssh,
                self.access_point.interfaces.get_bridge_interface()[0])
            radvd_config = RadvdConfig()
            self.router_adv_daemon.start(radvd_config)

        for _ in range(0, self.debug_loop_count):
            for rvr_attenuator in rvr_attenuators:
                rvr_attenuator.set_atten(self.starting_attn)

            associate_counter = 0
            associate_max_attempts = 3
            while associate_counter < associate_max_attempts:
                if self.dut.associate(
                        ssid,
                        target_pwd=password,
                        target_security=hostapd_constants.
                        SECURITY_STRING_TO_DEFAULT_TARGET_SECURITY.get(
                            security_mode),
                        check_connectivity=False):
                    break
                else:
                    associate_counter += 1
            else:
                asserts.fail('Unable to associate at starting '
                             'attenuation: %s' % self.starting_attn)

            if ip_version == 4:
                iperf_server_ip_address = self._wait_for_ipv4_addrs()
            elif ip_version == 6:
                self.iperf_server.renew_test_interface_ip_address()
                self.log.info('Waiting for iperf server to complete Duplicate '
                              'Address Detection...')
                iperf_server_ip_address = self._wait_for_dad(
                    self.iperf_server, self.iperf_server.test_interface)

                self.log.info('Waiting for DUT to complete Duplicate Address '
                              'Detection for "{}"...'.format(
                                  self.dut_iperf_client.test_interface))
                _ = self._wait_for_dad(self.dut.device,
                                       self.dut_iperf_client.test_interface)
            else:
                raise ValueError('Invalid IP version: {}'.format(ip_version))

            throughput, relative_attn = (self.rvr_loop(
                traffic_dir,
                rvr_attenuators,
                iperf_server_ip_address,
                ip_version,
                throughput=throughput,
                relative_attn=relative_attn))
            if self.reverse_rvr_after_forward:
                throughput, relative_attn = self.rvr_loop(
                    traffic_dir,
                    rvr_attenuators,
                    iperf_server_ip_address,
                    ip_version,
                    ssid=ssid,
                    security_mode=security_mode,
                    password=password,
                    reverse=True,
                    throughput=throughput,
                    relative_attn=relative_attn)
            self.dut.disconnect()

        throughput_vs_attn = {
            'throughput': throughput,
            'relative_attn': relative_attn,
            'x_label': 'Attenuation(db)',
            'y_label': 'Throughput(%s)' % REPORTING_SPEED_UNITS
        }
        graph_data = {'throughput_vs_attn': throughput_vs_attn}
        return graph_data

    def rvr_loop(self,
                 traffic_dir,
                 rvr_attenuators,
                 iperf_server_ip_address,
                 ip_version,
                 ssid=None,
                 security_mode=None,
                 password=None,
                 reverse=False,
                 throughput=None,
                 relative_attn=None):
        """The loop that goes through each attenuation level and runs the iperf
        throughput pair.
        Args:
            traffic_dir: The traffic direction from the perspective of the DUT.
            rvr_attenuators: A list of attenuators to set.
            iperf_server_ip_address: The IP address of the iperf server.
            ssid: The ssid of the wireless network that the should associated
                to.
            password: Password of the wireless network.
            reverse: Whether to run RvR test starting from the highest
                attenuation and going to the lowest.  This is run after the
                normal low attenuation to high attenuation RvR test.
            throughput: The list of throughput data for the test.
            relative_attn: The list of attenuation data for the test.

        Returns:
            throughput: The list of throughput data for the test.
            relative_attn: The list of attenuation data for the test.
            """
        iperf_flags = self.iperf_flags
        if traffic_dir == 'rx':
            iperf_flags = '%s -R' % self.iperf_flags
        starting_attn = self.starting_attn
        ending_attn = self.ending_attn
        step_size_in_db = self.step_size_in_db
        if reverse:
            starting_attn = self.ending_attn
            ending_attn = self.starting_attn
            step_size_in_db = step_size_in_db * -1
            self.dut.disconnect()
        for step in range(starting_attn, ending_attn, step_size_in_db):
            try:
                for attenuator in rvr_attenuators:
                    attenuator.set_atten(step)
            except ValueError as e:
                self.log.error(
                    f'{step} is beyond the max or min of the testbed '
                    f'attenuator\'s capability. Stopping. {e}')
                break
            self.log.info('Set relative attenuation to %s db' % step)

            associated = self.dut.is_connected()
            if associated:
                self.log.info('DUT is currently associated.')
            else:
                self.log.info('DUT is not currently associated.')

            if reverse:
                if not associated:
                    self.log.info('Trying to associate at relative '
                                  'attenuation of %s db' % step)
                    if self.dut.associate(
                            ssid,
                            target_pwd=password,
                            target_security=hostapd_constants.
                            SECURITY_STRING_TO_DEFAULT_TARGET_SECURITY.get(
                                security_mode),
                            check_connectivity=False):
                        associated = True
                        self.log.info('Successfully associated.')
                    else:
                        associated = False
                        self.log.info(
                            'Association failed. Marking a 0 %s for'
                            ' throughput. Skipping running traffic.' %
                            REPORTING_SPEED_UNITS)
            attn_value_inserted = False
            value_to_insert = str(step)
            while not attn_value_inserted:
                if value_to_insert in relative_attn:
                    value_to_insert = '%s ' % value_to_insert
                else:
                    relative_attn.append(value_to_insert)
                    attn_value_inserted = True

            dut_ip_addresses = self.dut.device.get_interface_ip_addresses(
                self.dut_iperf_client.test_interface)
            if ip_version == 4:
                if not dut_ip_addresses['ipv4_private']:
                    self.log.info('DUT does not have an IPv4 address. '
                                  'Traffic attempt to be run if the server '
                                  'is pingable.')
                else:
                    self.log.info('DUT has the following IPv4 address: "%s"' %
                                  dut_ip_addresses['ipv4_private'][0])
            elif ip_version == 6:
                if not dut_ip_addresses['ipv6_private_local']:
                    self.log.info('DUT does not have an IPv6 address. '
                                  'Traffic attempt to be run if the server '
                                  'is pingable.')
                else:
                    self.log.info('DUT has the following IPv6 address: "%s"' %
                                  dut_ip_addresses['ipv6_private_local'][0])
            server_pingable = self.dut.can_ping(iperf_server_ip_address)
            if not server_pingable:
                self.log.info('Iperf server "%s" is not pingable. Marking '
                              'a 0 %s for throughput. Skipping running '
                              'traffic.' %
                              (iperf_server_ip_address, REPORTING_SPEED_UNITS))
            else:
                self.log.info('Iperf server "%s" is pingable.' %
                              iperf_server_ip_address)
            if self.debug_pre_traffic_cmd:
                self.log.info('\nDEBUG: Sending command \'%s\' to DUT' %
                              self.debug_pre_traffic_cmd)
                self.log.info(
                    '\n%s' % self.dut.send_command(self.debug_pre_traffic_cmd))
            if server_pingable:
                if traffic_dir == 'tx':
                    self.log.info('Running traffic DUT to %s at relative '
                                  'attenuation of %s' %
                                  (iperf_server_ip_address, step))
                elif traffic_dir == 'rx':
                    self.log.info('Running traffic %s to DUT at relative '
                                  'attenuation of %s' %
                                  (iperf_server_ip_address, step))
                else:
                    raise ValueError('Invalid traffic direction')
                try:
                    iperf_tag = 'decreasing'
                    if reverse:
                        iperf_tag = 'increasing'
                    iperf_results_file = self.dut_iperf_client.start(
                        iperf_server_ip_address,
                        iperf_flags,
                        '%s_%s_%s' %
                        (iperf_tag, traffic_dir, self.starting_attn),
                        timeout=(self.dwell_time_in_secs * 2))
                except TimeoutError as e:
                    iperf_results_file = None
                    self.log.error(
                        f'Iperf traffic timed out. Marking 0 {REPORTING_SPEED_UNITS} for '
                        f'throughput. {e}')

                if not iperf_results_file:
                    throughput.append(0)
                else:
                    try:
                        iperf_results = IPerfResult(
                            iperf_results_file,
                            reporting_speed_units=REPORTING_SPEED_UNITS)
                        if iperf_results.error:
                            self.iperf_server.stop()
                            self.iperf_server.start()
                            self.log.error(
                                f'Errors in iperf logs:\n{iperf_results.error}'
                            )
                        if not iperf_results.avg_send_rate:
                            throughput.append(0)
                        else:
                            throughput.append(iperf_results.avg_send_rate)
                    except ValueError as e:
                        self.iperf_server.stop()
                        self.iperf_server.start()
                        self.log.error(
                            f'No data in iPerf3 file. Marking 0 {REPORTING_SPEED_UNITS} '
                            f'for throughput: {e}')
                        throughput.append(0)
                    except Exception as e:
                        self.iperf_server.stop()
                        self.iperf_server.start()
                        self.log.error(
                            f'Unknown exception. Marking 0 {REPORTING_SPEED_UNITS} for '
                            f'throughput: {e}')
                        self.log.error(e)
                        throughput.append(0)

                self.log.info(
                    'Iperf traffic complete. %s traffic received at '
                    '%s %s at relative attenuation of %s db' %
                    (traffic_dir, throughput[-1], REPORTING_SPEED_UNITS,
                     str(relative_attn[-1]).strip()))

            else:
                self.log.debug('DUT Associated: %s' % associated)
                self.log.debug('%s pingable: %s' %
                               (iperf_server_ip_address, server_pingable))
                throughput.append(0)
            if self.debug_post_traffic_cmd:
                self.log.info('\nDEBUG: Sending command \'%s\' to DUT' %
                              self.debug_post_traffic_cmd)
                self.log.info(
                    '\n%s' %
                    self.dut.send_command(self.debug_post_traffic_cmd))
        return throughput, relative_attn

    def test_rvr_11ac_5g_80mhz_open_tx_ipv4(self):
        ssid = rand_ascii_str(20)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_5G,
                 ssid=ssid,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  band='5g',
                                  traffic_dir='tx',
                                  ip_version=4)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11ac_5g_80mhz_open_rx_ipv4(self):
        ssid = rand_ascii_str(20)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_5G,
                 ssid=ssid,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  band='5g',
                                  traffic_dir='rx',
                                  ip_version=4)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11ac_5g_80mhz_open_tx_ipv6(self):
        ssid = rand_ascii_str(20)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_5G,
                 ssid=ssid,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  band='5g',
                                  traffic_dir='tx',
                                  ip_version=6)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11ac_5g_80mhz_open_rx_ipv6(self):
        ssid = rand_ascii_str(20)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_5G,
                 ssid=ssid,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  band='5g',
                                  traffic_dir='rx',
                                  ip_version=6)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11ac_5g_80mhz_wpa2_tx_ipv4(self):
        ssid = rand_ascii_str(20)
        password = rand_ascii_str(20)
        security_profile = Security(security_mode='wpa2', password=password)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_5G,
                 ssid=ssid,
                 security=security_profile,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  security_mode='wpa2',
                                  password=password,
                                  band='5g',
                                  traffic_dir='tx',
                                  ip_version=4)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11ac_5g_80mhz_wpa2_rx_ipv4(self):
        ssid = rand_ascii_str(20)
        password = rand_ascii_str(20)
        security_profile = Security(security_mode='wpa2', password=password)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_5G,
                 ssid=ssid,
                 security=security_profile,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  security_mode='wpa2',
                                  password=password,
                                  band='5g',
                                  traffic_dir='rx',
                                  ip_version=4)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11ac_5g_80mhz_wpa2_tx_ipv6(self):
        ssid = rand_ascii_str(20)
        password = rand_ascii_str(20)
        security_profile = Security(security_mode='wpa2', password=password)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_5G,
                 ssid=ssid,
                 security=security_profile,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  security_mode='wpa2',
                                  password=password,
                                  band='5g',
                                  traffic_dir='tx',
                                  ip_version=6)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11ac_5g_80mhz_wpa2_rx_ipv6(self):
        ssid = rand_ascii_str(20)
        password = rand_ascii_str(20)
        security_profile = Security(security_mode='wpa2', password=password)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_5G,
                 ssid=ssid,
                 security=security_profile,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  security_mode='wpa2',
                                  password=password,
                                  band='5g',
                                  traffic_dir='rx',
                                  ip_version=6)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11n_2g_20mhz_open_tx_ipv4(self):
        ssid = rand_ascii_str(20)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_2G,
                 ssid=ssid,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  band='2g',
                                  traffic_dir='tx',
                                  ip_version=4)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11n_2g_20mhz_open_rx_ipv4(self):
        ssid = rand_ascii_str(20)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_2G,
                 ssid=ssid,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  band='2g',
                                  traffic_dir='rx',
                                  ip_version=4)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11n_2g_20mhz_open_tx_ipv6(self):
        ssid = rand_ascii_str(20)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_2G,
                 ssid=ssid,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  band='2g',
                                  traffic_dir='tx',
                                  ip_version=6)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11n_2g_20mhz_open_rx_ipv6(self):
        ssid = rand_ascii_str(20)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_2G,
                 ssid=ssid,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  band='2g',
                                  traffic_dir='rx',
                                  ip_version=6)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11n_2g_20mhz_wpa2_tx_ipv4(self):
        ssid = rand_ascii_str(20)
        password = rand_ascii_str(20)
        security_profile = Security(security_mode='wpa2', password=password)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_2G,
                 ssid=ssid,
                 security=security_profile,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  security_mode='wpa2',
                                  password=password,
                                  band='2g',
                                  traffic_dir='tx',
                                  ip_version=4)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11n_2g_20mhz_wpa2_rx_ipv4(self):
        ssid = rand_ascii_str(20)
        password = rand_ascii_str(20)
        security_profile = Security(security_mode='wpa2', password=password)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_2G,
                 ssid=ssid,
                 security=security_profile,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  security_mode='wpa2',
                                  password=password,
                                  band='2g',
                                  traffic_dir='rx',
                                  ip_version=4)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11n_2g_20mhz_wpa2_tx_ipv6(self):
        ssid = rand_ascii_str(20)
        password = rand_ascii_str(20)
        security_profile = Security(security_mode='wpa2', password=password)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_2G,
                 ssid=ssid,
                 security=security_profile,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  security_mode='wpa2',
                                  password=password,
                                  band='2g',
                                  traffic_dir='tx',
                                  ip_version=6)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)

    def test_rvr_11n_2g_20mhz_wpa2_rx_ipv6(self):
        ssid = rand_ascii_str(20)
        password = rand_ascii_str(20)
        security_profile = Security(security_mode='wpa2', password=password)
        setup_ap(access_point=self.access_point,
                 profile_name='whirlwind',
                 channel=hostapd_constants.AP_DEFAULT_CHANNEL_2G,
                 ssid=ssid,
                 security=security_profile,
                 setup_bridge=True)
        graph_data = self.run_rvr(ssid,
                                  security_mode='wpa2',
                                  password=password,
                                  band='2g',
                                  traffic_dir='rx',
                                  ip_version=6)
        for rvr_graph in create_rvr_graph(
                self.test_name,
                context.get_current_context().get_full_output_path(),
                graph_data):
            self.rvr_graph_summary.append(rvr_graph)
        write_csv_rvr_data(
            self.test_name,
            context.get_current_context().get_full_output_path(), graph_data)
