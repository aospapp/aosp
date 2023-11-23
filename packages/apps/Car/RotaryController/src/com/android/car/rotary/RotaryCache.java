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
package com.android.car.rotary;

import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cache of rotation and nudge history of rotary controller. With this cache, the users can reverse
 * course and go back where they were if they accidentally nudge too far.
 */
class RotaryCache {

    /** The cache is disabled. */
    @VisibleForTesting
    static final int CACHE_TYPE_DISABLED = 1;
    /** Entries in the cache will expire after a period of time. */
    @VisibleForTesting
    static final int CACHE_TYPE_EXPIRED_AFTER_SOME_TIME = 2;
    /** Entries in the cache will never expire as long as RotaryService is alive. */
    @VisibleForTesting
    static final int CACHE_TYPE_NEVER_EXPIRE = 3;

    @IntDef(flag = true, value = {
            CACHE_TYPE_DISABLED, CACHE_TYPE_EXPIRED_AFTER_SOME_TIME, CACHE_TYPE_NEVER_EXPIRE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CacheType {
    }

    @NonNull
    private NodeCopier mNodeCopier = new NodeCopier();

    /** Cache of last focused node by focus area. */
    @NonNull
    private final FocusHistoryCache mFocusHistoryCache;

    /** Cache of target focus area by source focus area and direction (up, down, left or right). */
    @NonNull
    private final FocusAreaHistoryCache mFocusAreaHistoryCache;

    /**
     * Cache of recently focused nodes in recently focused windows. Used to recover when the
     * focused window closes.
     */
    @NonNull
    private final FocusWindowCache mFocusWindowCache;

    /** A record of when a node was focused. */
    private static class FocusHistory {

        /**
         * A node representing a focusable {@link View} or a {@link com.android.car.ui.FocusArea}.
         */
        @NonNull
        final AccessibilityNodeInfo node;

        /** The {@link SystemClock#uptimeMillis} when this history was recorded. */
        final long timestamp;

        FocusHistory(@NonNull AccessibilityNodeInfo node, long timestamp) {
            this.node = node;
            this.timestamp = timestamp;
        }
    }

    /**
     * A combination of a source focus area and a direction (up, down, left or right). Used as a key
     * in {@link #mFocusAreaHistoryCache}.
     */
    private static class FocusAreaHistory {

        @NonNull
        final AccessibilityNodeInfo sourceFocusArea;
        final int direction;

        FocusAreaHistory(@NonNull AccessibilityNodeInfo sourceFocusArea, int direction) {
            this.sourceFocusArea = sourceFocusArea;
            this.direction = direction;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            FocusAreaHistory that = (FocusAreaHistory) o;
            return direction == that.direction
                    && Objects.equals(sourceFocusArea, that.sourceFocusArea);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceFocusArea, direction);
        }
    }

    /** An entry in {@link #mFocusWindowCache}. */
    private static class FocusWindowHistory {
        /** A node in a window representing a focused {@link View}. */
        @NonNull
        final AccessibilityNodeInfo mNode;

        /** The {@link SystemClock#uptimeMillis} when this history was recorded. */
        final long mTimestamp;

        FocusWindowHistory(@NonNull AccessibilityNodeInfo node, long timestamp) {
            this.mNode = node;
            this.mTimestamp = timestamp;
        }

        void recycle() {
            this.mNode.recycle();
        }
    }

    /** A cache of the last focused node by focus area. */
    private class FocusHistoryCache extends LruCache<AccessibilityNodeInfo, FocusHistory> {

        /** Type of the cache. */
        private final @CacheType int mCacheType;

        /** How many milliseconds before an entry in the cache expires. */
        private final int mExpirationTimeMs;

        FocusHistoryCache(@CacheType int cacheType, int size, int expirationTimeMs) {
            super(size);
            mCacheType = cacheType;
            mExpirationTimeMs = expirationTimeMs;
            if (mCacheType == CACHE_TYPE_EXPIRED_AFTER_SOME_TIME && mExpirationTimeMs <= 0) {
                throw new IllegalArgumentException(
                        "Expiration time must be positive if CacheType is "
                                + "CACHE_TYPE_EXPIRED_AFTER_SOME_TIME");
            }
        }

        boolean enabled() {
            return mCacheType != CACHE_TYPE_DISABLED;
        }

        boolean isValidFocusHistory(@Nullable FocusHistory focusHistory, long elapsedRealtime) {
            if (focusHistory == null || focusHistory.node == null) {
                return false;
            }
            switch (mCacheType) {
                case CACHE_TYPE_NEVER_EXPIRE:
                    return true;
                case CACHE_TYPE_EXPIRED_AFTER_SOME_TIME:
                    return elapsedRealtime - focusHistory.timestamp < mExpirationTimeMs;
                default:
                    return false;
            }
        }

        @Override
        protected void entryRemoved(boolean evicted, AccessibilityNodeInfo key,
                FocusHistory oldValue, FocusHistory newValue) {
            Utils.recycleNode(key);
            Utils.recycleNode(oldValue.node);
        }
    }

    /**
     * A cache of the target focus area to nudge to, by source focus area and direction (up, down,
     * left or right).
     */
    private class FocusAreaHistoryCache extends LruCache<FocusAreaHistory, FocusHistory> {

        /** Type of the cache. */
        private final @CacheType int mCacheType;

        /** How many milliseconds before an entry in the cache expires. */
        private final int mExpirationTimeMs;

        FocusAreaHistoryCache(@CacheType int cacheType, int size, int expirationTimeMs) {
            super(size);
            mCacheType = cacheType;
            mExpirationTimeMs = expirationTimeMs;
            if (mCacheType == CACHE_TYPE_EXPIRED_AFTER_SOME_TIME && mExpirationTimeMs <= 0) {
                throw new IllegalArgumentException(
                        "Expiration time must be positive if CacheType is "
                                + "CACHE_TYPE_EXPIRED_AFTER_SOME_TIME");
            }
        }

        boolean enabled() {
            return mCacheType != CACHE_TYPE_DISABLED;
        }

        boolean isValidFocusHistory(@Nullable FocusHistory focusHistory, long elapsedRealtime) {
            if (focusHistory == null || focusHistory.node == null) {
                return false;
            }
            switch (mCacheType) {
                case CACHE_TYPE_NEVER_EXPIRE:
                    return true;
                case CACHE_TYPE_EXPIRED_AFTER_SOME_TIME:
                    return elapsedRealtime - focusHistory.timestamp < mExpirationTimeMs;
                default:
                    return false;
            }
        }

        @Override
        protected void entryRemoved(boolean evicted, FocusAreaHistory key, FocusHistory oldValue,
                FocusHistory newValue) {
            Utils.recycleNode(key.sourceFocusArea);
            Utils.recycleNode(oldValue.node);
        }
    }

    /**
     * A cache of recently focused nodes in recently focused windows. Used to recover when the
     * focused window closes.
     */
    private class FocusWindowCache extends LruCache<Integer, FocusWindowHistory> {
        @CacheType
        final int mCacheType;
        final int mExpirationTimeMs;

        FocusWindowCache(@CacheType int cacheType, int size, int expirationTimeMs) {
            super(size);
            mCacheType = cacheType;
            mExpirationTimeMs = expirationTimeMs;
            if (cacheType == CACHE_TYPE_EXPIRED_AFTER_SOME_TIME && expirationTimeMs <= 0) {
                throw new IllegalArgumentException(
                        "Expiration time must be positive if CacheType is "
                                + "CACHE_TYPE_EXPIRED_AFTER_SOME_TIME");
            }
        }

        /**
         * Returns whether an entry in this cache is valid. To be valid:
         * <ul>
         *     <li>the cached node must still be in the view tree
         *     <li>the cached node must still be able to take focus
         *     <li>the cache entry must not have expired
         * </ul>
         */
        boolean isValidEntry(@NonNull FocusWindowHistory focusWindowHistory, long elapsedRealtime) {
            if (!focusWindowHistory.mNode.refresh()
                    || !Utils.canTakeFocus(focusWindowHistory.mNode)) {
                return false;
            }

            switch (mCacheType) {
                case CACHE_TYPE_NEVER_EXPIRE:
                    return true;
                case CACHE_TYPE_EXPIRED_AFTER_SOME_TIME:
                    return elapsedRealtime - focusWindowHistory.mTimestamp < mExpirationTimeMs;
                default:
                    return false;
            }
        }

        /**
         * Stores the given (window ID, node) pair, overwriting the existing pair with the given
         * window ID, if any.
         */
        void put(int windowId, @NonNull AccessibilityNodeInfo node, long elapsedRealtime) {
            if (mCacheType == CACHE_TYPE_DISABLED) {
                return;
            }
            put(windowId, new FocusWindowHistory(copyNode(node), elapsedRealtime));
        }

        /**
         * Returns the most recently focused valid node or {@code null} if there are no valid
         * nodes in the cache. The caller is responsible for recycling the result.
         */
        @Nullable
        AccessibilityNodeInfo getMostRecentValidNode(long elapsedRealtime) {
            Map<Integer, FocusWindowHistory> snapshot = snapshot();
            List<FocusWindowHistory> focusWindowHistories = new ArrayList<>(snapshot.values());
            Collections.reverse(focusWindowHistories);
            for (FocusWindowHistory focusWindowHistory : focusWindowHistories) {
                if (isValidEntry(focusWindowHistory, elapsedRealtime)) {
                    return copyNode(focusWindowHistory.mNode);
                }
            }
            return null;
        }

        @Override
        protected void entryRemoved(boolean evicted, Integer windowId, FocusWindowHistory oldValue,
                FocusWindowHistory newValue) {
            oldValue.recycle();
        }
    }

    RotaryCache(@CacheType int focusHistoryCacheType,
            int focusHistoryCacheSize,
            int focusHistoryExpirationTimeMs,
            @CacheType int focusAreaHistoryCacheType,
            int focusAreaHistoryCacheSize,
            int focusAreaHistoryExpirationTimeMs,
            @CacheType int focusWindowCacheType,
            int focusWindowCacheSize,
            int focusWindowExpirationTimeMs) {
        mFocusHistoryCache = new FocusHistoryCache(
                focusHistoryCacheType, focusHistoryCacheSize, focusHistoryExpirationTimeMs);
        mFocusAreaHistoryCache = new FocusAreaHistoryCache(focusAreaHistoryCacheType,
                focusAreaHistoryCacheSize, focusAreaHistoryExpirationTimeMs);
        mFocusWindowCache = new FocusWindowCache(focusWindowCacheType, focusWindowCacheSize,
                focusWindowExpirationTimeMs);
    }

    /**
     * Searches the cache to find the last focused node in the given {@code focusArea}. Returns the
     * node, or null if there is nothing in the cache, the cache is stale, the view represented
     * by the node is no longer in the view tree, or the node's state has changed so that it can't
     * take focus any more. The caller is responsible for recycling the result.
     */
    AccessibilityNodeInfo getFocusedNode(@NonNull AccessibilityNodeInfo focusArea,
            long elapsedRealtime) {
        if (mFocusHistoryCache.enabled()) {
            FocusHistory focusHistory = mFocusHistoryCache.get(focusArea);
            if (mFocusHistoryCache.isValidFocusHistory(focusHistory, elapsedRealtime)) {
                AccessibilityNodeInfo node = copyNode(focusHistory.node);
                // Refresh the node in case the view represented by the node is no longer in the
                // view tree, or the node's state (e.g., isFocused()) has changed.
                AccessibilityNodeInfo refreshedNode = Utils.refreshNode(node);

                // If the node's state has changed so that it can't take focus any more, return
                // null.
                if (refreshedNode != null && !Utils.canTakeFocus(refreshedNode)) {
                    Utils.recycleNode(refreshedNode);
                    refreshedNode = null;
                }
                return refreshedNode;
            }
        }
        return null;
    }

    /**
     * Caches the last focused node by focus area. A copy of {@code focusArea} and {@code
     * focusedNode} will be saved in the cache.
     */
    void saveFocusedNode(@NonNull AccessibilityNodeInfo focusArea,
            @NonNull AccessibilityNodeInfo focusedNode, long elapsedRealtime) {
        if (mFocusHistoryCache.enabled()) {
            mFocusHistoryCache.put(
                    copyNode(focusArea), new FocusHistory(copyNode(focusedNode), elapsedRealtime));
        }
    }

    /**
     * Searches the cache to find the target focus area for a nudge in a given {@code direction}
     * from a given focus area. Returns the focus area, or null if there is nothing in the cache,
     * the cache is stale, or the view represented by the node is no longer in the view tree.
     * The caller is responsible for recycling the result.
     */
    AccessibilityNodeInfo getTargetFocusArea(@NonNull AccessibilityNodeInfo sourceFocusArea,
            int direction, long elapsedRealtime) {
        if (mFocusAreaHistoryCache.enabled()) {
            FocusHistory focusHistory =
                    mFocusAreaHistoryCache.get(new FocusAreaHistory(sourceFocusArea, direction));
            if (mFocusAreaHistoryCache.isValidFocusHistory(focusHistory, elapsedRealtime)) {
                AccessibilityNodeInfo focusArea = copyNode(focusHistory.node);
                // Refresh the node in case the view represented by the node is no longer in the
                // view tree.
                return Utils.refreshNode(focusArea);
            }
        }
        return null;
    }

    /**
     * Caches the focus area nudge history. A copy of {@code sourceFocusArea} and {@code
     * targetFocusArea} will be saved in the cache.
     */
    void saveTargetFocusArea(@NonNull AccessibilityNodeInfo sourceFocusArea,
            @NonNull AccessibilityNodeInfo targetFocusArea, int direction, long elapsedRealtime) {
        if (mFocusAreaHistoryCache.enabled()) {
            int oppositeDirection = getOppositeDirection(direction);
            mFocusAreaHistoryCache
                    .put(new FocusAreaHistory(copyNode(targetFocusArea), oppositeDirection),
                            new FocusHistory(copyNode(sourceFocusArea), elapsedRealtime));
        }
    }

    /** Clears the focus area nudge history cache. */
    void clearFocusAreaHistory() {
        if (mFocusAreaHistoryCache.enabled()) {
            mFocusAreaHistoryCache.evictAll();
        }
    }

    @VisibleForTesting
    boolean isFocusAreaHistoryCacheEmpty() {
        return mFocusAreaHistoryCache.size() == 0;
    }

    /** Saves the most recently focused node within a window. */
    void saveWindowFocus(@NonNull AccessibilityNodeInfo focusedNode, long elapsedRealtime) {
        mFocusWindowCache.put(focusedNode.getWindowId(), focusedNode, elapsedRealtime);
    }

    /**
     * Returns the most recently focused valid node or {@code null} if there are no valid nodes
     * saved by {@link #saveWindowFocus}. The caller is responsible for recycling the result.
     */
    @Nullable
    AccessibilityNodeInfo getMostRecentFocus(long elapsedRealtime) {
        return mFocusWindowCache.getMostRecentValidNode(elapsedRealtime);
    }

    /** Returns the direction opposite the given {@code direction} */
    @VisibleForTesting
    static int getOppositeDirection(int direction) {
        switch (direction) {
            case View.FOCUS_LEFT:
                return View.FOCUS_RIGHT;
            case View.FOCUS_RIGHT:
                return View.FOCUS_LEFT;
            case View.FOCUS_UP:
                return View.FOCUS_DOWN;
            case View.FOCUS_DOWN:
                return View.FOCUS_UP;
        }
        throw new IllegalArgumentException("direction must be "
                + "FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, or FOCUS_RIGHT.");
    }

    /** Sets a mock {@link NodeCopier} instance for testing. */
    @VisibleForTesting
    void setNodeCopier(@NonNull NodeCopier nodeCopier) {
        mNodeCopier = nodeCopier;
    }

    private AccessibilityNodeInfo copyNode(@Nullable AccessibilityNodeInfo node) {
        return mNodeCopier.copy(node);
    }
}
