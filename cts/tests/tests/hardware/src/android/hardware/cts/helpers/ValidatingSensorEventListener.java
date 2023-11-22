/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.cts.helpers;

import android.hardware.SensorEvent;
import android.hardware.SensorEventListener2;
import android.hardware.cts.helpers.sensorverification.ISensorVerification;

import java.util.Collection;
import java.util.LinkedList;

/**
 * A {@link TestSensorEventListener} which performs validations on the received events on the fly.
 * This class is useful for long running tests where it is not practical to store all the events to
 * be processed after.
 */
public class ValidatingSensorEventListener extends TestSensorEventListener {

    private final Collection<ISensorVerification> mVerifications =
            new LinkedList<ISensorVerification>();

    /**
     * Construct a {@link ValidatingSensorEventListener} with an additional
     * {@link SensorEventListener2}.
     */
    public ValidatingSensorEventListener(SensorEventListener2 listener,
            ISensorVerification ... verifications) {
        super(listener);
        for (ISensorVerification verification : verifications) {
            mVerifications.add(verification);
        }
    }

    /**
     * Construct a {@link ValidatingSensorEventListener} with an additional
     * {@link SensorEventListener2}.
     */
    public ValidatingSensorEventListener(SensorEventListener2 listener,
            Collection<ISensorVerification> verifications) {
        this(listener, verifications.toArray(new ISensorVerification[0]));
    }

    /**
     * Construct a {@link ValidatingSensorEventListener}.
     */
    public ValidatingSensorEventListener(ISensorVerification ... verifications) {
        this(null, verifications);
    }

    /**
     * Construct a {@link ValidatingSensorEventListener}.
     */
    public ValidatingSensorEventListener(Collection<ISensorVerification> verifications) {
        this(null, verifications);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        TestSensorEvent testEvent = new TestSensorEvent(event);
        for (ISensorVerification verification : mVerifications) {
            verification.addSensorEvent(testEvent);
        }
        super.onSensorChanged(event);
    }
}
