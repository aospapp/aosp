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

package android.photopicker.cts.util;

import static android.photopicker.cts.util.PhotoPickerUiUtils.SHORT_TIMEOUT;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;

/**
 * Photo Picker Utility methods for PhotoPicker UI assertions.
 */
public class UiAssertionUtils {
    /**
     * Verifies PhotoPicker UI is shown.
     */
    public static void assertThatShowsPickerUi() {
        // Assert that Bottom Sheet is shown
        // Add a short timeout wait for PhotoPicker to show
        assertThat(new UiObject(new UiSelector().resourceIdMatches(
                PhotoPickerUiUtils.REGEX_PACKAGE_NAME + ":id/bottom_sheet"))
                .waitForExists(SHORT_TIMEOUT)).isTrue();

        // Assert that privacy text is shown
        assertThat(new UiObject(new UiSelector().resourceIdMatches(
                PhotoPickerUiUtils.REGEX_PACKAGE_NAME + ":id/privacy_text"))
                .exists()).isTrue();

        // Assert that "Photos" and "Albums" headers are shown.
        assertThat(new UiObject(new UiSelector().text("Photos")).exists()).isTrue();
        assertThat(new UiObject(new UiSelector().text("Albums")).exists()).isTrue();
    }
}
