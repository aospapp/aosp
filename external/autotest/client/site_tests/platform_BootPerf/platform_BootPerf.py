# Lint as: python2, python3
# Copyright (c) 2009 The Chromium OS Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import glob
import logging
import re
import os
import shutil
import time
import utils

from autotest_lib.client.bin import test
from autotest_lib.client.common_lib import error

class platform_BootPerf(test.test):
    """Test to gather recorded boot time statistics.

    The primary function of this test is to gather a rather large
    assortment of performance keyvals that capture timing and disk
    usage statistics associated with the most recent boot or reboot.

    The test calculates some or all of the following keyvals:
      * seconds_kernel_to_startup
      * seconds_kernel_to_startup_done
      * seconds_kernel_to_chrome_exec
      * seconds_kernel_to_chrome_main
      * seconds_kernel_to_signin_start
      * seconds_kernel_to_signin_wait
      * seconds_kernel_to_signin_users
      * seconds_kernel_to_login
      * seconds_kernel_to_network
      * seconds_startup_to_chrome_exec
      * seconds_chrome_exec_to_login
      * rdbytes_kernel_to_startup
      * rdbytes_kernel_to_startup_done
      * rdbytes_kernel_to_chrome_exec
      * rdbytes_kernel_to_chrome_main
      * rdbytes_kernel_to_login
      * rdbytes_startup_to_chrome_exec
      * rdbytes_chrome_exec_to_login
      * seconds_power_on_to_kernel
      * seconds_power_on_to_login
      * seconds_shutdown_time
      * seconds_reboot_time
      * seconds_reboot_error
      * mhz_primary_cpu

    """

    version = 2

    # Names of keyvals, their associated bootstat events, and 'Required' flag.
    # Test fails if a required event is not found.
    # Each event samples statistics measured since kernel startup
    # at a specific moment on the boot critical path:
    #   pre-startup - The start of the `chromeos_startup` script;
    #     roughly, the time when /sbin/init emits the `startup`
    #     Upstart event.
    #   post-startup - Completion of the `chromeos_startup` script.
    #   chrome-exec - The moment when session_manager exec's the
    #     first Chrome process.
    #   chrome-main - The moment when the first Chrome process
    #     begins executing in main().
    #   kernel_to_signin_start - The moment when LoadPage(loginSceenURL)
    #     is called, i.e. initialization starts.
    #   kernel_to_signin_wait - The moment when UI thread has finished signin
    #     screen initialization and now waits until JS sends "ready" event.
    #   kernel_to_signin_users - The moment when UI thread receives "ready" from
    #     JS code. So V8 is initialized and running, etc...
    #   kernel_to_login - The moment when user can actually see signin UI.
    #   kernel_to_android_start - The moment when Android is started.
    _EVENT_KEYVALS = [
        # N.B.  Keyval attribute names go into a database that
        # truncates after 30 characters.  The key names below are
        # prefixed with 8 characters, either 'seconds_' or
        # 'rdbytes_', so we have 22 characters wiggle room.
        #
        # ----+----1----+----2--
        ('kernel_to_startup',       'pre-startup',                     True),
        ('kernel_to_startup_done',  'post-startup',                    True),
        ('kernel_to_chrome_exec',   'chrome-exec',                     True),
        ('kernel_to_chrome_main',   'chrome-main',                     True),
        # These two events do not happen if device is in OOBE.
        ('kernel_to_signin_start',  'login-start-signin-screen',       False),
        ('kernel_to_signin_wait',
            'login-wait-for-signin-state-initialize',                  False),
        # This event doesn't happen if device has no users.
        ('kernel_to_signin_users',  'login-send-user-list',            False),
        ('kernel_to_login',         'login-prompt-visible',            True),
        # Not all boards support ARC.
        ('kernel_to_android_start', 'android-start',                   False)
    ]

    _CPU_FREQ_FILE = ('/sys/devices/system/cpu/cpu0'
                      '/cpufreq/cpuinfo_max_freq')

    _UPTIME_PREFIX = 'uptime-'
    _DISK_PREFIX = 'disk-'
    _FW_TIMESTAMPS = 'cbmem-timestamps'


    _BOOTSTAT_ARCHIVE_GLOB = '/var/log/metrics/shutdown.[0-9]*'
    _UPTIME_FILE_GLOB = os.path.join('/tmp', _UPTIME_PREFIX + '*')
    _DISK_FILE_GLOB = os.path.join('/tmp', _DISK_PREFIX + '*')

    # The name of this file has changed starting with linux-3.19.
    # Use a glob to snarf up all existing records.
    _RAMOOPS_FILE_GLOB = "/sys/fs/pstore/console-ramoops*"


    def _copy_timestamp_files(self):
        """Copy raw data files to the test results."""
        statlist = (glob.glob(self._UPTIME_FILE_GLOB) +
                            glob.glob(self._DISK_FILE_GLOB))
        for fname in statlist:
            shutil.copy(fname, self.resultsdir)

    def _copy_console_ramoops(self):
        """Copy console_ramoops from previous reboot."""
        # If reboot was misbehaving, looking at ramoops may provide clues.
        for path in glob.glob(self._RAMOOPS_FILE_GLOB):
            try:
                shutil.copy(path, self.resultsdir)
                break
            except Exception:
                pass

    def _store_fw_timestamps(self):
        """Store detailed firmware timestamps for debugging."""
        with open(os.path.join(self.resultsdir, self._FW_TIMESTAMPS),
                  'w') as f:
            utils.run('cbmem -t', stdout_tee=f)

    def _parse_bootstat(self, filename, fieldnum, required=False):
        """Read values from a bootstat event file.

        Each line of a bootstat event file represents one occurrence
        of the event.  Each line is a copy of the content of
        /proc/uptime ("uptime-" files) or /sys/block/<dev>/stat
        ("disk-" files), captured at the time of the occurrence.
        For either kind of file, each line is a blank separated list
        of fields.

        The given event file can contain either uptime or disk data.
        This function reads all lines (occurrences) in the event
        file, and returns the value of the given field

        @param filename         Filename of the bootstat event.
        @param fieldnum         Which field of the file.
        @return                 List of values of `fieldnum` for
                                all occurrences in the file.
        @raises error.TestFail  Raised if the event file is missing,
                                unreadable, or malformed.

        """
        try:
            # crbug.com/1098635: racing with chrome browser
            #  See external/chromium_org/chrome/browser/chromeos/boot_times_loader.cc
            if required:
                cnt = 0
                while cnt < 30:
                    if os.path.exists(filename):
                        break
                    time.sleep(1)
                    cnt += 1

                if cnt :
                    logging.warning("Waited %d seconds for bootstat file: %s", cnt, filename)

            with open(filename) as statfile:
                values = list(map(lambda l: float(l.split()[fieldnum]),
                             statfile.readlines()))
            return values
        except IOError:
            raise error.TestFail('Failed to read bootstat file "%s"' %
                                 filename)


    def _parse_uptime(self, eventname, bootstat_dir='/tmp', index=0, required=False):
        """Return time since boot for a bootstat event.

        @param eventname        Name of the bootstat event.
        @param boostat_dir      Directory containing the bootstat
                                files.
        @param index            Index of which occurrence of the event
                                to select.
        @param required         If the parameter is required, wait for it.
        @return                 Time since boot for the selected
                                event.

        """
        event_file = os.path.join(bootstat_dir,
                                  self._UPTIME_PREFIX) + eventname
        return self._parse_bootstat(event_file, 0)[index]


    def _parse_diskstat(self, eventname, bootstat_dir='/tmp', index=0):
        """Return sectors read since boot for a bootstat event.

        @param eventname        Name of the bootstat event.
        @param boostat_dir      Directory containing the bootstat files.
        @param index            Index of which occurrence of the event
                                to select.
        @return                 Number of sectors read since boot for
                                the selected event.

        """
        event_file = os.path.join(bootstat_dir,
                                  self._DISK_PREFIX) + eventname
        return self._parse_bootstat(event_file, 2)[index]


    def _gather_firmware_boot_time(self, results):
        """Read and report firmware startup time.

        `cbmem -t` reports firmware boot time with format of
        'Total Time: {comma separated microseconds}'. Read the time
        and record it in `results` as the keyval
        seconds_power_on_to_kernel.

        @param results  Keyvals dictionary.

        """

        data = utils.system_output('cbmem -t | grep \'Total Time:\' |'
                                   'awk \'{print $NF}\'')
        firmware_time = round(float(data.replace(',', '')) / (1000 * 1000), 2)

        boot_time = results['seconds_kernel_to_login']
        results['seconds_power_on_to_kernel'] = firmware_time
        results['seconds_power_on_to_login'] = (
                round(firmware_time + boot_time, 2))


    def _gather_time_keyvals(self, results):
        """Read and report boot time keyvals.

        Read "seconds since kernel startup" from the bootstat files
        for the events named in `_EVENT_KEYVALS`, and store the
        values as perf keyvals.  The following keyvals are recorded:
          * seconds_kernel_to_startup
          * seconds_kernel_to_startup_done
          * seconds_kernel_to_chrome_exec
          * seconds_kernel_to_chrome_main
          * seconds_kernel_to_login
          * seconds_kernel_to_network
        All of these keyvals are considered mandatory, except
        for seconds_kernel_to_network.

        @param results          Keyvals dictionary.
        @raises error.TestFail  Raised if any mandatory keyval can't
                                be determined.

        """
        for keyval_name, event_name, required in self._EVENT_KEYVALS:
            key = 'seconds_' + keyval_name
            try:
                results[key] = self._parse_uptime(event_name, required=required)
            except error.TestFail:
                if required:
                    raise;

        # Not all 'uptime-network-*-ready' files necessarily exist;
        # probably there's only one.  We go through a list of
        # possibilities and pick the earliest one we find.  We're not
        # looking for 3G here, so we're not guaranteed to find any
        # file.
        network_ready_events = [
            'network-wifi-ready',
            'network-ethernet-ready'
        ]
        network_ready_timestamp = float('inf')
        for event_name in network_ready_events:
            metric_name = ('seconds_kernel_to_' +
                           event_name.replace('-', '_'))
            try:
                network_time = self._parse_uptime(event_name)
                results[metric_name] = network_time
                if network_time < network_ready_timestamp:
                    network_ready_timestamp = network_time
                    results['seconds_kernel_to_network'] = network_time
            except error.TestFail:
                pass


    def _gather_disk_keyvals(self, results):
        """Read and report disk read keyvals.

        Read "sectors read since kernel startup" from the bootstat
        files for the events named in `_EVENT_KEYVALS`, convert the
        values to "bytes read since boot", and store the values as
        perf keyvals.  The following keyvals are recorded:
          * rdbytes_kernel_to_startup
          * rdbytes_kernel_to_startup_done
          * rdbytes_kernel_to_chrome_exec
          * rdbytes_kernel_to_chrome_main
          * rdbytes_kernel_to_login

        Disk statistics are reported in units of 512 byte sectors;
        we convert the keyvals to bytes so that downstream consumers
        don't have to ask "How big is a sector?".

        @param results  Keyvals dictionary.

        """
        # We expect an error when reading disk statistics for the
        # "chrome-main" event because Chrome (not bootstat) generates
        # that event, and it doesn't include the disk statistics.
        # We get around that by ignoring all errors.
        for keyval_name, event_name, required in self._EVENT_KEYVALS:
            try:
                key = 'rdbytes_' + keyval_name
                results[key] = 512 * self._parse_diskstat(event_name)
            except Exception:
                pass


    def _calculate_timeval(self, event, t0, t1, t_uptime):
        """Estimate the absolute time of a time since boot.

        Input values `event` and `t_uptime` are times measured as
        seconds since boot (for the same boot event, as from
        /proc/uptime).  The input values `t0` and `t1` are two
        values measured as seconds since the epoch.  The three "t"
        values were sampled in the order `t0`, `t_uptime`, `t1`.

        Estimate the time of `event` measured as seconds since the
        epoch.  Also estimate the worst-case error based on the time
        elapsed between `t0` and `t1`.

        All values are floats.  The precision of `event` and
        `t_uptime` is expected to be kernel jiffies (i.e. one
        centisecond).  The output result is rounded to the nearest
        jiffy.

        @param event    A time to be converted from "seconds since
                        boot" into "seconds since the epoch".
        @param t0       A reference time earlier than time `t1`, and
                        measured as "seconds since the epoch".
        @param t1       A reference time later than time `t0`, and
                        measured as "seconds since the epoch".
        @param t_uptime A reference time measured as "seconds since
                        boot", in between time `t0` and `t1`.

        @return         An estimate of the time of `event` measured
                        as seconds since the epoch, rounded to the
                        nearest jiffy.

        """
        # Floating point geeks may argue that these calculations
        # don't guarantee the promised precision:  I don't care;
        # it's good enough.
        boot_timeval = round((t0 + t1) / 2, 2) - t_uptime
        error = (t1 - t0) / 2
        return boot_timeval + event, error


    def _gather_reboot_keyvals(self, results):
        """Read and report shutdown and reboot times.

        The shutdown process saves all bootstat files in /var/log,
        plus it saves a timestamp file that can be used to convert
        "time since boot" into times in UTC.  Read the saved files
        from the most recent shutdown, and use them to calculate
        the time spent from the start of that shutdown until the
        completion of the most recent boot.  Record these keyvals:
          * seconds_shutdown_time
          * seconds_reboot_time
          * seconds_reboot_error

        @param results  Keyvals dictionary.

        """
        bootstat_archives = glob.glob(self._BOOTSTAT_ARCHIVE_GLOB)
        if not bootstat_archives:
            return
        bootstat_dir = max(bootstat_archives)
        boot_id = open("/proc/sys/kernel/random/boot_id", "r").read()
        didrun_path = os.path.join(bootstat_dir, "bootperf_ran")
        if not os.path.exists(didrun_path):
            with open(didrun_path, "w") as didrun:
                didrun.write(boot_id)
        elif open(didrun_path, "r").read() != boot_id:
            logging.warning("Ignoring reboot based on stale shutdown %s",
                         os.path.basename(bootstat_dir))
            return
        timestamp_path = os.path.join(bootstat_dir, 'timestamp')
        try:
            with open(timestamp_path) as timestamp:
                archive_t0 = float(timestamp.readline())
                archive_t1 = float(timestamp.readline())
        except IOError:
            raise error.TestFail('Failed to read "%s"' % timestamp_path)
        archive_uptime = self._parse_uptime('archive',
                                            bootstat_dir=bootstat_dir)
        shutdown_uptime = self._parse_uptime('ui-post-stop',
                                             bootstat_dir=bootstat_dir,
                                             index=-1)
        shutdown_timeval, shutdown_error = self._calculate_timeval(
                shutdown_uptime, archive_t0, archive_t1, archive_uptime)
        boot_t0 = time.time()
        with open('/proc/uptime') as uptime_file:
            uptime = float(uptime_file.readline().split()[0])
        boot_t1 = time.time()
        boot_timeval, boot_error = self._calculate_timeval(
                results['seconds_kernel_to_login'],
                boot_t0, boot_t1, uptime)
        reboot_time = round(boot_timeval - shutdown_timeval, 2)
        poweron_time = results['seconds_power_on_to_login']
        shutdown_time = round(reboot_time - poweron_time, 2)
        results['seconds_reboot_time'] = reboot_time
        results['seconds_reboot_error'] = shutdown_error + boot_error
        results['seconds_shutdown_time'] = shutdown_time


    def _calculate_diff(self, results):
        barriers = ['startup', 'chrome_exec', 'login']
        for i in range(len(barriers) - 1):
            for type in ['seconds', 'rdbytes']:
                begin = '%s_kernel_to_%s' % (type, barriers[i])
                end = '%s_kernel_to_%s' % (type, barriers[i + 1])
                if begin in results and end in results:
                    diff_name = '%s_%s_to_%s' % (type, barriers[i], barriers[i + 1])
                    results[diff_name] = results[end] - results[begin]


    def run_once(self):
        """Gather boot time statistics.

        Every shutdown and boot creates `bootstat` files with
        summary statistics for time elapsed and disk usage.  Gather
        the values reported for shutdown, boot time and network
        startup time, and record them as perf keyvals.

        Additionally, gather information about firmware startup time
        from various sources, and record the times as perf keyvals.

        Finally, copy the raw data files to the results directory
        for reference.

        """
        # `results` is the keyvals dictionary
        results = {}

        self._gather_time_keyvals(results)
        self._gather_disk_keyvals(results)
        self._gather_firmware_boot_time(results)
        self._gather_reboot_keyvals(results)
        self._calculate_diff(results)

        self._copy_timestamp_files()
        self._copy_console_ramoops()
        self._store_fw_timestamps()

        self.write_perf_keyval(results)

        if utils.system('crossystem mainfw_type?normal',
                        ignore_status=True) != 0:
            raise error.TestNAError(
                    'Firmware boot times are not accurate in developer mode. '
                    'Please run this test in normal mode.')
        if 'EC returned from reboot' in utils.system_output('cbmem -1'):
            raise error.TestNAError(
                    'Firmware boot times should be measured without an EC reboot. '
                    'Please warm reboot this system by running the "reboot" userspace command, then rerun the test.'
            )
        # This is looking for the LB_TAG_SERIAL entry in the coreboot table.
        # 0x0000000f = tag, 0x00000020 = size, 0x0001c200 = 115200 baud.
        if re.search('\x0f\0\0\0\x20\0\0\0........\0\xc2\x01\0',
                     utils.system_output('cbmem -r 43425442')):
            raise error.TestNAError(
                    'Firmware boot times should be measured without serial output. '
                    'Please rerun this test with a production image (image.bin, not image.dev.bin).'
            )
