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
package android.cts.statsd.validation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.cts.statsd.atom.DeviceAtomTestCase;
import android.os.BatteryStatsProto;
import android.os.UidProto;
import android.os.UidProto.Package;
import android.os.UidProto.Package.Service;

import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.DeviceCalculatedPowerBlameUid;
import com.android.os.StatsLog.DimensionsValue;
import com.android.os.StatsLog.CountMetricData;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.List;

/**
 * Side-by-side comparison between statsd and batterystats.
 */
public class BatteryStatsValidationTests extends DeviceAtomTestCase {

    private static final String TAG = "Statsd.BatteryStatsValidationTests";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        resetBatteryStatus();
        unplugDevice();
    }

    @Override
    protected void tearDown() throws Exception {
        plugInUsb();
    }

    public void testConnectivityStateChange() throws Exception {
        if (!hasFeature(FEATURE_WIFI, true)) return;
        if (!hasFeature(FEATURE_WATCH, false)) return;
        final String fileName = "BATTERYSTATS_CONNECTIVITY_STATE_CHANGE_COUNT.pbtxt";
        StatsdConfig config = createValidationUtil().getConfig(fileName);
        LogUtil.CLog.d("Updating the following config:\n" + config.toString());
        uploadConfig(config);

        Thread.sleep(WAIT_TIME_SHORT);

        turnOnAirplaneMode();
        turnOffAirplaneMode();
        // wait for long enough for device to restore connection
        Thread.sleep(13_000);

        BatteryStatsProto batterystatsProto = getBatteryStatsProto();
        List<CountMetricData> countMetricData = getCountMetricDataList();
        assertEquals(1, countMetricData.size());
        assertEquals(1, countMetricData.get(0).getBucketInfoCount());
        assertTrue(countMetricData.get(0).getBucketInfo(0).getCount() >= 2);
        assertEquals(batterystatsProto.getSystem().getMisc().getNumConnectivityChanges(),
                countMetricData.get(0).getBucketInfo(0).getCount());
    }

    public void testPowerUse() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_LEANBACK_ONLY, false)) return;
        resetBatteryStats();
        unplugDevice();

        final double ALLOWED_FRACTIONAL_DIFFERENCE = 0.8; // ratio that statsd and bs can differ

        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.DEVICE_CALCULATED_POWER_USE_FIELD_NUMBER, null);
        uploadConfig(config);
        unplugDevice();

        Thread.sleep(WAIT_TIME_LONG);
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testSimpleCpu");
        Thread.sleep(WAIT_TIME_LONG);

        setAppBreadcrumbPredicate();
        BatteryStatsProto batterystatsProto = getBatteryStatsProto();
        Thread.sleep(WAIT_TIME_LONG);
        List<Atom> atomList = getGaugeMetricDataList();

        // Extract statsd data
        Atom atom = atomList.get(0);
        long statsdPowerNas = atom.getDeviceCalculatedPowerUse().getComputedPowerNanoAmpSecs();
        assertTrue("Statsd: Non-positive power value.", statsdPowerNas > 0);

        // Extract BatteryStats data
        double bsPowerNas = batterystatsProto.getSystem().getPowerUseSummary().getComputedPowerMah()
                * 1_000_000L * 3600L; /* mAh to nAs */
        assertTrue("BatteryStats: Non-positive power value.", bsPowerNas > 0);

        assertTrue(
                String.format("Statsd (%d) < Batterystats (%f)", statsdPowerNas, bsPowerNas),
                statsdPowerNas > ALLOWED_FRACTIONAL_DIFFERENCE * bsPowerNas);
        assertTrue(
                String.format("Batterystats (%f) < Statsd (%d)", bsPowerNas, statsdPowerNas),
                bsPowerNas > ALLOWED_FRACTIONAL_DIFFERENCE * statsdPowerNas);
    }

    public void testPowerBlameUid() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_LEANBACK_ONLY, false)) return;
        resetBatteryStats();
        unplugDevice();

        final double ALLOWED_FRACTIONAL_DIFFERENCE = 0.8; // ratio that statsd and bs can differ

        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.DEVICE_CALCULATED_POWER_BLAME_UID_FIELD_NUMBER,
                null);
        uploadConfig(config);
        unplugDevice();

        Thread.sleep(WAIT_TIME_LONG);
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testSimpleCpu");
        Thread.sleep(WAIT_TIME_LONG);

        setAppBreadcrumbPredicate();
        BatteryStatsProto batterystatsProto = getBatteryStatsProto();
        Thread.sleep(WAIT_TIME_LONG);
        List<Atom> atomList = getGaugeMetricDataList();

        // Extract statsd data
        boolean uidFound = false;
        int uid = getUid();
        long statsdUidPowerNas = 0;
        for (Atom atom : atomList) {
            DeviceCalculatedPowerBlameUid item = atom.getDeviceCalculatedPowerBlameUid();
            if (item.getUid() == uid) {
                assertFalse("Found multiple power values for uid " + uid, uidFound);
                uidFound = true;
                statsdUidPowerNas = item.getPowerNanoAmpSecs();
            }
        }
        assertTrue("Statsd: No power value for uid " + uid, uidFound);
        assertTrue("Statsd: Non-positive power value for uid " + uid, statsdUidPowerNas > 0);

        // Extract batterystats data
        double bsUidPowerNas = -1;
        boolean hadUid = false;
        for (UidProto uidProto : batterystatsProto.getUidsList()) {
            if (uidProto.getUid() == uid) {
                hadUid = true;
                bsUidPowerNas = uidProto.getPowerUseItem().getComputedPowerMah()
                        * 1_000_000L * 3600L; /* mAh to nAs */;
            }
        }
        assertTrue("Batterystats: No power value for uid " + uid, hadUid);
        assertTrue("BatteryStats: Non-positive power value for uid " + uid, bsUidPowerNas > 0);

        assertTrue(
                String.format("Statsd (%d) < Batterystats (%f).", statsdUidPowerNas, bsUidPowerNas),
                statsdUidPowerNas > ALLOWED_FRACTIONAL_DIFFERENCE * bsUidPowerNas);
        assertTrue(
                String.format("Batterystats (%f) < Statsd (%d).", bsUidPowerNas, statsdUidPowerNas),
                bsUidPowerNas > ALLOWED_FRACTIONAL_DIFFERENCE * statsdUidPowerNas);
    }

    public void testServiceStartCount() throws Exception {
        final String fileName = "BATTERYSTATS_SERVICE_START_COUNT.pbtxt";
        StatsdConfig config = createValidationUtil().getConfig(fileName);
        LogUtil.CLog.d("Updating the following config:\n" + config.toString());
        uploadConfig(config);

        Thread.sleep(WAIT_TIME_SHORT);

        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testForegroundService");

        BatteryStatsProto batterystatsProto = getBatteryStatsProto();
        List<CountMetricData> countMetricData = getCountMetricDataList();
        assertTrue(countMetricData.size() > 0);
        int uid = getUid();
        long countFromStatsd = 0;
        for (CountMetricData data : countMetricData) {
            List<DimensionsValue> dims = data.getDimensionLeafValuesInWhatList();
            if (dims.get(0).getValueInt() == uid) {
                assertEquals(DEVICE_SIDE_TEST_PACKAGE, dims.get(1).getValueStr());
                assertEquals(dims.get(2).getValueStr(), DEVICE_SIDE_TEST_FOREGROUND_SERVICE_NAME);
                countFromStatsd = data.getBucketInfo(0).getCount();
                assertTrue(countFromStatsd > 0);
            }
        }
        long countFromBS = 0;
        for (UidProto uidProto : batterystatsProto.getUidsList()) {
            if (uidProto.getUid() == uid) {
                for (Package pkg : uidProto.getPackagesList()) {
                    if (pkg.getName().equals(DEVICE_SIDE_TEST_PACKAGE)) {
                        for (Service svc : pkg.getServicesList()) {
                            if (svc.getName().equals(DEVICE_SIDE_TEST_FOREGROUND_SERVICE_NAME)) {
                                countFromBS = svc.getStartCount();
                                assertTrue(countFromBS > 0);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(countFromStatsd > 0);
        assertTrue(countFromBS > 0);
        assertEquals(countFromBS, countFromStatsd);
    }

    public void testServiceLaunchCount() throws Exception {
        final String fileName = "BATTERYSTATS_SERVICE_LAUNCH_COUNT.pbtxt";
        StatsdConfig config = createValidationUtil().getConfig(fileName);
        LogUtil.CLog.d("Updating the following config:\n" + config.toString());
        uploadConfig(config);

        Thread.sleep(WAIT_TIME_SHORT);

        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testForegroundService");

        BatteryStatsProto batterystatsProto = getBatteryStatsProto();
        List<CountMetricData> countMetricData = getCountMetricDataList();
        assertTrue(countMetricData.size() > 0);
        int uid = getUid();
        long countFromStatsd = 0;
        for (CountMetricData data : countMetricData) {
            List<DimensionsValue> dims = data.getDimensionLeafValuesInWhatList();
            if (dims.get(0).getValueInt() == uid) {
                assertEquals(DEVICE_SIDE_TEST_PACKAGE, dims.get(1).getValueStr());
                assertEquals(DEVICE_SIDE_TEST_FOREGROUND_SERVICE_NAME, dims.get(2).getValueStr());
                countFromStatsd = data.getBucketInfo(0).getCount();
                assertTrue(countFromStatsd > 0);
            }
        }
        long countFromBS = 0;
        for (UidProto uidProto : batterystatsProto.getUidsList()) {
            if (uidProto.getUid() == uid) {
                for (Package pkg : uidProto.getPackagesList()) {
                    if (pkg.getName().equals(DEVICE_SIDE_TEST_PACKAGE)) {
                        for (Service svc : pkg.getServicesList()) {
                            if (svc.getName().equals(DEVICE_SIDE_TEST_FOREGROUND_SERVICE_NAME)) {
                                countFromBS = svc.getLaunchCount();
                                assertTrue(countFromBS > 0);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(countFromStatsd > 0);
        assertTrue(countFromBS > 0);
        assertEquals(countFromBS, countFromStatsd);
    }
}
