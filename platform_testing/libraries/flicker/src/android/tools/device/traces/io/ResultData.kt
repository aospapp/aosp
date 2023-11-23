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

package android.tools.device.traces.io

import android.tools.common.Timestamp
import android.tools.common.io.IArtifact
import android.tools.common.io.RunStatus
import android.tools.common.io.TransitionTimeRange

/**
 * Contents of a flicker run (e.g. files, status, event log)
 *
 * @param _artifact Path to the artifact file
 * @param _transitionTimeRange Transition start and end time
 * @param _executionError Transition execution error (if any)
 */
open class ResultData(
    _artifact: IArtifact,
    _transitionTimeRange: TransitionTimeRange,
    _executionError: Throwable?
) : IResultData {
    final override val artifact: IArtifact = _artifact
    final override val transitionTimeRange: TransitionTimeRange = _transitionTimeRange
    final override val executionError: Throwable? = _executionError
    final override val runStatus: RunStatus
        get() = artifact.runStatus

    /** {@inheritDoc} */
    override fun slice(startTimestamp: Timestamp, endTimestamp: Timestamp) = apply {
        require(startTimestamp.hasAllTimestamps)
        require(endTimestamp.hasAllTimestamps)
        return ResultData(
            artifact,
            TransitionTimeRange(startTimestamp, endTimestamp),
            executionError
        )
    }

    override fun toString(): String = buildString {
        append(artifact)
        append(" (status=")
        append(runStatus)
        executionError?.let {
            append(", error=")
            append(it.message)
        }
        append(") ")
    }

    /** {@inheritDoc} */
    override fun updateStatus(newStatus: RunStatus) = apply { artifact.updateStatus(newStatus) }
}
