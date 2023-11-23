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

package com.android.server.nearby.util;

import android.os.SystemClock;

/** Default implementation of Clock. Instances of this class handle time operations. */
public class DefaultClock implements Clock {

    private static final DefaultClock sInstance = new DefaultClock();

    /** Returns an instance of DefaultClock. */
    public static Clock getsInstance() {
        return sInstance;
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long elapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public long currentThreadTimeMillis() {
        return SystemClock.currentThreadTimeMillis();
    }

    public DefaultClock() {}
}
