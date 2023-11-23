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

import android.app.cloudsearch.SearchRequest;
import android.app.cloudsearch.SearchResponse;
import android.os.Bundle;

public class CloudSearchTestUtils {
    public static SearchRequest getBasicSearchRequest(String query, String constraint) {
        final int rn = 20;
        final int offset = 0;
        Bundle constraints = new Bundle();
        constraints.putBoolean(SearchRequest.CONSTRAINT_IS_PRESUBMIT_SUGGESTION,
                true);
        constraints.putString(SearchRequest.CONSTRAINT_SEARCH_PROVIDER_FILTER, constraint);

        return new SearchRequest.Builder(query).setResultNumber(rn)
                .setResultOffset(offset).setSearchConstraints(constraints).build();
    }

    public static SearchResponse getSearchResponse(int searchStatusCode) {
        return new SearchResponse.Builder(searchStatusCode).build();
    }
}
