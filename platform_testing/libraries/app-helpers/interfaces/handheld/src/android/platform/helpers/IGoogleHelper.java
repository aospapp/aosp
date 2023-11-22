/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.platform.helpers;

import android.support.test.uiautomator.Direction;

public interface IGoogleHelper extends IAppHelper {

    /**
     * Setup expectations: Google app open
     *
     * This method will start a voice search
     *
     * @return true if the search is started, false otherwise
     */
    public boolean doVoiceSearch();

    /**
     * Setup expectations: Google app open to a search result
     *
     * This method will return the query from the search
     */
    public String getSearchQuery();

    /**
     * Setup expectations: In home and with search bar.
     *
     * This method starts search from the search bar in home.
     *
     */
    default public void startSearch() {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * Setup expectations: Google app open.
     *
     * This method inputs keyword in search box.
     *
     * @param query The keyword to search.
     *
     */
    default public void inputSearch(String query) {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * Setup expectations: Google app open and a search keyword input.
     *
     * This method clicks the search button and waits for the results.
     *
     */
    default public void clickSearchButtonAndWaitForResults() {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * Setup expectations: Google app open to a search result.
     *
     * This method flings the search results.
     *
     * @param dir The direction of the fling, must be UP or DOWN.
     *
     */
    default public void flingSearchResults(Direction dir) {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * Setup expectations: In home.
     *
     * This method flings right to Google Feed.
     *
     */
    default public void openFeed() {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * Setup expectations: Feed open.
     *
     * This method flings the feed.
     *
     * @param dir The direction of the fling, must be UP or DOWN.
     */
    default public void flingFeed(Direction dir) {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * Setup expectations: In home.
     *
     * <p>This method clear search result.
     */
    public default boolean clearSearchResult() {
        throw new UnsupportedOperationException("Not yet implemented.");
    }
}
