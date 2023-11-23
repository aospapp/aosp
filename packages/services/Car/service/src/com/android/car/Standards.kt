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

package com.android.car

/**
 * Identical to [also]. Use to communicate intent of guarding against null. Compatible with
 * `?:` (elvis operator) if else block is needed.
 *
 * Example:
 * ```
 * array.firstOrNull()?.guard { Log.d(TAG, "$it is guaranteed to not be null here") }
 * ```
 *
 */
internal inline fun <T> T.guard(block: (T) -> Unit): T {
    return this.also(block)
}