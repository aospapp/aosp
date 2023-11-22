#!/usr/bin/env python3.5
#
#   Copyright 2019 - Google
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

import time
import re
import os
import logging

from acts import utils
from acts import signals
from acts.controllers.android_device import list_adb_devices
from acts.controllers.android_device import list_fastboot_devices
from acts.controllers.android_device import DEFAULT_QXDM_LOG_PATH
from acts.controllers.android_device import SL4A_APK_NAME
from acts.test_utils.wifi import wifi_test_utils as wutils
from acts.test_utils.tel import tel_test_utils as tutils
from acts.utils import get_current_epoch_time

WifiEnums = wutils.WifiEnums
PULL_TIMEOUT = 300
GNSSSTATUS_LOG_PATH = "/storage/emulated/0/Android/data/com.android.gpstool/files"
QXDM_MASKS = ["GPS-general.cfg", "GPS.cfg", "default.cfg"]


class GnssTestUtilsError(Exception):
    pass

def remount_device(ad):
    """Remount device file system to read and write.

    Args:
        ad: An AndroidDevice object.
    """
    remount_flag = 0
    for retries in range(2):
        ad.root_adb()
        remount_result = ad.adb.remount()
        ad.log.info("Attempt %d - %s" % (retries + 1, remount_result))
        if "remount succeeded" in remount_result or remount_flag == 1:
            break
        if ad.adb.getprop("ro.boot.veritymode") == "enforcing":
            remount_flag = 1
            disable_verity_result = ad.adb.disable_verity()
            ad.log.info("%s" % disable_verity_result)
            ad.reboot()
            ad.unlock_screen(password=None)

def enable_gnss_verbose_logging(ad):
    """Enable GNSS VERBOSE Logging and logd.

    Args:
        ad: An AndroidDevice object.
    """
    remount_device(ad)
    ad.log.info("Enable GNSS VERBOSE Logging and logd.")
    ad.adb.shell("echo DEBUG_LEVEL = 5 >> /vendor/etc/gps.conf")
    ad.adb.shell("echo log.tag.LocationManagerService=VERBOSE >> /data/local.prop")
    ad.adb.shell("echo log.tag.GnssLocationProvider=VERBOSE >> /data/local.prop")
    ad.adb.shell("echo log.tag.GnssMeasurementsProvider=VERBOSE >> /data/local.prop")
    ad.adb.shell("chmod 644 /data/local.prop")
    ad.adb.shell("setprop persist.logd.logpersistd logcatd")
    ad.adb.shell("setprop persist.vendor.radio.adb_log_on 1")
    ad.adb.shell("setprop log.tag.copresGcore VERBOSE")
    ad.adb.shell("sync")

def disable_xtra_throttle(ad):
    """Disable XTRA throttle will have no limit to download XTRA data.

    Args:
        ad: An AndroidDevice object.
    """
    remount_device(ad)
    ad.log.info("Disable XTRA Throttle.")
    ad.adb.shell("echo XTRA_TEST_ENABLED=1 >> /vendor/etc/gps.conf")
    ad.adb.shell("echo XTRA_THROTTLE_ENABLED=0 >> /vendor/etc/gps.conf")

def enable_supl_mode(ad):
    """Enable SUPL back on for next test item.

    Args:
        ad: An AndroidDevice object.
    """
    remount_device(ad)
    ad.log.info("Enable SUPL mode.")
    ad.adb.shell("echo SUPL_MODE=1 >> /etc/gps_debug.conf")

def disable_supl_mode(ad):
    """Kill SUPL to test XTRA only test item.

    Args:
        ad: An AndroidDevice object.
    """
    remount_device(ad)
    ad.log.info("Disable SUPL mode.")
    ad.adb.shell("echo SUPL_MODE=0 >> /etc/gps_debug.conf")
    ad.log.info("Reboot device to make changes take effect.")
    ad.reboot()
    ad.unlock_screen(password=None)

def kill_xtra_daemon(ad):
    """Kill XTRA daemon to test SUPL only test item.

    Args:
        ad: An AndroidDevice object.
    """
    ad.root_adb()
    ad.log.info("Disable XTRA-daemon until next reboot.")
    ad.adb.shell("killall xtra-daemon")

def disable_private_dns_mode(ad):
    """Due to b/118365122, it's better to disable private DNS mode while
       testing. 8.8.8.8 private dns sever is unstable now, sometimes server
       will not response dns query suddenly.

    Args:
        ad: An AndroidDevice object.
    """
    tutils.get_operator_name(ad.log, ad, subId=None)
    if ad.adb.shell("settings get global private_dns_mode") != "off":
        ad.log.info("Disable Private DNS mode.")
        ad.adb.shell("settings put global private_dns_mode off")

def _init_device(ad):
    """Init GNSS test devices.

    Args:
        ad: An AndroidDevice object.
    """
    set_mobile_data(ad, True)
    disable_private_dns_mode(ad)
    tutils.synchronize_device_time(ad)
    enable_gnss_verbose_logging(ad)
    disable_xtra_throttle(ad)
    enable_supl_mode(ad)
    ad.adb.shell("svc power stayon true")
    ad.adb.shell("settings put system screen_off_timeout 1800000")
    wutils.wifi_toggle_state(ad, False)
    ad.log.info("Setting Bluetooth state to False")
    ad.droid.bluetoothToggleState(False)
    set_gnss_qxdm_mask(ad, QXDM_MASKS)
    check_location_service(ad)
    set_wifi_and_bt_scanning(ad, True)
    ad.reboot()
    ad.unlock_screen(password=None)

def connect_to_wifi_network(ad, network):
    """Connection logic for open and psk wifi networks.

    Args:
        ad: An AndroidDevice object.
        network: Dictionary with network info.
    """
    SSID = network[WifiEnums.SSID_KEY]
    ad.ed.clear_all_events()
    wutils.start_wifi_connection_scan(ad)
    scan_results = ad.droid.wifiGetScanResults()
    wutils.assert_network_in_list({WifiEnums.SSID_KEY: SSID}, scan_results)
    wutils.wifi_connect(ad, network, num_of_tries=5)

def set_wifi_and_bt_scanning(ad, state=True):
    """Set Wi-Fi and Bluetooth scanning on/off in Settings -> Location

    Args:
        ad: An AndroidDevice object.
        state: State for "Wi-Fi and Bluetooth scanning".
        If state is True, turn on "Wi-Fi and Bluetooth scanning".
        If state is False, turn off "Wi-Fi and Bluetooth scanning".
    """
    ad.root_adb()
    if state:
        ad.adb.shell("settings put global wifi_scan_always_enabled 1")
        ad.adb.shell("settings put global ble_scan_always_enabled 1")
        ad.log.info("Wi-Fi and Bluetooth scanning are enabled")
    else:
        ad.adb.shell("settings put global wifi_scan_always_enabled 0")
        ad.adb.shell("settings put global ble_scan_always_enabled 0")
        ad.log.info("Wi-Fi and Bluetooth scanning are disabled")

def check_location_service(ad):
    """Set location service on.
       Verify if location service is available.

    Args:
        ad: An AndroidDevice object.

    Return:
        True : location service is on.
        False : location service is off.
    """
    utils.set_location_service(ad, True)
    out = ad.adb.shell("settings get secure location_providers_allowed")
    ad.log.info("Current Location Provider >> %s" % out)
    if "gps,network" in out:
        return True
    return False

def clear_logd_gnss_qxdm_log(ad):
    """Clear /data/misc/logd,
    /storage/emulated/0/Android/data/com.android.gpstool/files and
    /data/vendor/radio/diag_logs/logs from previous test item then reboot.

    Args:
        ad: An AndroidDevice object.
    """
    remount_device(ad)
    ad.log.info("Clear Logd, GNSS and QXDM Log from previous test item.")
    ad.adb.shell("rm -rf /data/misc/logd", ignore_status=True)
    ad.adb.shell("rm -rf %s" % GNSSSTATUS_LOG_PATH, ignore_status=True)
    output_path = os.path.join(DEFAULT_QXDM_LOG_PATH, "logs")
    ad.adb.shell("rm -rf %s" % output_path, ignore_status=True)
    ad.reboot()
    ad.unlock_screen(password=None)

def get_gnss_qxdm_log(ad, test_name=""):
    """Get /storage/emulated/0/Android/data/com.android.gpstool/files and
    /data/vendor/radio/diag_logs/logs for failed test item.

    Args:
        ad: An AndroidDevice object.
    """
    log_path_base = getattr(logging, "log_path", "/tmp/logs")
    log_path = os.path.join(log_path_base, "AndroidDevice%s" % ad.serial)
    utils.create_dir(log_path)
    gnss_log_path = os.path.join(log_path, test_name, "gnssstatus_log_%s_%s"
                                 % (ad.model, ad.serial))
    utils.create_dir(gnss_log_path)
    ad.log.info("Pull GnssStatus Log to %s" % gnss_log_path)
    ad.adb.pull("%s %s" % (GNSSSTATUS_LOG_PATH, gnss_log_path),
                timeout=PULL_TIMEOUT, ignore_status=True)
    output_path = os.path.join(DEFAULT_QXDM_LOG_PATH, "logs")
    file_count = ad.adb.shell("find %s -type f -iname *.qmdl | wc -l" % output_path)
    if not int(file_count) == 0:
        qxdm_log_path = os.path.join(log_path, test_name, "QXDM_%s_%s"
                                     % (ad.model, ad.serial))
        utils.create_dir(qxdm_log_path)
        ad.log.info("Pull QXDM Log %s to %s" % (output_path, qxdm_log_path))
        ad.adb.pull("%s %s" % (output_path, qxdm_log_path),
                    timeout=PULL_TIMEOUT, ignore_status=True)
        if ad.model == "sailfish" or ad.model == "marlin":
            ad.adb.pull("/firmware/radio/qdsp6m.qdb %s" % qxdm_log_path,
                        timeout=PULL_TIMEOUT, ignore_status=True)
        elif ad.model == "walleye":
            ad.adb.pull("/firmware/image/qdsp6m.qdb %s" % qxdm_log_path,
                        timeout=PULL_TIMEOUT, ignore_status=True)
        else:
            ad.adb.pull("/vendor/firmware_mnt/image/qdsp6m.qdb %s"
                        % qxdm_log_path, timeout=PULL_TIMEOUT, ignore_status=True)
    else:
        ad.log.error("QXDM file count is %d. There is no QXDM log on device."
                     % int(file_count))

def start_youtube_video(ad, url=None, retries=0):
    """Start youtube video and verify if audio is in music state.

    Args:
        ad: An AndroidDevice object.
        url: Website for youtube video
        retries: Retry times if audio is not in music state.

    Returns:
        True if youtube video is playing normally.
        False if youtube video is not playing properly.
    """
    ad.droid.setMediaVolume(25)
    for i in range(retries):
        ad.log.info("Open an youtube video - attempt %d" % (i+1))
        ad.adb.shell("am start -a android.intent.action.VIEW -d \"%s\"" % url)
        time.sleep(1)
        out = ad.adb.shell("dumpsys activity | grep \"NewVersionAvailableActivity\"")
        if out:
            ad.log.info("Skip Youtube New Version Update.")
            ad.send_keycode("BACK")
        if tutils.wait_for_state(ad.droid.audioIsMusicActive, True, 15, 1):
            ad.log.info("Started a video in youtube, audio is in MUSIC state")
            return True
        ad.log.info("Force-Stop youtube and reopen youtube again.")
        ad.force_stop_apk("com.google.android.youtube")
        time.sleep(1)
    ad.log.error("Started a video in youtube, but audio is not in MUSIC state")
    return False

def set_mobile_data(ad, state):
    """Set mobile data on or off and check mobile data state.

    Args:
        ad: An AndroidDevice object.
        state: True to enable mobile data. False to disable mobile data.
    """
    ad.root_adb()
    if state:
        ad.log.info("Enable mobile data.")
        ad.adb.shell("svc data enable")
    else:
        ad.log.info("Disable mobile data.")
        ad.adb.shell("svc data disable")
    time.sleep(5)
    out = int(ad.adb.shell("settings get global mobile_data"))
    if state and out == 1:
        ad.log.info("Mobile data is enabled and set to %d" % out)
    elif not state and out == 0:
        ad.log.info("Mobile data is disabled and set to %d" % out)
    else:
        ad.log.error("Mobile data is at unknown state and set to %d" % out)

def get_modem_ssr_crash_count(ad):
    """Check current modem SSR crash count.

    Args:
        ad: An AndroidDevice object.

    Returns:
        Times of current modem SSR crash count
    """
    crash_count = 0
    ad.send_keycode("HOME")
    ad.log.info("Check modem SSR crash count...")
    total_subsys = ad.adb.shell("ls /sys/bus/msm_subsys/devices/")
    for i in range(0, len(total_subsys.split())):
        crash_count = int(ad.adb.shell("cat /sys/bus/msm_subsys/devices/"
                                       "subsys%d/crash_count" % i))
        ad.log.info("subsys%d crash_count is %d" % (i, crash_count))
        if crash_count != 0:
            return crash_count
    return crash_count

def check_xtra_download(ad, begin_time):
    """Verify XTRA download success log message in logcat.

    Args:
        ad: An AndroidDevice object.
        begin_time: test begin time

    Returns:
        True: xtra_download if XTRA downloaded and injected successfully
        otherwise return False.
    """
    ad.send_keycode("HOME")
    logcat_results = ad.search_logcat("XTRA download success. "
                                      "inject data into modem", begin_time)
    if logcat_results:
        ad.log.info("%s" % logcat_results[-1]["log_message"])
        ad.log.info("XTRA downloaded and injected successfully.")
        return True
    ad.log.error("XTRA downloaded FAIL.")
    return False

def pull_gtw_gpstool(ad):
    """Pull GTW_GPSTool apk from device.

    Args:
        ad: An AndroidDevice object.
    """
    out = ad.adb.shell("pm path com.android.gpstool")
    result = re.search(r"package:(.*)", out)
    if not result:
        tutils.abort_all_tests(ad.log, "Couldn't find GTW GPSTool apk")
    else:
        GTW_GPSTool_apk = result.group(1)
        ad.log.info("Get GTW GPSTool apk from %s" % GTW_GPSTool_apk)
        apkdir = "/tmp/GNSS/"
        utils.create_dir(apkdir)
        ad.pull_files([GTW_GPSTool_apk], apkdir)

def reinstall_gtw_gpstool(ad):
    """Reinstall GTW_GPSTool apk.

    Args:
        ad: An AndroidDevice object.
    """
    ad.log.info("Re-install GTW GPSTool")
    ad.adb.install("-r -g /tmp/GNSS/base.apk")

def fastboot_factory_reset(ad):
    """Factory reset the device in fastboot mode.
       Pull sl4a apk from device. Terminate all sl4a sessions,
       Reboot the device to bootloader,
       factory reset the device by fastboot.
       Reboot the device. wait for device to complete booting
       Re-install and start an sl4a session.

    Args:
        ad: An AndroidDevice object.

    Returns:
        True if factory reset process complete.
    """
    status = True
    skip_setup_wizard = True
    out = ad.adb.shell("pm path %s" % SL4A_APK_NAME)
    result = re.search(r"package:(.*)", out)
    if not result:
        tutils.abort_all_tests(ad.log, "Couldn't find sl4a apk")
    else:
        sl4a_apk = result.group(1)
        ad.log.info("Get sl4a apk from %s" % sl4a_apk)
        ad.pull_files([sl4a_apk], "/tmp/")
    pull_gtw_gpstool(ad)
    tutils.stop_qxdm_logger(ad)
    ad.stop_services()
    attempts = 3
    for i in range(1, attempts + 1):
        try:
            if ad.serial in list_adb_devices():
                ad.log.info("Reboot to bootloader")
                ad.adb.reboot("bootloader", ignore_status=True)
                time.sleep(10)
            if ad.serial in list_fastboot_devices():
                ad.log.info("Factory reset in fastboot")
                ad.fastboot._w(timeout=300, ignore_status=True)
                time.sleep(30)
                ad.log.info("Reboot in fastboot")
                ad.fastboot.reboot()
            ad.wait_for_boot_completion()
            ad.root_adb()
            if ad.skip_sl4a:
                break
            if ad.is_sl4a_installed():
                break
            ad.log.info("Re-install sl4a")
            ad.adb.shell("settings put global verifier_verify_adb_installs 0")
            ad.adb.shell("settings put global package_verifier_enable 0")
            ad.adb.install("-r -g /tmp/base.apk")
            reinstall_gtw_gpstool(ad)
            time.sleep(10)
            break
        except Exception as e:
            ad.log.error(e)
            if i == attempts:
                tutils.abort_all_tests(ad.log, str(e))
            time.sleep(5)
    try:
        ad.start_adb_logcat()
    except Exception as e:
        ad.log.error(e)
    if skip_setup_wizard:
        ad.exit_setup_wizard()
    if ad.skip_sl4a:
        return status
    tutils.bring_up_sl4a(ad)
    set_gnss_qxdm_mask(ad, QXDM_MASKS)
    return status

def clear_aiding_data_by_gtw_gpstool(ad):
    """Launch GTW GPSTool and Clear all GNSS aiding data.
       Wait 5 seconds for GTW GPStool to clear all GNSS aiding
       data properly.

    Args:
        ad: An AndroidDevice object.
    """
    ad.log.info("Launch GTW GPSTool and Clear all GNSS aiding data")
    ad.adb.shell("am start -S -n com.android.gpstool/.GPSTool --es mode clear")
    time.sleep(10)

def start_gnss_by_gtw_gpstool(ad, state):
    """Start or stop GNSS on GTW_GPSTool.

    Args:
        ad: An AndroidDevice object.
        state: True to start GNSS. False to Stop GNSS.
    """
    if state:
        ad.adb.shell("am start -S -n com.android.gpstool/.GPSTool --es mode gps")
    if not state:
        ad.log.info("Stop GNSS on GTW_GPSTool.")
        ad.adb.shell("am broadcast -a com.android.gpstool.stop_gps_action")
    time.sleep(3)

def process_gnss_by_gtw_gpstool(ad, criteria):
    """Launch GTW GPSTool and Clear all GNSS aiding data
       Start GNSS tracking on GTW_GPSTool.

    Args:
        ad: An AndroidDevice object.
        criteria: Criteria for current test item.

    Returns:
        True: First fix TTFF are within criteria.
        False: First fix TTFF exceed criteria.
    """
    retries = 3
    for i in range(retries):
        begin_time = get_current_epoch_time()
        clear_aiding_data_by_gtw_gpstool(ad)
        ad.log.info("Start GNSS on GTW_GPSTool - attempt %d" % (i+1))
        start_gnss_by_gtw_gpstool(ad, True)
        for _ in range(10 + criteria):
            logcat_results = ad.search_logcat("First fixed", begin_time)
            if logcat_results:
                first_fixed = int(logcat_results[-1]["log_message"].split()[-1])
                ad.log.info("GNSS First fixed = %.3f seconds" % (first_fixed / 1000))
                if (first_fixed / 1000) <= criteria:
                    return True
                ad.log.error("DUT takes more than %d seconds to get location "
                             "fixed. Test Abort and Close GPS for next test "
                             "item." % criteria)
                start_gnss_by_gtw_gpstool(ad, False)
                return False
            time.sleep(1)
        start_gnss_by_gtw_gpstool(ad, False)
        if not ad.is_adb_logcat_on:
            ad.start_adb_logcat()
    ad.log.error("Test Abort. DUT can't get location fixed within %d attempts."
                 % retries)
    return False

def start_ttff_by_gtw_gpstool(ad, ttff_mode, iteration):
    """Identify which TTFF mode for different test items.

    Args:
        ad: An AndroidDevice object.
        ttff_mode: TTFF Test mode for current test item.
        iteration: Iteration of TTFF cycles.
    """
    if ttff_mode == "ws":
        ad.log.info("Wait 5 minutes to start TTFF Warm Start...")
        time.sleep(300)
    if ttff_mode == "cs":
        ad.log.info("Start TTFF Cold Start...")
        time.sleep(3)
    ad.adb.shell("am broadcast -a com.android.gpstool.ttff_action "
                 "--es ttff %s --es cycle %d" % (ttff_mode, iteration))

def process_ttff_by_gtw_gpstool(ad, begin_time):
    """Process and save TTFF results.

    Args:
        ad: An AndroidDevice object.
        begin_time: test begin time

    Returns:
        ttff_result: A list of saved TTFF seconds.
    """
    loop = 1
    ttff_result = []
    ttff_log_loop = []
    while True:
        stop_gps_results = ad.search_logcat("stop gps test()", begin_time)
        if stop_gps_results:
            ad.send_keycode("HOME")
            break
        crash_result = ad.search_logcat("Force finishing activity "
                                        "com.android.gpstool/.GPSTool", begin_time)
        if crash_result:
            ad.log.error("GPSTool crashed. Abort test.")
            break
        logcat_results = ad.search_logcat("write TTFF log", begin_time)
        if logcat_results:
            ttff_log = logcat_results[-1]["log_message"].split()
            if not ttff_log_loop:
                ttff_log_loop.append(ttff_log[8].split(":")[-1])
            elif ttff_log[8].split(":")[-1] == ttff_log_loop[loop-1]:
                continue
            if ttff_log[11] == "0.0":
                ad.log.error("Iteration %d = Timeout" % loop)
            else:
                ad.log.info("Iteration %d = %s seconds" % (loop, ttff_log[11]))
            ttff_log_loop.append(ttff_log[8].split(":")[-1])
            ttff_result.append(float(ttff_log[11]))
            loop += 1
        if not ad.is_adb_logcat_on:
            ad.start_adb_logcat()
    return ttff_result

def check_ttff_result(ad, ttff_result, ttff_mode, criteria):
    """Verify all TTFF results.

    Args:
        ad: An AndroidDevice object.
        ttff_result: A list of saved TTFF seconds.
        ttff_mode: TTFF Test mode for current test item.
        criteria: Criteria for current test item.

    Returns:
        True: All TTFF results are within criteria.
        False: One or more TTFF results exceed criteria or Timeout.
    """
    ad.log.info("%d iterations of TTFF %s tests finished."
                % (len(ttff_result), ttff_mode))
    ad.log.info("%s PASS criteria is %d seconds" % (ttff_mode, criteria))
    if len(ttff_result) == 0:
        ad.log.error("GTW_GPSTool didn't process TTFF properly.")
        return False
    elif any(float(ttff_result[i]) == 0.0 for i in range(len(ttff_result))):
        ad.log.error("One or more TTFF %s Timeout" % ttff_mode)
        return False
    elif any(float(ttff_result[i]) >= criteria for i in range(len(ttff_result))):
        ad.log.error("One or more TTFF %s are over test criteria %d seconds"
                     % (ttff_mode, criteria))
        return False
    ad.log.info("All TTFF %s are within test criteria %d seconds."
                % (ttff_mode, criteria))
    return True

def launch_google_map(ad):
    """Launch Google Map via intent.

    Args:
        ad: An AndroidDevice object.
    """
    ad.log.info("Launch Google Map.")
    try:
        ad.adb.shell("am start -S -n com.google.android.apps.maps/"
                     "com.google.android.maps.MapsActivity")
        ad.send_keycode("BACK")
        ad.force_stop_apk("com.google.android.apps.maps")
        ad.adb.shell("am start -S -n com.google.android.apps.maps/"
                     "com.google.android.maps.MapsActivity")
    except Exception as e:
        ad.log.error(e)
        raise signals.TestFailure("Failed to launch google map.")

def check_location_api(ad, retries):
    """Verify if GnssLocationProvider API reports location.

    Args:
        ad: An AndroidDevice object.
        retries: Retry time.

    Returns:
        True: GnssLocationProvider API reports location.
        otherwise return False.
    """
    for i in range(retries):
        begin_time = get_current_epoch_time()
        ad.log.info("Try to get location report from GnssLocationProvider API "
                    "- attempt %d" % (i+1))
        while get_current_epoch_time() - begin_time <= 30000:
            logcat_results = ad.search_logcat("REPORT_LOCATION", begin_time)
            if logcat_results:
                ad.log.info("%s" % logcat_results[-1]["log_message"])
                ad.log.info("GnssLocationProvider reports location successfully.")
                return True
        if not ad.is_adb_logcat_on:
            ad.start_adb_logcat()
    ad.log.error("GnssLocationProvider is unable to report location.")
    return False

def check_network_location(ad, retries, location_type):
    """Verify if NLP reports location after requesting via GPSTool.

    Args:
        ad: An AndroidDevice object.
        retries: Retry time.
        location_type: neworkLocationType of cell or wifi.

    Returns:
        True: NLP reports location.
        otherwise return False.
    """
    for i in range(retries):
        time.sleep(1)
        begin_time = get_current_epoch_time()
        ad.log.info("Try to get NLP status - attempt %d" % (i+1))
        ad.adb.shell("am start -S -n com.android.gpstool/.GPSTool --es mode nlp")
        while get_current_epoch_time() - begin_time <= 30000:
            logcat_results = ad.search_logcat(
                "LocationManagerService: incoming location: Location", begin_time)
            if logcat_results:
                for logcat_result in logcat_results:
                    if location_type in logcat_result["log_message"]:
                        ad.log.info(logcat_result["log_message"])
                        ad.send_keycode("BACK")
                        return True
        if not ad.is_adb_logcat_on:
            ad.start_adb_logcat()
        ad.send_keycode("BACK")
    ad.log.error("Unable to report network location \"%s\"." % location_type)
    return False

def set_attenuator_gnss_signal(ad, attenuator, atten_value):
    """Set attenuation value for different GNSS signal.

    Args:
        ad: An AndroidDevice object.
        attenuator: The attenuator object.
        atten_value: attenuation value
    """
    ad.log.info("Set attenuation value to \"%d\" for GNSS signal." % atten_value)
    try:
        attenuator[0].set_atten(atten_value)
        time.sleep(3)
        atten_val = int(attenuator[0].get_atten())
        ad.log.info("Current attenuation value is \"%d\"" % atten_val)
    except Exception as e:
        ad.log.error(e)
        raise signals.TestFailure("Failed to set attenuation for gnss signal.")

def set_battery_saver_mode(ad, state):
    """Enable or diable battery saver mode via adb.

    Args:
        ad: An AndroidDevice object.
        state: True is enable Battery Saver mode. False is disable.
    """
    ad.root_adb()
    if state:
        ad.log.info("Enable Battery Saver mode.")
        ad.adb.shell("cmd battery unplug")
        ad.adb.shell("settings put global low_power 1")
    else:
        ad.log.info("Disable Battery Saver mode.")
        ad.adb.shell("settings put global low_power 0")
        ad.adb.shell("cmd battery reset")

def set_gnss_qxdm_mask(ad, masks):
    """Find defined gnss qxdm mask and set as default logging mask.

    Args:
        ad: An AndroidDevice object.
        masks: Defined gnss qxdm mask.
    """
    try:
        for mask in masks:
            if not tutils.find_qxdm_log_mask(ad, mask):
                continue
            tutils.set_qxdm_logger_command(ad, mask)
            break
    except Exception as e:
        ad.log.error(e)
        raise signals.TestFailure("Failed to set any QXDM masks.")
