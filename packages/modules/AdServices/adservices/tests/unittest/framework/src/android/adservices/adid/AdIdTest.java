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
package android.adservices.adid;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/** Unit tests for {@link android.adservices.adid.AdId} */
@SmallTest
public final class AdIdTest {
    @Test
    public void testAdId() throws Exception {
        AdId response = new AdId("TEST_ADID", true);

        // Validate the returned response is same to what we created
        assertEquals("TEST_ADID", response.getAdId());
        assertEquals(true, response.isLimitAdTrackingEnabled());

        AdId mirrorResponse = response;
        assertEquals(true, response.equals(mirrorResponse));
        assertEquals(true, response.equals(new AdId("TEST_ADID", true)));
        assertEquals(false, response.equals(new AdId("TEST_ADID", false)));
        assertEquals(false, response.equals("TEST_ADID"));

        assertEquals(true, response.hashCode() == new AdId("TEST_ADID", true).hashCode());
    }
}
