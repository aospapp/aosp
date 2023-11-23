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

package com.android.server.nearby.common.fastpair;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Bitmap;

import org.junit.Test;
import org.mockito.Mock;

public class IconUtilsTest {
    private static final int MIN_ICON_SIZE = 16;
    private static final int DESIRED_ICON_SIZE = 32;
    @Mock
    Context mContext;

    @Test
    public void isIconSizedCorrectly() {
        // Null bitmap is not sized correctly
        assertThat(IconUtils.isIconSizeCorrect(null)).isFalse();

        int minIconSize = MIN_ICON_SIZE;
        int desiredIconSize = DESIRED_ICON_SIZE;

        // Bitmap that is 1x1 pixels is not sized correctly
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
        assertThat(IconUtils.isIconSizeCorrect(icon)).isFalse();

        // Bitmap is categorized as small, and not regular
        icon = Bitmap.createBitmap(minIconSize + 1,
                minIconSize + 1, Bitmap.Config.ALPHA_8);
        assertThat(IconUtils.isIconSizeCorrect(icon)).isTrue();
        assertThat(IconUtils.isIconSizedSmall(icon)).isTrue();
        assertThat(IconUtils.isIconSizedRegular(icon)).isFalse();

        // Bitmap is categorized as regular, but not small
        icon = Bitmap.createBitmap(desiredIconSize + 1,
                desiredIconSize + 1, Bitmap.Config.ALPHA_8);
        assertThat(IconUtils.isIconSizeCorrect(icon)).isTrue();
        assertThat(IconUtils.isIconSizedSmall(icon)).isFalse();
        assertThat(IconUtils.isIconSizedRegular(icon)).isTrue();
    }

    @Test
    public void testAddWhiteCircleBackground() {
        int minIconSize = MIN_ICON_SIZE;
        Bitmap icon = Bitmap.createBitmap(minIconSize + 1, minIconSize + 1,
                Bitmap.Config.ALPHA_8);

        assertThat(
                IconUtils.isIconSizeCorrect(IconUtils.addWhiteCircleBackground(mContext, icon)))
                .isTrue();
    }
}
