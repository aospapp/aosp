/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.location.cts;

import android.test.AndroidTestCase;

/**
 * Base Test Case class for all Gnss Tests.
 */
public abstract class GnssTestCase extends AndroidTestCase {

    // This is used to mark cts tests as CtsVerifier tests.
    private volatile boolean mCtsVerifierTest = false;

    protected static boolean YEAR_2017_CAPABILITY_ENFORCED = false;

    protected TestLocationManager mTestLocationManager;

    protected GnssTestCase() {
    }

    public void setTestAsCtsVerifierTest(boolean value) {
        mCtsVerifierTest = value;
    }

    public boolean isCtsVerifierTest() {
        return mCtsVerifierTest;
    }
}
