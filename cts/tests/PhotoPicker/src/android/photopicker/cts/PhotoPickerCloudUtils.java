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

import static android.Manifest.permission.READ_DEVICE_CONFIG;
import static android.Manifest.permission.WRITE_ALLOWLISTED_DEVICE_CONFIG;
import static android.Manifest.permission.WRITE_DEVICE_CONFIG;
import static android.photopicker.cts.PickerProviderMediaGenerator.setCloudProvider;
import static android.photopicker.cts.PickerProviderMediaGenerator.syncCloudProvider;
import static android.photopicker.cts.util.PhotoPickerUiUtils.findAddButton;
import static android.photopicker.cts.util.PhotoPickerUiUtils.findItemList;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.UiAutomation;
import android.content.ClipData;
import android.content.Context;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;

import com.android.modules.utils.build.SdkLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PhotoPickerCloudUtils {
    private static final String NAMESPACE_STORAGE_NATIVE_BOOT = "storage_native_boot";
    private static final String KEY_ALLOWED_CLOUD_PROVIDERS = "allowed_cloud_providers";
    private static final String KEY_CLOUD_MEDIA_FEATURE_ENABLED = "cloud_media_feature_enabled";

    public static List<String> extractMediaIds(ClipData clipData, int minCount) {
        final int count = clipData.getItemCount();
        assertThat(count).isAtLeast(minCount);

        final List<String> mediaIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            mediaIds.add(clipData.getItemAt(i).getUri().getLastPathSegment());
        }

        return mediaIds;
    }

    public static void selectAndAddPickerMedia(UiDevice uiDevice, int maxCount)
            throws Exception {
        final List<UiObject> itemList = findItemList(maxCount);
        for (int i = 0; i < itemList.size(); i++) {
            final UiObject item = itemList.get(i);
            item.click();
            uiDevice.waitForIdle();
        }

        final UiObject addButton = findAddButton();
        addButton.click();
        uiDevice.waitForIdle();
    }

    public static ClipData fetchPickerMedia(GetResultActivity activity, UiDevice uiDevice,
            int maxCount) throws Exception {
        selectAndAddPickerMedia(uiDevice, maxCount);

        return activity.getResult().data.getClipData();
    }

    public static void initCloudProviderWithImage(
            Context context, PickerProviderMediaGenerator.MediaGenerator mediaGenerator,
            String authority, Pair<String, String>... mediaPairs) throws Exception {
        setCloudProvider(context, authority);
        assertThat(MediaStore.isCurrentCloudMediaProviderAuthority(context.getContentResolver(),
                authority)).isTrue();

        for (Pair<String, String> pair : mediaPairs) {
            addImage(mediaGenerator, pair.first, pair.second);
        }

        syncCloudProvider(context);
    }

    public static void addImage(PickerProviderMediaGenerator.MediaGenerator generator,
            String localId, String cloudId)
            throws Exception {
        final long imageSizeBytes = 107684;
        generator.addMedia(localId, cloudId, /* albumId */ null, "image/jpeg",
                /* mimeTypeExtension */ 0, imageSizeBytes, /* isFavorite */ false,
                R.raw.lg_g4_iso_800_jpg);
    }

    public static void containsExcept(List<String> mediaIds, String contained,
            String notContained) {
        assertThat(mediaIds).contains(contained);
        assertThat(mediaIds).containsNoneIn(Collections.singletonList(notContained));
    }

    public static boolean isCloudMediaEnabled() {
        return Boolean.parseBoolean(readDeviceConfigProp(KEY_CLOUD_MEDIA_FEATURE_ENABLED));
    }

    @Nullable
    static String getAllowedProvidersDeviceConfig() {
        return readDeviceConfigProp(KEY_ALLOWED_CLOUD_PROVIDERS);
    }

    static void enableCloudMediaAndSetAllowedCloudProviders(@NonNull String allowedPackagesJoined) {
        writeDeviceConfigProp(KEY_ALLOWED_CLOUD_PROVIDERS, allowedPackagesJoined);
        assertWithMessage("Failed to update the allowed cloud providers device config")
                .that(getAllowedProvidersDeviceConfig())
                .isEqualTo(allowedPackagesJoined);

        writeDeviceConfigProp(KEY_CLOUD_MEDIA_FEATURE_ENABLED, true);
    }


    static void disableCloudMediaAndClearAllowedCloudProviders() {
        writeDeviceConfigProp(KEY_CLOUD_MEDIA_FEATURE_ENABLED, false);

        deleteDeviceConfigProp(KEY_ALLOWED_CLOUD_PROVIDERS);
        assertWithMessage("Failed to delete the allowed cloud providers device config")
                .that(getAllowedProvidersDeviceConfig())
                .isNull();
    }

    @NonNull
    private static UiAutomation getUiAutomation() {
        return InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    @Nullable
    private static String readDeviceConfigProp(@NonNull String name) {
        getUiAutomation().adoptShellPermissionIdentity(READ_DEVICE_CONFIG);
        try {
            return DeviceConfig.getProperty(NAMESPACE_STORAGE_NATIVE_BOOT, name);
        } finally {
            getUiAutomation().dropShellPermissionIdentity();
        }
    }

    private static void writeDeviceConfigProp(@NonNull String name, boolean value) {
        writeDeviceConfigProp(name, Boolean.toString(value));
    }

    private static void writeDeviceConfigProp(@NonNull String name, @NonNull String value) {
        if (SdkLevel.isAtLeastU()) {
            getUiAutomation().adoptShellPermissionIdentity(WRITE_ALLOWLISTED_DEVICE_CONFIG);
        } else {
            getUiAutomation().adoptShellPermissionIdentity(WRITE_DEVICE_CONFIG);
        }
        try {
            DeviceConfig.setProperty(NAMESPACE_STORAGE_NATIVE_BOOT, name, value,
                    /* makeDefault*/ false);
        } finally {
            getUiAutomation().dropShellPermissionIdentity();
        }
    }

    private static void deleteDeviceConfigProp(@NonNull String name) {
        DeviceConfig.deleteProperty(NAMESPACE_STORAGE_NATIVE_BOOT, name);
    }
}
