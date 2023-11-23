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

import androidx.annotation.Nullable;

/**
 * A builder which builds a mock {@link AccessibilityWindowInfo}. Unlike real windows, mock windows
 * don't need to be recycled.
 */
class WindowBuilder {
    /** The root node in the window's hierarchy. */
    private AccessibilityNodeInfo mRoot;
    /** The bounds of this window in the screen. */
    private Rect mBoundsInScreen;
    /** The window type, if specified. */
    private Integer mType;

    AccessibilityWindowInfo build() {
        AccessibilityWindowInfo window = mock(AccessibilityWindowInfo.class);
        if (mRoot != null) {
            // Mock AccessibilityWindowInfo#getRoot().
            when(window.getRoot()).thenReturn(mRoot);
        }
        if (mBoundsInScreen != null) {
            // Mock AccessibilityWindowInfo#getBoundsInScreen(Rect).
            doAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                ((Rect) args[0]).set(mBoundsInScreen);
                return null;
            }).when(window).getBoundsInScreen(any(Rect.class));
        }
        if (mType != null) {
            when(window.getType()).thenReturn(mType);
        }
        return window;
    }

    WindowBuilder setRoot(@Nullable AccessibilityNodeInfo root) {
        mRoot = root;
        return this;
    }

    WindowBuilder setBoundsInScreen(@Nullable Rect boundsInScreen) {
        mBoundsInScreen = boundsInScreen;
        return this;
    }

    WindowBuilder setType(int type) {
        mType = type;
        return this;
    }
}
