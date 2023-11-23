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

import android.app.cloudsearch.SearchResponse;
import android.app.cloudsearch.SearchResult;
import android.os.Bundle;
import android.os.Parcel;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link SearchResponse}
 *
 * atest CtsCloudSearchServiceTestCases
 */
@RunWith(AndroidJUnit4.class)
public class SearchResponseTest {

    private static final String TAG = "CloudSearchTargetTest";

    @Test
    public void testCreateSearchResponse() {
        final int status = SearchResponse.SEARCH_STATUS_OK;
        final String source = "DEFAULT";
        List<SearchResult> results = new ArrayList<SearchResult>();

        final String titleA = "title a";
        final String snippetA = "Good Snippet a";
        final float scoreA = 10;
        Bundle extraInfosA = new Bundle();
        extraInfosA.putBoolean(SearchResult.EXTRAINFO_APP_CONTAINS_IAP_DISCLAIMER,
                false);
        extraInfosA.putString(SearchResult.EXTRAINFO_APP_DEVELOPER_NAME,
                "best_app_developer a");
        SearchResult resultA = new SearchResult.Builder(titleA, extraInfosA)
                .setSnippet(snippetA).setScore(scoreA).build();
        results.add(resultA);

        final String titleB = "title B";
        final String snippetB = "Good Snippet B";
        final float scoreB = 20;
        Bundle extraInfosB = new Bundle();
        extraInfosB.putBoolean(SearchResult.EXTRAINFO_APP_CONTAINS_IAP_DISCLAIMER,
                true);
        extraInfosB.putString(SearchResult.EXTRAINFO_APP_DEVELOPER_NAME,
                "best_app_developer B");
        SearchResult resultB = new SearchResult.Builder(titleB, extraInfosB)
                .setSnippet(snippetB).setScore(scoreB).build();
        results.add(resultB);

        SearchResponse response = new SearchResponse.Builder(SearchResponse.SEARCH_STATUS_UNKNOWN)
                .setSearchResults(results).setStatusCode(status).build();

        /** Checks the original response. */
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getSource()).isEqualTo(source);
        assertThat(response.getSearchResults().size()).isEqualTo(2);
        final SearchResult firstResult = response.getSearchResults().get(0);
        assertThat(firstResult.getTitle()).isEqualTo(titleA);
        final SearchResult secondResult = response.getSearchResults().get(1);
        assertThat(secondResult.getTitle()).isEqualTo(titleB);

        Parcel parcel = Parcel.obtain();
        parcel.setDataPosition(0);
        response.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        SearchResponse copy = SearchResponse.CREATOR.createFromParcel(parcel);

        /** Checks the copied response. */
        assertThat(copy.getStatusCode()).isEqualTo(status);
        assertThat(copy.getSource()).isEqualTo(source);
        assertThat(copy.getSearchResults().size()).isEqualTo(2);
        final SearchResult firstResultCopy = copy.getSearchResults().get(0);
        assertThat(firstResultCopy.getTitle()).isEqualTo(titleA);
        final SearchResult secondResultCopy = copy.getSearchResults().get(1);
        assertThat(secondResultCopy.getTitle()).isEqualTo(titleB);

        parcel.recycle();
    }
}
