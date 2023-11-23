/*
 * Copyright 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class RotaryCacheTest {
    private static final int FOCUS_CACHE_SIZE = 10;
    private static final int FOCUS_AREA_CACHE_SIZE = 5;
    private static final int FOCUS_WINDOW_CACHE_SIZE = 5;
    private static final int CACHE_TIME_OUT_MS = 10000;

    private RotaryCache mRotaryCache;

    private AccessibilityNodeInfo mFocusArea;
    private AccessibilityNodeInfo mTargetFocusArea;
    private AccessibilityNodeInfo mFocusedNode;

    private long mValidTime;
    private long mExpiredTime;

    @Mock
    private NodeCopier mNodeCopier;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRotaryCache = new RotaryCache(
                /* focusHistoryCacheType= */ RotaryCache.CACHE_TYPE_EXPIRED_AFTER_SOME_TIME,
                /* focusHistoryCacheSize= */ FOCUS_CACHE_SIZE,
                /* focusHistoryExpirationTimeMs= */ CACHE_TIME_OUT_MS,
                /* focusAreaHistoryCacheType= */ RotaryCache.CACHE_TYPE_EXPIRED_AFTER_SOME_TIME,
                /* focusAreaHistoryCacheSize= */ FOCUS_AREA_CACHE_SIZE,
                /* focusAreaHistoryExpirationTimeMs= */ CACHE_TIME_OUT_MS,
                /* focusWindowCacheType= */ RotaryCache.CACHE_TYPE_EXPIRED_AFTER_SOME_TIME,
                /* focusWindowCacheSize= */ FOCUS_WINDOW_CACHE_SIZE,
                /* focusWindowExpirationTimeMs= */ CACHE_TIME_OUT_MS);

        doAnswer(returnsFirstArg()).when(mNodeCopier).copy(any(AccessibilityNodeInfo.class));
        mRotaryCache.setNodeCopier(mNodeCopier);

        mFocusArea = createNode();
        mTargetFocusArea = createNode();
        mFocusedNode = createFocusNode();

        mValidTime = CACHE_TIME_OUT_MS - 1;
        mExpiredTime = CACHE_TIME_OUT_MS + 1;
    }

    @Test
    public void testClearFocusAreaHistoryCache() {
        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, View.FOCUS_UP, 0);
        assertThat(mRotaryCache.isFocusAreaHistoryCacheEmpty()).isFalse();

        mRotaryCache.clearFocusAreaHistory();
        assertThat(mRotaryCache.isFocusAreaHistoryCacheEmpty()).isTrue();
    }

    @Test
    public void testGetFocusedNodeInTheCache() {
        // Save a focused node.
        mRotaryCache.saveFocusedNode(mFocusArea, mFocusedNode, 0);

        // In the cache.
        AccessibilityNodeInfo node = mRotaryCache.getFocusedNode(mFocusArea, mValidTime);
        assertThat(node).isEqualTo(mFocusedNode);
    }

    @Test
    public void testGetFocusedNodeNotInTheCache() {
        // Not in the cache.
        AccessibilityNodeInfo node = mRotaryCache.getFocusedNode(mTargetFocusArea, mValidTime);
        assertThat(node).isNull();
    }

    @Test
    public void testGetFocusedNodeExpiredCache() {
        // Save a focused node.
        mRotaryCache.saveFocusedNode(mFocusArea, mFocusedNode, 0);

        // Expired cache.
        AccessibilityNodeInfo node = mRotaryCache.getFocusedNode(mFocusArea, mExpiredTime);
        assertThat(node).isNull();
    }

    @Test
    public void testGetFocusedNodeNotInViewTree() {
        // Save a node that is no longer in the view tree.
        AccessibilityNodeInfo node =
                new NodeBuilder().setInViewTree(false).setFocusable(true).build();
        mRotaryCache.saveFocusedNode(mFocusArea, node, 0);

        AccessibilityNodeInfo result = mRotaryCache.getFocusedNode(mFocusArea, mValidTime);
        assertThat(result).isNull();
    }

    @Test
    public void testGetFocusedNodeCannotTakeFocus() {
        // Save a node that is still in the view tree but can't take focus.
        AccessibilityNodeInfo node =
                new NodeBuilder().setInViewTree(true).setFocusable(false).build();
        mRotaryCache.saveFocusedNode(mFocusArea, node, 0);

        AccessibilityNodeInfo result = mRotaryCache.getFocusedNode(mFocusArea, mValidTime);
        assertThat(result).isNull();
    }

    /** Saves so many nodes that the cache overflows and the previously saved node is kicked off. */
    @Test
    public void testFocusCacheOverflow() {
        // Save a focused node (mFocusedNode).
        mRotaryCache.saveFocusedNode(mFocusArea, mFocusedNode, 0);

        // Save FOCUS_CACHE_SIZE nodes to make the cache overflow.
        for (int i = 0; i < FOCUS_CACHE_SIZE; i++) {
            saveFocusHistory();
        }

        // mFocusedNode should have been kicked off.
        AccessibilityNodeInfo savedNode = mRotaryCache.getFocusedNode(mFocusArea, mValidTime);
        assertThat(savedNode).isNull();
    }

    @Test
    public void testFocusCacheNotOverflow() {
        // Save a focused node (mFocusedNode).
        mRotaryCache.saveFocusedNode(mFocusArea, mFocusedNode, 0);

        // Save (FOCUS_CACHE_SIZE - 1) nodes so that the cache is just full.
        for (int i = 0; i < FOCUS_CACHE_SIZE - 1; i++) {
            saveFocusHistory();
        }

        // mFocusedNode should still be in the cache.
        AccessibilityNodeInfo savedNode = mRotaryCache.getFocusedNode(mFocusArea, mValidTime);
        assertThat(savedNode).isEqualTo(mFocusedNode);
    }

    @Test
    public void testGetTargetFocusAreaInTheCache() {
        int direction = View.FOCUS_LEFT;
        int oppositeDirection = RotaryCache.getOppositeDirection(direction);

        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, direction, 0);

        // In the cache.
        AccessibilityNodeInfo node =
                mRotaryCache.getTargetFocusArea(mTargetFocusArea, oppositeDirection, mValidTime);
        assertThat(node).isEqualTo(mFocusArea);
    }

    @Test
    public void testGetTargetFocusAreaNotInTheCache() {
        int direction = View.FOCUS_LEFT;

        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, direction, 0);

        // Not in the cache because the direction doesn't match.
        AccessibilityNodeInfo node = mRotaryCache.getTargetFocusArea(mTargetFocusArea, direction,
                mValidTime);
        assertThat(node).isNull();
    }

    @Test
    public void testGetTargetFocusAreaNotInViewTree() {
        int direction = View.FOCUS_LEFT;
        int oppositeDirection = RotaryCache.getOppositeDirection(direction);

        // Save a focus area that is no longer in the view tree.
        AccessibilityNodeInfo focusArea = new NodeBuilder().setInViewTree(false).build();
        mRotaryCache.saveTargetFocusArea(focusArea, mTargetFocusArea, direction, 0);

        AccessibilityNodeInfo result =
                mRotaryCache.getTargetFocusArea(mTargetFocusArea, oppositeDirection, mValidTime);
        assertThat(result).isNull();
    }

    @Test
    public void testGetTargetFocusAreaExpiredCache() {
        int direction = View.FOCUS_LEFT;
        int oppositeDirection = RotaryCache.getOppositeDirection(direction);

        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, direction, 0);

        // Expired cache.
        AccessibilityNodeInfo node = mRotaryCache.getTargetFocusArea(mTargetFocusArea,
                oppositeDirection, mExpiredTime);
        assertThat(node).isNull();
    }

    /**
     * Saves so many focus areas that the cache overflows and the previously saved focus area is
     * kicked off.
     */
    @Test
    public void testFocusAreaCacheOverflow() {
        int direction = View.FOCUS_RIGHT;
        int oppositeDirection = RotaryCache.getOppositeDirection(direction);

        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, direction, 0);

        // Save FOCUS_AREA_CACHE_SIZE focus areas to make the cache overflow.
        for (int i = 0; i < FOCUS_AREA_CACHE_SIZE; i++) {
            saveFocusAreaHistory();
        }

        // Previously saved focus area should have been kicked off.
        AccessibilityNodeInfo savedFocusArea =
                mRotaryCache.getTargetFocusArea(mTargetFocusArea, oppositeDirection, mValidTime);
        assertThat(savedFocusArea).isNull();
    }

    @Test
    public void testFocusAreaCacheNotOverflow() {
        int direction = View.FOCUS_RIGHT;
        int oppositeDirection = RotaryCache.getOppositeDirection(direction);

        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, direction, 0);

        // Save (FOCUS_AREA_CACHE_SIZE - 1) focus areas so that the cache is just full.
        for (int i = 0; i < FOCUS_AREA_CACHE_SIZE - 1; i++) {
            saveFocusAreaHistory();
        }

        // Previously saved focus area should still be in the cache.
        AccessibilityNodeInfo savedFocusArea =
                mRotaryCache.getTargetFocusArea(mTargetFocusArea, oppositeDirection, mValidTime);
        assertThat(savedFocusArea).isEqualTo(mFocusArea);
    }

    @Test
    public void testGetWindowFocusInTheCache() {
        // Save a window focus.
        mRotaryCache.saveWindowFocus(mFocusedNode, 0);

        // In the cache.
        AccessibilityNodeInfo node = mRotaryCache.getMostRecentFocus(mValidTime);
        assertThat(node).isEqualTo(mFocusedNode);
    }

    @Test
    public void testGetWindowFocusNotInTheCache() {
        // Not in the cache.
        AccessibilityNodeInfo node = mRotaryCache.getMostRecentFocus(mValidTime);
        assertThat(node).isNull();
    }

    @Test
    public void testGetWindowFocusExpiredCache() {
        // Save a window focus.
        mRotaryCache.saveWindowFocus(mFocusedNode, 0);

        // Expired cache.
        AccessibilityNodeInfo node = mRotaryCache.getMostRecentFocus(mExpiredTime);
        assertThat(node).isNull();
    }

    @Test
    public void testGetWindowFocusNotInViewTree() {
        // Save a window focus that is no longer in the view tree.
        AccessibilityNodeInfo node =
                new NodeBuilder().setInViewTree(false).setFocusable(true).build();
        mRotaryCache.saveWindowFocus(node, 0);

        AccessibilityNodeInfo result = mRotaryCache.getMostRecentFocus(mValidTime);
        assertThat(result).isNull();
    }

    @Test
    public void testGetWindowFocusCannotTakeFocus() {
        // Save a window focus that is still in the view tree but can't take focus.
        AccessibilityNodeInfo node =
                new NodeBuilder().setInViewTree(true).setFocusable(false).build();
        mRotaryCache.saveWindowFocus(node, 0);

        AccessibilityNodeInfo result = mRotaryCache.getMostRecentFocus(mValidTime);
        assertThat(result).isNull();
    }

    @Test
    public void testGetWindowFocusInMultipleWindows() {
        // Save two window focuses in one window and then two in another.
        AccessibilityNodeInfo node1InWindow1 = createFocusNodeInWindow(1);
        AccessibilityNodeInfo node2InWindow1 = createFocusNodeInWindow(1);
        AccessibilityNodeInfo node1InWindow2 = createFocusNodeInWindow(2);
        AccessibilityNodeInfo node2InWindow2 = createFocusNodeInWindow(2);
        mRotaryCache.saveWindowFocus(node1InWindow1, 0);
        mRotaryCache.saveWindowFocus(node2InWindow1, 0);
        mRotaryCache.saveWindowFocus(node1InWindow2, 0);
        mRotaryCache.saveWindowFocus(node2InWindow2, 0);

        // The most recent node should be the second node in the second window.
        AccessibilityNodeInfo node = mRotaryCache.getMostRecentFocus(mValidTime);
        assertThat(node).isEqualTo(node2InWindow2);
    }

    /** Creates a node that is in the view tree. */
    private AccessibilityNodeInfo createNode() {
        return new NodeBuilder().setInViewTree(true).build();
    }

    /** Creates a node that is in the view tree and can take focus. */
    private AccessibilityNodeInfo createFocusNode() {
        return new NodeBuilder()
                .setInViewTree(true)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
    }

    private AccessibilityNodeInfo createFocusNodeInWindow(int windowId) {
        return new NodeBuilder()
                .setWindowId(windowId)
                .setInViewTree(true)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
    }

    /** Creates a FocusHistory and saves it in the cache. */
    private void saveFocusHistory() {
        AccessibilityNodeInfo focusArea = createNode();
        AccessibilityNodeInfo node = createFocusNode();
        mRotaryCache.saveFocusedNode(focusArea, node, 0);
    }

    /** Creates a FocusAreaHistory and saves it in the cache. */
    private void saveFocusAreaHistory() {
        AccessibilityNodeInfo focusArea = createNode();
        AccessibilityNodeInfo targetFocusArea = createFocusNode();
        int direction = View.FOCUS_UP; // Any valid direction (up, down, left, or right) is fine.
        mRotaryCache.saveTargetFocusArea(focusArea, targetFocusArea, direction, 0);
    }
}
