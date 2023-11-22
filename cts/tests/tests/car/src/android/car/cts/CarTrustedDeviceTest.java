/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.car.cts;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CarTrustedDeviceTest extends CarApiTestBase {
    Context mContext;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void testDefaultTrustAgent_onlyHaveCarBleTrustAgent() {
        assertEquals("CarBleTrustAgent should be the only trust agent in AAE builds",
                mContext.getResources().getString(
                        com.android.internal.R.string.config_defaultTrustAgent),
                "com.android.car/com.android.car.trust.CarBleTrustAgent");
    }
}
