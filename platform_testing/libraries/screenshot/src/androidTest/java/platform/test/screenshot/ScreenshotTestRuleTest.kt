/*
 * Copyright 2022 The Android Open Source Project
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

package platform.test.screenshot

import android.content.Context
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileInputStream
import java.lang.AssertionError
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.screenshot.OutputFileType.IMAGE_ACTUAL
import platform.test.screenshot.OutputFileType.IMAGE_DIFF
import platform.test.screenshot.OutputFileType.IMAGE_EXPECTED
import platform.test.screenshot.OutputFileType.RESULT_BIN_PROTO
import platform.test.screenshot.OutputFileType.RESULT_PROTO
import platform.test.screenshot.matchers.MSSIMMatcher
import platform.test.screenshot.matchers.PixelPerfectMatcher
import platform.test.screenshot.proto.ScreenshotResultProto
import platform.test.screenshot.utils.loadBitmap

class CustomGoldenImagePathManager(
    appcontext: Context,
    assetsPath: String = "assets"
) : GoldenImagePathManager(appcontext, assetsPath) {
    public override fun goldenIdentifierResolver(testName: String): String = "$testName.png"
}

@RunWith(AndroidJUnit4::class)
@MediumTest
class ScreenshotTestRuleTest {

    val customizedAssetsPath = "libraries/screenshot/src/androidTest/assets"

    @get:Rule
    val rule = ScreenshotTestRule(
        CustomGoldenImagePathManager(InstrumentationRegistry.getInstrumentation().getContext()))

    @get:Rule
    val customizedRule = ScreenshotTestRule(
        CustomGoldenImagePathManager(
            InstrumentationRegistry.getInstrumentation().getContext(),
            customizedAssetsPath
        )
    )

    @Test
    fun performDiff_sameBitmaps() {
        val first = loadBitmap("round_rect_gray")

        first
            .assertAgainstGolden(rule, "round_rect_gray", matcher = PixelPerfectMatcher())

        val resultProto = rule.getPathOnDeviceFor(RESULT_PROTO)
        val fileResultBinProto = rule.getPathOnDeviceFor(RESULT_BIN_PROTO)
        var diffProto = ScreenshotResultProto.DiffResult.newBuilder()
        diffProto.mergeFrom(FileInputStream(fileResultBinProto))

        assertThat(resultProto.readText()).contains("PASS")
        assertThat(rule.getPathOnDeviceFor(IMAGE_ACTUAL).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(IMAGE_DIFF).exists()).isFalse()
        assertThat(rule.getPathOnDeviceFor(IMAGE_EXPECTED).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(RESULT_BIN_PROTO).exists()).isTrue()
        assertThat(diffProto.build().imageLocationGolden.startsWith("assets")).isTrue()
    }

    @Test
    fun performDiff_sameBitmaps_customizedAssetsPath() {
        val first = loadBitmap("round_rect_gray")

        first
            .assertAgainstGolden(customizedRule, "round_rect_gray", matcher = PixelPerfectMatcher())

        val resultProto = customizedRule.getPathOnDeviceFor(RESULT_PROTO)
        val fileResultBinProto = customizedRule.getPathOnDeviceFor(RESULT_BIN_PROTO)
        var diffProto = ScreenshotResultProto.DiffResult.newBuilder()
        diffProto.mergeFrom(FileInputStream(fileResultBinProto))

        assertThat(resultProto.readText()).contains("PASS")
        assertThat(customizedRule.getPathOnDeviceFor(IMAGE_ACTUAL).exists()).isTrue()
        assertThat(customizedRule.getPathOnDeviceFor(IMAGE_DIFF).exists()).isFalse()
        assertThat(customizedRule.getPathOnDeviceFor(IMAGE_EXPECTED).exists()).isTrue()
        assertThat(customizedRule.getPathOnDeviceFor(RESULT_BIN_PROTO).exists()).isTrue()
        assertThat(diffProto.build().imageLocationGolden.startsWith(customizedAssetsPath)).isTrue()
    }

    @Test
    fun performDiff_noPixelCompared() {
        val first = loadBitmap("round_rect_gray")

        first.assertAgainstGolden(
            rule, "round_rect_green", matcher = MSSIMMatcher(),
            regions = arrayOf(Rect(/* left= */1, /* top= */1, /* right= */2, /* bottom=*/2))
        )

        val resultProto = rule.getPathOnDeviceFor(RESULT_PROTO)
        assertThat(resultProto.readText()).contains("PASS")
        assertThat(rule.getPathOnDeviceFor(IMAGE_ACTUAL).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(IMAGE_DIFF).exists()).isFalse()
        assertThat(rule.getPathOnDeviceFor(IMAGE_EXPECTED).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(RESULT_BIN_PROTO).exists()).isTrue()
    }

    @Test
    fun performDiff_sameRegion() {
        val first = loadBitmap("qmc-folder1")
        val startHeight = 18 * first.height / 20
        val endHeight = 37 * first.height / 40
        val startWidth = 10 * first.width / 20
        val endWidth = 11 * first.width / 20
        val matcher = MSSIMMatcher()

        first.assertAgainstGolden(
            rule, "qmc-folder2", matcher,
            arrayOf(Rect(startWidth, startHeight, endWidth, endHeight))
        )

        val resultProto = rule.getPathOnDeviceFor(RESULT_PROTO)
        assertThat(resultProto.readText()).contains("PASS")
        assertThat(rule.getPathOnDeviceFor(IMAGE_ACTUAL).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(IMAGE_DIFF).exists()).isFalse()
        assertThat(rule.getPathOnDeviceFor(IMAGE_EXPECTED).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(RESULT_BIN_PROTO).exists()).isTrue()
    }

    @Test
    fun performDiff_sameSizes_default_noMatch() {
        val imageExtension = ".png"
        val first = loadBitmap("round_rect_gray")
        val compStatistics = ScreenshotResultProto.DiffResult.ComparisonStatistics.newBuilder()
            .setNumberPixelsCompared(1504)
            .setNumberPixelsDifferent(74)
            .setNumberPixelsIgnored(800)
            .setNumberPixelsSimilar(1430)
            .build()

        expectErrorMessage(
            "Image mismatch! Comparison stats: '$compStatistics'"
        ) {
            first.assertAgainstGolden(rule, "round_rect_green")
        }

        val resultProto = rule.getPathOnDeviceFor(RESULT_PROTO)
        assertThat(resultProto.readText()).contains("FAILED")

        val actualImagePathOnDevice = rule.getPathOnDeviceFor(IMAGE_ACTUAL)
        assertThat(actualImagePathOnDevice.exists()).isTrue()
        assertThat(actualImagePathOnDevice.getName().contains("_actual")).isTrue()
        assertThat(actualImagePathOnDevice.getName().contains(imageExtension)).isTrue()

        val diffImagePathOnDevice = rule.getPathOnDeviceFor(IMAGE_DIFF)
        assertThat(diffImagePathOnDevice.exists()).isTrue()
        assertThat(diffImagePathOnDevice.getName().contains("_diff")).isTrue()
        assertThat(diffImagePathOnDevice.getName().contains(imageExtension)).isTrue()

        val expectedImagePathOnDevice = rule.getPathOnDeviceFor(IMAGE_EXPECTED)
        assertThat(expectedImagePathOnDevice.exists()).isTrue()
        assertThat(expectedImagePathOnDevice.getName().contains("_expected")).isTrue()
        assertThat(expectedImagePathOnDevice.getName().contains(imageExtension)).isTrue()

        val binProtoPathOnDevice = rule.getPathOnDeviceFor(RESULT_BIN_PROTO)
        assertThat(binProtoPathOnDevice.exists()).isTrue()
        assertThat(binProtoPathOnDevice.getName().contains("_goldResult")).isTrue()
    }

    @Test
    fun performDiff_sameSizes_pixelPerfect_noMatch() {
        val first = loadBitmap("round_rect_gray")
        val compStatistics = ScreenshotResultProto.DiffResult.ComparisonStatistics.newBuilder()
            .setNumberPixelsCompared(2304)
            .setNumberPixelsDifferent(556)
            .setNumberPixelsIdentical(1748)
            .build()

        expectErrorMessage(
            "Image mismatch! Comparison stats: '$compStatistics'"
        ) {
            first
                .assertAgainstGolden(rule, "round_rect_green", matcher = PixelPerfectMatcher())
        }

        val resultProto = rule.getPathOnDeviceFor(RESULT_PROTO)
        assertThat(resultProto.readText()).contains("FAILED")
        assertThat(rule.getPathOnDeviceFor(IMAGE_ACTUAL).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(IMAGE_DIFF).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(IMAGE_EXPECTED).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(RESULT_BIN_PROTO).exists()).isTrue()
    }

    @Test
    fun performDiff_differentSizes() {
        val first =
            loadBitmap("fullscreen_rect_gray")

        expectErrorMessage("Sizes are different! Expected: [48, 48], Actual: [720, 1184]") {
            first
                .assertAgainstGolden(rule, "round_rect_gray")
        }

        val resultProto = rule.getPathOnDeviceFor(RESULT_PROTO)
        assertThat(resultProto.readText()).contains("FAILED")
        assertThat(rule.getPathOnDeviceFor(IMAGE_ACTUAL).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(IMAGE_DIFF).exists()).isFalse()
        assertThat(rule.getPathOnDeviceFor(IMAGE_EXPECTED).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(RESULT_BIN_PROTO).exists()).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun performDiff_incorrectGoldenName() {
        val first =
            loadBitmap("fullscreen_rect_gray")

        first
            .assertAgainstGolden(rule, "round_rect_gray #")
    }

    @Test
    fun performDiff_missingGolden() {
        val first = loadBitmap("round_rect_gray")

        expectErrorMessage(
            "Missing golden image 'does_not_exist.png'. Did you mean to check in " +
                "a new image?"
        ) {
            first
                .assertAgainstGolden(rule, "does_not_exist")
        }

        val resultProto = rule.getPathOnDeviceFor(RESULT_PROTO)
        assertThat(resultProto.readText()).contains("MISSING_REFERENCE")
        assertThat(rule.getPathOnDeviceFor(IMAGE_ACTUAL).exists()).isTrue()
        assertThat(rule.getPathOnDeviceFor(IMAGE_DIFF).exists()).isFalse()
        assertThat(rule.getPathOnDeviceFor(IMAGE_EXPECTED).exists()).isFalse()
        assertThat(rule.getPathOnDeviceFor(RESULT_BIN_PROTO).exists()).isTrue()
    }

    @After
    fun after() {
        // Clear all files we generated so we don't have dependencies between tests
        File(rule.goldenImagePathManager.deviceLocalPath).deleteRecursively()
    }

    private fun expectErrorMessage(expectedErrorMessage: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            val received = e.localizedMessage!!
            assertThat(received).isEqualTo(expectedErrorMessage.trim())
            return
        }

        throw AssertionError("No AssertionError thrown!")
    }
}
