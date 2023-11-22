#!/usr/bin/env python3.4
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

import bokeh, bokeh.plotting
import collections
import logging
import math
import re
import statistics
import time
from acts.controllers.android_device import AndroidDevice
from acts.controllers.utils_lib import ssh
from concurrent.futures import ThreadPoolExecutor

SHORT_SLEEP = 1
MED_SLEEP = 6
TEST_TIMEOUT = 10
STATION_DUMP = 'iw wlan0 station dump'
SCAN = 'wpa_cli scan'
SCAN_RESULTS = 'wpa_cli scan_results'
SIGNAL_POLL = 'wpa_cli signal_poll'
WPA_CLI_STATUS = 'wpa_cli status'
CONST_3dB = 3.01029995664
RSSI_ERROR_VAL = float('nan')
RTT_REGEX = re.compile(r'^\[(?P<timestamp>\S+)\] .*? time=(?P<rtt>\S+)')
LOSS_REGEX = re.compile(r'(?P<loss>\S+)% packet loss')


# Threading decorator
def nonblocking(f):
    """Creates a decorator transforming function calls to non-blocking"""

    def wrap(*args, **kwargs):
        executor = ThreadPoolExecutor(max_workers=1)
        thread_future = executor.submit(f, *args, **kwargs)
        # Ensure resources are freed up when executor ruturns or raises
        executor.shutdown(wait=False)
        return thread_future

    return wrap


# Plotting Utilities
class BokehFigure():
    def __init__(self,
                 title=None,
                 x_label=None,
                 primary_y=None,
                 secondary_y=None,
                 height=700,
                 width=1300,
                 title_size=15,
                 axis_label_size=12):
        self.figure_data = []
        self.fig_property = {
            'title': title,
            'x_label': x_label,
            'primary_y_label': primary_y,
            'secondary_y_label': secondary_y,
            'num_lines': 0,
            'title_size': '{}pt'.format(title_size),
            'axis_label_size': '{}pt'.format(axis_label_size)
        }
        self.TOOLS = (
            'box_zoom,box_select,pan,crosshair,redo,undo,reset,hover,save')
        self.COLORS = [
            'black',
            'blue',
            'blueviolet',
            'brown',
            'burlywood',
            'cadetblue',
            'cornflowerblue',
            'crimson',
            'cyan',
            'darkblue',
            'darkgreen',
            'darkmagenta',
            'darkorange',
            'darkred',
            'deepskyblue',
            'goldenrod',
            'green',
            'grey',
            'indigo',
            'navy',
            'olive',
            'orange',
            'red',
            'salmon',
            'teal',
            'yellow',
        ]
        self.MARKERS = [
            'asterisk', 'circle', 'circle_cross', 'circle_x', 'cross',
            'diamond', 'diamond_cross', 'hex', 'inverted_triangle', 'square',
            'square_x', 'square_cross', 'triangle', 'x'
        ]
        self.plot = bokeh.plotting.figure(
            plot_width=width,
            plot_height=height,
            title=title,
            tools=self.TOOLS,
            output_backend='webgl')
        self.plot.add_tools(
            bokeh.models.tools.WheelZoomTool(dimensions='width'))
        self.plot.add_tools(
            bokeh.models.tools.WheelZoomTool(dimensions='height'))

    def add_line(self,
                 x_data,
                 y_data,
                 legend,
                 color=None,
                 width=3,
                 style='solid',
                 marker=None,
                 marker_size=10,
                 shaded_region=None,
                 y_axis='default'):
        if y_axis not in ['default', 'secondary']:
            raise ValueError('y_axis must be default or secondary')
        if color == None:
            color = self.COLORS[self.fig_property['num_lines'] % len(
                self.COLORS)]
        if style == 'dashed':
            style = [5, 5]
        self.figure_data.append({
            'x_data': x_data,
            'y_data': y_data,
            'legend': legend,
            'color': color,
            'width': width,
            'style': style,
            'marker': marker,
            'marker_size': marker_size,
            'shaded_region': shaded_region,
            'y_range_name': y_axis
        })
        self.fig_property['num_lines'] += 1

    def generate_figure(self, output_file=None):
        two_axes = False
        for line in self.figure_data:
            self.plot.line(
                line['x_data'],
                line['y_data'],
                legend=line['legend'],
                line_width=line['width'],
                color=line['color'],
                line_dash=line['style'],
                name=line['y_range_name'],
                y_range_name=line['y_range_name'])
            if line['shaded_region']:
                band_x = line['shaded_region']['x_vector']
                band_x.extend(line['shaded_region']['x_vector'][::-1])
                band_y = line['shaded_region']['lower_limit']
                band_y.extend(line['shaded_region']['upper_limit'][::-1])
                self.plot.patch(
                    band_x,
                    band_y,
                    color='#7570B3',
                    line_alpha=0.1,
                    fill_alpha=0.1)
            if line['marker'] in self.MARKERS:
                marker_func = getattr(self.plot, line['marker'])
                marker_func(
                    line['x_data'],
                    line['y_data'],
                    size=line['marker_size'],
                    legend=line['legend'],
                    fill_color=line['color'],
                    name=line['y_range_name'],
                    y_range_name=line['y_range_name'])
            if line['y_range_name'] == 'secondary':
                two_axes = True

        #x-axis formatting
        self.plot.xaxis.axis_label = self.fig_property['x_label']
        self.plot.x_range.range_padding = 0
        self.plot.xaxis[0].axis_label_text_font_size = self.fig_property[
            'axis_label_size']
        #y-axis formatting
        self.plot.yaxis[0].axis_label = self.fig_property['primary_y_label']
        self.plot.yaxis[0].axis_label_text_font_size = self.fig_property[
            'axis_label_size']
        self.plot.y_range = bokeh.models.DataRange1d(names=['default'])
        if two_axes and 'secondary' not in self.plot.extra_y_ranges:
            self.plot.extra_y_ranges = {
                'secondary': bokeh.models.DataRange1d(names=['secondary'])
            }
            self.plot.add_layout(
                bokeh.models.LinearAxis(
                    y_range_name='secondary',
                    axis_label=self.fig_property['secondary_y_label'],
                    axis_label_text_font_size=self.
                    fig_property['axis_label_size']), 'right')
        # plot formatting
        self.plot.legend.location = 'top_right'
        self.plot.legend.click_policy = 'hide'
        self.plot.title.text_font_size = self.fig_property['title_size']

        if output_file is not None:
            bokeh.plotting.output_file(output_file)
            bokeh.plotting.save(self.plot)
        return self.plot

    def save_figure(self, output_file):
        bokeh.plotting.output_file(output_file)
        bokeh.plotting.save(self.plot)


def bokeh_plot(data_sets,
               legends,
               fig_property,
               shaded_region=None,
               output_file_path=None):
    """Plot bokeh figs.
        Args:
            data_sets: data sets including lists of x_data and lists of y_data
                       ex: [[[x_data1], [x_data2]], [[y_data1],[y_data2]]]
            legends: list of legend for each curve
            fig_property: dict containing the plot property, including title,
                      lables, linewidth, circle size, etc.
            shaded_region: optional dict containing data for plot shading
            output_file_path: optional path at which to save figure
        Returns:
            plot: bokeh plot figure object
    """
    TOOLS = ('box_zoom,box_select,pan,crosshair,redo,undo,reset,hover,save')
    plot = bokeh.plotting.figure(
        plot_width=1300,
        plot_height=700,
        title=fig_property['title'],
        tools=TOOLS,
        output_backend='webgl')
    plot.add_tools(bokeh.models.tools.WheelZoomTool(dimensions='width'))
    plot.add_tools(bokeh.models.tools.WheelZoomTool(dimensions='height'))
    colors = [
        'red', 'green', 'blue', 'olive', 'orange', 'salmon', 'black', 'navy',
        'yellow', 'darkred', 'goldenrod'
    ]
    if shaded_region:
        band_x = shaded_region['x_vector']
        band_x.extend(shaded_region['x_vector'][::-1])
        band_y = shaded_region['lower_limit']
        band_y.extend(shaded_region['upper_limit'][::-1])
        plot.patch(
            band_x, band_y, color='#7570B3', line_alpha=0.1, fill_alpha=0.1)

    for x_data, y_data, legend in zip(data_sets[0], data_sets[1], legends):
        index_now = legends.index(legend)
        color = colors[index_now % len(colors)]
        plot.line(
            x_data,
            y_data,
            legend=str(legend),
            line_width=fig_property['linewidth'],
            color=color)
        plot.circle(
            x_data,
            y_data,
            size=fig_property['markersize'],
            legend=str(legend),
            fill_color=color)

    # Plot properties
    plot.xaxis.axis_label = fig_property['x_label']
    plot.yaxis.axis_label = fig_property['y_label']
    plot.legend.location = 'top_right'
    plot.legend.click_policy = 'hide'
    plot.title.text_font_size = {'value': '15pt'}
    if output_file_path is not None:
        bokeh.plotting.output_file(output_file_path)
        bokeh.plotting.save(plot)
    return plot


def save_bokeh_plots(plot_array, output_file_path):
    all_plots = bokeh.layouts.column(children=plot_array)
    bokeh.plotting.output_file(output_file_path)
    bokeh.plotting.save(all_plots)


class PingResult(object):
    """An object that contains the results of running ping command.

    Attributes:
        connected: True if a connection was made. False otherwise.
        packet_loss_percentage: The total percentage of packets lost.
        transmission_times: The list of PingTransmissionTimes containing the
            timestamps gathered for transmitted packets.
        rtts: An list-like object enumerating all round-trip-times of
            transmitted packets.
        timestamps: A list-like object enumerating the beginning timestamps of
            each packet transmission.
        ping_interarrivals: A list-like object enumerating the amount of time
            between the beginning of each subsequent transmission.
    """

    def __init__(self, ping_output):
        self.packet_loss_percentage = 100
        self.transmission_times = []

        self.rtts = _ListWrap(self.transmission_times, lambda entry: entry.rtt)
        self.timestamps = _ListWrap(
            self.transmission_times, lambda entry: entry.timestamp)
        self.ping_interarrivals = _PingInterarrivals(self.transmission_times)

        for line in ping_output:
            if 'loss' in line:
                match = re.search(LOSS_REGEX, line)
                self.packet_loss_percentage = float(match.group('loss'))
            if 'time=' in line:
                match = re.search(RTT_REGEX, line)
                self.transmission_times.append(
                    PingTransmissionTimes(
                        float(match.group('timestamp')),
                        float(match.group('rtt'))))
        self.connected = len(
            ping_output) > 1 and self.packet_loss_percentage < 100

    def __getitem__(self, item):
        if item == 'rtt':
            return self.rtts
        if item == 'connected':
            return self.connected
        if item == 'packet_loss_percentage':
            return self.packet_loss_percentage
        raise ValueError('Invalid key. Please use an attribute instead.')

    def as_dict(self):
        return {
            'connected': 1 if self.connected else 0,
            'rtt': list(self.rtts),
            'time_stamp': list(self.timestamps),
            'ping_interarrivals': list(self.ping_interarrivals),
            'packet_loss_percentage': self.packet_loss_percentage
        }


class PingTransmissionTimes(object):
    """A class that holds the timestamps for a packet sent via the ping command.

    Attributes:
        rtt: The round trip time for the packet sent.
        timestamp: The timestamp the packet started its trip.
    """

    def __init__(self, timestamp, rtt):
        self.rtt = rtt
        self.timestamp = timestamp


class _ListWrap(object):
    """A convenient helper class for treating list iterators as native lists."""

    def __init__(self, wrapped_list, func):
        self.__wrapped_list = wrapped_list
        self.__func = func

    def __getitem__(self, key):
        return self.__func(self.__wrapped_list[key])

    def __iter__(self):
        for item in self.__wrapped_list:
            yield self.__func(item)

    def __len__(self):
        return len(self.__wrapped_list)


class _PingInterarrivals(object):
    """A helper class for treating ping interarrivals as a native list."""

    def __init__(self, ping_entries):
        self.__ping_entries = ping_entries

    def __getitem__(self, key):
        return (self.__ping_entries[key + 1].timestamp -
                self.__ping_entries[key].timestamp)

    def __iter__(self):
        for index in range(len(self.__ping_entries) - 1):
            yield self[index]

    def __len__(self):
        return max(0, len(self.__ping_entries) - 1)


def get_ping_stats(src_device, dest_address, ping_duration, ping_interval,
                   ping_size):
    """Run ping to or from the DUT.

    The function computes either pings the DUT or pings a remote ip from
    DUT.

    Args:
        src_device: object representing device to ping from
        dest_address: ip address to ping
        ping_duration: timeout to set on the the ping process (in seconds)
        ping_interval: time between pings (in seconds)
        ping_size: size of ping packet payload
    Returns:
        ping_result: dict containing ping results and other meta data
    """
    ping_cmd = 'ping -w {} -i {} -s {} -D'.format(
        ping_duration,
        ping_interval,
        ping_size,
    )
    if isinstance(src_device, AndroidDevice):
        ping_cmd = '{} {}'.format(ping_cmd, dest_address)
        ping_output = src_device.adb.shell(
            ping_cmd, timeout=ping_duration + TEST_TIMEOUT, ignore_status=True)
    elif isinstance(src_device, ssh.connection.SshConnection):
        ping_cmd = 'sudo {} {}'.format(ping_cmd, dest_address)
        ping_output = src_device.run(ping_cmd, ignore_status=True).stdout
    else:
        raise TypeError(
            'Unable to ping using src_device of type %s.' % type(src_device))
    return PingResult(ping_output.splitlines())


@nonblocking
def get_ping_stats_nb(src_device, dest_address, ping_duration, ping_interval,
                      ping_size):
    return get_ping_stats(src_device, dest_address, ping_duration,
                          ping_interval, ping_size)


@nonblocking
def start_iperf_client_nb(iperf_client, iperf_server_address, iperf_args, tag,
                          timeout):
    return iperf_client.start(iperf_server_address, iperf_args, tag, timeout)


# Rssi Utilities
def empty_rssi_result():
    return collections.OrderedDict([('data', []), ('mean', None),
                                    ('stdev', None)])


def get_connected_rssi(dut,
                       num_measurements=1,
                       polling_frequency=SHORT_SLEEP,
                       first_measurement_delay=0):
    """Gets all RSSI values reported for the connected access point/BSSID.

    Args:
        dut: android device object from which to get RSSI
        num_measurements: number of scans done, and RSSIs collected
        polling_frequency: time to wait between RSSI measurements
    Returns:
        connected_rssi: dict containing the measurements results for
        all reported RSSI values (signal_poll, per chain, etc.) and their
        statistics
    """
    # yapf: disable
    connected_rssi = collections.OrderedDict(
        [('time_stamp', []),
         ('bssid', []), ('frequency', []),
         ('signal_poll_rssi', empty_rssi_result()),
         ('signal_poll_avg_rssi', empty_rssi_result()),
         ('chain_0_rssi', empty_rssi_result()),
         ('chain_1_rssi', empty_rssi_result())])
    # yapf: enable
    t0 = time.time()
    time.sleep(first_measurement_delay)
    for idx in range(num_measurements):
        measurement_start_time = time.time()
        connected_rssi['time_stamp'].append(measurement_start_time - t0)
        # Get signal poll RSSI
        status_output = dut.adb.shell(WPA_CLI_STATUS)
        match = re.search('bssid=.*', status_output)
        if match:
            bssid = match.group(0).split('=')[1]
            connected_rssi['bssid'].append(bssid)
        else:
            connected_rssi['bssid'].append(RSSI_ERROR_VAL)
        signal_poll_output = dut.adb.shell(SIGNAL_POLL)
        match = re.search('FREQUENCY=.*', signal_poll_output)
        if match:
            frequency = int(match.group(0).split('=')[1])
            connected_rssi['frequency'].append(frequency)
        else:
            connected_rssi['frequency'].append(RSSI_ERROR_VAL)
        match = re.search('RSSI=.*', signal_poll_output)
        if match:
            temp_rssi = int(match.group(0).split('=')[1])
            if temp_rssi == -9999 or temp_rssi == 0:
                connected_rssi['signal_poll_rssi']['data'].append(
                    RSSI_ERROR_VAL)
            else:
                connected_rssi['signal_poll_rssi']['data'].append(temp_rssi)
        else:
            connected_rssi['signal_poll_rssi']['data'].append(RSSI_ERROR_VAL)
        match = re.search('AVG_RSSI=.*', signal_poll_output)
        if match:
            connected_rssi['signal_poll_avg_rssi']['data'].append(
                int(match.group(0).split('=')[1]))
        else:
            connected_rssi['signal_poll_avg_rssi']['data'].append(
                RSSI_ERROR_VAL)
        # Get per chain RSSI
        per_chain_rssi = dut.adb.shell(STATION_DUMP)
        match = re.search('.*signal avg:.*', per_chain_rssi)
        if match:
            per_chain_rssi = per_chain_rssi[per_chain_rssi.find('[') +
                                            1:per_chain_rssi.find(']')]
            per_chain_rssi = per_chain_rssi.split(', ')
            connected_rssi['chain_0_rssi']['data'].append(
                int(per_chain_rssi[0]))
            connected_rssi['chain_1_rssi']['data'].append(
                int(per_chain_rssi[1]))
        else:
            connected_rssi['chain_0_rssi']['data'].append(RSSI_ERROR_VAL)
            connected_rssi['chain_1_rssi']['data'].append(RSSI_ERROR_VAL)
        measurement_elapsed_time = time.time() - measurement_start_time
        time.sleep(max(0, polling_frequency - measurement_elapsed_time))

    # Compute mean RSSIs. Only average valid readings.
    # Output RSSI_ERROR_VAL if no valid connected readings found.
    for key, val in connected_rssi.copy().items():
        if 'data' not in val:
            continue
        filtered_rssi_values = [x for x in val['data'] if not math.isnan(x)]
        if filtered_rssi_values:
            connected_rssi[key]['mean'] = statistics.mean(filtered_rssi_values)
            if len(filtered_rssi_values) > 1:
                connected_rssi[key]['stdev'] = statistics.stdev(
                    filtered_rssi_values)
            else:
                connected_rssi[key]['stdev'] = 0
        else:
            connected_rssi[key]['mean'] = RSSI_ERROR_VAL
            connected_rssi[key]['stdev'] = RSSI_ERROR_VAL
    return connected_rssi


@nonblocking
def get_connected_rssi_nb(dut,
                          num_measurements=1,
                          polling_frequency=SHORT_SLEEP,
                          first_measurement_delay=0):
    return get_connected_rssi(dut, num_measurements, polling_frequency,
                              first_measurement_delay)


def get_scan_rssi(dut, tracked_bssids, num_measurements=1):
    """Gets scan RSSI for specified BSSIDs.

    Args:
        dut: android device object from which to get RSSI
        tracked_bssids: array of BSSIDs to gather RSSI data for
        num_measurements: number of scans done, and RSSIs collected
    Returns:
        scan_rssi: dict containing the measurement results as well as the
        statistics of the scan RSSI for all BSSIDs in tracked_bssids
    """
    scan_rssi = collections.OrderedDict()
    for bssid in tracked_bssids:
        scan_rssi[bssid] = empty_rssi_result()
    for idx in range(num_measurements):
        scan_output = dut.adb.shell(SCAN)
        time.sleep(MED_SLEEP)
        scan_output = dut.adb.shell(SCAN_RESULTS)
        for bssid in tracked_bssids:
            bssid_result = re.search(
                bssid + '.*', scan_output, flags=re.IGNORECASE)
            if bssid_result:
                bssid_result = bssid_result.group(0).split('\t')
                scan_rssi[bssid]['data'].append(int(bssid_result[2]))
            else:
                scan_rssi[bssid]['data'].append(RSSI_ERROR_VAL)
    # Compute mean RSSIs. Only average valid readings.
    # Output RSSI_ERROR_VAL if no readings found.
    for key, val in scan_rssi.items():
        filtered_rssi_values = [x for x in val['data'] if not math.isnan(x)]
        if filtered_rssi_values:
            scan_rssi[key]['mean'] = statistics.mean(filtered_rssi_values)
            if len(filtered_rssi_values) > 1:
                scan_rssi[key]['stdev'] = statistics.stdev(
                    filtered_rssi_values)
            else:
                scan_rssi[key]['stdev'] = 0
        else:
            scan_rssi[key]['mean'] = RSSI_ERROR_VAL
            scan_rssi[key]['stdev'] = RSSI_ERROR_VAL
    return scan_rssi


@nonblocking
def get_scan_rssi_nb(dut, tracked_bssids, num_measurements=1):
    return get_scan_rssi(dut, tracked_bssids, num_measurements)


## Attenuator Utilities
def atten_by_label(atten_list, path_label, atten_level):
    """Attenuate signals according to their path label.

    Args:
        atten_list: list of attenuators to iterate over
        path_label: path label on which to set desired attenuation
        atten_level: attenuation desired on path
    """
    for atten in atten_list:
        if path_label in atten.path:
            atten.set_atten(atten_level)


def get_server_address(ssh_connection, subnet):
    """Get server address on a specific subnet

    Args:
        ssh_connection: object representing server for which we want an ip
        subnet: string in ip address format, i.e., xxx.xxx.xxx.xxx,
        representing the subnet of interest.
    """
    subnet_str = subnet.split('.')[:-1]
    subnet_str = '.'.join(subnet_str)
    cmd = "ifconfig | grep 'inet addr:{}'".format(subnet_str)
    try:
        if_output = ssh_connection.run(cmd).stdout
        ip_line = if_output.split('inet addr:')[1]
        ip_address = ip_line.split(' ')[0]
    except:
        logging.warning('Could not find ip in requested subnet.')
    return ip_address
