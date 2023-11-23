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

package android.photopicker.cts.util;

import static com.google.common.truth.Truth.assertWithMessage;

import android.text.format.DateUtils;

import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;

import java.util.ArrayList;
import java.util.List;

/**
 * Photo Picker Utility methods for finding UI elements.
 */
public class PhotoPickerUiUtils {
    public static final long SHORT_TIMEOUT = 5 * DateUtils.SECOND_IN_MILLIS;

    public static final long TIMEOUT = 30 * DateUtils.SECOND_IN_MILLIS;

    public static final String REGEX_PACKAGE_NAME =
            "com(.google)?.android.providers.media(.module)?";

    /**
     * Get the list of items from the photo grid list.
     * @param itemCount if the itemCount is -1, return all matching items. Otherwise, return the
     *                  item list that its size is not greater than the itemCount.
     * @throws Exception
     */
    public static List<UiObject> findItemList(int itemCount) throws Exception {
        final List<UiObject> itemList = new ArrayList<>();
        final UiSelector gridList = new UiSelector().resourceIdMatches(
                REGEX_PACKAGE_NAME + ":id/picker_tab_recyclerview");

        // Wait for the first item to appear
        assertWithMessage("Timed out while waiting for first item to appear")
                .that(new UiObject(gridList.childSelector(new UiSelector())).waitForExists(TIMEOUT))
                .isTrue();

        final UiSelector itemSelector = new UiSelector().resourceIdMatches(
                REGEX_PACKAGE_NAME + ":id/icon_thumbnail");
        final UiScrollable grid = new UiScrollable(gridList);
        final int childCount = grid.getChildCount();
        final int count = itemCount == -1 ? childCount : itemCount;

        for (int i = 0; i < childCount; i++) {
            final UiObject item = grid.getChildByInstance(itemSelector, i);
            if (item.exists()) {
                itemList.add(item);
            }
            if (itemList.size() == count) {
                break;
            }
        }
        return itemList;
    }

    public static UiObject findPreviewAddButton() {
        return new UiObject(new UiSelector().resourceIdMatches(
                REGEX_PACKAGE_NAME + ":id/preview_add_button"));
    }

    public static UiObject findPreviewAddOrSelectButton() {
        return new UiObject(new UiSelector().resourceIdMatches(
                REGEX_PACKAGE_NAME + ":id/preview_add_or_select_button"));
    }

    public static UiObject findAddButton() {
        return new UiObject(new UiSelector().resourceIdMatches(
                REGEX_PACKAGE_NAME + ":id/button_add"));
    }

    public static UiObject findProfileButton() {
        return new UiObject(new UiSelector().resourceIdMatches(
                REGEX_PACKAGE_NAME + ":id/profile_button"));
    }

    public static void findAndClickBrowse(UiDevice uiDevice) throws Exception {
        final UiObject overflowMenu = getOverflowMenuObject(uiDevice);
        clickAndWait(uiDevice, overflowMenu);

        final UiObject browseButton = new UiObject(new UiSelector().textContains("Browse"));
        clickAndWait(uiDevice, browseButton);
    }

    public static UiObject findSettingsOverflowMenuItem(UiDevice uiDevice) throws Exception {
        final UiObject overflowMenu = getOverflowMenuObject(uiDevice);
        clickAndWait(uiDevice, overflowMenu);
        return new UiObject(new UiSelector().textContains("Cloud media app"));
    }

    public static UiObject getOverflowMenuObject(UiDevice uiDevice)  {
        // Wait for overflow menu to appear.
        verifyOverflowMenuExists(uiDevice);
        return new UiObject(new UiSelector().description("More options"));
    }

    public static boolean isPhotoPickerVisible() {
        return new UiObject(new UiSelector().resourceIdMatches(
                PhotoPickerUiUtils.REGEX_PACKAGE_NAME + ":id/bottom_sheet")).waitForExists(TIMEOUT);
    }

    public static void verifySettingsActionBarIsVisible() {
        assertWithMessage("Timed out waiting for action bar to appear")
                .that(new UiObject(new UiSelector()
                        .resourceIdMatches(REGEX_PACKAGE_NAME + ":id/picker_settings_toolbar"))
                        .waitForExists(TIMEOUT))
                .isTrue();
    }

    public static void verifySettingsTitleIsVisible() {
        assertWithMessage("Timed out waiting for settings page title to appear")
                .that(new UiObject(new UiSelector()
                        .resourceIdMatches(REGEX_PACKAGE_NAME + ":id/picker_settings_title"))
                        .waitForExists(TIMEOUT))
                .isTrue();
    }

    public static void verifySettingsDescriptionIsVisible() {
        assertWithMessage("Timed out waiting for settings page description to appear")
                .that(new UiObject(new UiSelector()
                        .resourceIdMatches(REGEX_PACKAGE_NAME + ":id/picker_settings_description"))
                        .waitForExists(TIMEOUT))
                .isTrue();
    }

    public static void verifySettingsFragmentContainerExists() {
        assertWithMessage("Timed out waiting for settings fragment container to appear")
                .that(new UiObject(new UiSelector()
                        .resourceIdMatches(REGEX_PACKAGE_NAME + ":id/settings_fragment_container"))
                        .waitForExists(TIMEOUT))
                .isTrue();
    }

    private static void verifyOverflowMenuExists(UiDevice uiDevice) {
        assertWithMessage("Timed out waiting for overflow menu to appear")
                .that(new UiObject(new UiSelector().description("More options"))
                        .waitForExists(TIMEOUT))
                .isTrue();
    }

    public static void verifySettingsActivityIsVisible() {
        // id/settings_activity_root is the root layout in activity_photo_picker_settings.xml
        assertWithMessage("Timed out waiting for settings activity to appear")
                .that(new UiObject(new UiSelector()
                .resourceIdMatches(REGEX_PACKAGE_NAME + ":id/settings_activity_root"))
                .waitForExists(TIMEOUT))
                .isTrue();
    }

    public static void clickAndWait(UiDevice uiDevice, UiObject uiObject) throws Exception {
        uiObject.click();
        uiDevice.waitForIdle();
    }

    public static String getBannerPrimaryText() throws Exception {
        final UiObject bannerPrimaryText = findBannerPrimaryText();
        assertWithMessage("Timed out waiting for the banner to appear")
                .that(bannerPrimaryText.waitForExists(TIMEOUT))
                .isTrue();
        return bannerPrimaryText.getText();
    }

    public static UiObject findBannerPrimaryText() {
        return new UiObject(new UiSelector().resourceIdMatches(
                REGEX_PACKAGE_NAME + ":id/banner_primary_text"));
    }

    public static UiObject findBannerDismissButton() {
        return new UiObject(new UiSelector().resourceIdMatches(
                REGEX_PACKAGE_NAME + ":id/dismiss_button"));
    }

    public static UiObject findBannerActionButton() {
        return new UiObject(new UiSelector().resourceIdMatches(
                REGEX_PACKAGE_NAME + ":id/action_button"));
    }
}
