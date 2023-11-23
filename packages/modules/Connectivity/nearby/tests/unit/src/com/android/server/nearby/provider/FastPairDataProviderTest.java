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

package com.android.server.nearby.provider;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.content.Context;
import android.nearby.FastPairDataProviderService;
import android.nearby.aidl.FastPairAccountDevicesMetadataRequestParcel;
import android.nearby.aidl.FastPairAntispoofKeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofKeyDeviceMetadataRequestParcel;
import android.nearby.aidl.FastPairDeviceMetadataParcel;
import android.nearby.aidl.FastPairEligibleAccountParcel;
import android.nearby.aidl.FastPairEligibleAccountsRequestParcel;
import android.nearby.aidl.FastPairManageAccountDeviceRequestParcel;
import android.nearby.aidl.FastPairManageAccountRequestParcel;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.nearby.fastpair.footprint.FastPairUploadInfo;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import service.proto.Cache;
import service.proto.FastPairString;
import service.proto.Rpcs;

public class FastPairDataProviderTest {

    private static final Account ACCOUNT = new Account("abc@google.com", "type1");
    private static final byte[] MODEL_ID = new byte[]{7, 9};
    private static final int BLE_TX_POWER = 5;
    private static final String CONNECT_SUCCESS_COMPANION_APP_INSTALLED =
            "CONNECT_SUCCESS_COMPANION_APP_INSTALLED";
    private static final String CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED =
            "CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED";
    private static final int DEVICE_TYPE = 1;
    private static final String DOWNLOAD_COMPANION_APP_DESCRIPTION =
            "DOWNLOAD_COMPANION_APP_DESCRIPTION";
    private static final String FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION =
            "FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION";
    private static final byte[] IMAGE = new byte[]{7, 9};
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
    private static final byte[] ACCOUNT_KEY = new byte[]{3};
    private static final byte[] SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS = new byte[]{2, 8};
    private static final byte[] ANTI_SPOOFING_KEY = new byte[]{4, 5, 6};
    private static final String ACTION_URL = "ACTION_URL";
    private static final String APP_NAME = "APP_NAME";
    private static final byte[] AUTHENTICATION_PUBLIC_KEY_SEC_P256R1 = new byte[]{5, 7};
    private static final String DESCRIPTION = "DESCRIPTION";
    private static final String DEVICE_NAME = "DEVICE_NAME";
    private static final String DISPLAY_URL = "DISPLAY_URL";
    private static final long FIRST_OBSERVATION_TIMESTAMP_MILLIS = 8393L;
    private static final String ICON_FIFE_URL = "ICON_FIFE_URL";
    private static final byte[] ICON_PNG = new byte[]{2, 5};
    private static final String ID = "ID";
    private static final long LAST_OBSERVATION_TIMESTAMP_MILLIS = 934234L;
    private static final String MAC_ADDRESS = "MAC_ADDRESS";
    private static final String NAME = "NAME";
    private static final String PACKAGE_NAME = "PACKAGE_NAME";
    private static final long PENDING_APP_INSTALL_TIMESTAMP_MILLIS = 832393L;
    private static final int RSSI = 9;
    private static final String TITLE = "TITLE";
    private static final String TRIGGER_ID = "TRIGGER_ID";
    private static final int TX_POWER = 63;

    @Mock ProxyFastPairDataProvider mProxyFastPairDataProvider;

    FastPairDataProvider mFastPairDataProvider;
    FastPairEligibleAccountParcel[] mFastPairEligibleAccountParcels =
            { genHappyPathFastPairEligibleAccountParcel() };
    FastPairAntispoofKeyDeviceMetadataParcel mFastPairAntispoofKeyDeviceMetadataParcel =
            genHappyPathFastPairAntispoofKeyDeviceMetadataParcel();
    FastPairUploadInfo mFastPairUploadInfo = genHappyPathFastPairUploadInfo();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mFastPairDataProvider = FastPairDataProvider.init(context);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testFailurePath_throwsException() throws IllegalStateException {
        mFastPairDataProvider = FastPairDataProvider.getInstance();
        assertThrows(
                IllegalStateException.class,
                () -> {
                    mFastPairDataProvider.loadFastPairEligibleAccounts(); });
        assertThrows(
                IllegalStateException.class,
                () -> {
                    mFastPairDataProvider.loadFastPairAntispoofKeyDeviceMetadata(MODEL_ID); });
        assertThrows(
                IllegalStateException.class,
                () -> {
                    mFastPairDataProvider.loadFastPairDeviceWithAccountKey(ACCOUNT); });
        assertThrows(
                IllegalStateException.class,
                () -> {
                    mFastPairDataProvider.loadFastPairDeviceWithAccountKey(
                            ACCOUNT, ImmutableList.of()); });
        assertThrows(
                IllegalStateException.class,
                () -> {
                    mFastPairDataProvider.optIn(ACCOUNT); });
        assertThrows(
                IllegalStateException.class,
                () -> {
                    mFastPairDataProvider.upload(ACCOUNT, mFastPairUploadInfo); });
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testLoadFastPairAntispoofKeyDeviceMetadata_receivesResponse()  {
        mFastPairDataProvider.setProxyDataProvider(mProxyFastPairDataProvider);
        when(mProxyFastPairDataProvider.loadFastPairAntispoofKeyDeviceMetadata(any()))
                .thenReturn(mFastPairAntispoofKeyDeviceMetadataParcel);

        mFastPairDataProvider.loadFastPairAntispoofKeyDeviceMetadata(MODEL_ID);
        ArgumentCaptor<FastPairAntispoofKeyDeviceMetadataRequestParcel> captor =
                ArgumentCaptor.forClass(FastPairAntispoofKeyDeviceMetadataRequestParcel.class);
        verify(mProxyFastPairDataProvider).loadFastPairAntispoofKeyDeviceMetadata(captor.capture());
        assertThat(captor.getValue().modelId).isSameInstanceAs(MODEL_ID);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testOptIn_finishesSuccessfully()  {
        mFastPairDataProvider.setProxyDataProvider(mProxyFastPairDataProvider);
        doNothing().when(mProxyFastPairDataProvider).manageFastPairAccount(any());
        mFastPairDataProvider.optIn(ACCOUNT);
        ArgumentCaptor<FastPairManageAccountRequestParcel> captor =
                ArgumentCaptor.forClass(FastPairManageAccountRequestParcel.class);
        verify(mProxyFastPairDataProvider).manageFastPairAccount(captor.capture());
        assertThat(captor.getValue().account).isSameInstanceAs(ACCOUNT);
        assertThat(captor.getValue().requestType).isEqualTo(
                FastPairDataProviderService.MANAGE_REQUEST_ADD);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testUpload_finishesSuccessfully()  {
        mFastPairDataProvider.setProxyDataProvider(mProxyFastPairDataProvider);
        doNothing().when(mProxyFastPairDataProvider).manageFastPairAccountDevice(any());
        mFastPairDataProvider.upload(ACCOUNT, mFastPairUploadInfo);
        ArgumentCaptor<FastPairManageAccountDeviceRequestParcel> captor =
                ArgumentCaptor.forClass(FastPairManageAccountDeviceRequestParcel.class);
        verify(mProxyFastPairDataProvider).manageFastPairAccountDevice(captor.capture());
        assertThat(captor.getValue().account).isSameInstanceAs(ACCOUNT);
        assertThat(captor.getValue().requestType).isEqualTo(
                FastPairDataProviderService.MANAGE_REQUEST_ADD);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testLoadFastPairEligibleAccounts_receivesOneAccount()  {
        mFastPairDataProvider.setProxyDataProvider(mProxyFastPairDataProvider);
        when(mProxyFastPairDataProvider.loadFastPairEligibleAccounts(any()))
                .thenReturn(mFastPairEligibleAccountParcels);
        assertThat(mFastPairDataProvider.loadFastPairEligibleAccounts().size())
                .isEqualTo(1);
        ArgumentCaptor<FastPairEligibleAccountsRequestParcel> captor =
                ArgumentCaptor.forClass(FastPairEligibleAccountsRequestParcel.class);
        verify(mProxyFastPairDataProvider).loadFastPairEligibleAccounts(captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testLoadFastPairDeviceWithAccountKey_finishesSuccessfully()  {
        mFastPairDataProvider.setProxyDataProvider(mProxyFastPairDataProvider);
        when(mProxyFastPairDataProvider.loadFastPairAccountDevicesMetadata(any()))
                .thenReturn(null);

        mFastPairDataProvider.loadFastPairDeviceWithAccountKey(ACCOUNT);
        ArgumentCaptor<FastPairAccountDevicesMetadataRequestParcel> captor =
                ArgumentCaptor.forClass(FastPairAccountDevicesMetadataRequestParcel.class);
        verify(mProxyFastPairDataProvider).loadFastPairAccountDevicesMetadata(captor.capture());
        assertThat(captor.getValue().account).isSameInstanceAs(ACCOUNT);
        assertThat(captor.getValue().deviceAccountKeys).isEmpty();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testLoadFastPairDeviceWithAccountKeyDeviceAccountKeys_finishesSuccessfully()  {
        mFastPairDataProvider.setProxyDataProvider(mProxyFastPairDataProvider);
        when(mProxyFastPairDataProvider.loadFastPairAccountDevicesMetadata(any()))
                .thenReturn(null);

        mFastPairDataProvider.loadFastPairDeviceWithAccountKey(
                ACCOUNT, ImmutableList.of(ACCOUNT_KEY));
        ArgumentCaptor<FastPairAccountDevicesMetadataRequestParcel> captor =
                ArgumentCaptor.forClass(FastPairAccountDevicesMetadataRequestParcel.class);
        verify(mProxyFastPairDataProvider).loadFastPairAccountDevicesMetadata(captor.capture());
        assertThat(captor.getValue().account).isSameInstanceAs(ACCOUNT);
        assertThat(captor.getValue().deviceAccountKeys.length).isEqualTo(1);
        assertThat(captor.getValue().deviceAccountKeys[0].byteArray).isSameInstanceAs(ACCOUNT_KEY);
    }

    private static FastPairEligibleAccountParcel genHappyPathFastPairEligibleAccountParcel() {
        FastPairEligibleAccountParcel parcel = new FastPairEligibleAccountParcel();
        parcel.account = ACCOUNT;
        parcel.optIn = true;

        return parcel;
    }

    private static FastPairAntispoofKeyDeviceMetadataParcel
                genHappyPathFastPairAntispoofKeyDeviceMetadataParcel() {
        FastPairAntispoofKeyDeviceMetadataParcel parcel =
                new FastPairAntispoofKeyDeviceMetadataParcel();
        parcel.antispoofPublicKey = ANTI_SPOOFING_KEY;
        parcel.deviceMetadata = genHappyPathFastPairDeviceMetadataParcel();

        return parcel;
    }

    private static FastPairDeviceMetadataParcel genHappyPathFastPairDeviceMetadataParcel() {
        FastPairDeviceMetadataParcel parcel = new FastPairDeviceMetadataParcel();

        parcel.bleTxPower = BLE_TX_POWER;
        parcel.connectSuccessCompanionAppInstalled = CONNECT_SUCCESS_COMPANION_APP_INSTALLED;
        parcel.connectSuccessCompanionAppNotInstalled =
                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED;
        parcel.deviceType = DEVICE_TYPE;
        parcel.downloadCompanionAppDescription = DOWNLOAD_COMPANION_APP_DESCRIPTION;
        parcel.failConnectGoToSettingsDescription = FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION;
        parcel.image = IMAGE;
        parcel.imageUrl = IMAGE_URL;
        parcel.initialNotificationDescription = INITIAL_NOTIFICATION_DESCRIPTION;
        parcel.initialNotificationDescriptionNoAccount =
                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT;
        parcel.initialPairingDescription = INITIAL_PAIRING_DESCRIPTION;
        parcel.intentUri = INTENT_URI;
        parcel.name = NAME;
        parcel.openCompanionAppDescription = OPEN_COMPANION_APP_DESCRIPTION;
        parcel.retroactivePairingDescription = RETRO_ACTIVE_PAIRING_DESCRIPTION;
        parcel.subsequentPairingDescription = SUBSEQUENT_PAIRING_DESCRIPTION;
        parcel.triggerDistance = TRIGGER_DISTANCE;
        parcel.trueWirelessImageUrlCase = TRUE_WIRELESS_IMAGE_URL_CASE;
        parcel.trueWirelessImageUrlLeftBud = TRUE_WIRELESS_IMAGE_URL_LEFT_BUD;
        parcel.trueWirelessImageUrlRightBud = TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD;
        parcel.unableToConnectDescription = UNABLE_TO_CONNECT_DESCRIPTION;
        parcel.unableToConnectTitle = UNABLE_TO_CONNECT_TITLE;
        parcel.updateCompanionAppDescription = UPDATE_COMPANION_APP_DESCRIPTION;
        parcel.waitLaunchCompanionAppDescription = WAIT_LAUNCH_COMPANION_APP_DESCRIPTION;

        return parcel;
    }

    private static Cache.StoredDiscoveryItem genHappyPathStoredDiscoveryItem() {
        Cache.StoredDiscoveryItem.Builder storedDiscoveryItemBuilder =
                Cache.StoredDiscoveryItem.newBuilder();
        storedDiscoveryItemBuilder.setActionUrl(ACTION_URL);
        storedDiscoveryItemBuilder.setActionUrlType(Cache.ResolvedUrlType.WEBPAGE);
        storedDiscoveryItemBuilder.setAppName(APP_NAME);
        storedDiscoveryItemBuilder.setAuthenticationPublicKeySecp256R1(
                ByteString.copyFrom(AUTHENTICATION_PUBLIC_KEY_SEC_P256R1));
        storedDiscoveryItemBuilder.setDescription(DESCRIPTION);
        storedDiscoveryItemBuilder.setDeviceName(DEVICE_NAME);
        storedDiscoveryItemBuilder.setDisplayUrl(DISPLAY_URL);
        storedDiscoveryItemBuilder.setFirstObservationTimestampMillis(
                FIRST_OBSERVATION_TIMESTAMP_MILLIS);
        storedDiscoveryItemBuilder.setIconFifeUrl(ICON_FIFE_URL);
        storedDiscoveryItemBuilder.setIconPng(ByteString.copyFrom(ICON_PNG));
        storedDiscoveryItemBuilder.setId(ID);
        storedDiscoveryItemBuilder.setLastObservationTimestampMillis(
                LAST_OBSERVATION_TIMESTAMP_MILLIS);
        storedDiscoveryItemBuilder.setMacAddress(MAC_ADDRESS);
        storedDiscoveryItemBuilder.setPackageName(PACKAGE_NAME);
        storedDiscoveryItemBuilder.setPendingAppInstallTimestampMillis(
                PENDING_APP_INSTALL_TIMESTAMP_MILLIS);
        storedDiscoveryItemBuilder.setRssi(RSSI);
        storedDiscoveryItemBuilder.setState(Cache.StoredDiscoveryItem.State.STATE_ENABLED);
        storedDiscoveryItemBuilder.setTitle(TITLE);
        storedDiscoveryItemBuilder.setTriggerId(TRIGGER_ID);
        storedDiscoveryItemBuilder.setTxPower(TX_POWER);

        FastPairString.FastPairStrings.Builder stringsBuilder =
                FastPairString.FastPairStrings.newBuilder();
        stringsBuilder.setPairingFinishedCompanionAppInstalled(
                CONNECT_SUCCESS_COMPANION_APP_INSTALLED);
        stringsBuilder.setPairingFinishedCompanionAppNotInstalled(
                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED);
        stringsBuilder.setPairingFailDescription(
                FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION);
        stringsBuilder.setTapToPairWithAccount(
                INITIAL_NOTIFICATION_DESCRIPTION);
        stringsBuilder.setTapToPairWithoutAccount(
                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT);
        stringsBuilder.setInitialPairingDescription(INITIAL_PAIRING_DESCRIPTION);
        stringsBuilder.setRetroactivePairingDescription(RETRO_ACTIVE_PAIRING_DESCRIPTION);
        stringsBuilder.setSubsequentPairingDescription(SUBSEQUENT_PAIRING_DESCRIPTION);
        stringsBuilder.setWaitAppLaunchDescription(WAIT_LAUNCH_COMPANION_APP_DESCRIPTION);
        storedDiscoveryItemBuilder.setFastPairStrings(stringsBuilder.build());

        Cache.FastPairInformation.Builder fpInformationBuilder =
                Cache.FastPairInformation.newBuilder();
        Rpcs.TrueWirelessHeadsetImages.Builder imagesBuilder =
                Rpcs.TrueWirelessHeadsetImages.newBuilder();
        imagesBuilder.setCaseUrl(TRUE_WIRELESS_IMAGE_URL_CASE);
        imagesBuilder.setLeftBudUrl(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD);
        imagesBuilder.setRightBudUrl(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD);
        fpInformationBuilder.setTrueWirelessImages(imagesBuilder.build());
        fpInformationBuilder.setDeviceType(Rpcs.DeviceType.HEADPHONES);

        storedDiscoveryItemBuilder.setFastPairInformation(fpInformationBuilder.build());
        storedDiscoveryItemBuilder.setTxPower(TX_POWER);

        storedDiscoveryItemBuilder.setIconPng(ByteString.copyFrom(ICON_PNG));
        storedDiscoveryItemBuilder.setIconFifeUrl(ICON_FIFE_URL);
        storedDiscoveryItemBuilder.setActionUrl(ACTION_URL);

        return storedDiscoveryItemBuilder.build();
    }

    private static FastPairUploadInfo genHappyPathFastPairUploadInfo() {
        return new FastPairUploadInfo(
                genHappyPathStoredDiscoveryItem(),
                ByteString.copyFrom(ACCOUNT_KEY),
                ByteString.copyFrom(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS));
    }
}
