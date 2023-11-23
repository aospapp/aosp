#!/usr/bin/env python3.4
#
#   Copyright 2022 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the 'License');
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an 'AS IS' BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import collections
import logging
import os
import time

PCC_PRESET_MAPPING = {
    'N257': {
        'low': 2054999,
        'mid': 2079165,
        'high': 2090832
    },
    'N258': {
        'low': 2017499,
        'mid': 2043749,
        'high': 2057499
    },
    'N260': {
        'low': 2229999,
        'mid': 2254165,
        'high': 2265832
    },
    'N261': {
        'low': 2071667
    }
}

DUPLEX_MODE_TO_BAND_MAPPING = {
    'LTE': {
        'FDD': [
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 18, 19, 20, 21,
            22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 85, 252, 255
        ],
        'TDD': [
            33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46, 47, 48,
            50, 51, 53
        ]
    },
    'NR5G': {
        'FDD': [
            'N1', 'N2', 'N3', 'N5', 'N7', 'N8', 'N12', 'N13', 'N14', 'N18',
            'N20', 'N25', 'N26', 'N28', 'N30', 'N65', 'N66', 'N70', 'N71',
            'N74'
        ],
        'TDD': [
            'N34', 'N38', 'N39', 'N40', 'N41', 'N48', 'N50', 'N51', 'N53',
            'N77', 'N78', 'N79', 'N90', 'N257', 'N258', 'N259', 'N260', 'N261'
        ]
    },
}


def extract_test_id(testcase_params, id_fields):
    test_id = collections.OrderedDict(
        (param, testcase_params[param]) for param in id_fields)
    return test_id


def start_pixel_logger(ad):
    """Function to start pixel logger with default log mask.

    Args:
        ad: android device on which to start logger
    """

    try:
        ad.adb.shell(
            'rm -R /storage/emulated/0/Android/data/com.android.pixellogger/files/logs/logs/'
        )
    except:
        pass
    ad.adb.shell(
        'am startservice -a com.android.pixellogger.service.logging.LoggingService.ACTION_START_LOGGING'
    )


def stop_pixel_logger(ad, log_path, tag=None):
    """Function to stop pixel logger and retrieve logs

    Args:
        ad: android device on which to start logger
        log_path: location of saved logs
    """
    ad.adb.shell(
        'am startservice -a com.android.pixellogger.service.logging.LoggingService.ACTION_STOP_LOGGING'
    )
    logging.info('Waiting for Pixel log file')
    file_name = None
    file_size = 0
    previous_file_size = 0
    for idx in range(600):
        try:
            file = ad.adb.shell(
                'ls -l /storage/emulated/0/Android/data/com.android.pixellogger/files/logs/logs/'
            ).split(' ')
            file_name = file[-1]
            file_size = file[-4]
        except:
            file_name = None
            file_size = 0
        if file_name and file_size == previous_file_size:
            logging.info('Log file found after {}s.'.format(idx))
            break
        else:
            previous_file_size = file_size
            time.sleep(1)
    try:
        local_file_name = '{}_{}'.format(file_name, tag) if tag else file_name
        local_path = os.path.join(log_path, local_file_name)
        ad.pull_files(
            '/storage/emulated/0/Android/data/com.android.pixellogger/files/logs/logs/{}'
            .format(file_name), log_path)
        return local_path
    except:
        logging.error('Could not pull pixel logs.')


def log_system_power_metrics(ad, verbose=1):
    # Log temperature sensors
    if verbose:
        temp_sensors = ad.adb.shell(
            'ls -1 /dev/thermal/tz-by-name/').splitlines()
    else:
        temp_sensors = ['BIG', 'battery', 'quiet_therm', 'usb_pwr_therm']
    temp_measurements = collections.OrderedDict()
    for sensor in temp_sensors:
        try:
            temp_measurements[sensor] = ad.adb.shell(
                'cat /dev/thermal/tz-by-name/{}/temp'.format(sensor))
        except:
            temp_measurements[sensor] = float('nan')
    logging.debug('Temperature sensor readings: {}'.format(temp_measurements))

    # Log mitigation items
    if verbose:
        mitigation_points = [
            "batoilo",
            "ocp_cpu1",
            "ocp_cpu2",
            "ocp_gpu",
            "ocp_tpu",
            "smpl_warn",
            "soft_ocp_cpu1",
            "soft_ocp_cpu2",
            "soft_ocp_gpu",
            "soft_ocp_tpu",
            "vdroop1",
            "vdroop2",
        ]
    else:
        mitigation_points = [
            "batoilo",
            "smpl_warn",
            "vdroop1",
            "vdroop2",
        ]

    parameters_f = ['count', 'capacity', 'timestamp', 'voltage']
    parameters_v = ['count', 'cap', 'time', 'volt']
    mitigation_measurements = collections.OrderedDict()
    for mp in mitigation_points:
        mitigation_measurements[mp] = collections.OrderedDict()
        for par_f, par_v in zip(parameters_f, parameters_v):
            mitigation_measurements[mp][par_v] = ad.adb.shell(
                'cat /sys/devices/virtual/pmic/mitigation/last_triggered_{}/{}_{}'
                .format(par_f, mp, par_v))
    logging.debug('Mitigation readings: {}'.format(mitigation_measurements))

    # Log power meter items
    power_meter_measurements = collections.OrderedDict()
    for device in ['device0', 'device1']:
        power_str = ad.adb.shell(
            'cat /sys/bus/iio/devices/iio:{}/lpf_power'.format(
                device)).splitlines()
        power_meter_measurements[device] = collections.OrderedDict()
        for line in power_str:
            if line.startswith('CH'):
                try:
                    line_split = line.split(', ')
                    power_meter_measurements[device][line_split[0]] = int(
                        line_split[1])
                except (IndexError, ValueError):
                    continue
            elif line.startswith('t='):
                try:
                    power_meter_measurements[device]['t_pmeter'] = int(
                        line[2:])
                except (IndexError, ValueError):
                    continue
            else:
                continue
        logging.debug(
            'Power Meter readings: {}'.format(power_meter_measurements))

        # Log battery items
        if verbose:
            battery_parameters = [
                "act_impedance", "capacity", "charge_counter", "charge_full",
                "charge_full_design", "current_avg", "current_now",
                "cycle_count", "health", "offmode_charger", "present",
                "rc_switch_enable", "resistance", "status", "temp",
                "voltage_avg", "voltage_now", "voltage_ocv"
            ]
        else:
            battery_parameters = [
                "capacity", "current_avg", "current_now", "voltage_avg",
                "voltage_now", "voltage_ocv"
            ]

        battery_meaurements = collections.OrderedDict()
        for par in battery_parameters:
            battery_meaurements['bat_{}'.format(par)] = ad.adb.shell(
                'cat /sys/class/power_supply/maxfg/{}'.format(par))
        logging.debug('Battery readings: {}'.format(battery_meaurements))
