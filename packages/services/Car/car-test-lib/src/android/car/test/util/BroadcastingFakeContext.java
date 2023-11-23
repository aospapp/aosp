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

package android.car.test.util;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.test.mock.MockContext;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * A fake implementation for {@link android.content.Context}, that helps broadcast {@link Intent}s
 * to registered {@link BroadcastReceiver} instances.
 */
// TODO(b/202420937): Add unit tests for this class.
public final class BroadcastingFakeContext extends MockContext {
    private BroadcastReceiver mReceiver;
    private IntentFilter mIntentFilter;
    private Handler mHandler;

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        mReceiver = receiver;
        mIntentFilter = filter;

        return null;
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        mReceiver = receiver;
        mIntentFilter = filter;
        mHandler = scheduler;

        return null;
    }

    @Override
    public void sendBroadcast(Intent intent) {
        if (mHandler == null) {
            mReceiver.onReceive(this, intent);
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        mHandler.getLooper().getQueue().addIdleHandler(() -> {
            latch.countDown();
            return false;
        });

        mHandler.post(() -> mReceiver.onReceive(this, intent));

        // wait until the queue is idle
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for Broadcast Intent to be received");
        }
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        if (receiver == mReceiver) {
            mReceiver = null;
            mIntentFilter = null;
            mHandler = null;
        }
    }

    public void verifyReceiverNotRegistered() {
        assertThat(mIntentFilter).isNull();
        assertThat(mReceiver).isNull();
        assertThat(mHandler).isNull();
    }

    public void verifyReceiverRegistered(String expectedAction) {
        assertThat(mIntentFilter.actionsIterator()).isNotNull();
        ArrayList<String> actions = Lists.newArrayList(mIntentFilter.actionsIterator());
        assertWithMessage("IntentFilter actions").that(actions).contains(expectedAction);
        assertWithMessage("Registered BroadcastReceiver").that(mReceiver).isNotNull();
    }
}
