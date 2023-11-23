/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.ui.utils;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.rule.ActivityTestRule;

import com.android.car.ui.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

/** Unit tests for {@link DirectManipulationHelper}. */
public class DirectManipulationHelperTest {
    @Rule
    public ActivityTestRule<DirectManipulationHelperTestActivity> mActivityRule =
            new ActivityTestRule<>(DirectManipulationHelperTestActivity.class);

    private DirectManipulationHelperTestActivity mActivity;

    @Mock
    private Context mContext;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private View mView;

    @Captor
    ArgumentCaptor<AccessibilityEvent> mAccessibilityEventCaptor;

    @Before
    public void setUp() {
        mActivity = mActivityRule.getActivity();
        initMocks(this);
    }

    @Test
    public void testEnableDirectManipulationMode_fail() {
        when(mView.getContext()).thenReturn(mContext);
        when(mContext.getSystemService(Context.ACCESSIBILITY_SERVICE)).thenReturn(
                mAccessibilityManager);
        when(mAccessibilityManager.isEnabled()).thenReturn(false);

        boolean sent = DirectManipulationHelper.enableDirectManipulationMode(mView, true);

        assertThat(sent).isFalse();
        verify(mAccessibilityManager, never()).sendAccessibilityEvent(any());
    }

    @Test
    public void testEnableDirectManipulationMode_enable() {
        when(mView.getContext()).thenReturn(mContext);
        when(mContext.getSystemService(Context.ACCESSIBILITY_SERVICE)).thenReturn(
                mAccessibilityManager);
        when(mAccessibilityManager.isEnabled()).thenReturn(true);

        boolean sent = DirectManipulationHelper.enableDirectManipulationMode(mView, true);

        assertThat(sent).isTrue();
        verify(mAccessibilityManager).sendAccessibilityEvent(mAccessibilityEventCaptor.capture());
        AccessibilityEvent capturedEvent = mAccessibilityEventCaptor.getValue();
        assertThat(capturedEvent.getClassName()).isEqualTo(
                DirectManipulationHelper.DIRECT_MANIPULATION);
        assertThat(capturedEvent.getEventType()).isEqualTo(
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    }

    @Test
    public void testEnableDirectManipulationMode_disable() {
        when(mView.getContext()).thenReturn(mContext);
        when(mContext.getSystemService(Context.ACCESSIBILITY_SERVICE)).thenReturn(
                mAccessibilityManager);
        when(mAccessibilityManager.isEnabled()).thenReturn(true);

        boolean sent = DirectManipulationHelper.enableDirectManipulationMode(mView, false);

        assertThat(sent).isTrue();
        verify(mAccessibilityManager).sendAccessibilityEvent(mAccessibilityEventCaptor.capture());
        AccessibilityEvent capturedEvent = mAccessibilityEventCaptor.getValue();
        assertThat(capturedEvent.getClassName()).isEqualTo(
                DirectManipulationHelper.DIRECT_MANIPULATION);
        assertThat(capturedEvent.getEventType()).isEqualTo(
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
    }

    @Test
    public void testIsDirectManipulation() {
        AccessibilityEvent event = AccessibilityEvent.obtain();
        assertThat(DirectManipulationHelper.isDirectManipulation(event)).isFalse();

        event.setClassName(DirectManipulationHelper.DIRECT_MANIPULATION);
        assertThat(DirectManipulationHelper.isDirectManipulation(event)).isTrue();
    }

    @Test
    public void testSupportRotateDirectly() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        assertThat(DirectManipulationHelper.supportRotateDirectly(node)).isFalse();

        node.setContentDescription("normal content description");
        assertThat(DirectManipulationHelper.supportRotateDirectly(node)).isFalse();

        node.setContentDescription(DirectManipulationHelper.DIRECT_MANIPULATION);
        assertThat(DirectManipulationHelper.supportRotateDirectly(node)).isTrue();
    }

    @Test
    public void testSetSupportsRotateDirectly() {
        View view = mActivity.findViewById(R.id.view);
        assertThat(view.getContentDescription()).isNull();

        DirectManipulationHelper.setSupportsRotateDirectly(view, true);
        assertThat(view.getContentDescription()).isEqualTo(
                DirectManipulationHelper.DIRECT_MANIPULATION);

        DirectManipulationHelper.setSupportsRotateDirectly(view, false);
        assertThat(view.getContentDescription()).isNull();
    }
}
