import logging
import re
import time

from acts.libs.metrics.metric import AsyncRecordableMetric

CPU_INFO_REGEX = re.compile(
    '(?P<total>[^%]+)% TOTAL:'
    ' (?P<user>[^%]+)% user'
    ' \+ (?P<kernel>[^%]+)% kernel'
    ' \+ (?P<iowait>[^%]+)% iowait'
    ' \+ (?P<irq>[^%]+)% irq'
    ' \+ (?P<softirq>[^%]+)% softirq')


class CpuMetric(AsyncRecordableMetric):
    """Metric for measuring the cpu usage of a phone over time."""

    def run_one_iteration(self):
        for android_device in self.test.android_devices:
            try:
                cpu_info = android_device.adb.shell('dumpsys cpuinfo')
                cpu_info_line = cpu_info.splitlines()[-1]
                current_time = time.time()

                match = CPU_INFO_REGEX.match(cpu_info_line)
                if match:
                    record = {
                        'total': float(match.group('total')) / 100.0,
                        'user': float(match.group('user')) / 100.0,
                        'kernel': float(match.group('kernel')) / 100.0,
                        'io': float(match.group('iowait')) / 100.0,
                        'irq': float(match.group('irq')) / 100.0,
                        'softirg': float(match.group('softirq')) / 100.0,
                    }

                    self.record(record, current_time, android_device.serial)
                else:
                    logging.error('Invalid format: %s', cpu_info_line)
            except Exception as e:
                serial = android_device.serial
                logging.exception(
                    'Could not record for android device %s', serial)
