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

package android.tools.common.io

import android.tools.CleanFlickerEnvironmentRule
import android.tools.common.Tag
import com.google.common.truth.Truth
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** Tests for [ResultArtifactDescriptor] */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ResultArtifactDescriptorTest {
    @Test
    fun generateDescriptorFromTrace() {
        createDescriptorAndValidateFileName(TraceType.SF)
        createDescriptorAndValidateFileName(TraceType.WM)
        createDescriptorAndValidateFileName(TraceType.TRANSACTION)
        createDescriptorAndValidateFileName(TraceType.TRANSACTION)
        createDescriptorAndValidateFileName(TraceType.SCREEN_RECORDING)
        createDescriptorAndValidateFileName(TraceType.WM_DUMP)
        createDescriptorAndValidateFileName(TraceType.SF_DUMP)
    }

    @Test
    fun generateDescriptorFromTraceWithTags() {
        createDescriptorAndValidateFileNameWithTag(TraceType.SF)
        createDescriptorAndValidateFileNameWithTag(TraceType.WM)
        createDescriptorAndValidateFileNameWithTag(TraceType.TRANSACTION)
        createDescriptorAndValidateFileNameWithTag(TraceType.TRANSACTION)
        createDescriptorAndValidateFileNameWithTag(TraceType.SCREEN_RECORDING)
        createDescriptorAndValidateFileNameWithTag(TraceType.WM_DUMP)
        createDescriptorAndValidateFileNameWithTag(TraceType.SF_DUMP)
    }

    @Test
    fun parseDescriptorFromFileName() {
        parseDescriptorAndValidateType(TraceType.SF.fileName, TraceType.SF)
        parseDescriptorAndValidateType(TraceType.WM.fileName, TraceType.WM)
        parseDescriptorAndValidateType(TraceType.TRANSACTION.fileName, TraceType.TRANSACTION)
        parseDescriptorAndValidateType(TraceType.TRANSACTION.fileName, TraceType.TRANSACTION)
        parseDescriptorAndValidateType(
            TraceType.SCREEN_RECORDING.fileName,
            TraceType.SCREEN_RECORDING
        )
        parseDescriptorAndValidateType(TraceType.WM_DUMP.fileName, TraceType.WM_DUMP)
        parseDescriptorAndValidateType(TraceType.SF_DUMP.fileName, TraceType.SF_DUMP)
    }

    @Test
    fun parseDescriptorFromFileNameWithTags() {
        parseDescriptorAndValidateType(buildTaggedName(TraceType.SF), TraceType.SF, TEST_TAG)
        parseDescriptorAndValidateType(buildTaggedName(TraceType.WM), TraceType.WM, TEST_TAG)
        parseDescriptorAndValidateType(
            buildTaggedName(TraceType.TRANSACTION),
            TraceType.TRANSACTION,
            TEST_TAG
        )
        parseDescriptorAndValidateType(
            buildTaggedName(TraceType.TRANSACTION),
            TraceType.TRANSACTION,
            TEST_TAG
        )
        parseDescriptorAndValidateType(
            buildTaggedName(TraceType.SCREEN_RECORDING),
            TraceType.SCREEN_RECORDING,
            TEST_TAG
        )
        parseDescriptorAndValidateType(
            buildTaggedName(TraceType.WM_DUMP),
            TraceType.WM_DUMP,
            TEST_TAG
        )
        parseDescriptorAndValidateType(
            buildTaggedName(TraceType.SF_DUMP),
            TraceType.SF_DUMP,
            TEST_TAG
        )
    }

    private fun buildTaggedName(traceType: TraceType): String =
        ResultArtifactDescriptor(traceType, TEST_TAG).fileNameInArtifact

    private fun parseDescriptorAndValidateType(
        fileNameInArtifact: String,
        expectedTraceType: TraceType,
        expectedTag: String = Tag.ALL
    ): ResultArtifactDescriptor {
        val descriptor = ResultArtifactDescriptor.fromFileName(fileNameInArtifact)
        Truth.assertWithMessage("Descriptor type")
            .that(descriptor.traceType)
            .isEqualTo(expectedTraceType)
        Truth.assertWithMessage("Descriptor tag").that(descriptor.tag).isEqualTo(expectedTag)
        return descriptor
    }

    private fun createDescriptorAndValidateFileName(traceType: TraceType) {
        val descriptor = ResultArtifactDescriptor(traceType)
        Truth.assertWithMessage("Result file name")
            .that(descriptor.fileNameInArtifact)
            .isEqualTo(traceType.fileName)
    }

    private fun createDescriptorAndValidateFileNameWithTag(traceType: TraceType) {
        val tag = "testTag"
        val descriptor = ResultArtifactDescriptor(traceType, TEST_TAG)
        val subject =
            Truth.assertWithMessage("Result file name").that(descriptor.fileNameInArtifact)
        subject.startsWith(tag)
        subject.endsWith(traceType.fileName)
    }

    companion object {
        private const val TEST_TAG = "testTag"

        @ClassRule @JvmField val cleanFlickerEnvironmentRule = CleanFlickerEnvironmentRule()
    }
}
