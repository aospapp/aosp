/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.platform.test.rule;

import androidx.annotation.VisibleForTesting;
import org.junit.runner.Description;

import android.os.SystemClock;
import android.util.Log;

/**
 * This rule will drop caches before running each test method.
 */
public class DropCachesRule extends TestWatcher {
    private static final String LOG_TAG = DropCachesRule.class.getSimpleName();

    @VisibleForTesting static final String KEY_DROP_CACHE = "drop-cache";
    private static boolean mDropCache = true;

    @Override
    protected void starting(Description description) {
        // Identify the filter option to use.
        mDropCache = Boolean.parseBoolean(getArguments().getString(KEY_DROP_CACHE, "true"));
        if (mDropCache == false) {
            return;
        }

        executeShellCommand("echo 3 > /proc/sys/vm/drop_caches");
        // TODO: b/117868612 to identify the root cause for additional wait.
        SystemClock.sleep(3000);
    }
}
