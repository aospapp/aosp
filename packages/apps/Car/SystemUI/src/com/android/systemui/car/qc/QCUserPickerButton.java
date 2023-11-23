/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.car.qc;

import static com.android.systemui.car.users.CarSystemUIUserUtil.getCurrentUserHandle;

import android.car.Car;
import android.car.app.CarActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.UserManager;
import android.util.AttributeSet;
import android.widget.ImageView;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.R;
import com.android.systemui.car.statusbar.UserNameViewController;
import com.android.systemui.car.userswitcher.UserIconProvider;

/**
 * One of {@link QCFooterButtonView} for quick control panels, which shows user information
 * and opens the user picker.
 */

public class QCUserPickerButton extends QCFooterButtonView {
    private static final String TAG = QCUserPickerButton.class.getSimpleName();

    private final Context mContext;
    private CarActivityManager mCarActivityManager;
    private UserIconProvider mUserIconProvider;
    private UserManager mUserManager;
    @VisibleForTesting
    UserNameViewController mUserNameViewController;

    public QCUserPickerButton(Context context) {
        this(context, null);
    }

    public QCUserPickerButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QCUserPickerButton(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public QCUserPickerButton(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mContext = context;
        mUserManager = mContext.getSystemService(UserManager.class);

        mCarServiceLifecycleListener = (car, ready) -> {
            if (!ready) {
                return;
            }
            mCarActivityManager = (CarActivityManager) car.getCarManager(
                    Car.CAR_ACTIVITY_SERVICE);
        };

        Car.createCar(mContext, /* handler= */ null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                mCarServiceLifecycleListener);

        mOnClickListener = v -> openUserPicker();
        setOnClickListener(mOnClickListener);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mUserTracker == null) {
            return;
        }

        ImageView userIconView = findViewById(R.id.user_icon);
        if (userIconView != null) {
            // Set user icon as the first letter of the user name.
            mUserIconProvider = new UserIconProvider();
            Drawable circleIcon = mUserIconProvider.getRoundedUserIcon(
                    mUserTracker.getUserInfo(), mContext);
            userIconView.setImageDrawable(circleIcon);
        }

        if (mBroadcastDispatcher != null) {
            mUserNameViewController = new UserNameViewController(
                    mContext, mUserTracker, mUserManager, mBroadcastDispatcher);
            mUserNameViewController.addUserNameView(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mUserNameViewController != null) {
            mUserNameViewController.removeUserNameView(this);
        }
    }

    private void openUserPicker() {
        mContext.sendBroadcastAsUser(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                getCurrentUserHandle(mContext, mUserTracker));
        if (mCarActivityManager != null) {
            mCarActivityManager.startUserPickerOnDisplay(mContext.getDisplayId());
        }
    }
}
