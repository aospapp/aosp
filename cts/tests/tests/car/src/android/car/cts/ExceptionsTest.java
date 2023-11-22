/*
 * Copyright (C) 2015 The Android Open Source Project
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
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import android.car.CarNotConnectedException;
import android.platform.test.annotations.RequiresDevice;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.FeatureUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RequiresDevice
@RunWith(AndroidJUnit4.class)
public class ExceptionsTest {
    private static final String MESSAGE = "Oops!";
    private static final Exception CAUSE = new RuntimeException();

    @Before
    public void setUp() {
        assumeTrue(FeatureUtil.isAutomotive());
    }

    @Test
    public void testCarNotConnectedException() {
        CarNotConnectedException exception = new CarNotConnectedException();
        assertNull(exception.getMessage());
        assertNull(exception.getCause());

        exception = new CarNotConnectedException(MESSAGE);
        assertEquals(MESSAGE, exception.getMessage());
        assertNull(exception.getCause());

        exception = new CarNotConnectedException(MESSAGE, CAUSE);
        assertEquals(MESSAGE, exception.getMessage());
        assertEquals(CAUSE, exception.getCause());

        exception = new CarNotConnectedException(CAUSE);
        assertEquals(CAUSE, exception.getCause());
    }
}
