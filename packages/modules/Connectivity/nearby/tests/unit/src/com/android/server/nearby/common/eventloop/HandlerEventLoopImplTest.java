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

package com.android.server.nearby.common.eventloop;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SdkSuppress;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.ArrayList;
import java.util.List;

public class HandlerEventLoopImplTest {
    private static final String TAG = "HandlerEventLoopImplTest";
    private final HandlerEventLoopImpl mHandlerEventLoopImpl =
            new HandlerEventLoopImpl(TAG);
    private final List<Integer> mExecutedRunnables = new ArrayList<>();
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void remove() {
        mHandlerEventLoopImpl.postRunnable(new NumberedRunnable(0));
        NumberedRunnable runnableToAddAndRemove = new NumberedRunnable(1);
        mHandlerEventLoopImpl.postRunnable(runnableToAddAndRemove);
        mHandlerEventLoopImpl.removeRunnable(runnableToAddAndRemove);
        mHandlerEventLoopImpl.postRunnable(new NumberedRunnable(2));
        assertThat(mExecutedRunnables).doesNotContain(1);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void isPosted() {
        NumberedRunnable runnable = new HandlerEventLoopImplTest.NumberedRunnable(0);
        mHandlerEventLoopImpl.postRunnableDelayed(runnable, 10 * 1000L);
        assertThat(mHandlerEventLoopImpl.isPosted(runnable)).isTrue();
        mHandlerEventLoopImpl.removeRunnable(runnable);
        assertThat(mHandlerEventLoopImpl.isPosted(runnable)).isFalse();

        // Let a runnable execute, then verify that it's not posted.
        mHandlerEventLoopImpl.postRunnable(runnable);
        assertThat(mHandlerEventLoopImpl.isPosted(runnable)).isTrue();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void postAndWaitAfterDestroy() throws InterruptedException {
        mHandlerEventLoopImpl.destroy();
        mHandlerEventLoopImpl.postAndWait(new HandlerEventLoopImplTest.NumberedRunnable(0));
        assertThat(mExecutedRunnables).isEmpty();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void postEmptyQueueRunnable() {
        mHandlerEventLoopImpl.postEmptyQueueRunnable(
                new HandlerEventLoopImplTest.NumberedRunnable(0));
    }

    private class NumberedRunnable extends NamedRunnable {
        private final int mId;

        private NumberedRunnable(int id) {
            super("NumberedRunnable:" + id);
            this.mId = id;
        }

        @Override
        public void run() {
            // Note: when running in robolectric, this is not actually executed on a different
            // thread, it's executed in the same thread the test runs in, so this is safe.
            mExecutedRunnables.add(mId);
        }
    }
}
