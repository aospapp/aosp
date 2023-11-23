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

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.android.car.ui.FocusArea;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class NodeBuilderTest {

    private static final String FOCUS_AREA_CLASS_NAME = FocusArea.class.getName();

    @Test
    public void testBuildNode() {
        AccessibilityWindowInfo window = new WindowBuilder().build();
        List<AccessibilityNodeInfo> nodeList = new ArrayList<>();
        Rect bounds = new Rect(100, 200, 300, 400);
        AccessibilityNodeInfo parent = new NodeBuilder()
                .setNodeList(nodeList)
                .setWindow(window)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setInViewTree(true)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(bounds)
                .build();

        assertThat(parent.getWindow()).isSameAs(window);
        assertThat(parent.getClassName()).isEqualTo(FOCUS_AREA_CLASS_NAME);
        assertThat(parent.isFocusable()).isTrue();
        assertThat(parent.isVisibleToUser()).isTrue();
        assertThat(parent.refresh()).isTrue();
        assertThat(parent.isEnabled()).isTrue();

        Rect boundsInScreen = new Rect();
        parent.getBoundsInScreen(boundsInScreen);
        assertThat(boundsInScreen).isEqualTo(bounds);

        AccessibilityNodeInfo child1 = new NodeBuilder()
                .setNodeList(nodeList)
                .setParent(parent).build();
        AccessibilityNodeInfo child2 = new NodeBuilder()
                .setNodeList(nodeList)
                .setParent(parent).build();

        assertThat(child1.getParent()).isSameAs(parent);
        assertThat(parent.getChildCount()).isEqualTo(2);
        assertThat(parent.getChild(0)).isSameAs(child1);
        assertThat(parent.getChild(1)).isSameAs(child2);
        assertThat(parent.getChild(2)).isNull();
    }
}
