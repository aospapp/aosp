/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.connecteddevice.util;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Class for invoking thread-safe callbacks.
 *
 * @param <T> Callback type.
 */
public class ThreadSafeCallbacks<T> {

    private final ConcurrentHashMap<T, Executor> mCallbacks = new ConcurrentHashMap<>();

    /** Add a callback to be notified on its executor. */
    public void add(@NonNull T callback, @NonNull @CallbackExecutor Executor executor) {
        mCallbacks.put(callback, executor);
    }

    /** Remove a callback from the collection. */
    public void remove(@NonNull T callback) {
        mCallbacks.remove(callback);
    }

    /** Clear all callbacks from the collection. */
    public void clear() {
        mCallbacks.clear();
    }

    /** Return the number of callbacks in collection. */
    public int size() {
        return mCallbacks.size();
    }

    /** Invoke notification on all callbacks with their supplied {@link Executor}. */
    public void invoke(Consumer<T> notification) {
        mCallbacks.forEach((callback, executor) ->
                executor.execute(() -> notification.accept(callback)));
    }
}
