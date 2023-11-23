/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.cts.statsd.atom;

import android.net.wifi.WifiModeEnum;
import android.os.WakeLockLevelEnum;
import android.platform.test.annotations.RestrictedBuildTest;
import android.server.ErrorSource;

import com.android.internal.os.StatsdConfigProto.FieldMatcher;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.AtomsProto;
import com.android.os.AtomsProto.ANROccurred;
import com.android.os.AtomsProto.AppCrashOccurred;
import com.android.os.AtomsProto.AppStartOccurred;
import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.AttributionNode;
import com.android.os.AtomsProto.AudioStateChanged;
import com.android.os.AtomsProto.BinderCalls;
import com.android.os.AtomsProto.BleScanResultReceived;
import com.android.os.AtomsProto.BleScanStateChanged;
import com.android.os.AtomsProto.CameraStateChanged;
import com.android.os.AtomsProto.CpuActiveTime;
import com.android.os.AtomsProto.DangerousPermissionState;
import com.android.os.AtomsProto.DeviceCalculatedPowerBlameUid;
import com.android.os.AtomsProto.FlashlightStateChanged;
import com.android.os.AtomsProto.ForegroundServiceStateChanged;
import com.android.os.AtomsProto.GpsScanStateChanged;
import com.android.os.AtomsProto.HiddenApiUsed;
import com.android.os.AtomsProto.LooperStats;
import com.android.os.AtomsProto.LmkKillOccurred;
import com.android.os.AtomsProto.MediaCodecStateChanged;
import com.android.os.AtomsProto.NativeProcessMemoryState;
import com.android.os.AtomsProto.OverlayStateChanged;
import com.android.os.AtomsProto.PictureInPictureStateChanged;
import com.android.os.AtomsProto.ProcessMemoryHighWaterMark;
import com.android.os.AtomsProto.ProcessMemoryState;
import com.android.os.AtomsProto.ScheduledJobStateChanged;
import com.android.os.AtomsProto.SyncStateChanged;
import com.android.os.AtomsProto.TestAtomReported;
import com.android.os.AtomsProto.VibratorStateChanged;
import com.android.os.AtomsProto.WakelockStateChanged;
import com.android.os.AtomsProto.WakeupAlarmOccurred;
import com.android.os.AtomsProto.WifiLockStateChanged;
import com.android.os.AtomsProto.WifiMulticastLockStateChanged;
import com.android.os.AtomsProto.WifiScanStateChanged;
import com.android.os.StatsLog.EventMetricData;
import com.android.tradefed.log.LogUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Statsd atom tests that are done via app, for atoms that report a uid.
 */
public class UidAtomTests extends DeviceAtomTestCase {

    private static final String TAG = "Statsd.UidAtomTests";

    private static final boolean DAVEY_ENABLED = false;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        resetBatteryStatus();
        super.tearDown();
    }

    public void testLmkKillOccurred() throws Exception {
        if (statsdDisabled() || !"true".equals(getProperty("ro.lmk.log_stats"))) {
            return;
        }

        StatsdConfig.Builder conf = createConfigBuilder()
                .addAllowedLogSource("AID_LMKD");
        final int atomTag = Atom.LMK_KILL_OCCURRED_FIELD_NUMBER;
        addAtomEvent(conf, atomTag, false);
        uploadConfig(conf);

        Thread.sleep(WAIT_TIME_SHORT);

        executeBackgroundService(ACTION_LMK);
        Thread.sleep(5_000);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        assertEquals(1, data.size());
        assertTrue(data.get(0).getAtom().hasLmkKillOccurred());
        LmkKillOccurred atom = data.get(0).getAtom().getLmkKillOccurred();
        assertEquals(getUid(), atom.getUid());
        assertEquals(DEVICE_SIDE_TEST_PACKAGE, atom.getProcessName());
        assertTrue(500 <= atom.getOomAdjScore());
    }

    public void testAppCrashOccurred() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        final int atomTag = Atom.APP_CRASH_OCCURRED_FIELD_NUMBER;
        createAndUploadConfig(atomTag, false);
        Thread.sleep(WAIT_TIME_SHORT);

        runActivity("StatsdCtsForegroundActivity", "action", "action.crash");

        Thread.sleep(WAIT_TIME_SHORT);
        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        AppCrashOccurred atom = data.get(0).getAtom().getAppCrashOccurred();
        assertEquals("crash", atom.getEventType());
        assertEquals(AppCrashOccurred.InstantApp.FALSE_VALUE, atom.getIsInstantApp().getNumber());
        assertEquals(AppCrashOccurred.ForegroundState.FOREGROUND_VALUE,
                atom.getForegroundState().getNumber());
        assertEquals("com.android.server.cts.device.statsd", atom.getPackageName());
    }

    public void testAppStartOccurred() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        final int atomTag = Atom.APP_START_OCCURRED_FIELD_NUMBER;

        createAndUploadConfig(atomTag, false);
        Thread.sleep(WAIT_TIME_SHORT);

        runActivity("StatsdCtsForegroundActivity", "action", "action.sleep_top");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        AppStartOccurred atom = data.get(0).getAtom().getAppStartOccurred();
        assertEquals("com.android.server.cts.device.statsd", atom.getPkgName());
        assertEquals("com.android.server.cts.device.statsd.StatsdCtsForegroundActivity",
                atom.getActivityName());
        assertFalse(atom.getIsInstantApp());
        assertTrue(atom.getActivityStartMillis() > 0);
        assertTrue(atom.getTransitionDelayMillis() > 0);
    }

    public void testAudioState() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_AUDIO_OUTPUT, true)) return;

        final int atomTag = Atom.AUDIO_STATE_CHANGED_FIELD_NUMBER;
        final String name = "testAudioState";

        Set<Integer> onState = new HashSet<>(
                Arrays.asList(AudioStateChanged.State.ON_VALUE));
        Set<Integer> offState = new HashSet<>(
                Arrays.asList(AudioStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(onState, offState);

        createAndUploadConfig(atomTag, true);  // True: uses attribution.
        Thread.sleep(WAIT_TIME_SHORT);

        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", name);

        Thread.sleep(WAIT_TIME_SHORT);
        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // AudioStateChanged timestamp is fuzzed to 5min buckets
        assertStatesOccurred(stateSet, data, 0,
                atom -> atom.getAudioStateChanged().getState().getNumber());
    }

    public void testBleScan() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_BLUETOOTH_LE, true)) return;

        final int atom = Atom.BLE_SCAN_STATE_CHANGED_FIELD_NUMBER;
        final int field = BleScanStateChanged.STATE_FIELD_NUMBER;
        final int stateOn = BleScanStateChanged.State.ON_VALUE;
        final int stateOff = BleScanStateChanged.State.OFF_VALUE;
        final int minTimeDiffMillis = 1_500;
        final int maxTimeDiffMillis = 3_000;

        List<EventMetricData> data = doDeviceMethodOnOff("testBleScanUnoptimized", atom, field,
                stateOn, stateOff, minTimeDiffMillis, maxTimeDiffMillis, true);

        BleScanStateChanged a0 = data.get(0).getAtom().getBleScanStateChanged();
        BleScanStateChanged a1 = data.get(1).getAtom().getBleScanStateChanged();
        assertTrue(a0.getState().getNumber() == stateOn);
        assertTrue(a1.getState().getNumber() == stateOff);
    }

    public void testBleUnoptimizedScan() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_BLUETOOTH_LE, true)) return;

        final int atom = Atom.BLE_SCAN_STATE_CHANGED_FIELD_NUMBER;
        final int field = BleScanStateChanged.STATE_FIELD_NUMBER;
        final int stateOn = BleScanStateChanged.State.ON_VALUE;
        final int stateOff = BleScanStateChanged.State.OFF_VALUE;
        final int minTimeDiffMillis = 1_500;
        final int maxTimeDiffMillis = 3_000;

        List<EventMetricData> data = doDeviceMethodOnOff("testBleScanUnoptimized", atom, field,
                stateOn, stateOff, minTimeDiffMillis, maxTimeDiffMillis, true);

        BleScanStateChanged a0 = data.get(0).getAtom().getBleScanStateChanged();
        assertTrue(a0.getState().getNumber() == stateOn);
        assertFalse(a0.getIsFiltered());
        assertFalse(a0.getIsFirstMatch());
        assertFalse(a0.getIsOpportunistic());
        BleScanStateChanged a1 = data.get(1).getAtom().getBleScanStateChanged();
        assertTrue(a1.getState().getNumber() == stateOff);
        assertFalse(a1.getIsFiltered());
        assertFalse(a1.getIsFirstMatch());
        assertFalse(a1.getIsOpportunistic());


        // Now repeat the test for opportunistic scanning and make sure it is reported correctly.
        data = doDeviceMethodOnOff("testBleScanOpportunistic", atom, field,
                stateOn, stateOff, minTimeDiffMillis, maxTimeDiffMillis, true);

        a0 = data.get(0).getAtom().getBleScanStateChanged();
        assertTrue(a0.getState().getNumber() == stateOn);
        assertFalse(a0.getIsFiltered());
        assertFalse(a0.getIsFirstMatch());
        assertTrue(a0.getIsOpportunistic());  // This scan is opportunistic.
        a1 = data.get(1).getAtom().getBleScanStateChanged();
        assertTrue(a1.getState().getNumber() == stateOff);
        assertFalse(a1.getIsFiltered());
        assertFalse(a1.getIsFirstMatch());
        assertTrue(a1.getIsOpportunistic());
    }

    public void testBleScanResult() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_BLUETOOTH_LE, true)) return;

        final int atom = Atom.BLE_SCAN_RESULT_RECEIVED_FIELD_NUMBER;
        final int field = BleScanResultReceived.NUM_RESULTS_FIELD_NUMBER;

        StatsdConfig.Builder conf = createConfigBuilder();
        addAtomEvent(conf, atom, createFvm(field).setGteInt(0));
        List<EventMetricData> data = doDeviceMethod("testBleScanResult", conf);

        assertTrue(data.size() >= 1);
        BleScanResultReceived a0 = data.get(0).getAtom().getBleScanResultReceived();
        assertTrue(a0.getNumResults() >= 1);
    }

    public void testHiddenApiUsed() throws Exception {
        if (statsdDisabled()) {
            return;
        }

        String oldRate = getDevice().executeShellCommand(
                "device_config get app_compat hidden_api_access_statslog_sampling_rate").trim();

        getDevice().executeShellCommand(
                "device_config put app_compat hidden_api_access_statslog_sampling_rate 65536");
        try {
            final int atomTag = Atom.HIDDEN_API_USED_FIELD_NUMBER;

            createAndUploadConfig(atomTag, false);

            runActivity("HiddenApiUsedActivity", null, null);


            List<EventMetricData> data = getEventMetricDataList();
            assertTrue(data.size() == 1);

            HiddenApiUsed atom = data.get(0).getAtom().getHiddenApiUsed();

            int uid = getUid();
            assertEquals(uid, atom.getUid());
            assertFalse(atom.getAccessDenied());
            assertEquals("Landroid/app/Activity;->mWindow:Landroid/view/Window;",
                    atom.getSignature());
        } finally {
            if (!oldRate.equals("null")) {
                getDevice().executeShellCommand(
                        "device_config put app_compat hidden_api_access_statslog_sampling_rate "
                        + oldRate);
            } else {
                getDevice().executeShellCommand(
                        "device_config delete hidden_api_access_statslog_sampling_rate");
            }
        }
    }

    public void testCameraState() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_CAMERA, true) && !hasFeature(FEATURE_CAMERA_FRONT, true)) return;

        final int atomTag = Atom.CAMERA_STATE_CHANGED_FIELD_NUMBER;
        Set<Integer> cameraOn = new HashSet<>(Arrays.asList(CameraStateChanged.State.ON_VALUE));
        Set<Integer> cameraOff = new HashSet<>(Arrays.asList(CameraStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(cameraOn, cameraOff);

        createAndUploadConfig(atomTag, true);  // True: uses attribution.
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testCameraState");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_LONG,
                atom -> atom.getCameraStateChanged().getState().getNumber());
    }

    public void testCpuTimePerUid() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WATCH, false)) return;
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.CPU_TIME_PER_UID_FIELD_NUMBER, null);

        uploadConfig(config);

        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testSimpleCpu");

        Thread.sleep(WAIT_TIME_SHORT);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> atomList = getGaugeMetricDataList();

        // TODO: We don't have atom matching on gauge yet. Let's refactor this after that feature is
        // implemented.
        boolean found = false;
        int uid = getUid();
        for (Atom atom : atomList) {
            if (atom.getCpuTimePerUid().getUid() == uid) {
                found = true;
                assertTrue(atom.getCpuTimePerUid().getUserTimeMicros() > 0);
                assertTrue(atom.getCpuTimePerUid().getSysTimeMicros() > 0);
            }
        }
        assertTrue("found uid " + uid, found);
    }

    @RestrictedBuildTest
    public void testCpuActiveTime() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WATCH, false)) return;
        StatsdConfig.Builder config = getPulledConfig();
        FieldMatcher.Builder dimension = FieldMatcher.newBuilder()
                .setField(Atom.CPU_ACTIVE_TIME_FIELD_NUMBER)
                .addChild(FieldMatcher.newBuilder()
                        .setField(CpuActiveTime.UID_FIELD_NUMBER));
        addGaugeAtomWithDimensions(config, Atom.CPU_ACTIVE_TIME_FIELD_NUMBER, dimension);

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testSimpleCpu");
        Thread.sleep(WAIT_TIME_SHORT);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> atomList = getGaugeMetricDataList();

        boolean found = false;
        int uid = getUid();
        long timeSpent = 0;
        for (Atom atom : atomList) {
            if (atom.getCpuActiveTime().getUid() == uid) {
                found = true;
                timeSpent += atom.getCpuActiveTime().getTimeMillis();
            }
        }
        assertTrue(timeSpent > 0);
        assertTrue("found uid " + uid, found);
    }

    public void testDeviceCalculatedPowerUse() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_LEANBACK_ONLY, false)) return;

        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.DEVICE_CALCULATED_POWER_USE_FIELD_NUMBER, null);
        uploadConfig(config);
        unplugDevice();

        Thread.sleep(WAIT_TIME_LONG);
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testSimpleCpu");
        Thread.sleep(WAIT_TIME_SHORT);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        Atom atom = getGaugeMetricDataList().get(0);
        assertTrue(atom.getDeviceCalculatedPowerUse().getComputedPowerNanoAmpSecs() > 0);
    }


    public void testDeviceCalculatedPowerBlameUid() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_LEANBACK_ONLY, false)) return;

        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config,
                Atom.DEVICE_CALCULATED_POWER_BLAME_UID_FIELD_NUMBER, null);
        uploadConfig(config);
        unplugDevice();

        Thread.sleep(WAIT_TIME_LONG);
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testSimpleCpu");
        Thread.sleep(WAIT_TIME_SHORT);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> atomList = getGaugeMetricDataList();
        boolean uidFound = false;
        int uid = getUid();
        long uidPower = 0;
        for (Atom atom : atomList) {
            DeviceCalculatedPowerBlameUid item = atom.getDeviceCalculatedPowerBlameUid();
                if (item.getUid() == uid) {
                assertFalse("Found multiple power values for uid " + uid, uidFound);
                uidFound = true;
                uidPower = item.getPowerNanoAmpSecs();
            }
        }
        assertTrue("No power value for uid " + uid, uidFound);
        assertTrue("Non-positive power value for uid " + uid, uidPower > 0);
    }

    public void testDavey() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!DAVEY_ENABLED ) return;
        long MAX_DURATION = 2000;
        long MIN_DURATION = 750;
        final int atomTag = Atom.DAVEY_OCCURRED_FIELD_NUMBER;
        createAndUploadConfig(atomTag, false); // UID is logged without attribution node

        runActivity("DaveyActivity", null, null);

        List<EventMetricData> data = getEventMetricDataList();
        assertTrue(data.size() == 1);
        long duration = data.get(0).getAtom().getDaveyOccurred().getJankDurationMillis();
        assertTrue("Jank duration of " + duration + "ms was less than " + MIN_DURATION + "ms",
                duration >= MIN_DURATION);
        assertTrue("Jank duration of " + duration + "ms was longer than " + MAX_DURATION + "ms",
                duration <= MAX_DURATION);
    }

    public void testFlashlightState() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_CAMERA_FLASH, true)) return;

        final int atomTag = Atom.FLASHLIGHT_STATE_CHANGED_FIELD_NUMBER;
        final String name = "testFlashlight";

        Set<Integer> flashlightOn = new HashSet<>(
            Arrays.asList(FlashlightStateChanged.State.ON_VALUE));
        Set<Integer> flashlightOff = new HashSet<>(
            Arrays.asList(FlashlightStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(flashlightOn, flashlightOff);

        createAndUploadConfig(atomTag, true);  // True: uses attribution.
        Thread.sleep(WAIT_TIME_SHORT);

        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", name);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getFlashlightStateChanged().getState().getNumber());
    }

    public void testForegroundServiceState() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        final int atomTag = Atom.FOREGROUND_SERVICE_STATE_CHANGED_FIELD_NUMBER;
        final String name = "testForegroundService";

        Set<Integer> enterForeground = new HashSet<>(
                Arrays.asList(ForegroundServiceStateChanged.State.ENTER_VALUE));
        Set<Integer> exitForeground = new HashSet<>(
                Arrays.asList(ForegroundServiceStateChanged.State.EXIT_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(enterForeground, exitForeground);

        createAndUploadConfig(atomTag, false);
        Thread.sleep(WAIT_TIME_SHORT);

        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", name);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getForegroundServiceStateChanged().getState().getNumber());
    }

    public void testGpsScan() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_LOCATION_GPS, true)) return;
        // Whitelist this app against background location request throttling
        String origWhitelist = getDevice().executeShellCommand(
                "settings get global location_background_throttle_package_whitelist").trim();
        getDevice().executeShellCommand(String.format(
                "settings put global location_background_throttle_package_whitelist %s",
                DEVICE_SIDE_TEST_PACKAGE));

        try {
            final int atom = Atom.GPS_SCAN_STATE_CHANGED_FIELD_NUMBER;
            final int key = GpsScanStateChanged.STATE_FIELD_NUMBER;
            final int stateOn = GpsScanStateChanged.State.ON_VALUE;
            final int stateOff = GpsScanStateChanged.State.OFF_VALUE;
            final int minTimeDiffMillis = 500;
            final int maxTimeDiffMillis = 60_000;

            List<EventMetricData> data = doDeviceMethodOnOff("testGpsScan", atom, key,
                    stateOn, stateOff, minTimeDiffMillis, maxTimeDiffMillis, true);

            GpsScanStateChanged a0 = data.get(0).getAtom().getGpsScanStateChanged();
            GpsScanStateChanged a1 = data.get(1).getAtom().getGpsScanStateChanged();
            assertTrue(a0.getState().getNumber() == stateOn);
            assertTrue(a1.getState().getNumber() == stateOff);
        } finally {
            if ("null".equals(origWhitelist) || "".equals(origWhitelist)) {
                getDevice().executeShellCommand(
                        "settings delete global location_background_throttle_package_whitelist");
            } else {
                getDevice().executeShellCommand(String.format(
                        "settings put global location_background_throttle_package_whitelist %s",
                        origWhitelist));
            }
        }
    }

    public void testMediaCodecActivity() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WATCH, false)) return;
        final int atomTag = Atom.MEDIA_CODEC_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> onState = new HashSet<>(
                Arrays.asList(MediaCodecStateChanged.State.ON_VALUE));
        Set<Integer> offState = new HashSet<>(
                Arrays.asList(MediaCodecStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(onState, offState);

        createAndUploadConfig(atomTag, true);  // True: uses attribution.
        Thread.sleep(WAIT_TIME_SHORT);

        runActivity("VideoPlayerActivity", "action", "action.play_video");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_LONG,
                atom -> atom.getMediaCodecStateChanged().getState().getNumber());
    }

    public void testOverlayState() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WATCH, false)) return;
        final int atomTag = Atom.OVERLAY_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> entered = new HashSet<>(
                Arrays.asList(OverlayStateChanged.State.ENTERED_VALUE));
        Set<Integer> exited = new HashSet<>(
                Arrays.asList(OverlayStateChanged.State.EXITED_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(entered, exited);

        createAndUploadConfig(atomTag, false);

        runActivity("StatsdCtsForegroundActivity", "action", "action.show_application_overlay",
                3_000);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        // The overlay box should appear about 2sec after the app start
        assertStatesOccurred(stateSet, data, 0,
                atom -> atom.getOverlayStateChanged().getState().getNumber());
    }

    public void testPictureInPictureState() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        String supported = getDevice().executeShellCommand("am supports-multiwindow");
        if (!hasFeature(FEATURE_WATCH, false) ||
                !hasFeature(FEATURE_PICTURE_IN_PICTURE, true) ||
                !supported.contains("true")) {
            LogUtil.CLog.d("Skipping picture in picture atom test.");
            return;
        }

        final int atomTag = Atom.PICTURE_IN_PICTURE_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> entered = new HashSet<>(
                Arrays.asList(PictureInPictureStateChanged.State.ENTERED_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(entered);

        createAndUploadConfig(atomTag, false);

        LogUtil.CLog.d("Playing video in Picture-in-Picture mode");
        runActivity("VideoPlayerActivity", "action", "action.play_video_picture_in_picture_mode");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_LONG,
                atom -> atom.getPictureInPictureStateChanged().getState().getNumber());
    }

    public void testScheduledJobState() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        String expectedName = "com.android.server.cts.device.statsd/.StatsdJobService";
        final int atomTag = Atom.SCHEDULED_JOB_STATE_CHANGED_FIELD_NUMBER;
        Set<Integer> jobSchedule = new HashSet<>(
                Arrays.asList(ScheduledJobStateChanged.State.SCHEDULED_VALUE));
        Set<Integer> jobOn = new HashSet<>(
                Arrays.asList(ScheduledJobStateChanged.State.STARTED_VALUE));
        Set<Integer> jobOff = new HashSet<>(
                Arrays.asList(ScheduledJobStateChanged.State.FINISHED_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(jobSchedule, jobOn, jobOff);

        createAndUploadConfig(atomTag, true);  // True: uses attribution.
        allowImmediateSyncs();
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testScheduledJob");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        assertStatesOccurred(stateSet, data, 0,
                atom -> atom.getScheduledJobStateChanged().getState().getNumber());

        for (EventMetricData e : data) {
            assertTrue(e.getAtom().getScheduledJobStateChanged().getJobName().equals(expectedName));
        }
    }

    //Note: this test does not have uid, but must run on the device
    public void testScreenBrightness() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        int initialBrightness = getScreenBrightness();
        boolean isInitialManual = isScreenBrightnessModeManual();
        setScreenBrightnessMode(true);
        setScreenBrightness(200);
        Thread.sleep(WAIT_TIME_LONG);

        final int atomTag = Atom.SCREEN_BRIGHTNESS_CHANGED_FIELD_NUMBER;

        Set<Integer> screenMin = new HashSet<>(Arrays.asList(47));
        Set<Integer> screen100 = new HashSet<>(Arrays.asList(100));
        Set<Integer> screen200 = new HashSet<>(Arrays.asList(198));
        // Set<Integer> screenMax = new HashSet<>(Arrays.asList(255));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(screenMin, screen100, screen200);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testScreenBrightness");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Restore initial screen brightness
        setScreenBrightness(initialBrightness);
        setScreenBrightnessMode(isInitialManual);

        popUntilFind(data, screenMin, atom->atom.getScreenBrightnessChanged().getLevel());
        popUntilFindFromEnd(data, screen200, atom->atom.getScreenBrightnessChanged().getLevel());
        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
            atom -> atom.getScreenBrightnessChanged().getLevel());
    }
    public void testSyncState() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        final int atomTag = Atom.SYNC_STATE_CHANGED_FIELD_NUMBER;
        Set<Integer> syncOn = new HashSet<>(Arrays.asList(SyncStateChanged.State.ON_VALUE));
        Set<Integer> syncOff = new HashSet<>(Arrays.asList(SyncStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(syncOn, syncOff, syncOn, syncOff);

        createAndUploadConfig(atomTag, true);
        allowImmediateSyncs();
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testSyncState");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getSyncStateChanged().getState().getNumber());
    }

    public void testVibratorState() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!checkDeviceFor("checkVibratorSupported")) return;

        final int atomTag = Atom.VIBRATOR_STATE_CHANGED_FIELD_NUMBER;
        final String name = "testVibratorState";

        Set<Integer> onState = new HashSet<>(
                Arrays.asList(VibratorStateChanged.State.ON_VALUE));
        Set<Integer> offState = new HashSet<>(
                Arrays.asList(VibratorStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(onState, offState);

        createAndUploadConfig(atomTag, true);  // True: uses attribution.
        Thread.sleep(WAIT_TIME_SHORT);

        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", name);

        Thread.sleep(WAIT_TIME_LONG);
        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        assertStatesOccurred(stateSet, data, 300,
                atom -> atom.getVibratorStateChanged().getState().getNumber());
    }

    public void testWakelockState() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        final int atomTag = Atom.WAKELOCK_STATE_CHANGED_FIELD_NUMBER;
        Set<Integer> wakelockOn = new HashSet<>(Arrays.asList(
                WakelockStateChanged.State.ACQUIRE_VALUE,
                WakelockStateChanged.State.CHANGE_ACQUIRE_VALUE));
        Set<Integer> wakelockOff = new HashSet<>(Arrays.asList(
                WakelockStateChanged.State.RELEASE_VALUE,
                WakelockStateChanged.State.CHANGE_RELEASE_VALUE));

        final String EXPECTED_TAG = "StatsdPartialWakelock";
        final WakeLockLevelEnum EXPECTED_LEVEL = WakeLockLevelEnum.PARTIAL_WAKE_LOCK;

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(wakelockOn, wakelockOff);

        createAndUploadConfig(atomTag, true);  // True: uses attribution.
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testWakelockState");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
            atom -> atom.getWakelockStateChanged().getState().getNumber());

        for (EventMetricData event: data) {
            String tag = event.getAtom().getWakelockStateChanged().getTag();
            WakeLockLevelEnum type = event.getAtom().getWakelockStateChanged().getType();
            assertTrue("Expected tag: " + EXPECTED_TAG + ", but got tag: " + tag,
                    tag.equals(EXPECTED_TAG));
            assertTrue("Expected wakelock type: " + EXPECTED_LEVEL  + ", but got level: " + type,
                    type == EXPECTED_LEVEL);
        }
    }

    public void testWakeupAlarm() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        // For automotive, all wakeup alarm becomes normal alarm. So this
        // test does not work.
        if (!hasFeature(FEATURE_AUTOMOTIVE, false)) return;
        final int atomTag = Atom.WAKEUP_ALARM_OCCURRED_FIELD_NUMBER;

        StatsdConfig.Builder config = createConfigBuilder();
        addAtomEvent(config, atomTag, true);  // True: uses attribution.

        List<EventMetricData> data = doDeviceMethod("testWakeupAlarm", config);
        assertTrue(data.size() >= 1);
        for (int i = 0; i < data.size(); i++) {
            WakeupAlarmOccurred wao = data.get(i).getAtom().getWakeupAlarmOccurred();
            assertEquals("*walarm*:android.cts.statsd.testWakeupAlarm", wao.getTag());
            assertEquals(DEVICE_SIDE_TEST_PACKAGE, wao.getPackageName());
        }
    }

    public void testWifiLockHighPerf() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WIFI, true)) return;
        if (!hasFeature(FEATURE_PC, false)) return;

        final int atomTag = Atom.WIFI_LOCK_STATE_CHANGED_FIELD_NUMBER;
        Set<Integer> lockOn = new HashSet<>(Arrays.asList(WifiLockStateChanged.State.ON_VALUE));
        Set<Integer> lockOff = new HashSet<>(Arrays.asList(WifiLockStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(lockOn, lockOff);

        createAndUploadConfig(atomTag, true);  // True: uses attribution.
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testWifiLockHighPerf");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getWifiLockStateChanged().getState().getNumber());

        for (EventMetricData event : data) {
            assertEquals(WifiModeEnum.WIFI_MODE_FULL_HIGH_PERF,
                         event.getAtom().getWifiLockStateChanged().getMode());
        }
    }

    public void testWifiLockLowLatency() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WIFI, true)) return;
        if (!hasFeature(FEATURE_PC, false)) return;

        final int atomTag = Atom.WIFI_LOCK_STATE_CHANGED_FIELD_NUMBER;
        Set<Integer> lockOn = new HashSet<>(Arrays.asList(WifiLockStateChanged.State.ON_VALUE));
        Set<Integer> lockOff = new HashSet<>(Arrays.asList(WifiLockStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(lockOn, lockOff);

        createAndUploadConfig(atomTag, true);  // True: uses attribution.
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testWifiLockLowLatency");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getWifiLockStateChanged().getState().getNumber());

        for (EventMetricData event : data) {
            assertEquals(WifiModeEnum.WIFI_MODE_FULL_LOW_LATENCY,
                         event.getAtom().getWifiLockStateChanged().getMode());
        }
    }

    public void testWifiMulticastLock() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WIFI, true)) return;
        if (!hasFeature(FEATURE_PC, false)) return;

        final int atomTag = Atom.WIFI_MULTICAST_LOCK_STATE_CHANGED_FIELD_NUMBER;
        Set<Integer> lockOn = new HashSet<>(
                Arrays.asList(WifiMulticastLockStateChanged.State.ON_VALUE));
        Set<Integer> lockOff = new HashSet<>(
                Arrays.asList(WifiMulticastLockStateChanged.State.OFF_VALUE));

        final String EXPECTED_TAG = "StatsdCTSMulticastLock";

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(lockOn, lockOff);

        createAndUploadConfig(atomTag, true);
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testWifiMulticastLock");

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getWifiMulticastLockStateChanged().getState().getNumber());

        for (EventMetricData event: data) {
            String tag = event.getAtom().getWifiMulticastLockStateChanged().getTag();
            assertEquals("Wrong tag.", EXPECTED_TAG, tag);
        }
    }

    public void testWifiScan() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WIFI, true)) return;

        final int atom = Atom.WIFI_SCAN_STATE_CHANGED_FIELD_NUMBER;
        final int key = WifiScanStateChanged.STATE_FIELD_NUMBER;
        final int stateOn = WifiScanStateChanged.State.ON_VALUE;
        final int stateOff = WifiScanStateChanged.State.OFF_VALUE;
        final int minTimeDiffMillis = 250;
        final int maxTimeDiffMillis = 60_000;
        final boolean demandExactlyTwo = false; // Two scans are performed, so up to 4 atoms logged.

        List<EventMetricData> data = doDeviceMethodOnOff("testWifiScan", atom, key,
                stateOn, stateOff, minTimeDiffMillis, maxTimeDiffMillis, demandExactlyTwo);

        assertTrue(data.size() >= 2);
        assertTrue(data.size() <= 4);
        WifiScanStateChanged a0 = data.get(0).getAtom().getWifiScanStateChanged();
        WifiScanStateChanged a1 = data.get(1).getAtom().getWifiScanStateChanged();
        assertTrue(a0.getState().getNumber() == stateOn);
        assertTrue(a1.getState().getNumber() == stateOff);
    }

    public void testBinderStats() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        try {
            unplugDevice();
            Thread.sleep(WAIT_TIME_SHORT);
            enableBinderStats();
            binderStatsNoSampling();
            resetBinderStats();
            StatsdConfig.Builder config = getPulledConfig();
            addGaugeAtomWithDimensions(config, Atom.BINDER_CALLS_FIELD_NUMBER, null);

            uploadConfig(config);
            Thread.sleep(WAIT_TIME_SHORT);

            runActivity("StatsdCtsForegroundActivity", "action", "action.show_notification",3_000);

            setAppBreadcrumbPredicate();
            Thread.sleep(WAIT_TIME_SHORT);

            boolean found = false;
            int uid = getUid();
            List<Atom> atomList = getGaugeMetricDataList();
            for (Atom atom : atomList) {
                BinderCalls calls = atom.getBinderCalls();
                boolean classMatches = calls.getServiceClassName().contains(
                        "com.android.server.notification.NotificationManagerService");
                boolean methodMatches = calls.getServiceMethodName()
                        .equals("createNotificationChannels");

                if (calls.getUid() == uid && classMatches && methodMatches) {
                    found = true;
                    assertTrue("Call count should not be negative or equal to 0.",
                            calls.getRecordedCallCount() > 0);
                    assertTrue("Call count should not be negative or equal to 0.",
                            calls.getCallCount() > 0);
                    assertTrue("Wrong latency",
                            calls.getRecordedTotalLatencyMicros() > 0
                            && calls.getRecordedTotalLatencyMicros() < 1000000);
                    assertTrue("Wrong cpu usage",
                            calls.getRecordedTotalCpuMicros() > 0
                            && calls.getRecordedTotalCpuMicros() < 1000000);
                }
            }

            assertTrue("Did not find a matching atom for uid " + uid, found);

        } finally {
            disableBinderStats();
            plugInAc();
        }
    }

    public void testLooperStats() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        try {
            unplugDevice();
            setUpLooperStats();
            StatsdConfig.Builder config = getPulledConfig();
            addGaugeAtomWithDimensions(config, Atom.LOOPER_STATS_FIELD_NUMBER, null);
            uploadConfig(config);
            Thread.sleep(WAIT_TIME_SHORT);

            runActivity("StatsdCtsForegroundActivity", "action", "action.show_notification", 3_000);

            setAppBreadcrumbPredicate();
            Thread.sleep(WAIT_TIME_SHORT);

            List<Atom> atomList = getGaugeMetricDataList();

            boolean found = false;
            int uid = getUid();
            for (Atom atom : atomList) {
                LooperStats stats = atom.getLooperStats();
                String notificationServiceFullName =
                        "com.android.server.notification.NotificationManagerService";
                boolean handlerMatches =
                        stats.getHandlerClassName().equals(
                                notificationServiceFullName + "$WorkerHandler");
                boolean messageMatches =
                        stats.getMessageName().equals(
                                notificationServiceFullName + "$EnqueueNotificationRunnable");
                if (atom.getLooperStats().getUid() == uid && handlerMatches && messageMatches) {
                    found = true;
                    assertTrue(stats.getMessageCount() > 0);
                    assertTrue("Message count should be non-negative.",
                            stats.getMessageCount() > 0);
                    assertTrue("Recorded message count should be non-negative.",
                            stats.getRecordedMessageCount() > 0);
                    assertTrue("Wrong latency",
                            stats.getRecordedTotalLatencyMicros() > 0
                                    && stats.getRecordedTotalLatencyMicros() < 1000000);
                    assertTrue("Wrong cpu usage",
                            stats.getRecordedTotalCpuMicros() > 0
                                    && stats.getRecordedTotalCpuMicros() < 1000000);
                    assertTrue("Wrong max latency",
                            stats.getRecordedMaxLatencyMicros() > 0
                                    && stats.getRecordedMaxLatencyMicros() < 1000000);
                    assertTrue("Wrong max cpu usage",
                            stats.getRecordedMaxCpuMicros() > 0
                                    && stats.getRecordedMaxCpuMicros() < 1000000);
                    assertTrue("Recorded delay message count should be non-negative.",
                            stats.getRecordedDelayMessageCount() > 0);
                    assertTrue("Wrong delay",
                            stats.getRecordedTotalDelayMillis() >= 0
                                    && stats.getRecordedTotalDelayMillis() < 5000);
                    assertTrue("Wrong max delay",
                            stats.getRecordedMaxDelayMillis() >= 0
                                    && stats.getRecordedMaxDelayMillis() < 5000);
                }
            }
            assertTrue("Did not find a matching atom for uid " + uid, found);
        } finally {
            cleanUpLooperStats();
            plugInAc();
        }
    }

    public void testProcessMemoryState() throws Exception {
        if (statsdDisabled()) {
            return;
        }

        // Get ProcessMemoryState as a simple gauge metric.
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.PROCESS_MEMORY_STATE_FIELD_NUMBER, null);
        uploadConfig(config);
        Thread.sleep(WAIT_TIME_SHORT);

        // Start test app.
        try (AutoCloseable a = withActivity("StatsdCtsForegroundActivity", "action",
                "action.show_notification")) {
            Thread.sleep(WAIT_TIME_SHORT);
            // Trigger new pull.
            setAppBreadcrumbPredicate();
        }

        // Assert about ProcessMemoryState for the test app.
        List<Atom> atoms = getGaugeMetricDataList();
        int uid = getUid();
        boolean found = false;
        for (Atom atom : atoms) {
            ProcessMemoryState state = atom.getProcessMemoryState();
            if (state.getUid() != uid) {
                continue;
            }
            found = true;
            assertEquals(DEVICE_SIDE_TEST_PACKAGE, state.getProcessName());
            assertTrue("oom_score should not be negative", state.getOomAdjScore() >= 0);
            assertTrue("page_fault should not be negative", state.getPageFault() >= 0);
            assertTrue("page_major_fault should not be negative", state.getPageMajorFault() >= 0);
            assertTrue("rss_in_bytes should be positive", state.getRssInBytes() > 0);
            assertTrue("cache_in_bytes should not be negative", state.getCacheInBytes() >= 0);
            assertTrue("swap_in_bytes should not be negative", state.getSwapInBytes() >= 0);
            assertTrue("start_time_nanos should be positive", state.getStartTimeNanos() > 0);
            assertTrue("start_time_nanos should be in the past",
                    state.getStartTimeNanos() < System.nanoTime());
        }
        assertTrue("Did not find a matching atom for uid=" + uid, found);
    }

    public void testNativeProcessMemoryState() throws Exception {
        if (statsdDisabled()) {
            return;
        }

        // Get NativeProcessState as a simple gauge metric.
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.NATIVE_PROCESS_MEMORY_STATE_FIELD_NUMBER, null);
        uploadConfig(config);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger new pull.
        setAppBreadcrumbPredicate();

        // Assert about NativeProcessMemoryState for statsd.
        List<Atom> atoms = getGaugeMetricDataList();
        boolean found = false;
        for (Atom atom : atoms) {
            NativeProcessMemoryState state = atom.getNativeProcessMemoryState();
            if (!state.getProcessName().contains("/statsd")) {
                continue;
            }
            found = true;
            assertTrue("uid is below 10000", state.getUid() < 10000);
            assertTrue("page_fault should not be negative", state.getPageFault() >= 0);
            assertTrue("page_major_fault should not be negative", state.getPageMajorFault() >= 0);
            assertTrue("rss_in_bytes should be positive", state.getRssInBytes() > 0);
            assertTrue("start_time_nanos should be positive", state.getStartTimeNanos() > 0);
            assertTrue("start_time_nanos should be in the past",
                    state.getStartTimeNanos() < System.nanoTime());
        }
        assertTrue("Did not find a matching atom for statsd", found);
    }

    public void testProcessMemoryHighWaterMark() throws Exception {
        if (statsdDisabled()) {
            return;
        }

        // Get ProcessMemoryState as a simple gauge metric.
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.PROCESS_MEMORY_HIGH_WATER_MARK_FIELD_NUMBER, null);
        uploadConfig(config);
        Thread.sleep(WAIT_TIME_SHORT);

        // Start test app and trigger a pull while its running.
        try (AutoCloseable a = withActivity("StatsdCtsForegroundActivity", "action",
                "action.show_notification")) {
            setAppBreadcrumbPredicate();
        }
        Thread.sleep(WAIT_TIME_SHORT);

        // Assert about ProcessMemoryHighWaterMark for the test app, statsd and system server.
        List<Atom> atoms = getGaugeMetricDataList();
        int uid = getUid();
        boolean foundTestApp = false;
        boolean foundStatsd = false;
        boolean foundSystemServer = false;
        for (Atom atom : atoms) {
            ProcessMemoryHighWaterMark state = atom.getProcessMemoryHighWaterMark();
            if (state.getUid() == uid) {
                foundTestApp = true;
                assertEquals(DEVICE_SIDE_TEST_PACKAGE, state.getProcessName());
                assertTrue("rss_high_water_mark_in_bytes should be positive",
                        state.getRssHighWaterMarkInBytes() > 0);
            } else if (state.getProcessName().contains("/statsd")) {
                foundStatsd = true;
                assertTrue("rss_high_water_mark_in_bytes should be positive",
                        state.getRssHighWaterMarkInBytes() > 0);
            } else if (state.getProcessName().equals("system")) {
                foundSystemServer = true;
                assertTrue("rss_high_water_mark_in_bytes should be positive",
                        state.getRssHighWaterMarkInBytes() > 0);
            }
        }
        assertTrue("Did not find a matching atom for test app uid=" + uid, foundTestApp);
        assertTrue("Did not find a matching atom for statsd", foundStatsd);
        assertTrue("Did not find a matching atom for system server", foundSystemServer);
    }

    /**
     * The the app id from a uid.
     *
     * @param uid The uid of the app
     *
     * @return The app id of the app
     *
     * @see android.os.UserHandle#getAppId
     */
    private static int getAppId(int uid) {
        return uid % 100000;
    }

    public void testRoleHolder() throws Exception {
        if (statsdDisabled()) {
            return;
        }

        // Make device side test package a role holder
        String callScreenAppRole = "android.app.role.CALL_SCREENING";
        getDevice().executeShellCommand(
                "cmd role add-role-holder " + callScreenAppRole + " " + DEVICE_SIDE_TEST_PACKAGE);

        // Set up what to collect
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.ROLE_HOLDER_FIELD_NUMBER, null);
        uploadConfig(config);
        Thread.sleep(WAIT_TIME_SHORT);

        boolean verifiedKnowRoleState = false;

        // Pull a report
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_SHORT);

        int testAppId = getAppId(getUid());

        for (Atom atom : getGaugeMetricDataList()) {
            AtomsProto.RoleHolder roleHolder = atom.getRoleHolder();

            assertNotNull(roleHolder.getPackageName());
            assertTrue(roleHolder.getUid() >= 0);
            assertNotNull(roleHolder.getRole());

            if (roleHolder.getPackageName().equals(DEVICE_SIDE_TEST_PACKAGE)) {
                assertEquals(testAppId, getAppId(roleHolder.getUid()));
                assertEquals(DEVICE_SIDE_TEST_PACKAGE, roleHolder.getPackageName());
                assertEquals(callScreenAppRole, roleHolder.getRole());

                verifiedKnowRoleState = true;
            }
        }

        assertTrue(verifiedKnowRoleState);
    }

    public void testDangerousPermissionState() throws Exception {
        if (statsdDisabled()) {
            return;
        }

        final int FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED =  1 << 8;
        final int FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED =  1 << 9;

        // Set up what to collect
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.DANGEROUS_PERMISSION_STATE_FIELD_NUMBER, null);
        uploadConfig(config);
        Thread.sleep(WAIT_TIME_SHORT);

        boolean verifiedKnowPermissionState = false;

        // Pull a report
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_SHORT);

        int testAppId = getAppId(getUid());

        for (Atom atom : getGaugeMetricDataList()) {
            DangerousPermissionState permissionState = atom.getDangerousPermissionState();

            assertNotNull(permissionState.getPermissionName());
            assertTrue(permissionState.getUid() >= 0);
            assertNotNull(permissionState.getPackageName());

            if (permissionState.getPackageName().equals(DEVICE_SIDE_TEST_PACKAGE)) {
                assertEquals(testAppId, getAppId(permissionState.getUid()));

                if (permissionState.getPermissionName().equals(
                        "android.permission.ACCESS_FINE_LOCATION")) {
                    assertTrue(permissionState.getIsGranted());
                    assertEquals(0, permissionState.getPermissionFlags() & (~(
                            FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED
                                    | FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED)));

                    verifiedKnowPermissionState = true;
                }
            }
        }

        assertTrue(verifiedKnowPermissionState);
    }

    public void testANROccurred() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        final int atomTag = Atom.ANR_OCCURRED_FIELD_NUMBER;
        createAndUploadConfig(atomTag, false);
        Thread.sleep(WAIT_TIME_SHORT);

        try (AutoCloseable a = withActivity("ANRActivity", null, null)) {
            Thread.sleep(WAIT_TIME_SHORT);
            getDevice().executeShellCommand(
                    "am broadcast -a action_anr -p " + DEVICE_SIDE_TEST_PACKAGE);
            Thread.sleep(11_000);
        }

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        assertEquals(1, data.size());
        assertTrue(data.get(0).getAtom().hasAnrOccurred());
        ANROccurred atom = data.get(0).getAtom().getAnrOccurred();
        assertEquals(ANROccurred.InstantApp.FALSE_VALUE, atom.getIsInstantApp().getNumber());
        assertEquals(ANROccurred.ForegroundState.FOREGROUND_VALUE,
                atom.getForegroundState().getNumber());
        assertEquals(ErrorSource.DATA_APP, atom.getErrorSource());
        assertEquals(DEVICE_SIDE_TEST_PACKAGE, atom.getPackageName());
    }

    public void testWriteRawTestAtom() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        final int atomTag = Atom.TEST_ATOM_REPORTED_FIELD_NUMBER;
        createAndUploadConfig(atomTag, true);
        Thread.sleep(WAIT_TIME_SHORT);

        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testWriteRawTestAtom");

        Thread.sleep(WAIT_TIME_SHORT);
        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();
        assertEquals(data.size(), 4);

        TestAtomReported atom = data.get(0).getAtom().getTestAtomReported();
        List<AttributionNode> attrChain = atom.getAttributionNodeList();
        assertEquals(2, attrChain.size());
        assertEquals(1234, attrChain.get(0).getUid());
        assertEquals("tag1", attrChain.get(0).getTag());
        assertEquals(getUid(), attrChain.get(1).getUid());
        assertEquals("tag2", attrChain.get(1).getTag());

        assertEquals(42, atom.getIntField());
        assertEquals(Long.MAX_VALUE, atom.getLongField());
        assertEquals(3.14f, atom.getFloatField());
        assertEquals("This is a basic test!", atom.getStringField());
        assertEquals(false, atom.getBooleanField());
        assertEquals(TestAtomReported.State.ON_VALUE, atom.getState().getNumber());
        List<Long> expIds = atom.getBytesField().getExperimentIdList();
        assertEquals(3, expIds.size());
        assertEquals(1L, (long) expIds.get(0));
        assertEquals(2L, (long) expIds.get(1));
        assertEquals(3L, (long) expIds.get(2));

        atom = data.get(1).getAtom().getTestAtomReported();
        attrChain = atom.getAttributionNodeList();
        assertEquals(2, attrChain.size());
        assertEquals(9999, attrChain.get(0).getUid());
        assertEquals("tag9999", attrChain.get(0).getTag());
        assertEquals(getUid(), attrChain.get(1).getUid());
        assertEquals("", attrChain.get(1).getTag());

        assertEquals(100, atom.getIntField());
        assertEquals(Long.MIN_VALUE, atom.getLongField());
        assertEquals(-2.5f, atom.getFloatField());
        assertEquals("Test null uid", atom.getStringField());
        assertEquals(true, atom.getBooleanField());
        assertEquals(TestAtomReported.State.UNKNOWN_VALUE, atom.getState().getNumber());
        expIds = atom.getBytesField().getExperimentIdList();
        assertEquals(3, expIds.size());
        assertEquals(1L, (long) expIds.get(0));
        assertEquals(2L, (long) expIds.get(1));
        assertEquals(3L, (long) expIds.get(2));

        atom = data.get(2).getAtom().getTestAtomReported();
        attrChain = atom.getAttributionNodeList();
        assertEquals(1, attrChain.size());
        assertEquals(getUid(), attrChain.get(0).getUid());
        assertEquals("tag1", attrChain.get(0).getTag());

        assertEquals(-256, atom.getIntField());
        assertEquals(-1234567890L, atom.getLongField());
        assertEquals(42.01f, atom.getFloatField());
        assertEquals("Test non chained", atom.getStringField());
        assertEquals(true, atom.getBooleanField());
        assertEquals(TestAtomReported.State.OFF_VALUE, atom.getState().getNumber());
        expIds = atom.getBytesField().getExperimentIdList();
        assertEquals(3, expIds.size());
        assertEquals(1L, (long) expIds.get(0));
        assertEquals(2L, (long) expIds.get(1));
        assertEquals(3L, (long) expIds.get(2));

        atom = data.get(3).getAtom().getTestAtomReported();
        attrChain = atom.getAttributionNodeList();
        assertEquals(1, attrChain.size());
        assertEquals(getUid(), attrChain.get(0).getUid());
        assertEquals("", attrChain.get(0).getTag());

        assertEquals(0, atom.getIntField());
        assertEquals(0L, atom.getLongField());
        assertEquals(0f, atom.getFloatField());
        assertEquals("", atom.getStringField());
        assertEquals(true, atom.getBooleanField());
        assertEquals(TestAtomReported.State.OFF_VALUE, atom.getState().getNumber());
        expIds = atom.getBytesField().getExperimentIdList();
        assertEquals(0, expIds.size());
    }

}
