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

package com.android.server.nearby.fastpair.cache;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.FastPairManager;
import com.android.server.nearby.fastpair.testing.FakeDiscoveryItems;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import service.proto.Cache;

/** Unit tests for {@link DiscoveryItem} */
public class DiscoveryItemTest {
    private static final String DEFAULT_MAC_ADDRESS = "00:11:22:33:44:55";
    private static final String DEFAULT_DESCRIPITON = "description";
    private static final long DEFAULT_TIMESTAMP = 1000000000L;
    private static final String DEFAULT_TITLE = "title";
    private static final String APP_NAME = "app_name";
    private static final String ACTION_URL =
            "intent:#Intent;action=com.android.server.nearby:ACTION_FAST_PAIR;"
            + "package=com.google.android.gms;"
            + "component=com.google.android.gms/"
            + ".nearby.discovery.service.DiscoveryService;end";
    private static final String DISPLAY_URL = "DISPLAY_URL";
    private static final String TRIGGER_ID = "trigger.id";
    private static final String FAST_PAIR_ID = "id";
    private static final int RSSI = -80;
    private static final int TX_POWER = -10;

    @Mock private Context mContext;
    private LocatorContextWrapper mLocatorContextWrapper;
    private FastPairCacheManager mFastPairCacheManager;
    private FastPairManager mFastPairManager;
    private DiscoveryItem mDiscoveryItem;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mLocatorContextWrapper = new LocatorContextWrapper(mContext);
        mFastPairManager = new FastPairManager(mLocatorContextWrapper);
        mFastPairCacheManager = mLocatorContextWrapper.getLocator().get(FastPairCacheManager.class);
        when(mContext.getContentResolver()).thenReturn(
                InstrumentationRegistry.getInstrumentation().getContext().getContentResolver());
        mDiscoveryItem =
                FakeDiscoveryItems.newFastPairDiscoveryItem(mLocatorContextWrapper);
    }

    @Test
    public void testMultipleFields() {
        assertThat(mDiscoveryItem.getId()).isEqualTo(FAST_PAIR_ID);
        assertThat(mDiscoveryItem.getDescription()).isEqualTo(DEFAULT_DESCRIPITON);
        assertThat(mDiscoveryItem.getDisplayUrl()).isEqualTo(DISPLAY_URL);
        assertThat(mDiscoveryItem.getTriggerId()).isEqualTo(TRIGGER_ID);
        assertThat(mDiscoveryItem.getMacAddress()).isEqualTo(DEFAULT_MAC_ADDRESS);
        assertThat(
                mDiscoveryItem.getFirstObservationTimestampMillis()).isEqualTo(DEFAULT_TIMESTAMP);
        assertThat(
                mDiscoveryItem.getLastObservationTimestampMillis()).isEqualTo(DEFAULT_TIMESTAMP);
        assertThat(mDiscoveryItem.getActionUrl()).isEqualTo(ACTION_URL);
        assertThat(mDiscoveryItem.getAppName()).isEqualTo(APP_NAME);
        assertThat(mDiscoveryItem.getRssi()).isEqualTo(RSSI);
        assertThat(mDiscoveryItem.getTxPower()).isEqualTo(TX_POWER);
        assertThat(mDiscoveryItem.getFastPairInformation()).isNull();
        assertThat(mDiscoveryItem.getFastPairSecretKey()).isNull();
        assertThat(mDiscoveryItem.getIcon()).isNull();
        assertThat(mDiscoveryItem.getIconFifeUrl()).isNotNull();
        assertThat(mDiscoveryItem.getState()).isNotNull();
        assertThat(mDiscoveryItem.getTitle()).isNotNull();
        assertThat(mDiscoveryItem.isApp()).isFalse();
        assertThat(mDiscoveryItem.isDeletable(
                100000L, 0L)).isTrue();
        assertThat(mDiscoveryItem.isDeviceType(Cache.NearbyType.NEARBY_CHROMECAST)).isTrue();
        assertThat(mDiscoveryItem.isExpired(
                100000L, 0L)).isTrue();
        assertThat(mDiscoveryItem.isFastPair()).isTrue();
        assertThat(mDiscoveryItem.isPendingAppInstallValid(5)).isTrue();
        assertThat(mDiscoveryItem.isPendingAppInstallValid(5,
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID,  null,
                TRIGGER_ID,  DEFAULT_MAC_ADDRESS,  "", RSSI, TX_POWER))).isTrue();
        assertThat(mDiscoveryItem.isTypeEnabled(Cache.NearbyType.NEARBY_CHROMECAST)).isTrue();
        assertThat(mDiscoveryItem.toString()).isNotNull();
    }

    @Test
    public void isMuted() {
        assertThat(mDiscoveryItem.isMuted()).isFalse();
    }

    @Test
    public void itemWithDefaultDescription_shouldShowUp() {
        assertThat(mDiscoveryItem.isReadyForDisplay()).isFalse();

        // Null description should not show up.
        mDiscoveryItem.setStoredItemForTest(DiscoveryItem.newStoredDiscoveryItem());
        mDiscoveryItem.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID,  null,
                        TRIGGER_ID,  DEFAULT_MAC_ADDRESS,  "", RSSI, TX_POWER));
        assertThat(mDiscoveryItem.isReadyForDisplay()).isFalse();

        // Empty description should not show up.
        mDiscoveryItem.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID,  "",
                        TRIGGER_ID,  DEFAULT_MAC_ADDRESS, DEFAULT_TITLE, RSSI, TX_POWER));
        assertThat(mDiscoveryItem.isReadyForDisplay()).isFalse();
    }

    @Test
    public void itemWithEmptyTitle_shouldNotShowUp() {
        // Null title should not show up.
        assertThat(mDiscoveryItem.isReadyForDisplay()).isFalse();
        // Empty title should not show up.
        mDiscoveryItem.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID, DEFAULT_DESCRIPITON,
                        TRIGGER_ID, DEFAULT_MAC_ADDRESS, "", RSSI, TX_POWER));
        assertThat(mDiscoveryItem.isReadyForDisplay()).isFalse();

        // Null title should not show up.
        mDiscoveryItem.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID, DEFAULT_DESCRIPITON,
                        TRIGGER_ID, DEFAULT_MAC_ADDRESS, null, RSSI, TX_POWER));
        assertThat(mDiscoveryItem.isReadyForDisplay()).isFalse();
    }

    @Test
    public void itemWithRssiAndTxPower_shouldHaveCorrectEstimatedDistance() {
        assertThat(mDiscoveryItem.getEstimatedDistance()).isWithin(0.01).of(28.18);
    }

    @Test
    public void itemWithoutRssiOrTxPower_shouldNotHaveEstimatedDistance() {
        mDiscoveryItem.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID, DEFAULT_DESCRIPITON,
                        TRIGGER_ID, DEFAULT_MAC_ADDRESS, "", 0, 0));
        assertThat(mDiscoveryItem.getEstimatedDistance()).isWithin(0.01).of(0);
    }

    @Test
    public void getUiHashCode_differentAddress_differentHash() {
        mDiscoveryItem.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID, DEFAULT_DESCRIPITON,
                        TRIGGER_ID, "00:11:22:33:44:55", "", RSSI, TX_POWER));
        DiscoveryItem compareTo =
                FakeDiscoveryItems.newFastPairDiscoveryItem(mLocatorContextWrapper);
        compareTo.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID, DEFAULT_DESCRIPITON,
                        TRIGGER_ID, "55:44:33:22:11:00", "", RSSI, TX_POWER));
        assertThat(mDiscoveryItem.getUiHashCode()).isNotEqualTo(compareTo.getUiHashCode());
    }

    @Test
    public void getUiHashCode_sameAddress_sameHash() {
        mDiscoveryItem.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID, DEFAULT_DESCRIPITON,
                        TRIGGER_ID, "00:11:22:33:44:55", "", RSSI, TX_POWER));
        DiscoveryItem compareTo =
                FakeDiscoveryItems.newFastPairDiscoveryItem(mLocatorContextWrapper);
        compareTo.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID, DEFAULT_DESCRIPITON,
                        TRIGGER_ID, "00:11:22:33:44:55", "", RSSI, TX_POWER));
        assertThat(mDiscoveryItem.getUiHashCode()).isEqualTo(compareTo.getUiHashCode());
    }

    @Test
    public void isFastPair() {
        DiscoveryItem fastPairItem =
                FakeDiscoveryItems.newFastPairDiscoveryItem(mLocatorContextWrapper);
        assertThat(fastPairItem.isFastPair()).isTrue();
    }

    @Test
    public void testEqual() {
        DiscoveryItem fastPairItem =
                FakeDiscoveryItems.newFastPairDiscoveryItem(mLocatorContextWrapper);
        assertThat(mDiscoveryItem.equals(fastPairItem)).isTrue();
    }

    @Test
    public void testCompareTo() {
        DiscoveryItem fastPairItem =
                FakeDiscoveryItems.newFastPairDiscoveryItem(mLocatorContextWrapper);
        assertThat(mDiscoveryItem.compareTo(fastPairItem)).isEqualTo(0);
    }


    @Test
    public void testCopyOfStoredItem() {
        DiscoveryItem fastPairItem =
                FakeDiscoveryItems.newFastPairDiscoveryItem(mLocatorContextWrapper);
        fastPairItem.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID, DEFAULT_DESCRIPITON,
                        TRIGGER_ID, "00:11:22:33:44:55", "", RSSI, TX_POWER));
        assertThat(mDiscoveryItem.equals(fastPairItem)).isFalse();
        fastPairItem.setStoredItemForTest(mDiscoveryItem.getCopyOfStoredItem());
        assertThat(mDiscoveryItem.equals(fastPairItem)).isTrue();
    }

    @Test
    public void testStoredItemForTest() {
        DiscoveryItem fastPairItem =
                FakeDiscoveryItems.newFastPairDiscoveryItem(mLocatorContextWrapper);
        fastPairItem.setStoredItemForTest(
                FakeDiscoveryItems.newFastPairDeviceStoredItem(FAST_PAIR_ID, DEFAULT_DESCRIPITON,
                        TRIGGER_ID, "00:11:22:33:44:55", "", RSSI, TX_POWER));
        assertThat(mDiscoveryItem.equals(fastPairItem)).isFalse();
        fastPairItem.setStoredItemForTest(mDiscoveryItem.getStoredItemForTest());
        assertThat(mDiscoveryItem.equals(fastPairItem)).isTrue();
    }
}
