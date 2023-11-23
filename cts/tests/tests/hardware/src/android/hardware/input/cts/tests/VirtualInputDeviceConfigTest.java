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

package android.hardware.input.cts.tests;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.input.VirtualDpadConfig;
import android.hardware.input.VirtualKeyboardConfig;
import android.hardware.input.VirtualMouseConfig;
import android.hardware.input.VirtualNavigationTouchpadConfig;
import android.hardware.input.VirtualTouchscreenConfig;
import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class VirtualInputDeviceConfigTest {

    private static final int PRODUCT_ID = 1;
    private static final int VENDOR_ID = 1;
    private static final String DEVICE_NAME = "VirtualTestDevice";
    private static final int DISPLAY_ID = 2;
    private static final int WIDTH = 600;
    private static final int HEIGHT = 800;

    private VirtualDpadConfig createVirtualDpadConfig() {
        return new VirtualDpadConfig.Builder()
                .setInputDeviceName(DEVICE_NAME)
                .setVendorId(VENDOR_ID)
                .setProductId(PRODUCT_ID)
                .setAssociatedDisplayId(DISPLAY_ID)
                .build();
    }

    private VirtualKeyboardConfig createVirtualKeyboardConfigWithLanguageTag(String languageTag) {
        return new VirtualKeyboardConfig.Builder()
                .setInputDeviceName(DEVICE_NAME)
                .setVendorId(VENDOR_ID)
                .setProductId(PRODUCT_ID)
                .setAssociatedDisplayId(DISPLAY_ID)
                .setLanguageTag(languageTag)
                .setLayoutType(VirtualKeyboardConfig.DEFAULT_LAYOUT_TYPE)
                .build();
    }

    private VirtualKeyboardConfig createVirtualKeyboardConfig() {
        return createVirtualKeyboardConfigWithLanguageTag(
                VirtualKeyboardConfig.DEFAULT_LANGUAGE_TAG);
    }

    private VirtualMouseConfig createVirtualMouseConfig() {
        return new VirtualMouseConfig.Builder()
                .setInputDeviceName(DEVICE_NAME)
                .setVendorId(VENDOR_ID)
                .setProductId(PRODUCT_ID)
                .setAssociatedDisplayId(DISPLAY_ID)
                .build();
    }

    private VirtualTouchscreenConfig createVirtualTouchscreenConfig() {
        return new VirtualTouchscreenConfig.Builder(WIDTH, HEIGHT)
                .setInputDeviceName(DEVICE_NAME)
                .setVendorId(VENDOR_ID)
                .setProductId(PRODUCT_ID)
                .setAssociatedDisplayId(DISPLAY_ID)
                .build();
    }

    private VirtualNavigationTouchpadConfig createVirtualNavigationTouchpadConfig() {
        return new VirtualNavigationTouchpadConfig.Builder(WIDTH, HEIGHT)
                .setInputDeviceName(DEVICE_NAME)
                .setVendorId(VENDOR_ID)
                .setProductId(PRODUCT_ID)
                .setAssociatedDisplayId(DISPLAY_ID)
                .build();
    }

    @Test
    public void testConstructorAndGetters_virtualDpadConfig() {
        VirtualDpadConfig config = createVirtualDpadConfig();
        assertThat(config.getInputDeviceName()).isEqualTo(DEVICE_NAME);
        assertThat(config.getVendorId()).isEqualTo(VENDOR_ID);
        assertThat(config.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(config.getAssociatedDisplayId()).isEqualTo(DISPLAY_ID);
    }

    @Test
    public void testParcel_virtualDpadConfig() {
        VirtualDpadConfig config = createVirtualDpadConfig();
        Parcel parcel = Parcel.obtain();
        config.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VirtualDpadConfig configFromParcel = VirtualDpadConfig.CREATOR.createFromParcel(parcel);
        assertThat(configFromParcel.getInputDeviceName()).isEqualTo(config.getInputDeviceName());
        assertThat(configFromParcel.getVendorId()).isEqualTo(config.getVendorId());
        assertThat(configFromParcel.getProductId()).isEqualTo(config.getProductId());
        assertThat(configFromParcel.getAssociatedDisplayId()).isEqualTo(
                config.getAssociatedDisplayId());
    }

    @Test
    public void testConstructorAndGetters_virtualKeyboardConfig() {
        VirtualKeyboardConfig config = createVirtualKeyboardConfig();
        assertThat(config.getInputDeviceName()).isEqualTo(DEVICE_NAME);
        assertThat(config.getVendorId()).isEqualTo(VENDOR_ID);
        assertThat(config.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(config.getAssociatedDisplayId()).isEqualTo(DISPLAY_ID);
        assertThat(config.getLanguageTag()).isEqualTo(VirtualKeyboardConfig.DEFAULT_LANGUAGE_TAG);
        assertThat(config.getLayoutType()).isEqualTo(VirtualKeyboardConfig.DEFAULT_LAYOUT_TYPE);
    }

    @Test
    public void testParcel_virtualKeyboardConfig() {
        VirtualKeyboardConfig config = createVirtualKeyboardConfig();
        Parcel parcel = Parcel.obtain();
        config.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VirtualKeyboardConfig configFromParcel =
                VirtualKeyboardConfig.CREATOR.createFromParcel(parcel);
        assertThat(configFromParcel.getInputDeviceName()).isEqualTo(config.getInputDeviceName());
        assertThat(configFromParcel.getVendorId()).isEqualTo(config.getVendorId());
        assertThat(configFromParcel.getProductId()).isEqualTo(config.getProductId());
        assertThat(configFromParcel.getAssociatedDisplayId()).isEqualTo(
                config.getAssociatedDisplayId());
        assertThat(configFromParcel.getLanguageTag()).isEqualTo(
                config.getLanguageTag());
        assertThat(configFromParcel.getLayoutType()).isEqualTo(
                config.getLayoutType());
    }

    @Test
    public void testBuilder_virtualKeyboardConfig_defaultValuedUsed() {
        //(TODO:b/262924887) Add end-to-end tests for selecting virtual keyboard layout.
        VirtualKeyboardConfig config = new VirtualKeyboardConfig.Builder()
                .setInputDeviceName(DEVICE_NAME)
                .setVendorId(VENDOR_ID)
                .setProductId(PRODUCT_ID)
                .setAssociatedDisplayId(DISPLAY_ID)
                .build();
        assertThat(config.getLanguageTag()).isEqualTo(VirtualKeyboardConfig.DEFAULT_LANGUAGE_TAG);
        assertThat(config.getLayoutType()).isEqualTo(VirtualKeyboardConfig.DEFAULT_LAYOUT_TYPE);
    }

    @Test
    public void testBuilder_wellFormedTags_noException() {
        VirtualKeyboardConfig.Builder builder = new VirtualKeyboardConfig.Builder();

        String wellFormedTag1 = "ru-Cyrl";
        builder.setLanguageTag(wellFormedTag1);

        String wellFormedTag2 = "es-Latn-419";
        builder.setLanguageTag(wellFormedTag2);

        String wellFormedTag3 = "zh-CN";
        builder.setLanguageTag(wellFormedTag3);

        String wellFormedTag4 = "pt-Latn-PT";
        builder.setLanguageTag(wellFormedTag4);
    }

    @Test
    public void testConstructorAndGetters_virtualMouseConfig() {
        VirtualMouseConfig config = createVirtualMouseConfig();
        assertThat(config.getInputDeviceName()).isEqualTo(DEVICE_NAME);
        assertThat(config.getVendorId()).isEqualTo(VENDOR_ID);
        assertThat(config.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(config.getAssociatedDisplayId()).isEqualTo(DISPLAY_ID);
    }

    @Test
    public void testParcel_virtualVirtualMouseConfig() {
        VirtualMouseConfig config = createVirtualMouseConfig();
        Parcel parcel = Parcel.obtain();
        config.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VirtualMouseConfig configFromParcel = VirtualMouseConfig.CREATOR.createFromParcel(parcel);
        assertThat(configFromParcel.getInputDeviceName()).isEqualTo(config.getInputDeviceName());
        assertThat(configFromParcel.getVendorId()).isEqualTo(config.getVendorId());
        assertThat(configFromParcel.getProductId()).isEqualTo(config.getProductId());
        assertThat(configFromParcel.getAssociatedDisplayId()).isEqualTo(
                config.getAssociatedDisplayId());
    }

    @Test
    public void testConstructorAndGetters_virtualTouchscreenConfig() {
        VirtualTouchscreenConfig config = createVirtualTouchscreenConfig();
        assertThat(config.getInputDeviceName()).isEqualTo(DEVICE_NAME);
        assertThat(config.getVendorId()).isEqualTo(VENDOR_ID);
        assertThat(config.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(config.getAssociatedDisplayId()).isEqualTo(DISPLAY_ID);
        assertThat(config.getWidth()).isEqualTo(WIDTH);
        assertThat(config.getHeight()).isEqualTo(HEIGHT);

    }

    @Test
    public void testParcel_virtualTouchscreenConfig() {
        VirtualTouchscreenConfig config = createVirtualTouchscreenConfig();
        Parcel parcel = Parcel.obtain();
        config.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VirtualTouchscreenConfig configFromParcel =
                VirtualTouchscreenConfig.CREATOR.createFromParcel(parcel);
        assertThat(configFromParcel.getInputDeviceName()).isEqualTo(config.getInputDeviceName());
        assertThat(configFromParcel.getVendorId()).isEqualTo(config.getVendorId());
        assertThat(configFromParcel.getProductId()).isEqualTo(config.getProductId());
        assertThat(configFromParcel.getAssociatedDisplayId()).isEqualTo(
                config.getAssociatedDisplayId());
        assertThat(configFromParcel.getWidth()).isEqualTo(config.getWidth());
        assertThat(configFromParcel.getHeight()).isEqualTo(config.getHeight());
    }


    @Test
    public void testConstructorAndGetters_virtualNavigationTouchpadConfig() {
        VirtualNavigationTouchpadConfig config = createVirtualNavigationTouchpadConfig();
        assertThat(config.getInputDeviceName()).isEqualTo(DEVICE_NAME);
        assertThat(config.getVendorId()).isEqualTo(VENDOR_ID);
        assertThat(config.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(config.getAssociatedDisplayId()).isEqualTo(DISPLAY_ID);
        assertThat(config.getWidth()).isEqualTo(WIDTH);
        assertThat(config.getHeight()).isEqualTo(HEIGHT);

    }

    @Test
    public void testParcel_virtualNavigationTouchpadConfig() {
        VirtualNavigationTouchpadConfig config = createVirtualNavigationTouchpadConfig();
        Parcel parcel = Parcel.obtain();
        config.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VirtualNavigationTouchpadConfig configFromParcel =
                VirtualNavigationTouchpadConfig.CREATOR.createFromParcel(parcel);
        assertThat(configFromParcel.getInputDeviceName()).isEqualTo(config.getInputDeviceName());
        assertThat(configFromParcel.getVendorId()).isEqualTo(config.getVendorId());
        assertThat(configFromParcel.getProductId()).isEqualTo(config.getProductId());
        assertThat(configFromParcel.getAssociatedDisplayId()).isEqualTo(
                config.getAssociatedDisplayId());
        assertThat(configFromParcel.getWidth()).isEqualTo(config.getWidth());
        assertThat(configFromParcel.getHeight()).isEqualTo(config.getHeight());
    }
}
