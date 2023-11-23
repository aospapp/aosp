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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A builder which builds a mock {@link AccessibilityNodeInfo}. Unlike real nodes, mock nodes don't
 * need to be recycled.
 */
class NodeBuilder {
    /**
     * A list of mock nodes created via NodeBuilder. This list is used for searching for a
     * node's child nodes.
     */
    private List<AccessibilityNodeInfo> mNodeList;
    /** The window to which this node belongs. */
    @Nullable
    private AccessibilityWindowInfo mWindow;
    /** The window ID to which this node belongs. */
    @Nullable
    private Integer mWindowId;
    /** The parent of this node. */
    @Nullable
    private AccessibilityNodeInfo mParent;
    /** The class this node comes from. */
    @Nullable
    private String mClassName;
    /** The node bounds in screen coordinates. */
    @Nullable
    private Rect mBoundsInScreen;
    /** Whether this node is focusable. */
    private boolean mFocusable;
    /** Whether this node is visible to the user. */
    private boolean mVisibleToUser;
    /** Whether this node is enabled. */
    private boolean mEnabled;
    /** Whether the view represented by this node is still in the view tree. */
    private boolean mInViewTree;
    /** The content description for this node. */
    @Nullable
    private String mContentDescription;
    /** The action list for this node. */
    @NonNull
    private List<AccessibilityNodeInfo.AccessibilityAction> mActionList = new ArrayList<>();

    AccessibilityNodeInfo build() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        if (mWindow != null) {
            // Mock AccessibilityNodeInfo#getWindow().
            when(node.getWindow()).thenReturn(mWindow);
        }
        if (mWindowId != null) {
            // Mock AccessibilityNodeInfo#getWindowId().
            when(node.getWindowId()).thenReturn(mWindowId);
        }
        if (mParent != null && mNodeList != null) {
            // Mock AccessibilityNodeInfo#getParent().
            when(node.getParent()).thenReturn(mParent);

            // Mock AccessibilityNodeInfo#getChildCount().
            doAnswer(invocation -> {
                int childCount = 0;
                for (AccessibilityNodeInfo candidate : mNodeList) {
                    if (mParent.equals(candidate.getParent())) {
                        childCount++;
                    }
                }
                return childCount;
            }).when(mParent).getChildCount();

            // Mock AccessibilityNodeInfo#getChild(int).
            doAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                int index = (int) args[0];
                for (AccessibilityNodeInfo candidate : mNodeList) {
                    if (mParent.equals(candidate.getParent())) {
                        if (index == 0) {
                            return candidate;
                        } else {
                            index--;
                        }
                    }
                }
                return null;
            }).when(mParent).getChild(any(Integer.class));
        }
        if (mClassName != null) {
            // Mock AccessibilityNodeInfo#getClassName().
            when(node.getClassName()).thenReturn(mClassName);
        }
        if (mBoundsInScreen != null) {
            // Mock AccessibilityNodeInfo#getBoundsInScreen(Rect).
            doAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                ((Rect) args[0]).set(mBoundsInScreen);
                return null;
            }).when(node).getBoundsInScreen(any(Rect.class));
        }
        // Mock AccessibilityNodeInfo#isFocusable().
        when(node.isFocusable()).thenReturn(mFocusable);
        // Mock AccessibilityNodeInfo#isVisibleToUser().
        when(node.isVisibleToUser()).thenReturn(mVisibleToUser);
        // Mock AccessibilityNodeInfo#isEnabled().
        when(node.isEnabled()).thenReturn(mEnabled);

        // Mock AccessibilityNodeInfo#refresh().
        when(node.refresh()).thenReturn(mInViewTree);

        if (mContentDescription != null) {
            // Mock AccessibilityNodeInfo#getContentDescription().
            when(node.getContentDescription()).thenReturn(mContentDescription);
        }

        // Mock AccessibilityNodeInfo#getActionList().
        when(node.getActionList()).thenReturn(mActionList);

        if (mNodeList != null) {
            mNodeList.add(node);
        }
        return node;
    }

    NodeBuilder setNodeList(@NonNull List<AccessibilityNodeInfo> nodeList) {
        mNodeList = nodeList;
        return this;
    }

    NodeBuilder setWindow(@Nullable AccessibilityWindowInfo window) {
        mWindow = window;
        return this;
    }

    NodeBuilder setWindowId(int windowId) {
        mWindowId = windowId;
        return this;
    }

    NodeBuilder setParent(@Nullable AccessibilityNodeInfo parent) {
        mParent = parent;
        return this;
    }

    NodeBuilder setClassName(@Nullable String className) {
        mClassName = className;
        return this;
    }

    NodeBuilder setBoundsInScreen(@Nullable Rect boundsInScreen) {
        mBoundsInScreen = boundsInScreen;
        return this;
    }

    NodeBuilder setFocusable(boolean focusable) {
        mFocusable = focusable;
        return this;
    }

    NodeBuilder setVisibleToUser(boolean visibleToUser) {
        mVisibleToUser = visibleToUser;
        return this;
    }

    NodeBuilder setEnabled(boolean enabled) {
        mEnabled = enabled;
        return this;
    }

    NodeBuilder setInViewTree(boolean inViewTree) {
        mInViewTree = inViewTree;
        return this;
    }

    NodeBuilder setContentDescription(@Nullable String contentDescription) {
        mContentDescription = contentDescription;
        return this;
    }

    NodeBuilder setActionList(@NonNull List<AccessibilityNodeInfo.AccessibilityAction> actionList) {
        mActionList = actionList;
        return this;
    }
}
