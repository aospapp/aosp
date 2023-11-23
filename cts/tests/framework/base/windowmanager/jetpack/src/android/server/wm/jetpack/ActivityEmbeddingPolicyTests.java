/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.server.wm.jetpack.second.Components.SECOND_UNTRUSTED_EMBEDDING_ACTIVITY;
import static android.server.wm.jetpack.signed.Components.SIGNED_EMBEDDING_ACTIVITY;
import static android.server.wm.jetpack.utils.ActivityEmbeddingUtil.createWildcardSplitPairRule;
import static android.server.wm.jetpack.utils.ExtensionUtil.assumeExtensionSupportedDevice;
import static android.server.wm.jetpack.utils.ExtensionUtil.getWindowExtensions;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.EXTRA_EMBED_ACTIVITY;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.startActivityFromActivity;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.startActivityNewTask;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.startActivityOnDisplaySingleTop;
import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNotNull;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.os.Bundle;
import android.server.wm.ActivityManagerTestBase;
import android.server.wm.Condition;
import android.server.wm.NestedShellPermission;
import android.server.wm.WindowManagerState;
import android.server.wm.jetpack.utils.TestActivityKnownEmbeddingCerts;
import android.server.wm.jetpack.utils.TestConfigChangeHandlingActivity;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.window.extensions.WindowExtensions;
import androidx.window.extensions.embedding.ActivityEmbeddingComponent;
import androidx.window.extensions.embedding.SplitPairRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.function.BooleanSupplier;

/**
 * Tests for the {@link androidx.window.extensions} implementation provided on the device (and only
 * if one is available) for the Activity Embedding functionality. Specifically tests security
 * policies that should be applied by the system.
 *
 * Build/Install/Run:
 *     atest CtsWindowManagerJetpackTestCases:ActivityEmbeddingPolicyTests
 */
@RunWith(AndroidJUnit4.class)
public class ActivityEmbeddingPolicyTests extends ActivityManagerTestBase {
    protected ActivityEmbeddingComponent mActivityEmbeddingComponent;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        // TODO(b/207070762): remove the assumption after shell transition enabled.
        assumeFalse(ENABLE_SHELL_TRANSITIONS);
        assumeExtensionSupportedDevice();
        WindowExtensions windowExtensions = getWindowExtensions();
        assumeNotNull(windowExtensions);
        mActivityEmbeddingComponent = windowExtensions.getActivityEmbeddingComponent();
        assumeNotNull(mActivityEmbeddingComponent);
    }

    @After
    public void tearDown() {
        ActivityManager am = mContext.getSystemService(ActivityManager.class);
        NestedShellPermission.run(() -> am.forceStopPackage("android.server.wm.jetpack.second"));
        NestedShellPermission.run(() -> am.forceStopPackage("android.server.wm.jetpack.signed"));
    }

    /**
     * Verifies that all input is dropped for activities that are embedded and being animated with
     * untrusted embedding.
     */
    @Test
    public void testInputDuringAnimationIsNotAllowed_untrustedEmbedding() {
        Activity primaryActivity = startActivityNewTask(mContext, mInstrumentation,
                TestConfigChangeHandlingActivity.class, null /* activityId */);

        SplitPairRule splitPairRule = createWildcardSplitPairRule(true /* shouldClearTop */);
        mActivityEmbeddingComponent.setEmbeddingRules(Collections.singleton(splitPairRule));

        // Extend the animation scale, so that the test has enough time to catch the state during
        // transition.
        UiAutomation automation = mInstrumentation.getUiAutomation();
        automation.setAnimationScale(2f);

        try {
            startActivityFromActivity(primaryActivity, SECOND_UNTRUSTED_EMBEDDING_ACTIVITY,
                    "initialSecondaryActivity", Bundle.EMPTY);

            // Verify that the embedded activity drops input during animation
            mWmState.waitForAppTransitionRunningOnDisplay(primaryActivity.getDisplayId());
            waitForOrFailWithRapidRetry(
                    "Embedded activity must drop all input for the duration of animation",
                    () -> {
                        mWmState.computeState();
                        return mWmState.getActivity(SECOND_UNTRUSTED_EMBEDDING_ACTIVITY)
                                .getLastDropInputMode() == 1 /* DropInputMode.ALL */;
                    });

            // Verify that the embedded activity drops input if obscured after animation
            mWmState.waitForAppTransitionIdleOnDisplay(primaryActivity.getDisplayId());
            assertEquals(
                    "Embedded activity must drop input if obscured in untrusted embedding",
                    2 /* DropInputMode.OBSCURED */,
                    mWmState.getActivity(
                            SECOND_UNTRUSTED_EMBEDDING_ACTIVITY).getLastDropInputMode());
        } finally {
            automation.setAnimationScale(1f);
        }
    }

    /**
     * Verifies that all input is dropped for activities that are embedded and being animated with
     * trusted embedding.
     */
    @Test
    public void testInputDuringAnimationIsNotAllowed_trustedEmbedding() {
        // Extend the animation scale, so that the test has enough time to catch the state during
        // transition.
        UiAutomation automation = mInstrumentation.getUiAutomation();
        automation.setAnimationScale(2f);

        try {
            // Start an activity that will attempt to embed TestActivityKnownEmbeddingCerts
            startActivityOnDisplaySingleTop(mContext, DEFAULT_DISPLAY, SIGNED_EMBEDDING_ACTIVITY,
                    Bundle.EMPTY);
            mWmState.waitForActivityState(SIGNED_EMBEDDING_ACTIVITY,
                    WindowManagerState.STATE_RESUMED);
            mWmState.waitForAppTransitionIdleOnDisplay(DEFAULT_DISPLAY);

            Bundle embedExtra = new Bundle();
            embedExtra.putBoolean(EXTRA_EMBED_ACTIVITY, true);
            startActivityOnDisplaySingleTop(mContext, DEFAULT_DISPLAY, SIGNED_EMBEDDING_ACTIVITY,
                    embedExtra);

            // Verify that the embedded activity drops input during animation
            final ComponentName embeddedActivityComponent = new ComponentName(mContext,
                    TestActivityKnownEmbeddingCerts.class);
            mWmState.waitForAppTransitionRunningOnDisplay(DEFAULT_DISPLAY);
            waitForOrFailWithRapidRetry(
                    "Embedded activity must drop all input for the duration of animation",
                    () -> {
                        mWmState.computeState();
                        return mWmState.getActivity(embeddedActivityComponent)
                                .getLastDropInputMode() == 1 /* DropInputMode.ALL */;
                    });

            // Verify that the embedded activity drops input if obscured after animation
            mWmState.waitForAppTransitionIdleOnDisplay(DEFAULT_DISPLAY);
            assertEquals(
                    "Embedded activity must not drop input if obscured in trusted embedding",
                    0 /* DropInputMode.NONE */,
                    mWmState.getActivity(embeddedActivityComponent).getLastDropInputMode());
        } finally {
            automation.setAnimationScale(1f);
        }
    }

    static void waitForOrFailWithRapidRetry(@NonNull String message,
            @NonNull BooleanSupplier condition) {
        Condition.waitFor(new Condition<>(message, condition)
                .setRetryIntervalMs(50)
                .setRetryLimit(100)
                .setOnFailure(unusedResult -> fail("FAILED because unsatisfied: " + message)));
    }
}
