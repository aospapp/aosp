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

package com.android.server.nearby.fastpair.notification;

import static com.android.server.nearby.fastpair.notification.FastPairNotificationManager.DEVICES_WITHIN_REACH_CHANNEL_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.nearby.halfsheet.R;
import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.HalfSheetResources;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import service.proto.Cache;

public class FastPairNotificationsTest {
    private static final Cache.StoredDiscoveryItem SCAN_FAST_PAIR_ITEM =
            Cache.StoredDiscoveryItem.newBuilder()
                    .setDeviceName("TestName")
                    .build();
    private static final String STRING_DEVICE = "Devices";
    private static final String STRING_NEARBY = "Nearby";
    private static final String STRING_YOUR_DEVICE = "Your saved device is available";
    private static final String STRING_CONNECTING = "Connecting";
    private static final String STRING_DEVICE_READY = "Device connected";

    private static final byte[] ACCOUNT_KEY = new byte[]{0x01, 0x02};
    @Mock
    LocatorContextWrapper mContextWrapper;
    @Mock
    Locator mLocator;
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private Drawable mDrawable;

    private DiscoveryItem mItem;
    private HalfSheetResources mHalfSheetResources;
    private FastPairNotifications mFastPairNotifications;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mHalfSheetResources = new HalfSheetResources(mContext);
        Context realContext = InstrumentationRegistry.getInstrumentation().getContext();
        mFastPairNotifications =
                new FastPairNotifications(realContext, mHalfSheetResources);
        HalfSheetResources.setResourcesContextForTest(mContext);

        when(mContextWrapper.getLocator()).thenReturn(mLocator);
        when(mContext.getResources()).thenReturn(mResources);

        when(mResources.getString(eq(R.string.common_devices))).thenReturn(STRING_DEVICE);
        when(mResources.getString(eq(R.string.common_nearby_title))).thenReturn(STRING_NEARBY);
        when(mResources.getString(eq(R.string.fast_pair_your_device)))
                .thenReturn(STRING_YOUR_DEVICE);
        when(mResources.getString(eq(R.string.common_connecting))).thenReturn(STRING_CONNECTING);
        when(mResources.getString(eq(R.string.fast_pair_device_ready)))
                .thenReturn(STRING_DEVICE_READY);
        when(mResources.getDrawable(eq(R.drawable.quantum_ic_devices_other_vd_theme_24), any()))
                .thenReturn(mDrawable);

        mItem = new DiscoveryItem(mContextWrapper, SCAN_FAST_PAIR_ITEM);
    }

    @Test
    public void verify_progressNotification() {
        Notification notification = mFastPairNotifications.progressNotification(mItem);

        assertThat(notification.getChannelId()).isEqualTo(DEVICES_WITHIN_REACH_CHANNEL_ID);
        assertThat(notification.getSmallIcon().getResId())
                .isEqualTo(R.drawable.quantum_ic_devices_other_vd_theme_24);
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_PROGRESS);
        assertThat(notification.tickerText.toString()).isEqualTo(STRING_CONNECTING);
    }

    @Test
    public void verify_discoveryNotification() {
        Notification notification =
                mFastPairNotifications.discoveryNotification(mItem, ACCOUNT_KEY);

        assertThat(notification.getChannelId()).isEqualTo(DEVICES_WITHIN_REACH_CHANNEL_ID);
        assertThat(notification.getSmallIcon().getResId())
                .isEqualTo(R.drawable.quantum_ic_devices_other_vd_theme_24);
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_RECOMMENDATION);
    }

    @Test
    public void verify_succeededNotification() {
        Notification notification = mFastPairNotifications
                .pairingSucceededNotification(101, null, "model name", mItem);

        assertThat(notification.getChannelId()).isEqualTo(DEVICES_WITHIN_REACH_CHANNEL_ID);
        assertThat(notification.getSmallIcon().getResId())
                .isEqualTo(R.drawable.quantum_ic_devices_other_vd_theme_24);
        assertThat(notification.tickerText.toString()).isEqualTo(STRING_DEVICE_READY);
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_STATUS);
    }

    @Test
    public void verify_failedNotification() {
        Notification notification =
                mFastPairNotifications.showPairingFailedNotification(mItem, ACCOUNT_KEY);

        assertThat(notification.getChannelId()).isEqualTo(DEVICES_WITHIN_REACH_CHANNEL_ID);
        assertThat(notification.getSmallIcon().getResId())
                .isEqualTo(R.drawable.quantum_ic_devices_other_vd_theme_24);
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_ERROR);
    }
}
