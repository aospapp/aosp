#!/usr/bin/python

# Copyright (C) 2014 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import logging
import os.path
import select
import sys
import time
import collections
import socket
import gflags as flags  # http://code.google.com/p/python-gflags/
import pkgutil
import threading
import Queue

# queue to signal thread to exit
signal_exit_q = Queue.Queue()
signal_abort = Queue.Queue()

# let this script know about the power monitor implementations
sys.path = [os.path.basename(__file__)] + sys.path
available_monitors = [name for _, name, _ in pkgutil.iter_modules(
    [os.path.join(os.path.dirname(__file__),'power_monitors')]) if not name.startswith('_')]

APK = os.path.join( os.path.dirname(__file__), '..', "CtsVerifier.apk")

FLAGS = flags.FLAGS

# whether to use a strict delay to ensure screen is off, or attempt to use power measurements
USE_STRICT_DELAY = False
if USE_STRICT_DELAY:
    DELAY_SCREEN_OFF = 30.0
else:
    DELAY_SCREEN_OFF = 2.0

# whether to log data collected to a file for each sensor run:
LOG_DATA_TO_FILE = True

logging.getLogger().setLevel(logging.ERROR)

def do_import(name):
    """import a module by name dynamically"""
    mod = __import__(name)
    components = name.split('.')
    for comp in components[1:]:
        mod = getattr(mod, comp)
    return mod


class PowerTest:
    """Class to run a suite of power tests"""

    # Thresholds for max allowed power usage per sensor tested
    MAX_ACCEL_POWER = 0.08  # Amps
    MAX_MAG_POWER = 0.08  # Amps
    MAX_GYRO_POWER = 0.08  # Amps
    MAX_SIGMO_POWER = 0.08 # Amps
    MAX_STEP_COUNTER_POWER = 0.08 # Amps
    MAX_STEP_DETECTOR_POWER = 0.08 # Amps


    PORT = 0  # any available port
    DOMAIN_NAME = "/android/cts/powertest"
    SAMPLE_COUNT_NOMINAL = 1000
    RATE_NOMINAL = 100

    REQUEST_EXTERNAL_STORAGE = "EXTERNAL STORAGE?"
    REQUEST_EXIT = "EXIT"
    REQUEST_RAISE = "RAISE %s %s"
    REQUEST_USER_RESPONSE = "USER RESPONSE %s"
    REQUEST_SET_TEST_RESULT = "SET TEST RESULT %s %s %s"
    REQUEST_SENSOR_SWITCH = "SENSOR %s %s"
    REQUEST_SENSOR_AVAILABILITY = "SENSOR? %s"
    REQUEST_SCREEN_OFF = "SCREEN OFF"
    REQUEST_SHOW_MESSAGE = "MESSAGE %s"


    def __init__(self):
        power_monitors = do_import("power_monitors.%s" % FLAGS.power_monitor)
        testid = time.strftime("%d_%m_%Y__%H__%M_%S")
        self._power_monitor = power_monitors.Power_Monitor(log_file_id = testid)
        print ("Establishing connection to device...")
        self.setUsbEnabled(True)
        status = self._power_monitor.GetStatus()
        self._native_hz = status["sampleRate"] * 1000
        self._current_test = "None"
        self._external_storage = self.executeOnDevice(PowerTest.REQUEST_EXTERNAL_STORAGE,
                                                      reportErrors=True)

    def __del__(self):
        self.finalize()

    def finalize(self):
        """To be called upon termination of host connection to device"""
        if PowerTest.PORT > 0:
            # tell device side to exit connection loop, and remove the forwarding connection
            self.executeOnDevice(PowerTest.REQUEST_EXIT, reportErrors=False)
            self.executeLocal("adb forward --remove tcp:%d" % PowerTest.PORT)
        PowerTest.PORT = 0
        if self._power_monitor:
            self._power_monitor.Close()
            self._power_monitor = None

    def _send(self, msg, report_errors=True):
        """Connect to the device, send the given command, and then disconnect"""
        if PowerTest.PORT == 0:
            # on first attempt to send a command, connect to device via any open port number,
            # forwarding that port to a local socket on the device via adb
            logging.debug("Seeking port for communication...")
            # discover an open port
            dummysocket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            dummysocket.bind(("localhost", 0))
            (_, PowerTest.PORT) = dummysocket.getsockname()
            dummysocket.close()
            assert(PowerTest.PORT > 0)
            status = self.executeLocal("adb forward tcp:%d localabstract:%s" %
                    (PowerTest.PORT, PowerTest.DOMAIN_NAME))
            if report_errors:
                self.reportErrorIf(status != 0, msg="Unable to forward requests to client over adb")
            logging.info("Forwarding requests over local port %d" % PowerTest.PORT)

        link = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

        try:
            logging.debug("Connecting to device...")
            link.connect (("localhost", PowerTest.PORT))
            logging.debug("Connected.")
        except:
            if report_errors:
                self.reportErrorIf(True, msg="Unable to communicate with device: connection refused")
        logging.debug("Sending '%s'" % msg)
        link.sendall(msg)
        logging.debug("Getting response...")
        response = link.recv(4096)
        logging.debug("Got response '%s'" % response)
        link.close()
        return response

    def queryDevice(self, query):
        """Post a yes/no query to the device, return True upon successful query, False otherwise"""
        logging.info("Querying device with '%s'" % query)
        return self._send(query) == "OK"

    # TODO: abstract device communication (and string commands) into its own class
    def executeOnDevice(self, cmd , reportErrors=True):
        """Execute a (string) command on the remote device"""
        return self._send(cmd , reportErrors)

    def executeLocal(self, cmd, check_status=True):
        """execute a shell command locally (on the host)"""
        from subprocess import call
        status = call(cmd.split(' '))
        if status != 0 and check_status:
            logging.error("Failed to execute \"%s\"" % cmd)
        else:
            logging.debug("Executed \"%s\"" % cmd)
        return status

    def reportErrorIf(self, condition, msg):
        """Report an error condition to the device if condition is True.
        Will raise an exception on the device if condition is True"""
        if condition:
            try:
                logging.error("Exiting on error: %s" % msg)
                self.executeOnDevice(PowerTest.REQUEST_RAISE % (self._current_test, msg), False)
            except:

                logging.error("Unable to communicate with device to report error: %s" % msg)
                self.finalize()
                sys.exit(msg)
            raise Exception(msg)

    def setUsbEnabled(self, enabled, verbose=True):
        if enabled:
            val = 1
        else:
            val = 0
        self._power_monitor.SetUsbPassthrough(val)
        tries = 0

        # Sometimes command won't go through first time, particularly if immediately after a data
        # collection, so allow for retries
        status = self._power_monitor.GetStatus()
        while status is None and tries < 5:
            tries += 1
            time.sleep(2.0)
            logging.error("Retrying get status call...")
            self._power_monitor.StopDataCollection()
            self._power_monitor.SetUsbPassthrough(val)
            status = self._power_monitor.GetStatus()

        if enabled:
            if verbose: print("...USB enabled, waiting for device")
            self.executeLocal ("adb wait-for-device")
            if verbose: print("...device online")
        else:
            if verbose: logging.info("...USB disabled")
        # re-establish port forwarding
        if enabled and PowerTest.PORT > 0:
            status = self.executeLocal("adb forward tcp:%d localabstract:%s" %
                                       (PowerTest.PORT, PowerTest.DOMAIN_NAME))
            self.reportErrorIf(status != 0, msg="Unable to forward requests to client over adb")

    def waitForScreenOff(self):
        # disconnect of USB will cause screen to go on, so must wait (1 second more than screen off
        # timeout)
        if USE_STRICT_DELAY:
            time.sleep(DELAY_SCREEN_OFF)
            return

        # need at least 100 sequential clean low-power measurements to know screen is off
        THRESHOLD_COUNT_LOW_POWER = 100
        CURRENT_LOW_POWER_THRESHOLD = 0.060  # Amps
        TIMEOUT_SCREEN_OFF = 30 # this many tries at most
        count_good = 0
        tries = 0
        print("Waiting for screen off and application processor in suspend mode...")
        while count_good < THRESHOLD_COUNT_LOW_POWER:
            measurements = self.collectMeasurements(THRESHOLD_COUNT_LOW_POWER,
                                                      PowerTest.RATE_NOMINAL,
                                                      ensure_screen_off=False,
                                                      verbose=False)
            count_good = len([m for m in measurements
                               if m < CURRENT_LOW_POWER_THRESHOLD])
            tries += 1
            if count_good < THRESHOLD_COUNT_LOW_POWER and measurements:
                print("Current usage: %.2f mAmps. Device is probably not in suspend mode.   Waiting..." %
                      (1000.0*(sum(measurements)/len(measurements))))
            if tries >= TIMEOUT_SCREEN_OFF:
                # TODO: dump the state of sensor service to identify if there are features using sensors
                self.reportErrorIf(tries>=TIMEOUT_SCREEN_OFF,
                    msg="Unable to determine application processor suspend mode status.")
                break
        if DELAY_SCREEN_OFF:
            # add additional delay time if necessary
            time.sleep(DELAY_SCREEN_OFF)
        print("...Screen off and device in suspend mode.")

    def collectMeasurements(self, measurementCount, rate , ensure_screen_off=True, verbose=True,
                             plot_data = False):
        assert(measurementCount > 0)
        decimate_by = self._native_hz / rate  or 1
        if ensure_screen_off:
            self.waitForScreenOff()
            print ("Taking measurements...")
        self._power_monitor.StartDataCollection()
        sub_measurements = []
        measurements = []
        tries = 0
        if verbose: print("")
        try:
            while len(measurements) < measurementCount and tries < 5:
                if tries:
                    self._power_monitor.StopDataCollection()
                    self._power_monitor.StartDataCollection()
                    time.sleep(1.0)
                tries += 1
                additional = self._power_monitor.CollectData()
                if additional is not None:
                    tries = 0
                    sub_measurements.extend(additional)
                    while len(sub_measurements) >= decimate_by:
                        sub_avg = sum(sub_measurements) / len(sub_measurements)
                        measurements.append(sub_avg)
                        sub_measurements = sub_measurements[decimate_by:]
                        if verbose:
                            sys.stdout.write("\33[1A\33[2K")
                            print ("MEASURED[%d]: %f" % (len(measurements),measurements[-1]))
        finally:
            self._power_monitor.StopDataCollection()

        self.reportErrorIf(measurementCount > len(measurements),
                            "Unable to collect all requested measurements")
        return measurements

    def request_user_acknowledgment(self, msg):
        """Post message to user on screen and wait for acknowledgment"""
        response = self.executeOnDevice(PowerTest.REQUEST_USER_RESPONSE % msg)
        self.reportErrorIf(response != "OK", "Unable to request user acknowledgment")

    def setTestResult (self, testname, condition, msg):
        if condition is False:
            val = "FAIL"
        elif condition is True:
            val = "PASS"
        else:
            val = condition
        print ("Test %s : %s" % (testname, val))
        response = self.executeOnDevice(PowerTest.REQUEST_SET_TEST_RESULT % (testname, val, msg))
        self.reportErrorIf(response != "OK", "Unable to send test status to Verifier")

    def setPowerOn(self, sensor, powered_on):
        response = self.executeOnDevice(PowerTest.REQUEST_SENSOR_SWITCH %
                                        ({True:"ON", False:"OFF"}[powered_on], sensor))
        self.reportErrorIf(response == "ERR", "Unable to set sensor %s state" % sensor)
        logging.info("Set %s %s" % (sensor, {True:"ON", False:"OFF"}[powered_on]))
        return response

    def runPowerTest(self, sensor, max_power_allowed, user_request = None):
        if not signal_abort.empty():
            sys.exit( signal_abort.get() )
        self._current_test = "%s_Power_Test_While_%s" % (sensor,
                                    {True:"Under_Motion", False:"Still"}[user_request is not None])
        try:
            print ("\n\n---------------------------------")
            if user_request is not None:
                print ("Running power test on %s under motion." % sensor)
            else:
                print ("Running power test on %s while device is still." % sensor)
            print ("---------------------------------")
            response = self.executeOnDevice(PowerTest.REQUEST_SENSOR_AVAILABILITY % sensor)
            if response == "UNAVAILABLE":
                self.setTestResult(self._current_test, condition="SKIPPED",
                    msg="Sensor %s not available on this platform"%sensor)
            self.setPowerOn("ALL", False)
            if response == "UNAVAILABLE":
                self.setTestResult(self._current_test, condition="SKIPPED",
                                   msg="Sensor %s not available on this device"%sensor)
                return

            self.reportErrorIf(response != "OK", "Unable to set all sensor off")
            if not signal_abort.empty():
                sys.exit( signal_abort.get() )
            self.executeOnDevice(PowerTest.REQUEST_SCREEN_OFF)
            self.setUsbEnabled(False)
            print("Collecting background measurements...")
            measurements = self.collectMeasurements( PowerTest.SAMPLE_COUNT_NOMINAL,
                                                     PowerTest.RATE_NOMINAL,
                                                     plot_data = True)
            if measurements and LOG_DATA_TO_FILE:
                with open( "/tmp/cts-power-tests-%s-%s-background-data.log"%(sensor,
                   {True:"Under_Motion", False:"Still"}[user_request is not None] ),'w') as f:
                    for m in measurements:
                        f.write( "%.4f\n"%m)
            self.reportErrorIf(not measurements, "No background measurements could be taken")
            backgnd = sum(measurements) / len(measurements)
            self.setUsbEnabled(True)
            self.setPowerOn(sensor, True)
            if user_request is not None:
                print("===========================================\n" +
                      "==> Please follow the instructions presented on the device\n" +
                      "==========================================="
                     )
                self.request_user_acknowledgment(user_request)
            self.executeOnDevice(PowerTest.REQUEST_SCREEN_OFF)
            self.setUsbEnabled(False)
            self.reportErrorIf(response != "OK", "Unable to set sensor %s ON" % sensor)
            print ("Collecting sensor %s measurements" % sensor)
            measurements = self.collectMeasurements(PowerTest.SAMPLE_COUNT_NOMINAL,
                                                    PowerTest.RATE_NOMINAL)

            if measurements and LOG_DATA_TO_FILE:
                with open( "/tmp/cts-power-tests-%s-%s-sensor-data.log"%(sensor,
                   {True:"Under_Motion", False:"Still"}[user_request is not None] ),'w') as f:
                    for m in measurements:
                        f.write( "%.4f\n"%m)
                    self.setUsbEnabled(True, verbose = False)
                    print("Saving raw data files to device...")
                    self.executeLocal("adb shell mkdir -p %s" % self._external_storage, False)
                    self.executeLocal("adb push %s %s/." % (f.name, self._external_storage))
                    self.setUsbEnabled(False, verbose = False)
            self.reportErrorIf(not measurements, "No measurements could be taken for %s" % sensor)
            avg = sum(measurements) / len(measurements)
            squared = [(m-avg)*(m-avg) for m in measurements]

            import math
            stddev = math.sqrt(sum(squared)/len(squared))
            current_diff = avg - backgnd
            self.setUsbEnabled(True)
            max_power = max(measurements) - avg
            if current_diff <= max_power_allowed:
                # TODO: fail the test of background > current
                message = ("Draw is within limits. Current:%f Background:%f   Measured: %f Stddev: %f  Peak: %f")%\
                             ( current_diff*1000.0, backgnd*1000.0, avg*1000.0, stddev*1000.0, max_power*1000.0)
            else:
                message = ("Draw is too high. Current:%f Background:%f   Measured: %f Stddev: %f  Peak: %f")%\
                             ( current_diff*1000.0, backgnd*1000.0, avg*1000.0, stddev*1000.0, max_power*1000.0)
            self.setTestResult( testname = self._current_test,
                                condition = current_diff <= max_power_allowed,
                                msg = message)
            print("Result: "+message)
        except:
            import traceback
            traceback.print_exc()
            self.setTestResult(self._current_test, condition="FAIL",
                               msg="Exception occurred during run of test.")


    @staticmethod
    def run_tests():
        testrunner = None
        try:
            GENERIC_MOTION_REQUEST = "\n===> Please press Next and when the screen is off, keep " + \
                "the device under motion with only tiny, slow movements until the screen turns " + \
                "on again.\nPlease refrain from interacting with the screen or pressing any side " + \
                "buttons while measurements are taken."
            USER_STEPS_REQUEST = "\n===> Please press Next and when the screen is off, then move " + \
                "the device to simulate step motion until the screen turns on again.\nPlease " + \
                "refrain from interacting with the screen or pressing any side buttons while " + \
                "measurements are taken."
            testrunner = PowerTest()
            testrunner.executeOnDevice(PowerTest.REQUEST_SHOW_MESSAGE % "Connected.  Running tests...")
            testrunner.runPowerTest("SIGNIFICANT_MOTION", PowerTest.MAX_SIGMO_POWER, user_request = GENERIC_MOTION_REQUEST)
            testrunner.runPowerTest("STEP_DETECTOR", PowerTest.MAX_STEP_DETECTOR_POWER, user_request = USER_STEPS_REQUEST)
            testrunner.runPowerTest("STEP_COUNTER", PowerTest.MAX_STEP_COUNTER_POWER, user_request = USER_STEPS_REQUEST)
            testrunner.runPowerTest("ACCELEROMETER", PowerTest.MAX_ACCEL_POWER, user_request = GENERIC_MOTION_REQUEST)
            testrunner.runPowerTest("MAGNETIC_FIELD", PowerTest.MAX_MAG_POWER, user_request = GENERIC_MOTION_REQUEST)
            testrunner.runPowerTest("GYROSCOPE", PowerTest.MAX_GYRO_POWER, user_request = GENERIC_MOTION_REQUEST)
            testrunner.runPowerTest("ACCELEROMETER", PowerTest.MAX_ACCEL_POWER, user_request = None)
            testrunner.runPowerTest("MAGNETIC_FIELD", PowerTest.MAX_MAG_POWER, user_request = None)
            testrunner.runPowerTest("GYROSCOPE", PowerTest.MAX_GYRO_POWER, user_request = None)
            testrunner.runPowerTest("SIGNIFICANT_MOTION", PowerTest.MAX_SIGMO_POWER, user_request = None)
            testrunner.runPowerTest("STEP_DETECTOR", PowerTest.MAX_STEP_DETECTOR_POWER, user_request = None)
            testrunner.runPowerTest("STEP_COUNTER", PowerTest.MAX_STEP_COUNTER_POWER, user_request = None)
        except:
            import traceback
            traceback.print_exc()
        finally:
            signal_exit_q.put(0) # anything will signal thread to terminate
            logging.info("TESTS COMPLETE")
            if testrunner:
                try:
                    testrunner.finalize()
                except socket.error:
                    sys.exit("============================\nUnable to connect to device under " + \
                             "test. Make sure the device is connected via the usb pass-through, " + \
                             "the CtsVerifier app is running the SensorPowerTest on the device, " + \
                             "and USB pass-through is enabled.\n===========================")


def main(argv):
  """ Simple command-line interface for a power test application."""
  useful_flags = ["voltage", "status", "usbpassthrough",
                  "samples", "current", "log", "power_monitor"]
  if not [f for f in useful_flags if FLAGS.get(f, None) is not None]:
    print __doc__.strip()
    print FLAGS.MainModuleHelp()
    return

  if FLAGS.avg and FLAGS.avg < 0:
    loggign.error("--avg must be greater than 0")
    return

  if FLAGS.voltage is not None:
    if FLAGS.voltage > 5.5:
        print("!!WARNING: Voltage higher than typical values!!!")
    try:
        response = raw_input("Voltage of %.3f requested.  Confirm this is correct (Y/N)"%FLAGS.voltage)
        if response.upper() != "Y":
            sys.exit("Aborting")
    except:
        sys.exit("Aborting.")

  if not FLAGS.power_monitor:
      sys.exit("You must specify a '--power_monitor' option to specify which power monitor type " + \
               "you are using.\nOne of:\n  \n  ".join(available_monitors))
  power_monitors = do_import('power_monitors.%s' % FLAGS.power_monitor)
  try:
      mon = power_monitors.Power_Monitor(device=FLAGS.device)
  except:
      import traceback
      traceback.print_exc()
      sys.exit("No power monitors found")

  if FLAGS.voltage is not None:

    if FLAGS.ramp is not None:
      mon.RampVoltage(mon.start_voltage, FLAGS.voltage)
    else:
      mon.SetVoltage(FLAGS.voltage)

  if FLAGS.current is not None:
    mon.SetMaxCurrent(FLAGS.current)

  if FLAGS.status:
    items = sorted(mon.GetStatus().items())
    print "\n".join(["%s: %s" % item for item in items])

  if FLAGS.usbpassthrough:
    if FLAGS.usbpassthrough == 'off':
      mon.SetUsbPassthrough(0)
    elif FLAGS.usbpassthrough == 'on':
      mon.SetUsbPassthrough(1)
    elif FLAGS.usbpassthrough == 'auto':
      mon.SetUsbPassthrough(2)
    else:
      mon.Close()
      sys.exit('bad pass-through flag: %s' % FLAGS.usbpassthrough)

  if FLAGS.samples:
    # Make sure state is normal
    mon.StopDataCollection()
    status = mon.GetStatus()
    native_hz = status["sampleRate"] * 1000

    # Collect and average samples as specified
    mon.StartDataCollection()

    # In case FLAGS.hz doesn't divide native_hz exactly, use this invariant:
    # 'offset' = (consumed samples) * FLAGS.hz - (emitted samples) * native_hz
    # This is the error accumulator in a variation of Bresenham's algorithm.
    emitted = offset = 0
    collected = []
    history_deque = collections.deque()  # past n samples for rolling average

    try:
      last_flush = time.time()
      while emitted < FLAGS.samples or FLAGS.samples == -1:
        # The number of raw samples to consume before emitting the next output
        need = (native_hz - offset + FLAGS.hz - 1) / FLAGS.hz
        if need > len(collected):  # still need more input samples
          samples = mon.CollectData()
          if not samples: break
          collected.extend(samples)
        else:
          # Have enough data, generate output samples.
          # Adjust for consuming 'need' input samples.
          offset += need * FLAGS.hz
          while offset >= native_hz:  # maybe multiple, if FLAGS.hz > native_hz
            this_sample = sum(collected[:need]) / need

            if FLAGS.timestamp: print int(time.time()),

            if FLAGS.avg:
              history_deque.appendleft(this_sample)
              if len(history_deque) > FLAGS.avg: history_deque.pop()
              print "%f %f" % (this_sample,
                               sum(history_deque) / len(history_deque))
            else:
              print "%f" % this_sample
            sys.stdout.flush()

            offset -= native_hz
            emitted += 1  # adjust for emitting 1 output sample
          collected = collected[need:]
          now = time.time()
          if now - last_flush >= 0.99:  # flush every second
            sys.stdout.flush()
            last_flush = now
    except KeyboardInterrupt:
      print("interrupted")
      return 1
    finally:
      mon.Close()
    return 0

  if FLAGS.run:
    if not FLAGS.power_monitor:
        sys.exit("When running power tests, you must specify which type of power monitor to use" +
                 " with '--power_monitor <type of power monitor>'")
    PowerTest.run_tests()


if __name__ == "__main__":
    flags.DEFINE_boolean("status", None, "Print power meter status")
    flags.DEFINE_integer("avg", None,
                         "Also report average over last n data points")
    flags.DEFINE_float("voltage", None, "Set output voltage (0 for off)")
    flags.DEFINE_float("current", None, "Set max output current")
    flags.DEFINE_string("usbpassthrough", None, "USB control (on, off, auto)")
    flags.DEFINE_integer("samples", None, "Collect and print this many samples")
    flags.DEFINE_integer("hz", 5000, "Print this many samples/sec")
    flags.DEFINE_string("device", None,
                        "Path to the device in /dev/... (ex:/dev/ttyACM1)")
    flags.DEFINE_boolean("timestamp", None,
                         "Also print integer (seconds) timestamp on each line")
    flags.DEFINE_boolean("ramp", True, "Gradually increase voltage")
    flags.DEFINE_boolean("log", False, "Log progress to a file or not")
    flags.DEFINE_boolean("run", False, "Run the test suite for power")
    flags.DEFINE_string("power_monitor", None, "Type of power monitor to use")
    sys.exit(main(FLAGS(sys.argv)))

