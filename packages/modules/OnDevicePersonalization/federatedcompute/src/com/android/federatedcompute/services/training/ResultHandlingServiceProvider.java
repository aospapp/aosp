/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.federatedcompute.services.training;

import android.annotation.Nullable;
import android.content.Intent;
import android.federatedcompute.aidl.IResultHandlingService;

/** Interface used to provide a reference to the IResultHandlingService. */
public interface ResultHandlingServiceProvider {

    /** Returns the connected ResultHandlingService, or otherwise {@code null}. */
    @Nullable
    IResultHandlingService getResultHandlingService();

    /** Bind to and establish a connection with client implemented ResultHandlingService. */
    boolean bindService(Intent intent);

    /** Unbind from the client implemented ResultHandlingService. */
    void unbindService();
}
