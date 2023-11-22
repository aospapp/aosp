/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.cts.device.graphicsstats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Used by GraphicsStatsTest.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class SimpleDrawFrameTests {
    private static final String TAG = "GraphicsStatsDeviceTest";

    @Rule
    public ActivityTestRule<DrawFramesActivity> mActivityRule =
            new ActivityTestRule<>(DrawFramesActivity.class);

    @Test
    public void testDrawTenFrames() throws Throwable {
        DrawFramesActivity activity = mActivityRule.getActivity();
        activity.waitForReady();
        int initialFrames = activity.getRenderedFramesCount();
        assertTrue(initialFrames < 5);
        assertEquals(0, activity.getDroppedReportsCount());
        activity.drawFrames(10);
        assertEquals(initialFrames + 10, activity.getRenderedFramesCount());
        assertEquals(0, activity.getDroppedReportsCount());
    }

    @Test
    public void testDrawJankyFrames() throws Throwable {
        DrawFramesActivity activity = mActivityRule.getActivity();
        activity.waitForReady();
        int initialFrames = activity.getRenderedFramesCount();
        assertTrue(initialFrames < 5);
        assertEquals(0, activity.getDroppedReportsCount());
        int[] frames = new int[50];
        for (int i = 0; i < 10; i++) {
            int indx = i * 5;
            frames[indx] = DrawFramesActivity.FRAME_JANK_RECORD_DRAW;
            frames[indx + 1] = DrawFramesActivity.FRAME_JANK_ANIMATION;
            frames[indx + 2] = DrawFramesActivity.FRAME_JANK_LAYOUT;
            frames[indx + 3] = DrawFramesActivity.FRAME_JANK_MISS_VSYNC;
        }
        activity.drawFrames(frames);
        assertEquals(initialFrames + 50, activity.getRenderedFramesCount());
        assertEquals(0, activity.getDroppedReportsCount());
    }

    @Test
    public void testDrawDaveyFrames() throws Throwable {
        DrawFramesActivity activity = mActivityRule.getActivity();
        activity.waitForReady();
        int initialFrames = activity.getRenderedFramesCount();
        assertTrue(initialFrames < 5);
        assertEquals(0, activity.getDroppedReportsCount());
        int[] frames = new int[40];
        for (int i = 0; i < 10; i++) {
            int indx = i * 4;
            frames[indx] = DrawFramesActivity.FRAME_JANK_DAVEY;
            frames[indx + 2] = DrawFramesActivity.FRAME_JANK_DAVEY_JR;
        }
        activity.drawFrames(frames);
        assertEquals(initialFrames + 40, activity.getRenderedFramesCount());
        assertEquals(0, activity.getDroppedReportsCount());
    }
}
