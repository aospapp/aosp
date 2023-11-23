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

package com.android.systemui.car.systembar;

import static android.hardware.SensorPrivacyManager.Sensors.MICROPHONE;
import static android.hardware.SensorPrivacyManager.Sources.QS_TILE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.SensorPrivacyManager;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.privacy.MicPrivacyChip;
import com.android.systemui.car.privacy.MicQcPanel;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.privacy.PrivacyType;

import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

/**
 * Controls a Privacy Chip view in system icons.
 */
@SysUISingleton
public class PrivacyChipViewController implements MicQcPanel.MicSensorInfoProvider {
    private static final String TAG = "PrivacyChipViewContrllr";
    private static final boolean DEBUG = false;

    private final PrivacyItemController mPrivacyItemController;
    private final CarServiceProvider mCarServiceProvider;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final SensorPrivacyManager mSensorPrivacyManager;

    private Context mContext;
    private MicPrivacyChip mPrivacyChip;
    private Runnable mQsTileNotifyUpdateRunnable;
    private final SensorPrivacyManager.OnSensorPrivacyChangedListener
            mOnSensorPrivacyChangedListener = (sensor, sensorPrivacyEnabled) -> {
        if (mContext == null) {
            return;
        }
        // Since this is launched using a callback thread, its UI based elements need
        // to execute on main executor.
        mContext.getMainExecutor().execute(() -> {
            // We need to negate sensorPrivacyEnabled since when it is {@code true} it means
            // microphone has been toggled off.
            mPrivacyChip.setMicrophoneEnabled(/* isMicrophoneEnabled= */ !sensorPrivacyEnabled);
            mQsTileNotifyUpdateRunnable.run();
        });
    };
    private boolean mAllIndicatorsEnabled;
    private boolean mMicCameraIndicatorsEnabled;
    private boolean mIsMicPrivacyChipVisible;
    private final PrivacyItemController.Callback mPicCallback =
            new PrivacyItemController.Callback() {
                @Override
                public void onPrivacyItemsChanged(@NonNull List<PrivacyItem> privacyItems) {
                    if (mPrivacyChip == null) {
                        return;
                    }

                    // Call QS Tile notify update runnable here so that QS tile can update when app
                    // usage is added/removed/updated
                    mQsTileNotifyUpdateRunnable.run();

                    boolean shouldShowMicPrivacyChip = isMicPartOfPrivacyItems(privacyItems);
                    if (mIsMicPrivacyChipVisible == shouldShowMicPrivacyChip) {
                        return;
                    }

                    mIsMicPrivacyChipVisible = shouldShowMicPrivacyChip;
                    setChipVisibility(shouldShowMicPrivacyChip);
                }

                @Override
                public void onFlagAllChanged(boolean enabled) {
                    onAllIndicatorsToggled(enabled);
                }

                @Override
                public void onFlagMicCameraChanged(boolean enabled) {
                    onMicCameraToggled(enabled);
                }

                private void onMicCameraToggled(boolean enabled) {
                    if (mMicCameraIndicatorsEnabled != enabled) {
                        mMicCameraIndicatorsEnabled = enabled;
                    }
                }

                private void onAllIndicatorsToggled(boolean enabled) {
                    if (mAllIndicatorsEnabled != enabled) {
                        mAllIndicatorsEnabled = enabled;
                    }
                }
            };
    private int mCurrentUserId;
    private final BroadcastReceiver mUserUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mPrivacyChip == null) {
                return;
            }
            if (!Intent.ACTION_USER_INFO_CHANGED.equals(intent.getAction())) {
                return;
            }
            int currentUserId = mCarDeviceProvisionedController.getCurrentUser();
            if (mCurrentUserId == currentUserId) {
                return;
            }

            setUser(currentUserId);
        }
    };
    private final BroadcastReceiver mUserChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mPrivacyChip == null) {
                return;
            }
            int currentUserId = mCarDeviceProvisionedController.getCurrentUser();
            if (mCurrentUserId == currentUserId) {
                return;
            }
            setUser(currentUserId);
        }
    };

    @Inject
    public PrivacyChipViewController(Context context, PrivacyItemController privacyItemController,
            CarServiceProvider carServiceProvider, BroadcastDispatcher broadcastDispatcher,
            SensorPrivacyManager sensorPrivacyManager,
            CarDeviceProvisionedController carDeviceProvisionedController) {
        mContext = context;
        mPrivacyItemController = privacyItemController;
        mCarServiceProvider = carServiceProvider;
        mBroadcastDispatcher = broadcastDispatcher;
        mSensorPrivacyManager = sensorPrivacyManager;
        mCarDeviceProvisionedController = carDeviceProvisionedController;

        mQsTileNotifyUpdateRunnable = () -> {
        };
        mIsMicPrivacyChipVisible = false;
        mCurrentUserId = carDeviceProvisionedController.getCurrentUser();
    }

    @Override
    public boolean isMicEnabled() {
        // We need to negate return of isSensorPrivacyEnabled since when it is {@code true} it
        // means microphone has been toggled off
        return !mSensorPrivacyManager.isSensorPrivacyEnabled(MICROPHONE, mCurrentUserId);
    }

    @Override
    public void toggleMic() {
        mSensorPrivacyManager.setSensorPrivacy(QS_TILE, MICROPHONE, isMicEnabled(), mCurrentUserId);
    }

    @Override
    public void setNotifyUpdateRunnable(Runnable runnable) {
        mQsTileNotifyUpdateRunnable = runnable;
    }

    private boolean isMicPartOfPrivacyItems(@NonNull List<PrivacyItem> privacyItems) {
        Optional<PrivacyItem> optionalMicPrivacyItem = privacyItems.stream()
                .filter(privacyItem -> privacyItem.getPrivacyType()
                        .equals(PrivacyType.TYPE_MICROPHONE))
                .findAny();
        return optionalMicPrivacyItem.isPresent();
    }

    /**
     * Finds the {@link OngoingPrivacyChip} and sets relevant callbacks.
     */
    public void addPrivacyChipView(View view) {
        if (mPrivacyChip != null) {
            return;
        }

        mPrivacyChip = view.findViewById(R.id.privacy_chip);
        if (mPrivacyChip == null) return;

        mAllIndicatorsEnabled = mPrivacyItemController.getAllIndicatorsAvailable();
        mMicCameraIndicatorsEnabled = mPrivacyItemController.getMicCameraAvailable();
        mPrivacyItemController.addCallback(mPicCallback);
        registerForUserChangeEvents();
        setUser(mCarDeviceProvisionedController.getCurrentUser());
    }

    /**
     * Cleans up the controller and removes callbacks.
     */
    public void removeAll() {
        if (mPrivacyChip != null) {
            mPrivacyChip.setOnClickListener(null);
        }

        mPrivacyItemController.removeCallback(mPicCallback);
        mBroadcastDispatcher.unregisterReceiver(mUserUpdateReceiver);
        mBroadcastDispatcher.unregisterReceiver(mUserChangeReceiver);
        mSensorPrivacyManager.removeSensorPrivacyListener(MICROPHONE,
                mOnSensorPrivacyChangedListener);
        mPrivacyChip = null;
    }

    private void setChipVisibility(boolean chipVisible) {
        if (mPrivacyChip == null) {
            return;
        }

        // Since this is launched using a callback thread, its UI based elements need
        // to execute on main executor.
        mContext.getMainExecutor().execute(() -> {
            if (chipVisible && getChipEnabled()) {
                mPrivacyChip.animateIn();
            } else {
                mPrivacyChip.animateOut();
            }
        });
    }

    private boolean getChipEnabled() {
        return mMicCameraIndicatorsEnabled || mAllIndicatorsEnabled;
    }

    private void registerForUserChangeEvents() {
        // Register for user switching
        IntentFilter userChangeFilter = new IntentFilter(Intent.ACTION_USER_FOREGROUND);
        mBroadcastDispatcher.registerReceiver(mUserChangeReceiver, userChangeFilter,
                /* executor= */ null, UserHandle.ALL);

        // Also register for user info changing
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        mBroadcastDispatcher.registerReceiver(mUserUpdateReceiver, filter, /* executor= */ null,
                UserHandle.ALL);
    }

    @AnyThread
    private void setUser(int userId) {
        if (DEBUG) Log.d(TAG, "New user ID: " + userId);

        mCurrentUserId = userId;

        mSensorPrivacyManager.removeSensorPrivacyListener(MICROPHONE,
                mOnSensorPrivacyChangedListener);
        mSensorPrivacyManager.addSensorPrivacyListener(MICROPHONE, userId,
                mOnSensorPrivacyChangedListener);

        // Since this can be launched using a callback thread, its UI based elements need
        // to execute on main executor.
        mContext.getMainExecutor().execute(() -> {
            mPrivacyChip.setMicrophoneEnabled(isMicEnabled());
        });
    }
}
