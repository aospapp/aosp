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

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.nearby.halfsheet.R;
import com.android.server.nearby.fastpair.HalfSheetResources;
import com.android.server.nearby.fastpair.PackageUtils;

/** Wrapper class for Fast Pair specific logic for notification builder. */
public class FastPairNotificationBuilder extends Notification.Builder {

    @VisibleForTesting
    static final String NOTIFICATION_OVERRIDE_NAME_EXTRA = "android.substName";
    final String mPackageName;
    final Context mContext;
    final HalfSheetResources mResources;

    public FastPairNotificationBuilder(Context context, String channelId) {
        super(context, channelId);
        this.mContext = context;
        this.mPackageName = PackageUtils.getHalfSheetApkPkgName(context);
        this.mResources = new HalfSheetResources(context);
    }

    /**
     * If the flag is enabled, all the devices notification should use "Devices" as the source name,
     * and links/Apps uses "Nearby". If the flag is not enabled, all notifications use "Nearby" as
     * source name.
     */
    public FastPairNotificationBuilder setIsDevice(boolean isDevice) {
        Bundle extras = new Bundle();
        String notificationOverrideName =
                isDevice
                        ? mResources.get().getString(R.string.common_devices)
                        : mResources.get().getString(R.string.common_nearby_title);
        extras.putString(NOTIFICATION_OVERRIDE_NAME_EXTRA, notificationOverrideName);
        addExtras(extras);
        return this;
    }

    /** Set the "ticker" text which is sent to accessibility services. */
    public FastPairNotificationBuilder setTickerForAccessibility(String tickerText) {
        // On Lollipop and above, setTicker() tells Accessibility what to say about the notification
        // (e.g. this is what gets announced when a HUN appears).
        setTicker(tickerText);
        return this;
    }
}
