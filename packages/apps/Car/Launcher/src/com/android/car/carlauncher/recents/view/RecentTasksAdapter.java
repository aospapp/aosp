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

import android.annotation.IntDef;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.carlauncher.R;
import com.android.car.carlauncher.recents.RecentTasksViewModel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Adapter that is used to display the list of Recent tasks.
 * ViewTypes in this adapter:
 * - FIRST_ITEM_VIEW_TYPE:  First task has special handling since it is takes up more area.
 * - HIDDEN_ITEM_VIEW_TYPE: Hidden ViewHolders are added to/removed from the end to always maintain
 *                          complete pages.
 * - DEFAULT_ITEM_VIEW_TYPE: all other view holders that hold a recent task.
 */
public class RecentTasksAdapter extends RecyclerView.Adapter<BaseViewHolder> implements
        RecentTasksViewModel.RecentTasksChangeListener {
    @IntDef({RecentsItemViewType.DEFAULT_ITEM_VIEW_TYPE, RecentsItemViewType.FIRST_ITEM_VIEW_TYPE,
            RecentsItemViewType.HIDDEN_ITEM_VIEW_TYPE})
    @Retention(RetentionPolicy.SOURCE)
    @interface RecentsItemViewType {
        int DEFAULT_ITEM_VIEW_TYPE = 0;
        int FIRST_ITEM_VIEW_TYPE = 1;
        int HIDDEN_ITEM_VIEW_TYPE = 2;
    }

    private static final byte THUMBNAIL_UPDATED = 0x1; // 00000001
    private static final byte ICON_UPDATED = 0x2; // 00000010
    private final RecentTasksViewModel mRecentTasksViewModel;
    private final LayoutInflater mLayoutInflater;
    private final ItemTouchHelper mItemTouchHelper;
    private final float mStartSwipeThreshold;
    private final int mColumnsPerPage;
    private final Drawable mHiddenTaskIcon;
    private final Bitmap mHiddenThumbnail;
    private int mEmptyViewHolderCount;
    private int mSpanCount;

    public RecentTasksAdapter(Context context, LayoutInflater layoutInflater,
            ItemTouchHelper itemTouchHelper) {
        this(context, layoutInflater, itemTouchHelper, RecentTasksViewModel.getInstance());
    }

    @VisibleForTesting
    public RecentTasksAdapter(Context context, LayoutInflater layoutInflater,
            ItemTouchHelper itemTouchHelper, RecentTasksViewModel recentTasksViewModel) {
        mRecentTasksViewModel = recentTasksViewModel;
        mRecentTasksViewModel.addRecentTasksChangeListener(this);
        mLayoutInflater = layoutInflater;
        mItemTouchHelper = itemTouchHelper;
        mColumnsPerPage = context.getResources().getInteger(
                R.integer.config_recents_columns_per_page);
        mStartSwipeThreshold = context.getResources().getFloat(
                R.dimen.recent_task_start_swipe_threshold);
        mHiddenTaskIcon = context.getResources().getDrawable(
                R.drawable.recent_task_hidden_icon, /* theme= */ null);
        mHiddenThumbnail = mRecentTasksViewModel.createThumbnail(
                Color.argb(/* alpha= */ 0, /* red= */ 0, /* green= */ 0, /* blue= */ 0));
    }

    @NonNull
    @Override
    public BaseViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
            @RecentsItemViewType int viewType) {
        switch (viewType) {
            case RecentsItemViewType.FIRST_ITEM_VIEW_TYPE:
                return new TaskViewHolder(mLayoutInflater.inflate(R.layout.recent_task_view_first,
                        parent, /* attachToRoot= */ false));
            case RecentsItemViewType.HIDDEN_ITEM_VIEW_TYPE:
                return new BaseViewHolder(mLayoutInflater.inflate(R.layout.recent_task_view_hidden,
                        parent, /* attachToRoot= */ false));
            default:
                return new TaskViewHolder(mLayoutInflater.inflate(R.layout.recent_task_view, parent,
                        /* attachToRoot= */ false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder holder, int position) {
        if (holder instanceof TaskViewHolder) {
            TaskViewHolder taskViewHolder = (TaskViewHolder) holder;
            Drawable taskIcon = mRecentTasksViewModel.getRecentTaskIconAt(position);
            Bitmap taskThumbnail = mRecentTasksViewModel.getRecentTaskThumbnailAt(position);
            boolean isDisabled = mRecentTasksViewModel.isRecentTaskDisabled(position);
            View.OnClickListener openTaskClickListener =
                    isDisabled ? mRecentTasksViewModel.getDisabledTaskClickListener(position)
                            : new TaskClickListener(position);
            taskViewHolder.bind(taskIcon, taskThumbnail, isDisabled, openTaskClickListener,
                    /* dismissTaskClickListener= */ new DismissTaskClickListener(position),
                    /* taskTouchListener= */
                    new TaskTouchListener(mStartSwipeThreshold, mItemTouchHelper, holder));
            return;
        }
        holder.bind(mHiddenTaskIcon, mHiddenThumbnail);
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder holder, int position,
            @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
            return;
        }
        payloads.forEach(payload -> {
            if (payload instanceof Byte) {
                byte updateType = (Byte) payload;
                if ((updateType & THUMBNAIL_UPDATED) > 0) {
                    Bitmap taskThumbnail = mRecentTasksViewModel.getRecentTaskThumbnailAt(position);
                    holder.updateThumbnail(taskThumbnail);
                }
                if ((updateType & ICON_UPDATED) > 0) {
                    Drawable taskIcon = mRecentTasksViewModel.getRecentTaskIconAt(position);
                    holder.updateIcon(taskIcon);
                }
            }
        });
    }

    @Override
    public void onViewAttachedToWindow(@NonNull BaseViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (holder instanceof TaskViewHolder) {
            ((TaskViewHolder) holder).attachedToWindow();
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull BaseViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
        if (holder instanceof TaskViewHolder) {
            ((TaskViewHolder) holder).detachedFromWindow();
        }
    }

    @Override
    public int getItemCount() {
        return mRecentTasksViewModel.getRecentTasksSize() + mEmptyViewHolderCount;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) {
            return RecentsItemViewType.FIRST_ITEM_VIEW_TYPE;
        }
        if (position >= mRecentTasksViewModel.getRecentTasksSize()) {
            return RecentsItemViewType.HIDDEN_ITEM_VIEW_TYPE;
        }
        return RecentsItemViewType.DEFAULT_ITEM_VIEW_TYPE;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        if (recyclerView.getLayoutManager() instanceof GridLayoutManager) {
            GridLayoutManager layoutManager = (GridLayoutManager) recyclerView.getLayoutManager();
            mSpanCount = layoutManager.getSpanCount();
        }
    }

    @Override
    public void onRecentTasksFetched() {
        int tasksCount = mRecentTasksViewModel.getRecentTasksSize();
        if (tasksCount <= 0) {
            return;
        }
        mEmptyViewHolderCount = calculateEmptyItemsNeededToCompletePages(
                mRecentTasksViewModel.getRecentTasksSize() - 1,
                mSpanCount, mColumnsPerPage);
        notifyDataSetChanged();
    }

    @Override
    public void onTaskThumbnailChange(int position) {
        this.notifyItemChanged(position, THUMBNAIL_UPDATED);
    }

    @Override
    public void onTaskIconChange(int position) {
        this.notifyItemChanged(position, ICON_UPDATED);
    }

    @Override
    public void onAllRecentTasksRemoved(int countRemoved) {
        countRemoved += mEmptyViewHolderCount;
        mEmptyViewHolderCount = 0;
        this.notifyItemRangeRemoved(0, countRemoved);
    }

    @Override
    public void onRecentTaskRemoved(int position) {
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, mRecentTasksViewModel.getRecentTasksSize() - position);

        int newEmptyViewHolderCount = calculateEmptyItemsNeededToCompletePages(
                mRecentTasksViewModel.getRecentTasksSize() - 1,
                mSpanCount, mColumnsPerPage);
        int emptyViewHolderCountChange = newEmptyViewHolderCount - mEmptyViewHolderCount;
        if (emptyViewHolderCountChange > 0) {
            notifyItemRangeInserted(getItemCount(), emptyViewHolderCountChange);
        } else if (emptyViewHolderCountChange < 0) {
            notifyItemRangeRemoved(mRecentTasksViewModel.getRecentTasksSize(),
                    Math.abs(emptyViewHolderCountChange));
        }
        mEmptyViewHolderCount = newEmptyViewHolderCount;
    }

    private int calculateEmptyItemsNeededToCompletePages(int listLength, int spanSize,
            int colPerPage) {
        if (listLength <= 0) {
            return 0;
        }

        int itemsPerPage = colPerPage * spanSize;
        int lastPageItems = (listLength % itemsPerPage);
        return lastPageItems == 0 ? 0 : itemsPerPage - lastPageItems;
    }

    @VisibleForTesting
    void setEmptyViewHolderCount(int emptyViewHolderCount) {
        mEmptyViewHolderCount = emptyViewHolderCount;
    }
}

