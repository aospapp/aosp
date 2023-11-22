/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.cts;

import com.android.tradefed.device.CollectingOutputReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceTestCase;
import com.android.tradefed.device.BackgroundDeviceAction;

import android.platform.test.annotations.RootPermissionTest;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.Log;
import com.android.ddmlib.TimeoutException;
import java.lang.ref.WeakReference;

/**
 * A utility to monitor the device lowmemory state and reboot when low. Without this, tests that
 * cause an OOM can sometimes cause ADB to become unresponsive indefinitely. Usage is to create an
 * instance per instance of SecurityTestCase and call start() and stop() matching to
 * SecurityTestCase setup() and teardown().
 */
public class HostsideOomCatcher {

    private static final String LOG_TAG = "HostsideOomCatcher";

    private static final long LOW_MEMORY_DEVICE_THRESHOLD_KB = 1024 * 1024; // 1GB
    private static Map<String, WeakReference<BackgroundDeviceAction>> oomCatchers =
            new ConcurrentHashMap<>();
    private static Map<String, Long> totalMemories = new ConcurrentHashMap<>();

    private boolean isLowMemoryDevice = false;

    private SecurityTestCase context;

    /**
     * test behavior when oom is detected.
     */
    public enum OomBehavior {
        FAIL_AND_LOG, // normal behavior
        PASS_AND_LOG, // skip tests that oom low memory devices
        FAIL_NO_LOG,  // tests that check for oom
    }
    private OomBehavior oomBehavior = OomBehavior.FAIL_AND_LOG; // accessed across threads
    private boolean oomDetected = false; // accessed across threads

    public HostsideOomCatcher(SecurityTestCase context) {
        this.context = context;
    }

    /**
     * Utility to get the device memory total by reading /proc/meminfo and returning MemTotal
     */
    private static long getMemTotal(ITestDevice device) throws DeviceNotAvailableException {
        // cache device TotalMem to avoid an adb shell for every test.
        String serial = device.getSerialNumber();
        Long totalMemory = totalMemories.get(serial);
        if (totalMemory == null) {
            String memInfo = device.executeShellCommand("cat /proc/meminfo");
            Pattern pattern = Pattern.compile("MemTotal:\\s*(.*?)\\s*[kK][bB]");
            Matcher matcher = pattern.matcher(memInfo);
            if (matcher.find()) {
                totalMemory = Long.parseLong(matcher.group(1));
            } else {
                throw new RuntimeException("Could not get device memory total.");
            }
            Log.logAndDisplay(Log.LogLevel.INFO, LOG_TAG,
                    "Device " + serial + " has " + totalMemory + "KB total memory.");
            totalMemories.put(serial, totalMemory);
        }
        return totalMemory;
    }

    /**
     * Start the hostside oom catcher thread for the test.
     * Match this call to SecurityTestCase.setup().
     */
    public synchronized void start() throws Exception {
        long totalMemory = getMemTotal(getDevice());
        isLowMemoryDevice = totalMemory < LOW_MEMORY_DEVICE_THRESHOLD_KB;

        // reset test oom behavior
        // Devices should fail tests that OOM so that they'll be ran again with --retry.
        // If the test OOMs because previous tests used the memory, it will likely pass
        // on a second try.
        oomBehavior = OomBehavior.FAIL_AND_LOG;
        oomDetected = false;

        // Cache OOM detection in separate persistent threads for each device.
        WeakReference<BackgroundDeviceAction> reference =
                oomCatchers.get(getDevice().getSerialNumber());
        BackgroundDeviceAction oomCatcher = null;
        if (reference != null) {
            oomCatcher = reference.get();
        }
        if (oomCatcher == null || !oomCatcher.isAlive() || oomCatcher.isCancelled()) {
            AdbUtils.runCommandLine("am start com.android.cts.oomcatcher/.OomCatcher", getDevice());

            oomCatcher = new BackgroundDeviceAction(
                    "logcat -c && logcat OomCatcher:V *:S",
                    "Oom Catcher background thread",
                    getDevice(), new OomReceiver(getDevice()), 0);

            oomCatchers.put(getDevice().getSerialNumber(), new WeakReference<>(oomCatcher));
            oomCatcher.start();
        }
    }

    /**
     * Stop the hostside oom catcher thread.
     * Match this call to SecurityTestCase.setup().
     */
    public static void stop(String serial) {
        WeakReference<BackgroundDeviceAction> reference = oomCatchers.get(serial);
        if (reference != null) {
            BackgroundDeviceAction oomCatcher = reference.get();
            if (oomCatcher != null) {
                oomCatcher.cancel();
            }
        }
    }

    /**
     * Check every test teardown to see if the device oomed during the test.
     */
    public synchronized boolean isOomDetected() {
        return oomDetected;
    }

    /**
     * Return the current test behavior for when oom is detected.
     */
    public synchronized OomBehavior getOomBehavior() {
        return oomBehavior;
    }

    /**
     * Flag meaning the test will likely fail on devices with low memory.
     */
    public synchronized void setHighMemoryTest() {
        if (isLowMemoryDevice) {
            oomBehavior = OomBehavior.PASS_AND_LOG;
        } else {
            oomBehavior = OomBehavior.FAIL_AND_LOG;
        }
    }

    /**
     * Flag meaning the test uses the OOM catcher to fail the test because the test vulnerability
     * intentionally OOMs the device.
     */
    public synchronized void setOomTest() {
        oomBehavior = OomBehavior.FAIL_NO_LOG;
    }

    private ITestDevice getDevice() {
        return context.getDevice();
    }

    /**
     * Read through logcat to find when the OomCatcher app reports low memory. Once detected, reboot
     * the device to prevent a soft reset with the possiblity of ADB becomming unresponsive.
     */
    class OomReceiver extends MultiLineReceiver {

        private ITestDevice device = null;
        private boolean isCancelled = false;

        public OomReceiver(ITestDevice device) {
            this.device = device;
        }

        @Override
        public void processNewLines(String[] lines) {
            for (String line : lines) {
                if (Pattern.matches(".*Low memory.*", line)) {
                    // low memory detected, reboot device to clear memory and pass test
                    isCancelled = true;
                    Log.logAndDisplay(Log.LogLevel.INFO, LOG_TAG,
                            "lowmemorykiller detected; rebooting device.");
                    synchronized (HostsideOomCatcher.this) { // synchronized for oomDetected
                        oomDetected = true; // set HostSideOomCatcher var
                    }
                    try {
                        device.nonBlockingReboot();
                        device.waitForDeviceOnline(60 * 2 * 1000); // 2 minutes
                    } catch (Exception e) {
                        Log.e(LOG_TAG, e.toString());
                    }
                    return; // we don't need to process remaining lines in the array
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return isCancelled;
        }
    }
}

