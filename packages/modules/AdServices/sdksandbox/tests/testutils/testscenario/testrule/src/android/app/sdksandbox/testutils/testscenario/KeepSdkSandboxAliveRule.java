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

package android.app.sdksandbox.testutils.testscenario;

import android.app.sdksandbox.SdkSandboxManager;
import android.app.sdksandbox.testutils.FakeLoadSdkCallback;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * This rule is meant to be used as a {@link org.junit.ClassRule} for test suites where we intend to
 * keep the sandbox process alive even when all other test sdks are unloaded. This rule loads a
 * given empty sdk into a sandbox and unloads it after all tests have finished executing.
 */
public class KeepSdkSandboxAliveRule implements TestRule {

    private static final String TAG = KeepSdkSandboxAliveRule.class.getName();
    private final String mSdkName;
    private SdkSandboxManager mSdkSandboxManager;

    public KeepSdkSandboxAliveRule(String sdkName) {
        mSdkName = sdkName;
    }

    @Override
    public Statement apply(final Statement base, final Description description) {
        // This statement would wrap around a test suite, similar to @BeforeClass and @AfterClass
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try (ActivityScenario scenario =
                        ActivityScenario.launch(SdkSandboxCtsActivity.class)) {
                    final Context context =
                            InstrumentationRegistry.getInstrumentation().getContext();
                    mSdkSandboxManager = context.getSystemService(SdkSandboxManager.class);
                    final FakeLoadSdkCallback callback = new FakeLoadSdkCallback();
                    mSdkSandboxManager.loadSdk(mSdkName, new Bundle(), Runnable::run, callback);
                    try {
                        callback.assertLoadSdkIsSuccessful();
                    } catch (Exception e) {
                        if (callback.getLoadSdkErrorCode()
                                == SdkSandboxManager.LOAD_SDK_SDK_SANDBOX_DISABLED) {
                            // TODO: Change this Log to an ExecutionCondition in Junit5 so that test
                            // suite is skipped if condition is not met
                            Log.w(TAG, "Sdk Sandbox is disabled");
                        } else {
                            throw e;
                        }
                    }
                }
                try {
                    base.evaluate();
                } finally {
                    try (ActivityScenario scenario =
                            ActivityScenario.launch(SdkSandboxCtsActivity.class)) {
                        mSdkSandboxManager.unloadSdk(mSdkName);
                    }
                }
            }
        };
    }
}
