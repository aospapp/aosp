/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.location.cts.common;

import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.util.Log;

import junit.framework.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Used for receiving GPS satellite measurements from the GPS engine.
 * Each measurement contains raw and computed data identifying a satellite.
 * Only counts measurement events with more than one actual Measurement in them (not just clock)
 */
public class TestGnssMeasurementListener extends GnssMeasurementsEvent.Callback {
    // When filterByEventSize flag is true, we only keep the GnssMeasurementsEvents that have at
    // least 4 decoded GnssMeasurement in same constellation.
    private boolean filterByEventSize = false;
    // Timeout in sec for count down latch wait
    private static final int STATUS_TIMEOUT_IN_SEC = 10;
    public static final int MEAS_TIMEOUT_IN_SEC = 75;
    public static final int CORRELATION_VECTOR_TIMEOUT_IN_SEC = 10;
    private static final int BIAS_UNCERTAINTY_TIMEOUT_IN_SEC = 10;
    private static final int C_TO_N0_THRESHOLD_DB_HZ = 18;
    private static final double BIAS_UNCERTAINTY_THRESHOLD_NANOS = 1e6; // 1 millisecond
    private volatile int mStatus = -1;

    private final String mTag;
    private final List<GnssMeasurementsEvent> mMeasurementsEvents;
    private final CountDownLatch mCountDownLatch;
    private final CountDownLatch mCountDownLatchStatus;
    private final CountDownLatch mCountDownLatchBiasUncertainty;
    private final CountDownLatch mCountDownLatchSatellitePvt;
    private final CountDownLatch mCountDownLatchCorrelationVector;

    /**
    * Constructor for TestGnssMeasurementListener
    * @param tag for Logging.
    */
    public TestGnssMeasurementListener(String tag) {
        this(tag, 0, false);
    }

    /**
    * Constructor for TestGnssMeasurementListener
    * @param tag for Logging.
    * @param eventsToCollect wait until the number of events collected.
    */
    public TestGnssMeasurementListener(String tag, int eventsToCollect) {
        this(tag, eventsToCollect, false);
    }

    /**
     * Constructor for TestGnssMeasurementListener
     *
     * @param tag               tag for Logging.
     * @param eventsToCollect   wait until this number of events collected.
     * @param filterByEventSize whether to filter the GnssMeasurementsEvents when we collect them.
     */
    public TestGnssMeasurementListener(String tag, int eventsToCollect, boolean filterByEventSize) {
        mTag = tag;
        mCountDownLatch = new CountDownLatch(eventsToCollect);
        mCountDownLatchStatus = new CountDownLatch(1);
        mCountDownLatchBiasUncertainty = new CountDownLatch(1);
        mCountDownLatchSatellitePvt = new CountDownLatch(1);
        mCountDownLatchCorrelationVector = new CountDownLatch(1);
        mMeasurementsEvents = new ArrayList<>(eventsToCollect);
        this.filterByEventSize = filterByEventSize;
    }

    @Override
    public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
        // Only count measurement events with more than 4 actual Measurements in same constellation
        // with Cn0DbHz value greater than 18
        if (event.getMeasurements().size() > 0) {
            Log.i(mTag, "GnssMeasurementsEvent size:" + event.getMeasurements().size());
            if (filterByEventSize) {
                HashMap<Integer, Integer> constellationEventCount = new HashMap<>();
                GnssClock gnssClock = event.getClock();
                if (!gnssClock.hasFullBiasNanos()) {
                    // If devices does not have FullBiasNanos yet, it will be difficult to check
                    // the quality, so await this flag as well.
                    return;
                }
                for (GnssMeasurement gnssMeasurement : event.getMeasurements()){
                    int constellationType = gnssMeasurement.getConstellationType();
                    // if the measurement's signal level is too small ignore
                    if (gnssMeasurement.getCn0DbHz() < C_TO_N0_THRESHOLD_DB_HZ ||
                        (gnssMeasurement.getState() & GnssMeasurement.STATE_TOW_DECODED) == 0) {
                        continue;
                    }
                    if (constellationEventCount.containsKey(constellationType)) {
                        constellationEventCount.put(constellationType,
                            constellationEventCount.get(constellationType) + 1);
                    }
                    else {
                        constellationEventCount.put(constellationType, 1);
                    }
                    if (constellationEventCount.get(constellationType) >= 4) {
                        synchronized(mMeasurementsEvents) {
                            mMeasurementsEvents.add(event);
                        }
                        mCountDownLatch.countDown();
                        return;
                    }
                }
            } else {
                synchronized(mMeasurementsEvents) {
                    mMeasurementsEvents.add(event);
                }
                mCountDownLatch.countDown();
            }

            if (mCountDownLatchSatellitePvt.getCount() > 0) {
                for (GnssMeasurement measurement : event.getMeasurements()) {
                    if (measurement.hasSatellitePvt()) {
                        Log.i(mTag, "Found a GnssMeasurement with SatellitePvt.");
                        mCountDownLatchSatellitePvt.countDown();
                    }
                }
            }
            if (mCountDownLatchCorrelationVector.getCount() > 0) {
                for (GnssMeasurement measurement : event.getMeasurements()) {
                    if (measurement.hasCorrelationVectors()) {
                        Log.i(mTag, "Found a GnssMeasurement with CorrelationVector.");
                        mCountDownLatchCorrelationVector.countDown();
                    }
                }
            }
            GnssClock gnssClock = event.getClock();
            if (gnssClock.hasBiasUncertaintyNanos()) {
                if (gnssClock.getBiasUncertaintyNanos() < BIAS_UNCERTAINTY_THRESHOLD_NANOS) {
                    mCountDownLatchBiasUncertainty.countDown();
                }
            }
        }
    }

    @Override
    public void onStatusChanged(int status) {
        mStatus = status;
        mCountDownLatchStatus.countDown();
    }

    public boolean awaitStatus() throws InterruptedException {
        return TestUtils.waitFor(mCountDownLatchStatus, STATUS_TIMEOUT_IN_SEC);
    }

    public boolean await() throws InterruptedException {
        return await(MEAS_TIMEOUT_IN_SEC);
    }

    public boolean await(int seconds) throws InterruptedException {
        return TestUtils.waitFor(mCountDownLatch, seconds);
    }

    /**
     * Wait until {@link GnssClock#getBiasUncertaintyNanos()} becomes small enough.
     */
    public boolean awaitSmallBiasUncertainty() throws InterruptedException {
        return TestUtils.waitFor(mCountDownLatchBiasUncertainty, BIAS_UNCERTAINTY_TIMEOUT_IN_SEC);
    }

    /**
     * Wait until a measurement with {@link GnssMeasurement#hasSatellitePvt()} is found.
     */
    public boolean awaitSatellitePvt() throws InterruptedException {
        return TestUtils.waitFor(mCountDownLatchSatellitePvt, MEAS_TIMEOUT_IN_SEC);
    }

    /**
     * Wait until a measurement with {@link GnssMeasurement#hasCorrelationVectors()} is found.
     */
    public boolean awaitCorrelationVector() throws InterruptedException {
        return TestUtils.waitFor(mCountDownLatchCorrelationVector,
                CORRELATION_VECTOR_TIMEOUT_IN_SEC);
    }

    /**
     * @return {@code true} if the state of the test ensures that data is expected to be collected,
     *         {@code false} otherwise.
     */
    public boolean verifyStatus() {
        switch (getStatus()) {
            case GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED:
                String message = "GnssMeasurements is not supported in the device:"
                        + " verifications performed by this test may be skipped on older devices.";
                Assert.fail(message);
                return false;
            case GnssMeasurementsEvent.Callback.STATUS_READY:
                return true;
            case GnssMeasurementsEvent.Callback.STATUS_LOCATION_DISABLED:
                message =  "Location or GPS is disabled on the device:"
                        + " enable location to continue the test";
                Assert.fail(message);
                return false;
            default:
                Assert.fail("GnssMeasurementsEvent status callback was not received.");
        }
        return false;
    }

    /**
     * Get GPS Measurements Status.
     *
     * @return mStatus Gps Measurements Status
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * Get the current list of GPS Measurements Events.
     *
     * @return the current list of GPS Measurements Events
     */
    public List<GnssMeasurementsEvent> getEvents() {
        synchronized(mMeasurementsEvents) {
            List<GnssMeasurementsEvent> clone = new ArrayList<>();
            clone.addAll(mMeasurementsEvents);
            return clone;
        }
    }
}
