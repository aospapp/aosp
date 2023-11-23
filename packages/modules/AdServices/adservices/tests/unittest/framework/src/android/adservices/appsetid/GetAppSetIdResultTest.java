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

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** Unit tests for {@link GetAppSetIdResult} */
@SmallTest
public final class GetAppSetIdResultTest {
    @Test
    public void testWriteToParcel() throws Exception {
        GetAppSetIdResult response =
                new GetAppSetIdResult.Builder()
                        .setAppSetId("UNITTEST_ID")
                        .setAppSetIdScope(GetAppSetIdResult.SCOPE_APP)
                        .build();
        Parcel p = Parcel.obtain();
        response.writeToParcel(p, 0);
        p.setDataPosition(0);

        GetAppSetIdResult fromParcel = GetAppSetIdResult.CREATOR.createFromParcel(p);
        assertEquals(GetAppSetIdResult.CREATOR.newArray(1).length, 1);

        assertEquals(fromParcel.getAppSetId(), "UNITTEST_ID");
        assertEquals(fromParcel.getAppSetIdScope(), GetAppSetIdResult.SCOPE_APP);

        assertEquals(fromParcel.equals(response), true);

        GetAppSetIdResult mirrorFromParcel = fromParcel;
        assertEquals(fromParcel.equals(mirrorFromParcel), true);
        assertEquals(fromParcel.equals("UNITTEST_ID"), false);

        assertEquals(fromParcel.hashCode(), response.hashCode());

        assertEquals(response.getErrorMessage(), null);

        assertEquals(response.describeContents(), 0);

        assertThat(response.toString()).isNotNull();
    }

    @Test
    public void testWriteToParcel_nullableThrows() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    GetAppSetIdResult unusedResponse =
                            new GetAppSetIdResult.Builder().setAppSetId(null).build();
                });
    }
}
