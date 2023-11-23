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

package android.tools.common.flicker.extractors

import android.tools.common.Rotation
import android.tools.common.flicker.ScenarioInstance
import android.tools.common.flicker.config.FaasScenarioType
import android.tools.common.io.IReader

class EntireTraceExtractor(val type: FaasScenarioType) : IScenarioExtractor {
    override fun extract(reader: IReader): List<ScenarioInstance> {
        val layersTrace = reader.readLayersTrace() ?: error("Missing layers trace")

        return listOf(
            ScenarioInstance(
                type,
                startRotation =
                    layersTrace.entries.first().physicalDisplay?.transform?.getRotation()
                        ?: Rotation.ROTATION_0,
                endRotation = layersTrace.entries.last().physicalDisplay?.transform?.getRotation()
                        ?: Rotation.ROTATION_0,
                startTimestamp = layersTrace.entries.first().timestamp,
                endTimestamp = layersTrace.entries.last().timestamp,
                associatedCuj = null,
                associatedTransition = null,
                reader = reader
            )
        )
    }
}
