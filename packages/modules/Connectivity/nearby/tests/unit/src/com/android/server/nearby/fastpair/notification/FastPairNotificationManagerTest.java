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

import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Resources;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.HalfSheetResources;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import service.proto.Cache;

public class FastPairNotificationManagerTest {

    @Mock
    private Context mContext;
    @Mock
    NotificationManager mNotificationManager;
    @Mock
    Resources mResources;
    @Mock
    private LocatorContextWrapper mLocatorContextWrapper;
    @Mock
    private Locator mLocator;

    private static final int NOTIFICATION_ID = 1;
    private static final int BATTERY_LEVEL = 1;
    private static final String DEVICE_NAME = "deviceName";
    private FastPairNotificationManager mFastPairNotificationManager;
    private DiscoveryItem mItem;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getContentResolver()).thenReturn(
                InstrumentationRegistry.getInstrumentation().getContext().getContentResolver());
        when(mLocatorContextWrapper.getResources()).thenReturn(mResources);
        when(mLocatorContextWrapper.getLocator()).thenReturn(mLocator);
        HalfSheetResources.setResourcesContextForTest(mLocatorContextWrapper);
        // Real context is needed
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        FastPairNotificationManager fastPairNotificationManager =
        mFastPairNotificationManager =
                new FastPairNotificationManager(context, NOTIFICATION_ID, mNotificationManager,
                        new HalfSheetResources(mLocatorContextWrapper));
        mLocator.overrideBindingForTest(FastPairNotificationManager.class,
                fastPairNotificationManager);

        mItem = new DiscoveryItem(mLocatorContextWrapper,
                Cache.StoredDiscoveryItem.newBuilder().setTitle("Device Name").build());
    }

    @Test
    public void  notifyPairingProcessDone() {
        mFastPairNotificationManager.notifyPairingProcessDone(true, true,
                "privateAddress", "publicAddress");
    }

    @Test
    public void  showConnectingNotification() {
        mFastPairNotificationManager.showConnectingNotification(mItem);
    }

    @Test
    public void   showPairingFailedNotification() {
        mFastPairNotificationManager
                .showPairingFailedNotification(mItem, new byte[]{1});
    }

    @Test
    public void  showPairingSucceededNotification() {
        mFastPairNotificationManager
                .showPairingSucceededNotification(mItem, BATTERY_LEVEL, DEVICE_NAME);
    }
}
