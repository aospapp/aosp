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

import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;

import com.android.car.rotary.Navigator.FindRotateTargetResult;
import com.android.car.ui.FocusArea;
import com.android.car.ui.FocusParkingView;
import com.android.car.ui.utils.RotaryConstants;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class NavigatorTest {
    private static final String FOCUS_AREA_CLASS_NAME = FocusArea.class.getName();
    private static final String FOCUS_PARKING_VIEW_CLASS_NAME = FocusParkingView.class.getName();

    @Mock
    private NodeCopier mNodeCopier;

    private Rect mHunWindowBounds;

    private Navigator mNavigator;

    private List<AccessibilityNodeInfo> mNodeList;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mHunWindowBounds = new Rect(50, 10, 950, 200);

        mNavigator = new Navigator(
                /* focusHistoryCacheType= */ RotaryCache.CACHE_TYPE_NEVER_EXPIRE,
                /* focusHistoryCacheSize= */ 10,
                /* focusHistoryExpirationTimeMs= */ 0,
                /* focusAreaHistoryCacheType= */ RotaryCache.CACHE_TYPE_NEVER_EXPIRE,
                /* focusAreaHistoryCacheSize= */ 5,
                /* focusAreaHistoryExpirationTimeMs= */ 0,
                /* focusWindowCacheType= */ RotaryCache.CACHE_TYPE_NEVER_EXPIRE,
                /* focusWindowCacheSize= */ 5,
                /* focusWindowExpirationTimeMs= */ 0,
                mHunWindowBounds.left,
                mHunWindowBounds.right,
                /* showHunOnBottom= */ false);

        // Utils#copyNode() doesn't work when passed a mock node, so we create a mock method
        // which returns the passed node itself rather than a copy. As a result, nodes created by
        // the mock method (such as |target| in testFindRotateTarget()) shouldn't be recycled.
        doAnswer(returnsFirstArg()).when(mNodeCopier).copy(any(AccessibilityNodeInfo.class));

        mNavigator.setNodeCopier(mNodeCopier);

        mNodeList = new ArrayList<>();
    }

    @Test
    public void testSetRootNodeForWindow() {
        AccessibilityWindowInfo window = new WindowBuilder().build();
        AccessibilityNodeInfo root = new NodeBuilder().build();
        setRootNodeForWindow(root, window);

        assertThat(window.getRoot()).isSameAs(root);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *              root
     *               |
     *           focusArea
     *          /    |    \
     *        /      |     \
     *    button1 button2 button3
     * </pre>
     */
    @Test
    public void testFindRotateTarget() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();

        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .build();
        AccessibilityNodeInfo button3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(null);

        // Rotate once, the focus should move from button1 to button2.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, null, direction, 1);
        assertThat(target.node).isSameAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);

        // Rotate twice, the focus should move from button1 to button3.
        target = mNavigator.findRotateTarget(button1, null, direction, 2);
        assertThat(target.node).isSameAs(button3);
        assertThat(target.advancedCount).isEqualTo(2);

        // Rotate 3 times and exceed the boundary, the focus should stay at the boundary.
        target = mNavigator.findRotateTarget(button1, null, direction, 3);
        assertThat(target.node).isSameAs(button3);
        assertThat(target.advancedCount).isEqualTo(2);

        // Rotate once, skipping button2; the focus should move to button3.
        target = mNavigator.findRotateTarget(button1, button2, direction, 1);
        assertThat(target.node).isSameAs(button3);
        assertThat(target.advancedCount).isEqualTo(1);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                     root
     *                    /    \
     *                   /      \
     *      focusParkingView   focusArea
     *                           /    \
     *                          /      \
     *                       button1  button2
     * </pre>
     */
    @Test
    public void testFindRotateTargetNoWrapAround() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusParkingView = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_PARKING_VIEW_CLASS_NAME)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo focusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();

        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(focusParkingView);
        when(focusParkingView.focusSearch(direction)).thenReturn(button1);

        // Rotate at the end of focus area, no wrap-around should happen.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button2, null, direction, 1);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *      focusParkingView   button1   button2
     * </pre>
     */
    @Test
    public void testFindRotateTargetNoWrapAround2() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusParkingView = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_PARKING_VIEW_CLASS_NAME)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(focusParkingView);
        when(focusParkingView.focusSearch(direction)).thenReturn(button1);

        // Rotate at the end of focus area, no wrap-around should happen.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button2, null, direction, 1);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following layout:
     * <pre>
     *     ============ focus area ============
     *     =                                  =
     *     =  ***** scrollable container **** =
     *     =  *                             * =
     *     =  *  ........ button 1 ........ * =
     *     =  *  .                        . * =
     *     =  *  .......................... * =
     *     =  *                             * =
     *     =  *  ........ button 2 ........ * =
     *     =  *  .                        . * =
     *     =  *  .......................... * =
     *     =  *                             * =
     *     =  ******************************* =
     *     =                                  =
     *     ============ focus area ============
     *
     *           ........ button 3 ........
     *           .      (offscreen)       .
     *           ..........................
     * </pre>
     * where {@code button 3} is inside the scrollable container.
     */
    @Test
    public void testFindRotateTargetInScrollableContainer() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 0, 100, 100))
                .build();
        AccessibilityNodeInfo scrollableContainer = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .setFocusable(true)
                .setContentDescription(RotaryConstants.ROTARY_VERTICALLY_SCROLLABLE)
                .setActionList(new ArrayList<>(Collections.singletonList(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)))
                .setBoundsInScreen(new Rect(0, 0, 100, 100))
                .build();

        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(scrollableContainer)
                .setBoundsInScreen(new Rect(0, 0, 100, 50))
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(scrollableContainer)
                .setBoundsInScreen(new Rect(0, 50, 100, 100))
                .build();
        AccessibilityNodeInfo button3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(scrollableContainer)
                .setBoundsInScreen(new Rect(0, 100, 100, 150))
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(null);

        // Rotate once, the focus should move from button1 to button2.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, null, direction, 1);
        assertThat(target.node).isSameAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);

        // Rotate twice, the focus should move from button1 to button2 since button3 is out of
        // bounds.
        target = mNavigator.findRotateTarget(button1, null, direction, 2);
        assertThat(target.node).isSameAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);

        // Rotate three times should do the same.
        target = mNavigator.findRotateTarget(button1, null, direction, 3);
        assertThat(target.node).isSameAs(button2);
        assertThat(target.advancedCount).isEqualTo(1);
    }

    /**
     * Tests {@link Navigator#findNudgeTarget} in the following layout:
     * <pre>
     *    ****************leftWindow**************    **************rightWindow****************
     *    *                                      *    *                                       *
     *    *  ========topLeft focus area========  *    *  ========topRight focus area========  *
     *    *  =                                =  *    *  =                                 =  *
     *    *  =  .............  .............  =  *    *  =  .............                  =  *
     *    *  =  .           .  .           .  =  *    *  =  .           .                  =  *
     *    *  =  . topLeft1  .  .  topLeft2 .  =  *    *  =  . topRight1 .                  =  *
     *    *  =  .           .  .           .  =  *    *  =  .           .                  =  *
     *    *  =  .............  .............  =  *    *  =  .............                  =  *
     *    *  =                                =  *    *  =                                 =  *
     *    *  ==================================  *    *  ===================================  *
     *    *                                      *    *                                       *
     *    *  =======middleLeft focus area======  *    *                                       *
     *    *  =                                =  *    *                                       *
     *    *  =  .............  .............  =  *    *                                       *
     *    *  =  .           .  .           .  =  *    *                                       *
     *    *  =  .middleLeft1.  .middleLeft2.  =  *    *                                       *
     *    *  =  . disabled  .  . disabled  .  =  *    *                                       *
     *    *  =  .............  .............  =  *    *                                       *
     *    *  =                                =  *    *                                       *
     *    *  ==================================  *    *                                       *
     *    *                                      *    *                                       *
     *    *  =======bottomLeft focus area======  *    *                                       *
     *    *  =                                =  *    *                                       *
     *    *  =  .............  .............  =  *    *                                       *
     *    *  =  .           .  .           .  =  *    *                                       *
     *    *  =  .bottomLeft1.  .bottomLeft2.  =  *    *                                       *
     *    *  =  .           .  .           .  =  *    *                                       *
     *    *  =  .............  .............  =  *    *                                       *
     *    *  =                                =  *    *                                       *
     *    *  ==================================  *    *                                       *
     *    *                                      *    *                                       *
     *    ****************************************    *****************************************
     * </pre>
     */
    @Test
    public void testFindNudgeTarget() {
        // There are 2 windows. This is the left window.
        Rect leftWindowBounds = new Rect(0, 0, 400, 1200);
        AccessibilityWindowInfo leftWindow = new WindowBuilder()
                .setBoundsInScreen(leftWindowBounds)
                .build();
        // We must specify window and boundsInScreen for each node when finding nudge target.
        AccessibilityNodeInfo leftRoot = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setBoundsInScreen(leftWindowBounds)
                .build();
        setRootNodeForWindow(leftRoot, leftWindow);

        // Left window has 3 vertically aligned focus areas.
        AccessibilityNodeInfo topLeft = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 0, 400, 400))
                .build();
        AccessibilityNodeInfo middleLeft = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 400, 400, 800))
                .build();
        AccessibilityNodeInfo bottomLeft = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 800, 400, 1200))
                .setInViewTree(true)
                .build();

        // Each focus area but middleLeft has 2 horizontally aligned views that can take focus.
        AccessibilityNodeInfo topLeft1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(topLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 0, 200, 400))
                .build();
        AccessibilityNodeInfo topLeft2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(topLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(200, 0, 400, 400))
                .build();
        AccessibilityNodeInfo bottomLeft1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(bottomLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setInViewTree(true)
                .setBoundsInScreen(new Rect(0, 800, 200, 1200))
                .build();
        AccessibilityNodeInfo bottomLeft2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(bottomLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(200, 800, 400, 1200))
                .build();

        // middleLeft focus area has 2 disabled views, so that it will be skipped when nudging.
        AccessibilityNodeInfo middleLeft1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(bottomLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(false)
                .setInViewTree(true)
                .setBoundsInScreen(new Rect(0, 400, 200, 800))
                .build();
        AccessibilityNodeInfo middleLeft2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(bottomLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(false)
                .setBoundsInScreen(new Rect(200, 400, 400, 800))
                .build();

        // This is the right window.
        Rect rightWindowBounds = new Rect(400, 0, 800, 1200);
        AccessibilityWindowInfo rightWindow = new WindowBuilder()
                .setBoundsInScreen(rightWindowBounds)
                .build();
        AccessibilityNodeInfo rightRoot = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setBoundsInScreen(rightWindowBounds)
                .build();
        setRootNodeForWindow(rightRoot, rightWindow);

        // Right window has 1 focus area.
        AccessibilityNodeInfo topRight = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setParent(rightRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(400, 0, 800, 400))
                .build();

        // The focus area has 1 view that can take focus.
        AccessibilityNodeInfo topRight1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setParent(topRight)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(400, 0, 600, 400))
                .build();

        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        windows.add(leftWindow);
        windows.add(rightWindow);

        // Nudge within the same window.
        AccessibilityNodeInfo target =
                mNavigator.findNudgeTarget(windows, topLeft1, View.FOCUS_DOWN);
        assertThat(target).isSameAs(bottomLeft1);

        // Reach to the boundary.
        target = mNavigator.findNudgeTarget(windows, topLeft1, View.FOCUS_UP);
        assertThat(target).isNull();

        // Nudge to a different window.
        target = mNavigator.findNudgeTarget(windows, topRight1, View.FOCUS_LEFT);
        assertThat(target).isSameAs(topLeft2);

        // When nudging back, the focus should return to the previously focused node within the
        // previous focus area, rather than the geometrically close node or focus area.

        // Firstly, we need to save the focused node.
        mNavigator.saveFocusedNode(bottomLeft1);
        // Then nudge to right.
        target = mNavigator.findNudgeTarget(windows, bottomLeft1, View.FOCUS_RIGHT);
        assertThat(target).isSameAs(topRight1);
        // Then nudge back.
        target = mNavigator.findNudgeTarget(windows, topRight1, View.FOCUS_LEFT);
        assertThat(target).isSameAs(bottomLeft1);
    }

    /**
     * Tests {@link Navigator#findNudgeTarget} in the following layout:
     * <pre>
     *    ****************leftWindow**************    **************rightWindow***************
     *    *                                      *    *                                      *
     *    *  ===left focus area===   parking1    *    *   parking2   ===right focus area===  *
     *    *  =                   =               *    *              =                    =  *
     *    *  =  .............    =               *    *              =  .............     =  *
     *    *  =  .           .    =               *    *              =  .           .     =  *
     *    *  =  .   left    .    =               *    *              =  .   right   .     =  *
     *    *  =  .           .    =               *    *              =  .           .     =  *
     *    *  =  .............    =               *    *              =  .............     =  *
     *    *  =                   =               *    *              =                    =  *
     *    *  =====================               *    *              ======================  *
     *    *                                      *    *                                      *
     *    ****************************************    *****************************************
     * </pre>
     */
    @Test
    public void testFindNudgeTargetWithFocusParkingView() {
        // There are 2 windows. This is the left window.
        Rect leftWindowBounds = new Rect(0, 0, 400, 400);
        AccessibilityWindowInfo leftWindow = new WindowBuilder()
                .setBoundsInScreen(leftWindowBounds)
                .build();
        AccessibilityNodeInfo leftRoot = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setBoundsInScreen(leftWindowBounds)
                .build();
        setRootNodeForWindow(leftRoot, leftWindow);

        // Left focus area and its view inside.
        AccessibilityNodeInfo leftFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 0, 300, 400))
                .build();
        AccessibilityNodeInfo left = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 0, 300, 400))
                .build();

        // Left focus parking view.
        AccessibilityNodeInfo parking1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftFocusArea)
                .setClassName(FOCUS_PARKING_VIEW_CLASS_NAME)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(350, 0, 351, 1))
                .build();

        // Right window.
        Rect rightWindowBounds = new Rect(400, 0, 800, 400);
        AccessibilityWindowInfo rightWindow = new WindowBuilder()
                .setBoundsInScreen(rightWindowBounds)
                .build();
        AccessibilityNodeInfo rightRoot = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setBoundsInScreen(rightWindowBounds)
                .build();
        setRootNodeForWindow(rightRoot, rightWindow);

        // Right focus area and its view inside.
        AccessibilityNodeInfo rightFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setParent(rightRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(500, 0, 800, 400))
                .build();
        AccessibilityNodeInfo right = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setParent(rightFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(500, 0, 800, 400))
                .build();

        // Right focus parking view.
        AccessibilityNodeInfo parking2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setParent(rightFocusArea)
                .setClassName(FOCUS_PARKING_VIEW_CLASS_NAME)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(450, 0, 451, 1))
                .build();

        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        windows.add(leftWindow);
        windows.add(rightWindow);

        // Nudge from left window to right window.
        AccessibilityNodeInfo target = mNavigator.findNudgeTarget(windows, left, View.FOCUS_RIGHT);
        assertThat(target).isSameAs(right);
    }

    /**
     * Tests {@link Navigator#findNudgeTarget} in the following layout:
     * <pre>
     *    ****************mainWindow**************
     *    *                                      *
     *    *    ==============top=============    *
     *    *    =                            =    *
     *    *    =  ...........  ...........  =    *
     *    *    =  .         .  .         .  =    *
     *    *    =  . topLeft .  . topRight.  =    *
     *    *    =  .         .  .         .  =    *
     *    *    =  ...........  ...........  =    *
     *    *    =                            =    *
     *    *    ==============================    *
     *    *                                      *
     *    *    ============bottom============    *
     *    *    =                            =    *
     *    *    =  ...........  ...........  =    *
     *    *    =  .         .  .         .  =    *
     *    *    =  . bottom  .  . bottom  .  =    *
     *    *    =  .  Left   .  .  Right  .  =    *
     *    *    =  ...........  ...........  =    *
     *    *    =                            =    *
     *    *    ==============================    *
     *    *                                      *
     *    ****************************************
     * </pre>
     * with the HUN overlapping the top of the main window:
     * <pre>
     *       *************hunWindow************
     *       * ..............  .............. *
     *       * .  hunLeft   .  .  hunRight  . *
     *       * ..............  .............. *
     *       **********************************
     * </pre>
     */
    @Test
    public void testFindHunNudgeTarget() {
        // There are two windows. This is the HUN window.
        AccessibilityWindowInfo hunWindow = new WindowBuilder()
                .setBoundsInScreen(mHunWindowBounds)
                .setType(AccessibilityWindowInfo.TYPE_SYSTEM)
                .build();
        // We must specify window and boundsInScreen for each node when finding nudge target.
        AccessibilityNodeInfo hunRoot = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(hunWindow)
                .setBoundsInScreen(mHunWindowBounds)
                .build();
        setRootNodeForWindow(hunRoot, hunWindow);

        // HUN window has two views that can take focus (directly in the root).
        AccessibilityNodeInfo hunLeft = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(hunWindow)
                .setParent(hunRoot)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(mHunWindowBounds.left, mHunWindowBounds.top,
                        mHunWindowBounds.centerX(), mHunWindowBounds.bottom))
                .build();
        AccessibilityNodeInfo hunRight = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(hunWindow)
                .setParent(hunRoot)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(mHunWindowBounds.centerX(), mHunWindowBounds.top,
                        mHunWindowBounds.right, mHunWindowBounds.bottom))
                .build();

        // This is the main window.
        Rect mainWindowBounds = new Rect(0, 0, 1000, 1000);
        AccessibilityWindowInfo mainWindow = new WindowBuilder()
                .setBoundsInScreen(mainWindowBounds)
                .build();
        AccessibilityNodeInfo mainRoot = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(mainWindow)
                .setBoundsInScreen(mainWindowBounds)
                .build();
        setRootNodeForWindow(mainRoot, mainWindow);

        // Main window has two focus areas.
        AccessibilityNodeInfo topFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(mainWindow)
                .setParent(mainRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 0, 1000, 500))
                .build();
        AccessibilityNodeInfo bottomFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(mainWindow)
                .setParent(mainRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 500, 1000, 1000))
                .build();

        // The top focus area has two views that can take focus.
        AccessibilityNodeInfo topLeft = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(mainWindow)
                .setParent(topFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 0, 500, 500))
                .build();
        AccessibilityNodeInfo topRight = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(mainWindow)
                .setParent(topFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(500, 0, 1000, 500))
                .build();

        // The bottom focus area has two views that can take focus.
        AccessibilityNodeInfo bottomLeft = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(mainWindow)
                .setParent(bottomFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 500, 500, 1000))
                .build();
        AccessibilityNodeInfo bottomRight = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(mainWindow)
                .setParent(bottomFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(500, 500, 1000, 1000))
                .build();

        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        windows.add(hunWindow);
        windows.add(mainWindow);

        // Nudging up from the top left or right view should go to the HUN's left button. The
        // source and target overlap so geometric targeting fails. We should fall back to using the
        // first focusable view in the HUN.
        AccessibilityNodeInfo target = mNavigator.findNudgeTarget(windows, topLeft, View.FOCUS_UP);
        assertThat(target).isSameAs(hunLeft);
        target = mNavigator.findNudgeTarget(windows, topRight, View.FOCUS_UP);
        assertThat(target).isSameAs(hunLeft);

        // Nudging up from the bottom left or right view should go to the corresponding button in
        // the HUN, skipping over the top focus area. Geometric targeting should work.
        target = mNavigator.findNudgeTarget(windows, bottomLeft, View.FOCUS_UP);
        assertThat(target).isSameAs(hunLeft);
        target = mNavigator.findNudgeTarget(windows, bottomRight, View.FOCUS_UP);
        assertThat(target).isSameAs(hunRight);
    }

    /**
     * Tests {@link Navigator#findNudgeTarget} in the following layout:
     * In the same window
     *
     *            ======focus area 1===========
     *            =                  *view1*  =
     *            =============================
     *
     *            ========focus area 2=========
     *            = *view2*                   =
     *            =============================
     *
     *    =====source focus area=====
     *    = *    source view      * =
     *    ===========================
     */
    @Test
    public void testNudgeToFocusAreaWithNoCandidates() {
        Rect windowBounds = new Rect(0, 0, 600, 500);
        AccessibilityWindowInfo window = new WindowBuilder()
                .setBoundsInScreen(windowBounds)
                .build();
        AccessibilityNodeInfo root = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setBoundsInScreen(windowBounds)
                .build();
        setRootNodeForWindow(root, window);

        // Currently focused view.
        AccessibilityNodeInfo sourceFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 400, 400, 500))
                .build();
        AccessibilityNodeInfo sourceView = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(sourceFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 400, 400, 500))
                .build();

        // focusArea1 is a better candidate than focusArea2 for a nudge to right, but its descendant
        // view is not a candidate.
        AccessibilityNodeInfo focusArea1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(200, 0, 600, 100))
                .build();
        AccessibilityNodeInfo view1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(focusArea1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(599, 0, 600, 100))
                .build();

        // focusArea2 is a worse candidate than focusArea1 for a nudge to right, but its descendant
        // view is a candidate.
        AccessibilityNodeInfo focusArea2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(200, 200, 600, 300))
                .build();
        AccessibilityNodeInfo view2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(focusArea2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(200, 200, 201, 300))
                .build();

        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        windows.add(window);

        // Nudge from sourceView to right, and it should go to view1.
        AccessibilityNodeInfo target
                = mNavigator.findNudgeTarget(windows, sourceView, View.FOCUS_RIGHT);
        assertThat(target).isSameAs(view1);
    }

    /**
     * Tests {@link Navigator#findNudgeTarget} in the following layout:
     * In the same window
     *
     *    =====source focus area=====
     *    = *    source view      * =
     *    ===========================
     *
     *    ======target focus area====
     *    =   ---non-focusable----  =
     *    =   -                  -  =
     *    =   -  *target view*   -  =
     *    =   -                  -  =
     *    =   ---view container---  =
     *    ===========================
     */
    @Test
    public void testNudgeToFocusAreaWithIndirectChild() {
        Rect windowBounds = new Rect(0, 0, 100, 200);
        AccessibilityWindowInfo window = new WindowBuilder()
                .setBoundsInScreen(windowBounds)
                .build();
        AccessibilityNodeInfo root = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setBoundsInScreen(windowBounds)
                .build();
        setRootNodeForWindow(root, window);

        // Currently focused view.
        AccessibilityNodeInfo sourceFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 0, 100, 100))
                .build();
        AccessibilityNodeInfo sourceView = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(sourceFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 0, 100, 100))
                .build();

        // Target view.
        AccessibilityNodeInfo targetFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 100, 100, 200))
                .build();
        // viewContainer is non-focusable.
        AccessibilityNodeInfo viewContainer = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(targetFocusArea)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 100, 100, 200))
                .build();
        AccessibilityNodeInfo targetView = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(viewContainer)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 100, 100, 200))
                .build();

        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        windows.add(window);

        // Nudge down from sourceView, and it should go to targetView.
        AccessibilityNodeInfo target
                = mNavigator.findNudgeTarget(windows, sourceView, View.FOCUS_DOWN);
        assertThat(target).isSameAs(targetView);
    }

    /**
     * Tests {@link Navigator#findNudgeTarget} in the following layout:
     * In the same window
     *
     *    =====source focus area=====
     *    = *    source view      * =
     *    ===========================
     *
     *    ======target focus area====
     *    =   -----focusable------  =
     *    =   -                  -  =
     *    =   -  *target view*   -  =
     *    =   -                  -  =
     *    =   ---view container---  =
     *    ===========================
     */
    @Test
    public void testNudgeToFocusAreaWithNestedFocusableChild() {
        Rect windowBounds = new Rect(0, 0, 100, 200);
        AccessibilityWindowInfo window = new WindowBuilder()
                .setBoundsInScreen(windowBounds)
                .build();
        AccessibilityNodeInfo root = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setBoundsInScreen(windowBounds)
                .build();
        setRootNodeForWindow(root, window);

        // Currently focused view.
        AccessibilityNodeInfo sourceFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 0, 100, 100))
                .build();
        AccessibilityNodeInfo sourceView = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(sourceFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 0, 100, 100))
                .build();

        // Target view.
        AccessibilityNodeInfo targetFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 100, 100, 200))
                .build();
        // viewContainer is focusable.
        AccessibilityNodeInfo viewContainer = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(targetFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 100, 100, 200))
                .build();
        AccessibilityNodeInfo targetView = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(window)
                .setParent(viewContainer)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 100, 100, 200))
                .build();

        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        windows.add(window);

        // Nudge down from sourceView, and it should go to viewContainer.
        AccessibilityNodeInfo target
                = mNavigator.findNudgeTarget(windows, sourceView, View.FOCUS_DOWN);
        assertThat(target).isSameAs(viewContainer);
    }

    /**
     * Tests {@link Navigator#findFirstFocusDescendant} in the following node tree:
     * <pre>
     *                   root
     *                  /    \
     *                /       \
     *          focusArea1  focusArea2
     *           /   \          /   \
     *         /      \        /     \
     *     button1 button2 button3 button4
     * </pre>
     */
    @Test
    public void testFindFirstFocusDescendant() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusArea1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();
        AccessibilityNodeInfo focusArea2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();

        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button4 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setNodeList(mNodeList)
                .setParent(focusArea2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();

        int direction = View.FOCUS_FORWARD;

        // Search forward from the focus area.
        when(focusArea1.focusSearch(direction)).thenReturn(button2);
        AccessibilityNodeInfo target = mNavigator.findFirstFocusDescendant(root);
        assertThat(target).isSameAs(button2);

        // Fall back to tree traversal.
        when(focusArea1.focusSearch(direction)).thenReturn(null);
        target = mNavigator.findFirstFocusDescendant(root);
        assertThat(target).isSameAs(button1);
    }

    /**
     * Tests {@link Navigator#findFirstFocusDescendant} in the following node tree:
     * <pre>
     *                     root
     *                    /    \
     *                   /      \
     *      focusParkingView   focusArea
     *                           /    \
     *                          /      \
     *                      button1   button2
     * </pre>
     */
    @Test
    public void testFindFirstFocusDescendantWithFocusParkingView() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusParkingView = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_PARKING_VIEW_CLASS_NAME)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo focusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();

        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();

        int direction = View.FOCUS_FORWARD;

        // Search forward from the focus area.
        when(focusArea.focusSearch(direction)).thenReturn(button2);
        AccessibilityNodeInfo target = mNavigator.findFirstFocusDescendant(root);
        assertThat(target).isSameAs(button2);

        // Fall back to tree traversal.
        when(focusArea.focusSearch(direction)).thenReturn(null);
        target = mNavigator.findFirstFocusDescendant(root);
        assertThat(target).isSameAs(button1);
    }

    /**
     * Tests {@link Navigator#findScrollableContainer} in the following node tree:
     * <pre>
     *                root
     *                 |
     *                 |
     *             focusArea
     *              /     \
     *            /         \
     *        scrolling    button2
     *        container
     *           |
     *           |
     *       container
     *           |
     *           |
     *        button1
     * </pre>
     */
    @Test
    public void testFindScrollableContainer() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();
        AccessibilityNodeInfo scrollableContainer = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .setFocusable(true)
                .setContentDescription(RotaryConstants.ROTARY_VERTICALLY_SCROLLABLE)
                .build();
        AccessibilityNodeInfo container = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(scrollableContainer)
                .build();
        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .build();

        AccessibilityNodeInfo target = mNavigator.findScrollableContainer(button1);
        assertThat(target).isSameAs(scrollableContainer);
        target = mNavigator.findScrollableContainer(button2);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findPreviousFocusableDescendant} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     */
    @Test
    public void testFindPreviousFocusableDescendant() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo container1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .build();
        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo container2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .build();
        AccessibilityNodeInfo button3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button4 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();

        int direction = View.FOCUS_BACKWARD;
        when(button4.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button1);
        when(button1.focusSearch(direction)).thenReturn(null);

        AccessibilityNodeInfo target =
                Navigator.findPreviousFocusableDescendant(container2, button4);
        assertThat(target).isSameAs(button3);
        target = Navigator.findPreviousFocusableDescendant(container2, button3);
        assertThat(target).isNull();
        target = Navigator.findPreviousFocusableDescendant(container1, button2);
        assertThat(target).isSameAs(button1);
        target = Navigator.findPreviousFocusableDescendant(container1, button1);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findNextFocusableDescendant} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     */
    @Test
    public void testFindNextFocusableDescendant() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo container1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .build();
        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo container2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .build();
        AccessibilityNodeInfo button3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button4 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(button4);
        when(button4.focusSearch(direction)).thenReturn(null);

        AccessibilityNodeInfo target = mNavigator.findNextFocusableDescendant(container1, button1);
        assertThat(target).isSameAs(button2);
        target = mNavigator.findNextFocusableDescendant(container1, button2);
        assertThat(target).isNull();
        target = mNavigator.findNextFocusableDescendant(container2, button3);
        assertThat(target).isSameAs(button4);
        target = mNavigator.findNextFocusableDescendant(container2, button4);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findFirstFocusableDescendant} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     * where {@code button1} and {@code button2} are disabled.
     */
    @Test
    public void testFindFirstFocusableDescendant() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo container1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .build();
        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(false)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(false)
                .build();
        AccessibilityNodeInfo container2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .build();
        AccessibilityNodeInfo button3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button4 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();

        AccessibilityNodeInfo target = mNavigator.findFirstFocusableDescendant(root);
        assertThat(target).isSameAs(button3);
    }

    /**
     * Tests {@link Navigator#findLastFocusableDescendant} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     * where {@code button3} and {@code button4} are disabled.
     */
    @Test
    public void testFindLastFocusableDescendant() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo container1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .build();
        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo container2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .build();
        AccessibilityNodeInfo button3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(false)
                .build();
        AccessibilityNodeInfo button4 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(container2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(false)
                .build();

        AccessibilityNodeInfo target = mNavigator.findLastFocusableDescendant(root);
        assertThat(target).isSameAs(button2);
    }

    /** Sets the {@code root} node in the {@code window}'s hierarchy. */
    private void setRootNodeForWindow(@NonNull AccessibilityNodeInfo root,
            @NonNull AccessibilityWindowInfo window) {
        when(window.getRoot()).thenReturn(root);
    }
}
