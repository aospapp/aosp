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

package com.android.devicelockcontroller.policy;

import androidx.annotation.IntDef;

import com.android.devicelockcontroller.policy.DeviceStateController.DeviceState;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Interface used for setting policies for a given state.
 */
public interface PolicyHandler {
    /** Result Type for operation */
    @Target(ElementType.TYPE_USE)
    @IntDef(value = {SUCCESS, FAILURE})
    @Retention(RetentionPolicy.SOURCE)
    @interface ResultType {
    }

    int SUCCESS = 0;
    int FAILURE = 1;

    /**
     * Sets the policy state based on the new state. Throws SecurityException when the app is not
     * privileged.
     */
    @ResultType
    ListenableFuture<@ResultType Integer> setPolicyForState(@DeviceState int state);
}
