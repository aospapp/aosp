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
package android.cloudsearch.cts;

import static com.google.common.truth.Truth.assertThat;

import android.app.cloudsearch.SearchRequest;
import android.os.Bundle;
import android.os.Parcel;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link SearchRequest}
 *
 * atest CtsCloudSearchServiceTestCases
 */
@RunWith(AndroidJUnit4.class)
public class SearchRequestTest {

    private static final String TAG = "CloudSearchTargetTest";

    @Test
    public void testCreateSearchRequest() {
        final String query = "bo";
        final int rn = 20;
        final int offset = 0;
        final float maxLatency = 100;
        Bundle constraints = new Bundle();
        constraints.putBoolean(SearchRequest.CONSTRAINT_IS_PRESUBMIT_SUGGESTION,
                true);
        final String pkgName = "android.cloudsearch.cts";

        SearchRequest request = new SearchRequest.Builder("").setResultNumber(rn)
                .setResultOffset(offset).setSearchConstraints(constraints).setQuery(query)
                .setMaxLatencyMillis(maxLatency).setCallerPackageName(pkgName).build();

        /** Check the original request. */
        assertThat(request.getQuery()).isEqualTo(query);
        assertThat(request.getResultNumber()).isEqualTo(rn);
        assertThat(request.getMaxLatencyMillis()).isEqualTo(maxLatency);
        assertThat(request.getResultOffset()).isEqualTo(offset);
        final Bundle sc = request.getSearchConstraints();
        assertThat(sc.getBoolean(SearchRequest.CONSTRAINT_IS_PRESUBMIT_SUGGESTION))
                .isEqualTo(true);
        assertThat(request.getCallerPackageName()).isEqualTo(pkgName);

        Parcel parcel = Parcel.obtain();
        parcel.setDataPosition(0);
        request.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        SearchRequest copy = SearchRequest.CREATOR.createFromParcel(parcel);
        /** Check the copied request. */
        assertThat(copy.getQuery()).isEqualTo(query);
        assertThat(copy.getResultNumber()).isEqualTo(rn);
        assertThat(copy.getMaxLatencyMillis()).isEqualTo(maxLatency);
        assertThat(copy.getResultOffset()).isEqualTo(offset);
        final Bundle sccopy = request.getSearchConstraints();
        assertThat(sccopy.getBoolean(SearchRequest.CONSTRAINT_IS_PRESUBMIT_SUGGESTION))
                .isEqualTo(true);
        assertThat(copy.getCallerPackageName()).isEqualTo(pkgName);

        parcel.recycle();
    }
}
