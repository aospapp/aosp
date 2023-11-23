/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.carlauncher.recents.view;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.testing.TestableContext;
import android.view.LayoutInflater;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.carlauncher.R;
import com.android.car.carlauncher.recents.RecentTasksViewModel;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class RecentTasksAdapterTest {
    private static final int COL_PER_PAGE = 2;
    private static final int SPAN_COUNT = 2;

    private RecentTasksAdapter mRecentTasksAdapter;

    @Mock
    private RecentTasksViewModel mRecentTasksViewModel;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private ItemTouchHelper mItemTouchHelper;
    @Mock
    private RecyclerView mRecyclerView;
    @Mock
    private GridLayoutManager mGridLayoutManager;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext()) {
        @Override
        public Context createApplicationContext(ApplicationInfo application, int flags) {
            return this;
        }
    };

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext.getOrCreateTestableResources().addOverride(
                R.integer.config_recents_columns_per_page, /* value= */ COL_PER_PAGE);
        when(mGridLayoutManager.getSpanCount()).thenReturn(SPAN_COUNT);
        when(mRecyclerView.getLayoutManager()).thenReturn(mGridLayoutManager);
        mRecentTasksAdapter = new RecentTasksAdapter(mContext, mLayoutInflater, mItemTouchHelper,
                mRecentTasksViewModel);
        mRecentTasksAdapter = spy(mRecentTasksAdapter);
        mRecentTasksAdapter.onAttachedToRecyclerView(mRecyclerView);
    }

    @Test
    public void tasksFetched_completePage_getItemCount_returnsSameItemCount() {
        // 1st item + 1 page of 4 items
        when(mRecentTasksViewModel.getRecentTasksSize()).thenReturn(5);

        mRecentTasksAdapter.onRecentTasksFetched();

        assertThat(mRecentTasksAdapter.getItemCount()).isEqualTo(5);
    }

    @Test
    public void tasksFetched_incompletePage_getItemCount_returnsCompletePageCount() {
        // 1st item + 1 page of 3 items
        when(mRecentTasksViewModel.getRecentTasksSize()).thenReturn(4);

        mRecentTasksAdapter.onRecentTasksFetched();

        assertThat(mRecentTasksAdapter.getItemCount()).isEqualTo(5);
    }

    @Test
    public void taskRemoved_incompleteToCompletePage_getItemCount_returnsSameItemCount() {
        // 1st item + 1 page of 4 items + 1 page of 1 item - removing 1 item
        when(mRecentTasksViewModel.getRecentTasksSize()).thenReturn(5);
        // last page had 1 item, so 3 hidden items needed to complete the page
        mRecentTasksAdapter.setEmptyViewHolderCount(3);

        mRecentTasksAdapter.onRecentTaskRemoved(2);

        assertThat(mRecentTasksAdapter.getItemCount()).isEqualTo(5);
    }

    @Test
    public void taskRemoved_incompleteToCompletePage_hiddenItemsRemoved() {
        // 1st item + 1 page of 4 items + 1 page of 1 item - removing 1 item
        when(mRecentTasksViewModel.getRecentTasksSize()).thenReturn(5);
        // last page had 1 item, so 3 hidden items needed to complete the page
        mRecentTasksAdapter.setEmptyViewHolderCount(3);

        mRecentTasksAdapter.onRecentTaskRemoved(2);

        verify(mRecentTasksAdapter, times(1)).notifyItemRangeRemoved(5, 3);
    }

    @Test
    public void taskRemoved_completeToIncompletePage_getItemCount_returnsCompletePageCount() {
        // 1st item + 1 page of 4 items - removing 1 item
        when(mRecentTasksViewModel.getRecentTasksSize()).thenReturn(4);
        // last page had 4 items, so no hidden items needed
        mRecentTasksAdapter.setEmptyViewHolderCount(0);

        mRecentTasksAdapter.onRecentTaskRemoved(2);

        assertThat(mRecentTasksAdapter.getItemCount()).isEqualTo(5);
    }

    @Test
    public void taskRemoved_completeToIncompletePage_hiddenItemsAdded() {
        // 1st item + 1 page of 4 items - removing 1 item
        when(mRecentTasksViewModel.getRecentTasksSize()).thenReturn(4);
        // last page had 4 items, so no hidden items needed
        mRecentTasksAdapter.setEmptyViewHolderCount(0);

        mRecentTasksAdapter.onRecentTaskRemoved(2);

        verify(mRecentTasksAdapter, times(1)).notifyItemRangeInserted(4, 1);
    }

    @Test
    public void taskRemoved_incompleteToIncompletePage_getItemCount_returnsCompletePageCount() {
        // 1st item + 1 page of 3 items - removing 1 item
        when(mRecentTasksViewModel.getRecentTasksSize()).thenReturn(3);
        // last page had 3 items, so 1 hidden item needed to complete the page
        mRecentTasksAdapter.setEmptyViewHolderCount(1);

        mRecentTasksAdapter.onRecentTaskRemoved(2);

        assertThat(mRecentTasksAdapter.getItemCount()).isEqualTo(5);
    }

    @Test
    public void taskRemoved_incompleteToIncompletePage_hiddenItemsAdded() {
        // 1st item + 1 page of 3 items - removing 1 item
        when(mRecentTasksViewModel.getRecentTasksSize()).thenReturn(3);
        // last page had 3 items, so 1 hidden item needed to complete the page
        mRecentTasksAdapter.setEmptyViewHolderCount(1);

        mRecentTasksAdapter.onRecentTaskRemoved(2);

        verify(mRecentTasksAdapter, times(1)).notifyItemRangeInserted(4, 1);
    }
}
