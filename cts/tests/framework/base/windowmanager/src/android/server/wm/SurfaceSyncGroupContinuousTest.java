/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.server.wm;

import static android.server.wm.WindowManagerState.getLogicalDisplaySize;

import android.platform.test.annotations.Presubmit;
import android.view.cts.surfacevalidator.CapturedActivity;

import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class SurfaceSyncGroupContinuousTest {
    @Rule
    public TestName mName = new TestName();

    @Rule
    public ActivityTestRule<CapturedActivity> mActivityRule =
            new ActivityTestRule<>(CapturedActivity.class);

    public CapturedActivity mCapturedActivity;

    @Before
    public void setup() {
        mCapturedActivity = mActivityRule.getActivity();
        mCapturedActivity.setLogicalDisplaySize(getLogicalDisplaySize());
    }

    @Test
    public void testSurfaceControlViewHostIPCSync_Fast() throws Throwable {
        mCapturedActivity.verifyTest(
                new SyncValidatorSCVHTestCase(0 /* delayMs */, false /* overrideDefaultDuration */,
                        false /* inProcess */), mName);
    }

    @Test
    public void testSurfaceControlViewHostIPCSync_Slow() throws Throwable {
        mCapturedActivity.verifyTest(new SyncValidatorSCVHTestCase(100 /* delayMs */,
                false /* overrideDefaultDuration */, false /* inProcess */), mName);
    }

    @Test
    @Presubmit
    public void testSurfaceControlViewHostIPCSync_Short() throws Throwable {
        mCapturedActivity.setMinimumCaptureDurationMs(5000);
        mCapturedActivity.verifyTest(
                new SyncValidatorSCVHTestCase(0 /* delayMs */, true /* overrideDefaultDuration */,
                        false /* inProcess */), mName);
    }

    @Test
    @Presubmit
    public void testSurfaceControlViewHostSyncInProcess() throws Throwable {
        mCapturedActivity.setMinimumCaptureDurationMs(5000);
        mCapturedActivity.verifyTest(new SyncValidatorSCVHTestCase(0 /* delayMs */,
                true /* overrideDefaultDuration */, true /* inProcess */), mName);
    }
}
