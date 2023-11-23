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

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.google.common.truth.Truth.assertThat
import platform.test.screenshot.matchers.PixelPerfectMatcher
import platform.test.screenshot.utils.loadBitmap
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class PixelPerfectMatcherTest {

    @Test
    fun performDiff_sameBitmaps() {
        val first = loadBitmap("round_rect_gray")
        val second = loadBitmap("round_rect_gray")

        val matcher = PixelPerfectMatcher()
        val result = matcher.compareBitmaps(
            first.toIntArray(), second.toIntArray(),
            first.width, first.height
        )

        assertThat(result.matches).isTrue()
    }

    @Test
    fun performDiff_sameSize_differentBorders() {
        val first = loadBitmap("round_rect_gray")
        val second = loadBitmap("round_rect_green")

        val matcher = PixelPerfectMatcher()
        val result = matcher.compareBitmaps(
            first.toIntArray(), second.toIntArray(),
            first.width, first.height
        )

        assertThat(result.matches).isFalse()
    }

    @Test
    fun performDiff_sameSize_differentBorders_partialCompare() {
        val first = loadBitmap("round_rect_gray")
        val second = loadBitmap("round_rect_green")

        val matcher = PixelPerfectMatcher()
        val result = matcher.compareBitmaps(
            first.toIntArray(), second.toIntArray(),
            first.width, first.height,
            arrayOf(Rect(/* left= */1, /* top= */1, /* right= */4, /* bottom= */4))
        )

        assertThat(result.matches).isTrue()
    }
}
