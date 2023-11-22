/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.cts.intent.sender;

import android.app.Activity;
import android.content.Intent;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

public class IntentSenderActivity extends Activity {

    private final SynchronousQueue<Result> mResult = new SynchronousQueue<>();

    public static class Result {
        public final int resultCode;
        public final Intent data;

        public Result(int resultCode, Intent data) {
            this.resultCode = resultCode;
            this.data = data;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            try {
                mResult.offer(new Result(resultCode, data), 5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public Intent getResult(Intent intent) throws Exception {
        startActivityForResult(intent, 42);
        final Result result = mResult.poll(30, TimeUnit.SECONDS);
        return (result != null) ? result.data : null;
    }
}
