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

package android.nearby;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FastPairAntispoofKeyDeviceMetadataTest {

    private static final int BLE_TX_POWER  = 5;
    private static final String CONNECT_SUCCESS_COMPANION_APP_INSTALLED =
            "CONNECT_SUCCESS_COMPANION_APP_INSTALLED";
    private static final String CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED =
            "CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED";
    private static final float DELTA = 0.001f;
    private static final int DEVICE_TYPE = 7;
    private static final String DOWNLOAD_COMPANION_APP_DESCRIPTION =
            "DOWNLOAD_COMPANION_APP_DESCRIPTION";
    private static final String FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION =
            "FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION";
    private static final byte[] IMAGE = new byte[] {7, 9};
    private static final String IMAGE_URL = "IMAGE_URL";
    private static final String INITIAL_NOTIFICATION_DESCRIPTION =
            "INITIAL_NOTIFICATION_DESCRIPTION";
    private static final String INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT =
            "INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT";
    private static final String INITIAL_PAIRING_DESCRIPTION = "INITIAL_PAIRING_DESCRIPTION";
    private static final String INTENT_URI = "INTENT_URI";
    private static final String OPEN_COMPANION_APP_DESCRIPTION = "OPEN_COMPANION_APP_DESCRIPTION";
    private static final String RETRO_ACTIVE_PAIRING_DESCRIPTION =
            "RETRO_ACTIVE_PAIRING_DESCRIPTION";
    private static final String SUBSEQUENT_PAIRING_DESCRIPTION = "SUBSEQUENT_PAIRING_DESCRIPTION";
    private static final float TRIGGER_DISTANCE = 111;
    private static final String TRUE_WIRELESS_IMAGE_URL_CASE = "TRUE_WIRELESS_IMAGE_URL_CASE";
    private static final String TRUE_WIRELESS_IMAGE_URL_LEFT_BUD =
            "TRUE_WIRELESS_IMAGE_URL_LEFT_BUD";
    private static final String TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD =
            "TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD";
    private static final String UNABLE_TO_CONNECT_DESCRIPTION = "UNABLE_TO_CONNECT_DESCRIPTION";
    private static final String UNABLE_TO_CONNECT_TITLE = "UNABLE_TO_CONNECT_TITLE";
    private static final String UPDATE_COMPANION_APP_DESCRIPTION =
            "UPDATE_COMPANION_APP_DESCRIPTION";
    private static final String WAIT_LAUNCH_COMPANION_APP_DESCRIPTION =
            "WAIT_LAUNCH_COMPANION_APP_DESCRIPTION";
    private static final byte[] ANTI_SPOOFING_KEY = new byte[] {4, 5, 6};
    private static final String NAME = "NAME";

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testSetGetFastPairAntispoofKeyDeviceMetadataNotNull() {
        FastPairDeviceMetadata fastPairDeviceMetadata = genFastPairDeviceMetadata();
        FastPairAntispoofKeyDeviceMetadata fastPairAntispoofKeyDeviceMetadata =
                genFastPairAntispoofKeyDeviceMetadata(ANTI_SPOOFING_KEY, fastPairDeviceMetadata);

        assertThat(fastPairAntispoofKeyDeviceMetadata.getAntispoofPublicKey()).isEqualTo(
                ANTI_SPOOFING_KEY);
        ensureFastPairDeviceMetadataAsExpected(
                fastPairAntispoofKeyDeviceMetadata.getFastPairDeviceMetadata());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testSetGetFastPairAntispoofKeyDeviceMetadataNull() {
        FastPairAntispoofKeyDeviceMetadata fastPairAntispoofKeyDeviceMetadata =
                genFastPairAntispoofKeyDeviceMetadata(null, null);
        assertThat(fastPairAntispoofKeyDeviceMetadata.getAntispoofPublicKey()).isEqualTo(
                null);
        assertThat(fastPairAntispoofKeyDeviceMetadata.getFastPairDeviceMetadata()).isEqualTo(
                null);
    }

    /* Verifies DeviceMetadata. */
    private static void ensureFastPairDeviceMetadataAsExpected(FastPairDeviceMetadata metadata) {
        assertThat(metadata.getBleTxPower()).isEqualTo(BLE_TX_POWER);
        assertThat(metadata.getConnectSuccessCompanionAppInstalled())
                .isEqualTo(CONNECT_SUCCESS_COMPANION_APP_INSTALLED);
        assertThat(metadata.getConnectSuccessCompanionAppNotInstalled())
                .isEqualTo(CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED);
        assertThat(metadata.getDeviceType()).isEqualTo(DEVICE_TYPE);
        assertThat(metadata.getDownloadCompanionAppDescription())
                .isEqualTo(DOWNLOAD_COMPANION_APP_DESCRIPTION);
        assertThat(metadata.getFailConnectGoToSettingsDescription())
                .isEqualTo(FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION);
        assertThat(metadata.getImage()).isEqualTo(IMAGE);
        assertThat(metadata.getImageUrl()).isEqualTo(IMAGE_URL);
        assertThat(metadata.getInitialNotificationDescription())
                .isEqualTo(INITIAL_NOTIFICATION_DESCRIPTION);
        assertThat(metadata.getInitialNotificationDescriptionNoAccount())
                .isEqualTo(INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT);
        assertThat(metadata.getInitialPairingDescription()).isEqualTo(INITIAL_PAIRING_DESCRIPTION);
        assertThat(metadata.getIntentUri()).isEqualTo(INTENT_URI);
        assertThat(metadata.getName()).isEqualTo(NAME);
        assertThat(metadata.getOpenCompanionAppDescription())
                .isEqualTo(OPEN_COMPANION_APP_DESCRIPTION);
        assertThat(metadata.getRetroactivePairingDescription())
                .isEqualTo(RETRO_ACTIVE_PAIRING_DESCRIPTION);
        assertThat(metadata.getSubsequentPairingDescription())
                .isEqualTo(SUBSEQUENT_PAIRING_DESCRIPTION);
        assertThat(metadata.getTriggerDistance()).isWithin(DELTA).of(TRIGGER_DISTANCE);
        assertThat(metadata.getTrueWirelessImageUrlCase()).isEqualTo(TRUE_WIRELESS_IMAGE_URL_CASE);
        assertThat(metadata.getTrueWirelessImageUrlLeftBud())
                .isEqualTo(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD);
        assertThat(metadata.getTrueWirelessImageUrlRightBud())
                .isEqualTo(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD);
        assertThat(metadata.getUnableToConnectDescription())
                .isEqualTo(UNABLE_TO_CONNECT_DESCRIPTION);
        assertThat(metadata.getUnableToConnectTitle()).isEqualTo(UNABLE_TO_CONNECT_TITLE);
        assertThat(metadata.getUpdateCompanionAppDescription())
                .isEqualTo(UPDATE_COMPANION_APP_DESCRIPTION);
        assertThat(metadata.getWaitLaunchCompanionAppDescription())
                .isEqualTo(WAIT_LAUNCH_COMPANION_APP_DESCRIPTION);
    }

    /* Generates FastPairAntispoofKeyDeviceMetadata. */
    private static FastPairAntispoofKeyDeviceMetadata genFastPairAntispoofKeyDeviceMetadata(
            byte[] antispoofPublicKey, FastPairDeviceMetadata deviceMetadata) {
        FastPairAntispoofKeyDeviceMetadata.Builder builder =
                new FastPairAntispoofKeyDeviceMetadata.Builder();
        builder.setAntispoofPublicKey(antispoofPublicKey);
        builder.setFastPairDeviceMetadata(deviceMetadata);

        return builder.build();
    }

    /* Generates FastPairDeviceMetadata. */
    private static FastPairDeviceMetadata genFastPairDeviceMetadata() {
        FastPairDeviceMetadata.Builder builder = new FastPairDeviceMetadata.Builder();
        builder.setBleTxPower(BLE_TX_POWER);
        builder.setConnectSuccessCompanionAppInstalled(CONNECT_SUCCESS_COMPANION_APP_INSTALLED);
        builder.setConnectSuccessCompanionAppNotInstalled(
                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED);
        builder.setDeviceType(DEVICE_TYPE);
        builder.setDownloadCompanionAppDescription(DOWNLOAD_COMPANION_APP_DESCRIPTION);
        builder.setFailConnectGoToSettingsDescription(FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION);
        builder.setImage(IMAGE);
        builder.setImageUrl(IMAGE_URL);
        builder.setInitialNotificationDescription(INITIAL_NOTIFICATION_DESCRIPTION);
        builder.setInitialNotificationDescriptionNoAccount(
                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT);
        builder.setInitialPairingDescription(INITIAL_PAIRING_DESCRIPTION);
        builder.setIntentUri(INTENT_URI);
        builder.setName(NAME);
        builder.setOpenCompanionAppDescription(OPEN_COMPANION_APP_DESCRIPTION);
        builder.setRetroactivePairingDescription(RETRO_ACTIVE_PAIRING_DESCRIPTION);
        builder.setSubsequentPairingDescription(SUBSEQUENT_PAIRING_DESCRIPTION);
        builder.setTriggerDistance(TRIGGER_DISTANCE);
        builder.setTrueWirelessImageUrlCase(TRUE_WIRELESS_IMAGE_URL_CASE);
        builder.setTrueWirelessImageUrlLeftBud(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD);
        builder.setTrueWirelessImageUrlRightBud(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD);
        builder.setUnableToConnectDescription(UNABLE_TO_CONNECT_DESCRIPTION);
        builder.setUnableToConnectTitle(UNABLE_TO_CONNECT_TITLE);
        builder.setUpdateCompanionAppDescription(UPDATE_COMPANION_APP_DESCRIPTION);
        builder.setWaitLaunchCompanionAppDescription(WAIT_LAUNCH_COMPANION_APP_DESCRIPTION);

        return builder.build();
    }
}
