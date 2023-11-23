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

import static com.android.car.ui.utils.RotaryConstants.ROTARY_HORIZONTALLY_SCROLLABLE;
import static com.android.car.ui.utils.RotaryConstants.ROTARY_VERTICALLY_SCROLLABLE;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.ui.FocusArea;
import com.android.car.ui.FocusParkingView;

import java.util.List;

/**
 * Utility methods for {@link AccessibilityNodeInfo} and {@link AccessibilityWindowInfo}.
 * <p>
 * Because {@link AccessibilityNodeInfo}s must be recycled, it's important to be consistent about
 * who is responsible for recycling them. For simplicity, it's best to avoid having multiple objects
 * refer to the same instance of {@link AccessibilityNodeInfo}. Instead, each object should keep its
 * own copy which it's responsible for. Methods that return an {@link AccessibilityNodeInfo}
 * generally pass ownership to the caller. Such methods should never return a reference to one of
 * their parameters or the caller will recycle it twice.
 */
class Utils {

    private static final String FOCUS_AREA_CLASS_NAME = FocusArea.class.getName();
    private static final String FOCUS_PARKING_VIEW_CLASS_NAME = FocusParkingView.class.getName();

    private Utils() {
    }

    /** Recycles a node. */
    static void recycleNode(@Nullable AccessibilityNodeInfo node) {
        if (node != null) {
            node.recycle();
        }
    }

    /** Recycles a list of nodes. */
    static void recycleNodes(@Nullable List<AccessibilityNodeInfo> nodes) {
        if (nodes != null) {
            for (AccessibilityNodeInfo node : nodes) {
                recycleNode(node);
            }
        }
    }

    /**
     * Updates the given {@code node} in case the view represented by it is no longer in the view
     * tree. If it's still in the view tree, returns the {@code node}. Otherwise recycles the
     * {@code node} and returns null.
     */
    static AccessibilityNodeInfo refreshNode(@Nullable AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        boolean succeeded = node.refresh();
        if (succeeded) {
            return node;
        }
        L.w("This node is no longer in the view tree: " + node);
        node.recycle();
        return null;
    }

    /** Returns whether the given {@code node} can be focused by a rotary controller. */
    static boolean canTakeFocus(@NonNull AccessibilityNodeInfo node) {
        return node.isVisibleToUser() && node.isFocusable() && node.isEnabled()
                && !isFocusParkingView(node);
    }

    /** Returns whether the given {@code node} or its descendants can take focus. */
    static boolean canHaveFocus(@NonNull AccessibilityNodeInfo node) {
        if (canTakeFocus(node)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = node.getChild(i);
            if (childNode != null) {
                boolean result = canHaveFocus(childNode);
                childNode.recycle();
                if (result) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns whether the given {@code node} has focus (i.e. the node or one of its descendants is
     * focused).
     */
    static boolean hasFocus(@NonNull AccessibilityNodeInfo node) {
        if (node.isFocused()) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = node.getChild(i);
            if (childNode != null) {
                boolean result = hasFocus(childNode);
                childNode.recycle();
                if (result) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns whether the given {@code node} represents a {@link FocusParkingView}. */
    static boolean isFocusParkingView(@NonNull AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName();
        return className != null && FOCUS_PARKING_VIEW_CLASS_NAME.contentEquals(className);
    }

    /** Returns whether the given {@code node} represents a {@link FocusArea}. */
    static boolean isFocusArea(@NonNull AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName();
        return className != null && FOCUS_AREA_CLASS_NAME.contentEquals(className);
    }

    /**
     * Returns whether the given node represents a view which can be scrolled using the rotary
     * controller, as indicated by its content description.
     */
    static boolean isScrollableContainer(@NonNull AccessibilityNodeInfo node) {
        CharSequence contentDescription = node.getContentDescription();
        return contentDescription != null
                && (ROTARY_HORIZONTALLY_SCROLLABLE.contentEquals(contentDescription)
                || ROTARY_VERTICALLY_SCROLLABLE.contentEquals(contentDescription));
    }

    /**
     * Returns whether the given node represents a view which can be scrolled horizontally using the
     * rotary controller, as indicated by its content description.
     */
    static boolean isHorizontallyScrollableContainer(@NonNull AccessibilityNodeInfo node) {
        CharSequence contentDescription = node.getContentDescription();
        return contentDescription != null
                && (ROTARY_HORIZONTALLY_SCROLLABLE.contentEquals(contentDescription));
    }

    /** Returns whether {@code descendant} is a descendant of {@code ancestor}. */
    static boolean isDescendant(@NonNull AccessibilityNodeInfo ancestor,
            @NonNull AccessibilityNodeInfo descendant) {
        AccessibilityNodeInfo parent = descendant.getParent();
        if (parent == null) {
            return false;
        }
        boolean result = parent.equals(ancestor) || isDescendant(ancestor, parent);
        recycleNode(parent);
        return result;
    }

    /** Recycles a window. */
    static void recycleWindow(@Nullable AccessibilityWindowInfo window) {
        if (window != null) {
            window.recycle();
        }
    }

    /** Recycles a list of windows. */
    static void recycleWindows(@Nullable List<AccessibilityWindowInfo> windows) {
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                recycleWindow(window);
            }
        }
    }
}
