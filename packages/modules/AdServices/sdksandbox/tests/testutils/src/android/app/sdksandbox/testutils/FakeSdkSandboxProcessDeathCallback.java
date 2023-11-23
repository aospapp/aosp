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

package android.app.sdksandbox.testutils;

import android.app.sdksandbox.SdkSandboxManager;

import java.util.concurrent.atomic.AtomicInteger;

public class FakeSdkSandboxProcessDeathCallback
        implements SdkSandboxManager.SdkSandboxProcessDeathCallback {
    private final AtomicInteger mSandboxDeathCount = new AtomicInteger(0);

    @Override
    public void onSdkSandboxDied() {
        mSandboxDeathCount.incrementAndGet();
    }

    public int getSdkSandboxDeathCount() throws InterruptedException {
        // Wait 5 seconds to determine whether sandbox death is ever detected.
        Thread.sleep(5000);
        return mSandboxDeathCount.get();
    }
}
