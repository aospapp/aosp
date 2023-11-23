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
package android.adservices.appsetid;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/** Unit tests for {@link android.adservices.appsetid.AppSetId} */
@SmallTest
public final class AppSetIdTest {
    @Test
    public void testAppSetId() throws Exception {
        AppSetId response = new AppSetId("TEST_APPSETID", GetAppSetIdResult.SCOPE_APP);

        // Validate the returned response is same to what we created
        assertEquals("TEST_APPSETID", response.getId());
        assertEquals(GetAppSetIdResult.SCOPE_APP, response.getScope());

        AppSetId mirrorResponse = response;
        assertEquals(true, response.equals(mirrorResponse));
        assertEquals(
                true, response.equals(new AppSetId("TEST_APPSETID", GetAppSetIdResult.SCOPE_APP)));
        assertEquals(
                false,
                response.equals(new AppSetId("TEST_APPSETID", GetAppSetIdResult.SCOPE_DEVELOPER)));
        assertEquals(false, response.equals("TEST_APPSETID"));

        assertEquals(
                true,
                response.hashCode()
                        == new AppSetId("TEST_APPSETID", GetAppSetIdResult.SCOPE_APP).hashCode());
    }
}
