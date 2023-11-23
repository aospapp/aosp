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

package android.tools.common.traces.surfaceflinger

import kotlin.js.JsExport

@JsExport
enum class HwcCompositionType(val value: Int) {
    INVALID(0),
    CLIENT(1),
    DEVICE(2),
    SOLID_COLOR(3),
    CURSOR(4),
    SIDEBAND(5),
    UNRECOGNIZED(-1)
}
