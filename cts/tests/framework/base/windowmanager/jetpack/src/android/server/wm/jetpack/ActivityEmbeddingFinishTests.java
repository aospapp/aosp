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

package android.server.wm.jetpack;


import static android.server.wm.jetpack.utils.ActivityEmbeddingUtil.createWildcardSplitPairRule;
import static android.server.wm.jetpack.utils.ActivityEmbeddingUtil.startActivityAndVerifySplit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.server.wm.jetpack.utils.ActivityEmbeddingTestBase;
import android.server.wm.jetpack.utils.TestActivity;
import android.server.wm.jetpack.utils.TestActivityWithId;
import android.server.wm.jetpack.utils.TestConfigChangeHandlingActivity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.window.extensions.embedding.SplitInfo;
import androidx.window.extensions.embedding.SplitPairRule;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

/**
 * Tests for the {@link androidx.window.extensions} implementation provided on the device (and only
 * if one is available) for the Activity Embedding functionality. Specifically tests activity
 * finish scenarios.
 *
 * Build/Install/Run:
 *     atest CtsWindowManagerJetpackTestCases:ActivityEmbeddingFinishTests
 */

@RunWith(AndroidJUnit4.class)
public class ActivityEmbeddingFinishTests extends ActivityEmbeddingTestBase {

    /**
     * Tests that finishing the primary activity results in the secondary activity resizing to fill
     * the task.
     */
    @Test
    public void testFinishPrimary() throws InterruptedException {
        SplitPairRule splitPairRule = createWildcardSplitPairRule();
        mActivityEmbeddingComponent.setEmbeddingRules(Collections.singleton(splitPairRule));

        Activity primaryActivity = startActivityNewTask(TestConfigChangeHandlingActivity.class);
        TestActivity secondaryActivity = (TestActivity) startActivityAndVerifySplit(primaryActivity,
                TestActivityWithId.class, splitPairRule, "secondaryActivity", mSplitInfoConsumer);

        // Finishing the primary activity should cause the secondary activity to resize to fill the
        // task.
        primaryActivity.finish();
        assertTrue(secondaryActivity.waitForBoundsChange());
        assertEquals(getMaximumActivityBounds(secondaryActivity),
                getActivityBounds(secondaryActivity));

        // Verify that there are no split states
        List<SplitInfo> splitInfoList = mSplitInfoConsumer.waitAndGet();
        assertTrue(splitInfoList.isEmpty());
    }

    /**
     * Tests that finishing the secondary activity results in the primary activity resizing to fill
     * the task.
     */
    @Test
    public void testFinishSecondary() throws InterruptedException {
        SplitPairRule splitPairRule = createWildcardSplitPairRule();
        mActivityEmbeddingComponent.setEmbeddingRules(Collections.singleton(splitPairRule));

        TestActivity primaryActivity = (TestActivityWithId)
                startActivityNewTask(TestActivityWithId.class);
        TestActivity secondaryActivity = (TestActivity) startActivityAndVerifySplit(primaryActivity,
                TestActivityWithId.class, splitPairRule, "secondaryActivity", mSplitInfoConsumer);

        // Need to reset primary activity bounds change counter because entering the split already
        // triggered a bounds change.
        primaryActivity.resetBoundsChangeCounter();

        // Finishing the secondary activity should cause the primary activity to resize to fill the
        // task.
        secondaryActivity.finish();
        assertTrue(primaryActivity.waitForBoundsChange());
        assertEquals(getMaximumActivityBounds(primaryActivity),
                getActivityBounds(primaryActivity));

        // Verify that there are no split states
        List<SplitInfo> splitInfoList = mSplitInfoConsumer.waitAndGet();
        assertTrue(splitInfoList.isEmpty());
    }
}
