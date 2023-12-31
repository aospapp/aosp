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

package android.view.cts;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class InputEventInterceptTestActivity extends Activity {
    final BlockingQueue<KeyEvent> mKeyEvents = new LinkedBlockingQueue<>();
    final BlockingQueue<MotionEvent> mMotionEvents = new LinkedBlockingQueue<>();

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        mKeyEvents.add(event);
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        mMotionEvents.add(MotionEvent.obtain(event));
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        mMotionEvents.add(MotionEvent.obtain(event));
        return true;
    }
}
