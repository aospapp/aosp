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

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.SnapHelper;

/**
 * Snaps the first item or to the center of a page of items to the center of the RecyclerView.
 */
public class TaskSnapHelper extends SnapHelper {
    private final int mSpanCount;
    private final int mColPerPage;
    private final int mItemsInPageCount;
    private RecyclerView mRecyclerView;

    public TaskSnapHelper(int spanCount, int colPerPage) {
        mSpanCount = spanCount;
        mColPerPage = colPerPage;
        mItemsInPageCount = mColPerPage * mSpanCount;
    }

    @Nullable
    @Override
    public int[] calculateDistanceToFinalSnap(@NonNull LayoutManager layoutManager,
            @NonNull View targetView) {
        if (mRecyclerView == null) {
            return new int[]{0, 0};
        }
        int adapterPosition = mRecyclerView.getChildAdapterPosition(targetView);
        int childCenter = Integer.MAX_VALUE;
        if (adapterPosition == 0) {
            childCenter = findCenterOfView(targetView);
        } else {
            int targetPosition = getChildPosition(adapterPosition, layoutManager, mRecyclerView);
            if (targetPosition == RecyclerView.NO_POSITION) {
                return new int[]{0, 0};
            }
            childCenter = findCenterOfPage(targetPosition, mItemsInPageCount, layoutManager);
        }
        if (childCenter == Integer.MAX_VALUE) {
            return new int[]{0, 0};
        }
        int center = layoutManager.getWidth() / 2;
        return new int[]{childCenter - center, 0};
    }

    /**
     * @return View at {@code 0} adapter position if that view is to be snapped else returns 1st
     * view in the page that is to be snapped
     */
    @Nullable
    @Override
    public View findSnapView(LayoutManager layoutManager) {
        int childCount = layoutManager.getChildCount();
        if (childCount == 0 || mRecyclerView == null) {
            return null;
        }
        int center = layoutManager.getWidth() / 2;
        View closestView = null;
        int closestDistance = Integer.MAX_VALUE;
        int i = 0;
        while (i < childCount) {
            View child = layoutManager.getChildAt(i);
            if (child == null) {
                i++;
                continue;
            }
            int adapterPosition = mRecyclerView.getChildAdapterPosition(child);
            if (adapterPosition == RecyclerView.NO_POSITION) {
                i++;
                continue;
            }
            int childCenter;
            if (adapterPosition == 0) {
                childCenter = findCenterOfView(child);
                i++;
            } else {
                int colNum = (int) Math.ceil(adapterPosition / (float) mSpanCount);
                // following calculations have -1 to account for the first element not following
                // regular grid span
                boolean isStartCol = ((colNum - 1) % mColPerPage) == 0;
                boolean isFirstInStartCol = ((adapterPosition - 1) % mSpanCount) == 0;
                if (isStartCol && isFirstInStartCol) {
                    childCenter = findCenterOfPage(i, mItemsInPageCount, layoutManager);
                    i += mItemsInPageCount;
                } else {
                    i++;
                    continue;
                }
            }
            if (childCenter == Integer.MAX_VALUE) {
                continue;
            }
            int distanceToCenter = Math.abs(center - childCenter);
            if (distanceToCenter < closestDistance) {
                closestView = child;
                closestDistance = distanceToCenter;
            }
        }
        return closestView;
    }

    @Override
    public int findTargetSnapPosition(LayoutManager layoutManager, int velocityX, int velocityY) {
        return RecyclerView.NO_POSITION;
    }

    @Override
    public void attachToRecyclerView(@Nullable RecyclerView recyclerView)
            throws IllegalStateException {
        super.attachToRecyclerView(recyclerView);
        if (mRecyclerView == recyclerView) {
            return;
        }
        if (mRecyclerView != null) {
            mRecyclerView.setOnFlingListener(null);
        }
        mRecyclerView = recyclerView;
        if (mRecyclerView == null) {
            return;
        }
        mRecyclerView.setOnFlingListener(new RecyclerView.OnFlingListener() {
            @Override
            public boolean onFling(int velocityX, int velocityY) {
                if (mRecyclerView == null) {
                    return false;
                }
                RecyclerView.LayoutManager layoutManager = mRecyclerView.getLayoutManager();
                if (layoutManager == null) {
                    return false;
                }
                calculateDistanceToFinalSnapAndScroll(layoutManager, mRecyclerView,
                        findSnapView(layoutManager));
                return true;
            }
        });
    }

    private void calculateDistanceToFinalSnapAndScroll(@NonNull LayoutManager layoutManager,
            @NonNull RecyclerView recyclerView, @Nullable View targetView) {
        if (targetView == null) {
            return;
        }
        int[] snapDistance = calculateDistanceToFinalSnap(layoutManager, targetView);
        if (snapDistance == null || (snapDistance[0] == 0 && snapDistance[1] == 0)) {
            return;
        }
        recyclerView.smoothScrollBy(snapDistance[0], snapDistance[1]);
    }

    private int getChildPosition(int adapterPosition, @NonNull LayoutManager layoutManager,
            @NonNull RecyclerView recyclerView) {
        if (adapterPosition == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION;
        for (int i = 0; i < layoutManager.getChildCount(); i++) {
            View child = layoutManager.getChildAt(i);
            if (child != null && adapterPosition == recyclerView.getChildAdapterPosition(child)) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    private int findCenterOfView(@NonNull View child) {
        int left = child.getLeft();
        int right = child.getRight();
        return (left + right) / 2;
    }

    private int findCenterOfPage(int startViewInPageIndex, int numOfViewsInPage,
            LayoutManager layoutManager) {
        int averageCenter = 0;
        int numOfViewsPresent = 0;
        for (int i = startViewInPageIndex; i < startViewInPageIndex + numOfViewsInPage; i++) {
            View child = layoutManager.getChildAt(i);
            if (child == null) {
                continue;
            }
            averageCenter += findCenterOfView(child);
            numOfViewsPresent++;
        }
        if (numOfViewsPresent == 0) {
            return Integer.MAX_VALUE;
        }
        averageCenter = averageCenter / numOfViewsPresent;
        return averageCenter;
    }
}
