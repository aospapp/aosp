/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.time.cts.host;

import static android.app.time.cts.shell.DeviceConfigKeys.NAMESPACE_SYSTEM_TIME;
import static android.app.time.cts.shell.DeviceConfigShellHelper.SYNC_DISABLED_MODE_UNTIL_REBOOT;
import static android.app.time.cts.shell.FakeTimeZoneProviderAppShellHelper.FAKE_TZPS_APP_APK;
import static android.app.time.cts.shell.FakeTimeZoneProviderAppShellHelper.FAKE_TZPS_APP_PACKAGE;

import static java.util.stream.Collectors.toList;

import android.app.time.cts.shell.DeviceConfigShellHelper;
import android.app.time.cts.shell.DeviceShellCommandExecutor;
import android.app.time.cts.shell.FakeTimeZoneProviderAppShellHelper;
import android.app.time.cts.shell.LocationShellHelper;
import android.app.time.cts.shell.LocationTimeZoneManagerShellHelper;
import android.app.time.cts.shell.TimeZoneDetectorShellHelper;
import android.app.time.cts.shell.host.HostShellCommandExecutor;
import android.cts.statsdatom.lib.AtomTestUtils;
import android.cts.statsdatom.lib.ConfigUtils;
import android.cts.statsdatom.lib.DeviceUtils;
import android.cts.statsdatom.lib.ReportUtils;

import com.android.os.AtomsProto;
import com.android.os.AtomsProto.LocationTimeZoneProviderStateChanged;
import com.android.os.StatsLog;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Host-side CTS tests for the location time zone manager service stats logging. */
@RunWith(DeviceJUnit4ClassRunner.class)
public class LocationTimeZoneManagerStatsTest extends BaseHostJUnit4Test {

    private static final int PRIMARY_PROVIDER_INDEX = 0;
    private static final int SECONDARY_PROVIDER_INDEX = 1;

    private static final int PROVIDER_STATES_COUNT =
            LocationTimeZoneProviderStateChanged.State.values().length;

    private TimeZoneDetectorShellHelper mTimeZoneDetectorShellHelper;
    private LocationTimeZoneManagerShellHelper mLocationTimeZoneManagerShellHelper;
    private LocationShellHelper mLocationShellHelper;
    private DeviceConfigShellHelper mDeviceConfigShellHelper;
    private DeviceConfigShellHelper.PreTestState mDeviceConfigPreTestState;

    private boolean mOriginalLocationEnabled;
    private boolean mOriginalAutoDetectionEnabled;
    private boolean mOriginalGeoDetectionEnabled;

    @Before
    public void setUp() throws Exception {
        ITestDevice device = getDevice();
        DeviceShellCommandExecutor shellCommandExecutor = new HostShellCommandExecutor(device);
        mLocationTimeZoneManagerShellHelper =
                new LocationTimeZoneManagerShellHelper(shellCommandExecutor);

        // Confirm the service being tested is present. It can be turned off, in which case there's
        // nothing to test.
        mLocationTimeZoneManagerShellHelper.assumeLocationTimeZoneManagerIsPresent();

        // Install the app that hosts the fake providers.
        // Installations are tracked in BaseHostJUnit4Test and uninstalled automatically.
        installPackage(FAKE_TZPS_APP_APK);

        mTimeZoneDetectorShellHelper = new TimeZoneDetectorShellHelper(shellCommandExecutor);
        mLocationShellHelper = new LocationShellHelper(shellCommandExecutor);
        mDeviceConfigShellHelper = new DeviceConfigShellHelper(shellCommandExecutor);

        mDeviceConfigPreTestState = mDeviceConfigShellHelper.setSyncModeForTest(
                SYNC_DISABLED_MODE_UNTIL_REBOOT, NAMESPACE_SYSTEM_TIME);

        // All tests start with the location_time_zone_manager disabled so that providers can be
        // configured.
        mLocationTimeZoneManagerShellHelper.stop();

        // Make sure locations is enabled, otherwise the geo detection feature will be disabled
        // whatever the geolocation detection setting is set to.
        mOriginalLocationEnabled = mLocationShellHelper.isLocationEnabledForCurrentUser();
        if (!mOriginalLocationEnabled) {
            mLocationShellHelper.setLocationEnabledForCurrentUser(true);
        }

        // Make sure automatic time zone detection is enabled, otherwise the geo detection feature
        // will be disabled whatever the geolocation detection setting is set to
        mOriginalAutoDetectionEnabled = mTimeZoneDetectorShellHelper.isAutoDetectionEnabled();
        if (!mOriginalAutoDetectionEnabled) {
            mTimeZoneDetectorShellHelper.setAutoDetectionEnabled(true);
        }

        // On devices with no location time zone providers (e.g. AOSP), we cannot turn geo detection
        // on until the test LTZPs are configured as the time_zone_detector will refuse.
        mOriginalGeoDetectionEnabled = mTimeZoneDetectorShellHelper.isGeoDetectionEnabled();

        // Make sure that the fake providers used in the tests are available.
        FakeTimeZoneProviderAppShellHelper fakeTimeZoneProviderAppShellHelper =
                new FakeTimeZoneProviderAppShellHelper(shellCommandExecutor);
        fakeTimeZoneProviderAppShellHelper.waitForInstallation();

        ConfigUtils.removeConfig(device);
        ReportUtils.clearReports(device);
    }

    @After
    public void tearDown() throws Exception {
        if (!mLocationTimeZoneManagerShellHelper.isLocationTimeZoneManagerPresent()) {
            // Nothing to tear down.
            return;
        }

        // Reset the geoDetectionEnabled state while there is at least one LTZP configured: this
        // setting cannot be modified if there are no LTZPs on the device, e.g. on AOSP.
        mTimeZoneDetectorShellHelper.setGeoDetectionEnabled(mOriginalGeoDetectionEnabled);

        // Turn off the service before we reset configuration, otherwise it will restart itself
        // repeatedly.
        mLocationTimeZoneManagerShellHelper.stop();

        // Reset settings and server flags as best we can.
        if (mTimeZoneDetectorShellHelper.isAutoDetectionEnabled()
                != mOriginalAutoDetectionEnabled) {
            mTimeZoneDetectorShellHelper.setAutoDetectionEnabled(mOriginalAutoDetectionEnabled);
        }
        mLocationShellHelper.setLocationEnabledForCurrentUser(mOriginalLocationEnabled);

        ConfigUtils.removeConfig(getDevice());
        ReportUtils.clearReports(getDevice());
        mDeviceConfigShellHelper.restoreDeviceConfigStateForTest(mDeviceConfigPreTestState);

        // Attempt to start the service. It may not start if there are no providers configured,
        // but that is ok.
        mLocationTimeZoneManagerShellHelper.start();
    }

    @Test
    public void testAtom_locationTimeZoneProviderStateChanged() throws Exception {
        ConfigUtils.uploadConfigForPushedAtom(getDevice(), DeviceUtils.STATSD_ATOM_TEST_PKG,
                AtomsProto.Atom.LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED_FIELD_NUMBER);

        String testPrimaryLocationTimeZoneProviderPackageName = null;
        String testSecondaryLocationTimeZoneProviderPackageName = FAKE_TZPS_APP_PACKAGE;
        mLocationTimeZoneManagerShellHelper.startWithTestProviders(
                testPrimaryLocationTimeZoneProviderPackageName,
                testSecondaryLocationTimeZoneProviderPackageName,
                true /* recordProviderStates */);

        // Turn geo detection on and off, twice.
        for (int i = 0; i < 2; i++) {
            Thread.sleep(AtomTestUtils.WAIT_TIME_SHORT);
            mTimeZoneDetectorShellHelper.setGeoDetectionEnabled(true);
            Thread.sleep(AtomTestUtils.WAIT_TIME_SHORT);
            mTimeZoneDetectorShellHelper.setGeoDetectionEnabled(false);
        }

        // Sorted list of events in order in which they occurred.
        List<StatsLog.EventMetricData> data = ReportUtils.getEventMetricDataList(getDevice());

        // States.
        Set<Integer> primaryProviderCreated = singletonStateId(PRIMARY_PROVIDER_INDEX,
                LocationTimeZoneProviderStateChanged.State.STOPPED);
        Set<Integer> primaryProviderStarted = singletonStateId(PRIMARY_PROVIDER_INDEX,
                LocationTimeZoneProviderStateChanged.State.INITIALIZING);
        Set<Integer> primaryProviderFailed = singletonStateId(PRIMARY_PROVIDER_INDEX,
                LocationTimeZoneProviderStateChanged.State.PERM_FAILED);
        Set<Integer> secondaryProviderCreated = singletonStateId(SECONDARY_PROVIDER_INDEX,
                LocationTimeZoneProviderStateChanged.State.STOPPED);
        Set<Integer> secondaryProviderStarted = singletonStateId(SECONDARY_PROVIDER_INDEX,
                LocationTimeZoneProviderStateChanged.State.INITIALIZING);
        Set<Integer> secondaryProviderStopped = singletonStateId(SECONDARY_PROVIDER_INDEX,
                LocationTimeZoneProviderStateChanged.State.STOPPED);
        Function<AtomsProto.Atom, Integer> eventToStateFunction = atom -> {
            int providerIndex = atom.getLocationTimeZoneProviderStateChanged().getProviderIndex();
            return stateId(providerIndex,
                    atom.getLocationTimeZoneProviderStateChanged().getState());
        };

        // Add state sets to the list in order.
        // Assert that the events happened in the expected order. This does not check "wait" (the
        // time between events).
        List<Set<Integer>> stateSets = Arrays.asList(
                primaryProviderCreated, secondaryProviderCreated,
                primaryProviderStarted, primaryProviderFailed,
                secondaryProviderStarted, secondaryProviderStopped,
                secondaryProviderStarted, secondaryProviderStopped);
        AtomTestUtils.assertStatesOccurredInOrder(stateSets, data,
                0 /* wait */, eventToStateFunction);
    }

    private static Set<Integer> singletonStateId(int providerIndex,
            LocationTimeZoneProviderStateChanged.State state) {
        return Collections.singleton(stateId(providerIndex, state));
    }

    private static List<StatsLog.EventMetricData> extractEventsForProviderIndex(
            List<StatsLog.EventMetricData> data, int providerIndex) {
        return data.stream().filter(event -> {
            if (!event.getAtom().hasLocationTimeZoneProviderStateChanged()) {
                return false;
            }
            return event.getAtom().getLocationTimeZoneProviderStateChanged().getProviderIndex()
                    == providerIndex;
        }).collect(toList());
    }

    /** Maps a (provider index, provider state) pair to an integer state ID. */
    private static Integer stateId(
            int providerIndex, LocationTimeZoneProviderStateChanged.State providerState) {
        return (providerIndex * PROVIDER_STATES_COUNT) + providerState.getNumber();
    }
}
