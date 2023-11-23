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

package com.android.server.nearby.fastpair.testing;

import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;

import service.proto.Cache;

public class FakeDiscoveryItems {
    private static final String DEFAULT_MAC_ADDRESS = "00:11:22:33:44:55";
    private static final long DEFAULT_TIMESTAMP = 1000000000L;
    private static final String DEFAULT_DESCRIPITON = "description";
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
    public static DiscoveryItem newFastPairDiscoveryItem(LocatorContextWrapper contextWrapper) {
        return new DiscoveryItem(contextWrapper, newFastPairDeviceStoredItem());
    }

    public static Cache.StoredDiscoveryItem newFastPairDeviceStoredItem() {
        return newFastPairDeviceStoredItem(TRIGGER_ID);
    }

    public static Cache.StoredDiscoveryItem newFastPairDeviceStoredItem(String triggerId) {
        Cache.StoredDiscoveryItem.Builder item = Cache.StoredDiscoveryItem.newBuilder();
        item.setState(Cache.StoredDiscoveryItem.State.STATE_ENABLED);
        item.setId(FAST_PAIR_ID);
        item.setDescription(DEFAULT_DESCRIPITON);
        item.setTriggerId(triggerId);
        item.setMacAddress(DEFAULT_MAC_ADDRESS);
        item.setFirstObservationTimestampMillis(DEFAULT_TIMESTAMP);
        item.setLastObservationTimestampMillis(DEFAULT_TIMESTAMP);
        item.setActionUrl(ACTION_URL);
        item.setAppName(APP_NAME);
        item.setRssi(RSSI);
        item.setTxPower(TX_POWER);
        item.setDisplayUrl(DISPLAY_URL);
        return item.build();
    }

    public static Cache.StoredDiscoveryItem newFastPairDeviceStoredItem(String id,
            String description, String triggerId, String macAddress, String title,
            int rssi, int txPower) {
        Cache.StoredDiscoveryItem.Builder item = Cache.StoredDiscoveryItem.newBuilder();
        item.setState(Cache.StoredDiscoveryItem.State.STATE_ENABLED);
        if (id != null) {
            item.setId(id);
        }
        if (description != null) {
            item.setDescription(description);
        }
        if (triggerId != null) {
            item.setTriggerId(triggerId);
        }
        if (macAddress != null) {
            item.setMacAddress(macAddress);
        }
        if (title != null) {
            item.setTitle(title);
        }
        item.setRssi(rssi);
        item.setTxPower(txPower);
        return item.build();
    }
}
