/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.car.statusicon.ui;

import static android.content.Intent.ACTION_USER_FOREGROUND;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.location.LocationManager;
import android.os.UserHandle;

import com.android.systemui.R;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.statusicon.StatusIconController;
import com.android.systemui.dagger.qualifiers.Main;

import javax.inject.Inject;

/**
 * A controller for the read-only icon that shows location active status.
 */
public class LocationStatusIconController extends StatusIconController {

    private static final String TAG = LocationStatusIconController.class.getSimpleName();
    private static final IntentFilter INTENT_FILTER_LOCATION_MODE_CHANGED = new IntentFilter(
            LocationManager.MODE_CHANGED_ACTION);
    private static final IntentFilter INTENT_FILTER_USER_CHANGED = new IntentFilter(
            ACTION_USER_FOREGROUND);

    private final Context mContext;
    private final LocationManager mLocationManager;
    private final CarServiceProvider mCarServiceProvider;

    private boolean mIsLocationActive;

    private final BroadcastReceiver mLocationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateIconVisibilityForCurrentUser();
        }
    };

    private final BroadcastReceiver mUserChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateIconVisibilityForCurrentUser();
        }
    };

    @Inject
    LocationStatusIconController(
            Context context,
            @Main Resources resources,
            CarServiceProvider carServiceProvider) {

        mContext = context;
        mLocationManager = context.getSystemService(LocationManager.class);
        mCarServiceProvider = carServiceProvider;

        context.registerReceiverForAllUsers(mLocationReceiver, INTENT_FILTER_LOCATION_MODE_CHANGED,
                /* broadcastPermission= */ null, /* scheduler= */ null);
        context.registerReceiverForAllUsers(mUserChangeReceiver, INTENT_FILTER_USER_CHANGED,
                /* broadcastPermission= */ null,
                /* scheduler= */ null);
        setIconDrawableToDisplay(resources.getDrawable(R.drawable.ic_location, context.getTheme()));
        updateIconVisibilityForCurrentUser();
    }

    @Override
    protected void updateStatus() {
        setIconVisibility(mIsLocationActive);
        onStatusUpdated();
    }

    private void updateIconVisibilityForCurrentUser() {
        int fgUserId = ActivityManager.getCurrentUser();
        UserHandle fgUserHandle = UserHandle.of(fgUserId);
        mIsLocationActive = mLocationManager.isLocationEnabledForUser(fgUserHandle);
        updateStatus();
    }
}
