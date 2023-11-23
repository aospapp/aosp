/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.junit.Assert.*;

import android.content.AttributionSource;
import android.content.Context;
import android.platform.test.annotations.AsbSecurityTest;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.sts.common.util.StsExtraBusinessLogicTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
public class AttributionSourceTest extends StsExtraBusinessLogicTestCase {
    private static final String TAG = "AttributionSourceTest";

    private static final int WAIT_TIME = 2000; // Time to wait for process to launch (ms).

    public static final String ATTRIBUTION_SOURCE_KEY = "attributionSource";

    @AsbSecurityTest(cveBugId = 200288596)
    @Test
    public void testPidCheck() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AttributionSource attributionSource =
                new AttributionSource(
                        (AttributionSource)
                                Context.class.getMethod("getAttributionSource").invoke(context),
                        null);

        Field attSourceStateField =
                attributionSource.getClass().getDeclaredField("mAttributionSourceState");
        attSourceStateField.setAccessible(true);

        Object attSourceState = attSourceStateField.get(attributionSource);
        attSourceState.getClass().getField("pid").setInt(attSourceState, 0);
        final AttributionSource attributionSourceFinal = attributionSource;
        assertThrows(SecurityException.class, () -> attributionSourceFinal.enforceCallingPid());
    }

    @Test
    @AsbSecurityTest(cveBugId = 267231571)
    public void testOutsideTransaction() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AttributionSourceService.AttributionSourceServiceConnection connection =
                new AttributionSourceService.AttributionSourceServiceConnection(context);
        connection.start();

        try {
            assertTrue("Service successfully unparceled AttributionSource off transaction thread",
                    !connection.postAttributionSource(context.getAttributionSource(), WAIT_TIME,
                            true));
        } finally {
            connection.stop();
        }
    }

    @Test
    @AsbSecurityTest(cveBugId = 267231571)
    public void testInsideTransaction() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AttributionSourceService.AttributionSourceServiceConnection connection =
                new AttributionSourceService.AttributionSourceServiceConnection(context);
        connection.start();

        try {
            assertTrue("Service successfully unparceled AttributionSource off transaction thread",
                    connection.postAttributionSource(context.getAttributionSource(), WAIT_TIME,
                            false));
        } finally {
            connection.stop();
        }
    }
}

