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

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryPluggedStateEnum;
import android.os.BatteryStatusEnum;
import android.platform.test.annotations.RestrictedBuildTest;
import android.server.DeviceIdleModeEnum;
import android.view.DisplayStateEnum;

import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.AtomsProto.AppBreadcrumbReported;
import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.BatterySaverModeStateChanged;
import com.android.os.AtomsProto.BuildInformation;
import com.android.os.AtomsProto.ConnectivityStateChanged;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.EventMetricData;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Statsd atom tests that are done via adb (hostside).
 */
public class HostAtomTests extends AtomTestCase {

    private static final String TAG = "Statsd.HostAtomTests";

    // Either file must exist to read kernel wake lock stats.
    private static final String WAKE_LOCK_FILE = "/proc/wakelocks";
    private static final String WAKE_SOURCES_FILE = "/d/wakeup_sources";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    public void testScreenStateChangedAtom() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        // Setup, make sure the screen is off and turn off AoD if it is on.
        // AoD needs to be turned off because the screen should go into an off state. But, if AoD is
        // on and the device doesn't support STATE_DOZE, the screen sadly goes back to STATE_ON.
        String aodState = getAodState();
        setAodState("0");
        turnScreenOn();
        Thread.sleep(WAIT_TIME_SHORT);
        turnScreenOff();
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.SCREEN_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> screenOnStates = new HashSet<>(
                Arrays.asList(DisplayStateEnum.DISPLAY_STATE_ON_VALUE,
                        DisplayStateEnum.DISPLAY_STATE_ON_SUSPEND_VALUE,
                        DisplayStateEnum.DISPLAY_STATE_VR_VALUE));
        Set<Integer> screenOffStates = new HashSet<>(
                Arrays.asList(DisplayStateEnum.DISPLAY_STATE_OFF_VALUE,
                        DisplayStateEnum.DISPLAY_STATE_DOZE_VALUE,
                        DisplayStateEnum.DISPLAY_STATE_DOZE_SUSPEND_VALUE,
                        DisplayStateEnum.DISPLAY_STATE_UNKNOWN_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(screenOnStates, screenOffStates);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        turnScreenOn();
        Thread.sleep(WAIT_TIME_LONG);
        turnScreenOff();
        Thread.sleep(WAIT_TIME_LONG);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();
        // reset screen to on
        turnScreenOn();
        // Restores AoD to initial state.
        setAodState(aodState);
        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_LONG,
                atom -> atom.getScreenStateChanged().getState().getNumber());
    }

    public void testChargingStateChangedAtom() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_AUTOMOTIVE, false)) return;
        // Setup, set charging state to full.
        setChargingState(5);
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.CHARGING_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> batteryUnknownStates = new HashSet<>(
                Arrays.asList(BatteryStatusEnum.BATTERY_STATUS_UNKNOWN_VALUE));
        Set<Integer> batteryChargingStates = new HashSet<>(
                Arrays.asList(BatteryStatusEnum.BATTERY_STATUS_CHARGING_VALUE));
        Set<Integer> batteryDischargingStates = new HashSet<>(
                Arrays.asList(BatteryStatusEnum.BATTERY_STATUS_DISCHARGING_VALUE));
        Set<Integer> batteryNotChargingStates = new HashSet<>(
                Arrays.asList(BatteryStatusEnum.BATTERY_STATUS_NOT_CHARGING_VALUE));
        Set<Integer> batteryFullStates = new HashSet<>(
                Arrays.asList(BatteryStatusEnum.BATTERY_STATUS_FULL_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(batteryUnknownStates, batteryChargingStates,
                batteryDischargingStates, batteryNotChargingStates, batteryFullStates);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        setChargingState(1);
        Thread.sleep(WAIT_TIME_SHORT);
        setChargingState(2);
        Thread.sleep(WAIT_TIME_SHORT);
        setChargingState(3);
        Thread.sleep(WAIT_TIME_SHORT);
        setChargingState(4);
        Thread.sleep(WAIT_TIME_SHORT);
        setChargingState(5);
        Thread.sleep(WAIT_TIME_SHORT);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Unfreeze battery state after test
        resetBatteryStatus();
        Thread.sleep(WAIT_TIME_SHORT);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getChargingStateChanged().getState().getNumber());
    }

    public void testPluggedStateChangedAtom() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_AUTOMOTIVE, false)) return;
        // Setup, unplug device.
        unplugDevice();
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.PLUGGED_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> unpluggedStates = new HashSet<>(
                Arrays.asList(BatteryPluggedStateEnum.BATTERY_PLUGGED_NONE_VALUE));
        Set<Integer> acStates = new HashSet<>(
                Arrays.asList(BatteryPluggedStateEnum.BATTERY_PLUGGED_AC_VALUE));
        Set<Integer> usbStates = new HashSet<>(
                Arrays.asList(BatteryPluggedStateEnum.BATTERY_PLUGGED_USB_VALUE));
        Set<Integer> wirelessStates = new HashSet<>(
                Arrays.asList(BatteryPluggedStateEnum.BATTERY_PLUGGED_WIRELESS_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(acStates, unpluggedStates, usbStates,
                unpluggedStates, wirelessStates, unpluggedStates);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        plugInAc();
        Thread.sleep(WAIT_TIME_SHORT);
        unplugDevice();
        Thread.sleep(WAIT_TIME_SHORT);
        plugInUsb();
        Thread.sleep(WAIT_TIME_SHORT);
        unplugDevice();
        Thread.sleep(WAIT_TIME_SHORT);
        plugInWireless();
        Thread.sleep(WAIT_TIME_SHORT);
        unplugDevice();
        Thread.sleep(WAIT_TIME_SHORT);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Unfreeze battery state after test
        resetBatteryStatus();
        Thread.sleep(WAIT_TIME_SHORT);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getPluggedStateChanged().getState().getNumber());
    }

    public void testBatteryLevelChangedAtom() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_AUTOMOTIVE, false)) return;
        // Setup, set battery level to full.
        setBatteryLevel(100);
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.BATTERY_LEVEL_CHANGED_FIELD_NUMBER;

        Set<Integer> batteryLow = new HashSet<>(Arrays.asList(2));
        Set<Integer> battery25p = new HashSet<>(Arrays.asList(25));
        Set<Integer> battery50p = new HashSet<>(Arrays.asList(50));
        Set<Integer> battery75p = new HashSet<>(Arrays.asList(75));
        Set<Integer> batteryFull = new HashSet<>(Arrays.asList(100));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(batteryLow, battery25p, battery50p,
                battery75p, batteryFull);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        setBatteryLevel(2);
        Thread.sleep(WAIT_TIME_SHORT);
        setBatteryLevel(25);
        Thread.sleep(WAIT_TIME_SHORT);
        setBatteryLevel(50);
        Thread.sleep(WAIT_TIME_SHORT);
        setBatteryLevel(75);
        Thread.sleep(WAIT_TIME_SHORT);
        setBatteryLevel(100);
        Thread.sleep(WAIT_TIME_SHORT);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Unfreeze battery state after test
        resetBatteryStatus();
        Thread.sleep(WAIT_TIME_SHORT);

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getBatteryLevelChanged().getBatteryLevel());
    }

    public void testDeviceIdleModeStateChangedAtom() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        // Setup, leave doze mode.
        leaveDozeMode();
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.DEVICE_IDLE_MODE_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> dozeOff = new HashSet<>(
                Arrays.asList(DeviceIdleModeEnum.DEVICE_IDLE_MODE_OFF_VALUE));
        Set<Integer> dozeLight = new HashSet<>(
                Arrays.asList(DeviceIdleModeEnum.DEVICE_IDLE_MODE_LIGHT_VALUE));
        Set<Integer> dozeDeep = new HashSet<>(
                Arrays.asList(DeviceIdleModeEnum.DEVICE_IDLE_MODE_DEEP_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(dozeLight, dozeDeep, dozeOff);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        enterDozeModeLight();
        Thread.sleep(WAIT_TIME_SHORT);
        enterDozeModeDeep();
        Thread.sleep(WAIT_TIME_SHORT);
        leaveDozeMode();
        Thread.sleep(WAIT_TIME_SHORT);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();;

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_SHORT,
                atom -> atom.getDeviceIdleModeStateChanged().getState().getNumber());
    }

    public void testBatterySaverModeStateChangedAtom() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_AUTOMOTIVE, false)) return;
        // Setup, turn off battery saver.
        turnBatterySaverOff();
        Thread.sleep(WAIT_TIME_SHORT);

        final int atomTag = Atom.BATTERY_SAVER_MODE_STATE_CHANGED_FIELD_NUMBER;

        Set<Integer> batterySaverOn = new HashSet<>(
                Arrays.asList(BatterySaverModeStateChanged.State.ON_VALUE));
        Set<Integer> batterySaverOff = new HashSet<>(
                Arrays.asList(BatterySaverModeStateChanged.State.OFF_VALUE));

        // Add state sets to the list in order.
        List<Set<Integer>> stateSet = Arrays.asList(batterySaverOn, batterySaverOff);

        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        // Trigger events in same order.
        turnBatterySaverOn();
        Thread.sleep(WAIT_TIME_LONG);
        turnBatterySaverOff();
        Thread.sleep(WAIT_TIME_LONG);

        // Sorted list of events in order in which they occurred.
        List<EventMetricData> data = getEventMetricDataList();

        // Assert that the events happened in the expected order.
        assertStatesOccurred(stateSet, data, WAIT_TIME_LONG,
                atom -> atom.getBatterySaverModeStateChanged().getState().getNumber());
    }

    @RestrictedBuildTest
    public void testRemainingBatteryCapacity() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WATCH, false)) return;
        if (!hasFeature(FEATURE_AUTOMOTIVE, false)) return;
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.REMAINING_BATTERY_CAPACITY_FIELD_NUMBER, null);

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> data = getGaugeMetricDataList();

        assertTrue(data.size() > 0);
        Atom atom = data.get(0);
        assertTrue(atom.getRemainingBatteryCapacity().hasChargeMicroAmpereHour());
        if (hasBattery()) {
            assertTrue(atom.getRemainingBatteryCapacity().getChargeMicroAmpereHour() > 0);
        }
    }

    @RestrictedBuildTest
    public void testFullBatteryCapacity() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WATCH, false)) return;
        if (!hasFeature(FEATURE_AUTOMOTIVE, false)) return;
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.FULL_BATTERY_CAPACITY_FIELD_NUMBER, null);

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> data = getGaugeMetricDataList();

        assertTrue(data.size() > 0);
        Atom atom = data.get(0);
        assertTrue(atom.getFullBatteryCapacity().hasCapacityMicroAmpereHour());
        if (hasBattery()) {
            assertTrue(atom.getFullBatteryCapacity().getCapacityMicroAmpereHour() > 0);
        }
    }

    public void testBatteryVoltage() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WATCH, false)) return;
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.BATTERY_VOLTAGE_FIELD_NUMBER, null);

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> data = getGaugeMetricDataList();

        assertTrue(data.size() > 0);
        Atom atom = data.get(0);
        assertTrue(atom.getBatteryVoltage().hasVoltageMillivolt());
        if (hasBattery()) {
            assertTrue(atom.getBatteryVoltage().getVoltageMillivolt() > 0);
        }
    }

    // This test is for the pulled battery level atom.
    public void testBatteryLevel() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WATCH, false)) return;
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.BATTERY_LEVEL_FIELD_NUMBER, null);

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> data = getGaugeMetricDataList();

        assertTrue(data.size() > 0);
        Atom atom = data.get(0);
        assertTrue(atom.getBatteryLevel().hasBatteryLevel());
        if (hasBattery()) {
            assertTrue(atom.getBatteryLevel().getBatteryLevel() > 0);
            assertTrue(atom.getBatteryLevel().getBatteryLevel() <= 100);
        }
    }

    // This test is for the pulled battery charge count atom.
    public void testBatteryCycleCount() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WATCH, false)) return;
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.BATTERY_CYCLE_COUNT_FIELD_NUMBER, null);

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> data = getGaugeMetricDataList();

        assertTrue(data.size() > 0);
        Atom atom = data.get(0);
        assertTrue(atom.getBatteryCycleCount().hasCycleCount());
        if (hasBattery()) {
            assertTrue(atom.getBatteryCycleCount().getCycleCount() >= 0);
        }
    }

    public void testKernelWakelock() throws Exception {
        if (statsdDisabled() || !kernelWakelockStatsExist()) {
            return;
        }
        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.KERNEL_WAKELOCK_FIELD_NUMBER, null);

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> data = getGaugeMetricDataList();

        Atom atom = data.get(0);
        assertTrue(!atom.getKernelWakelock().getName().equals(""));
        assertTrue(atom.getKernelWakelock().hasCount());
        assertTrue(atom.getKernelWakelock().hasVersion());
        assertTrue(atom.getKernelWakelock().getVersion() > 0);
        assertTrue(atom.getKernelWakelock().hasTimeMicros());
    }

    // Returns true iff either |WAKE_LOCK_FILE| or |WAKE_SOURCES_FILE| exists.
    private boolean kernelWakelockStatsExist() {
      try {
        return doesFileExist(WAKE_LOCK_FILE) || doesFileExist(WAKE_SOURCES_FILE);
      } catch(Exception e) {
        return false;
      }
    }

    public void testWifiActivityInfo() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WIFI, true)) return;
        if (!hasFeature(FEATURE_WATCH, false)) return;
        if (!checkDeviceFor("checkWifiEnhancedPowerReportingSupported")) return;

        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.WIFI_ACTIVITY_INFO_FIELD_NUMBER, null);

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> dataList = getGaugeMetricDataList();

        for (Atom atom: dataList) {
            assertTrue(atom.getWifiActivityInfo().getTimestampMillis() > 0);
            assertTrue(atom.getWifiActivityInfo().getStackState() >= 0);
            assertTrue(atom.getWifiActivityInfo().getControllerIdleTimeMillis() > 0);
            assertTrue(atom.getWifiActivityInfo().getControllerTxTimeMillis() >= 0);
            assertTrue(atom.getWifiActivityInfo().getControllerRxTimeMillis() >= 0);
            assertTrue(atom.getWifiActivityInfo().getControllerEnergyUsed() >= 0);
        }
    }

    public void testBuildInformation() throws Exception {
        if (statsdDisabled()) {
            return;
        }

        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.BUILD_INFORMATION_FIELD_NUMBER, null);
        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> data = getGaugeMetricDataList();
        assertTrue(data.size() > 0);
        BuildInformation atom = data.get(0).getBuildInformation();
        assertEquals(getProperty("ro.product.brand"),             atom.getBrand());
        assertEquals(getProperty("ro.product.name"),              atom.getProduct());
        assertEquals(getProperty("ro.product.device"),            atom.getDevice());
        assertEquals(getProperty("ro.build.version.release"),     atom.getVersionRelease());
        assertEquals(getProperty("ro.build.id"),                  atom.getId());
        assertEquals(getProperty("ro.build.version.incremental"), atom.getVersionIncremental());
        assertEquals(getProperty("ro.build.type"),                atom.getType());
        assertEquals(getProperty("ro.build.tags"),                atom.getTags());
    }

    public void testOnDevicePowerMeasurement() throws Exception {
        if (!OPTIONAL_TESTS_ENABLED) return;
        if (statsdDisabled()) {
            return;
        }

        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.ON_DEVICE_POWER_MEASUREMENT_FIELD_NUMBER, null);

        uploadConfig(config);

        Thread.sleep(WAIT_TIME_LONG);
        setAppBreadcrumbPredicate();
        Thread.sleep(WAIT_TIME_LONG);

        List<Atom> dataList = getGaugeMetricDataList();

        for (Atom atom: dataList) {
            assertTrue(atom.getOnDevicePowerMeasurement().getMeasurementTimestampMillis() >= 0);
            assertTrue(atom.getOnDevicePowerMeasurement().getEnergyMicrowattSecs() >= 0);
        }
    }

    // Explicitly tests if the adb command to log a breadcrumb is working.
    public void testBreadcrumbAdb() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        final int atomTag = Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER;
        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        doAppBreadcrumbReportedStart(1);
        Thread.sleep(WAIT_TIME_SHORT);

        List<EventMetricData> data = getEventMetricDataList();
        AppBreadcrumbReported atom = data.get(0).getAtom().getAppBreadcrumbReported();
        assertTrue(atom.getLabel() == 1);
        assertTrue(atom.getState().getNumber() == AppBreadcrumbReported.State.START_VALUE);
    }

    // Test dumpsys stats --proto.
    public void testDumpsysStats() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        final int atomTag = Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER;
        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        doAppBreadcrumbReportedStart(1);
        Thread.sleep(WAIT_TIME_SHORT);

        // Get the stats incident section.
        List<ConfigMetricsReportList> listList = getReportsFromStatsDataDumpProto();
        assertTrue(listList.size() > 0);

        // Extract the relevent report from the incident section.
        ConfigMetricsReportList ourList = null;
        int hostUid = getHostUid();
        for (ConfigMetricsReportList list : listList) {
            ConfigMetricsReportList.ConfigKey configKey = list.getConfigKey();
            if (configKey.getUid() == hostUid && configKey.getId() == CONFIG_ID) {
                ourList = list;
                break;
            }
        }
        assertNotNull("Could not find list for uid=" + hostUid
                + " id=" + CONFIG_ID, ourList);

        // Make sure that the report is correct.
        List<EventMetricData> data = getEventMetricDataList(ourList);
        AppBreadcrumbReported atom = data.get(0).getAtom().getAppBreadcrumbReported();
        assertTrue(atom.getLabel() == 1);
        assertTrue(atom.getState().getNumber() == AppBreadcrumbReported.State.START_VALUE);
    }

    public void testConnectivityStateChange() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_WIFI, true)) return;
        if (!hasFeature(FEATURE_WATCH, false)) return;

        final int atomTag = Atom.CONNECTIVITY_STATE_CHANGED_FIELD_NUMBER;
        createAndUploadConfig(atomTag);
        Thread.sleep(WAIT_TIME_SHORT);

        turnOnAirplaneMode();
        // wait long enough for airplane mode events to propagate.
        Thread.sleep(1_200);
        turnOffAirplaneMode();
        // wait long enough for the device to restore connection
        Thread.sleep(13_000);

        List<EventMetricData> data = getEventMetricDataList();
        // at least 1 disconnect and 1 connect
        assertThat(data.size()).isAtLeast(2);
        boolean foundDisconnectEvent = false;
        boolean foundConnectEvent = false;
        for (EventMetricData d : data) {
            ConnectivityStateChanged atom = d.getAtom().getConnectivityStateChanged();
            if(atom.getState().getNumber()
                    == ConnectivityStateChanged.State.DISCONNECTED_VALUE) {
                foundDisconnectEvent = true;
            }
            if(atom.getState().getNumber()
                    == ConnectivityStateChanged.State.CONNECTED_VALUE) {
                foundConnectEvent = true;
            }
        }
        assertTrue(foundConnectEvent && foundDisconnectEvent);
    }
}
