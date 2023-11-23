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

package android.car.app;

import android.content.ComponentName;

/** @hide */
interface ICarActivityService {
    /**
     * Designates the given {@code activity} to be launched in {@code TaskDisplayArea} of
     * {@code featureId} in the display of {@code displayId}.
     */
    int setPersistentActivity(in ComponentName activity, int displayId, int featureId) = 0;
}

