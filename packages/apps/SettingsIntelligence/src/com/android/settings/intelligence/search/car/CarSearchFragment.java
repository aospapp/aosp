/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.settings.intelligence.search.car;

import static com.android.car.ui.core.CarUi.requireInsets;
import static com.android.car.ui.core.CarUi.requireToolbar;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.preference.PreferenceFragment;
import com.android.car.ui.toolbar.MenuItem;
import com.android.car.ui.toolbar.Toolbar;
import com.android.car.ui.toolbar.ToolbarController;
import com.android.settings.intelligence.R;
import com.android.settings.intelligence.overlay.FeatureFactory;
import com.android.settings.intelligence.search.SearchCommon;
import com.android.settings.intelligence.search.SearchFeatureProvider;
import com.android.settings.intelligence.search.SearchResult;
import com.android.settings.intelligence.search.indexing.IndexingCallback;
import com.android.settings.intelligence.search.savedqueries.car.CarSavedQueryController;
import com.android.settings.intelligence.search.savedqueries.car.CarSavedQueryViewHolder;

import java.util.List;

/**
 * Search fragment for car settings.
 */
public class CarSearchFragment extends PreferenceFragment implements
        LoaderManager.LoaderCallbacks<List<? extends SearchResult>>, IndexingCallback {

    private SearchFeatureProvider mSearchFeatureProvider;

    private ToolbarController mToolbar;
    private RecyclerView mRecyclerView;

    private String mQuery;
    private boolean mShowingSavedQuery;

    private CarSearchResultsAdapter mSearchAdapter;
    private CarSavedQueryController mSavedQueryController;

    private final RecyclerView.OnScrollListener mScrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (dy != 0) {
                        hideKeyboard();
                    }
                }
            };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.car_search_fragment, rootKey);
    }

    protected ToolbarController getToolbar() {
        return requireToolbar(requireActivity());
    }

    protected List<MenuItem> getToolbarMenuItems() {
        return null;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mSearchFeatureProvider = FeatureFactory.get(context).searchFeatureProvider();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mQuery = savedInstanceState.getString(SearchCommon.STATE_QUERY);
            mShowingSavedQuery = savedInstanceState.getBoolean(
                    SearchCommon.STATE_SHOWING_SAVED_QUERY);
        } else {
            mShowingSavedQuery = true;
        }

        LoaderManager loaderManager = getLoaderManager();
        mSearchAdapter = new CarSearchResultsAdapter(/* fragment= */ this);
        mSavedQueryController = new CarSavedQueryController(
                getContext(), loaderManager, mSearchAdapter);
        mSearchFeatureProvider.updateIndexAsync(getContext(), /* indexingCallback= */ this);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mToolbar = getToolbar();
        if (mToolbar != null) {
            List<MenuItem> items = getToolbarMenuItems();
            mToolbar.setTitle(getPreferenceScreen().getTitle());
            mToolbar.setMenuItems(items);
            mToolbar.setNavButtonMode(Toolbar.NavButtonMode.BACK);
            mToolbar.setState(Toolbar.State.SUBPAGE);
            mToolbar.setState(Toolbar.State.SEARCH);
            mToolbar.setSearchHint(R.string.abc_search_hint);
            mToolbar.registerOnSearchListener(this::onQueryTextChange);
            mToolbar.registerOnSearchCompletedListener(this::onSearchComplete);
            mToolbar.setShowMenuItemsWhileSearching(true);
            mToolbar.setSearchQuery(mQuery);
        }
        mRecyclerView = getListView();
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(mSearchAdapter);
            mRecyclerView.addOnScrollListener(mScrollListener);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        onCarUiInsetsChanged(requireInsets(requireActivity()));
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(SearchCommon.STATE_QUERY, mQuery);
        outState.putBoolean(SearchCommon.STATE_SHOWING_SAVED_QUERY, mShowingSavedQuery);
    }

    private void onQueryTextChange(String query) {
        if (TextUtils.equals(query, mQuery)) {
            return;
        }
        boolean isEmptyQuery = TextUtils.isEmpty(query);

        mQuery = query;

        // If indexing is not finished, register the query text, but don't search.
        if (!mSearchFeatureProvider.isIndexingComplete(getActivity())) {
            mToolbar.getProgressBar().setVisible(!isEmptyQuery);
            return;
        }

        if (isEmptyQuery) {
            LoaderManager loaderManager = getLoaderManager();
            loaderManager.destroyLoader(SearchCommon.SearchLoaderId.SEARCH_RESULT);
            mShowingSavedQuery = true;
            mSavedQueryController.loadSavedQueries();
        } else {
            restartLoaders();
        }
    }

    private void onSearchComplete() {
        if (!TextUtils.isEmpty(mQuery)) {
            mSavedQueryController.saveQuery(mQuery);
        }
    }

    /**
     * Gets called when a saved query is clicked.
     */
    public void onSavedQueryClicked(CarSavedQueryViewHolder vh, CharSequence query) {
        String queryString = query.toString();
        mToolbar.setSearchQuery(queryString);
        onQueryTextChange(queryString);
    }

    /**
     * Gets called when a search result is clicked.
     */
    public void onSearchResultClicked(CarSearchViewHolder resultViewHolder, SearchResult result) {
        mSearchFeatureProvider.searchResultClicked(getContext(), mQuery, result);
        mSavedQueryController.saveQuery(mQuery);
    }

    @Override
    public Loader<List<? extends SearchResult>> onCreateLoader(int id, Bundle args) {
        Activity activity = getActivity();

        if (id == SearchCommon.SearchLoaderId.SEARCH_RESULT) {
            return mSearchFeatureProvider.getSearchResultLoader(activity, mQuery);
        }
        return null;
    }

    @Override
    public void onLoadFinished(Loader<List<? extends SearchResult>> loader,
            List<? extends SearchResult> data) {
        mSearchAdapter.postSearchResults(data);
        mRecyclerView.scrollToPosition(0);
    }

    @Override
    public void onLoaderReset(Loader<List<? extends SearchResult>> loader) {
    }

    /**
     * Gets called when Indexing is completed.
     */
    @Override
    public void onIndexingFinished() {
        if (getActivity() == null) {
            return;
        }
        mToolbar.getProgressBar().setVisible(false);
        if (mShowingSavedQuery) {
            mSavedQueryController.loadSavedQueries();
        } else {
            LoaderManager loaderManager = getLoaderManager();
            loaderManager.initLoader(SearchCommon.SearchLoaderId.SEARCH_RESULT,
                    /* args= */ null, /* callback= */ this);
        }
        requery();
    }

    private void requery() {
        if (TextUtils.isEmpty(mQuery)) {
            return;
        }
        String query = mQuery;
        mQuery = "";
        onQueryTextChange(query);
    }

    private void restartLoaders() {
        mShowingSavedQuery = false;
        LoaderManager loaderManager = getLoaderManager();
        loaderManager.restartLoader(SearchCommon.SearchLoaderId.SEARCH_RESULT,
                /* args= */ null, /* callback= */ this);
    }

    private void hideKeyboard() {
        Activity activity = getActivity();
        if (activity != null) {
            View view = activity.getCurrentFocus();
            InputMethodManager imm = (InputMethodManager)
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm.isActive(view)) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), /* flags= */ 0);
            }
        }

        if (mRecyclerView != null) {
            mRecyclerView.requestFocus();
        }
    }
}
