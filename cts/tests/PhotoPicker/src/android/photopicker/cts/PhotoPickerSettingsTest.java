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

package android.photopicker.cts;

import static android.photopicker.cts.PhotoPickerCloudUtils.disableCloudMediaAndClearAllowedCloudProviders;
import static android.photopicker.cts.PhotoPickerCloudUtils.enableCloudMediaAndSetAllowedCloudProviders;
import static android.photopicker.cts.PhotoPickerCloudUtils.getAllowedProvidersDeviceConfig;
import static android.photopicker.cts.PhotoPickerCloudUtils.isCloudMediaEnabled;
import static android.photopicker.cts.util.PhotoPickerUiUtils.isPhotoPickerVisible;
import static android.photopicker.cts.util.PhotoPickerUiUtils.verifySettingsActionBarIsVisible;
import static android.photopicker.cts.util.PhotoPickerUiUtils.verifySettingsActivityIsVisible;
import static android.photopicker.cts.util.PhotoPickerUiUtils.verifySettingsDescriptionIsVisible;
import static android.photopicker.cts.util.PhotoPickerUiUtils.verifySettingsFragmentContainerExists;
import static android.photopicker.cts.util.PhotoPickerUiUtils.verifySettingsTitleIsVisible;

import android.content.Intent;
import android.os.Build;
import android.photopicker.cts.util.PhotoPickerUiUtils;
import android.provider.MediaStore;

import androidx.test.filters.SdkSuppress;
import androidx.test.uiautomator.UiObject;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Photo Picker tests for settings page launched from the overflow menu in PhotoPickerActivity or
 * the Settings app.
 */
// TODO(b/195009187): Enabling settings page requires setting allowed_cloud_providers device config.
//  We currently can't do this in R.
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
public class PhotoPickerSettingsTest extends PhotoPickerBaseTest {

    private static boolean sCloudMediaPreviouslyEnabled;
    private static String sPreviouslyAllowedCloudProviders;

    @BeforeClass
    public static void setUpBeforeClass() {
        // Store the current CMP configs, so that we can reset them at the end of the test.
        sCloudMediaPreviouslyEnabled = isCloudMediaEnabled();
        if (sCloudMediaPreviouslyEnabled) {
            sPreviouslyAllowedCloudProviders = getAllowedProvidersDeviceConfig();
        }

        // Enable Settings menu item in PhotoPickerActivity's overflow menu.
        PhotoPickerCloudUtils.enableCloudMediaAndSetAllowedCloudProviders(
                /* allowedCloudProviders */ "not_empty");
    }

    @AfterClass
    public static void tearDownClass() {
        // Reset CloudMedia configs.
        if (sCloudMediaPreviouslyEnabled) {
            enableCloudMediaAndSetAllowedCloudProviders(sPreviouslyAllowedCloudProviders);
        } else {
            disableCloudMediaAndClearAllowedCloudProviders();
        }
    }

    @Test
    public void testSettingsLaunchFromOverflowMenu() throws Exception {
        // Launch PhotoPickerActivity.
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        mActivity.startActivityForResult(intent, REQUEST_CODE);
        sDevice.waitForIdle();
        Assume.assumeTrue("Assume photo picker activity is visible", isPhotoPickerVisible());

        // Click on the Settings menu item in the overflow menu.
        final UiObject settingsMenuItem = PhotoPickerUiUtils.findSettingsOverflowMenuItem(sDevice);
        PhotoPickerUiUtils.clickAndWait(sDevice, settingsMenuItem);

        // Verify PhotoPickerSettingsActivity is launched and visible.
        verifySettingsActivityIsVisible();
        verifySettingsActionBarIsVisible();
        verifySettingsTitleIsVisible();
        verifySettingsDescriptionIsVisible();
        verifySettingsFragmentContainerExists();
    }
}
